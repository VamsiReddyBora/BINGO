package com.bingo.multiplayer.domain.network

import org.junit.Assert.*
import org.junit.Test
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EmqxConnectivityRegressionTest {
    @Test fun countdownUsesFullThreeTwoOneIntervals() {
        val deadline = 13_300L
        assertEquals(3, LobbyStartSchedule.secondsUntil(deadline, 10_301L))
        assertEquals(3, LobbyStartSchedule.secondsUntil(deadline, 11_299L))
        assertEquals(2, LobbyStartSchedule.secondsUntil(deadline, 11_300L))
        assertEquals(2, LobbyStartSchedule.secondsUntil(deadline, 12_299L))
        assertEquals(1, LobbyStartSchedule.secondsUntil(deadline, 12_300L))
        assertEquals(1, LobbyStartSchedule.secondsUntil(deadline, 13_299L))
        assertEquals(0, LobbyStartSchedule.secondsUntil(deadline, 13_300L))
    }
    @Test fun onlyFreshOnlineFriendsAreAvailableAndAiIsBusy() {
        val now = System.currentTimeMillis()
        assertEquals(FriendPlayAvailability.AVAILABLE, FriendPlayAvailability.of(PlayerPresence("friend", "ONLINE", now), now))
        for (detail in listOf("vs AI (Easy)", "vs AI (Master)", "Online Match"))
            assertEquals(FriendPlayAvailability.BUSY, FriendPlayAvailability.of(PlayerPresence("friend", "PLAYING", now, activityDetail = detail), now))
        assertEquals(FriendPlayAvailability.IN_LOBBY, FriendPlayAvailability.of(PlayerPresence("friend", "IN_LOBBY", now, currentRoomCode = "ABC123"), now))
        assertEquals(FriendPlayAvailability.UNKNOWN, FriendPlayAvailability.of(PlayerPresence("friend", "PLAYING", now - 15_000L), now))
        assertEquals(FriendPlayAvailability.UNKNOWN, FriendPlayAvailability.of(null, now))
    }

    @Test fun productionScheduleRunsOnceWithPhoneClockSkewAndCancelsOnDisconnect() = runTest {
        val seen = mutableListOf<Int>()
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = 4000L,
            startClockOffsets = mapOf("guest" to 60_000L))
        val started = LobbyStartSchedule.awaitStart(packet, "guest", { true }, { seen.add(it) },
            wallNow = { 61_000L + testScheduler.currentTime }, monotonicNow = { testScheduler.currentTime * 1_000_000L })
        assertTrue(started)
        assertEquals(listOf(3, 2, 1), seen)
        var ticks = 0
        val cancelled = LobbyStartSchedule.awaitStart(packet.copy(startCommitDeadline = 7000L), "guest",
            valid = { ticks < 2 }, display = { ticks++ },
            wallNow = { 61_000L + testScheduler.currentTime }, monotonicNow = { testScheduler.currentTime * 1_000_000L })
        assertFalse(cancelled)
        assertEquals(2, ticks)
    }

    @Test fun hostMetadataFinalizationCannotConsumeItsThreeTwoOneCountdown() = runTest {
        val seen = mutableListOf<Int>()
        var committed = false
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = 3300L)
        val started = LobbyStartSchedule.awaitHostStart(packet, "host",
            finalize = { delay(1400L); committed = true; true },
            valid = { true }, display = { seen.add(it) },
            wallNow = { testScheduler.currentTime }, monotonicNow = { testScheduler.currentTime * 1_000_000L })
        assertTrue(started && committed)
        assertEquals(listOf(3, 2, 1), seen)
        assertEquals(3300L, testScheduler.currentTime)
    }

    @Test fun notificationRoundTripPreservesExactLobbyIncarnationAndSender() {
        val invite = GameInvite("kevin", "Kevin", roomCode = "ABC123", senderUid = "host",
            recipientUid = "guest", recipientUsername = "stuart", roomSessionId = "exact-lobby-session")
        assertEquals(invite, invite.toEvent().toInvite())
    }

    @Test fun registryGuestJoinUsesHostWriterWithoutChangingTheHostRecord() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val previous = RoomMetadataApi.transportOverride
        val code = "RJ0123"
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host.copy(lastSeenTimestamp = 100L)))
            val original = hs.read(code).getOrThrow()!!
            RoomMetadataApi.transportOverride = gs
            val joined = OnlineRoomRegistry.validateAndJoinRoom(code, guest, original.sessionId, host.id)
            assertTrue("The actual registry guest-join transform must be admitted: $joined", joined is RoomJoinResult.Success)
            val stored = hs.read(code).getOrThrow()!!
            assertEquals(original.players.single(), stored.players.first { it.id == host.id })
            assertEquals(setOf(host.id, guest.id), stored.players.map { it.id }.toSet())
        } finally {
            RoomMetadataApi.transportOverride = previous; hs.close(); gs.close()
            OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun metadataFollowsAcceptedGameplayHostAfterIntentionalGroupExit() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = OnlineRoomSyncManager(scope)
        val previous = RoomMetadataApi.transportOverride
        val third = Player("third", "Third", "third")
        val code = "AH0123"
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + guest) }
            val thirdStore = MqttRoomMetadataTransport(wire, { third.id })
            try { assertTrue(RoomMetadataCoordinator(thirdStore).mutate(code) { it.copy(players = it.players + third) } is MutationResult.Applied) }
            finally { thirdStore.close() }
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "PLAYING", seed = 99L) is MutationResult.Applied)
            val started = hs.read(code).getOrThrow()!!
            // Bind the real manager without starting a network connection. Gameplay invokes
            // updateMatchRoster only after its reducer accepts permanent surrender (also on recovery).
            sync.javaClass.getDeclaredField("currentRoomCode").apply { isAccessible = true }.set(sync, code)
            assertTrue(sync.applyRoomMetadata(started, code))
            sync.bindPeerMatch(99L)
            sync.updateMatchRoster(listOf(guest.copy(isHost = true), third))
            RoomMetadataApi.transportOverride = gs
            val transferred = OnlineRoomRegistry.updateRoomStatus(code, "PLAYING", seed = 99L,
                administrativeHost = guest, departedIds = setOf(host.id), expectedSeed = 99L)
            assertTrue("The accepted gameplay administrative host must own metadata without the departed host: $transferred", transferred is MutationResult.Applied)
            assertEquals(guest.id, gs.read(code).getOrThrow()!!.hostId)
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "WAITING", expectedSeed = 99L) is MutationResult.Applied)
            RoomMetadataApi.transportOverride = hs
            assertTrue("The former host must no longer close the lobby", OnlineRoomRegistry.updateRoomStatus(code, "CLOSED") !is MutationResult.Applied)
        } finally {
            RoomMetadataApi.transportOverride = previous; hs.close(); gs.close(); scope.cancel()
            OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun readinessVersionChangeCannotReusePreparedSchedule() {
        val h = LobbyStartBarrier(); val g = LobbyStartBarrier(); val p = prepare()
        h.begin(p)
        val sent = h.preparation(1000L)!!
        val ack = g.acceptPreparation(sent, guest.id, host.id, listOf(host, guest), true, 1020L)!!
        assertTrue(h.acknowledge(ack, 1040L))
        val start = h.schedule(p.copy(type = "START_GAME", startCommitDeadline = 5000L))!!
        val rereadied = listOf(host, guest.copy(readyVersion = guest.readyVersion + 2L))
        assertFalse("A changed readiness version requires a new preparation epoch",
            g.acceptsSchedule(start, guest.id, host.id, rereadied))
    }

    @Test fun cancelledStartEpochCannotBeRepublishedAsStartingAfterReset() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val previous = RoomMetadataApi.transportOverride
        val code = "CE0123"
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            val start = RoomMessagePacket("START_GAME", roomCode = code, playerId = host.id, senderPlayerId = host.id,
                seed = 99L, requestId = "cancelled-epoch", players = listOf(host))
            val payload = FastPacketCodec.encode(start)
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "STARTING", seed = 99L,
                expectedSeed = 0L, canonicalStartPacketJson = payload) is MutationResult.Applied)
            assertTrue(OnlineRoomRegistry.cancelScheduledStart(code, 99L, start.requestId))
            assertEquals("WAITING", hs.read(code).getOrThrow()!!.status)
            val late = OnlineRoomRegistry.updateRoomStatus(code, "STARTING", seed = 99L,
                expectedSeed = 0L, canonicalStartPacketJson = payload)
            assertTrue("A cancelled epoch cannot resurrect after seed resets to zero: $late", late is MutationResult.Rejected)
        } finally {
            RoomMetadataApi.transportOverride = previous; hs.close()
            OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun ordinaryLivePacketDoesNotEraseTheObservedPresenceSession() {
        val tracker = MatchReturnTracker()
        tracker.observe(99L, "host", "real-connection", "ONLINE")
        // Actual gameplay picks also call the legacy markPlayerOnline helper with no session.
        tracker.observe(99L, "host", "", "ONLINE")
        tracker.observe(99L, "host", "real-connection", "AWAY")
        assertTrue("A real absence must remain detectable after ordinary gameplay traffic",
            tracker.observe(99L, "host", "real-connection", "ONLINE"))
        assertFalse(tracker.observe(99L, "host", "real-connection", "ONLINE"))
    }

    @Test fun actualRequestPublisherListenerNeverRetainsOrStagesAndSuppressesDuplicates() = runBlocking {
        val wire = EmqxWireFixture()
        val recipient = "owned_request_recipient"
        val request = GameInvite("owned_sender", "Kevin", roomCode = "", senderUid = "sender",
            recipientUid = "recipient", recipientUsername = recipient, isPlayRequest = true,
            eventId = "owned-request-${java.util.UUID.randomUUID()}")
        val receipts = java.util.concurrent.CopyOnWriteArrayList<GameInvite>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { GameInviteManager.playRequestReceipts.collect { receipts.add(it) } }
        var listener = GameInviteManager.listen(recipient, wire) { fail("A request must not call the invitation callback") }
        val filter = "bingo/emqx7/social/request/$recipient/+"
        try {
            GameInviteManager.clearForegroundInvite()
            withTimeout(2000L) { while (wire.activeSubscriptions(filter) != 1) delay(10L) }
            repeat(2) { GameInviteManager.publishMqttInviteAwaited(recipient, request, wire) }
            withTimeout(2000L) { while (receipts.isEmpty()) delay(10L) }
            assertEquals(listOf(request), receipts.toList())
            assertTrue(wire.retained.isEmpty())
            assertNull(GameInviteManager.stagedInvite)
            listener.close()
            listener = GameInviteManager.listen(recipient, wire) { fail("No request reservation may become an invitation") }
            withTimeout(2000L) { while (wire.activeSubscriptions(filter) != 1) delay(10L) }
            wire.fence()
            assertEquals("Reconnect must not deliver a queued request", 1, receipts.size)
            assertTrue(wire.retained.isEmpty())
        } finally { listener.close(); collecting.cancelAndJoin(); GameInviteManager.onAccountChanged() }
    }

    @Test fun hostNeverEntersWhenFinalCommitIsUnfinishedOrRejectedAtDeadline() = runTest {
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = 3300L)
        assertFalse(LobbyStartSchedule.awaitHostStart(packet, "host", finalize = { delay(5000L); true },
            valid = { true }, display = {}, wallNow = { testScheduler.currentTime },
            monotonicNow = { testScheduler.currentTime * 1_000_000L }))
        assertEquals(3300L, testScheduler.currentTime)
        assertFalse(LobbyStartSchedule.awaitHostStart(packet.copy(startCommitDeadline = 6600L), "host", finalize = { delay(100L); false },
            valid = { true }, display = {}, wallNow = { testScheduler.currentTime },
            monotonicNow = { testScheduler.currentTime * 1_000_000L }))
    }

    @Test fun durableIntentionalExitRetiresFormerMetadataWriterBeforeHandoff() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val previous = RoomMetadataApi.transportOverride
        val previousPrefs = OngoingMatchStore.customPrefs
        val code = "RW0123"
        OngoingMatchStore.customPrefs = com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences()
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            assertTrue(RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "PLAYING", seed = 99L) is MutationResult.Applied)
            assertTrue(OngoingMatchStore.enqueueExit(null, PendingMatchExit(host.id, code,
                RoomMessagePacket("SURRENDER", roomCode = code, playerId = host.id, seed = 99L))))
            val revision = hs.read(code).getOrThrow()!!.revision
            val attempted = RoomMetadataCoordinator(gs).mutate(code) { room -> room.copy(players = room.players.map {
                if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY", readyVersion = 10L) else it
            }) }
            assertTrue("A departed process must stop writing before the new host publishes: $attempted", attempted is MutationResult.Unavailable)
            assertEquals(revision, hs.read(code).getOrThrow()!!.revision)
        } finally {
            RoomMetadataApi.transportOverride = previous; OngoingMatchStore.customPrefs = previousPrefs
            hs.close(); gs.close(); OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun lostCommitNotificationCannotLeaveAHealthyReaderOnAnOldRoster() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        try {
            assertTrue(RoomMetadataCoordinator(hs).mutate("ABC123", initial =
                OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            val old = gs.read("ABC123").getOrThrow()!!
            wire.beforePublish = { topic, _ -> if (topic.endsWith("/committed")) throw java.io.IOException("Lost commit notification") }
            val changed = RoomMetadataCoordinator(hs).mutate("ABC123") { it.copy(boardSize = 6) } as MutationResult.Applied
            assertTrue(changed.session.revision > old.revision)
            assertEquals("An authoritative read must refresh retained state even when the live commit notification was lost",
                changed.session, gs.read("ABC123").getOrThrow())
        } finally { hs.close(); gs.close() }
    }

    @Test fun missingRetainedStorageDoesNotInvalidateAnActiveHost() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        try {
            assertTrue(RoomMetadataCoordinator(hs).mutate("ABC123", initial =
                OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            wire.retained.clear()
            assertEquals("An active host remains authoritative when its optional cache disappears", host.id, hs.read("ABC123").getOrThrow()!!.hostId)
        } finally { hs.close() }
    }

    @Test fun committedEntryVersionDoesNotCancelPeersFinishingTheSameCountdown() {
        val h = LobbyStartBarrier(); val p = prepare(); h.begin(p)
        val sent = h.preparation(1000L)!!
        val g = LobbyStartBarrier()
        val ack = g.acceptPreparation(sent, guest.id, host.id, listOf(host, guest), true, 1020L)!!
        assertTrue(h.acknowledge(ack, 1040L))
        val start = h.schedule(p.copy(type = "START_GAME", startCommitDeadline = 5000L))!!
        assertTrue(h.acknowledgeScheduled(ack.copy(heartbeatAction = "SCHEDULED")))
        assertTrue(h.commitSchedule())
        assertTrue(g.observeCommit(start.copy(startCommitted = true)))
        val entered = listOf(host, guest.copy(lobbyReadyStatus = "IN_GAME", readyVersion = guest.readyVersion + 1L))
        assertTrue("The peer's automatic entry must not cancel the shared countdown on a slightly later phone", h.rosterStillPrepared(entered))
        assertTrue(g.rosterStillPrepared(entered))
    }

    @Test fun preparationCommitCannotEnterBeforeTheDurableRosterRelease() {
        val h = LobbyStartBarrier(); val g = LobbyStartBarrier(); val p = prepare()
        h.begin(p)
        val sent = h.preparation(1000L)!!
        val ack = g.acceptPreparation(sent, guest.id, host.id, listOf(host, guest), true, 1020L)!!
        assertTrue(h.acknowledge(ack, 1040L))
        val start = h.schedule(p.copy(type = "START_GAME", startCommitDeadline = 5000L))!!
        assertTrue(g.observeCommit(start.copy(startCommitted = true)))
        assertFalse("Preparation commit is not permission to enter before the final roster is durable", g.canEnter)
        assertTrue(h.acknowledgeScheduled(ack.copy(heartbeatAction = "SCHEDULED")))
        assertTrue(h.commitSchedule())
        assertFalse(h.acknowledgeScheduled(ack.copy(heartbeatAction = "RELEASED")))
        assertTrue(h.acknowledgeScheduled(ack.copy(heartbeatAction = "COMMITTED")))
        assertTrue(h.releaseSchedule())
        assertTrue(g.observeCommit(start.copy(startCommitted = true, startReleased = true)))
        assertTrue(g.canEnter)
        assertFalse(h.acknowledgeScheduled(ack.copy(heartbeatAction = "COMMITTED")))
        assertTrue(h.acknowledgeScheduled(ack.copy(heartbeatAction = "RELEASED")))
        assertTrue(h.scheduledFresh())
        assertFalse(LobbyStartSchedule.canArm(start, guest.id, now = 5000L))
        val stale = start.copy(requestId = "stale-epoch")
        assertFalse(g.isCurrentEpoch(stale))
        g.cancel(stale)
        assertTrue(g.isCurrentEpoch(start))
        assertTrue(g.canEnter)
    }

    @Test fun durableReleasedStartCannotBeResetByALatePreparationCancellation() = runBlocking {
        val wire = EmqxWireFixture(); val hs = MqttRoomMetadataTransport(wire, { host.id })
        val previous = RoomMetadataApi.transportOverride; val code = "DR0123"
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            val start = RoomMessagePacket("START_GAME", roomCode = code, playerId = host.id, senderPlayerId = host.id,
                seed = 99L, requestId = "durable-release", players = listOf(host), startCommitted = true, startReleased = true)
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "PLAYING", seed = 99L,
                expectedSeed = 0L, canonicalStartPacketJson = FastPacketCodec.encode(start)) is MutationResult.Applied)
            assertFalse("A start released to the complete roster remains an existing recoverable match",
                OnlineRoomRegistry.cancelScheduledStart(code, 99L, start.requestId))
            assertEquals("PLAYING", hs.read(code).getOrThrow()!!.status)
            assertEquals(99L, hs.read(code).getOrThrow()!!.currentSeed)
        } finally {
            RoomMetadataApi.transportOverride = previous; hs.close()
            OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    private val host = Player("host", "Kevin", "kevin", isHost = true, lobbyReadyStatus = "READY", readyVersion = 1L)
    private val guest = Player("guest", "Stuart", "stuart", lobbyReadyStatus = "READY", readyVersion = 2L)
    private fun prepare() = RoomMessagePacket("PREPARE_START", playerId = host.id, senderPlayerId = host.id,
        roomCode = "ABC123", seed = 99L, connectionGeneration = "host-session", requestId = "epoch-one", players = listOf(host, guest))

    @Test fun realBarrierRequiresCompleteRosterExactEpochAndFinalReceipt() {
        val h = LobbyStartBarrier(); val g = LobbyStartBarrier(); val p = prepare()
        h.begin(p)
        val sent = h.preparation(1000L)!!
        assertNull(g.acceptPreparation(sent, guest.id, host.id, listOf(host), true, 71_020L))
        val ack = g.acceptPreparation(sent, guest.id, host.id, listOf(host, guest), true, 71_020L)!!
        assertFalse(h.acknowledge(ack.copy(connectionGeneration = "stale"), 1040L))
        assertFalse(h.acknowledge(ack.copy(senderPlayerId = "forged"), 1040L))
        assertTrue(h.acknowledge(ack, 1040L)); assertTrue(h.complete)
        val start = h.schedule(p.copy(type = "START_GAME", startCommitDeadline = 5000L))!!
        assertEquals(70_000L, start.startClockOffsets[guest.id])
        assertTrue(g.acceptsSchedule(start, guest.id, host.id, listOf(host, guest)))
        assertFalse(h.scheduledComplete)
        assertFalse(h.acknowledgeScheduled(ack))
        assertTrue(h.acknowledgeScheduled(ack.copy(heartbeatAction = "SCHEDULED"), 100L))
        assertTrue(h.scheduledFresh(200L))
        assertFalse(h.scheduledFresh(600_000_101L))
        g.cancel(start.copy(type = "CANCEL_START"))
        assertFalse(g.acceptsSchedule(start, guest.id, host.id, listOf(host, guest)))
    }

    @Test fun readinessChangeAndOldEpochNeverArmScheduledStart() {
        val h = LobbyStartBarrier(); val p = prepare(); h.begin(p)
        val g = LobbyStartBarrier()
        assertNull(g.acceptPreparation(p, guest.id, host.id, listOf(host, guest.copy(lobbyReadyStatus = "NOT_READY")), true, 1L))
        assertNull(h.schedule(p.copy(type = "START_GAME")))
        assertNull(g.acceptPreparation(p, guest.id, host.id, listOf(host, guest), false, 1L))
    }

    @Test fun initialPresenceAndFreshMatchDoNotAnnounceHostReturn() {
        val tracker = MatchReturnTracker()
        assertFalse(tracker.rejoin(1L, host.id))
        assertFalse(tracker.observe(1L, host.id, "old", "OFFLINE", false))
        assertFalse(tracker.observe(1L, host.id, "session", "ONLINE"))
        assertFalse(tracker.observe(1L, host.id, "session", "ONLINE"))
        assertFalse(tracker.observe(1L, host.id, "old", "OFFLINE"))
        assertFalse(tracker.rejoin(1L, host.id))
        tracker.observe(1L, host.id, "session", "OFFLINE")
        assertTrue(tracker.observe(1L, host.id, "new-session", "ONLINE"))
        assertFalse(tracker.observe(1L, host.id, "new-session", "ONLINE"))
        tracker.observe(1L, host.id, "new-session", "AWAY")
        assertFalse(tracker.rejoin(2L, host.id))
        assertFalse(tracker.observe(2L, host.id, "new-session", "ONLINE"))
    }

    @Test fun productionMqttAuthoritySerializesIndependentGuestUpdatesAndRetainsClosure() = runBlocking {
        val wire = EmqxWireFixture()
        val hostStore = MqttRoomMetadataTransport(wire, { host.id })
        val guestStore = MqttRoomMetadataTransport(wire, { guest.id })
        val h = RoomMetadataCoordinator(hostStore)
        val g = RoomMetadataCoordinator(guestStore)
        val initial = OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))
        assertTrue(h.mutate("ABC123", initial = initial) { it } is MutationResult.Applied)
        assertTrue(g.mutate("ABC123") { it.copy(players = it.players + guest) } is MutationResult.Applied)
        val before = hostStore.read("ABC123").getOrThrow()!!
        assertEquals(2, before.players.size)
        assertTrue(h.mutate("ABC123", expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 99L) } is MutationResult.Applied)
        // A stale full WAITING snapshot is a conditional conflict, not an unconditional overwrite.
        assertTrue(guestStore.commit("ABC123", before, before.copy(players = before.players.map {
            if (it.id == guest.id) it.copy(lobbyReadyStatus = "NOT_READY", readyVersion = 3L) else it
        })) is RoomMetadataCommit.Conflict)
        assertEquals("PLAYING", guestStore.read("ABC123").getOrThrow()!!.status)
        assertTrue(h.mutate("ABC123", expectedSeed = 99L) { it.copy(status = "CLOSED") } is MutationResult.Applied)
        assertTrue(g.mutate("ABC123") { it.copy(status = "WAITING") } is MutationResult.Rejected)
        val cold = MqttRoomMetadataTransport(wire, { "newcomer" }).read("ABC123").getOrThrow()!!
        assertEquals("CLOSED", cold.status); assertTrue(cold.isTerminalClosed)
        assertEquals(99L, cold.currentSeed)
        assertTrue(wire.published.all { it.startsWith("bingo/emqx7/") })
    }

    @Test fun guestCannotForgeHostOrAnotherParticipantAndOutageHasNoFallback() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id }); val gs = MqttRoomMetadataTransport(wire, { guest.id })
        RoomMetadataCoordinator(hs).mutate("ABC123", initial = OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host, guest))) { it }
        // Creation requires a host-only initial roster.
        assertNull(hs.read("ABC123").getOrThrow())
        RoomMetadataCoordinator(hs).mutate("ABC123", initial = OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))) { it }
        RoomMetadataCoordinator(gs).mutate("ABC123") { it.copy(players = it.players + guest) }
        assertTrue(RoomMetadataCoordinator(gs).mutate("ABC123") { it.copy(hostId = guest.id) } is MutationResult.Rejected)
        assertTrue(RoomMetadataCoordinator(gs).mutate("ABC123") { it.copy(players = it.players.map { p -> if (p.id == host.id) p.copy(displayName = "forged") else p }) } is MutationResult.Rejected)
        val count = wire.published.size
        wire.isHealthy = false
        assertTrue(RoomMetadataCoordinator(gs).mutate("ABC123") { it.copy(status = "CLOSED") } is MutationResult.Unavailable)
        assertEquals(count, wire.published.size)
    }

    @Test fun mqttPubackCanAdvertiseALiveLobbyWithoutRetainedStorage() = runBlocking {
        val wire = EmqxWireFixture()
        wire.afterPublish = { topic, _ -> if (topic.endsWith("/state")) wire.retained.remove(topic) }
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val result = RoomMetadataCoordinator(transport).mutate("ABC123", initial =
            OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))) { it }
        assertTrue("A live lobby does not require broker storage readback", result is MutationResult.Applied)
        transport.close()
    }

    @Test fun retainedMetadataHandlerRejectsStaleClosureAndDifferentIncarnation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = OnlineRoomSyncManager(scope)
        val room = OnlineRoomSession("HANDLER", host.id, host.username, host.displayName, players = listOf(host, guest),
            metadataProtocolVersion = 7, sessionId = "one", revision = 2L)
        try {
            assertTrue(sync.applyRoomMetadata(room, "HANDLER"))
            assertEquals(2, sync.players.value.size)
            assertFalse(sync.applyRoomMetadata(room.copy(status = "CLOSED", revision = 1L), "HANDLER"))
            assertEquals(2, sync.players.value.size)
            assertFalse(sync.applyRoomMetadata(room.copy(sessionId = "foreign", revision = 3L), "HANDLER"))
            assertEquals(host.id, sync.currentHostId)
        } finally {
            scope.cancel()
            OnlineRoomRegistry.recentRoomCache.remove("HANDLER")
        }
    }

    @Test fun closureRetryUsesOneProductionJobPerRoomAndGeneration() = runBlocking {
        val code = "COALESCED_CLOSURE"
        val previous = RoomMetadataApi.transportOverride
        val reads = java.util.concurrent.atomic.AtomicInteger()
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> { reads.incrementAndGet(); return Result.failure(java.io.IOException("Controlled outage")) }
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit = error("No write during outage")
        }
        OnlineRoomRegistry.recentRoomCache[code] = OnlineRoomRegistry.CachedRoom(
            OnlineRoomSession(code, host.id, host.username, host.displayName, currentSeed = 99L), System.currentTimeMillis())
        try {
            repeat(10) { OnlineRoomRegistry.queueDurableClosureRetry(code, 99L) }
            delay(100L)
            assertEquals(1, reads.get())
            assertEquals(1, OnlineRoomRegistry.activeClosureRetryCount(code))
            OnlineRoomRegistry.pendingClosures.remove(code)
            delay(600L)
            assertEquals(0, OnlineRoomRegistry.activeClosureRetryCount(code))
        } finally {
            OnlineRoomRegistry.pendingClosures.remove(code); OnlineRoomRegistry.recentRoomCache.remove(code)
            RoomMetadataApi.transportOverride = previous
        }
    }

    @Test fun remoteProposalCannotClaimHostIdentityForPrivilegedMutation() = runBlocking {
        val wire = EmqxWireFixture()
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val initial = OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))
        assertTrue(RoomMetadataCoordinator(transport).mutate("ABC123", initial = initial) { it } is MutationResult.Applied)
        val base = transport.read("ABC123").getOrThrow()!!
        val json = Json { encodeDefaults = true }
        val reply = CompletableDeferred<String>()
        val response = "bingo/emqx7/metadata/reply/owned-forgery-test"
        val listener = wire.subscribe(response) { bytes, _ -> reply.complete(bytes.toString(Charsets.UTF_8)) }
        try {
            val body = """{"id":"forged","actor":"${host.id}","reply":"$response","sentAt":${System.currentTimeMillis()},"base":${json.encodeToString(base)},"next":${json.encodeToString(base.copy(status = "CLOSED"))}}"""
            wire.publish("bingo/emqx7/metadata/ABC123/command", body.toByteArray())
            val result = withTimeout(2000L) { reply.await() }
            assertTrue("Remote host impersonation must be rejected by the actual command handler: $result", result.contains("rejected"))
            assertEquals("WAITING", transport.read("ABC123").getOrThrow()!!.status)
        } finally { listener.close(); transport.close() }
    }

    @Test fun tentativeLiveRoomWriteCannotReplacePublicCommittedCache() = runBlocking {
        val code = "TENTATIVE"
        val previous = RoomMetadataApi.transportOverride
        val wire = EmqxWireFixture()
        val transport = MqttRoomMetadataTransport(wire, { host.id }, OnlineRoomRegistry::rememberCanonicalRoom)
        RoomMetadataApi.transportOverride = transport
        try {
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            wire.afterPublish = { topic, _ -> if (topic.endsWith("/state")) wire.retained.remove(topic) }
            val result = OnlineRoomRegistry.updateRoomStatus(code, "STARTING", seed = 99L)
            assertTrue(result is MutationResult.Unavailable)
            assertEquals("A live tentative echo cannot advance the public committed seed", 0L,
                OnlineRoomRegistry.getRoomCachedOnly(code)?.currentSeed)
        } finally {
            RoomMetadataApi.transportOverride = previous; transport.close()
            OnlineRoomRegistry.recentRoomCache.remove(code); OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun guestClockSkewDoesNotPreventAcknowledgedLobbyAdmission() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id }, wallNow = { System.currentTimeMillis() + 60_000L })
        try {
            val created = RoomMetadataCoordinator(hs).mutate("ABC123", initial =
                OnlineRoomSession("ABC123", host.id, host.username, host.displayName, players = listOf(host))) { it }
            assertTrue(created is MutationResult.Applied)
            val joined = RoomMetadataCoordinator(gs).mutate("ABC123") { it.copy(players = it.players + guest) }
            assertTrue("Live sender freshness must use a challenge, not equal phone wall clocks", joined is MutationResult.Applied)
            assertEquals(2, hs.read("ABC123").getOrThrow()!!.players.size)
        } finally { hs.close(); gs.close() }
    }

    @Test fun scheduledGuestStartsWithFullThirtySecondsOnItsOwnClock() {
        val hostNow = 10_000L
        val start = RoomMessagePacket("START_GAME", playerId = host.id, senderPlayerId = host.id, seed = 99L,
            startClockOffsets = mapOf(host.id to 0L, guest.id to 60_000L), startCommitDeadline = hostNow,
            turnClock = MultiplayerTurnClock.start(1, host.id, hostNow))
        val received = MatchPacketClock.ingress(start, 0L, guest.id, 0L)
        assertEquals(30_000L, received.turnClock!!.remainingMillis(hostNow + 60_000L))
        assertEquals(start.startCommitDeadline, received.startCommitDeadline)
        assertEquals(received, MatchPacketClock.ingress(received, 60_000L, guest.id, 99L))
    }
    @Test fun calibratedPacketsPreserveAbsencePromotionFreezeAndCodec() {
        val now = 1_000_000L
        val absent = MultiplayerTurnClock.start(2, guest.id, now, true).welcomeBack(now + 1000L).freeze(now + 2000L)
        val raw = RoomMessagePacket("GAME_SYNC", playerId = guest.id, timestamp = now + 2000L,
            turnClock = absent, lastPlayerActivityAt = now, clockOffsetMillis = 60_000L,
            playerPresences = mapOf(guest.id to MultiplayerPresenceRecord("c", "i", 2L, now, "AWAY")))
        val local = MatchPacketClock.localize(raw, 0L)
        assertEquals(absent.frozenRemainingMillis, local.turnClock!!.frozenRemainingMillis)
        assertEquals(absent.initialBudgetMillis, local.turnClock!!.initialBudgetMillis)
        assertTrue(local.turnClock!!.welcomeBackGranted)
        assertEquals(local.turnClock, local.turnClock!!.welcomeBack(now))
        assertEquals(now - 60_000L, local.playerPresences.getValue(guest.id).observedAt)
        assertEquals(raw, FastPacketCodec.decode(FastPacketCodec.encode(raw)))
        assertEquals(raw, MatchPacketClock.localize(local, 60_000L))
    }

    @Test fun runningIngressAndColdRecoveryUseSameCalibratedTurnClock() {
        val now = 1_000_000L
        val players = listOf(host, guest)
        val engine = com.bingo.multiplayer.domain.engine.BingoEngine()
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
        val local = OngoingMatchData("ROOM", 42L, 5, participants = players, accountUid = guest.id,
            playerBoard = boards.getValue(guest.id), opponentBoard = boards.getValue(host.id), allPlayerBoards = boards,
            randomizedTurnOrder = players, currentTurnPlayerId = host.id, startedAt = now + 60_000L,
            lastUpdatedAt = now + 60_000L, lastPlayerActivityAt = now + 60_000L,
            turnClock = MultiplayerTurnClock.start(1, host.id, now + 60_000L), simplifiedRulesVersion = 1,
            clockOffsetMillis = 60_000L)
        val raw = RoomMessagePacket("GAME_SYNC", playerId = host.id, senderPlayerId = host.id,
            seed = 42L, roomCode = "ROOM", sequence = 1L, timestamp = now + 1000L, turnNumber = 1,
            currentTurnPlayerId = host.id, turnClock = MultiplayerTurnClock.start(1, host.id, now + 1000L),
            lastPlayerActivityAt = now + 1000L, simplifiedRulesVersion = 1)
        val running = MatchPacketClock.ingress(raw, 60_000L, guest.id, 42L)
        val cold = PeerMatchRecovery.merge(local, listOf(raw), now + 61_000L)
        assertEquals(running.turnClock, cold.turnClock)
        assertEquals(30_000L, cold.turnClock!!.remainingMillis(now + 61_000L))
        assertEquals(cold, PeerMatchRecovery.merge(cold, listOf(raw, running), now + 61_000L))
    }

    @Test fun concurrentReadbackDuringReplyDoesNotRejectPublicationAndGuestIsAdmitted() = runBlocking {
        val wire = EmqxWireFixture()
        val code = "RC0123"
        val stateTopic = "bingo/emqx7/metadata/$code/state"
        var readbackExecuted = false

        wire.beforePublish = { topic, _ ->
            if (topic.startsWith("bingo/emqx7/metadata/reply/")) {
                wire.readRetained(stateTopic)
                readbackExecuted = true
            }
        }

        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        try {
            val initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
            val created = RoomMetadataCoordinator(hs).mutate(code, initial = initial) { it }
            assertTrue("Host room creation must succeed: $created", created is MutationResult.Applied)

            val joined = RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + guest) }
            assertTrue("Guest must be admitted even when readback interleaves with reply: $joined", joined is MutationResult.Applied)
            assertTrue("Interleaved readback must have executed", readbackExecuted)
            val session = (joined as MutationResult.Applied).session
            assertEquals(2, session.players.size)
            assertTrue(session.players.any { it.id == guest.id })
        } finally {
            hs.close(); gs.close()
        }
    }

    @Test fun genuineDisconnectionDuringReplyFailsWithUsableError() = runBlocking {
        val wire = EmqxWireFixture()
        val code = "DC0123"
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        try {
            val initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
            val created = RoomMetadataCoordinator(hs).mutate(code, initial = initial) { it }
            assertTrue(created is MutationResult.Applied)

            wire.isHealthy = false
            val joined = RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + guest) }
            assertTrue("Disconnected wire must return Unavailable with actual cause: $joined", joined is MutationResult.Unavailable)
            assertEquals("Controlled EMQX outage", (joined as MutationResult.Unavailable).error.message)
        } finally {
            hs.close(); gs.close()
        }
    }

    @Test fun crossDeviceGuestWithAbsentEvidenceJoinsWithoutHostKeyBinding() = runBlocking {
        val wire = EmqxWireFixture()
        val code = "CD0123"
        val hostDir = java.io.File(System.getProperty("java.io.tmpdir"), "bingo_host_cd_${System.currentTimeMillis()}").apply { mkdirs() }
        val guestDir = java.io.File(System.getProperty("java.io.tmpdir"), "bingo_guest_cd_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            AccountKeyStore.clearCachesForTests()
            val (hostUid, hostKey) = AccountKeyStore.createGuestKeyPair(hostDir)
            val (guestUid, guestKey) = AccountKeyStore.createGuestKeyPair(guestDir)
            val crossHost = Player(hostUid, "Kevin", "kevin", isHost = true)
            val crossGuest = Player(guestUid, "Bora", "bora")

            AccountKeyStore.userKeyDirs[hostUid] = hostDir
            AccountKeyStore.userKeyDirs[guestUid] = guestDir

            val hs = MqttRoomMetadataTransport(wire, { crossHost.id })
            val gs = MqttRoomMetadataTransport(wire, { crossGuest.id })
            try {
                val initial = OnlineRoomSession(code, crossHost.id, crossHost.username, crossHost.displayName, players = listOf(crossHost))
                val created = RoomMetadataCoordinator(hs).mutate(code, initial = initial) { it }
                assertTrue("Host room creation must succeed: $created", created is MutationResult.Applied)

                val joined = RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + crossGuest) }
                assertTrue("Cross-device guest without pre-shared keys must be admitted: $joined", joined is MutationResult.Applied)
                val session = (joined as MutationResult.Applied).session
                assertEquals(2, session.players.size)
                assertTrue("Bora must be present in room players", session.players.any { it.id == crossGuest.id })

                val boundHostKey = AccountKeyStore.getEstablishedPublicKey(crossHost.id, local = crossGuest.id)
                assertNull("Admission must not create a custom host-key binding", boundHostKey)
            } finally {
                hs.close(); gs.close()
            }
        } finally {
            hostDir.deleteRecursively()
            guestDir.deleteRecursively()
        }
    }
}
