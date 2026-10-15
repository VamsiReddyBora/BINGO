package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Keep
@Serializable
data class OngoingMatchData(
    val roomCode: String,
    val matchSeed: Long,
    val boardSize: Int,
    val isDynamicBoard: Boolean = false,
    val isManualBoard: Boolean = false,
    val isHost: Boolean = false,
    val participants: List<Player> = emptyList(),
    val playerBoard: Board? = null,
    val opponentBoard: Board? = null,
    val allPlayerBoards: Map<String, Board> = emptyMap(),
    val pickedNumbers: List<Int> = emptyList(),
    val pickedByPlayers: List<String> = emptyList(),
    val turnIds: List<Int> = emptyList(),
    val turnNumber: Int = 1,
    val currentTurnPlayerId: String = "",
    val randomizedTurnOrder: List<Player> = emptyList(),
    val chatMessages: List<InGameChatMessage> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val is1v1Match: Boolean = false,
    val accountUid: String = "",
    val protocolVersion: Int = 1,
    val controllerEpoch: Long = 0L,
    val matchRevision: Long = 0L,
    val isPaused: Boolean = false,
    val remainingTurnMillis: Long? = null,
    val pauseOwnerId: String? = null,
    val committedEpisodeCount: Int = 0,
    val currentEpisodeId: String? = null,
    val isEpisodeOpen: Boolean = false,
    val peerAvailabilityState: String = "RESPONSIVE"
)

/**
 * Persists active online room match metadata so a player who temporarily exited,
 * switched apps, or experienced a connection drop can effortlessly rejoin the same
 * in-progress match from Home screen with complete state consistency.
 */
object OngoingMatchStore {
    private const val PREFS_NAME = "bingo_ongoing_match"
    private const val KEY_MATCH_DATA = "active_match_data"
    private const val MAX_MATCH_AGE_MS = 8 * 60 * 1000L // 8 minutes TTL for legacy group matches

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @androidx.annotation.VisibleForTesting
    var customPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context?): SharedPreferences {
        return customPrefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Context or customPrefs must be provided")
    }

    /**
     * Atomically and durably saves match checkpoint using commit().
     */
    fun saveOngoingMatch(
        context: Context?,
        matchData: OngoingMatchData
    ): Boolean {
        return try {
            val serialized = json.encodeToString(matchData)
            getPrefs(context).edit().putString(KEY_MATCH_DATA, serialized).commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Updates ongoing match state with incremental turn, board, pause, and heartbeat data.
     */
    fun updateMatchGameState(
        context: Context? = null,
        playerBoard: Board,
        opponentBoard: Board,
        allPlayerBoards: Map<String, Board>,
        pickedNumbers: List<Int>,
        pickedByPlayers: List<String>,
        turnNumber: Int,
        currentTurnPlayerId: String,
        chatMessages: List<InGameChatMessage> = emptyList(),
        turnIds: List<Int> = emptyList(),
        isPaused: Boolean? = null,
        remainingTurnMillis: Long? = null,
        pauseOwnerId: String? = null,
        committedEpisodeCount: Int? = null,
        currentEpisodeId: String? = null,
        isEpisodeOpen: Boolean? = null,
        peerAvailabilityState: String? = null,
        matchRevision: Long? = null
    ): Boolean {
        return try {
            val current = getOngoingMatch(context) ?: return false
            val updated = current.copy(
                playerBoard = playerBoard,
                opponentBoard = opponentBoard,
                allPlayerBoards = allPlayerBoards,
                pickedNumbers = pickedNumbers,
                pickedByPlayers = pickedByPlayers,
                turnIds = if (turnIds.isNotEmpty()) turnIds else current.turnIds,
                turnNumber = turnNumber,
                currentTurnPlayerId = currentTurnPlayerId,
                chatMessages = if (chatMessages.isNotEmpty()) chatMessages else current.chatMessages,
                isPaused = isPaused ?: current.isPaused,
                remainingTurnMillis = remainingTurnMillis ?: current.remainingTurnMillis,
                pauseOwnerId = pauseOwnerId ?: current.pauseOwnerId,
                committedEpisodeCount = committedEpisodeCount ?: current.committedEpisodeCount,
                currentEpisodeId = currentEpisodeId ?: current.currentEpisodeId,
                isEpisodeOpen = isEpisodeOpen ?: current.isEpisodeOpen,
                peerAvailabilityState = peerAvailabilityState ?: current.peerAvailabilityState,
                matchRevision = matchRevision ?: (current.matchRevision + 1L),
                lastUpdatedAt = System.currentTimeMillis()
            )
            saveOngoingMatch(context, updated)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves the active ongoing match.
     * 1v1 online matches bypass the 8-minute TTL so interrupted/recovering games never expire silently.
     * When accountUid is specified, ensures cross-account isolation (R09).
     */
    fun getOngoingMatch(context: Context? = null, accountUid: String? = null): OngoingMatchData? {
        val serialized = getPrefs(context).getString(KEY_MATCH_DATA, null) ?: return null
        return try {
            val data = json.decodeFromString<OngoingMatchData>(serialized)
            if (accountUid != null && data.accountUid.isNotBlank() && data.accountUid != accountUid) {
                // Cross-account isolation: match belongs to another account
                return null
            }
            val age = System.currentTimeMillis() - data.lastUpdatedAt
            val isTwoPlayer = data.is1v1Match || data.participants.size == 2
            val isExpired = if (isTwoPlayer) {
                false // 1v1 online matches NEVER expire silently after 8 minutes!
            } else {
                age >= MAX_MATCH_AGE_MS
            }
            if (!isExpired && data.roomCode.isNotBlank()) {
                data
            } else {
                clearOngoingMatch(context)
                null
            }
        } catch (_: Exception) {
            clearOngoingMatch(context)
            null
        }
    }

    /**
     * Atomically clears active ongoing match on match completion, explicit surrender, or exit.
     */
    fun clearOngoingMatch(context: Context? = null): Boolean {
        return try {
            getPrefs(context).edit().remove(KEY_MATCH_DATA).commit()
        } catch (_: Exception) {
            false
        }
    }
}
