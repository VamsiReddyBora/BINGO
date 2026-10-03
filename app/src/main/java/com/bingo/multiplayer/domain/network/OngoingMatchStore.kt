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
    val turnNumber: Int = 1,
    val currentTurnPlayerId: String = "",
    val randomizedTurnOrder: List<Player> = emptyList(),
    val chatMessages: List<InGameChatMessage> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * Persists active online room match metadata so a player who temporarily exited
 * or experienced a connection drop can effortlessly rejoin the same in-progress match from Home screen.
 */
object OngoingMatchStore {
    private const val PREFS_NAME = "bingo_ongoing_match"
    private const val KEY_MATCH_DATA = "active_match_data"
    private const val MAX_MATCH_AGE_MS = 8 * 60 * 1000L // 8 minutes TTL for active match

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveOngoingMatch(
        context: Context,
        matchData: OngoingMatchData
    ) {
        try {
            val serialized = json.encodeToString(matchData)
            getPrefs(context).edit().putString(KEY_MATCH_DATA, serialized).apply()
        } catch (_: Exception) {}
    }

    fun updateMatchGameState(
        context: Context,
        playerBoard: Board,
        opponentBoard: Board,
        allPlayerBoards: Map<String, Board>,
        pickedNumbers: List<Int>,
        pickedByPlayers: List<String>,
        turnNumber: Int,
        currentTurnPlayerId: String,
        chatMessages: List<InGameChatMessage> = emptyList()
    ) {
        try {
            val current = getOngoingMatch(context) ?: return
            val updated = current.copy(
                playerBoard = playerBoard,
                opponentBoard = opponentBoard,
                allPlayerBoards = allPlayerBoards,
                pickedNumbers = pickedNumbers,
                pickedByPlayers = pickedByPlayers,
                turnNumber = turnNumber,
                currentTurnPlayerId = currentTurnPlayerId,
                chatMessages = if (chatMessages.isNotEmpty()) chatMessages else current.chatMessages,
                lastUpdatedAt = System.currentTimeMillis()
            )
            saveOngoingMatch(context, updated)
        } catch (_: Exception) {}
    }

    fun getOngoingMatch(context: Context): OngoingMatchData? {
        val serialized = getPrefs(context).getString(KEY_MATCH_DATA, null) ?: return null
        return try {
            val data = json.decodeFromString<OngoingMatchData>(serialized)
            val age = System.currentTimeMillis() - data.lastUpdatedAt
            if (age < MAX_MATCH_AGE_MS && data.roomCode.isNotBlank()) {
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

    fun clearOngoingMatch(context: Context) {
        getPrefs(context).edit().remove(KEY_MATCH_DATA).apply()
    }
}
