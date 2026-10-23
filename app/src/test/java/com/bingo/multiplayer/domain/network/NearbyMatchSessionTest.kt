package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class NearbyMatchSessionTest {
    private fun host() = NearbyMatchSession().apply { bind(77, "host", "host"); bindTurn(1, "guest", false, 1_000) }
    private fun guest() = NearbyMatchSession().apply { bind(77, "guest", "host"); bindTurn(1, "guest", false, 1_000) }
    private fun snapshot(host: NearbyMatchSession, now: Long, request: String = "") = host.prepareOutgoing(RoomMessagePacket(
        type = "GAME_SYNC", playerId = "host", senderPlayerId = "host", seed = 77,
        turnNumber = host.turnClock!!.turnNumber, currentTurnPlayerId = host.turnClock!!.playerId,
        remainingTurnMillis = host.turnClock!!.remainingMillis(now), requestId = request))

    @Test fun `host background freezes exact remaining time across a long absence`() {
        val host = host()
        host.background(13_000)
        assertTrue(host.state.suspended)
        assertEquals(18_000, host.remainingMillis(100_000, true, false))
        host.foreground(100_000)
        assertEquals(18_000, host.remainingMillis(100_000, true, false))
        assertEquals(17_000, host.remainingMillis(101_000, true, false))
    }

    @Test fun `hold snapshot freezes guest and resumed snapshot restores same countdown`() {
        val host = host(); val guest = guest()
        host.background(13_000)
        val hold = snapshot(host, 13_000)
        assertTrue(guest.canApplySnapshot(hold)); guest.snapshotApplied(hold, 13_100)
        assertTrue(guest.state.hostUnavailable)
        assertEquals(18_000, guest.remainingMillis(99_000, true, false))
        host.foreground(100_000)
        val resumed = snapshot(host, 100_000)
        assertTrue(guest.canApplySnapshot(resumed)); guest.snapshotApplied(resumed, 100_050)
        assertFalse(guest.state.suspended)
        assertEquals(18_000, guest.remainingMillis(100_050, true, false))
        assertFalse("Old hold cannot re-freeze", guest.canApplySnapshot(hold))
    }

    @Test fun `returning guest waits for a response to the current sync request`() {
        val host = host(); val guest = guest()
        val queued = snapshot(host, 2_000)
        guest.background(3_000)
        val request = guest.foreground(4_000)
        assertTrue(guest.state.suspended)
        assertFalse(guest.canApplySnapshot(queued))
        val fresh = snapshot(host, 4_000, request)
        assertTrue(guest.canApplySnapshot(fresh)); guest.snapshotApplied(fresh, 4_100)
        assertFalse(guest.state.suspended)
        assertEquals(27_000, guest.remainingMillis(4_100, true, false))
    }

    @Test fun `guest returning while host absent stays held until the host returns`() {
        val host = host(); val guest = guest()
        host.background(7_000); guest.background(8_000)
        guest.foreground(9_000)
        val hold = snapshot(host, 9_000)
        assertTrue(guest.canApplySnapshot(hold)); guest.snapshotApplied(hold, 9_010)
        assertTrue(guest.state.suspended)
        assertTrue(guest.state.hostUnavailable)
    }

    @Test fun `backgrounding during a running turn does not shorten thirty seconds`() {
        val host = host()
        assertEquals(18_000, host.remainingMillis(13_000, false, false))
        assertEquals(0, host.remainingMillis(31_000, false, false))
        host.bindTurn(3, "guest", true, 32_000)
        assertEquals(5_000, host.remainingMillis(32_000, false, false))
    }

    @Test fun `five second absent turn grants welcome back once and never restores a missed turn`() {
        val host = host()
        host.bindTurn(3, "guest", true, 32_000)
        assertEquals(1_000, host.remainingMillis(36_000, false, false))
        assertEquals(30_000, host.remainingMillis(36_000, true, false))
        assertEquals(29_000, host.remainingMillis(37_000, false, false))
        assertEquals(28_000, host.remainingMillis(38_000, true, false))
        host.bindTurn(5, "guest", true, 80_000)
        assertEquals(0, host.remainingMillis(85_000, true, false))
    }

    @Test fun `guest never publishes timeouts and stale pre hold gameplay is rejected`() {
        val host = host(); val guest = guest()
        assertTrue(host.canPublishTimeout()); assertFalse(guest.canPublishTimeout())
        val pick = RoomMessagePacket(type = "PICK_NUMBER", playerId = "guest", seed = 77)
        assertTrue(host.canAcceptGameplay(pick))
        host.background(2_000); assertFalse(host.canAcceptGameplay(pick))
        host.foreground(3_000); assertFalse(host.canAcceptGameplay(pick))
        assertTrue(host.canAcceptGameplay(pick.copy(controllerEpoch = host.state.epoch)))
        assertFalse(guest.canAcceptGameplay(RoomMessagePacket(type = "TURN_TIMEOUT", playerId = "guest", senderPlayerId = "guest", seed = 77)))
    }

    @Test fun `zero seconds survives host hold and resumes as one timeout rather than thirty seconds`() {
        val host = host(); val guest = guest()
        host.background(31_000)
        host.foreground(50_000)
        val packet = snapshot(host, 50_000)
        assertTrue(guest.canApplySnapshot(packet)); guest.snapshotApplied(packet, 50_100)
        assertEquals(0, guest.remainingMillis(50_100, true, false))
    }

    @Test fun `manual pause survives host absence and neither clock budget resets`() {
        val host = host()
        assertEquals(20_000, host.remainingMillis(11_000, true, true))
        host.background(12_000); host.foreground(90_000)
        assertEquals(20_000, host.remainingMillis(90_000, true, true))
        assertEquals(20_000, host.remainingMillis(95_000, true, false))
    }

    @Test fun `transport loss freezes guest without changing match identity or turn`() {
        val guest = guest()
        guest.transportLost(11_000)
        assertTrue(guest.state.hostUnavailable)
        assertEquals(1, guest.turnClock!!.turnNumber)
        assertEquals(20_000, guest.remainingMillis(90_000, false, false))
        assertFalse(guest.canPublishTimeout())
    }

    @Test fun `wrong host wrong match malformed budget and stale epoch cannot release a hold`() {
        val host = host(); val guest = guest()
        host.background(2_000)
        val packet = snapshot(host, 2_000)
        assertFalse(guest.canApplySnapshot(packet.copy(seed = 88)))
        assertFalse(guest.canApplySnapshot(packet.copy(playerId = "other")))
        assertFalse(guest.canApplySnapshot(packet.copy(remainingTurnMillis = 31_000)))
        assertFalse(guest.canApplySnapshot(packet.copy(nearbyControl = packet.nearbyControl!!.copy(initialTurnSeconds = 3))))
        guest.snapshotApplied(packet, 2_000)
        assertFalse(guest.canApplySnapshot(packet.copy(nearbyControl = packet.nearbyControl!!.copy(epoch = 0))))
    }

    @Test fun `snapshot codec preserves held state countdown board picks chat and accepted timeout remains deduplicated`() {
        val host = host(); host.background(13_000)
        val engine = BingoEngine()
        val a = engine.generateBoard(5, 11); val b = engine.generateBoard(5, 12)
        val context = MultiplayerGameplayContext(77, "guest", "host",
            listOf(Player("host", "Kevin", isHost = true), Player("guest", "Stuart")), emptyList(), allowPeerTimeouts = true)
        val packet = snapshot(host, 13_000).copy(pickedHistory = listOf(4), pickedByHistory = listOf("host"),
            turnIds = listOf(1), turnNumber = 2, currentTurnPlayerId = "guest",
            chatMessages = listOf(com.bingo.multiplayer.domain.model.InGameChatMessage(1, "hello", false, "Kevin", 1)))
        val decoded = FastPacketCodec.decode(FastPacketCodec.encode(packet))!!
        val accepted = MultiplayerSnapshotReconciler.reconcile(decoded, 77, "host", "guest", MultiplayerSnapshotCheckpoint(),
            emptyList(), b, a, mapOf("host" to a, "guest" to b))!!
        assertEquals(listOf(4), accepted.picks.map { it.number })
        assertTrue(accepted.playerBoard.cells.first { it.number == 4 }.isMarked)
        assertEquals("hello", decoded.chatMessages.single().text)
        val guest = guest(); guest.snapshotApplied(decoded, 13_100)
        val state = MultiplayerGameplayState(accepted.picks, accepted.playerBoard, accepted.opponentBoard, accepted.allPlayerBoards,
            2, "guest", 18_000, isSuspended = guest.state.suspended)
        val timeout = MultiplayerGameplayReducer.timeoutPacket(77, "host", "guest", 2, "host")
        assertFalse(MultiplayerGameplayReducer.timeout(timeout, context, state).accepted)
        val live = state.copy(isSuspended = false)
        val passed = MultiplayerGameplayReducer.timeout(timeout, context, live)
        assertTrue(passed.accepted)
        assertFalse(MultiplayerGameplayReducer.timeout(timeout, context, passed.state).accepted)
    }
    @Test fun `hold and resume codec preserve recovery controls instead of using lossy legacy micro packets`() {
        val host = host(); host.background(13_000)
        val hold = snapshot(host, 13_000).copy(type = "HOST_HOLD")
        val decoded = FastPacketCodec.decode(FastPacketCodec.encode(hold))!!
        assertEquals(hold.nearbyControl, decoded.nearbyControl)
        assertEquals(18_000, decoded.remainingTurnMillis)
        assertEquals(1, decoded.turnNumber)
        val guest = guest()
        assertTrue(guest.canApplySnapshot(guest.normalizePacket(decoded)))
    }

    @Test fun `repeated background cycles preserve board turn budget and reject previous recovery epochs`() {
        val host = host(); val guest = guest()
        var now = 2_000L
        repeat(12) {
            host.background(now)
            val hold = snapshot(host, now)
            assertTrue(guest.canApplySnapshot(hold)); guest.snapshotApplied(hold, now)
            now += 20_000
            host.foreground(now)
            val resumed = snapshot(host, now)
            assertTrue(guest.canApplySnapshot(resumed)); guest.snapshotApplied(resumed, now)
            assertEquals(1, guest.turnClock!!.turnNumber)
            assertEquals(30_000, guest.turnClock!!.initialBudgetMillis)
            assertFalse(guest.canApplySnapshot(hold))
            assertEquals(host.turnClock!!.remainingMillis(now), guest.turnClock!!.remainingMillis(now))
            now += 100
        }
    }

    @Test fun `in flight snapshot preserves a pending next turn clock until host confirmation`() {
        val guest = guest(); val host = host()
        guest.bindTurn(2, "host", false, 13_000)
        val inFlight = snapshot(host, 13_000)
        assertTrue(guest.canApplySnapshot(inFlight))
        guest.snapshotApplied(inFlight, 13_100, preservePendingTurn = true)
        assertEquals(2, guest.turnClock!!.turnNumber)
        assertEquals("host", guest.turnClock!!.playerId)
        assertEquals(29_900, guest.turnClock!!.remainingMillis(13_100))
        guest.transportLost(14_000)
        val request = guest.foreground(15_000)
        val recovery = snapshot(host, 15_000, request)
        assertTrue(guest.canApplySnapshot(recovery))
        guest.snapshotApplied(recovery, 15_100)
        assertEquals(1, guest.turnClock!!.turnNumber)
        assertEquals("guest", guest.turnClock!!.playerId)
        assertEquals(16_000, guest.turnClock!!.remainingMillis(15_100))
    }

}
