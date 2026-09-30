package com.bingo.multiplayer.domain.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED
}

@Keep
@Serializable
enum class PlayerOnlineStatus {
    ONLINE,
    LAST_SEEN_RECENTLY,
    OFFLINE
}

@Keep
@Serializable
data class Player(
    val id: String,
    val displayName: String,
    val username: String = "",
    val isHost: Boolean = false,
    val isAi: Boolean = false,
    val avatarUrl: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    val score: Int = 0,
    val completedLinesCount: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val level: Int = 1,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val lobbyReadyStatus: String = if (isHost) "READY" else "NOT_READY",
    val readyVersion: Long = 0L
) {
    val winRatePercentage: Int
        get() = if (gamesPlayed > 0) ((gamesWon.toFloat() / gamesPlayed) * 100).toInt() else 0

    val rankTitle: String
        get() = when {
            level >= 8 -> "Grandmaster"
            level >= 5 -> "Gold Master"
            level >= 3 -> "Silver Competitor"
            else -> "Bronze Player"
        }
    val onlineStatus: PlayerOnlineStatus
        get() {
            val diff = System.currentTimeMillis() - lastSeenTimestamp
            return when {
                diff < 15_000L -> PlayerOnlineStatus.ONLINE       // Within 15s = app is in foreground
                diff < 86_400_000L -> PlayerOnlineStatus.LAST_SEEN_RECENTLY // Within 24h = last seen
                else -> PlayerOnlineStatus.OFFLINE                 // Over 24h = offline
            }
        }

    val lastSeenDisplay: String
        get() {
            val diffSec = ((System.currentTimeMillis() - lastSeenTimestamp) / 1000).coerceAtLeast(0)
            if (diffSec < 15) return "online"

            // Over 24 hours = offline
            if (diffSec > 86400) return "offline"

            // Show "last seen just now" for very recent (< 60s)
            if (diffSec < 60) return "last seen just now"
            
            val hours = diffSec / 3600
            val minutes = (diffSec % 3600) / 60
            
            val parts = mutableListOf<String>()
            if (hours > 0) parts.add("${hours}h")
            if (minutes > 0 && hours < 24) parts.add("${minutes}m")
            
            return "last seen ${parts.joinToString(" ")} ago"
        }
}

@Keep
@Serializable
sealed interface CellMarkState {
    @Keep
    @Serializable
    data object Unmarked : CellMarkState

    @Keep
    @Serializable
    data class Marked(
        val pickedByPlayerId: String,
        val isOwnPick: Boolean,
        val turnNumber: Int
    ) : CellMarkState
}

@Keep
@Serializable
data class Cell(
    val row: Int,
    val col: Int,
    val number: Int,
    val markState: CellMarkState = CellMarkState.Unmarked,
    val isPartOfCompletedLine: Boolean = false,
    val isRecentPick: Boolean = false
) {
    val isMarked: Boolean
        get() = markState is CellMarkState.Marked
}

@Keep
@Serializable
enum class LineType {
    ROW,
    COLUMN,
    MAIN_DIAGONAL,
    ANTI_DIAGONAL
}

@Keep
@Serializable
data class LineCoordinate(
    val type: LineType,
    val index: Int
)

@Keep
@Serializable
data class Board(
    val size: Int,
    val cells: List<Cell>,
    val completedLines: Set<LineCoordinate> = emptySet(),
    val targetLines: Int = 5
) {
    init {
        require(cells.size == size * size) {
            "Board cells size (${cells.size}) must equal size * size (${size * size})"
        }
    }

    val maxNumber: Int get() = size * size
    val completedLinesCount: Int get() = completedLines.size
    val isBingo: Boolean get() = completedLinesCount >= targetLines

    fun findCellByNumber(number: Int): Cell? = cells.find { it.number == number }

    fun getCell(row: Int, col: Int): Cell = cells[row * size + col]

    fun copyWithCellUpdated(updatedCell: Cell): Board {
        val newCells = cells.toMutableList()
        val index = updatedCell.row * size + updatedCell.col
        newCells[index] = updatedCell
        return copy(cells = newCells)
    }
}

@Keep
@Serializable
data class RecentPick(
    val number: Int,
    val pickedByPlayerId: String,
    val turnNumber: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@Serializable
enum class GameStatus {
    WAITING_FOR_PLAYERS,
    IN_PROGRESS,
    GAME_OVER
}

@Keep
@Serializable
enum class GameMode {
    AI_EASY,
    AI_HARD,
    ONLINE_ROOM,
    NEARBY_NETWORK
}

