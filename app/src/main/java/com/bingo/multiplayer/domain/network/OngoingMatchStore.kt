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

@Keep
@Serializable
data class MatchResultTombstone(
    val matchSeed: Long,
    val roomCode: String,
    val winnerUid: String,
    val winReason: String,
    val episodeCount: Int,
    val recordedAt: Long = System.currentTimeMillis(),
    val accountUid: String = ""
)

/**
 * Persists active online room match metadata so a player who temporarily exited,
 * switched apps, or experienced a connection drop can effortlessly rejoin the same
 * in-progress match from Home screen with complete state consistency.
 */
object OngoingMatchStore {
    private const val PREFS_NAME = "bingo_ongoing_match"
    private const val KEY_MATCH_DATA = "active_match_data"
    private const val KEY_TOMBSTONE_PREFIX = "match_tombstone_"
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

    private fun getMatchKey(accountUid: String?): String {
        val clean = accountUid?.trim()?.lowercase() ?: ""
        return if (clean.isNotBlank()) "active_match_data_$clean" else KEY_MATCH_DATA
    }

    private fun getTombstoneKey(matchSeed: Long): String = "$KEY_TOMBSTONE_PREFIX$matchSeed"

    /**
     * Atomically and durably saves match checkpoint using commit().
     */
    fun saveOngoingMatch(
        context: Context?,
        matchData: OngoingMatchData
    ): Boolean {
        return try {
            val serialized = json.encodeToString(matchData)
            val key = getMatchKey(matchData.accountUid)
            val editor = getPrefs(context).edit().putString(key, serialized)
            if (key != KEY_MATCH_DATA) {
                editor.putString(KEY_MATCH_DATA, serialized)
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Updates ongoing match state with incremental turn, board, pause, and heartbeat data.
     */
    fun updateMatchGameState(
        context: Context? = null,
        playerBoard: Board? = null,
        opponentBoard: Board? = null,
        allPlayerBoards: Map<String, Board>? = null,
        pickedNumbers: List<Int>? = null,
        pickedByPlayers: List<String>? = null,
        turnNumber: Int? = null,
        currentTurnPlayerId: String? = null,
        chatMessages: List<InGameChatMessage>? = null,
        turnIds: List<Int>? = null,
        isPaused: Boolean? = null,
        remainingTurnMillis: Long? = null,
        pauseOwnerId: String? = null,
        committedEpisodeCount: Int? = null,
        currentEpisodeId: String? = null,
        isEpisodeOpen: Boolean? = null,
        peerAvailabilityState: String? = null,
        matchRevision: Long? = null,
        clearNullableEpisodeFields: Boolean = false,
        accountUid: String? = null
    ): Boolean {
        return try {
            val current = getOngoingMatch(context, accountUid) ?: return false
            val updated = current.copy(
                playerBoard = playerBoard ?: current.playerBoard,
                opponentBoard = opponentBoard ?: current.opponentBoard,
                allPlayerBoards = allPlayerBoards ?: current.allPlayerBoards,
                pickedNumbers = pickedNumbers ?: current.pickedNumbers,
                pickedByPlayers = pickedByPlayers ?: current.pickedByPlayers,
                turnIds = if (turnIds != null && turnIds.isNotEmpty()) turnIds else current.turnIds,
                turnNumber = turnNumber ?: current.turnNumber,
                currentTurnPlayerId = currentTurnPlayerId ?: current.currentTurnPlayerId,
                chatMessages = if (chatMessages != null && chatMessages.isNotEmpty()) chatMessages else current.chatMessages,
                isPaused = isPaused ?: current.isPaused,
                remainingTurnMillis = remainingTurnMillis ?: current.remainingTurnMillis,
                pauseOwnerId = pauseOwnerId ?: current.pauseOwnerId,
                committedEpisodeCount = committedEpisodeCount ?: current.committedEpisodeCount,
                currentEpisodeId = if (clearNullableEpisodeFields) currentEpisodeId else (currentEpisodeId ?: current.currentEpisodeId),
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
     * When accountUid is specified, ensures cross-account isolation (HB13).
     */
    fun getOngoingMatch(context: Context? = null, accountUid: String? = null): OngoingMatchData? {
        val prefs = getPrefs(context)
        val scopedKey = getMatchKey(accountUid)
        var serialized = prefs.getString(scopedKey, null)

        // Legacy migration: if scoped record is absent, check global legacy record
        if (serialized == null && scopedKey != KEY_MATCH_DATA) {
            val legacy = prefs.getString(KEY_MATCH_DATA, null)
            if (legacy != null) {
                try {
                    val candidate = json.decodeFromString<OngoingMatchData>(legacy)
                    val uidClean = accountUid?.trim()?.lowercase() ?: ""
                    if (candidate.accountUid.equals(uidClean, ignoreCase = true) ||
                        candidate.participants.any { it.id.equals(uidClean, ignoreCase = true) }
                    ) {
                        val migrated = candidate.copy(accountUid = uidClean)
                        serialized = json.encodeToString(migrated)
                        prefs.edit().putString(scopedKey, serialized).commit()
                    }
                } catch (_: Exception) {}
            }
        }

        if (serialized == null) return null

        return try {
            val data = json.decodeFromString<OngoingMatchData>(serialized)
            if (!accountUid.isNullOrBlank() && data.accountUid.isNotBlank() && !data.accountUid.equals(accountUid.trim(), ignoreCase = true)) {
                return null
            }

            // Check if this match has already concluded via a durable result tombstone (HB11)
            val tombstone = getMatchResultTombstone(context, data.matchSeed)
            if (tombstone != null) {
                clearOngoingMatch(context, accountUid)
                return null
            }

            val age = System.currentTimeMillis() - data.lastUpdatedAt
            val isTwoPlayer = data.is1v1Match || data.participants.size == 2
            val isExpired = if (isTwoPlayer) {
                false
            } else {
                age >= MAX_MATCH_AGE_MS
            }
            if (!isExpired && data.roomCode.isNotBlank()) {
                data
            } else {
                clearOngoingMatch(context, accountUid)
                null
            }
        } catch (_: Exception) {
            clearOngoingMatch(context, accountUid)
            null
        }
    }

    /**
     * Atomically clears active ongoing match on match completion, explicit surrender, or exit.
     */
    fun clearOngoingMatch(context: Context? = null, accountUid: String? = null): Boolean {
        return try {
            val prefs = getPrefs(context)
            val editor = prefs.edit().remove(KEY_MATCH_DATA)
            if (!accountUid.isNullOrBlank()) {
                editor.remove(getMatchKey(accountUid))
            } else {
                val legacy = prefs.getString(KEY_MATCH_DATA, null)
                if (legacy != null) {
                    try {
                        val parsed = json.decodeFromString<OngoingMatchData>(legacy)
                        if (parsed.accountUid.isNotBlank()) {
                            editor.remove(getMatchKey(parsed.accountUid))
                        }
                    } catch (_: Exception) {}
                }
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Durably stores an immutable final result tombstone keyed by match seed (HB11).
     */
    fun recordMatchResultTombstone(
        context: Context?,
        roomCode: String,
        matchSeed: Long,
        winnerUid: String,
        winReason: String,
        episodeCount: Int,
        accountUid: String? = null
    ): Boolean {
        if (matchSeed == 0L) return false
        return try {
            val tombstone = MatchResultTombstone(
                matchSeed = matchSeed,
                roomCode = roomCode,
                winnerUid = winnerUid,
                winReason = winReason,
                episodeCount = episodeCount,
                accountUid = accountUid ?: ""
            )
            val serialized = json.encodeToString(tombstone)
            getPrefs(context).edit().putString(getTombstoneKey(matchSeed), serialized).commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves an immutable final result tombstone if one exists for this match seed (HB11).
     */
    fun getMatchResultTombstone(context: Context?, matchSeed: Long): MatchResultTombstone? {
        if (matchSeed == 0L) return null
        return try {
            val serialized = getPrefs(context).getString(getTombstoneKey(matchSeed), null) ?: return null
            json.decodeFromString<MatchResultTombstone>(serialized)
        } catch (_: Exception) {
            null
        }
    }
}
