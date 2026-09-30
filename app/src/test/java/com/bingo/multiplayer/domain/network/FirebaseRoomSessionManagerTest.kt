package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.GameStatus
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FirebaseRoomSessionManagerTest {

    private lateinit var snapshotSource: InMemoryFirestoreSnapshotSource
    private lateinit var sessionManager: FirebaseRoomSessionManager

    private val hostPlayer = Player(id = "host_1", displayName = "Host User", isHost = true)
    private val guestPlayer1 = Player(id = "guest_1", displayName = "Guest One", isHost = false)
    private val guestPlayer2 = Player(id = "guest_2", displayName = "Guest Two", isHost = false)

    @Before
    fun setUp() {
        snapshotSource = InMemoryFirestoreSnapshotSource()
        sessionManager = FirebaseRoomSessionManager(snapshotSource)
    }

    @Test
    fun testCreateRoom_initializesValidFirestoreSnapshot() = runBlocking {
        val result = sessionManager.createRoom(
            roomCode = "ROOM42",
            hostPlayer = hostPlayer,
            boardSize = 5
        )

        assertTrue("Room creation should succeed", result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals("ROOM42", snapshot.roomCode)
        assertEquals(hostPlayer.id, snapshot.hostId)
        assertEquals(GameStatus.WAITING_FOR_PLAYERS, snapshot.gameStatus)
        assertEquals(1, snapshot.players.size)
        assertTrue(snapshot.players.first().isHost)

        // Observe snapshot stream
        val observed = snapshotSource.listenToRoom("ROOM42").first()
        assertNotNull(observed)
        assertEquals("ROOM42", observed?.roomCode)
    }

    @Test
    fun testJoinRoom_addsPlayersToSnapshot() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)

        val join1 = sessionManager.joinRoom("ROOM42", guestPlayer1)
        assertTrue(join1.isSuccess)
        assertEquals(2, join1.getOrThrow().players.size)

        val join2 = sessionManager.joinRoom("ROOM42", guestPlayer2)
        assertTrue(join2.isSuccess)
        assertEquals(3, join2.getOrThrow().players.size)
    }

    @Test
    fun testStartGame_onlyHostCanStart() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)
        sessionManager.joinRoom("ROOM42", guestPlayer1)

        // Guest attempts to start -> should fail with SecurityException
        val guestAttempt = sessionManager.startGame("ROOM42", hostId = guestPlayer1.id, dynamicSize = 5)
        assertTrue(guestAttempt.isFailure)
        assertTrue(guestAttempt.exceptionOrNull() is SecurityException)

        // Host starts -> succeeds and status becomes IN_PROGRESS
        val hostStart = sessionManager.startGame("ROOM42", hostId = hostPlayer.id, dynamicSize = 5)
        assertTrue(hostStart.isSuccess)
        assertEquals(GameStatus.IN_PROGRESS, hostStart.getOrThrow().gameStatus)
    }

    @Test
    fun testConcurrentNumberSelection_raceConditionsHandledGracefully() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)
        sessionManager.joinRoom("ROOM42", guestPlayer1)
        sessionManager.startGame("ROOM42", hostPlayer.id, 5)

        // 1. Host's turn: Host picks 7 -> SUCCESS
        val pick1 = sessionManager.selectNumber("ROOM42", playerId = hostPlayer.id, number = 7)
        assertTrue("Host pick should succeed", pick1 is SelectNumberResult.Success)
        val success1 = pick1 as SelectNumberResult.Success
        assertEquals(7, success1.number)
        assertEquals(guestPlayer1.id, success1.nextPlayerId)

        // 2. Race condition: Host tries to pick again immediately out of turn -> REJECTED
        val outOfTurn = sessionManager.selectNumber("ROOM42", playerId = hostPlayer.id, number = 14)
        assertTrue("Out of turn pick must be rejected", outOfTurn is SelectNumberResult.Rejected)
        assertTrue((outOfTurn as SelectNumberResult.Rejected).reason.contains("Not player's turn"))

        // 3. Guest picks the SAME number (7) that was already chosen -> REJECTED (Duplicate selection)
        val duplicatePick = sessionManager.selectNumber("ROOM42", playerId = guestPlayer1.id, number = 7)
        assertTrue("Duplicate pick must be rejected", duplicatePick is SelectNumberResult.Rejected)
        assertTrue((duplicatePick as SelectNumberResult.Rejected).reason.contains("already been picked"))

        // 4. Guest picks a valid number (12) -> SUCCESS
        val pick2 = sessionManager.selectNumber("ROOM42", playerId = guestPlayer1.id, number = 12)
        assertTrue("Valid pick should succeed", pick2 is SelectNumberResult.Success)
        assertEquals(12, (pick2 as SelectNumberResult.Success).number)
    }

    @Test
    fun testSelectNumber_outOfBoundsRejected() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)
        sessionManager.joinRoom("ROOM42", guestPlayer1)
        sessionManager.startGame("ROOM42", hostPlayer.id, 5)

        // On a 5x5 board, max number is 25
        val outOfBounds = sessionManager.selectNumber("ROOM42", hostPlayer.id, number = 26)
        assertTrue(outOfBounds is SelectNumberResult.Rejected)
        assertTrue((outOfBounds as SelectNumberResult.Rejected).reason.contains("out of valid bounds"))
    }

    @Test
    fun testHostDisconnect_cleansUpRoomInFirestore() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)
        sessionManager.joinRoom("ROOM42", guestPlayer1)

        // Host disconnects
        val result = sessionManager.handleHostDisconnect("ROOM42")
        assertTrue(result.isSuccess)

        // Verify room was deleted from snapshot source
        assertNull(snapshotSource.getSnapshot("ROOM42"))
    }

    @Test
    fun testHeartbeat_updatesPlayerTimestamp() = runBlocking {
        sessionManager.createRoom("ROOM42", hostPlayer, boardSize = 5)
        val initialTimestamp = snapshotSource.getSnapshot("ROOM42")!!.players.first().lastSeenTimestamp

        // Delay briefly to verify timestamp increment
        kotlinx.coroutines.delay(10)
        sessionManager.sendHeartbeat("ROOM42", hostPlayer.id)

        val updatedTimestamp = snapshotSource.getSnapshot("ROOM42")!!.players.first().lastSeenTimestamp
        assertTrue("Timestamp should be refreshed", updatedTimestamp >= initialTimestamp)
    }
}
