package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.RoomMessagePacket
import org.junit.Assert.*
import org.junit.Test

class LobbyLifecycleEngineTest {

    @Test
    fun testMonotonicVersionStrictlyIncreases() {
        var v = 100L
        for (i in 1..1000) {
            val next = LobbyLifecycleEngine.nextVersion(v)
            assertTrue("Expected $next > $v", next > v)
            v = next
        }
    }

    @Test
    fun testLocalToggleReadyTransitionsAndVersion() {
        val player = Player(
            id = "p1",
            displayName = "Player1",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 100L
        )

        // Toggle to Ready
        val readyPlayer = LobbyLifecycleEngine.onLocalToggleReady(player, isReady = true)
        assertEquals(LobbyLifecycleEngine.STATUS_READY, readyPlayer.lobbyReadyStatus)
        assertTrue(readyPlayer.readyVersion > player.readyVersion)

        // Toggle back to Not Ready
        val unreadyPlayer = LobbyLifecycleEngine.onLocalToggleReady(readyPlayer, isReady = false)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, unreadyPlayer.lobbyReadyStatus)
        assertTrue(unreadyPlayer.readyVersion > readyPlayer.readyVersion)
    }

    @Test
    fun testRapidToggling1000TimesMaintainsMonotonicity() {
        var player = Player(
            id = "p1",
            displayName = "RapidPlayer",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 1L
        )

        for (i in 1..1000) {
            val shouldBeReady = (i % 2 == 1)
            player = LobbyLifecycleEngine.onLocalToggleReady(player, isReady = shouldBeReady)
            val expectedStatus = if (shouldBeReady) LobbyLifecycleEngine.STATUS_READY else LobbyLifecycleEngine.STATUS_NOT_READY
            assertEquals(expectedStatus, player.lobbyReadyStatus)
        }
        assertTrue(player.readyVersion >= 1001L)
    }

    @Test
    fun testReconcileReadyStatusHigherVersionWins() {
        val (status, ver) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            currentVersion = 100L,
            incomingStatus = LobbyLifecycleEngine.STATUS_READY,
            incomingVersion = 200L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_READY, status)
        assertEquals(200L, ver)
    }

    @Test
    fun testReconcileReadyStatusStaleLowerVersionIgnored() {
        val (status, ver) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_READY,
            currentVersion = 300L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 150L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_READY, status)
        assertEquals(300L, ver)
    }

    @Test
    fun testReconcileReadyStatusActiveOverridesLeftLobbyOnEqualVersion() {
        val (status, _) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY,
            currentVersion = 500L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 500L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, status)
    }

    @Test
    fun testPlayerLeaveAndRejoinLifecycle() {
        val p2 = Player(id = "p2", displayName = "Player2", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY, readyVersion = 100L)

        // Player 2 leaves lobby
        val leftPlayer = LobbyLifecycleEngine.onPlayerLeave(p2)
        assertEquals(LobbyLifecycleEngine.STATUS_LEFT_LOBBY, leftPlayer.lobbyReadyStatus)
        assertTrue(leftPlayer.readyVersion > p2.readyVersion)

        // Player 2 re-enters lobby
        val rejoinedPlayer = LobbyLifecycleEngine.onPlayerJoinSession(
            player = Player(id = "p2", displayName = "Player2", isHost = false),
            existingPlayerInSession = leftPlayer
        )
        // Must be NOT_READY and have version higher than leftPlayer's version
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, rejoinedPlayer.lobbyReadyStatus)
        assertTrue(rejoinedPlayer.readyVersion > leftPlayer.readyVersion)

        // Reconciling with cloud that had leftPlayer must now yield NOT_READY because rejoinedPlayer has higher version
        val (reconciledStatus, reconciledVer) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = leftPlayer.lobbyReadyStatus,
            currentVersion = leftPlayer.readyVersion,
            incomingStatus = rejoinedPlayer.lobbyReadyStatus,
            incomingVersion = rejoinedPlayer.readyVersion
        )
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, reconciledStatus)
        assertEquals(rejoinedPlayer.readyVersion, reconciledVer)
    }

    @Test
    fun testRemoteReadyStatusPacketRejectsStalePackets() {
        val existing = Player(
            id = "p2",
            displayName = "Player2",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY,
            readyVersion = 500L
        )

        // Stale packet with version 400
        val stalePacket = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "p2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 400L
        )
        val result = LobbyLifecycleEngine.onRemoteReadyStatusPacket(stalePacket, existing)
        assertNull("Stale packet must be discarded", result)

        // Newer packet with version 600
        val newerPacket = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "p2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 600L
        )
        val updated = LobbyLifecycleEngine.onRemoteReadyStatusPacket(newerPacket, existing)
        assertNotNull(updated)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, updated?.lobbyReadyStatus)
        assertEquals(600L, updated?.readyVersion)
    }

    @Test
    fun testCanStartMatchValidation() {
        val host = Player(id = "host", displayName = "Host", isHost = true, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        val p2 = Player(id = "p2", displayName = "P2", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY)

        // Only host: cannot start
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host)))

        // Host + unready P2: cannot start
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host, p2)))
        assertEquals(1, LobbyLifecycleEngine.countReadyPlayers(listOf(host, p2)))

        // P2 ready: can start
        val readyP2 = p2.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        assertTrue(LobbyLifecycleEngine.canStartMatch(listOf(host, readyP2)))
        assertEquals(2, LobbyLifecycleEngine.countReadyPlayers(listOf(host, readyP2)))

        // P2 left lobby: cannot start
        val leftP2 = p2.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY)
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host, leftP2)))
    }

    @Test
    fun testIsPlayerLeft() {
        val p = Player(
            id = "p2",
            displayName = "P2",
            isHost = false,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        assertTrue(LobbyLifecycleEngine.isPlayerLeft(p, isMe = false, rawPresenceStatus = "online"))

        val activePlayer = p.copy(
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        assertFalse(LobbyLifecycleEngine.isPlayerLeft(activePlayer, isMe = false, rawPresenceStatus = "online"))

        val timedOutPlayer = p.copy(
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            lastSeenTimestamp = System.currentTimeMillis() - 70_000L
        )
        assertTrue(LobbyLifecycleEngine.isPlayerLeft(timedOutPlayer, isMe = false, rawPresenceStatus = "offline"))
        // If it's me, should not mark left just due to presence delay
        assertFalse(LobbyLifecycleEngine.isPlayerLeft(timedOutPlayer, isMe = true, rawPresenceStatus = "offline"))
    }
}
