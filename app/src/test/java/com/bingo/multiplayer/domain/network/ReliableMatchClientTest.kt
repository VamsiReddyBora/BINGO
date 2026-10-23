package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReliableMatchClientTest {
    @Test fun pendingHostCandidateWithNoSharedCheckpointStaysBlockedAndCannotInventAResult() = runTest {
        val p=Pairing(this);runCurrent();val accepted=p.aStore.accepted!!
        var candidate:ReliableMatchSnapshot?=null
        val staging=ReliableMatchCoordinator(accepted,"A","owner-A",p.clock){candidate=it;false}
        staging.resumeObservation()
        staging.dispatch(MatchIntent("pending-surrender",accepted.matchId,"B",accepted.revision,accepted.turn,"SURRENDER"))
        p.host.close();p.a.connected=false;p.broker.retained=null
        val endpoint=p.broker.Endpoint();val store=Store()
        val returning=ReliableMatchClient(candidate!!,"A","owner-A",p.clock,endpoint,store,backgroundScope,previouslyAccepted=false)
        endpoint.receive=returning::receive;runCurrent()
        advanceTimeBy(20_000);runCurrent()
        assertTrue(returning.view.value.waiting)
        assertNull(store.accepted)
        assertNull(p.broker.retained)
    }
    @Test fun durableFinalResultOverridesAnOldHostHoldEvenOnOfflineColdLaunch() = runTest {
        val p=Pairing(this);runCurrent();val old=p.guest.view.value.snapshot!!
        p.guest.close();p.b.connected=false
        p.host.peerEpisode("B",MatchEpisode(3,"third",true,true));runCurrent();p.host.claim("third");runCurrent()
        val accepted=p.broker.retained!!.reliableSnapshot!!
        val endpoint=p.broker.Endpoint();endpoint.connected=false;endpoint.healthy=false
        val offline=ReliableMatchClient(accepted,"B","owner-B",p.clock,endpoint,Store(),backgroundScope,
            previouslyAccepted=true,initialHostWait=old)
        endpoint.receive=offline::receive;runCurrent()
        assertFalse(offline.view.value.hostDisconnected)
        assertFalse(offline.view.value.waiting)
        assertEquals(accepted.final,offline.view.value.snapshot!!.final)
    }
    @Test fun hostCrashWithUnpublishedCandidateRecoversAcceptedBrokerStateInsteadOfInventingPickOrResult() = runTest {
        for (kind in listOf("PICK","SURRENDER")) {
            val p=Pairing(this);runCurrent();val accepted=p.aStore.accepted!!
            var candidate:ReliableMatchSnapshot?=null
            val staging=ReliableMatchCoordinator(accepted,"A","owner-A",p.clock){candidate=it;false}
            staging.resumeObservation()
            val actor=if(kind=="SURRENDER") "B" else "A"
            assertFalse(staging.dispatch(MatchIntent("pending-$kind",accepted.matchId,actor,accepted.revision,accepted.turn,kind,number=5)))
            p.host.close();p.a.connected=false;p.a.healthy=false
            val endpoint=p.broker.Endpoint();val store=Store()
            val returning=ReliableMatchClient(candidate!!,"A","owner-A",p.clock,endpoint,store,backgroundScope,previouslyAccepted=false)
            endpoint.receive=returning::receive;runCurrent()
            assertFalse(returning.view.value.waiting)
            assertEquals(accepted.picks,returning.view.value.snapshot!!.picks)
            assertEquals(accepted.boards,returning.view.value.snapshot!!.boards)
            assertNull(returning.view.value.snapshot!!.final)
            returning.close();endpoint.connected=false;p.guest.close();p.b.connected=false
        }
    }

    @Test fun staleBrokerPauseSampleCannotUndoAnAcceptedManualPauseWhenHostRestarts() = runTest {
        val p=Pairing(this);runCurrent();val stale=p.broker.retained!!
        advanceTimeBy(5_000);runCurrent();p.host.request("PAUSE");runCurrent()
        val saved=p.aStore.accepted!!
        p.host.close();p.a.connected=false;p.a.healthy=false;p.guest.close();p.b.connected=false
        p.broker.retained=stale
        val endpoint=p.broker.Endpoint();val store=Store()
        val returning=ReliableMatchClient(saved,"A","owner-A",p.clock,endpoint,store,backgroundScope,previouslyAccepted=true)
        endpoint.receive=returning::receive;runCurrent()
        advanceTimeBy(15_100);runCurrent()
        assertFalse(returning.view.value.waiting)
        assertTrue(returning.view.value.snapshot!!.paused)
        assertEquals(saved.remainingMillis,returning.view.value.remainingMillis)
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun initial() = ReliableMatchSnapshot(matchId = "987", roomCode = "TEST", seed = 987, hostId = "A", ownerId = "owner-A",
        participants = listOf(Player("A", "Kevin", isHost = true), Player("B", "Stuart")),
        boards = mapOf("A" to BingoEngine().generateBoard(3, 9), "B" to BingoEngine().generateBoard(3, 10)), order = listOf("A", "B"), currentPlayerId = "A")
    private class Store : ReliableMatchCheckpoint {
        var accepted: ReliableMatchSnapshot? = null; var pending: ReliableMatchSnapshot? = null
        var fail = false; var failAcceptance = false
        var hostWait: ReliableMatchSnapshot? = null
        override fun saveHostWait(snapshot: ReliableMatchSnapshot?): Boolean { if (fail) return false; hostWait = snapshot; return true }
        override fun save(snapshot: ReliableMatchSnapshot, pending: Boolean): Boolean {
            if (fail || (!pending && failAcceptance)) return false
            if (pending) this.pending = snapshot else { accepted = snapshot; this.pending = null }
            return true
        }
    }
    private inner class Broker {
        var retained: RoomMessagePacket? = null
        var failPublish = false
        val endpoints = mutableListOf<Endpoint>()
        inner class Endpoint : ReliableMatchTransport {
            override var connected = true; override var healthy = true
            var subscribed = false; var receive: ((RoomMessagePacket) -> Unit)? = null
            var holdSyncAcks = false
            var dropReady = false
            val heldAcks = mutableListOf<RoomMessagePacket>()
            val sent = mutableListOf<RoomMessagePacket>()
            init { endpoints.add(this) }
            override suspend fun subscribe(seed: Long): Boolean {
                subscribed = true
                retained?.let { receive?.invoke(json.decodeFromString(json.encodeToString(it))) }
                return connected
            }
            override suspend fun publish(packet: RoomMessagePacket, retained: Boolean, stateTopic: Boolean): Boolean {
                if (!connected || failPublish) return false
                sent.add(packet)
                if (dropReady && packet.type == "MATCH_V2_READY") return true
                if (holdSyncAcks && packet.type == "MATCH_V2_SYNC_ACK") { heldAcks.add(packet); return true }
                val wire = json.encodeToString(packet)
                if (retained) this@Broker.retained = json.decodeFromString(wire)
                endpoints.filter { it.connected && (!stateTopic || it.subscribed) }.forEach { it.receive?.invoke(json.decodeFromString(wire)) }
                return true
            }
        }
    }
    private inner class Pairing(val test: TestScope) {
        val broker = Broker(); val a = broker.Endpoint(); val b = broker.Endpoint()
        val aStore = Store(); val bStore = Store()
        val clock = object : Clock { override fun elapsedRealtime() = test.testScheduler.currentTime }
        var hostSyncs = 0
        val host = ReliableMatchClient(initial(), "A", "owner-A", clock, a, aStore, test.backgroundScope,
            onSynchronized = { hostSyncs++ })
        val guest = ReliableMatchClient(initial(), "B", "owner-B", clock, b, bStore, test.backgroundScope)
        init { a.receive = host::receive; b.receive = guest::receive }
    }
    @Test fun actualProtocolNegotiatesAndReconcilesAPickWithSeparateStores() = runTest {
        val p = Pairing(this); runCurrent()
        assertFalse(p.host.view.value.waiting); assertFalse(p.guest.view.value.waiting)
        p.host.request("PICK", number = 5); runCurrent()
        assertEquals(listOf(5), p.aStore.accepted!!.picks.map { it.number })
        assertEquals(p.aStore.accepted!!.picks, p.bStore.accepted!!.picks)
        assertNotSame(p.aStore, p.bStore)
        p.guest.request("PICK", number = 7); runCurrent()
        assertEquals(listOf(5,7), p.host.view.value.snapshot!!.picks.map { it.number })
        assertEquals(p.host.view.value.snapshot!!.boards, p.guest.view.value.snapshot!!.boards)
    }
    @Test fun exactPauseSurvivesWireAndResumeWithoutThirtySecondReset() = runTest {
        val p = Pairing(this); runCurrent(); advanceTimeBy(15_750); runCurrent()
        p.host.request("PAUSE"); runCurrent()
        assertEquals(14_250L, p.host.view.value.remainingMillis)
        assertEquals(14_250L, p.bStore.accepted!!.remainingMillis)
        val old = p.broker.retained!!
        advanceTimeBy(80_000); runCurrent()
        p.guest.request("RESUME"); runCurrent()
        assertTrue(p.host.view.value.snapshot!!.paused)
        p.host.request("RESUME"); runCurrent()
        assertFalse(p.host.view.value.snapshot!!.paused)
        assertEquals(14_250L, p.host.view.value.remainingMillis)
        p.guest.receive(old); runCurrent()
        assertFalse(p.guest.view.value.snapshot!!.paused)
        advanceTimeBy(14_300); runCurrent()
        assertEquals(2, p.host.view.value.snapshot!!.turn)
        assertTrue(p.host.view.value.remainingMillis in 29_800L..30_000L)
    }
    @Test fun peerReturningWithoutTransportLossCanCompleteAFreshSyncAndPreserveManualPause() = runTest {
        val p = Pairing(this); runCurrent(); advanceTimeBy(15_750); runCurrent()
        p.host.request("PAUSE"); runCurrent()
        p.host.peerEpisode("B", MatchEpisode(1, "lost-probes", true, true)); runCurrent()
        val syncs = p.hostSyncs
        p.host.peerEpisode("B", MatchEpisode(1, "lost-probes", true, false))
        p.host.requestSync(); runCurrent()
        assertTrue(p.host.view.value.waiting)
        assertTrue(p.host.view.value.snapshot!!.paused)
        // Recovery sends its new barrier/HELLO on the next 100 ms protocol tick.
        advanceTimeBy(300); runCurrent()
        assertTrue(p.hostSyncs > syncs)
        assertFalse(p.host.view.value.waiting)
        assertTrue(p.host.view.value.snapshot!!.paused)
        assertTrue(p.guest.view.value.snapshot!!.paused)
        assertEquals(14_250L, p.host.view.value.remainingMillis)
        p.host.request("RESUME"); runCurrent()
        assertFalse(p.host.view.value.snapshot!!.paused)
        assertEquals(14_250L, p.host.view.value.remainingMillis)
    }

    @Test fun guestCannotPauseThroughTheRealRequestPath() = runTest {
        val p = Pairing(this); runCurrent()
        p.guest.request("PAUSE"); runCurrent()
        assertFalse(p.host.view.value.snapshot!!.paused)
        assertFalse(p.b.sent.any { it.matchIntent?.kind == "PAUSE" })
    }
    @Test fun lateUnpausedSnapshotCannotClearADeliberatePause() = runTest {
        val p = Pairing(this); runCurrent(); val old = p.broker.retained!!
        p.host.request("PAUSE"); runCurrent()
        p.guest.receive(old); runCurrent()
        assertTrue(p.guest.view.value.snapshot!!.paused)
    }
    @Test fun failedPublicationKeepsPendingStateAndBlocksMutationsUntilBrokerRecovery() = runTest {
        val p = Pairing(this); runCurrent(); val accepted = p.aStore.accepted!!
        p.broker.failPublish = true; p.host.request("PICK", number = 5); runCurrent()
        assertTrue(p.host.view.value.waiting)
        assertEquals(accepted.picks, p.aStore.accepted!!.picks)
        assertNotNull(p.aStore.pending)
        p.host.request("PICK", number = 6); runCurrent()
        assertEquals(accepted.picks, p.aStore.accepted!!.picks)
        p.broker.failPublish = false; advanceTimeBy(300); runCurrent()
        assertFalse(p.host.view.value.waiting)
        assertEquals(accepted.picks, p.host.view.value.snapshot!!.picks)
    }
    @Test fun failedLocalAcceptanceAfterPublishRecoversTheBrokersCommittedSnapshot() = runTest {
        val p = Pairing(this); runCurrent()
        p.aStore.failAcceptance = true; p.host.request("PICK", number = 5); runCurrent()
        assertTrue(p.host.view.value.waiting)
        assertEquals(listOf(5), p.broker.retained!!.reliableSnapshot!!.picks.map { it.number })
        p.aStore.failAcceptance = false; advanceTimeBy(300); runCurrent()
        assertEquals(listOf(5), p.aStore.accepted!!.picks.map { it.number })
        assertFalse(p.host.view.value.waiting)
    }
    @Test fun absentPeerGetsFinalResultFromRetainedBrokerAfterWinnerClientCloses() = runTest {
        val p = Pairing(this); runCurrent()
        val savedGuest = p.bStore.accepted!!
        p.b.connected = false; p.b.healthy = false; p.guest.close()
        p.host.peerEpisode("B", MatchEpisode(3, "third", true, true)); runCurrent()
        p.host.claim("third"); runCurrent()
        assertEquals("A", p.host.view.value.snapshot!!.final!!.winnerId)
        val final = p.broker.retained!!.reliableSnapshot!!.final
        p.host.close(); p.a.connected = false
        val independentStore = Store(); val endpoint = p.broker.Endpoint()
        val returning = ReliableMatchClient(savedGuest, "B", "owner-B", p.clock, endpoint, independentStore, backgroundScope, previouslyAccepted = true)
        endpoint.receive = returning::receive; runCurrent()
        assertFalse(returning.view.value.waiting)
        assertEquals(final, independentStore.accepted!!.final)
    }
    @Test fun guestCannotPromoteItselfWhenHostStopsResponding() = runTest {
        val p = Pairing(this); runCurrent(); p.a.connected = false; p.a.healthy = false
        p.guest.waitForHost(); runCurrent(); advanceTimeBy(90_000); runCurrent()
        assertTrue(p.guest.view.value.waiting)
        assertNull(p.guest.view.value.snapshot!!.final)
        assertEquals(1, p.guest.view.value.snapshot!!.turn)
    }
    @Test fun wrongProtocolOrIncompleteSnapshotCannotUnlockRecovery() = runTest {
        val p = Pairing(this); runCurrent()
        p.guest.requestSync(); runCurrent()
        p.a.connected = false; p.a.healthy = false; p.guest.waitForHost(); runCurrent()
        val bad = p.broker.retained!!.copy(protocolVersion = 0)
        p.guest.receive(bad)
        p.guest.receive(bad.copy(protocolVersion = 2, reliableSnapshot = initial().copy(boards = emptyMap(), sequence = 99)))
        runCurrent(); assertTrue(p.guest.view.value.waiting)
    }
    @Test fun forgedClaimAndStaleSyncAckCannotCreateAFinalResult() = runTest {
        val p = Pairing(this); runCurrent()
        p.host.receive(RoomMessagePacket(type = "MATCH_V2_SYNC_ACK", playerId = "B", seed = 987, matchId = "987", protocolVersion = 2, requestId = "old", reliableSnapshot = initial()))
        p.host.receive(RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "B", seed = 987, matchId = "987", claimEpisodeCount = 3, claimEpisodeId = "invented"))
        p.guest.request("CLAIM", episodeId = "invented"); runCurrent()
        assertNull(p.host.view.value.snapshot!!.final)
    }
    @Test fun chatUsesAcceptedStableIdsAndSurvivesASeparateReturningClient() = runTest {
        val p=Pairing(this); runCurrent()
        p.host.request("CHAT",text="Before disconnect"); runCurrent()
        p.guest.close(); p.b.connected=false
        val saved=p.bStore.accepted!!
        p.host.request("CHAT",text="While you were away"); runCurrent()
        val endpoint=p.broker.Endpoint(); val store=Store()
        val returning=ReliableMatchClient(saved,"B","owner-B",p.clock,endpoint,store,backgroundScope,previouslyAccepted=true)
        endpoint.receive=returning::receive; runCurrent()
        assertEquals(listOf("Before disconnect","While you were away"),store.accepted!!.chat.map{it.text})
        assertEquals(2,store.accepted!!.chat.map{it.id}.distinct().size)
    }
    @Test fun duplicateSequenceWithDifferentPauseDataCannotOverwriteTheAcceptedCheckpoint() = runTest {
        val p=Pairing(this); runCurrent()
        p.host.request("PAUSE"); runCurrent()
        val accepted=p.bStore.accepted!!
        val forged=p.broker.retained!!.copy(reliableSnapshot=accepted.copy(paused=false,pauseOwnerId=""))
        p.guest.receive(forged); runCurrent()
        assertTrue(p.guest.view.value.snapshot!!.paused)
        assertEquals(accepted,p.bStore.accepted)
    }

    @Test fun twentyReconnectCyclesForEachRoleKeepOneHistoryAndNeverInventAResult() = runTest {
        val p=Pairing(this); runCurrent()
        p.host.request("PICK",number=5); runCurrent()
        for(endpoint in listOf(p.b,p.a)) {
            repeat(20) {
                endpoint.connected=false; endpoint.healthy=false
                advanceTimeBy(300); runCurrent()
                endpoint.connected=true; endpoint.healthy=true
                advanceTimeBy(300); runCurrent()
                assertFalse(p.host.view.value.waiting)
                assertFalse(p.guest.view.value.waiting)
                assertEquals(p.host.view.value.snapshot!!.picks,p.guest.view.value.snapshot!!.picks)
                assertEquals(p.host.view.value.snapshot!!.boards,p.guest.view.value.snapshot!!.boards)
                assertNull(p.host.view.value.snapshot!!.final)
            }
        }
    }

    @Test fun inconsistentSavedBoardRemainsWaitingInsteadOfCrashingOrGeneratingANewBoard() = runTest {
        val broker=Broker(); val endpoint=broker.Endpoint(); val store=Store()
        val clock=object:Clock{override fun elapsedRealtime()=testScheduler.currentTime}
        val inconsistent=initial().copy(turn=2,picks=listOf(MatchPick(5,"A",1))) // saved layouts were never marked
        assertFalse(inconsistent.valid())
        val client=ReliableMatchClient(inconsistent,"B","owner-B",clock,endpoint,store,backgroundScope)
        endpoint.receive=client::receive; runCurrent()
        assertTrue(client.view.value.waiting)
        assertTrue(client.view.value.snapshot!!.boards.isEmpty())
        assertNull(client.view.value.snapshot!!.final)
    }

    @Test fun delayedSyncAckStillAuthorizesThePeerAfterTheOwnerAdvancesAndDuplicateRequestArrives() = runTest {
        val p=Pairing(this); runCurrent(); p.b.holdSyncAcks=true
        p.guest.requestSync(); runCurrent()
        assertTrue(p.guest.view.value.waiting)
        advanceTimeBy(100); runCurrent()
        p.host.request("PICK",number=5); runCurrent()
        val duplicate=p.b.sent.last{it.type=="MATCH_V2_HELLO"}
        p.host.receive(duplicate);runCurrent()
        p.b.holdSyncAcks=false
        p.b.heldAcks.toList().forEach{p.host.receive(it)};runCurrent()
        p.guest.request("PICK",number=7);runCurrent()
        assertEquals(listOf(5,7),p.host.view.value.snapshot!!.picks.map{it.number})
        assertEquals(100L,testScheduler.currentTime) // no retry-period stall
    }

    @Test fun hostAbsenceFreezes14250MillisAndEveryGuestActionUntilConfirmedRecovery() = runTest {
        val p=Pairing(this); runCurrent()
        p.a.connected=false; p.a.healthy=false
        advanceTimeBy(15_750);runCurrent();p.guest.waitForHost();runCurrent()
        val frozen=p.guest.view.value
        assertTrue(frozen.hostDisconnected);assertTrue(frozen.waiting)
        assertEquals(14_250L,frozen.remainingMillis)
        assertEquals(14_250L,p.bStore.hostWait!!.remainingMillis)
        for(kind in listOf("PICK","PAUSE","RESUME","CHAT","SURRENDER","CLAIM")) p.guest.request(kind,number=5,text="blocked",episodeId="third")
        advanceTimeBy(120_000);runCurrent()
        assertEquals(frozen.snapshot,p.guest.view.value.snapshot)
        assertEquals(14_250L,p.guest.view.value.remainingMillis)
        assertNull(p.guest.view.value.snapshot!!.final)
        p.a.connected=true;p.a.healthy=true
        advanceTimeBy(50);runCurrent()
        assertFalse(p.guest.view.value.waiting);assertFalse(p.guest.view.value.hostDisconnected)
        assertNull(p.bStore.hostWait)
        assertEquals(14_250L,p.guest.view.value.remainingMillis)
        assertEquals(p.host.view.value.remainingMillis,p.guest.view.value.remainingMillis)
        assertEquals(15_750L,p.host.view.value.snapshot!!.elapsedPlayMillis)
        assertEquals(p.host.view.value.snapshot!!.elapsedPlayMillis,p.guest.view.value.snapshot!!.elapsedPlayMillis)
        assertEquals(frozen.snapshot!!.boards,p.guest.view.value.snapshot!!.boards)
        assertEquals(frozen.snapshot!!.turn,p.guest.view.value.snapshot!!.turn)
    }

    @Test fun zeroBeforeHostDetectionStaysZeroAndExpiresOnceAfterRecovery() = runTest {
        val p=Pairing(this);runCurrent();p.a.connected=false;p.a.healthy=false
        advanceTimeBy(30_000);runCurrent();p.guest.waitForHost();runCurrent()
        assertEquals(0L,p.guest.view.value.remainingMillis)
        advanceTimeBy(60_000);runCurrent()
        assertEquals(1,p.guest.view.value.snapshot!!.turn)
        p.a.connected=true;p.a.healthy=true
        advanceTimeBy(100);runCurrent()
        assertFalse(p.guest.view.value.hostDisconnected)
        advanceTimeBy(100);runCurrent()
        assertEquals(2,p.guest.view.value.snapshot!!.turn)
        assertEquals(30_000L,p.guest.view.value.remainingMillis)
        assertEquals("B",p.guest.view.value.snapshot!!.currentPlayerId)
    }

    @Test fun manualPauseAndAcceptedChatSurviveHostHoldWithoutImplicitResume() = runTest {
        val p=Pairing(this);runCurrent()
        p.host.request("CHAT",text="Keep this message");runCurrent()
        advanceTimeBy(15_750);runCurrent();p.host.request("PAUSE");runCurrent()
        val before=p.guest.view.value.snapshot!!
        p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_050);runCurrent();p.guest.waitForHost();runCurrent()
        advanceTimeBy(60_000);runCurrent()
        p.a.connected=true;p.a.healthy=true
        advanceTimeBy(100);runCurrent()
        val after=p.guest.view.value
        assertFalse(after.hostDisconnected);assertFalse(after.waiting)
        assertTrue(after.snapshot!!.paused)
        assertEquals(before.pauseOwnerId,after.snapshot!!.pauseOwnerId)
        assertEquals(before.chat,after.snapshot!!.chat)
        assertEquals(before.boards,after.snapshot!!.boards)
        assertEquals(14_250L,after.remainingMillis)
        advanceTimeBy(60_000);runCurrent();assertEquals(14_250L,p.guest.view.value.remainingMillis)
    }

    @Test fun guestColdLaunchRestoresBlockingHostHoldAndFrozenTime() = runTest {
        val p=Pairing(this);runCurrent();p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_000);runCurrent();p.guest.waitForHost();runCurrent()
        val frozen=p.bStore.hostWait!!;val accepted=p.bStore.accepted!!
        p.guest.close();p.b.connected=false
        val endpoint=p.broker.Endpoint();val store=Store()
        val returning=ReliableMatchClient(accepted,"B","owner-B",p.clock,endpoint,store,backgroundScope,
            previouslyAccepted=true,initialHostWait=frozen)
        endpoint.receive=returning::receive;runCurrent()
        assertTrue(returning.view.value.hostDisconnected)
        advanceTimeBy(600_000);runCurrent()
        assertEquals(15_000L,returning.view.value.remainingMillis)
        p.a.connected=true;p.a.healthy=true
        advanceTimeBy(100);runCurrent()
        assertFalse(returning.view.value.hostDisconnected)
        assertEquals(returning.view.value.snapshot!!.boards,p.host.view.value.snapshot!!.boards)
        assertEquals(returning.view.value.remainingMillis,p.host.view.value.remainingMillis)
    }

    @Test fun fullSyncWithoutFinalReadyCannotRemoveHostPopupOrEnableOwnerMoves() = runTest {
        val p=Pairing(this);runCurrent();p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_000);runCurrent();p.guest.waitForHost();runCurrent()
        p.b.holdSyncAcks=true;p.a.connected=true;p.a.healthy=true
        advanceTimeBy(100);runCurrent()
        assertTrue(p.guest.view.value.hostDisconnected);assertTrue(p.guest.view.value.waiting)
        assertTrue(p.host.view.value.waiting)
        p.host.request("PICK",number=5);p.guest.request("PICK",number=7);runCurrent()
        assertTrue(p.host.view.value.snapshot!!.picks.isEmpty())
        advanceTimeBy(2_000);runCurrent()
        assertEquals(15_000L,p.guest.view.value.remainingMillis)
        p.b.holdSyncAcks=false
        p.b.heldAcks.toList().forEach{p.host.receive(it)};runCurrent()
        assertFalse(p.guest.view.value.hostDisconnected)
        assertFalse(p.guest.view.value.waiting)
        assertEquals(15_000L,p.guest.view.value.remainingMillis)
    }

    @Test fun replayedStateAndWrongReadyCannotUnlockHostHold() = runTest {
        val p=Pairing(this);runCurrent();val old=p.broker.retained!!
        p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_000);runCurrent();p.guest.waitForHost();runCurrent()
        p.guest.receive(old)
        p.guest.receive(old.copy(type="MATCH_V2_READY",requestId="old-recovery"))
        p.guest.receive(old.copy(type="MATCH_V2_SYNC",requestId="old-recovery"))
        p.guest.receive(old.copy(type="MATCH_V2_READY",hostWaitVersion=0))
        runCurrent();assertTrue(p.guest.view.value.hostDisconnected)
        assertEquals(15_000L,p.guest.view.value.remainingMillis)
    }

    @Test fun delayedReadyRetriesWithoutLettingRetainedStateUnlockThePopup() = runTest {
        val p=Pairing(this);runCurrent();p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_000);runCurrent();p.guest.waitForHost();runCurrent()
        p.a.dropReady=true;p.a.connected=true;p.a.healthy=true
        advanceTimeBy(100);runCurrent()
        assertTrue(p.guest.view.value.hostDisconnected)
        p.a.dropReady=false
        advanceTimeBy(2_600);runCurrent()
        assertFalse(p.guest.view.value.hostDisconnected)
        assertFalse(p.guest.view.value.waiting)
    }

    @Test fun guestStorageFailureKeepsPopupAndRetriesInsteadOfUnlocking() = runTest {
        val p=Pairing(this);runCurrent();p.a.connected=false;p.a.healthy=false
        advanceTimeBy(15_000);runCurrent();p.guest.waitForHost();runCurrent()
        p.bStore.fail=true;p.a.connected=true;p.a.healthy=true
        advanceTimeBy(500);runCurrent();assertTrue(p.guest.view.value.hostDisconnected)
        p.bStore.fail=false
        advanceTimeBy(3_000);runCurrent()
        assertFalse(p.guest.view.value.hostDisconnected)
    }

    @Test fun hostReturningWithoutGuestSuspendsOfflineTimeThenContinuesNormalTurns() = runTest {
        val p=Pairing(this);runCurrent();advanceTimeBy(10_000);runCurrent()
        p.a.connected=false;p.a.healthy=false;p.b.connected=false;p.b.healthy=false
        advanceTimeBy(600_000);runCurrent()
        val turn=p.host.view.value.snapshot!!.turn
        p.a.connected=true;p.a.healthy=true
        advanceTimeBy(15_200);runCurrent()
        assertFalse(p.host.view.value.waiting)
        assertEquals(turn,p.host.view.value.snapshot!!.turn)
        assertTrue(p.host.view.value.remainingMillis in 19_500L..20_000L)
        advanceTimeBy(20_000);runCurrent()
        assertEquals(turn+1,p.host.view.value.snapshot!!.turn)
        assertTrue(p.host.view.value.remainingMillis in 29_500L..30_000L)
    }

    @Test fun guestAbsenceStillAllowsTheActiveHostToAdvanceNormally() = runTest {
        val p=Pairing(this);runCurrent();p.b.connected=false;p.b.healthy=false
        advanceTimeBy(30_000);runCurrent()
        assertEquals(2,p.host.view.value.snapshot!!.turn)
        assertEquals(30_000L,p.host.view.value.remainingMillis)
        assertFalse(p.host.view.value.waiting)
    }

    @Test fun duplicateReadyCannotRestartTheResumedCountdown() = runTest {
        val p=Pairing(this);runCurrent()
        val ready=p.a.sent.last{it.type=="MATCH_V2_READY"}
        advanceTimeBy(5_000);runCurrent();val before=p.guest.view.value.remainingMillis
        p.guest.receive(ready);runCurrent()
        assertEquals(before,p.guest.view.value.remainingMillis)
    }

    @Test fun coldHostWithPendingCheckpointAndAbsentGuestRecoversTheBrokerThenResumesAlone() = runTest {
        val p=Pairing(this);runCurrent();val accepted=p.aStore.accepted!!
        p.host.close();p.a.connected=false;p.guest.close();p.b.connected=false
        val endpoint=p.broker.Endpoint();val store=Store()
        val returning=ReliableMatchClient(accepted,"A","owner-A",p.clock,endpoint,store,backgroundScope,previouslyAccepted=false)
        endpoint.receive=returning::receive;runCurrent()
        assertTrue(returning.view.value.waiting)
        advanceTimeBy(15_100);runCurrent()
        assertFalse(returning.view.value.waiting)
        assertEquals(1,returning.view.value.snapshot!!.turn)
        assertTrue(returning.view.value.remainingMillis in 29_800..30_000)
    }

}
