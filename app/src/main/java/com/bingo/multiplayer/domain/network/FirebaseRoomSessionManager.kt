package com.bingo.multiplayer.domain.network

import androidx.annotation.Keep
import com.bingo.multiplayer.domain.model.GameStatus
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Keep
@Serializable
data class RoomSnapshot(
    val roomCode: String,
    val hostId: String,
    val players: List<Player> = emptyList(),
    val boardSize: Int = 5,
    val gameStatus: GameStatus = GameStatus.WAITING_FOR_PLAYERS,
    val currentTurnPlayerId: String = "",
    val turnNumber: Int = 1,
    val pickedNumbers: List<Int> = emptyList(),
    val winnerId: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

@Keep
sealed interface SelectNumberResult {
    @Keep
    data class Success(
        val number: Int,
        val turnNumber: Int,
        val nextPlayerId: String
    ) : SelectNumberResult

    @Keep
    data class Rejected(
        val reason: String
    ) : SelectNumberResult
}

@Keep
interface FirestoreRoomSnapshotSource {
    fun listenToRoom(roomCode: String): Flow<RoomSnapshot?>
    suspend fun updateRoomSnapshot(roomCode: String, snapshot: RoomSnapshot): Boolean
    suspend fun deleteRoom(roomCode: String): Boolean
}

@Keep
class InMemoryFirestoreSnapshotSource : FirestoreRoomSnapshotSource {
    private val rooms = ConcurrentHashMap<String, MutableStateFlow<RoomSnapshot?>>()
    private val mutex = Mutex()

    override fun listenToRoom(roomCode: String): Flow<RoomSnapshot?> {
        val flow = rooms.computeIfAbsent(roomCode) { MutableStateFlow(null) }
        return flow.asStateFlow()
    }

    override suspend fun updateRoomSnapshot(roomCode: String, snapshot: RoomSnapshot): Boolean = mutex.withLock {
        val flow = rooms.computeIfAbsent(roomCode) { MutableStateFlow(null) }
        flow.value = snapshot
        true
    }

    override suspend fun deleteRoom(roomCode: String): Boolean = mutex.withLock {
        val flow = rooms[roomCode]
        if (flow != null) {
            flow.value = null
            rooms.remove(roomCode)
            true
        } else {
            false
        }
    }

    fun getSnapshot(roomCode: String): RoomSnapshot? = rooms[roomCode]?.value
}

/**
 * Firebase / Firestore Room Session Manager.
 * Orchestrates online multiplayer room lifecycle, Firestore snapshot streams,
 * race-condition mitigation on concurrent number selection, and host-disconnect cleanups.
 */
class FirebaseRoomSessionManager(
    private val snapshotSource: FirestoreRoomSnapshotSource = InMemoryFirestoreSnapshotSource(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val roomMutex = Mutex()
    private val activeListeners = ConcurrentHashMap<String, Job>()
    private val _currentRoomState = MutableStateFlow<RoomSnapshot?>(null)
    val currentRoomState: StateFlow<RoomSnapshot?> = _currentRoomState.asStateFlow()

    /**
     * Creates a new Firebase room.
     */
    suspend fun createRoom(
        roomCode: String,
        hostPlayer: Player,
        boardSize: Int = 5
    ): Result<RoomSnapshot> = roomMutex.withLock {
        val initialSnapshot = RoomSnapshot(
            roomCode = roomCode,
            hostId = hostPlayer.id,
            players = listOf(hostPlayer.copy(isHost = true)),
            boardSize = boardSize,
            gameStatus = GameStatus.WAITING_FOR_PLAYERS,
            currentTurnPlayerId = hostPlayer.id,
            turnNumber = 1,
            pickedNumbers = emptyList(),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        val success = snapshotSource.updateRoomSnapshot(roomCode, initialSnapshot)
        if (success) {
            _currentRoomState.value = initialSnapshot
            startListeningToRoom(roomCode)
            Result.success(initialSnapshot)
        } else {
            Result.failure(IllegalStateException("Failed to create room $roomCode in Firestore"))
        }
    }

    /**
     * Joins an existing Firebase room.
     */
    suspend fun joinRoom(roomCode: String, player: Player): Result<RoomSnapshot> = roomMutex.withLock {
        val current = snapshotSource.listenToRoom(roomCode).firstOrNull()
            ?: _currentRoomState.value
            ?: return Result.failure(NoSuchElementException("Room $roomCode does not exist"))

        if (current.players.any { it.id == player.id }) {
            // Already joined
            _currentRoomState.value = current
            startListeningToRoom(roomCode)
            return Result.success(current)
        }

        if (current.players.size >= 8) {
            return Result.failure(IllegalStateException("Room $roomCode is already full (max 8 players)"))
        }

        val updatedPlayers = current.players + player.copy(isHost = false)
        val updated = current.copy(
            players = updatedPlayers,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        snapshotSource.updateRoomSnapshot(roomCode, updated)
        _currentRoomState.value = updated
        startListeningToRoom(roomCode)
        Result.success(updated)
    }


    /**
     * Starts the game when host triggers it.
     */
    suspend fun startGame(roomCode: String, hostId: String, dynamicSize: Int): Result<RoomSnapshot> = roomMutex.withLock {
        val current = snapshotSource.listenToRoom(roomCode).firstOrNull() ?: _currentRoomState.value
            ?: return Result.failure(NoSuchElementException("Room not found"))
        if (current.hostId != hostId) {
            return Result.failure(SecurityException("Only the host can start the game"))
        }
        if (current.players.size < 2) {
            return Result.failure(IllegalStateException("At least 2 players are required to start"))
        }

        val updated = current.copy(
            boardSize = dynamicSize,
            gameStatus = GameStatus.IN_PROGRESS,
            currentTurnPlayerId = hostId,
            turnNumber = 1,
            pickedNumbers = emptyList(),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        snapshotSource.updateRoomSnapshot(roomCode, updated)
        _currentRoomState.value = updated
        Result.success(updated)
    }

    /**
     * Selects a number with atomic race-condition checking.
     * Prevents duplicate selections, illegal turns, and concurrent conflicts.
     */
    suspend fun selectNumber(
        roomCode: String,
        playerId: String,
        number: Int
    ): SelectNumberResult = roomMutex.withLock {
        val current = snapshotSource.listenToRoom(roomCode).firstOrNull() ?: _currentRoomState.value
            ?: return SelectNumberResult.Rejected("Room $roomCode does not exist")

        if (current.gameStatus != GameStatus.IN_PROGRESS) {
            return SelectNumberResult.Rejected("Game is not currently in progress")
        }

        // Validate turn authority
        if (current.currentTurnPlayerId != playerId) {
            return SelectNumberResult.Rejected("Not player's turn. Current turn belongs to ${current.currentTurnPlayerId}")
        }

        // Validate number legality
        val maxNumber = current.boardSize * current.boardSize
        if (number < 1 || number > maxNumber) {
            return SelectNumberResult.Rejected("Number $number is out of valid bounds (1..$maxNumber)")
        }

        // Check if already picked (Concurrent race condition check)
        if (number in current.pickedNumbers) {
            return SelectNumberResult.Rejected("Number $number has already been picked")
        }

        // Determine next player turn
        val playerList = current.players
        val currentIndex = playerList.indexOfFirst { it.id == playerId }
        val nextIndex = if (currentIndex != -1 && playerList.isNotEmpty()) {
            (currentIndex + 1) % playerList.size
        } else {
            0
        }
        val nextPlayerId = playerList.getOrNull(nextIndex)?.id ?: ""

        val updated = current.copy(
            pickedNumbers = current.pickedNumbers + number,
            turnNumber = current.turnNumber + 1,
            currentTurnPlayerId = nextPlayerId,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        snapshotSource.updateRoomSnapshot(roomCode, updated)
        _currentRoomState.value = updated
        SelectNumberResult.Success(
            number = number,
            turnNumber = updated.turnNumber,
            nextPlayerId = nextPlayerId
        )
    }


    /**
     * Handles host disconnection: initiates graceful room cleanup.
     */
    suspend fun handleHostDisconnect(roomCode: String): Result<Unit> = roomMutex.withLock {
        val current = _currentRoomState.value
        if (current != null && current.roomCode == roomCode) {
            val terminated = current.copy(
                gameStatus = GameStatus.GAME_OVER,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            snapshotSource.updateRoomSnapshot(roomCode, terminated)
        }
        snapshotSource.deleteRoom(roomCode)
        stopListening(roomCode)
        _currentRoomState.value = null
        Result.success(Unit)
    }

    /**
     * Player leaves the room.
     */
    suspend fun leaveRoom(roomCode: String, playerId: String): Result<Unit> = roomMutex.withLock {
        val current = _currentRoomState.value ?: return Result.success(Unit)
        if (current.hostId == playerId) {
            return handleHostDisconnect(roomCode)
        }

        val remaining = current.players.filter { it.id != playerId }
        val updated = current.copy(
            players = remaining,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        snapshotSource.updateRoomSnapshot(roomCode, updated)
        if (playerId == current.players.firstOrNull()?.id) {
            stopListening(roomCode)
            _currentRoomState.value = null
        }
        Result.success(Unit)
    }

    /**
     * Heartbeat updater to record presence in Firestore.
     */
    suspend fun sendHeartbeat(roomCode: String, playerId: String): Boolean = roomMutex.withLock {
        val current = _currentRoomState.value ?: return false
        val updatedPlayers = current.players.map {
            if (it.id == playerId) it.copy(lastSeenTimestamp = System.currentTimeMillis()) else it
        }
        val updated = current.copy(
            players = updatedPlayers,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        snapshotSource.updateRoomSnapshot(roomCode, updated)
    }

    fun startListeningToRoom(roomCode: String) {
        if (activeListeners.containsKey(roomCode)) return

        val job = scope.launch {
            snapshotSource.listenToRoom(roomCode).collect { snapshot ->
                _currentRoomState.value = snapshot
            }
        }
        activeListeners[roomCode] = job
    }

    fun stopListening(roomCode: String) {
        activeListeners.remove(roomCode)?.cancel()
    }

    fun cleanup() {
        activeListeners.values.forEach { it.cancel() }
        activeListeners.clear()
        _currentRoomState.value = null
    }
}
