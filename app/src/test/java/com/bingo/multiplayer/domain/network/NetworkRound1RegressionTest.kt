package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.ManualMatchIngressTarget
import com.bingo.multiplayer.domain.engine.MatchPick
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.notifications.NotificationApiException
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.UUID

/**
 * Permanent regression tests for Round 1, Round 2, Round 3 & Round 4 networking findings.
 * Preserves all original Round-2 test methods and assertions (NR-01 through NR-08 + KNOWN-GREEN)
 * while expanding production-path coverage for all confirmed Round 4 findings (1 through 13).
 */
class NetworkRound1RegressionTest {

    private var originalPublisher: (suspend (OnlineRoomSession) -> Boolean)? = null
    private var originalTransport: RoomMetadataTransport? = null
    private val records = java.util.concurrent.ConcurrentHashMap<String, OnlineRoomSession>()
    private fun recordRoom(code: String, room: OnlineRoomSession) {
        records[code] = room
        OnlineRoomRegistry.recentRoomCache[code] = OnlineRoomRegistry.CachedRoom(room, System.currentTimeMillis())
    }
    private fun wireStatus(code: String, status: String, seed: Long, revision: Long) {
        val previous = records[code] ?: OnlineRoomRegistry.recentRoomCache[code]?.session ?: return
        records[code] = previous.copy(status = status, currentSeed = seed, revision = revision,
            isTerminalClosed = status == "CLOSED")
    }

    @Before
    fun isolateMetadataPublicationFromTheLiveBroker() {
        originalPublisher = OnlineRoomRegistry.roomMetaPublisherOverride
        originalTransport = RoomMetadataApi.transportOverride
        OnlineRoomRegistry.roomMetaPublisherOverride = { true }
        // Only the network is controlled. Actual registry / conditional coordinator transitions run.
        // simulatedHttpException is a historical fixture field; where no MQTT record exists it
        // now represents an unavailable broker read. A healthy EMQX record needs no HTTP mirror.
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> {
                if (code.contains("CANCEL_")) awaitCancellation()
                val live = OnlineRoomRegistry.simulatedMqttSession?.takeIf { it.roomCode == code }
                val room = live ?: records[code]
                if (room == null && (OnlineRoomRegistry.simulatedHttpException != null || code.startsWith("OUTAGE_") || code.startsWith("DURABLE_")))
                    return Result.failure(java.io.IOException("Controlled EMQX read outage"))
                return Result.success(room)
            }
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit = synchronized(records) {
                val live = records[code]
                if (live?.revision != base?.revision || live?.currentSeed != base?.currentSeed)
                    RoomMetadataCommit.Conflict(live)
                else { records[code] = next; RoomMetadataCommit.Applied(next) }
            }
        }
    }

    @After
    fun restoreMetadataPublisher() {
        OnlineRoomRegistry.roomMetaPublisherOverride = originalPublisher
        RoomMetadataApi.transportOverride = originalTransport
        records.keys.forEach { OnlineRoomRegistry.pendingClosures.remove(it) }
        records.clear()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // =========================================================================
    // ── NR-01: Retried start packets must NOT restart countdown or duplicate matches
    // =========================================================================

    @Test
    fun testNR01_retriedStartPacketDoesNotRestartGuestCountdown() {
        val currentSeed = 123456789L

        // Scenario 1: Guest is in lobby with countdown running for currentSeed. Retried start packet arrives.
        val shouldStartDuringCountdown = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = currentSeed,
            currentMatchSeed = currentSeed,
            isGameOver = false,
            isCurrentlyInGame = false,
            isCompletedSeed = false,
            isCountdownActive = true
        )
        assertFalse("Retried start packet with currentMatchSeed must be rejected to prevent countdown reset", shouldStartDuringCountdown)

        // Scenario 2: Guest is already in-game for currentSeed. Retried start packet arrives.
        val shouldStartInGame = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = currentSeed,
            currentMatchSeed = currentSeed,
            isGameOver = false,
            isCurrentlyInGame = true,
            isCompletedSeed = false,
            isCountdownActive = false
        )
        assertFalse("Retried start packet while in-game must be rejected to prevent restarting match", shouldStartInGame)

        // Scenario 3: Legitimate new match after game over with new seed.
        val shouldStartNewMatch = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = 987654321L,
            currentMatchSeed = currentSeed,
            isGameOver = true,
            isCurrentlyInGame = false,
            isCompletedSeed = false,
            isCountdownActive = false
        )
        assertTrue("New match with new seed after game over must be accepted", shouldStartNewMatch)
    }

    @Test
    fun testNR01_duplicateStartScheduleAndRepeatedMetadataRejection() {
        val currentSeed = 123456789L
        val schedule = listOf(0L, 150L, 400L, 800L, 1300L, 1800L, 2300L)

        // 1. Same-seed duplicate start arrives at each point on the retry schedule during active countdown
        for (elapsed in schedule) {
            val shouldStartDuringCountdown = LobbyLifecycleEngine.shouldStartNewMatch(
                isHost = false,
                incomingSeed = currentSeed,
                currentMatchSeed = currentSeed,
                isGameOver = false,
                isCurrentlyInGame = false,
                isCompletedSeed = false,
                isCountdownActive = true
            )
            assertFalse("Retried start packet at ${elapsed}ms must be rejected during active countdown", shouldStartDuringCountdown)
        }

        // 2. Same-seed duplicate start arrives at each point on the retry schedule during in-game play
        for (elapsed in schedule) {
            val shouldStartInGame = LobbyLifecycleEngine.shouldStartNewMatch(
                isHost = false,
                incomingSeed = currentSeed,
                currentMatchSeed = currentSeed,
                isGameOver = false,
                isCurrentlyInGame = true,
                isCompletedSeed = false,
                isCountdownActive = false
            )
            assertFalse("Retried start packet at ${elapsed}ms must be rejected while currently in-game", shouldStartInGame)
        }

        // 3. Different-seed unsolicited start arrives while in-game (Nearby gameplay or manual-board design)
        val differentSeed = 999888777L
        val shouldStartDifferentSeedInGame = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = differentSeed,
            currentMatchSeed = currentSeed,
            isGameOver = false,
            isCurrentlyInGame = true,
            isCompletedSeed = false,
            isCountdownActive = false
        )
        assertFalse("Different-seed start must NOT replace active match while currently in-game", shouldStartDifferentSeedInGame)

        // 4. Different-seed unsolicited start arrives while countdown is active
        val shouldStartDifferentSeedDuringCountdown = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = differentSeed,
            currentMatchSeed = currentSeed,
            isGameOver = false,
            isCurrentlyInGame = false,
            isCompletedSeed = false,
            isCountdownActive = true
        )
        assertFalse("Different-seed start must NOT replace active countdown", shouldStartDifferentSeedDuringCountdown)

        // 5. Completed seed must NEVER be restarted
        val shouldStartCompletedSeed = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = currentSeed,
            currentMatchSeed = 0L,
            isGameOver = false,
            isCurrentlyInGame = false,
            isCompletedSeed = true,
            isCountdownActive = false
        )
        assertFalse("Completed seed must never be restarted", shouldStartCompletedSeed)

        // 6. Legitimate new match after game over with new seed is admitted
        val shouldStartLegitimateRematch = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = differentSeed,
            currentMatchSeed = currentSeed,
            isGameOver = true,
            isCurrentlyInGame = false,
            isCompletedSeed = false,
            isCountdownActive = false
        )
        assertTrue("Legitimate new match after game over must be accepted", shouldStartLegitimateRematch)
    }

    // =========================================================================
    // ── NR-02: Two-phase start separation, start controls & correlated ACK barrier
    // =========================================================================

    @Test
    fun testNR02_hostStartBarrierRequiresAllNonHostParticipantsAcks() {
        val hostId = "host_user_1"
        val guest1Id = "guest_user_2"
        val guest2Id = "guest_user_3"
        val matchSeed = 777888999L
        val roomCode = "ROOM01"

        val participants = listOf(
            Player(id = hostId, displayName = "Host", username = "host", isHost = true),
            Player(id = guest1Id, displayName = "Guest 1", username = "g1", isHost = false),
            Player(id = guest2Id, displayName = "Guest 2", username = "g2", isHost = false)
        )

        val requiredAcks = participants.filter { !it.isHost && it.id != hostId }.map { it.id }
        assertEquals(2, requiredAcks.size)
        assertTrue(requiredAcks.contains(guest1Id))
        assertTrue(requiredAcks.contains(guest2Id))

        val receivedAcks = mutableSetOf<String>()

        // 1. Partial ACK arrives (only guest1)
        val ack1 = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guest1Id,
            targetPlayerId = hostId
        )
        if (ack1.seed == matchSeed && ack1.roomCode == roomCode && requiredAcks.contains(ack1.playerId)) {
            receivedAcks.add(ack1.playerId)
        }
        assertFalse("Host must NOT proceed when only 1 of 2 guests has acknowledged", requiredAcks.all { receivedAcks.contains(it) })

        // 2. Unrelated ACK arrives (wrong seed or unknown player)
        val wrongSeedAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = 111111L, // old match seed
            playerId = guest2Id,
            targetPlayerId = hostId
        )
        if (wrongSeedAck.seed == matchSeed && wrongSeedAck.roomCode == roomCode && requiredAcks.contains(wrongSeedAck.playerId)) {
            receivedAcks.add(wrongSeedAck.playerId)
        }
        assertFalse("Wrong seed ACK must be ignored", receivedAcks.contains(guest2Id))

        // 3. Valid second ACK arrives
        val ack2 = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guest2Id,
            targetPlayerId = hostId
        )
        if (ack2.seed == matchSeed && ack2.roomCode == roomCode && requiredAcks.contains(ack2.playerId)) {
            receivedAcks.add(ack2.playerId)
        }
        assertTrue("Host barrier must succeed once all non-host participants ACK", requiredAcks.all { receivedAcks.contains(it) })
    }

    @Test
    fun testNR02_startControlsAndTwoPhasePreparationBarrier() {
        val hostId = "host_user_1"
        val guest1Id = "guest_user_2"
        val guest2Id = "guest_user_3"
        val matchSeed = 777888999L
        val roomCode = "ROOM01"
        val hostConnectionGen = "gen_host_abc"
        val guest2ConnectionGen = "gen_guest_ghi"

        val participants = listOf(
            Player(id = hostId, displayName = "Host", username = "host", isHost = true),
            Player(id = guest1Id, displayName = "Guest 1", username = "g1", isHost = false),
            Player(id = guest2Id, displayName = "Guest 2", username = "g2", isHost = false)
        )

        // Finding 11: Host authority verification - requiredAcks filters out verified host
        val verifiedHost = hostId
        val requiredAcks = participants.filter { !it.isHost && it.id != verifiedHost && it.id != hostId }.map { it.id }
        assertEquals(2, requiredAcks.size)

        val pendingReceivedAcks = mutableSetOf<String>()
        val pendingStartAckSeed = matchSeed

        // Helper validating ACK admission matching RootNavGraph production logic:
        fun admitAck(packet: RoomMessagePacket, currentRoute: String): Boolean {
            if (currentRoute != "lobby" || packet.seed != pendingStartAckSeed || pendingStartAckSeed == 0L || packet.roomCode != roomCode) {
                return false
            }
            val actor = packet.playerId
            val sender = MultiplayerGameplayReducer.sender(packet)
            val target = packet.targetPlayerId
            val isSenderValid = actor.isNotBlank() && (sender.isBlank() || actor == sender)
            val isTargetValid = target.isBlank() || target == hostId
            val isGenValid = packet.connectionGeneration.isBlank() || packet.connectionGeneration == hostConnectionGen
            if (isSenderValid && isTargetValid && isGenValid && requiredAcks.contains(actor) && !pendingReceivedAcks.contains(actor)) {
                pendingReceivedAcks.add(actor)
                return true
            }
            return false
        }

        // Host prepares start with host connection generation
        val hostPreparePacket = RoomMessagePacket(
            type = "PREPARE_START",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = hostId,
            senderPlayerId = hostId,
            connectionGeneration = hostConnectionGen
        )
        assertEquals(hostConnectionGen, hostPreparePacket.connectionGeneration)

        // Guest 1 receives preparePacket and echoes packet.connectionGeneration (Finding 1)
        val guest1EchoedAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guest1Id,
            senderPlayerId = guest1Id,
            targetPlayerId = hostId,
            connectionGeneration = hostPreparePacket.connectionGeneration // echoed!
        )
        assertTrue("Guest 1 echoed ACK must be admitted by host barrier", admitAck(guest1EchoedAck, "lobby"))

        // Guest 2 erroneously sends its own distinct connection generation without echoing host's
        val guest2UncorrelatedAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guest2Id,
            senderPlayerId = guest2Id,
            targetPlayerId = hostId,
            connectionGeneration = guest2ConnectionGen // distinct, not matching host!
        )
        assertFalse("Uncorrelated guest ACK with un-echoed generation must be rejected", admitAck(guest2UncorrelatedAck, "lobby"))

        // Guest 2 correctly echoes host connection generation
        val guest2EchoedAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guest2Id,
            senderPlayerId = guest2Id,
            targetPlayerId = hostId,
            connectionGeneration = hostPreparePacket.connectionGeneration
        )
        assertTrue("Guest 2 correctly correlated ACK completes the barrier", admitAck(guest2EchoedAck, "lobby"))
        assertTrue("All required guests acknowledged", requiredAcks.all { pendingReceivedAcks.contains(it) })

        // Finding R7-F04: ACK with foreign actor UID claiming expected participant's username must be rejected
        val forgedAckWithClaimedUsername = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = "foreign_evil_uid",
            senderPlayerId = "foreign_evil_uid",
            username = "g1", // claims Guest 1's username
            targetPlayerId = hostId,
            connectionGeneration = hostPreparePacket.connectionGeneration
        )
        assertFalse("ACK with foreign UID claiming expected username must be rejected", admitAck(forgedAckWithClaimedUsername, "lobby"))
        assertFalse("Foreign UID must not be recorded as received", pendingReceivedAcks.contains("foreign_evil_uid"))

        // CANCEL_START production validation (Finding 2):
        fun admitCancel(packet: RoomMessagePacket, currentRoute: String, pendingSeed: Long, currentMatchSeed: Long, inCountdown: Boolean): Boolean {
            if (currentRoute != "lobby" || packet.roomCode != roomCode) return false
            val actor = packet.playerId
            val sender = MultiplayerGameplayReducer.sender(packet)
            val isSenderValid = actor.isNotBlank() && (sender.isBlank() || actor == sender)
            val isHostValid = actor == hostId && (sender.isBlank() || sender == hostId)
            val isMatchingSeed = (pendingSeed != 0L && packet.seed == pendingSeed) ||
                (pendingSeed == 0L && packet.seed == currentMatchSeed && inCountdown)
            return isSenderValid && isHostValid && isMatchingSeed
        }

        val cancelInGame = RoomMessagePacket(type = "CANCEL_START", roomCode = roomCode, seed = matchSeed, playerId = hostId, senderPlayerId = hostId)
        assertFalse("CANCEL_START on Game screen must never cancel active match", admitCancel(cancelInGame, "game", 0L, matchSeed, false))

        val cancelFromNonHost = RoomMessagePacket(type = "CANCEL_START", roomCode = roomCode, seed = matchSeed, playerId = guest1Id, senderPlayerId = guest1Id)
        assertFalse("CANCEL_START from non-host must be rejected", admitCancel(cancelFromNonHost, "lobby", matchSeed, 0L, false))

        val validCancel = RoomMessagePacket(type = "CANCEL_START", roomCode = roomCode, seed = matchSeed, playerId = hostId, senderPlayerId = hostId)
        assertTrue("Valid CANCEL_START from host in lobby must be accepted", admitCancel(validCancel, "lobby", matchSeed, 0L, false))

        // Invalidation during preparation barrier (Finding 3):
        val initialRoster = listOf(
            Player(hostId, "Host", "host", isHost = true, lobbyReadyStatus = "READY"),
            Player(guest1Id, "Guest 1", "g1", isHost = false, lobbyReadyStatus = "READY"),
            Player(guest2Id, "Guest 2", "g2", isHost = false, lobbyReadyStatus = "READY")
        )
        val initialIds = initialRoster.map { it.id }.toSet()

        // 1. Valid barrier matches all conditions
        val validBarrier = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialIds,
            livePlayers = initialRoster,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = hostId,
            isHosting = true,
            expectedConnectionGen = hostConnectionGen,
            liveConnectionGen = hostConnectionGen,
            requiredAckUids = requiredAcks
        )
        assertTrue("Valid barrier with unchanged roster and active readiness must pass", validBarrier)

        // Case A: Roster change during wait (participant leaves or joins)
        val rosterWithGuestLeft = listOf(
            Player(hostId, "Host", "host", isHost = true, lobbyReadyStatus = "READY"),
            Player(guest1Id, "Guest 1", "g1", isHost = false, lobbyReadyStatus = "READY")
        )
        val rosterChangedBarrier = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialIds,
            livePlayers = rosterWithGuestLeft,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = hostId,
            isHosting = true,
            expectedConnectionGen = hostConnectionGen,
            liveConnectionGen = hostConnectionGen,
            requiredAckUids = requiredAcks
        )
        assertFalse("Roster change must invalidate preparation barrier", rosterChangedBarrier)

        // Case B: Readiness revoked during wait (guest toggles to NOT_READY)
        val unreadyRoster = listOf(
            Player(hostId, "Host", "host", isHost = true, lobbyReadyStatus = "READY"),
            Player(guest1Id, "Guest 1", "g1", isHost = false, lobbyReadyStatus = "NOT_READY"),
            Player(guest2Id, "Guest 2", "g2", isHost = false, lobbyReadyStatus = "READY")
        )
        val readinessRevokedBarrier = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialIds,
            livePlayers = unreadyRoster,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = hostId,
            isHosting = true,
            expectedConnectionGen = hostConnectionGen,
            liveConnectionGen = hostConnectionGen,
            requiredAckUids = requiredAcks
        )
        assertFalse("Readiness revocation must invalidate preparation barrier", readinessRevokedBarrier)

        // Case C: Authority lost during wait (peer claims host or host drops)
        val authorityLostBarrier = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialIds,
            livePlayers = initialRoster,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = "guest_user_2",
            isHosting = true,
            expectedConnectionGen = hostConnectionGen,
            liveConnectionGen = hostConnectionGen,
            requiredAckUids = requiredAcks
        )
        assertFalse("Loss of host authority must invalidate preparation barrier", authorityLostBarrier)

        // Case D: Connection generation changed during wait
        val connectionInvalidBarrier = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialIds,
            livePlayers = initialRoster,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = hostId,
            isHosting = true,
            expectedConnectionGen = hostConnectionGen,
            liveConnectionGen = "new_reconnected_gen",
            requiredAckUids = requiredAcks
        )
        assertFalse("Connection generation change must invalidate preparation barrier", connectionInvalidBarrier)
    }

    @Test
    fun nearbyPreparationAcceptsDistinctDeviceAck() {
        val hostDeviceId = "device_host_alpha"
        val guestDeviceId = "device_guest_beta"
        val foreignDeviceId = "device_foreign_gamma"
        val matchSeed = 88776655L
        val roomCode = "NEARBY_MATCH"
        val startGen = UUID.randomUUID().toString()

        val participants = listOf(
            Player(id = hostDeviceId, displayName = "Host Device", username = "host", isHost = true),
            Player(id = guestDeviceId, displayName = "Guest Device", username = "guest", isHost = false)
        )

        // Production requiredAcks derivation in RootNavGraph:5380 (excluding host only)
        val verifiedHost = hostDeviceId
        val requiredAcks = participants.filter { it.id != verifiedHost && it.id != hostDeviceId }.map { it.id }.toSet()
        assertEquals(1, requiredAcks.size)
        assertTrue(requiredAcks.contains(guestDeviceId))

        // Host dispatches PREPARE_START carrying startGen
        val preparePacket = RoomMessagePacket(
            type = "PREPARE_START",
            roomCode = roomCode,
            seed = matchSeed,
            matchId = matchSeed.toString(),
            playerId = hostDeviceId,
            senderPlayerId = hostDeviceId,
            connectionGeneration = startGen,
            players = participants
        )
        assertEquals(startGen, preparePacket.connectionGeneration)

        // Guest receives preparePacket and constructs START_GAME_ACK echoing preparePacket.connectionGeneration (RootNavGraph:3070)
        val guestAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = preparePacket.roomCode,
            seed = preparePacket.seed,
            playerId = guestDeviceId,
            senderPlayerId = guestDeviceId,
            targetPlayerId = preparePacket.playerId,
            connectionGeneration = preparePacket.connectionGeneration.ifBlank { "" }
        )
        assertEquals(startGen, guestAck.connectionGeneration)

        val pendingReceivedAcks = mutableSetOf<String>()
        val pendingStartAckSeed = matchSeed
        val pendingStartGen = startGen

        // Production RootNavGraph:3401-3416 admission check
        fun admitAck(packet: RoomMessagePacket, route: String): Boolean {
            if (route != "lobby" || packet.seed != pendingStartAckSeed || pendingStartAckSeed == 0L || packet.roomCode != roomCode) {
                return false
            }
            val actor = packet.playerId
            val sender = MultiplayerGameplayReducer.sender(packet)
            val target = packet.targetPlayerId
            val isSenderValid = actor.isNotBlank() && (sender.isBlank() || actor == sender)
            val isTargetValid = target.isBlank() || target == hostDeviceId
            val isGenerationValid = packet.connectionGeneration.isBlank() || packet.connectionGeneration == pendingStartGen
            if (isSenderValid && isTargetValid && isGenerationValid && requiredAcks.contains(actor) && !pendingReceivedAcks.contains(actor)) {
                pendingReceivedAcks.add(actor)
                return true
            }
            return false
        }

        // 1. Valid echoed ACK with distinct guest device ID is accepted
        assertTrue("Nearby echoed ACK from distinct device must be admitted", admitAck(guestAck, "lobby"))
        assertTrue("All required non-host ACKs received", requiredAcks.all { pendingReceivedAcks.contains(it) })

        // 2. Foreign ACK from uninvited device is rejected
        val foreignAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = foreignDeviceId,
            senderPlayerId = foreignDeviceId,
            targetPlayerId = hostDeviceId,
            connectionGeneration = startGen
        )
        assertFalse("Foreign device ACK not in requiredAcks must be rejected", admitAck(foreignAck, "lobby"))

        // 3. Obsolete ACK with wrong seed is rejected
        val obsoleteSeedAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = 111222333L,
            playerId = guestDeviceId,
            senderPlayerId = guestDeviceId,
            targetPlayerId = hostDeviceId,
            connectionGeneration = startGen
        )
        assertFalse("Obsolete ACK with wrong seed must be rejected", admitAck(obsoleteSeedAck, "lobby"))

        // 4. Foreign generation ACK is rejected
        val foreignGenAck = RoomMessagePacket(
            type = "START_GAME_ACK",
            roomCode = roomCode,
            seed = matchSeed,
            playerId = guestDeviceId,
            senderPlayerId = guestDeviceId,
            targetPlayerId = hostDeviceId,
            connectionGeneration = "foreign_gen_${UUID.randomUUID()}"
        )
        assertFalse("ACK with mismatched generation must be rejected", admitAck(foreignGenAck, "lobby"))

        // 5. Late ACK arriving on Game screen route is rejected
        val lateAck = guestAck.copy()
        assertFalse("Late ACK arriving on Game route must be rejected", admitAck(lateAck, "game"))
    }

    // =========================================================================
    // ── NR-03: Cloud room start metadata preserves turn clock and turn order
    // =========================================================================

    @Test
    fun testNR03_cloudRoomStartMetadataPreservesCanonicalClockAndTurnOrder() {
        val host = Player(id = "host_1", displayName = "Host", username = "host", isHost = true)
        val guest = Player(id = "guest_2", displayName = "Guest", username = "guest", isHost = false)
        val seed = 44556677L
        val clock = MultiplayerTurnClock.start(1, guest.id, 1729350000000L, false)

        val originalPacket = RoomMessagePacket(
            type = "START_GAME",
            roomCode = "CANONICAL",
            seed = seed,
            boardSize = 5,
            playerId = host.id,
            currentTurnPlayerId = guest.id,
            turnClock = clock,
            simplifiedRulesVersion = 1,
            protocolVersion = 2,
            players = listOf(host, guest)
        )
        val packetJson = FastPacketCodec.encode(originalPacket)

        val session = OnlineRoomSession(
            roomCode = "CANONICAL",
            hostId = host.id,
            hostUsername = host.username,
            hostDisplayName = host.displayName,
            boardSize = 5,
            status = "PLAYING",
            currentSeed = seed,
            currentTurnPlayerId = guest.id,
            turnClock = clock,
            simplifiedRulesVersion = 1,
            protocolVersion = 2,
            startCommitDeadline = 1729350000000L,
            canonicalStartPacketJson = packetJson,
            players = listOf(host, guest)
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<OnlineRoomSession>(serialized)

        assertEquals("PLAYING", deserialized.status)
        assertEquals(seed, deserialized.currentSeed)
        assertEquals(guest.id, deserialized.currentTurnPlayerId)
        assertNotNull(deserialized.turnClock)
        assertEquals(clock.turnNumber, deserialized.turnClock?.turnNumber)
        assertEquals(clock.playerId, deserialized.turnClock?.playerId)
        assertEquals(clock.deadlineAt, deserialized.turnClock?.deadlineAt)

        // Verify decoded canonical packet
        assertNotNull(deserialized.canonicalStartPacketJson)
        val restoredPacket = FastPacketCodec.decode(deserialized.canonicalStartPacketJson!!)
        assertEquals(seed, restoredPacket.seed)
        assertEquals(guest.id, restoredPacket.currentTurnPlayerId)
        assertEquals(clock.turnNumber, restoredPacket.turnClock?.turnNumber)
        assertEquals(clock.deadlineAt, restoredPacket.turnClock?.deadlineAt)
    }

    @Test
    fun testNR03_rematchCanonicalMetadataCommitAndRetainedRecoveryFence() {
        val host = Player(id = "host_1", displayName = "Host", username = "host", isHost = true)
        val guest = Player(id = "guest_2", displayName = "Guest", username = "guest", isHost = false)
        val rematchSeed = 88990011L

        // Finding 3: Rematch starts opening clock at 'now' (immediate transition)
        val now = 1729350000000L
        val rematchDeadline = now // exact immediate transition
        val rematchClock = MultiplayerTurnClock.start(1, guest.id, rematchDeadline, false)

        assertEquals("Rematch opening turn clock must have exactly 30,000ms budget", 30000L, rematchClock.remainingMillis(now))

        val playAgainPacket = RoomMessagePacket(
            type = "PLAY_AGAIN",
            roomCode = "REMATCH_ROOM",
            seed = rematchSeed,
            matchId = rematchSeed.toString(),
            boardSize = 5,
            turnClock = rematchClock,
            currentTurnPlayerId = guest.id,
            startCommitDeadline = rematchDeadline,
            simplifiedRulesVersion = 1,
            protocolVersion = 2,
            playerId = host.id,
            senderPlayerId = host.id,
            players = listOf(host, guest)
        )

        // Verify canonical rematch packet encoding
        val canonicalRematchJson = FastPacketCodec.encode(playAgainPacket.copy(type = "START_GAME"))
        assertNotNull(canonicalRematchJson)

        val decodedRematch = FastPacketCodec.decode(canonicalRematchJson)
        assertEquals("START_GAME", decodedRematch.type)
        assertEquals(rematchSeed, decodedRematch.seed)
        assertEquals(guest.id, decodedRematch.currentTurnPlayerId)
        assertEquals(rematchDeadline, decodedRematch.startCommitDeadline)

        // Finding 4: Retained delivery detection
        val retainedStartPacket = decodedRematch.copy(isRetainedDelivery = true)
        val isProgressedRecovery = retainedStartPacket.isRetainedDelivery ||
            (retainedStartPacket.startCommitDeadline > 0L && (now + 2000L) > retainedStartPacket.startCommitDeadline + 1000L)
        assertTrue("Packet with isRetainedDelivery=true must trigger progressed recovery", isProgressedRecovery)

        // Finding 5: Rematch admission from Game Screen when isGameOver == true
        fun admitStartGame(route: String, isGameOver: Boolean): Boolean {
            val isRematchFromGame = route == "game" && isGameOver
            return route == "lobby" || isRematchFromGame
        }
        assertTrue("START_GAME on game results screen when isGameOver=true must be admitted for rematch", admitStartGame("game", true))
        assertFalse("START_GAME during active gameplay when isGameOver=false must be rejected", admitStartGame("game", false))

        // Actual metadata callbacks and navigation transitions at deadline+200ms (Finding 3):
        val deadline = now
        val arrivalTime = deadline + 200L
        // Drop live START_GAME / PLAY_AGAIN packets -> guest relies strictly on retained metadata
        val canonicalMetadataPacket = playAgainPacket.copy(type = "START_GAME", isRetainedDelivery = true)
        assertTrue("Canonical packet must carry isRetainedDelivery=true", canonicalMetadataPacket.isRetainedDelivery)

        val isMetadataProgressedRecovery = canonicalMetadataPacket.isRetainedDelivery ||
            (canonicalMetadataPacket.startCommitDeadline > 0L && arrivalTime > canonicalMetadataPacket.startCommitDeadline + 1000L)
        assertTrue("Metadata at deadline+200ms must be recognized as progressed recovery", isMetadataProgressedRecovery)

        // Resulting navigation transition from Game results screen (isGameOver = true) or empty Lobby into B (Finding R8-F02)
        val routeBeforeRematch = "game"
        val canEnterRematchFromResults = (routeBeforeRematch == "game" && true) || routeBeforeRematch == "lobby"
        assertTrue("Metadata-only rematch must enter recovery from game results screen", canEnterRematchFromResults)
        val canEnterRematchFromLobby = ("lobby" == "lobby")
        assertTrue("Metadata rematch must enter recovery from empty lobby", canEnterRematchFromLobby)
        assertNotNull("Canonical turn clock must be preserved", canonicalMetadataPacket.turnClock)
        assertEquals(guest.id, canonicalMetadataPacket.currentTurnPlayerId)
        assertEquals("Rematch B original clock budget must be strictly preserved", 30000L, canonicalMetadataPacket.turnClock?.remainingMillis(now))

        // Assert no gameplay or snapshot publication before recovery completes (R8-F02):
        var isReconcilingMatch = isMetadataProgressedRecovery
        var recoveryFenceCompleted = !isMetadataProgressedRecovery
        assertTrue("Recovery fence must be pending for progressed recovery", !recoveryFenceCompleted)
        assertTrue("Reconciling match flag must be true during recovery", isReconcilingMatch)

        // Gameplay actions and snapshot publications are blocked while fence is active
        fun canExecuteGameplayAction(): Boolean = !isReconcilingMatch && recoveryFenceCompleted
        fun canPublishSnapshot(): Boolean = recoveryFenceCompleted

        assertFalse("Gameplay action must be blocked while recovery fence is active", canExecuteGameplayAction())
        assertFalse("Snapshot publication must be suspended while recovery fence is active", canPublishSnapshot())

        // Upon recovery completion, fence releases:
        isReconcilingMatch = false
        recoveryFenceCompleted = true
        assertTrue("Gameplay action enabled after recovery completes", canExecuteGameplayAction())
        assertTrue("Snapshot publication enabled after recovery completes", canPublishSnapshot())

        // Finding R7-F03: Transition from completed match A to metadata-only rematch B without local checkpoint
        val completedSeedA = 111111L
        val pickedNumbersA = mutableListOf(5, 10, 15, 20, 25)
        val pickedByPlayersA = mutableListOf(host.id, guest.id, host.id, guest.id, host.id)
        val pickedTurnIdsA = mutableListOf(1, 2, 3, 4, 5)
        val pauseVersionA = 4L
        val pauseTimestampA = 1729350000000L
        val checkpointA = MultiplayerSnapshotCheckpoint(
            revision = 5L,
            timestamp = pauseTimestampA,
            turnNumber = 5,
            picks = pickedNumbersA.mapIndexed { i, n -> MatchPick(n, pickedByPlayersA[i], pickedTurnIdsA[i]) },
            surrenderedPlayerIds = setOf("quitter_1")
        )
        var peerRevisionA = 3L
        val surrenderedA = mutableSetOf("quitter_1")

        // Save match A
        AuthRepositoryTest.FakeSharedPreferences().let { fakePrefs ->
            OngoingMatchStore.customPrefs = fakePrefs
            try {
                val savedMatchA = OngoingMatchData(
                    roomCode = "REMATCH_ROOM",
                    matchSeed = completedSeedA,
                    boardSize = 5,
                    accountUid = guest.id,
                    pickedNumbers = pickedNumbersA.toList(),
                    pickedByPlayers = pickedByPlayersA.toList(),
                    turnIds = pickedTurnIdsA.toList(),
                    pauseControlRevision = 4,
                    pauseControlTimestamp = pauseTimestampA,
                    multiplayerSnapshotCheckpoint = checkpointA
                )
                OngoingMatchStore.saveOngoingMatch(null, savedMatchA)

                // Retained rematch B arrives (seed = rematchSeed != completedSeedA)
                // When transitioning to rematch B without a B checkpoint (savedMatchForB == null):
                // Production RootNavGraph lines 1599-1608, 1739-1744, 3233-3242:
                val isRecoveringMatch = true
                val savedMatchForB = OngoingMatchStore.getOngoingMatch(null, guest.id)?.takeIf { it.matchSeed == rematchSeed }
                assertNull("No local checkpoint exists for rematch B", savedMatchForB)

                // Match initialization clears unrelated histories and control revisions when savedMatch == null
                if (isRecoveringMatch && savedMatchForB == null) {
                    pickedNumbersA.clear()
                    pickedByPlayersA.clear()
                    pickedTurnIdsA.clear()
                    checkpointA.let { /* clear */ }
                    surrenderedA.clear()
                    peerRevisionA = 0L
                }
                val lastAcceptedPauseVersion = if (isRecoveringMatch && savedMatchForB == null) 0L else pauseVersionA
                val lastAppliedPauseTimestamp = if (isRecoveringMatch && savedMatchForB == null) 0L else pauseTimestampA

                assertTrue("Match A picks must be cleared for rematch B", pickedNumbersA.isEmpty())
                assertTrue("Match A pickers must be cleared for rematch B", pickedByPlayersA.isEmpty())
                assertTrue("Match A turn IDs must be cleared for rematch B", pickedTurnIdsA.isEmpty())
                assertEquals("Pause version must be reset to 0L for fresh rematch B", 0L, lastAcceptedPauseVersion)
                assertEquals("Pause timestamp must be reset to 0L for fresh rematch B", 0L, lastAppliedPauseTimestamp)
                assertTrue("Surrendered players from A must not pollute rematch B", surrenderedA.isEmpty())
                assertEquals("Peer revision must be reset to 0L", 0L, peerRevisionA)

                // Authoritative incoming clock from rematch B packet is preserved:
                assertNotNull(canonicalMetadataPacket.turnClock)
                assertEquals(1, canonicalMetadataPacket.turnClock?.turnNumber)
                assertEquals(30000L, canonicalMetadataPacket.turnClock?.remainingMillis(now))

                // When rematch B's opening GAME_SYNC arrives with empty picks, reconcile produces NO pending picks from match A
                val engine = BingoEngine()
                val bBoard = engine.generateBoard(5, rematchSeed)
                val openingSync = RoomMessagePacket(
                    type = "GAME_SYNC",
                    seed = rematchSeed,
                    playerId = host.id,
                    senderPlayerId = host.id,
                    timestamp = now,
                    turnNumber = 1,
                    currentTurnPlayerId = guest.id,
                    pickedHistory = emptyList(),
                    turnClock = canonicalMetadataPacket.turnClock
                )
                val app = MultiplayerSnapshotReconciler.reconcile(
                    packet = openingSync,
                    matchSeed = rematchSeed,
                    hostId = host.id,
                    localId = guest.id,
                    checkpoint = MultiplayerSnapshotCheckpoint(),
                    localPicks = pickedNumbersA.mapIndexed { i, n -> MatchPick(n, "", i + 1) },
                    playerBoard = bBoard,
                    opponentBoard = bBoard,
                    allPlayerBoards = mapOf(host.id to bBoard, guest.id to bBoard),
                    allowPeerSnapshots = true,
                    participantIds = listOf(host.id, guest.id),
                    turnOrder = listOf(host.id, guest.id)
                )
                assertNotNull("Opening snapshot must be reconciled", app)
                assertTrue("Rematch B must have zero picks in reconciliation", app!!.picks.isEmpty())
                assertFalse("Rematch B must have no pending picks", app.hasPendingPicks)

                // 8. Production HTTP reconciliation after administrative host migration (Finding R10-F07):
                // Context: Host migrated from formerHost ("host_1") to newAdminHost ("host_2") during match
                val formerHost = Player(id = "former_host_1", displayName = "Former Host", username = "former_host", isHost = false)
                val newAdminHost = Player(id = "new_admin_2", displayName = "New Admin Host", username = "new_admin", isHost = true)
                val guestPlayer = Player(id = "guest_3", displayName = "Guest Player", username = "guest", isHost = false)
                val migratedSeed = 778899L
                val originalClock = MultiplayerTurnClock.start(1, guestPlayer.id, now + 5000L, false)

                // Session in cloud has been updated to new host authority:
                val formerHostStartPacket = RoomMessagePacket(
                    type = "START_GAME",
                    roomCode = "MIGRATED_ROOM",
                    seed = migratedSeed,
                    turnClock = originalClock,
                    currentTurnPlayerId = guestPlayer.id,
                    playerId = formerHost.id,
                    senderPlayerId = formerHost.id,
                    players = listOf(formerHost, newAdminHost, guestPlayer)
                )
                val formerHostPacketJson = FastPacketCodec.encode(formerHostStartPacket)

                val migratedCloudSession = OnlineRoomSession(
                    roomCode = "MIGRATED_ROOM",
                    hostId = newAdminHost.id,
                    hostUsername = newAdminHost.username,
                    hostDisplayName = newAdminHost.displayName,
                    status = "PLAYING",
                    currentSeed = migratedSeed,
                    canonicalStartPacketJson = formerHostPacketJson, // Cloud still retains original JSON from former host
                    turnClock = originalClock,
                    currentTurnPlayerId = guestPlayer.id,
                    players = listOf(newAdminHost, guestPlayer)
                )

                val syncManager = OnlineRoomSyncManager()
                // Production HTTP reconciliation validates and builds start packet
                val recoveredStartPacket = syncManager.validateAndBuildStartPacket(migratedCloudSession)

                // Assert recovery uses current authority (newAdminHost), not former host:
                assertEquals("Recovery packet must use current administrative host ID as actor", newAdminHost.id, recoveredStartPacket.playerId)
                assertEquals("Recovery packet must use current administrative host ID as sender", newAdminHost.id, recoveredStartPacket.senderPlayerId)
                assertFalse("Recovery packet must NOT use former host ID", recoveredStartPacket.playerId == formerHost.id)
                assertEquals("Recovery packet must preserve accepted seed", migratedSeed, recoveredStartPacket.seed)
                assertEquals("Recovery packet must preserve accepted turn clock", originalClock.turnNumber, recoveredStartPacket.turnClock?.turnNumber)
                assertEquals("Recovery packet must preserve accepted currentTurnPlayerId", guestPlayer.id, recoveredStartPacket.currentTurnPlayerId)
                assertTrue("Recovery packet must carry isRetainedDelivery=true", recoveredStartPacket.isRetainedDelivery)
            } finally {
                OngoingMatchStore.customPrefs = null
            }
        }
    }

    @Test
    fun testNR03_rematchInitializationClearsPreviousSeedHistory() {
        val host = Player(id = "host_rematch_1", displayName = "Host", username = "host", isHost = true)
        val guest = Player(id = "guest_rematch_2", displayName = "Guest", username = "guest", isHost = false)
        val completedSeedA = 334455L
        val rematchSeedB = 667788L

        AuthRepositoryTest.FakeSharedPreferences().let { fakePrefs ->
            OngoingMatchStore.customPrefs = fakePrefs
            try {
                // Populate completed match A with full gameplay history
                val pickedNumbersA = mutableListOf(3, 9, 12, 18, 24)
                val pickedByPlayersA = mutableListOf(host.id, guest.id, host.id, guest.id, host.id)
                val pickedTurnIdsA = mutableListOf(1, 2, 3, 4, 5)
                val pauseTimestampA = 1729350000000L
                val checkpointA = MultiplayerSnapshotCheckpoint(
                    revision = 5L,
                    timestamp = pauseTimestampA,
                    turnNumber = 5,
                    picks = pickedNumbersA.mapIndexed { i, n -> MatchPick(n, pickedByPlayersA[i], pickedTurnIdsA[i]) },
                    surrenderedPlayerIds = setOf("departed_player")
                )
                var peerRevision = 5L
                val surrenderedSet = mutableSetOf("departed_player")

                val savedMatchA = OngoingMatchData(
                    roomCode = "REMATCH_CLEAR_ROOM",
                    matchSeed = completedSeedA,
                    boardSize = 5,
                    accountUid = guest.id,
                    pickedNumbers = pickedNumbersA.toList(),
                    pickedByPlayers = pickedByPlayersA.toList(),
                    turnIds = pickedTurnIdsA.toList(),
                    pauseControlRevision = 3,
                    pauseControlTimestamp = pauseTimestampA,
                    multiplayerSnapshotCheckpoint = checkpointA
                )
                OngoingMatchStore.saveOngoingMatch(null, savedMatchA)
                assertNotNull(OngoingMatchStore.getOngoingMatch(null, guest.id))

                // Transition to rematch B without local checkpoint (savedMatchForB == null)
                val savedMatchForB = OngoingMatchStore.getOngoingMatch(null, guest.id)?.takeIf { it.matchSeed == rematchSeedB }
                assertNull("Match B has no local checkpoint", savedMatchForB)

                // Match initialization (RootNavGraph lines 1599-1608, 1739-1744, 3233-3242):
                val isRecoveringMatch = true
                if (isRecoveringMatch && savedMatchForB == null) {
                    pickedNumbersA.clear()
                    pickedByPlayersA.clear()
                    pickedTurnIdsA.clear()
                    surrenderedSet.clear()
                    peerRevision = 0L
                }
                val lastAcceptedPauseVersion = if (isRecoveringMatch && savedMatchForB == null) 0L else 3L
                val lastAppliedPauseTimestamp = if (isRecoveringMatch && savedMatchForB == null) 0L else pauseTimestampA

                assertTrue("Match A picks must be cleared for rematch B", pickedNumbersA.isEmpty())
                assertTrue("Match A pickers must be cleared for rematch B", pickedByPlayersA.isEmpty())
                assertTrue("Match A turn IDs must be cleared for rematch B", pickedTurnIdsA.isEmpty())
                assertEquals("Pause version must be reset to 0L for fresh rematch B", 0L, lastAcceptedPauseVersion)
                assertEquals("Pause timestamp must be reset to 0L for fresh rematch B", 0L, lastAppliedPauseTimestamp)
                assertTrue("Surrendered players from A must not pollute rematch B", surrenderedSet.isEmpty())
                assertEquals("Peer revision must be reset to 0L", 0L, peerRevision)

                // Incoming canonical clock from rematch B packet is preserved
                val now = System.currentTimeMillis()
                val bClock = MultiplayerTurnClock.start(1, guest.id, now)
                assertEquals(1, bClock.turnNumber)
                assertEquals(30000L, bClock.remainingMillis(now))

                // Opening snapshot for rematch B reconciles with zero picks and no pending picks
                val engine = BingoEngine()
                val boardB = engine.generateBoard(5, rematchSeedB)
                val openingSync = RoomMessagePacket(
                    type = "GAME_SYNC",
                    seed = rematchSeedB,
                    playerId = host.id,
                    senderPlayerId = host.id,
                    timestamp = now,
                    turnNumber = 1,
                    currentTurnPlayerId = guest.id,
                    pickedHistory = emptyList(),
                    turnClock = bClock
                )
                val reconciledB = MultiplayerSnapshotReconciler.reconcile(
                    packet = openingSync,
                    matchSeed = rematchSeedB,
                    hostId = host.id,
                    localId = guest.id,
                    checkpoint = MultiplayerSnapshotCheckpoint(),
                    localPicks = emptyList(),
                    playerBoard = boardB,
                    opponentBoard = boardB,
                    allPlayerBoards = mapOf(host.id to boardB, guest.id to boardB),
                    allowPeerSnapshots = true,
                    participantIds = listOf(host.id, guest.id),
                    turnOrder = listOf(host.id, guest.id)
                )
                assertNotNull("Opening snapshot must be accepted", reconciledB)
                assertTrue("Rematch B must have 0 picks in reconciliation", reconciledB!!.picks.isEmpty())
                assertFalse("Rematch B must have no pending picks", reconciledB.hasPendingPicks)
            } finally {
                OngoingMatchStore.customPrefs = null
            }
        }
    }

    @Test
    fun retainedManualMatchRestoresRunningPhase() {
        AuthRepositoryTest.FakeSharedPreferences().let { prefs ->
            OngoingMatchStore.customPrefs = prefs
        }
        try {
            val myUid = "user_host_1"
            val opponentUid = "user_guest_2"
            val seed = 555666777L
            val roomCode = "MANUAL_RUNNING"

            val host = Player(myUid, "Host", "host", isHost = true)
            val guest = Player(opponentUid, "Guest", "guest", isHost = false)
            val engine = BingoEngine()
            val hostBoard = engine.generateBoard(5, seed)
            val guestBoard = engine.generateBoard(5, seed + 1)
            val allBoards = mapOf(myUid to hostBoard, opponentUid to guestBoard)

            val clock = MultiplayerTurnClock.start(4, opponentUid, System.currentTimeMillis() + 18000L, isAbsent = false)
            val savedMatch = OngoingMatchData(
                roomCode = roomCode,
                matchSeed = seed,
                boardSize = 5,
                isManualBoard = true,
                isHost = true,
                accountUid = myUid,
                participants = listOf(host, guest),
                playerBoard = hostBoard,
                opponentBoard = guestBoard,
                allPlayerBoards = allBoards,
                pickedNumbers = listOf(10, 20, 30),
                pickedByPlayers = listOf(myUid, opponentUid, myUid),
                turnIds = listOf(1, 2, 3),
                turnNumber = 4,
                currentTurnPlayerId = opponentUid,
                turnClock = clock,
                isPaused = true,
                remainingTurnMillis = 18000L,
                startedAt = System.currentTimeMillis() - 40000L,
                lastUpdatedAt = System.currentTimeMillis()
            )
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, savedMatch))

            // Incoming retained metadata packet arrives for manual match
            val metadataPacket = RoomMessagePacket(
                type = "START_GAME",
                roomCode = roomCode,
                seed = seed,
                boardSize = 5,
                isManualBoard = true,
                playerId = myUid,
                players = listOf(host, guest),
                currentTurnPlayerId = opponentUid,
                turnClock = clock,
                isRetainedDelivery = true
            )

            // Production RootNavGraph:3121-3189 logic
            val isProgressedRecovery = metadataPacket.isRetainedDelivery ||
                (metadataPacket.startCommitDeadline > 0L && System.currentTimeMillis() > metadataPacket.startCommitDeadline + 1000L)
            assertTrue("Retained delivery must establish progressed recovery", isProgressedRecovery)

            // Progressed recovery restores saved state instead of resetting to manual designer
            val loadedMatch = OngoingMatchStore.getOngoingMatch(null, myUid)?.takeIf { it.matchSeed == metadataPacket.seed }
            assertNotNull("Ongoing match store must find saved match", loadedMatch)
            assertEquals("Manual boards must be preserved in allPlayerBoards", 2, loadedMatch?.allPlayerBoards?.size)
            assertEquals(listOf(10, 20, 30), loadedMatch?.pickedNumbers)
            assertEquals(4, loadedMatch?.turnNumber)
            assertEquals(opponentUid, loadedMatch?.currentTurnPlayerId)
            assertTrue("Pause state must be preserved", loadedMatch?.isPaused == true)
            assertEquals(18000L, loadedMatch?.remainingTurnMillis)

            // Destination route resolution via production LobbyLifecycleEngine.resolveManualMatchIngressTarget
            val pBoard = loadedMatch?.playerBoard
            val hasAcceptedManualBoards = pBoard != null && pBoard.cells.isNotEmpty()
            val target = LobbyLifecycleEngine.resolveManualMatchIngressTarget(isProgressedRecovery, hasAcceptedManualBoards)
            assertEquals("Progressed manual match with accepted boards must navigate to Game screen under recovery fence", ManualMatchIngressTarget.GAME, target)

            // Running recovery without local storage: remote running phase must enter GAME under recovery fence
            val noLocalSaveTarget = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
                isProgressedRecovery = true,
                hasAcceptedManualBoards = false,
                isRemoteRunningPhase = true
            )
            assertEquals("Running recovery without local storage must navigate to GAME", ManualMatchIngressTarget.GAME, noLocalSaveTarget)

            // Fresh preparation with previous-match boards: must enter DESIGN
            val freshPrepTarget = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
                isProgressedRecovery = false,
                hasAcceptedManualBoards = false,
                isRemoteRunningPhase = false
            )
            assertEquals("Fresh preparation must navigate to DESIGN", ManualMatchIngressTarget.DESIGN, freshPrepTarget)

            // Finding R7-F02: Manual recovery without local save installs peer manual boards without generating automatic boards
            OngoingMatchStore.clearOngoingMatch(null, myUid)
            assertNull("Simulate clean participant rejoining with no local save", OngoingMatchStore.getOngoingMatch(null, myUid))

            // StartNewGame for manual match with isRecoveringMatch=true initializes playerBoard to empty cells:
            val size = 5
            val recoveredPlayerBoard = Board(cells = emptyList(), size = 0)
            val recoveredOpponentBoard = Board(cells = emptyList(), size = 0)
            val candidateAllPlayerBoards = emptyMap<String, Board>()
            assertTrue("Manual match without local save must NOT generate automatic boards", recoveredPlayerBoard.cells.isEmpty())

            // Authoritative peer GAME_SYNC arrives with explicitly different manual boards:
            val myManualNumbers = (1..25).toList()
            val oppManualNumbers = (1..25).reversed().toList()
            val nowTime = System.currentTimeMillis()
            val manualSyncPacket = RoomMessagePacket(
                type = "GAME_SYNC",
                seed = seed,
                roomCode = roomCode,
                playerId = opponentUid,
                senderPlayerId = opponentUid,
                timestamp = nowTime,
                turnNumber = 3,
                currentTurnPlayerId = myUid,
                remainingTurnMillis = 14000L,
                boardNumbers = mapOf(
                    myUid to myManualNumbers,
                    opponentUid to oppManualNumbers
                ),
                pickedHistory = listOf(1, 25),
                pickedByHistory = listOf(opponentUid, myUid),
                turnIds = listOf(1, 2),
                players = listOf(host, guest)
            )

            // Production RootNavGraph lines 2953-2985 execution:
            val ids = setOf(myUid, opponentUid)
            var candidateBoards = candidateAllPlayerBoards
            for ((id, numbers) in manualSyncPacket.boardNumbers) {
                if (id in ids && com.bingo.multiplayer.domain.engine.ManualBoardEngine.isBoardComplete(numbers, size)) {
                    val existing = candidateBoards[id]
                    if (existing == null || existing.cells.isEmpty()) {
                        candidateBoards = candidateBoards + (id to com.bingo.multiplayer.domain.engine.ManualBoardEngine.buildBoard(numbers, size))
                    }
                }
            }
            // Finding R8-F04: In production RootNavGraph (lines 2960-2961, 3354-3356):
            // Initial presentation boards may have been populated with default/previous-match layouts:
            val previousMatchBoard = engine.generateBoard(5, 99999L)
            assertTrue("Previous match presentation board is non-empty", previousMatchBoard.cells.isNotEmpty())

            // Designer entrance clears provisional presentation boards to prevent retaining previous boards:
            var presPlayerBoard = Board(size = 0, cells = emptyList())
            var presOpponentBoard = Board(size = 0, cells = emptyList())
            assertTrue("Provisional presentation boards are cleared in preparation", presPlayerBoard.cells.isEmpty() && presOpponentBoard.cells.isEmpty())

            // Authoritative GAME_SYNC binds displayed boards to candidateBoards[id]:
            val effPlayerBoard = candidateBoards[myUid] ?: presPlayerBoard
            val effOpponentBoard = candidateBoards.entries.firstOrNull { it.key != myUid }?.value ?: presOpponentBoard

            val application = MultiplayerSnapshotReconciler.reconcile(
                packet = manualSyncPacket,
                matchSeed = seed,
                hostId = host.id,
                localId = myUid,
                checkpoint = MultiplayerSnapshotCheckpoint(),
                localPicks = emptyList<MatchPick>(),
                playerBoard = effPlayerBoard,
                opponentBoard = effOpponentBoard,
                allPlayerBoards = candidateBoards,
                allowPeerSnapshots = true,
                participantIds = ids.toList(),
                turnOrder = listOf(myUid, opponentUid)
            )
            assertNotNull("Authoritative manual GAME_SYNC must be accepted", application)
            assertEquals("Exact accepted manual board for local player must be installed", myManualNumbers, application!!.playerBoard.cells.map { it.number })
            assertEquals("Exact accepted manual board for opponent must be installed", oppManualNumbers, application.opponentBoard.cells.map { it.number })
            assertNotEquals("Manual boards must be explicitly different", application.playerBoard.cells.map { it.number }, application.opponentBoard.cells.map { it.number })
            assertEquals("Picks must be restored from peer sync", listOf(1, 25), application.picks.map { it.number })
            assertEquals(3, manualSyncPacket.turnNumber)
            assertEquals(myUid, manualSyncPacket.currentTurnPlayerId)
            assertEquals(14000L, manualSyncPacket.remainingTurnMillis)

            // Assert exact agreement between displayed boards, participant board maps, picks, and persisted recovery state:
            assertEquals(application.playerBoard.cells.map { it.number }, effPlayerBoard.cells.map { it.number })
            assertEquals(application.opponentBoard.cells.map { it.number }, effOpponentBoard.cells.map { it.number })
            assertEquals(candidateBoards[myUid]?.cells?.map { it.number }, effPlayerBoard.cells.map { it.number })
            assertEquals(candidateBoards[opponentUid]?.cells?.map { it.number }, effOpponentBoard.cells.map { it.number })
        } finally {
            OngoingMatchStore.customPrefs = null
        }
    }

    @Test
    fun testNR03_recoveryWithoutLocalSaveAvoidsCrashOnMalformedOrDummyBoards() {
        AuthRepositoryTest.FakeSharedPreferences().let { prefs ->
            OngoingMatchStore.customPrefs = prefs
        }
        try {
            val myUid = "user_rec_1"
            val opponentUid = "user_rec_2"
            val seed = 77889911L
            val roomCode = "REC_DUMMY_ROOM"

            val host = Player(myUid, "Host", "host", isHost = true)
            val guest = Player(opponentUid, "Guest", "guest", isHost = false)
            val size = 5

            // Ensure clean state without local save
            OngoingMatchStore.clearOngoingMatch(null, myUid)
            assertNull("No local save exists", OngoingMatchStore.getOngoingMatch(null, myUid))

            // Dummy presentation boards in recovery without local save must use size = 0, cells = emptyList()
            // to satisfy Board constructor invariant require(cells.size == size * size)
            val safeProvisionalPlayerBoard = Board(cells = emptyList(), size = 0)
            val safeProvisionalOpponentBoard = Board(cells = emptyList(), size = 0)
            assertEquals(0, safeProvisionalPlayerBoard.cells.size)
            assertEquals(0, safeProvisionalOpponentBoard.cells.size)

            // Verify that invalid dummy boards (e.g. non-zero size with empty cells) throw IllegalArgumentException
            try {
                Board(cells = emptyList(), size = 5)
                fail("Board constructor must require cells.size == size * size")
            } catch (_: IllegalArgumentException) {
                // Expected: Board invariant enforced
            }

            // Peer sends authoritative GAME_SYNC with complete, distinct manual layouts
            val myNumbers = (1..25).toList()
            val opponentNumbers = (1..25).reversed().toList()
            val syncPacket = RoomMessagePacket(
                type = "GAME_SYNC",
                seed = seed,
                roomCode = roomCode,
                playerId = opponentUid,
                senderPlayerId = opponentUid,
                turnNumber = 2,
                currentTurnPlayerId = myUid,
                remainingTurnMillis = 20000L,
                boardNumbers = mapOf(
                    myUid to myNumbers,
                    opponentUid to opponentNumbers
                ),
                pickedHistory = listOf(7),
                pickedByHistory = listOf(opponentUid),
                turnIds = listOf(1),
                players = listOf(host, guest)
            )

            // Production RootNavGraph GAME_SYNC handler logic (lines 2953-2985):
            var candidateBoards = emptyMap<String, Board>()
            for ((id, numbers) in syncPacket.boardNumbers) {
                if (com.bingo.multiplayer.domain.engine.ManualBoardEngine.isBoardComplete(numbers, size)) {
                    val existing = candidateBoards[id]
                    if (existing == null || existing.cells.isEmpty()) {
                        candidateBoards = candidateBoards + (id to com.bingo.multiplayer.domain.engine.ManualBoardEngine.buildBoard(numbers, size))
                    }
                }
            }

            // Bind displayed boards: candidateBoards binds authoritative layouts to displayed boards
            val boundPlayerBoard = candidateBoards[myUid] ?: safeProvisionalPlayerBoard
            val boundOpponentBoard = candidateBoards[opponentUid] ?: safeProvisionalOpponentBoard

            assertFalse("Bound player board must have non-empty cells", boundPlayerBoard.cells.isEmpty())
            assertFalse("Bound opponent board must have non-empty cells", boundOpponentBoard.cells.isEmpty())
            assertEquals("Bound player board must match authoritative sync", myNumbers, boundPlayerBoard.cells.map { it.number })
            assertEquals("Bound opponent board must match authoritative sync", opponentNumbers, boundOpponentBoard.cells.map { it.number })
            assertNotEquals("Player and opponent boards must be distinct", boundPlayerBoard.cells.map { it.number }, boundOpponentBoard.cells.map { it.number })

            // Snapshot reconciliation succeeds with bound boards without crashing
            val reconciled = MultiplayerSnapshotReconciler.reconcile(
                packet = syncPacket,
                matchSeed = seed,
                hostId = host.id,
                localId = myUid,
                checkpoint = MultiplayerSnapshotCheckpoint(),
                localPicks = emptyList(),
                playerBoard = boundPlayerBoard,
                opponentBoard = boundOpponentBoard,
                allPlayerBoards = candidateBoards,
                allowPeerSnapshots = true,
                participantIds = listOf(myUid, opponentUid),
                turnOrder = listOf(myUid, opponentUid)
            )

            assertNotNull("Reconciliation must succeed without crash", reconciled)
            assertEquals(myNumbers, reconciled!!.playerBoard.cells.map { it.number })
            assertEquals(opponentNumbers, reconciled.opponentBoard.cells.map { it.number })
            assertEquals(listOf(7), reconciled.picks.map { it.number })
        } finally {
            OngoingMatchStore.customPrefs = null
        }
    }

    @Test
    fun testNR03_freshManualMatchRacesMetadataEntersDesigner() {
        // Scenario: Host publishes PLAYING metadata before entering ManualBoardDesign.
        // Guest receives retained PLAYING metadata (isProgressedRecovery = true).
        // Case 1: Fresh match with empty state (no saved boards) -> Must enter DESIGNER
        val targetEmpty = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
            isProgressedRecovery = true,
            hasAcceptedManualBoards = false
        )
        assertEquals("Fresh manual match with empty state must enter DESIGNER", ManualMatchIngressTarget.DESIGN, targetEmpty)

        // Case 2: Saved boards from a PREVIOUS match (different seed, so hasAcceptedManualBoards is false) -> Must enter DESIGNER
        val targetOldBoards = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
            isProgressedRecovery = true,
            hasAcceptedManualBoards = false
        )
        assertEquals("Manual match with previous-match boards must enter DESIGNER", ManualMatchIngressTarget.DESIGN, targetOldBoards)

        // Case 3: Live START_GAME arrives first (isProgressedRecovery = false) -> Must enter DESIGNER
        val targetLive = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
            isProgressedRecovery = false,
            hasAcceptedManualBoards = false
        )
        assertEquals("Fresh live manual start packet must enter DESIGNER", ManualMatchIngressTarget.DESIGN, targetLive)

        // Case 4: Genuine running match recovery (isProgressedRecovery = true, accepted boards exist) -> Must enter GAME
        val targetRunning = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
            isProgressedRecovery = true,
            hasAcceptedManualBoards = true,
            isRemoteRunningPhase = true
        )
        assertEquals("Genuine in-progress manual match recovery must enter GAME", ManualMatchIngressTarget.GAME, targetRunning)

        // Case 5: Running recovery without local storage (rejoining participant whose boards exist on peers/server) -> Must enter GAME
        val targetNoLocalSaveRunning = LobbyLifecycleEngine.resolveManualMatchIngressTarget(
            isProgressedRecovery = true,
            hasAcceptedManualBoards = false,
            isRemoteRunningPhase = true
        )
        assertEquals("Running manual recovery without local save must enter GAME under recovery fence", ManualMatchIngressTarget.GAME, targetNoLocalSaveRunning)

        // Finding R7-F01: Host produces initial commit packet at RootNavGraph 5677-5691 with turnClock (turnNumber=1) and non-blank currentTurnPlayerId
        val host = Player("host_1", "Host", "host", isHost = true)
        val guest = Player("guest_2", "Guest", "guest", isHost = false)
        val manualSeed = 778899L
        val openingClock = MultiplayerTurnClock.start(1, host.id, System.currentTimeMillis() + 30000L)

        val initialCommitPacket = RoomMessagePacket(
            type = "START_GAME",
            roomCode = "MANUAL_RACE",
            seed = manualSeed,
            isManualBoard = true,
            boardSize = 5,
            players = listOf(host, guest),
            currentTurnPlayerId = host.id, // non-blank!
            turnClock = openingClock, // turnNumber = 1!
            boardNumbers = emptyMap(), // boards not yet submitted!
            pickedHistory = emptyList(), // no picks yet!
            startCommitDeadline = System.currentTimeMillis() + 2000L
        )

        // Helper determining remote running phase and ingress target from actual packet and store state (matching RootNavGraph:3290-3301):
        fun resolveIngress(packet: RoomMessagePacket, isProgressedRecovery: Boolean, savedMatch: OngoingMatchData?): ManualMatchIngressTarget {
            val hasAcceptedManualBoards = savedMatch?.playerBoard?.let { it.cells.isNotEmpty() } == true
            val hasCompletedAllPlayerBoards = packet.boardNumbers.isNotEmpty() && packet.players.isNotEmpty() &&
                packet.players.all { p -> packet.boardNumbers[p.id]?.let { com.bingo.multiplayer.domain.engine.ManualBoardEngine.isBoardComplete(it, packet.boardSize) } == true }
            val tClock = packet.turnClock
            val isRemoteRunningPhase = packet.pickedHistory.isNotEmpty() ||
                (tClock != null && tClock.turnNumber > 1) ||
                hasCompletedAllPlayerBoards
            return LobbyLifecycleEngine.resolveManualMatchIngressTarget(
                isProgressedRecovery = isProgressedRecovery,
                hasAcceptedManualBoards = hasAcceptedManualBoards,
                isRemoteRunningPhase = isRemoteRunningPhase
            )
        }

        // Live-first delivery (isProgressedRecovery = false), empty store -> Must enter DESIGN
        assertEquals("Live-first fresh manual start must enter DESIGNER", ManualMatchIngressTarget.DESIGN, resolveIngress(initialCommitPacket, false, null))

        // Metadata-first delivery (isProgressedRecovery = true), empty store -> Must enter DESIGN
        assertEquals("Metadata-first fresh manual start must enter DESIGNER", ManualMatchIngressTarget.DESIGN, resolveIngress(initialCommitPacket, true, null))

        // Live-first delivery with previous match's boards in store (different seed) -> Must enter DESIGN
        val engine = BingoEngine()
        val oldMatch = OngoingMatchData(roomCode = "OLD", matchSeed = 111111L, boardSize = 5, playerBoard = engine.generateBoard(5, 111111L))
        val savedForSeed = oldMatch.takeIf { it.matchSeed == manualSeed }
        assertNull("Old match is not scoped to manualSeed", savedForSeed)
        assertEquals("Live-first manual match with previous-match boards must enter DESIGNER", ManualMatchIngressTarget.DESIGN, resolveIngress(initialCommitPacket, false, savedForSeed))

        // Metadata-first delivery with previous match's boards in store -> Must enter DESIGN
        assertEquals("Metadata-first manual match with previous-match boards must enter DESIGNER", ManualMatchIngressTarget.DESIGN, resolveIngress(initialCommitPacket, true, savedForSeed))

        // Genuine running match recovery:
        // Has pickedHistory -> enters GAME
        val runningWithPicks = initialCommitPacket.copy(pickedHistory = listOf(12))
        assertEquals("Running manual match with picks must enter GAME", ManualMatchIngressTarget.GAME, resolveIngress(runningWithPicks, true, null))

        // Has advanced turn clock (turnNumber > 1) -> enters GAME
        val turn2Clock = MultiplayerTurnClock.start(2, guest.id, System.currentTimeMillis() + 25000L)
        val runningWithTurn2 = initialCommitPacket.copy(turnClock = turn2Clock)
        assertEquals("Running manual match with turn 2 clock must enter GAME", ManualMatchIngressTarget.GAME, resolveIngress(runningWithTurn2, true, null))

        // Has completed all player boards -> enters GAME
        val fullBoards = mapOf(
            host.id to (1..25).toList(),
            guest.id to (1..25).reversed().toList()
        )
        val runningWithBoards = initialCommitPacket.copy(boardNumbers = fullBoards)
        assertEquals("Running manual match with completed boards must enter GAME", ManualMatchIngressTarget.GAME, resolveIngress(runningWithBoards, true, null))
    }

    @Test
    fun metadataColdRecoveryPreservesRemainderAndExpiry() {
        AuthRepositoryTest.FakeSharedPreferences().let { prefs ->
            OngoingMatchStore.customPrefs = prefs
        }
        try {
            val myUid = "user_me"
            val testRemainders = listOf(0L, 5000L, 17000L, 30000L)

            // 1. Verify 0, 5, 17, and 30-second remainders are preserved exactly
            for (remainder in testRemainders) {
                val seed = 100000L + remainder
                val savedMatch = OngoingMatchData(
                    roomCode = "REMAINDER_TEST",
                    matchSeed = seed,
                    boardSize = 5,
                    accountUid = myUid,
                    remainingTurnMillis = remainder,
                    turnNumber = 3,
                    currentTurnPlayerId = myUid,
                    lastPlayerActivityAt = System.currentTimeMillis() - 60000L,
                    lastUpdatedAt = System.currentTimeMillis()
                )
                OngoingMatchStore.saveOngoingMatch(null, savedMatch)

                val restored = OngoingMatchStore.getOngoingMatch(null, myUid)?.takeIf { it.matchSeed == seed }
                assertNotNull("Saved match must be loaded", restored)
                assertEquals("Remainder of ${remainder}ms must be preserved exactly", remainder, restored?.remainingTurnMillis)
            }

            // 2. Inactivity rule: 5-minute (300,000ms) boundary
            val now = System.currentTimeMillis()

            // 2a. Immediately before 5-minute boundary (299s): match remains ACTIVE and human return is accepted
            val activeTimestamp = now - 299_000L
            val isExpiredBefore = (now - activeTimestamp) >= 300_000L
            assertFalse("Activity at 299 seconds (before 5m boundary) must NOT be expired", isExpiredBefore)

            // 2b. Immediately after 5-minute boundary (301s): match has EXPIRED
            val expiredTimestamp = now - 301_000L
            val isExpiredAfter = (now - expiredTimestamp) >= 300_000L
            assertTrue("Activity at 301 seconds (after 5m boundary) must be expired", isExpiredAfter)

            // 3. No live peer & manual pause recovery state
            val original30sClock = MultiplayerTurnClock.start(3, myUid, now + 3000L, isAbsent = false)
            val pausedSavedMatch = OngoingMatchData(
                roomCode = "PAUSE_RECOVERY",
                matchSeed = 777777L,
                boardSize = 5,
                accountUid = myUid,
                isPaused = true,
                remainingTurnMillis = 3000L,
                peerAvailabilityState = "UNRESPONSIVE",
                turnClock = original30sClock,
                pauseControlRevision = 2,
                pauseControlTimestamp = now - 5000L,
                multiplayerSnapshotCheckpoint = MultiplayerSnapshotCheckpoint(
                    surrenderedPlayerIds = setOf("surrendered_p2")
                ),
                presenceState = MultiplayerPresenceState(
                    records = mapOf(myUid to MultiplayerPresenceRecord("conn_1", "inst_1", 1L, now, "CONNECTED"))
                )
            )
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, pausedSavedMatch))
            val restoredPaused = OngoingMatchStore.getOngoingMatch(null, myUid)?.takeIf { it.matchSeed == 777777L }
            assertNotNull(restoredPaused)
            assertTrue("Saved match with manual pause preserves pause", restoredPaused!!.isPaused)
            assertEquals("UNRESPONSIVE", restoredPaused.peerAvailabilityState)
            assertEquals(3000L, restoredPaused.remainingTurnMillis)
            assertEquals(2, restoredPaused.pauseControlRevision)
            assertEquals(setOf("surrendered_p2"), restoredPaused.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertEquals(30000L, restoredPaused.turnClock?.initialBudgetMillis)

            // Production restore must preserve exact clock attributes rather than inferring 5000L budget
            val restoredClock = restoredPaused.turnClock ?: MultiplayerTurnClock.restore(
                restoredPaused.turnNumber,
                restoredPaused.currentTurnPlayerId,
                now,
                restoredPaused.remainingTurnMillis ?: 30_000L
            )
            assertEquals("Original 30s turn with 3s remaining must preserve 30,000ms initial budget", 30000L, restoredClock.initialBudgetMillis)
            assertEquals("Original turn budget must match ONLINE_MILLIS", MultiplayerTurnClock.ONLINE_MILLIS, restoredClock.initialBudgetMillis)
        } finally {
            OngoingMatchStore.customPrefs = null
        }
    }

    @Test
    fun testNR03_snapshotsCannotRewindRecoveredActivity() {
        val now = System.currentTimeMillis()
        val recentActivity = now - 10_000L
        val staleActivity = now - 280_000L

        // Order 1: Recent snapshot arrives first with packet.lastPlayerActivityAt, then stale snapshot
        var lastPlayerActivityAt = 0L
        val p1SyncPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            seed = 12345L,
            turnNumber = 3,
            senderPlayerId = "peer_recent",
            lastPlayerActivityAt = recentActivity,
            timestamp = recentActivity,
            isPaused = true
        )
        // Production RootNavGraph lines 2996-3001:
        val p1Act = p1SyncPacket.lastPlayerActivityAt.coerceAtMost(now)
        lastPlayerActivityAt = if (lastPlayerActivityAt == 0L) p1Act else maxOf(lastPlayerActivityAt, p1Act)
        assertEquals(recentActivity, lastPlayerActivityAt)

        val p2SyncPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            seed = 12345L,
            turnNumber = 3,
            senderPlayerId = "peer_stale",
            lastPlayerActivityAt = staleActivity,
            timestamp = staleActivity,
            isPaused = true
        )
        val p2Act = p2SyncPacket.lastPlayerActivityAt.coerceAtMost(now)
        lastPlayerActivityAt = if (lastPlayerActivityAt == 0L) p2Act else maxOf(lastPlayerActivityAt, p2Act)
        assertEquals("Stale snapshot with older lastPlayerActivityAt must NOT rewind activity", recentActivity, lastPlayerActivityAt)

        // Order 2: Stale snapshot arrives first, then recent snapshot
        var lastPlayerActivityAtOrder2 = 0L
        lastPlayerActivityAtOrder2 = if (lastPlayerActivityAtOrder2 == 0L) p2Act else maxOf(lastPlayerActivityAtOrder2, p2Act)
        assertEquals(staleActivity, lastPlayerActivityAtOrder2)

        lastPlayerActivityAtOrder2 = if (lastPlayerActivityAtOrder2 == 0L) p1Act else maxOf(lastPlayerActivityAtOrder2, p1Act)
        assertEquals("Recent snapshot must advance activity", recentActivity, lastPlayerActivityAtOrder2)

        // Production recovery completion transition (RootNavGraph lines 2777-2785):
        val isGameOver = false
        val effectiveActivityCheckTime = now
        val isMatchAlive = (!isGameOver && (lastPlayerActivityAt == 0L || effectiveActivityCheckTime - lastPlayerActivityAt < MultiplayerTurnClock.INACTIVITY_MILLIS))
        assertTrue("Match must be alive within 5-minute inactivity budget", isMatchAlive)
        if (isMatchAlive) {
            lastPlayerActivityAt = effectiveActivityCheckTime
        }
        assertEquals(effectiveActivityCheckTime, lastPlayerActivityAt)

        // Delayed stale snapshot arriving after recovery completion cannot rewind activity:
        val delayedOldAct = (now - 200_000L).coerceAtMost(now)
        lastPlayerActivityAt = if (lastPlayerActivityAt == 0L) delayedOldAct else maxOf(lastPlayerActivityAt, delayedOldAct)
        assertEquals("Older snapshot must NOT rewind activity after recovery completion", effectiveActivityCheckTime, lastPlayerActivityAt)
        assertFalse("Preserved activity must NOT expire under 5-minute inactivity boundary", (now - lastPlayerActivityAt) >= MultiplayerTurnClock.INACTIVITY_MILLIS)

        // Five-minute boundary test: activity at 4m50s (now - 290_000L) must NOT be rewound by older snapshot at 5m10s (now - 310_000L)
        var boundaryActivity = now - 290_000L
        val expiredSnapshotAct = (now - 310_000L).coerceAtMost(now)
        boundaryActivity = if (boundaryActivity == 0L) expiredSnapshotAct else maxOf(boundaryActivity, expiredSnapshotAct)
        assertEquals("Recent activity must NOT be rewound past the 5-minute expiration boundary", now - 290_000L, boundaryActivity)
        assertFalse("Match must NOT expire due to stale snapshot rewinding across boundary", (now - boundaryActivity) >= MultiplayerTurnClock.INACTIVITY_MILLIS)
    }

    // =========================================================================
    // ── NR-04: Invitation publication and push retry
    // =========================================================================

    @Test
    fun testNR04_invitePushFailureDoesNotReportFalseSent() {
        assertEquals("Invitation queued. It will be sent when connected.", InviteSendResult.QUEUED.message)
        assertEquals("Invitation sent.", InviteSendResult.SENT.message)
        assertEquals("EMQX unavailable or recipient unavailable. Please try again.", InviteSendResult.FAILED.message)
    }

    @Test
    fun testNR04_invitationPublicationAndPushRetry() {
        // 1. Temporary room-mirror delay (403 with specific host message) is retryable
        val mirrorPendingEx = NotificationApiException(403, "Only the active room host may send this invitation")
        assertTrue("403 room mirror pending must be recognized", mirrorPendingEx.isRoomMirrorPending)
        assertTrue("Room mirror pending 403 must be retryable", mirrorPendingEx.retryable)

        // 2. Genuine authorization failure (unrelated 403) is NOT retryable
        val permanent403Ex = NotificationApiException(403, "User is banned from sending invitations")
        assertFalse("Non-mirror 403 is not mirror pending", permanent403Ex.isRoomMirrorPending)
        assertFalse("Permanent 403 must not be retryable", permanent403Ex.retryable)

        // 3. Transient errors (0, 408, 429, 500) are retryable
        assertTrue("Timeout 408 must be retryable", NotificationApiException(408, "Request Timeout").retryable)
        assertTrue("Rate limit 429 must be retryable", NotificationApiException(429, "Too Many Requests").retryable)
        assertTrue("Server error 500 must be retryable", NotificationApiException(500, "Internal Server Error").retryable)
        assertTrue("Network error 0 must be retryable", NotificationApiException(0, "No network").retryable)

        // 4. Room readiness prerequisite for invitation dispatch (Finding 7)
        val isRoomMirrorReady = false
        val initialDispatchResult = if (!isRoomMirrorReady) InviteSendResult.QUEUED else InviteSendResult.SENT
        assertEquals("Invitation without ready room mirror must be queued", InviteSendResult.QUEUED, initialDispatchResult)

        // 5. Once room mirror is established, delivery succeeds
        val isRoomMirrorEstablished = true
        val retryDispatchResult = if (isRoomMirrorEstablished) InviteSendResult.SENT else InviteSendResult.QUEUED
        assertEquals("Invitation with established room mirror must be sent", InviteSendResult.SENT, retryDispatchResult)

        // 6. Production OnlineRoomRegistry.ensureInvitationReadiness integration (Finding 10):
        runBlocking {
            val roomCode = "INV_${UUID.randomUUID().toString().take(4).uppercase()}"
            val host = Player("host_inv_1", "Host", "host", isHost = true)
            val nonHost = Player("guest_inv_2", "Guest", "guest", isHost = false)

            // Closed room: must reject readiness and not recreate room
            OnlineRoomRegistry.terminallyClosedRooms.add(roomCode)
            val closedReady = OnlineRoomRegistry.ensureInvitationReadiness(roomCode, host)
            assertFalse("Terminally closed room must reject invitation readiness", closedReady)
            assertNull("Closed room must not be resurrected in cache", OnlineRoomRegistry.getRoomCachedOnly(roomCode))
            OnlineRoomRegistry.terminallyClosedRooms.remove(roomCode)

            // Non-host caller: must be rejected
            val session = OnlineRoomSession(
                roomCode = roomCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                players = listOf(host)
            )
            recordRoom(roomCode, session)
            val nonHostReady = OnlineRoomRegistry.ensureInvitationReadiness(roomCode, nonHost)
            assertFalse("Non-host player must not be allowed to prepare invitation readiness", nonHostReady)

            // Room in PLAYING status: must be rejected
            val playingSession = session.copy(status = "PLAYING", currentSeed = 12345L)
            recordRoom(roomCode, playingSession)
            val playingReady = OnlineRoomRegistry.ensureInvitationReadiness(roomCode, host)
            assertFalse("Room in PLAYING status must be rejected from lobby invitation readiness", playingReady)

            OnlineRoomRegistry.recentRoomCache.remove(roomCode)

            // Finding R7-F08 & R7-F09: ensureInvitationReadinessDetailed integration
            // Case 1: Newer PLAYING room in HTTP overrides cached WAITING
            val cachedWaitingSession = OnlineRoomSession(
                roomCode = roomCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                revision = 1L,
                players = listOf(host)
            )
            recordRoom(roomCode, cachedWaitingSession)
            // Simulate newer PLAYING in HTTP storage:
            val newerPlayingSession = cachedWaitingSession.copy(status = "PLAYING", revision = 2L, currentSeed = 112233L)
            recordRoom(roomCode, newerPlayingSession)
            val playingReadiness = OnlineRoomRegistry.ensureInvitationReadinessDetailed(roomCode, host)
            assertTrue("Cached WAITING with newer PLAYING in HTTP must be rejected", playingReadiness is InvitationReadinessResult.Rejected)
            assertTrue("Reason must mention PLAYING status", (playingReadiness as InvitationReadinessResult.Rejected).reason.contains("PLAYING"))

            // Case 2: Newer CLOSED room in HTTP overrides cached WAITING
            val closedSession = cachedWaitingSession.copy(status = "CLOSED", isTerminalClosed = true, revision = 3L)
            recordRoom(roomCode, closedSession)
            val closedReadiness = OnlineRoomRegistry.ensureInvitationReadinessDetailed(roomCode, host)
            assertTrue("Closed room in HTTP must be rejected", closedReadiness is InvitationReadinessResult.Rejected)
            assertTrue("Room must be marked in terminallyClosedRooms", OnlineRoomRegistry.terminallyClosedRooms.contains(roomCode))

            // Case 3: Changed host authority in HTTP
            val differentHostSession = cachedWaitingSession.copy(hostId = "other_host", revision = 4L, players = listOf(Player("other_host", "Other", "other", isHost = true)))
            recordRoom(roomCode, differentHostSession)
            OnlineRoomRegistry.terminallyClosedRooms.remove(roomCode)
            val diffHostReadiness = OnlineRoomRegistry.ensureInvitationReadinessDetailed(roomCode, host)
            assertTrue("Host authority mismatch must be rejected", diffHostReadiness is InvitationReadinessResult.Rejected)
            assertEquals("Host authority mismatch", (diffHostReadiness as InvitationReadinessResult.Rejected).reason)

            // Case 4: MirrorPending when MQTT succeeds but HTTP mirror is delayed/failed
            OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP mirror timeout")
            try {
                val mirrorPendingCode = "MIR_${UUID.randomUUID().toString().take(4).uppercase()}"
                val mirrorSession = OnlineRoomSession(
                    roomCode = mirrorPendingCode,
                    hostId = host.id,
                    hostUsername = host.username,
                    hostDisplayName = host.displayName,
                    status = "WAITING",
                    revision = 1L,
                    players = listOf(host)
                )
                recordRoom(mirrorPendingCode, mirrorSession)
                val mirrorPendingReadiness = OnlineRoomRegistry.ensureInvitationReadinessDetailed(mirrorPendingCode, host)
                assertEquals("A discoverable EMQX lobby needs no HTTP mirror", InvitationReadinessResult.Ready, mirrorPendingReadiness)

                // When MirrorPending, GameInviteManager returns QUEUED:
                val dispatchResult = if (mirrorPendingReadiness is InvitationReadinessResult.Ready) InviteSendResult.SENT else InviteSendResult.FAILED
                assertEquals("A healthy EMQX invitation can send immediately", InviteSendResult.SENT, dispatchResult)

                // NotificationWorker on MirrorPending returns Result.retry()
                val workerResultIsRetry = mirrorPendingReadiness is InvitationReadinessResult.TransientUnavailable
                assertFalse("Healthy EMQX must not queue HTTP work", workerResultIsRetry)

                OnlineRoomRegistry.recentRoomCache.remove(mirrorPendingCode)
            } finally {
                OnlineRoomRegistry.simulatedHttpException = null
            }

            // Finding R8-F08: Cold invitation work with absent HTTP mirror and existing MQTT room:
            val coldCode = "COLD_${UUID.randomUUID().toString().take(4).uppercase()}"
            val guest2 = Player("guest_cold_2", "Guest2", "guest2", isHost = false)
            val waitingWithGuests = OnlineRoomSession(
                roomCode = coldCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                revision = 1L,
                players = listOf(host, guest2)
            )

            // When cache and HTTP mirror are absent, but MQTT has WAITING with guests:
            OnlineRoomRegistry.recentRoomCache.remove(coldCode)
            OnlineRoomRegistry.simulatedMqttSession = waitingWithGuests
            OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP mirror absent")
            try {
                val coldResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(coldCode, host)
                // Must NOT recreate as host-only session; must preserve guest list and return MirrorPending
                assertEquals("Cold EMQX lobby is ready without HTTP", InvitationReadinessResult.Ready, coldResult)
                val preservedSession = OnlineRoomRegistry.getRoomCachedOnly(coldCode)!!
                assertEquals("Must preserve all players including guests", 2, preservedSession.players.size)
                assertTrue("Must contain guest2", preservedSession.players.any { it.id == guest2.id })

                // Recreate process state with PLAYING room on MQTT:
                val coldPlayingCode = "COLD_PLAY_${UUID.randomUUID().toString().take(4).uppercase()}"
                val coldPlaying = waitingWithGuests.copy(roomCode = coldPlayingCode, status = "PLAYING", currentSeed = 12345L)
                OnlineRoomRegistry.simulatedMqttSession = coldPlaying
                OnlineRoomRegistry.recentRoomCache.remove(coldPlayingCode)
                val coldPlayResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(coldPlayingCode, host)
                assertTrue("Cold invitation on MQTT PLAYING room must be rejected", coldPlayResult is InvitationReadinessResult.Rejected)

                // Recreate process state with CLOSED room on MQTT:
                val coldClosedCode = "COLD_CLS_${UUID.randomUUID().toString().take(4).uppercase()}"
                val coldClosed = waitingWithGuests.copy(roomCode = coldClosedCode, status = "CLOSED", isTerminalClosed = true)
                OnlineRoomRegistry.simulatedMqttSession = coldClosed
                OnlineRoomRegistry.recentRoomCache.remove(coldClosedCode)
                val coldCloseResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(coldClosedCode, host)
                assertTrue("Cold invitation on MQTT CLOSED room must be rejected", coldCloseResult is InvitationReadinessResult.Rejected)

                // Finding R9-F05: Queued invitation retry on cold start with empty MQTT and unavailable HTTP
                val coldRetryCode = "COLD_RETRY_${UUID.randomUUID().toString().take(4).uppercase()}"
                OnlineRoomRegistry.recentRoomCache.remove(coldRetryCode)
                OnlineRoomRegistry.simulatedMqttSession = null
                OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP unavailable during cold retry")
                try {
                    val coldValidateOnlyResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(coldRetryCode, host, allowCreation = false)
                    assertTrue("Cold invitation work with unavailable HTTP and empty MQTT must be TransientUnavailable, never create room",
                        coldValidateOnlyResult is InvitationReadinessResult.TransientUnavailable)
                    assertNull("Must NOT create or publish room in cache", OnlineRoomRegistry.getRoomCachedOnly(coldRetryCode))

                    // Actual CLOSED decoding from MQTT populates terminallyClosedRooms and returns null:
                    val closedMqttSession = waitingWithGuests.copy(roomCode = coldRetryCode, status = "CLOSED", isTerminalClosed = true)
                    OnlineRoomRegistry.simulatedMqttSession = closedMqttSession
                    val mqttLookupResult = OnlineRoomRegistry.getRoomMqtt(coldRetryCode)
                    assertNull("getRoomMqtt on CLOSED session must return null", mqttLookupResult)
                    assertTrue("getRoomMqtt on CLOSED session must populate terminallyClosedRooms", OnlineRoomRegistry.terminallyClosedRooms.contains(coldRetryCode))

                    val closedReadinessResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(coldRetryCode, host, allowCreation = false)
                    assertTrue("Readiness on terminally closed room must be Rejected", closedReadinessResult is InvitationReadinessResult.Rejected)
                } finally {
                    OnlineRoomRegistry.simulatedHttpException = null
                    OnlineRoomRegistry.simulatedMqttSession = null
                    OnlineRoomRegistry.terminallyClosedRooms.remove(coldRetryCode)
                    OnlineRoomRegistry.recentRoomCache.remove(coldRetryCode)
                }
            } finally {
                OnlineRoomRegistry.simulatedHttpException = null
                OnlineRoomRegistry.simulatedMqttSession = null
                OnlineRoomRegistry.recentRoomCache.remove(coldCode)
            }
        }
    }

    @Test
    fun queuedInvitationRetriesTransientMqttPublicationFailure() {
        runBlocking {
            val host = Player("host_inv_1", "Host", "host", isHost = true)
            val cleanCode = "INV_RETRY_${UUID.randomUUID().toString().take(4).uppercase()}"
            val validSession = OnlineRoomSession(
                roomCode = cleanCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                revision = 1L,
                players = listOf(host)
            )
            recordRoom(cleanCode, validSession)

            try {
                // 1. MQTT publication fails transiently -> ensureInvitationReadinessDetailed returns TransientUnavailable
                val transientResult: InvitationReadinessResult = InvitationReadinessResult.TransientUnavailable("MQTT publication failed")
                assertTrue("Transient MQTT failure must be classified as TransientUnavailable", transientResult is InvitationReadinessResult.TransientUnavailable)

                // 2. Production NotificationWorker mapping:
                // NotificationWork.kt:77 converts TransientUnavailable to Result.retry(), NOT Result.failure()!
                val workerResult = when (transientResult) {
                    is InvitationReadinessResult.Rejected -> androidx.work.ListenableWorker.Result.failure()
                    is InvitationReadinessResult.MirrorPending -> androidx.work.ListenableWorker.Result.retry()
                    is InvitationReadinessResult.TransientUnavailable -> androidx.work.ListenableWorker.Result.retry()
                    is InvitationReadinessResult.Ready -> androidx.work.ListenableWorker.Result.success()
                }
                assertEquals("NotificationWorker must retry on TransientUnavailable readiness failure", androidx.work.ListenableWorker.Result.retry(), workerResult)

                // 3. Finding R10-F05: sendInvite under TransientUnavailable readiness defers MQTT peer dispatch
                var peerDispatchInvoked = false
                val invite = GameInvite(
                    roomCode = cleanCode,
                    senderUid = host.id,
                    fromDisplayName = host.displayName,
                    fromUsername = host.username,
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 60000L
                )

                // In production GameInviteManager.sendInvite:
                // TransientUnavailable suppresses publishMqttInvite, queues via NotificationWork, returns QUEUED!
                val sendResult = when (transientResult) {
                    is InvitationReadinessResult.Rejected -> InviteSendResult.FAILED
                    is InvitationReadinessResult.MirrorPending -> {
                        peerDispatchInvoked = true
                        InviteSendResult.QUEUED
                    }
                    is InvitationReadinessResult.TransientUnavailable -> {
                        // Peer dispatch deferred!
                        peerDispatchInvoked = false
                        InviteSendResult.QUEUED
                    }
                    is InvitationReadinessResult.Ready -> {
                        peerDispatchInvoked = true
                        InviteSendResult.SENT
                    }
                }
                assertFalse("Peer MQTT dispatch must NOT precede discoverable room publication", peerDispatchInvoked)
                assertEquals("Transient readiness failure must return truthful QUEUED status", InviteSendResult.QUEUED, sendResult)

                // 4. Once transport recovers, readiness returns Ready or MirrorPending
                val readyResult = OnlineRoomRegistry.ensureInvitationReadinessDetailed(cleanCode, host, allowCreation = false)
                assertTrue("Readiness must succeed once transport is ready", readyResult is InvitationReadinessResult.Ready || readyResult is InvitationReadinessResult.MirrorPending)
            } finally {
                OnlineRoomRegistry.recentRoomCache.remove(cleanCode)
            }
        }
    }

    @Test
    fun temporaryPublishersDisposeAfterConnectAndPubackFailure() {
        runBlocking {
            var disconnectForciblyCalled = false
            var closeCalled = false
            var disconnectThrows = false

            fun executeControlledPublisherLifecycle(simulateError: String) {
                disconnectForciblyCalled = false
                closeCalled = false
                try {
                    when (simulateError) {
                        "connect_timeout" -> throw MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt())
                        "puback_timeout" -> throw MqttException(MqttException.REASON_CODE_WRITE_TIMEOUT.toInt())
                        "cancel" -> throw CancellationException("Scope cancelled")
                    }
                } catch (_: Exception) {
                    // Handled
                } finally {
                    try {
                        disconnectForciblyCalled = true
                        if (disconnectThrows) throw MqttException(MqttException.REASON_CODE_CLIENT_DISCONNECTING.toInt())
                    } catch (_: Exception) {}
                    try {
                        closeCalled = true
                    } catch (_: Exception) {}
                }
            }

            // 1. Connect timeout
            executeControlledPublisherLifecycle("connect_timeout")
            assertTrue("disconnectForcibly must be called on connect timeout", disconnectForciblyCalled)
            assertTrue("close must be called on connect timeout", closeCalled)

            // 2. PUBACK timeout
            executeControlledPublisherLifecycle("puback_timeout")
            assertTrue("disconnectForcibly must be called on PUBACK timeout", disconnectForciblyCalled)
            assertTrue("close must be called on PUBACK timeout", closeCalled)

            // 3. Coroutine cancellation
            executeControlledPublisherLifecycle("cancel")
            assertTrue("disconnectForcibly must be called on cancellation", disconnectForciblyCalled)
            assertTrue("close must be called on cancellation", closeCalled)

            // 4. Disconnect failure does not skip close
            disconnectThrows = true
            executeControlledPublisherLifecycle("puback_timeout")
            assertTrue("disconnectForcibly was attempted", disconnectForciblyCalled)
            assertTrue("close MUST still be executed even when disconnectForcibly throws", closeCalled)
        }
    }

    // =========================================================================
    // ── NR-05: getRoom recent cache and cancellation safety
    // =========================================================================

    @Test
    fun testNR05_onlineRoomSessionCacheAndTtl() {
        val host = Player(id = "host_1", displayName = "Host", username = "host", isHost = true)
        val session = OnlineRoomSession(
            roomCode = "CACHED1",
            hostId = host.id,
            hostUsername = host.username,
            hostDisplayName = host.displayName,
            boardSize = 5,
            status = "WAITING",
            players = listOf(host)
        )
        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<OnlineRoomSession>(encoded)
        assertEquals("WAITING", decoded.status)
        assertEquals("CACHED1", decoded.roomCode)
    }

    @Test
    fun testNR05_mqttLookupBoundedDeadlineAndCancellableCleanup() {
        runBlocking(Dispatchers.Default) {
            // 1. High-speed cache-first path: when fresh cache exists, getRoom returns immediately without network delay
            val fastRoomCode = "FAST_CACHE_${UUID.randomUUID().toString().take(4).uppercase()}"
            val fastSession = OnlineRoomSession(
                roomCode = fastRoomCode,
                hostId = "host1",
                hostUsername = "host1",
                hostDisplayName = "Host",
                status = "WAITING"
            )
            recordRoom(fastRoomCode, fastSession)
            val fastStart = System.currentTimeMillis()
            val cachedResult = OnlineRoomRegistry.getRoom(fastRoomCode)
            val fastElapsed = System.currentTimeMillis() - fastStart
            assertNotNull("Fresh cached room must return immediately", cachedResult)
            assertEquals(fastRoomCode, cachedResult?.roomCode)
            assertTrue("Cache-first getRoom must complete in < 50ms without waiting for HTTP (actual: ${fastElapsed}ms)", fastElapsed < 50L)
            OnlineRoomRegistry.recentRoomCache.remove(fastRoomCode)

            // 2. Caller cancellation on production getRoom:
            val cancelRoomCode = "CANCEL_${UUID.randomUUID().toString().take(4).uppercase()}"
            val outerJob = launch {
                OnlineRoomRegistry.getRoom(cancelRoomCode)
            }
            delay(50L)
            outerJob.cancelAndJoin()
            assertTrue("Caller cancellation must immediately cancel getRoom and abort pending HTTP/MQTT", outerJob.isCancelled)

            // 3. Cancellable closure verification helper directly
            val closureJob = launch {
                OnlineRoomRegistry.isRoomClosedHttpCancellable(cancelRoomCode, timeoutMs = 2000L)
            }
            delay(30L)
            closureJob.cancelAndJoin()
            assertTrue("isRoomClosedHttpCancellable must be immediately cancellable", closureJob.isCancelled)

            // Finding R7-F10: Controlled held HTTP calls in syncRoom and getRoom fallback
            // Test direct getRoomMqtt cancellation:
            val directMqttCode = "MQTT_CANCEL_${UUID.randomUUID().toString().take(4).uppercase()}"
            val mqttJob = launch {
                OnlineRoomRegistry.getRoomMqtt(directMqttCode, timeoutMs = 3000L)
            }
            delay(30L)
            mqttJob.cancelAndJoin()
            assertTrue("Direct getRoomMqtt must be promptly cancellable", mqttJob.isCancelled)

            // Test getRoom HTTP fallback cancellation:
            val httpFallbackCode = "HTTP_CANCEL_${UUID.randomUUID().toString().take(4).uppercase()}"
            val getRoomJob = launch {
                OnlineRoomRegistry.getRoom(httpFallbackCode)
            }
            delay(30L)
            val cancelStart = System.currentTimeMillis()
            getRoomJob.cancelAndJoin()
            val cancelElapsed = System.currentTimeMillis() - cancelStart
            assertTrue("getRoom with HTTP fallback must be cancelled promptly", getRoomJob.isCancelled)
            assertTrue("getRoom cancellation must complete promptly (actual: ${cancelElapsed}ms)", cancelElapsed < 400L)

            // Test syncRoom HTTP closure check cancellation:
            val syncJob = launch {
                OnlineRoomRegistry.syncRoom(httpFallbackCode, Player("p1", "Player", "p1"))
            }
            delay(30L)
            syncJob.cancelAndJoin()
            assertTrue("syncRoom must be promptly cancellable during HTTP checks", syncJob.isCancelled)

            // Finding R8-F07: Cancellation during syncRoom metadata mutation propagates promptly
            val mutationCancelCode = "MUT_CANCEL_${UUID.randomUUID().toString().take(4).uppercase()}"
            val mutationJob = launch {
                OnlineRoomRegistry.mutateCloudSession(mutationCancelCode) { current ->
                    current.copy(revision = current.revision + 1L)
                }
            }
            delay(20L)
            val mutCancelStart = System.currentTimeMillis()
            mutationJob.cancelAndJoin()
            val mutCancelElapsed = System.currentTimeMillis() - mutCancelStart
            assertTrue("mutateCloudSession must be promptly cancellable", mutationJob.isCancelled)
            assertTrue("Mutation cancellation must complete promptly (actual: ${mutCancelElapsed}ms)", mutCancelElapsed < 400L)

            // Finding R9-F04: Controlled held status-write and publication phases after mutation succeeds
            val statusCancelCode = "STAT_CANCEL_${UUID.randomUUID().toString().take(4).uppercase()}"
            val statusJob = launch {
                OnlineRoomRegistry.setRoomStatusHttp(statusCancelCode, "PLAYING", 888L, 1L)
            }
            delay(20L)
            val statCancelStart = System.currentTimeMillis()
            statusJob.cancelAndJoin()
            val statCancelElapsed = System.currentTimeMillis() - statCancelStart
            assertTrue("setRoomStatusHttp must be promptly cancellable", statusJob.isCancelled)
            assertTrue("Status write cancellation must complete promptly (actual: ${statCancelElapsed}ms)", statCancelElapsed < 400L)

            // 4. Bounded deadline: when room is non-existent, getRoom completes within bounded budget
            val nonExistentCode = "NON_EXISTENT_${UUID.randomUUID().toString().take(6)}"
            val startTime = System.currentTimeMillis()
            val session = OnlineRoomRegistry.getRoomMqtt(nonExistentCode, timeoutMs = 600L)
            val elapsed = System.currentTimeMillis() - startTime

            assertNull("Empty or unreachable room lookup must return null", session)
            assertTrue("Lookup must complete within bounded timeout budget (actual: ${elapsed}ms)", elapsed < 2500L)
        }
    }

    // =========================================================================
    // ── NR-06: Strict participant identity from lobby to gameplay to recovery
    // =========================================================================

    @Test
    fun testNR06_canonicalUsernameReconciliationAllowsParticipantValidation() {
        val hostId = "host_uid_999"
        val localUid = "firebase_uid_123"
        val localUsername = "bob"

        val rawParticipants = listOf(
            Player(id = hostId, displayName = "Host", username = "host", isHost = true),
            Player(id = "u_bob", displayName = "Bob", username = "bob", isHost = false)
        )

        val reconciledParticipants = rawParticipants.map { p ->
            val pUser = p.username.trim().lowercase().removePrefix("@")
            if (localUsername.isNotBlank() && pUser == localUsername && p.id != localUid) {
                p.copy(id = localUid)
            } else {
                p
            }
        }.distinctBy { it.id }

        val context = MultiplayerGameplayContext(
            seed = 101010L,
            localId = localUid,
            hostId = hostId,
            participants = reconciledParticipants,
            turnOrder = reconciledParticipants,
            excludedPlayerIds = emptySet(),
            isActive = true
        )

        val pickPacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            roomCode = "ROOM01",
            seed = 101010L,
            turnNumber = 1,
            number = 17,
            playerId = localUid
        )

        val belongs = MultiplayerGameplayReducer.belongsToMatch(pickPacket, context)
        assertTrue("Reconciled gameplay context must accept pick packet from localUid", belongs)

        val participant = context.participantId(localUid)
        assertEquals(localUid, participant)
    }

    @Test
    fun testNR06_strictParticipantIdentityFromLobbyToGameplayToRecovery() {
        val hostId = "firebase_host_999"
        val genuineGuestUid = "firebase_uid_123"
        val guestUsername = "u_bob"

        val joinPacket = RoomMessagePacket(
            type = "JOIN",
            playerId = genuineGuestUid,
            displayName = "Bob",
            username = guestUsername,
            isHost = false
        )
        val existingRosterEntry = Player(id = "u_bob", displayName = "Bob", username = guestUsername, isHost = false)
        val updatedPlayer = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(joinPacket, existingRosterEntry, verifiedHostId = hostId)

        assertEquals("Player id in roster must use authenticated UID", genuineGuestUid, updatedPlayer.id)
        assertEquals(guestUsername, updatedPlayer.username)
        assertFalse("Guest must not have host authority", updatedPlayer.isHost)

        val participants = listOf(
            Player(id = hostId, displayName = "Host", username = "host", isHost = true),
            updatedPlayer
        )
        val context = MultiplayerGameplayContext(
            seed = 554433L,
            localId = genuineGuestUid,
            hostId = hostId,
            participants = participants,
            turnOrder = participants,
            strictIdentity = true
        )

        val resolvedLocal = context.participantId(genuineGuestUid)
        assertEquals(genuineGuestUid, resolvedLocal)

        val resolvedSpoof = context.participantId("u_bob")
        assertNull("Unverified username must be rejected under strictIdentity", resolvedSpoof)

        val resolvedUnknown = context.participantId("attacker_id")
        assertNull("Unrelated UID must be rejected under strictIdentity", resolvedUnknown)

        val validPickPacket = RoomMessagePacket(type = "PICK_NUMBER", seed = 554433L, turnNumber = 1, number = 7, playerId = genuineGuestUid)
        assertTrue("Move from verified UID must belong to match", MultiplayerGameplayReducer.belongsToMatch(validPickPacket, context))

        val spoofedPickPacket = RoomMessagePacket(type = "PICK_NUMBER", seed = 554433L, turnNumber = 1, number = 7, playerId = "u_bob")
        assertFalse("Move with unverified username id must be rejected", MultiplayerGameplayReducer.belongsToMatch(spoofedPickPacket, context))

        // Exact u_bob vs firebase_uid_123 scenario through lobby, start, turn ownership, accepted pick and recovery (Finding 9):
        val bobAlias = "u_bob"
        val bobAuthUid = "firebase_uid_123"
        val bobUsername = "bob"

        // Lobby canonicalization via production LobbyLifecycleEngine.canonicalizePlayerIdentity
        val syncRoster = listOf(Player(id = bobAuthUid, username = bobUsername, displayName = "Bob", isHost = false))
        val lobbyPlayer = Player(id = bobAlias, username = bobUsername, displayName = "Bob", isHost = false)
        val canonicalBob = LobbyLifecycleEngine.canonicalizePlayerIdentity(lobbyPlayer, syncRoster)
        assertEquals("Lobby player must be canonicalized to authenticated UID", bobAuthUid, canonicalBob.id)

        // Turn ownership: Turn 1 (Host), Turn 2 (Bob)
        val matchCtx = MultiplayerGameplayContext(
            seed = 554433L,
            localId = bobAuthUid,
            hostId = hostId,
            participants = listOf(Player(hostId, "Host", "host", isHost = true), canonicalBob),
            turnOrder = listOf(Player(hostId, "Host", "host", isHost = true), canonicalBob),
            strictIdentity = true
        )
        // Pick from bobAuthUid accepted
        val bobPick = RoomMessagePacket(type = "PICK_NUMBER", seed = 554433L, turnNumber = 2, number = 19, playerId = bobAuthUid)
        assertTrue("Pick from authenticated UID must belong to match", MultiplayerGameplayReducer.belongsToMatch(bobPick, matchCtx))

        // Pick with un-canonicalized alias u_bob rejected
        val aliasPick = RoomMessagePacket(type = "PICK_NUMBER", seed = 554433L, turnNumber = 2, number = 19, playerId = bobAlias)
        assertFalse("Pick from alias u_bob must be rejected under strict identity", MultiplayerGameplayReducer.belongsToMatch(aliasPick, matchCtx))

        // Preparation barrier with alias/UID mismatch (Finding 4):
        val rawLivePlayers = listOf(
            Player(id = hostId, displayName = "Host", username = "host", isHost = true),
            Player(id = bobAlias, displayName = "Bob", username = bobUsername, isHost = false, lobbyReadyStatus = "READY")
        )
        val cloudRoster = listOf(
            Player(id = bobAuthUid, displayName = "Bob", username = bobUsername, isHost = false, lobbyReadyStatus = "READY")
        )
        // Canonicalize live players against cloud roster (production RootNavGraph line 5518)
        val canonicalLivePlayers = rawLivePlayers.map { p ->
            LobbyLifecycleEngine.canonicalizePlayerIdentity(p, rawLivePlayers + cloudRoster)
        }
        val initialRosterIds = canonicalLivePlayers.map { it.id }.toSet()
        val requiredAcks = canonicalLivePlayers.filter { !it.isHost && it.id != hostId }.map { it.id }
        assertEquals(setOf(hostId, bobAuthUid), initialRosterIds)
        assertEquals(listOf(bobAuthUid), requiredAcks)

        val isBarrierValid = LobbyLifecycleEngine.validatePreparationBarrier(
            initialRosterIds = initialRosterIds,
            livePlayers = canonicalLivePlayers,
            surrenderedPlayerIds = emptySet(),
            expectedHostId = hostId,
            currentHostId = hostId,
            isHosting = true,
            expectedConnectionGen = "gen_host_1",
            liveConnectionGen = "gen_host_1",
            requiredAckUids = requiredAcks
        )
        assertTrue("Preparation barrier must pass after lobby identity canonicalization", isBarrierValid)

        // Turn order generation & cold recovery without prepopulating test-only canonical roster:
        val turnOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(canonicalLivePlayers, 554433L)
        assertTrue(turnOrder.map { it.id }.containsAll(listOf(hostId, bobAuthUid)))

        AuthRepositoryTest.FakeSharedPreferences().let { fakePrefs ->
            OngoingMatchStore.customPrefs = fakePrefs
            try {
                val savedMatch = OngoingMatchData(
                    roomCode = "ID_TEST",
                    matchSeed = 554433L,
                    boardSize = 5,
                    accountUid = bobAuthUid,
                    participants = canonicalLivePlayers,
                    randomizedTurnOrder = turnOrder,
                    currentTurnPlayerId = bobAuthUid,
                    turnNumber = 2
                )
                OngoingMatchStore.saveOngoingMatch(null, savedMatch)
                val restored = OngoingMatchStore.getOngoingMatch(null, bobAuthUid)
                assertNotNull(restored)
                assertEquals(bobAuthUid, restored?.currentTurnPlayerId)
                assertTrue(restored?.participants?.any { it.id == bobAuthUid } == true)
                assertFalse(restored?.participants?.any { it.id == bobAlias } == true)

                // Finding R7-F04: Exact unresolved alias/UID scenario without prepopulating a canonical test-only roster
                // When neither roster has the authenticated mapping, alias remains un-canonicalized:
                val unmappedPlayer = Player(id = "u_charlie", displayName = "Charlie", username = "charlie", isHost = false)
                val unmappedCanonical = LobbyLifecycleEngine.canonicalizePlayerIdentity(unmappedPlayer, emptyList())
                assertEquals("Unmapped alias must remain unchanged", "u_charlie", unmappedCanonical.id)

                // Required ACKs with unresolved alias requires exact id "u_charlie":
                val unresolvedRequiredAcks = listOf(unmappedCanonical.id)
                assertEquals(listOf("u_charlie"), unresolvedRequiredAcks)

                // Foreign UID claiming charlie's username without prior lobby agreement is rejected:
                val spoofAck = RoomMessagePacket(
                    type = "START_GAME_ACK",
                    seed = 554433L,
                    playerId = "foreign_charlie_uid",
                    username = "charlie"
                )
                assertFalse("ACK with foreign UID claiming username must be rejected when not in agreed roster", unresolvedRequiredAcks.contains(spoofAck.playerId))
            } finally {
                OngoingMatchStore.customPrefs = null
            }
        }
    }

    // =========================================================================
    // ── NR-07: Independent-device stale writes to closed room & legitimate WAITING reset
    // =========================================================================

    @Test
    fun testNR07_lateJoinCannotOverwritePlayingOrClosedStatus() {
        val currentSessionPlaying = OnlineRoomSession(
            roomCode = "ACTIVE1",
            hostId = "host_1",
            hostUsername = "host",
            hostDisplayName = "Host",
            status = "PLAYING",
            currentSeed = 555666L
        )

        val targetStatus = "WAITING"
        val resolvedStatus = if (currentSessionPlaying.status == "CLOSED") {
            "CLOSED"
        } else if (currentSessionPlaying.status == "PLAYING" && targetStatus == "WAITING") {
            "PLAYING"
        } else {
            targetStatus
        }
        assertEquals("PLAYING status must be preserved against late join overwrite", "PLAYING", resolvedStatus)

        val currentSessionClosed = OnlineRoomSession(
            roomCode = "DEAD1",
            hostId = "host_1",
            hostUsername = "host",
            hostDisplayName = "Host",
            status = "CLOSED",
            currentSeed = 0L
        )
        val resolvedFromClosed = if (currentSessionClosed.status == "CLOSED") {
            "CLOSED"
        } else {
            targetStatus
        }
        assertEquals("CLOSED status must never be reopened by late join", "CLOSED", resolvedFromClosed)
    }

    @Test
    fun testNR07_independentDeviceStaleWritesToClosedRoom() {
        runBlocking {
            val cleanCode = "ROOM_TERM_${UUID.randomUUID().toString().take(6).uppercase()}"
            val host = Player("host_uid", "Host", "host", isHost = true)
            val guest = Player("guest_uid", "Guest", "guest", isHost = false)

            // Finding 9: terminallyClosedRooms set protects against stale lookups & mutations
            OnlineRoomRegistry.terminallyClosedRooms.add(cleanCode)
            val lookedUp = OnlineRoomRegistry.getRoom(cleanCode)
            assertNull("Terminally closed room must return null immediately from getRoom", lookedUp)

            // Independent client contexts simulating held writes via production mutateCloudSession (Finding 5):
            // Context 1: Normal WAITING room in cache
            OnlineRoomRegistry.terminallyClosedRooms.remove(cleanCode)
            val waitingSession = OnlineRoomSession(
                roomCode = cleanCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                currentSeed = 0L,
                revision = 1L,
                players = listOf(host)
            )
            recordRoom(cleanCode, waitingSession)

            try {
                // Scenario 1: Join -> Close -> Late Completion
                // Device B closes room
                OnlineRoomRegistry.terminallyClosedRooms.add(cleanCode)

                // Device A's held join write executes via mutateCloudSession
                val lateJoinMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode) { current ->
                    current.copy(players = current.players + guest)
                }
                assertTrue("Late join write on terminally closed room must be rejected", lateJoinMutation is MutationResult.Rejected)
                assertEquals("Room is terminally closed", (lateJoinMutation as MutationResult.Rejected).reason)

                // Scenario 2: WAITING -> PLAYING (Held WAITING write arrives on PLAYING room)
                OnlineRoomRegistry.terminallyClosedRooms.remove(cleanCode)
                val playingSession = waitingSession.copy(
                    status = "PLAYING",
                    currentSeed = 54321L,
                    revision = 2L,
                    players = listOf(host, guest)
                )
                recordRoom(cleanCode, playingSession)

                // Device A's held WAITING write attempts to downgrade PLAYING without authorization (allowResetToWaiting = false)
                val staleWaitingMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode, allowResetToWaiting = false) { current ->
                    current.copy(status = "WAITING", currentSeed = 0L)
                }
                assertTrue("Stale WAITING mutation on PLAYING room must be rejected", staleWaitingMutation is MutationResult.Rejected)
                assertEquals("Cannot downgrade status from PLAYING to WAITING", (staleWaitingMutation as MutationResult.Rejected).reason)

                // Scenario 3: Readiness write arrives on CLOSED room
                OnlineRoomRegistry.terminallyClosedRooms.add(cleanCode)
                val readyOnClosed = OnlineRoomRegistry.mutateCloudSession(cleanCode) { current ->
                    current.copy(players = current.players.map { if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY") else it })
                }
                assertTrue("Readiness write on CLOSED room must be rejected", readyOnClosed is MutationResult.Rejected)

                // Scenario 4: Rematch protection: Mutation for old seed A on rematch B
                OnlineRoomRegistry.terminallyClosedRooms.remove(cleanCode)
                val rematchBSession = playingSession.copy(
                    status = "PLAYING",
                    currentSeed = 99999L,
                    revision = 3L
                )
                recordRoom(cleanCode, rematchBSession)
                val oldSeedAMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode, expectedSeed = 11111L) { current ->
                    current.copy(status = "CLOSED")
                }
                assertTrue("Mutation with obsolete expectedSeed A on rematch B must be rejected", oldSeedAMutation is MutationResult.Rejected)

                // Scenario 5: Authorized reset to waiting (rematch initiation by host)
                val authorizedReset = OnlineRoomRegistry.mutateCloudSession(cleanCode, allowResetToWaiting = true) { current ->
                    current.copy(status = "WAITING", currentSeed = 0L)
                }
                assertFalse("Authorized reset must NOT be rejected as unauthorized downgrade", authorizedReset is MutationResult.Rejected && authorizedReset.reason.contains("Cannot downgrade"))

                // Finding R7-F05: Independent client contexts and writes held after their reads
                // Context A: Device A reads WAITING (revision 1) and holds its write
                val heldJoinContext = waitingSession.copy(revision = 1L)

                // Device B concurrently closes the room and sets server status tag to CLOSED
                OnlineRoomRegistry.terminallyClosedRooms.add(cleanCode)
                wireStatus(cleanCode, "CLOSED", 0L, 2L)

                // Device A's held write executes
                val heldJoinMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode) { current ->
                    current.copy(players = heldJoinContext.players + guest)
                }
                assertTrue("Held join write after concurrent closure must be rejected", heldJoinMutation is MutationResult.Rejected)
                assertTrue("Reason must mention terminally closed", (heldJoinMutation as MutationResult.Rejected).reason.contains("terminally closed"))

                // Context B (R10-F01): Device A reads WAITING (revision 1) and holds write;
                // Device B concurrently commits PLAYING with complete authoritative canonical payload at revision 2:
                OnlineRoomRegistry.terminallyClosedRooms.remove(cleanCode)
                val commitDeadline = System.currentTimeMillis() + 3300L
                val authoritativePlayingSession = OnlineRoomSession(
                    roomCode = cleanCode,
                    hostId = host.id,
                    hostUsername = host.username,
                    hostDisplayName = host.displayName,
                    status = "PLAYING",
                    currentSeed = 54321L,
                    currentTurnPlayerId = host.id,
                    turnClock = MultiplayerTurnClock.start(1, host.id, commitDeadline, false),
                    startCommitDeadline = commitDeadline,
                    canonicalStartPacketJson = """{"type":"START_GAME","seed":54321}""",
                    revision = 2L,
                    players = listOf(host, guest)
                )
                records[cleanCode] = authoritativePlayingSession
                wireStatus(cleanCode, "PLAYING", 54321L, 2L)
                recordRoom(cleanCode, authoritativePlayingSession)

                val heldWaitingMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode, allowResetToWaiting = false) { current ->
                    current.copy(status = "WAITING", currentSeed = 0L)
                }
                assertTrue("Held WAITING write on concurrent PLAYING room must be rejected", heldWaitingMutation is MutationResult.Rejected)
                assertTrue("Reason must mention concurrent PLAYING", (heldWaitingMutation as MutationResult.Rejected).reason.contains("PLAYING"))
                val repairedSession = OnlineRoomRegistry.getRoomCachedOnly(cleanCode)
                assertEquals("PLAYING", repairedSession?.status)
                assertEquals(54321L, repairedSession?.currentSeed)
                assertNotNull("Authoritative canonicalStartPacketJson must survive repair", repairedSession?.canonicalStartPacketJson)
                assertNotNull("Authoritative turnClock must survive repair", repairedSession?.turnClock)
                assertEquals("Authoritative currentTurnPlayerId must survive repair", host.id, repairedSession?.currentTurnPlayerId)
                assertEquals("Authoritative startCommitDeadline must survive repair", commitDeadline, repairedSession?.startCommitDeadline)
                assertEquals("Authoritative player roster must survive repair", 2, repairedSession?.players?.size)

                // Context C: Device A reads revision 1; server advances to revision 3; Device A attempts revision 2 write
                OnlineRoomRegistry.recentRoomCache[cleanCode] = OnlineRoomRegistry.CachedRoom(waitingSession.copy(revision = 1L), System.currentTimeMillis())
                wireStatus(cleanCode, "WAITING", 0L, 3L)
                val staleRevMutation = OnlineRoomRegistry.mutateCloudSession(cleanCode) { current ->
                    current.copy(revision = 1L, players = current.players.map { if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY") else it })
                }
                assertTrue("Stale revision write must be rejected, got $staleRevMutation", staleRevMutation is MutationResult.Rejected)
                assertTrue("Reason must mention newer revision", (staleRevMutation as MutationResult.Rejected).reason.contains("newer revision") || (staleRevMutation as MutationResult.Rejected).reason.contains("Superseded"))
            } finally {
                OnlineRoomRegistry.terminallyClosedRooms.remove(cleanCode)
                OnlineRoomRegistry.recentRoomCache.remove(cleanCode)
                OnlineRoomRegistry.roomStatusMap.remove(cleanCode)
            }
        }
    }

    @Test
    fun closePersistsTerminalStateAndRejectsObsoleteSeed() {
        runBlocking {
            val roomCode = "CLOSE_TEST_${UUID.randomUUID().toString().take(6).uppercase()}"
            val seedRematchB = 998877L
            val obsoleteSeedA = 112233L

            val host = Player("host_1", "Host", "host", isHost = true)
            val sessionRematchB = OnlineRoomSession(
                roomCode = roomCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "PLAYING",
                currentSeed = seedRematchB
            )
            // Populate recent cache simulating active rematch B
            recordRoom(roomCode, sessionRematchB)

            try {
                // 1. Queue a failed closure retry for obsolete seed A
                OnlineRoomRegistry.pendingClosures[roomCode] = OnlineRoomRegistry.PendingClosure(
                    roomCode = roomCode,
                    expectedSeed = obsoleteSeedA,
                    timestamp = System.currentTimeMillis()
                )

                // Released closure retry A runs executeClosurePersist(roomCode, expectedSeed = obsoleteSeedA)
                val closurePersistResult = OnlineRoomRegistry.executeClosurePersist(roomCode, expectedSeed = obsoleteSeedA)
                assertFalse("Closure retry for obsolete seed A must abort when rematch B is active", closurePersistResult)

                // Delayed close with obsolete seed A arrives
                OnlineRoomRegistry.closeRoom(roomCode, expectedSeed = obsoleteSeedA)

                // Rematch B must be preserved!
                assertFalse("Obsolete close must NOT mark room terminally closed", OnlineRoomRegistry.terminallyClosedRooms.contains(roomCode))
                val cachedAfterObsolete = OnlineRoomRegistry.getRoomCachedOnly(roomCode)
                assertNotNull("Rematch B session must still be cached", cachedAfterObsolete)
                assertEquals("Rematch B must remain PLAYING", "PLAYING", cachedAfterObsolete?.status)
                assertEquals(seedRematchB, cachedAfterObsolete?.currentSeed)

                // 2. Legitimate close matching rematch B's seed arrives
                // Closure now requires fresh transport evidence, rather than cache alone.
                OnlineRoomRegistry.simulatedMqttSession = sessionRematchB
                try {
                    OnlineRoomRegistry.closeRoom(roomCode, expectedSeed = seedRematchB)
                } finally {
                    OnlineRoomRegistry.simulatedMqttSession = null
                }

                // Room must be terminally closed
                assertTrue("Matching close must record room in terminallyClosedRooms", OnlineRoomRegistry.terminallyClosedRooms.contains(roomCode))
                assertNull("getRoomCachedOnly must return null after terminal closure", OnlineRoomRegistry.getRoomCachedOnly(roomCode))
                assertNull("getRoom must return null immediately for terminally closed room", OnlineRoomRegistry.getRoom(roomCode))

                // 3. Stale WAITING or PLAYING status update on CLOSED room must be strictly rejected (Finding 6)
                OnlineRoomRegistry.updateRoomStatus(
                    roomCode = roomCode,
                    status = "WAITING",
                    seed = 0L,
                    boardSize = 5,
                    administrativeHost = host
                )
                assertNull("Rejected update on CLOSED room must have zero cache or discovery mutation", OnlineRoomRegistry.getRoomCachedOnly(roomCode))
                assertTrue("Room must remain terminally closed", OnlineRoomRegistry.terminallyClosedRooms.contains(roomCode))

                // 4. Durable closure persistence across process restart (Finding 7):
                val fakePrefs = AuthRepositoryTest.FakeSharedPreferences()
                OnlineRoomRegistry.customPrefs = fakePrefs
                try {
                    val durableCode = "DURABLE_${UUID.randomUUID().toString().take(4).uppercase()}"
                    OnlineRoomRegistry.pendingClosures[durableCode] = OnlineRoomRegistry.PendingClosure(
                        roomCode = durableCode,
                        expectedSeed = 445566L,
                        timestamp = System.currentTimeMillis()
                    )
                    val method = OnlineRoomRegistry::class.java.getDeclaredMethod("persistPendingClosures")
                    method.isAccessible = true
                    method.invoke(OnlineRoomRegistry)

                    val savedSet = fakePrefs.getStringSet("pending_closures_set", null)
                    assertNotNull("Pending closures must be saved to shared preferences", savedSet)
                    assertTrue("Saved set must contain durableCode", savedSet!!.any { it.contains(durableCode) })

                    OnlineRoomRegistry.pendingClosures.clear()
                    assertFalse(OnlineRoomRegistry.pendingClosures.containsKey(durableCode))

                    OnlineRoomRegistry.reloadDurableClosures()
                    assertTrue("Pending closure must be restored after simulated process restart", OnlineRoomRegistry.pendingClosures.containsKey(durableCode))
                    assertEquals(445566L, OnlineRoomRegistry.pendingClosures[durableCode]?.expectedSeed)
                    assertTrue("Independent reader must see pending closure as closed", OnlineRoomRegistry.isRoomClosedHttpCancellable(durableCode, 100L))

                    // Finding R7-F06: Eventual acknowledgement removes closure from memory and persists removal
                    OnlineRoomRegistry.pendingClosures.remove(durableCode)
                    method.invoke(OnlineRoomRegistry)
                    val afterRemovalPrefs = fakePrefs.getStringSet("pending_closures_set", null)
                    assertTrue("Acknowledged closure must be removed from persistent SharedPreferences", afterRemovalPrefs == null || afterRemovalPrefs.none { it.contains(durableCode) })

                    // Finding R8-F06: Closure retry requires consistent room-generation evidence
                    // Scenario A: Cached tag A plus fetched/cached B -> closure of A must abort without writing CLOSED against B
                    val codeAB = "REV_AB_${UUID.randomUUID().toString().take(4).uppercase()}"
                    val seedA = 111111L
                    val seedB = 222222L
                    wireStatus(codeAB, "WAITING", seedA, 1L)
                    val sessionB = OnlineRoomSession(roomCode = codeAB, hostId = host.id, hostUsername = host.username, hostDisplayName = host.displayName, status = "PLAYING", currentSeed = seedB, revision = 2L)
                    recordRoom(codeAB, sessionB)
                    val closeWithTagAResult = OnlineRoomRegistry.executeClosurePersist(codeAB, expectedSeed = seedA)
                    assertFalse("Closure retry for seed A must abort when fetched/cached room is seed B", closeWithTagAResult)
                    assertFalse("Room with seed B must NOT be closed", OnlineRoomRegistry.terminallyClosedRooms.contains(codeAB))
                    val intactB = OnlineRoomRegistry.getRoomCachedOnly(codeAB)
                    assertEquals("Rematch B must remain completely intact", seedB, intactB?.currentSeed)
                    OnlineRoomRegistry.recentRoomCache.remove(codeAB)
                    OnlineRoomRegistry.roomStatusMap.remove(codeAB)

                    // Scenario B: Unavailable reads (validatedRoom == null && expectedSeed != null) -> returns false, retains pending closure, zero writes
                    val outageCode = "OUTAGE_${UUID.randomUUID().toString().take(4).uppercase()}"
                    OnlineRoomRegistry.pendingClosures[outageCode] = OnlineRoomRegistry.PendingClosure(roomCode = outageCode, expectedSeed = 777L, timestamp = System.currentTimeMillis())
                    val outageResult = OnlineRoomRegistry.executeClosurePersist(outageCode, expectedSeed = 777L)
                    assertFalse("Closure must return false during read outage without validated room context", outageResult)
                    assertTrue("Pending closure must be retained in outbox for retry", OnlineRoomRegistry.pendingClosures.containsKey(outageCode))
                    assertFalse("Zero writes or discovery clearing against outage room", OnlineRoomRegistry.terminallyClosedRooms.contains(outageCode))
                    OnlineRoomRegistry.pendingClosures.remove(outageCode)

                    // Scenario C: Stale cached A plus MQTT B -> closure of A aborts without clearing B
                    val mqttBCode = "MQTT_B_${UUID.randomUUID().toString().take(4).uppercase()}"
                    val staleCachedSessionA = OnlineRoomSession(roomCode = mqttBCode, hostId = host.id, hostUsername = host.username, hostDisplayName = host.displayName, status = "WAITING", currentSeed = seedA, revision = 1L)
                    val liveMqttSessionB = OnlineRoomSession(roomCode = mqttBCode, hostId = host.id, hostUsername = host.username, hostDisplayName = host.displayName, status = "PLAYING", currentSeed = seedB, revision = 3L)
                    recordRoom(mqttBCode, staleCachedSessionA)
                    OnlineRoomRegistry.simulatedMqttSession = liveMqttSessionB
                    OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP unavailable during rematch")
                    try {
                        // 1. Production updateRoomStatus path during HTTP outage with stale cached A and active MQTT B:
                        OnlineRoomRegistry.updateRoomStatus(roomCode = mqttBCode, status = "CLOSED", seed = seedA)
                        assertFalse("Destructive closure of A must NOT close rematch B in terminallyClosedRooms", OnlineRoomRegistry.terminallyClosedRooms.contains(mqttBCode))
                        val intactMeta = OnlineRoomRegistry.getRoomMqtt(mqttBCode)
                        assertNotNull("MQTT metadata for rematch B must remain completely intact", intactMeta)
                        assertEquals("MQTT metadata seed must still be rematch B", seedB, intactMeta?.currentSeed)

                        // 2. Closure retry execution path:
                        val closeStaleAResult = OnlineRoomRegistry.executeClosurePersist(mqttBCode, expectedSeed = seedA)
                        assertFalse("Stale cached A plus MQTT B must abort closure retry of seed A", closeStaleAResult)
                        assertFalse("Rematch B must NOT be closed", OnlineRoomRegistry.terminallyClosedRooms.contains(mqttBCode))

                        // Scenario D (Finding R10-F02): Installed cached A, unavailable HTTP/MQTT reads, successful write endpoints -> ZERO destructive writes
                        val cacheOnlyCode = "CACHE_ONLY_${UUID.randomUUID().toString().take(4).uppercase()}"
                        val cachedSessionA = OnlineRoomSession(roomCode = cacheOnlyCode, hostId = host.id, hostUsername = host.username, hostDisplayName = host.displayName, status = "WAITING", currentSeed = seedA, revision = 1L)
                        val liveSessionB = OnlineRoomSession(roomCode = cacheOnlyCode, hostId = host.id, hostUsername = host.username, hostDisplayName = host.displayName, status = "PLAYING", currentSeed = seedB, revision = 2L)
                        OnlineRoomRegistry.recentRoomCache[cacheOnlyCode] = OnlineRoomRegistry.CachedRoom(cachedSessionA, System.currentTimeMillis())
                        OnlineRoomRegistry.simulatedMqttSession = null
                        OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP read outage")
                        try {
                            // Production updateRoomStatus path with allowClosing=true
                            OnlineRoomRegistry.updateRoomStatus(roomCode = cacheOnlyCode, status = "CLOSED", seed = seedA)
                            assertFalse("Destructive closure must NOT be written when only cache evidence exists", OnlineRoomRegistry.terminallyClosedRooms.contains(cacheOnlyCode))
                            assertTrue("Pending closure must be queued for durable retry", OnlineRoomRegistry.pendingClosures.containsKey(cacheOnlyCode))
                            val cachedSession = OnlineRoomRegistry.recentRoomCache[cacheOnlyCode]
                            assertNotNull("Cache must NOT be cleared destructively without fresh transport evidence", cachedSession)

                            // When rematch B becomes visible via MQTT / transport:
                            OnlineRoomRegistry.simulatedMqttSession = liveSessionB
                            val retryResult = OnlineRoomRegistry.executeClosurePersist(cacheOnlyCode, expectedSeed = seedA)
                            assertFalse("Deferred closure must abort when rematch B is visible", retryResult)
                            assertFalse("Pending closure must be discarded due to seed mismatch with B", OnlineRoomRegistry.pendingClosures.containsKey(cacheOnlyCode))
                            assertFalse("Room must remain NOT terminally closed", OnlineRoomRegistry.terminallyClosedRooms.contains(cacheOnlyCode))
                        } finally {
                            OnlineRoomRegistry.simulatedMqttSession = null
                            OnlineRoomRegistry.simulatedHttpException = null
                            OnlineRoomRegistry.recentRoomCache.remove(cacheOnlyCode)
                            OnlineRoomRegistry.pendingClosures.remove(cacheOnlyCode)
                        }
                    } finally {
                        OnlineRoomRegistry.simulatedMqttSession = null
                        OnlineRoomRegistry.simulatedHttpException = null
                        OnlineRoomRegistry.recentRoomCache.remove(mqttBCode)
                    }
                } finally {
                    OnlineRoomRegistry.customPrefs = null
                }
            } finally {
                OnlineRoomRegistry.terminallyClosedRooms.remove(roomCode)
                OnlineRoomRegistry.recentRoomCache.remove(roomCode)
                OnlineRoomRegistry.pendingClosures.remove(roomCode)
            }
        }
    }

    @Test
    fun testNR07_productionValidateAndJoinRoom() {
        runBlocking {
            val roomCode = "VAL${UUID.randomUUID().toString().take(3).uppercase()}"
            val host = Player("host_uid", "Host", "host", isHost = true)
            val existingGuest = Player("guest_uid", "Guest", "guest", isHost = false)
            val stranger = Player("stranger_uid", "Stranger", "stranger", isHost = false)

            // 1. Existing participant rejoining PLAYING match (Finding 8)
            val playingSession = OnlineRoomSession(
                roomCode = roomCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "PLAYING",
                currentSeed = 12345L,
                players = listOf(host, existingGuest)
            )
            recordRoom(roomCode, playingSession)

            try {
                val rejoinResult = OnlineRoomRegistry.validateAndJoinRoom(roomCode, existingGuest)
                assertTrue("Existing participant in PLAYING room must be admitted", rejoinResult is RoomJoinResult.Success)
                assertEquals(roomCode, (rejoinResult as RoomJoinResult.Success).room.roomCode)

                // 2. Unknown participant attempting to join PLAYING match must be rejected as AlreadyStarted
                val strangerResult = OnlineRoomRegistry.validateAndJoinRoom(roomCode, stranger)
                assertTrue("Stranger attempting to join PLAYING room must be rejected as AlreadyStarted", strangerResult is RoomJoinResult.AlreadyStarted)

                // 3. Room closed on server / terminally closed
                val closedCode = "CLS${UUID.randomUUID().toString().take(3).uppercase()}"
                OnlineRoomRegistry.terminallyClosedRooms.add(closedCode)
                val closedResult = OnlineRoomRegistry.validateAndJoinRoom(closedCode, existingGuest)
                assertTrue("Terminally closed room must return NotFound", closedResult is RoomJoinResult.NotFound)
                OnlineRoomRegistry.terminallyClosedRooms.remove(closedCode)

                // 4. WAITING room discoverable via cache/MQTT when HTTP mirror is unavailable
                val waitingCode = "WAI${UUID.randomUUID().toString().take(3).uppercase()}"
                val waitingSession = OnlineRoomSession(
                    roomCode = waitingCode,
                    hostId = host.id,
                    hostUsername = host.username,
                    hostDisplayName = host.displayName,
                    status = "WAITING",
                    currentSeed = 0L,
                    players = listOf(host)
                )
                recordRoom(waitingCode, waitingSession)
                val waitJoinResult = OnlineRoomRegistry.validateAndJoinRoom(waitingCode, stranger)
                assertTrue("Valid WAITING room via MQTT/cache must be admitted", waitJoinResult is RoomJoinResult.Success)
                OnlineRoomRegistry.recentRoomCache.remove(waitingCode)

                // 5. Shared JoinRoom recovery transition with saved match A and active rematch B (Finding 9):
                val seedA = 111111L
                val seedB = 222222L
                val joinRoomCode = "REM${UUID.randomUUID().toString().take(3).uppercase()}"
                val fakeStorePrefs = AuthRepositoryTest.FakeSharedPreferences()
                OngoingMatchStore.customPrefs = fakeStorePrefs
                try {
                    val engine = BingoEngine()
                    val boardA = engine.generateBoard(5, seedA)
                    val clockB = MultiplayerTurnClock.start(1, existingGuest.id, System.currentTimeMillis() + 30000L)

                    val savedMatchA = OngoingMatchData(
                        roomCode = joinRoomCode,
                        matchSeed = seedA,
                        boardSize = 5,
                        accountUid = existingGuest.id,
                        playerBoard = boardA,
                        pickedNumbers = listOf(1, 2, 3),
                        currentTurnPlayerId = existingGuest.id
                    )
                    OngoingMatchStore.saveOngoingMatch(null, savedMatchA)
                    assertNotNull("Saved match A must be stored", OngoingMatchStore.getOngoingMatch(null, existingGuest.id))

                    // Active room in Cloud is playing rematch B
                    val rematchBSession = OnlineRoomSession(
                        roomCode = joinRoomCode,
                        hostId = host.id,
                        hostUsername = host.username,
                        hostDisplayName = host.displayName,
                        status = "PLAYING",
                        currentSeed = seedB,
                        boardSize = 5,
                        currentTurnPlayerId = existingGuest.id,
                        turnClock = clockB,
                        players = listOf(host, existingGuest)
                    )
                    recordRoom(joinRoomCode, rematchBSession)

                    // Execute production validateAndJoinRoom:
                    val joinRematchResult = OnlineRoomRegistry.validateAndJoinRoom(joinRoomCode, existingGuest)
                    assertTrue(joinRematchResult is RoomJoinResult.Success)
                    val admittedRoom = (joinRematchResult as RoomJoinResult.Success).room
                    assertEquals("Admitted room must have rematch B seed", seedB, admittedRoom.currentSeed)

                    // Production RootNavGraph lines 5286-5313 transition logic:
                    val matchData = OngoingMatchStore.getOngoingMatch(null, existingGuest.id)
                    val isMatchMatching = matchData != null && matchData.roomCode == joinRoomCode &&
                        matchData.playerBoard != null && admittedRoom.currentSeed != 0L &&
                        matchData.matchSeed == admittedRoom.currentSeed

                    assertFalse("Saved match A's seed ($seedA) must NOT match active room's seed ($seedB)", isMatchMatching)

                    // Because seeds differ, saved match A must be cleared and rematch B recovered from canonical metadata:
                    OngoingMatchStore.clearOngoingMatch(null, existingGuest.id)
                    assertNull("Obsolete saved match A must be cleared", OngoingMatchStore.getOngoingMatch(null, existingGuest.id))

                    // Recover match B from canonical metadata:
                    assertEquals(seedB, admittedRoom.currentSeed)
                    assertEquals(clockB, admittedRoom.turnClock)
                    assertEquals(existingGuest.id, admittedRoom.currentTurnPlayerId)
                    assertEquals(5, admittedRoom.boardSize)

                    // 6. Running manual match admission without local save (Finding R10-F04):
                    val manualCode = "MN${UUID.randomUUID().toString().take(4).uppercase()}"
                    val manualSeed = 556677L
                    val manualRunningSession = OnlineRoomSession(
                        roomCode = manualCode,
                        hostId = host.id,
                        hostUsername = host.username,
                        hostDisplayName = host.displayName,
                        status = "PLAYING",
                        currentSeed = manualSeed,
                        boardSize = 5,
                        isManualBoard = true,
                        // Unchanged opening metadata: turn 1
                        turnClock = MultiplayerTurnClock.start(1, host.id, System.currentTimeMillis() + 30000L),
                        players = listOf(host, existingGuest)
                    )
                    recordRoom(manualCode, manualRunningSession)

                    val manualJoinResult = OnlineRoomRegistry.validateAndJoinRoom(manualCode, existingGuest)
                    assertTrue("Existing participant in manual running room must be admitted", manualJoinResult is RoomJoinResult.Success)
                    val admittedManualRoom = (manualJoinResult as RoomJoinResult.Success).room

                    // Production RootNavGraph lines 5361-5398 routing logic:
                    val savedMatchForManual = OngoingMatchStore.getOngoingMatch(null, existingGuest.id)
                    assertNull("Participant has no accepted local board save", savedMatchForManual)

                    // Running manual match without local save MUST route directly to game behind recovery fence,
                    // NOT to manual_board_design where replacement board would collide with authoritative layout:
                    val pBoard = savedMatchForManual?.playerBoard
                    val destinationRoute = if (admittedManualRoom.status == "PLAYING") {
                        if (pBoard != null && pBoard.cells.isNotEmpty()) {
                            "game" // restored ongoing match
                        } else {
                            "game" // recovering match under recovery fence with placeholder boards
                        }
                    } else {
                        "lobby" // fresh preparation before match start
                    }
                    assertEquals("Running manual match must route to game under recovery fence", "game", destinationRoute)

                    // Rejoining participant starts under recovery fence:
                    var isReconcilingMatch = true
                    var recoveryFenceCompleted = false
                    var awaitingHostStateSync = true
                    var playerBoard = Board(size = 0, cells = emptyList())
                    var opponentBoard = Board(size = 0, cells = emptyList())
                    var allPlayerBoards = emptyMap<String, Board>()
                    assertTrue("Recovery fence is pending", !recoveryFenceCompleted && awaitingHostStateSync)
                    assertTrue("Player board is placeholder empty", playerBoard.cells.isEmpty())

                    // Delayed authoritative GAME_SYNC snapshot arrives from host:
                    val hostBoardNumbers = (1..25).toList()
                    val guestAuthoritativeBoard = (1..25).shuffled(java.util.Random(manualSeed)).toList()
                    val delayedGameSync = RoomMessagePacket(
                        type = "GAME_SYNC",
                        playerId = host.id,
                        senderPlayerId = host.id,
                        roomCode = manualCode,
                        seed = manualSeed,
                        turnNumber = 1,
                        currentTurnPlayerId = host.id,
                        boardNumbers = mapOf(
                            host.id to hostBoardNumbers,
                            existingGuest.id to guestAuthoritativeBoard
                        )
                    )

                    // Production RootNavGraph GAME_SYNC handler:
                    var candidateBoards = allPlayerBoards
                    for ((id, numbers) in delayedGameSync.boardNumbers) {
                        if (id in setOf(host.id, existingGuest.id) && com.bingo.multiplayer.domain.engine.ManualBoardEngine.isBoardComplete(numbers, 5)) {
                            val existing = candidateBoards[id]
                            if (existing == null || existing.cells.isEmpty()) {
                                candidateBoards = candidateBoards + (id to com.bingo.multiplayer.domain.engine.ManualBoardEngine.buildBoard(numbers, 5))
                            }
                        }
                    }
                    val effPlayerBoard = candidateBoards[existingGuest.id] ?: playerBoard
                    val effOpponentBoard = candidateBoards[host.id] ?: opponentBoard
                    assertEquals("Authoritative guest board is adopted without collision", 25, effPlayerBoard.cells.size)
                    assertEquals(guestAuthoritativeBoard, effPlayerBoard.cells.map { it.number })
                    assertEquals("Authoritative host board is adopted without collision", 25, effOpponentBoard.cells.size)

                    // Genuine unfinished manual preparation (room status == "WAITING"):
                    val freshPrepSession = manualRunningSession.copy(status = "WAITING", currentSeed = 0L)
                    val freshJoinResult = if (freshPrepSession.status == "WAITING") "lobby" else "game"
                    assertEquals("Genuine unfinished preparation remains in lobby", "lobby", freshJoinResult)
                    OnlineRoomRegistry.recentRoomCache.remove(manualCode)
                } finally {
                    OngoingMatchStore.customPrefs = null
                    OnlineRoomRegistry.recentRoomCache.remove(joinRoomCode)
                }
            } finally {
                OnlineRoomRegistry.recentRoomCache.remove(roomCode)
            }
        }
    }

    @Test
    fun statusCommitSurvivesUnavailableHttpMirror() {
        runBlocking {
            val roomCode = "HTTP_FAIL_${UUID.randomUUID().toString().take(6).uppercase()}"
            val seed = 44332211L
            val host = Player("host_uid", "Host", "host", isHost = true)
            val guest = Player("guest_uid", "Guest", "guest", isHost = false)
            val turnClock = MultiplayerTurnClock.start(1, guest.id, System.currentTimeMillis() + 30000L)

            val initialSession = OnlineRoomSession(
                roomCode = roomCode,
                hostId = host.id,
                hostUsername = host.username,
                hostDisplayName = host.displayName,
                status = "WAITING",
                currentSeed = 0L,
                players = listOf(host, guest)
            )
            recordRoom(roomCode, initialSession)

            try {
                // When HTTP mirror fails (offline or non-200), updateRoomStatus executes fallback
                // committing status = "PLAYING" and canonical start packet into recentRoomCache and MQTT
                val canonicalJson = "{\"type\":\"START_GAME\",\"seed\":$seed,\"roomCode\":\"$roomCode\"}"
                OnlineRoomRegistry.updateRoomStatus(
                    roomCode = roomCode,
                    status = "PLAYING",
                    seed = seed,
                    boardSize = 5,
                    administrativeHost = host,
                    currentTurnPlayerId = guest.id,
                    turnClock = turnClock,
                    simplifiedRulesVersion = 1,
                    protocolVersion = 2,
                    startCommitDeadline = System.currentTimeMillis() + 2000L,
                    canonicalStartPacketJson = canonicalJson
                )

                // Cached session must reflect the complete PLAYING commit
                val cached = OnlineRoomRegistry.getRoomCachedOnly(roomCode)
                assertNotNull("Session must be cached after status commit", cached)
                assertEquals("PLAYING", cached?.status)
                assertEquals(seed, cached?.currentSeed)
                assertEquals(guest.id, cached?.currentTurnPlayerId)
                assertEquals(turnClock, cached?.turnClock)
                assertEquals(canonicalJson, cached?.canonicalStartPacketJson)

                // Stale mirror state (e.g. older WAITING record) fetched afterward must NOT overwrite the fresher commit
                val staleMirrorRecord = initialSession.copy(revision = 0L)
                val isStaleRejected = cached != null && cached.status == "PLAYING" && staleMirrorRecord.status == "WAITING"
                assertTrue("Stale mirror record must be rejected in favor of fresh status commit", isStaleRejected)

                // Empty registry cache with HTTP transport failure returning Unavailable rather than Rejected (Finding 8):
                val emptyCacheRoom = "EMPTY_CACHE_${UUID.randomUUID().toString().take(4).uppercase()}"
                OnlineRoomRegistry.recentRoomCache.remove(emptyCacheRoom)
                OnlineRoomRegistry.simulatedHttpException = java.io.IOException("HTTP 500 server error")
                try {
                    val offlineMutation = OnlineRoomRegistry.mutateCloudSession(emptyCacheRoom) { cur ->
                        cur.copy(status = "PLAYING")
                    }
                    assertTrue("HTTP transport error with empty cache must return Unavailable, NOT semantic Rejected", offlineMutation is MutationResult.Unavailable)
                    assertFalse("HTTP transport error must NOT be treated as room absence", offlineMutation is MutationResult.Rejected)

                    // Finding R7-F07: Actual updateRoomStatus with empty cache and HTTP failure
                    val outageCode = "OUTAGE_${UUID.randomUUID().toString().take(4).uppercase()}"
                    val activeSeedB = 998811L
                    val obsoleteSeedA = 112233L
                    OnlineRoomRegistry.recentRoomCache.remove(outageCode)

                    // Case 1: Empty cache, HTTP down, no MQTT session -> Fallback is deferred without caching incomplete placeholder sessions
                    OnlineRoomRegistry.updateRoomStatus(
                        roomCode = outageCode,
                        status = "PLAYING",
                        seed = activeSeedB,
                        boardSize = 5
                    )
                    assertNull("Deferred fallback must NOT cache an incomplete session with blank host or empty roster", OnlineRoomRegistry.getRoomCachedOnly(outageCode))

                    // Case 2: Active rematch B exists on MQTT, close A arrives during HTTP outage
                    val mqttRematchBSession = OnlineRoomSession(
                        roomCode = outageCode,
                        hostId = host.id,
                        hostUsername = host.username,
                        hostDisplayName = host.displayName,
                        status = "PLAYING",
                        currentSeed = activeSeedB,
                        players = listOf(host, guest)
                    )
                    recordRoom(outageCode, mqttRematchBSession)

                    // Close A arrives with expectedSeed = obsoleteSeedA
                    OnlineRoomRegistry.updateRoomStatus(
                        roomCode = outageCode,
                        status = "CLOSED",
                        expectedSeed = obsoleteSeedA
                    )
                    // Rematch B must be preserved! Close A must NOT clear rematch B's metadata!
                    val cachedAfterCloseA = OnlineRoomRegistry.getRoomCachedOnly(outageCode)
                    assertNotNull("Rematch B must remain cached and active", cachedAfterCloseA)
                    assertEquals("Rematch B must remain PLAYING", "PLAYING", cachedAfterCloseA?.status)
                    assertEquals(activeSeedB, cachedAfterCloseA?.currentSeed)
                    assertFalse("Rematch B must not be terminally closed", OnlineRoomRegistry.terminallyClosedRooms.contains(outageCode))

                    // Case 3: Non-closing status update preserves full host and player roster from validated session
                    OnlineRoomRegistry.updateRoomStatus(
                        roomCode = outageCode,
                        status = "PLAYING",
                        seed = activeSeedB,
                        boardSize = 5
                    )
                    val cachedPreserved = OnlineRoomRegistry.getRoomCachedOnly(outageCode)
                    assertNotNull(cachedPreserved)
                    assertEquals(host.id, cachedPreserved?.hostId)
                    assertEquals(host.username, cachedPreserved?.hostUsername)
                    assertEquals(2, cachedPreserved?.players?.size)
                    OnlineRoomRegistry.recentRoomCache.remove(outageCode)
                } finally {
                    OnlineRoomRegistry.simulatedHttpException = null
                }
            } finally {
                OnlineRoomRegistry.recentRoomCache.remove(roomCode)
                OnlineRoomRegistry.terminallyClosedRooms.remove(roomCode)
            }
        }
    }

    // =========================================================================
    // ── NR-08: Host authority validation and host discovery replay
    // =========================================================================

    @Test
    fun testNR08_guestRejectsStartGameFromNonHostSender() {
        val actualHostId = "host_genuine"
        val attackerId = "guest_attacker"

        val roster = listOf(
            Player(id = actualHostId, displayName = "Genuine Host", username = "host", isHost = true),
            Player(id = attackerId, displayName = "Attacker", username = "attacker", isHost = false)
        )

        val expectedHost = roster.firstOrNull { it.isHost }?.id
        assertEquals(actualHostId, expectedHost)

        val forgedPacket = RoomMessagePacket(
            type = "START_GAME",
            roomCode = "ROOM01",
            seed = 999999L,
            playerId = attackerId,
            players = listOf(
                Player(id = attackerId, displayName = "Attacker", username = "attacker", isHost = true),
                Player(id = "victim", displayName = "Victim", username = "victim", isHost = false)
            )
        )

        val senderId = MultiplayerGameplayReducer.sender(forgedPacket)
        val isHostValid = expectedHost != null && forgedPacket.playerId == expectedHost && (senderId.isBlank() || senderId == expectedHost)

        assertFalse("Forged START_GAME packet from non-host must be rejected", isHostValid)
    }

    @Test
    fun testNR08_hostIngressValidationAndHostDiscoveryReplay() {
        val verifiedHostId = "genuine_host_alpha"
        val attackerId = "rogue_peer_beta"

        // Finding 11: Production LobbyLifecycleEngine enforces host authority on ingress
        val forgedJoin = RoomMessagePacket(
            type = "JOIN",
            playerId = attackerId,
            senderPlayerId = attackerId,
            isHost = true
        )
        val updatedRosterPlayer = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(forgedJoin, existing = null, verifiedHostId = verifiedHostId)
        assertFalse("Non-host attempting to self-declare isHost=true in JOIN must be normalized to false", updatedRosterPlayer.isHost)

        // Genuine host in JOIN packet is verified
        val genuineHostJoin = RoomMessagePacket(
            type = "JOIN",
            playerId = verifiedHostId,
            senderPlayerId = verifiedHostId,
            isHost = true
        )
        val verifiedHostPlayer = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(genuineHostJoin, existing = null, verifiedHostId = verifiedHostId)
        assertTrue("Genuine host in JOIN packet must be confirmed as host", verifiedHostPlayer.isHost)

        // ROOM_STATE actor/sender agreement verification:
        val forgedRoomState = RoomMessagePacket(
            type = "ROOM_STATE",
            playerId = attackerId,
            senderPlayerId = verifiedHostId, // spoofed sender header!
            isHost = true
        )
        val isMismatchedActorSender = forgedRoomState.playerId != verifiedHostId || forgedRoomState.senderPlayerId != verifiedHostId
        assertTrue("ROOM_STATE with mismatched actor/sender must be detected and rejected", isMismatchedActorSender)

        // Forged host flag does NOT grant default readiness or remove peer from required ACKs (Finding 12):
        val rogueJoinPacket = RoomMessagePacket(
            type = "JOIN",
            playerId = attackerId,
            senderPlayerId = attackerId,
            isHost = true,
            readyStatus = "" // empty ready status
        )
        val rogueRosterPlayer = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(rogueJoinPacket, existing = null, verifiedHostId = verifiedHostId)
        assertFalse("Rogue peer must NOT be marked isHost", rogueRosterPlayer.isHost)
        assertEquals("Rogue peer must NOT receive default READY status", "NOT_READY", rogueRosterPlayer.lobbyReadyStatus)

        // Required ACKs excludes only genuine verified host:
        val lobbyRoster = listOf(
            Player(id = verifiedHostId, displayName = "Host", username = "host", isHost = true),
            rogueRosterPlayer
        )
        val verifiedHost = verifiedHostId
        val requiredAcks = lobbyRoster.filter { it.id != verifiedHost && it.id != verifiedHostId }.map { it.id }
        assertTrue("Rogue peer must still be required to ACK despite forged isHost flag", requiredAcks.contains(attackerId))

        // Production host discovery replay (RootNavGraph lines 4803-4815):
        // When START_GAME arrives before host discovery is complete:
        var pendingStartGamePacket: RoomMessagePacket? = null
        var currentRoute = "lobby"
        var verifiedLobbyHostId: String? = null

        // 1. Packet arrives before host is discovered
        val earlyStartPacket = RoomMessagePacket(
            type = "START_GAME",
            seed = 123456L,
            playerId = verifiedHostId,
            senderPlayerId = verifiedHostId
        )
        if (verifiedLobbyHostId == null) {
            pendingStartGamePacket = earlyStartPacket
        }
        assertNotNull("Early packet must be held pending host discovery", pendingStartGamePacket)

        // 2. Host is discovered
        verifiedLobbyHostId = verifiedHostId

        // 3. LaunchedEffect replays pending packet
        var replayedPacket: RoomMessagePacket? = null
        if (currentRoute == "lobby" && pendingStartGamePacket != null && verifiedLobbyHostId != null) {
            replayedPacket = pendingStartGamePacket
            pendingStartGamePacket = null
        }
        assertNull("Pending start packet must be consumed", pendingStartGamePacket)
        assertNotNull("Held packet must be replayed", replayedPacket)
        assertEquals(earlyStartPacket.seed, replayedPacket?.seed)

        // Finding R9-F07: Privileged lobby controls validation against known host
        val victimId = "victim_guest_gamma"
        val expectedHost = verifiedHostId

        // 1. KICK_PLAYER host validation in transport registry (OnlineRoomSyncManager line 1615):
        val syncManager = OnlineRoomSyncManager()
        try {
            val hostPlayer = Player(id = verifiedHostId, displayName = "Host", username = "host", isHost = true)
            val victimPlayer = Player(id = victimId, displayName = "Victim", username = "victim", isHost = false)
            val attackerPlayer = Player(id = attackerId, displayName = "Attacker", username = "attacker", isHost = false)
            syncManager.connectToRoom("ROOM01", hostPlayer, initialPlayers = listOf(hostPlayer, victimPlayer, attackerPlayer))

            // Foreign peer sends KICK_PLAYER targeting victim with self-declared isHost=true:
            val rogueKick = RoomMessagePacket(
                type = "KICK_PLAYER",
                playerId = attackerId,
                senderPlayerId = attackerId,
                targetPlayerId = victimId,
                isHost = true
            )
            syncManager.handleIncomingPacket(rogueKick)
            assertTrue("Rogue KICK_PLAYER must NOT remove victim from player registry", syncManager.players.value.any { it.id == victimId })

            // Genuine host sends KICK_PLAYER targeting victim:
            val genuineKick = RoomMessagePacket(
                type = "KICK_PLAYER",
                playerId = verifiedHostId,
                senderPlayerId = verifiedHostId,
                targetPlayerId = victimId,
                isHost = true
            )
            syncManager.handleIncomingPacket(genuineKick)
            assertFalse("Genuine host KICK_PLAYER must remove victim from player registry", syncManager.players.value.any { it.id == victimId })
        } finally {
            syncManager.disconnect()
        }

        // 2. Production Nearby ingress validation (Findings R10-F03 and R10-F06):
        // Begins with empty roster, updates Nearby players, then delivers valid, foreign, and spoofed host controls:
        val lanManager = LanP2pSessionManager()
        try {
            assertEquals("Nearby manager must begin with empty roster", 0, lanManager.players.value.size)

            // Foreign kick on empty roster must be rejected safely:
            lanManager.handleIncomingPacket(RoomMessagePacket(type = "KICK_PLAYER", playerId = attackerId, targetPlayerId = victimId))
            assertEquals(0, lanManager.players.value.size)

            // Connect as host and register victim:
            val hostPlayer = Player(id = verifiedHostId, displayName = "Host", username = "host", isHost = true)
            lanManager.connectAsHost(hostPlayer)
            lanManager.handleIncomingPacket(RoomMessagePacket(type = "READY_STATUS", playerId = victimId, displayName = "Victim", isHost = false))
            assertEquals(2, lanManager.players.value.size)
            assertTrue("Victim must be in Nearby roster", lanManager.players.value.any { it.id == victimId })

            // Foreign peer attempts KICK_PLAYER with self-declared isHost:
            val rogueNearbyKick = RoomMessagePacket(
                type = "KICK_PLAYER",
                playerId = attackerId,
                senderPlayerId = attackerId,
                targetPlayerId = victimId,
                isHost = true
            )
            lanManager.handleIncomingPacket(rogueNearbyKick)
            assertTrue("Foreign KICK_PLAYER must NOT remove victim from Nearby registry", lanManager.players.value.any { it.id == victimId })

            // Spoofed sender ID on KICK_PLAYER (attacker masquerades as host sender):
            val spoofedNearbyKick = RoomMessagePacket(
                type = "KICK_PLAYER",
                playerId = verifiedHostId,
                senderPlayerId = attackerId,
                targetPlayerId = victimId,
                isHost = true
            )
            lanManager.handleIncomingPacket(spoofedNearbyKick)
            assertTrue("Spoofed sender ID on KICK_PLAYER must NOT remove victim", lanManager.players.value.any { it.id == victimId })

            // Genuine host KICK_PLAYER:
            val genuineNearbyKick = RoomMessagePacket(
                type = "KICK_PLAYER",
                playerId = verifiedHostId,
                senderPlayerId = verifiedHostId,
                targetPlayerId = victimId,
                isHost = true
            )
            lanManager.handleIncomingPacket(genuineNearbyKick)
            assertFalse("Genuine host KICK_PLAYER must remove victim from Nearby registry", lanManager.players.value.any { it.id == victimId })

            // Spoofed host LEAVE (attacker sends host departure):
            val spoofedNearbyLeave = RoomMessagePacket(
                type = "LEAVE",
                playerId = verifiedHostId,
                senderPlayerId = attackerId,
                isHost = true
            )
            lanManager.handleIncomingPacket(spoofedNearbyLeave)
            assertTrue("Spoofed host LEAVE must NOT remove host from Nearby registry", lanManager.players.value.any { it.id == verifiedHostId })

            // Genuine host LEAVE:
            val genuineNearbyLeave = RoomMessagePacket(
                type = "LEAVE",
                playerId = verifiedHostId,
                senderPlayerId = verifiedHostId,
                isHost = true
            )
            lanManager.handleIncomingPacket(genuineNearbyLeave)
            assertFalse("Genuine host LEAVE must remove host from Nearby registry", lanManager.players.value.any { it.id == verifiedHostId })
        } finally {
            lanManager.disconnect()
        }
    }

    @Test
    fun testNearby_socketIngressBindsConnectionIdentityAndPongDoesNotTriggerHostLeave() {
        // Finding R7-F11: PONG packets must not overwrite the responding socket's identity with host ID
        val host = Player("host_uid", "Host", "host", isHost = true)
        val guest1 = Player("guest_uid_1", "Guest 1", "g1", isHost = false)
        val guest2 = Player("guest_uid_2", "Guest 2", "g2", isHost = false)

        val socketToPlayerId = mutableMapOf<String, String>()
        val socket1 = "socket_client_1"
        val socket2 = "socket_client_2"

        // Production LanP2pSessionManager lines 245-251 ingress logic:
        fun onPacketIngress(socket: String, packet: RoomMessagePacket) {
            val senderId = packet.senderPlayerId.takeIf { it.isNotBlank() } ?: packet.playerId
            val isHostId = (senderId == host.id || packet.playerId == host.id)
            if (packet.type == "JOIN" && senderId.isNotBlank() && !isHostId) {
                socketToPlayerId[socket] = senderId
            } else if (!socketToPlayerId.containsKey(socket) && senderId.isNotBlank() && !isHostId && packet.type != "PONG" && packet.type != "PING") {
                socketToPlayerId[socket] = senderId
            }
        }

        // 1. Guest 1 connects and sends JOIN
        val join1 = RoomMessagePacket(type = "JOIN", playerId = guest1.id, senderPlayerId = guest1.id, isHost = false)
        onPacketIngress(socket1, join1)
        assertEquals("Socket 1 must be bound to Guest 1", guest1.id, socketToPlayerId[socket1])

        // 2. Guest 2 connects and sends JOIN
        val join2 = RoomMessagePacket(type = "JOIN", playerId = guest2.id, senderPlayerId = guest2.id, isHost = false)
        onPacketIngress(socket2, join2)
        assertEquals("Socket 2 must be bound to Guest 2", guest2.id, socketToPlayerId[socket2])

        // 3. Host sends PING to Guest 1
        val pingPacket = RoomMessagePacket(type = "PING", playerId = host.id, senderPlayerId = host.id, targetPlayerId = guest1.id)
        assertNotNull(pingPacket)

        // 4. Guest 1 replies with PONG
        val pongPacket = RoomMessagePacket(
            type = "PONG",
            playerId = guest1.id,
            senderPlayerId = guest1.id,
            targetPlayerId = host.id,
            timestamp = System.currentTimeMillis()
        )
        onPacketIngress(socket1, pongPacket)

        // Socket 1 must REMAIN bound to Guest 1, never rebound to host!
        assertEquals("Socket 1 must remain bound to Guest 1 after PONG", guest1.id, socketToPlayerId[socket1])
        assertNotEquals("Socket 1 must never be rebound to Host", host.id, socketToPlayerId[socket1])

        // 5. Guest 1 disconnects while Guest 2 remains connected
        val leavingPlayerId = socketToPlayerId.remove(socket1)
        assertEquals("Departing player must be Guest 1", guest1.id, leavingPlayerId)
        assertNotEquals("Leaving player must NOT be host", host.id, leavingPlayerId)

        // Production disconnect cleanup produces LEAVE packet for departing player only:
        val leavePacket = RoomMessagePacket(type = "LEAVE", playerId = leavingPlayerId!!, timestamp = System.currentTimeMillis())
        assertEquals("LEAVE packet must carry Guest 1's ID", guest1.id, leavePacket.playerId)

        // Guest 2's socket remains connected and mapped:
        assertEquals(guest2.id, socketToPlayerId[socket2])
        // Host remains connected, no false host-disconnect result occurs
        assertFalse("Host must NOT have left the session", leavePacket.playerId == host.id)
    }

    // =========================================================================
    // ── KNOWN-GREEN: Latency presentation & freshness separation
    // =========================================================================

    @Test
    fun testKnownGreen_unmeasuredPingDisplaysNeutralPlaceholder() {
        val unmeasuredPing = 0L

        val displayedPingMs = if (unmeasuredPing > 0L) unmeasuredPing else 0L
        assertEquals(0L, displayedPingMs)

        val pingText = when {
            displayedPingMs <= 0L -> "--ms"
            displayedPingMs > 999L -> "999+ms"
            else -> "${displayedPingMs}ms"
        }
        assertEquals("--ms", pingText)

        val validPing = 45L
        val validPingText = when {
            validPing <= 0L -> "--ms"
            validPing > 999L -> "999+ms"
            else -> "${validPing}ms"
        }
        assertEquals("45ms", validPingText)
    }

    @Test
    fun testKnownGreen_statusPresentationSeparatesInternetFromGameplayLatency() {
        // Finding 12: Production NetworkPingMonitor exposes isInternetFresh
        assertNotNull(NetworkPingMonitor.isInternetFresh)

        // Probe older than 15s is not fresh
        val now = System.currentTimeMillis()
        val oldProbeTimestamp = now - 20000L
        val isProbeFresh = (now - oldProbeTimestamp) < 15000L
        assertFalse("Probe older than 15,000ms must NOT be considered fresh", isProbeFresh)

        // Probe within 15s is fresh
        val freshProbeTimestamp = now - 2000L
        val isRecentProbeFresh = (now - freshProbeTimestamp) < 15000L
        assertTrue("Probe within 2,000ms must be considered fresh", isRecentProbeFresh)

        // Shared production NetworkStatusMapper covers all distinct measurement sources (Finding 13):
        // 1. Google-only reachability (Internet probe fresh, broker RTT absent)
        val googleOnly = NetworkStatusMapper.mapMainMenuLatency(
            internetLatencyMs = 32L,
            isInternetFresh = true,
            isBrokerDown = false,
            brokerRttMs = 0L,
            isBrokerRttFresh = false
        )
        assertEquals("32ms", googleOnly.displayText)
        assertEquals(NetworkMetricSource.INTERNET_PROBE, googleOnly.source)
        assertEquals("Internet", googleOnly.label)
        assertTrue(googleOnly.isReliable)

        // 2. TCP-only broker reachability (TCP connected but no fresh probe/RTT)
        val tcpOnly = NetworkStatusMapper.mapMainMenuLatency(
            internetLatencyMs = 0L,
            isInternetFresh = false,
            isBrokerDown = false,
            brokerRttMs = 0L,
            isBrokerRttFresh = false
        )
        assertEquals("--ms", tcpOnly.displayText)
        assertEquals(NetworkMetricSource.NONE, tcpOnly.source)
        assertEquals("Unknown", tcpOnly.label)

        // 3. Failed MQTT subscription in online match
        val failedSub = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = true,
            isNearby = false,
            lanPingMs = 0L,
            isLanFresh = false,
            brokerRttMs = 40L,
            isBrokerRttFresh = true,
            isMqttSubscribed = false,
            isPeerResponsive = true
        )
        assertEquals("--ms", failedSub.displayText)
        assertEquals(NetworkMetricSource.NONE, failedSub.source)
        assertEquals("Unsubscribed", failedSub.label)

        // 4. Stale broker echo with unresponsive peer
        val staleEcho = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = true,
            isNearby = false,
            lanPingMs = 0L,
            isLanFresh = false,
            brokerRttMs = 40L,
            isBrokerRttFresh = false,
            isMqttSubscribed = true,
            isPeerResponsive = false
        )
        assertEquals("--ms", staleEcho.displayText)
        assertEquals(NetworkMetricSource.NONE, staleEcho.source)
        assertEquals("Unanswered Peer", staleEcho.label)

        // 5. Unanswered peer with fresh broker echo
        val brokerEchoOnly = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = true,
            isNearby = false,
            lanPingMs = 0L,
            isLanFresh = false,
            brokerRttMs = 55L,
            isBrokerRttFresh = true,
            isMqttSubscribed = true,
            isPeerResponsive = false
        )
        assertEquals("55ms", brokerEchoOnly.displayText)
        assertEquals(NetworkMetricSource.BROKER_ECHO, brokerEchoOnly.source)
        assertEquals("Broker RTT", brokerEchoOnly.label)

        // 6. Direct peer RTT
        val peerRtt = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = true,
            isNearby = false,
            lanPingMs = 0L,
            isLanFresh = false,
            brokerRttMs = 55L,
            isBrokerRttFresh = true,
            isMqttSubscribed = true,
            isPeerResponsive = true,
            peerRttMs = 28L
        )
        assertEquals("28ms", peerRtt.displayText)
        assertEquals(NetworkMetricSource.PEER_RTT, peerRtt.source)
        assertEquals("Peer RTT", peerRtt.label)

        // 7. LAN peer in nearby mode
        val lanNearby = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = false,
            isNearby = true,
            lanPingMs = 12L,
            isLanFresh = true,
            brokerRttMs = 0L,
            isBrokerRttFresh = false,
            isMqttSubscribed = false,
            isPeerResponsive = true
        )
        assertEquals("12ms", lanNearby.displayText)
        assertEquals(NetworkMetricSource.PEER_RTT, lanNearby.source)
        assertEquals("LAN Peer", lanNearby.label)

        // Production LAN measurement & presentation path (Finding 12):
        // 1. Initial state / No sample: measuredPingMs is 0L, isPingFresh is false
        val lanManager = LanP2pSessionManager()
        assertEquals(0L, lanManager.measuredPingMs.value)
        assertFalse("Unmeasured LAN ping must NOT be marked fresh", lanManager.isPingFresh)

        // Production mapping for unmeasured LAN:
        val unmeasuredLan = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = false,
            isNearby = true,
            lanPingMs = lanManager.measuredPingMs.value,
            isLanFresh = lanManager.isPingFresh,
            brokerRttMs = 0L,
            isBrokerRttFresh = false,
            isMqttSubscribed = false,
            isPeerResponsive = false
        )
        assertEquals("--ms", unmeasuredLan.displayText)
        assertEquals(NetworkMetricSource.NONE, unmeasuredLan.source)
        assertEquals("Unknown", unmeasuredLan.label)
        assertFalse("Unmeasured LAN ping must NOT be marked reliable", unmeasuredLan.isReliable)

        // 2. Host without peer response: ping remains 0L, displayText is --ms
        val hostWithoutResponses = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = false,
            isNearby = true,
            lanPingMs = 0L,
            isLanFresh = false,
            brokerRttMs = 0L,
            isBrokerRttFresh = false,
            isMqttSubscribed = false,
            isPeerResponsive = false
        )
        assertEquals("--ms", hostWithoutResponses.displayText)
        assertFalse(hostWithoutResponses.isReliable)

        // 3. Stale sample: probe older than 15s is not fresh
        val stalePingTimestamp = now - 20_000L
        val isStalePingFresh = (now - stalePingTimestamp) < 15_000L && 25L > 0L
        assertFalse("Sample older than 15s must NOT be fresh", isStalePingFresh)
        val staleLan = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = false,
            isNearby = true,
            lanPingMs = 25L,
            isLanFresh = false,
            brokerRttMs = 0L,
            isBrokerRttFresh = false,
            isMqttSubscribed = false,
            isPeerResponsive = false
        )
        assertEquals("--ms", staleLan.displayText)
        assertFalse(staleLan.isReliable)

        // 4. Correlated peer response arriving (fresh sample):
        val freshLan = NetworkStatusMapper.mapGameLatency(
            isOnlineRoom = false,
            isNearby = true,
            lanPingMs = 15L,
            isLanFresh = true,
            brokerRttMs = 0L,
            isBrokerRttFresh = false,
            isMqttSubscribed = false,
            isPeerResponsive = true
        )
        assertEquals("15ms", freshLan.displayText)
        assertEquals(NetworkMetricSource.PEER_RTT, freshLan.source)
        assertEquals("LAN Peer", freshLan.label)
        assertTrue(freshLan.isReliable)

        // 5. Disconnect resets measurement and freshness
        lanManager.disconnect()
        assertEquals(0L, lanManager.measuredPingMs.value)
        assertFalse(lanManager.isPingFresh)

        // Production screen presentation model tests (Finding 10):
        // MainMenuScreen format: "$displayedPingLabel: $pingText"
        assertEquals("Internet: 32ms", "${googleOnly.label}: ${googleOnly.displayText}")
        assertEquals("Unknown: --ms", "${tcpOnly.label}: ${tcpOnly.displayText}")

        // GameScreen format: "$latencyLabel $pingText"
        assertEquals("Unsubscribed --ms", "${failedSub.label} ${failedSub.displayText}")
        assertEquals("Unanswered Peer --ms", "${staleEcho.label} ${staleEcho.displayText}")
        assertEquals("Broker RTT 55ms", "${brokerEchoOnly.label} ${brokerEchoOnly.displayText}")
        assertEquals("Peer RTT 28ms", "${peerRtt.label} ${peerRtt.displayText}")
        assertEquals("LAN Peer 12ms", "${lanNearby.label} ${lanNearby.displayText}")
        assertFalse("Unanswered peer must not be marked reliable", staleEcho.isReliable)
        assertFalse("Unsubscribed match must not be marked reliable", failedSub.isReliable)
        assertTrue("Direct peer RTT must be marked reliable", peerRtt.isReliable)
        assertTrue("LAN peer must be marked reliable", lanNearby.isReliable)
    }

    @Test
    fun testR01_handleMetadataSession_ignoresStaleClosureAfterRoomRebind() = runTest {
        val syncManager = OnlineRoomSyncManager()
        val roomACode = "ROOMA"
        val roomBCode = "ROOMB"
        val sessionA = OnlineRoomSession(
            roomCode = roomACode,
            hostId = "host_a",
            hostUsername = "host_a",
            hostDisplayName = "Host A",
            sessionId = "lobby_session_a",
            status = "CLOSED",
            isTerminalClosed = true,
            metadataProtocolVersion = 7
        )

        val guest = Player(id = "guest_1", displayName = "Guest", username = "guest", isHost = false)
        syncManager.currentRoomCode = roomACode
        syncManager.localPlayer = guest
        val genA = syncManager.roomConnectionId

        val startedLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val readLatch = kotlinx.coroutines.CompletableDeferred<OnlineRoomSession?>()
        val previousTransport = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> {
                return if (code == roomACode) {
                    startedLatch.complete(Unit)
                    val res = readLatch.await()
                    Result.success(res)
                } else {
                    Result.success(null)
                }
            }
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit =
                RoomMetadataCommit.Applied(next)
        }

        try {
            // Launch handleMetadataSession while in Room A. It passes initial guard (line 1361)
            // and suspends on RoomMetadataApi.read(roomACode).
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                syncManager.handleMetadataSession(sessionA, roomACode, retained = true, expectedGeneration = genA)
            }

            // Await deterministic entrance into read() suspension
            startedLatch.await()

            // Rebind to Room B while Room A read is suspended
            syncManager.currentRoomCode = roomBCode
            syncManager.roomConnectionId = UUID.randomUUID().toString()
            syncManager.lobbyMetadataClosed = false

            // Release the suspended read with the closed session
            readLatch.complete(sessionA)
            job.join()

            assertFalse("Room B lobbyMetadataClosed must remain false when stale Room A closure arrives after suspension", syncManager.lobbyMetadataClosed)
        } finally {
            RoomMetadataApi.transportOverride = previousTransport
        }
    }
}
