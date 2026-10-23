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
import com.bingo.multiplayer.domain.engine.ReliableMatchSnapshot
import com.bingo.multiplayer.domain.engine.InterruptionPromptState
import com.bingo.multiplayer.domain.engine.MatchSystemMessages

@Keep
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
    val systemMessages: List<InGameChatMessage> = emptyList(),
    val interruptionPrompt: InterruptionPromptState = InterruptionPromptState(),
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val is1v1Match: Boolean = false,
    val accountUid: String = "",
    val protocolVersion: Int = 1,
    val controllerEpoch: Long = 0L,
    val matchRevision: Long = 0L,
    val isPaused: Boolean = false,
    val remainingTurnMillis: Long? = null,
    val pauseRemainingSeconds: Int? = null,
    val pauseDeadlineTimestamp: Long? = null,
    val pauseControlRevision: Int = 0,
    val versionedPauseControl: Boolean = false,
    val pauseControlTimestamp: Long = 0L,
    val multiplayerSnapshotCheckpoint: MultiplayerSnapshotCheckpoint = MultiplayerSnapshotCheckpoint(),
    val pauseOwnerId: String? = null,
    val eliminatedPlayerIds: List<String> = emptyList(),
    val committedEpisodeCount: Int = 0,
    val currentEpisodeId: String? = null,
    val isEpisodeOpen: Boolean = false,
    val peerAvailabilityState: String = "RESPONSIVE",
    val reliableSnapshot: ReliableMatchSnapshot? = null,
    val reliablePending: Boolean = false,
    // Local frozen display checkpoint, never a second authority or accepted shared revision.
    val hostWaitSnapshot: ReliableMatchSnapshot? = null,
    val hostHoldRemainingSeconds: Int? = null,
    val runnerPlayerIds: List<String> = emptyList(),
    val isMatchFinished: Boolean = false,
    val winnerPlayerId: String = "",
    val winReason: String = "",
    val turnClock: MultiplayerTurnClock? = null,
    val lastPlayerActivityAt: Long = startedAt,
    val isExpired: Boolean = false,
    val simplifiedRulesVersion: Int = 0,
    val presenceState: MultiplayerPresenceState = MultiplayerPresenceState(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val clockOffsetMillis: Long = 0L,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val clockOffsetsByPlayer: Map<String, Long> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val pendingStartEpoch: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val lobbySessionId: String = ""
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
    val accountUid: String = "",
    val runnerPlayerIds: List<String> = emptyList()
)

@Serializable
data class PendingMatchExit(val accountUid: String, val roomCode: String, val packet: RoomMessagePacket)

/**
 * Persists active online room match metadata so a player who temporarily exited,
 * switched apps, or experienced a connection drop can effortlessly rejoin the same
 * in-progress match from Home screen with complete state consistency.
 */
object OngoingMatchStore {
    private const val PREFS_NAME = "bingo_ongoing_match"
    private const val KEY_MATCH_DATA = "active_match_data"
    private const val KEY_TOMBSTONE_PREFIX = "match_tombstone_"
    private const val KEY_PENDING_EXIT_PREFIX = "pending_match_exit_"
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

    private fun getTombstoneKey(matchSeed: Long, accountUid: String? = null): String =
        "$KEY_TOMBSTONE_PREFIX${accountUid?.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()}$matchSeed"

    /**
     * Atomically and durably saves match checkpoint using commit().
     * Owner-scoped keys isolate each player's ongoing match without global mirror overwrites.
     */
    @Synchronized
    fun saveOngoingMatch(
        context: Context?,
        matchData: OngoingMatchData
    ): Boolean {
        return try {
            if (matchData.accountUid.isBlank()) return false
            val serialized = json.encodeToString(matchData)
            val key = getMatchKey(matchData.accountUid)
            val editor = getPrefs(context).edit().putString(key, serialized)
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun saveRecoveredMatch(context: Context?, data: OngoingMatchData): Boolean {
        val current = getOngoingMatch(context, data.accountUid) ?: return false
        if (current.matchSeed != data.matchSeed || current.roomCode != data.roomCode || current.startedAt != data.startedAt) return false
        return saveOngoingMatch(context, data)
    }

    /** Compare the active account/match before every asynchronous controller write.
     * A cancelled old session cannot overwrite a rematch or resurrect a dismissed checkpoint. */
    @Synchronized
    fun saveReliableCheckpoint(context: Context?, data: OngoingMatchData): Boolean {
        val snapshot = data.reliableSnapshot ?: return false
        if (data.accountUid.isBlank() || !snapshot.valid()) return false
        val stored = getPrefs(context).getString(getMatchKey(data.accountUid), null) ?: return false
        val current = try { json.decodeFromString<OngoingMatchData>(stored) } catch (_: Exception) { return false }
        if (current.accountUid != data.accountUid || current.matchSeed != data.matchSeed) return false
        val previous = current.reliableSnapshot
        if (previous != null && !current.reliablePending) {
            if (snapshot.sequence < previous.sequence || snapshot.revision < previous.revision) return false
            if (previous.final != null && snapshot.final != previous.final) return false
        }
        val scoped = if (data.accountUid != snapshot.hostId) data.copy(
            committedEpisodeCount = current.committedEpisodeCount, currentEpisodeId = current.currentEpisodeId,
            isEpisodeOpen = current.isEpisodeOpen, peerAvailabilityState = current.peerAvailabilityState,
            hostWaitSnapshot = current.hostWaitSnapshot
        ) else data
        return saveOngoingMatch(context, scoped.copy(
            systemMessages = MatchSystemMessages.merge(current.systemMessages, data.systemMessages),
            interruptionPrompt = InterruptionPromptState.merge(current.interruptionPrompt, data.interruptionPrompt)))
    }

    @Synchronized
    fun saveHostWait(context: Context?, accountUid: String, seed: Long, snapshot: ReliableMatchSnapshot?): Boolean {
        if (accountUid.isBlank()) return false
        val current = getOngoingMatch(context, accountUid) ?: return false
        if (current.accountUid != accountUid || current.matchSeed != seed) return false
        if (snapshot != null && (!snapshot.valid() || snapshot.seed != seed || snapshot.roomCode != current.roomCode ||
                    accountUid !in snapshot.order || accountUid == snapshot.hostId)) return false
        return saveOngoingMatch(context, current.copy(hostWaitSnapshot = snapshot))
    }

    @Synchronized
    fun savePresentation(context: Context?, accountUid: String, seed: Long,
        notice: InGameChatMessage? = null, prompt: InterruptionPromptState? = null): Boolean {
        val current = getOngoingMatch(context, accountUid) ?: return false
        if (current.matchSeed != seed || current.accountUid != accountUid) return false
        val notices = if (notice == null) current.systemMessages else MatchSystemMessages.merge(current.systemMessages, listOf(notice))
        return saveOngoingMatch(context, current.copy(systemMessages = notices,
            interruptionPrompt = if (prompt == null) current.interruptionPrompt else InterruptionPromptState.merge(current.interruptionPrompt, prompt)))
    }

    /**
     * Updates ongoing match state with incremental turn, board, pause, and heartbeat data.
     */
    @Synchronized
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
        pauseRemainingSeconds: Int? = null,
        pauseDeadlineTimestamp: Long? = null,
        pauseControlRevision: Int? = null,
        versionedPauseControl: Boolean? = null,
        pauseControlTimestamp: Long? = null,
        multiplayerSnapshotCheckpoint: MultiplayerSnapshotCheckpoint? = null,
        pauseOwnerId: String? = null,
        eliminatedPlayerIds: List<String>? = null,
        committedEpisodeCount: Int? = null,
        currentEpisodeId: String? = null,
        isEpisodeOpen: Boolean? = null,
        peerAvailabilityState: String? = null,
        matchRevision: Long? = null,
        clearNullableEpisodeFields: Boolean = false,
        expectedMatchSeed: Long? = null,
        accountUid: String? = null,
        hostHoldRemainingSeconds: Int? = null,
        turnClock: MultiplayerTurnClock? = null,
        lastPlayerActivityAt: Long? = null,
        isMatchFinished: Boolean? = null,
        winnerPlayerId: String? = null,
        winReason: String? = null,
        runnerPlayerIds: List<String>? = null
    ): Boolean {
        if (accountUid.isNullOrBlank()) return false
        return try {
            val current = getOngoingMatch(context, accountUid) ?: return false
            if (expectedMatchSeed != null && current.matchSeed != expectedMatchSeed) return false
            val effectivePaused = isPaused ?: current.isPaused
            val nowMs = System.currentTimeMillis()
            val effectiveDeadline = if (effectivePaused) {
                pauseDeadlineTimestamp ?: if (pauseRemainingSeconds != null) (nowMs + (pauseRemainingSeconds * 1000L)) else current.pauseDeadlineTimestamp
            } else null
            val updated = current.copy(
                playerBoard = playerBoard ?: current.playerBoard,
                opponentBoard = opponentBoard ?: current.opponentBoard,
                allPlayerBoards = allPlayerBoards ?: current.allPlayerBoards,
                pickedNumbers = pickedNumbers ?: current.pickedNumbers,
                pickedByPlayers = pickedByPlayers ?: current.pickedByPlayers,
                turnIds = turnIds ?: current.turnIds,
                turnNumber = turnNumber ?: current.turnNumber,
                currentTurnPlayerId = currentTurnPlayerId ?: current.currentTurnPlayerId,
                chatMessages = chatMessages ?: current.chatMessages,
                isPaused = effectivePaused,
                remainingTurnMillis = remainingTurnMillis ?: current.remainingTurnMillis,
                pauseRemainingSeconds = if (effectivePaused && effectiveDeadline != null) (((effectiveDeadline - nowMs + 999L) / 1000L).toInt().coerceAtLeast(0)) else (pauseRemainingSeconds ?: current.pauseRemainingSeconds),
                pauseDeadlineTimestamp = effectiveDeadline,
                pauseControlRevision = pauseControlRevision ?: current.pauseControlRevision,
                versionedPauseControl = versionedPauseControl ?: current.versionedPauseControl,
                pauseControlTimestamp = pauseControlTimestamp ?: current.pauseControlTimestamp,
                multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint ?: current.multiplayerSnapshotCheckpoint,
                pauseOwnerId = pauseOwnerId ?: current.pauseOwnerId,
                eliminatedPlayerIds = eliminatedPlayerIds ?: current.eliminatedPlayerIds,
                committedEpisodeCount = committedEpisodeCount ?: current.committedEpisodeCount,
                currentEpisodeId = if (clearNullableEpisodeFields) currentEpisodeId else (currentEpisodeId ?: current.currentEpisodeId),
                isEpisodeOpen = isEpisodeOpen ?: current.isEpisodeOpen,
                peerAvailabilityState = peerAvailabilityState ?: current.peerAvailabilityState,
                matchRevision = matchRevision ?: if (current.reliableSnapshot == null) current.matchRevision + 1 else current.matchRevision,
                hostHoldRemainingSeconds = hostHoldRemainingSeconds ?: current.hostHoldRemainingSeconds,
                turnClock = turnClock ?: current.turnClock,
                lastPlayerActivityAt = maxOf(current.lastPlayerActivityAt, lastPlayerActivityAt ?: 0L),
                isMatchFinished = isMatchFinished ?: current.isMatchFinished,
                winnerPlayerId = winnerPlayerId ?: current.winnerPlayerId,
                winReason = winReason ?: current.winReason,
                runnerPlayerIds = runnerPlayerIds ?: current.runnerPlayerIds,
                lastUpdatedAt = nowMs
            )
            saveOngoingMatch(context, updated)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves the active ongoing match.
     * 1v1 online matches bypass the 8-minute TTL so interrupted/recovering games never expire silently.
     * When accountUid is specified, ensures cross-account isolation (HB13, F005).
     */
    @Synchronized
    fun getOngoingMatch(context: Context? = null, accountUid: String? = null): OngoingMatchData? {
        if (accountUid.isNullOrBlank()) return null
        val prefs = getPrefs(context)
        val scopedKey = getMatchKey(accountUid)
        var serialized = prefs.getString(scopedKey, null)

        // Legacy migration: if scoped record is absent, check global legacy record
        if (serialized == null && scopedKey != KEY_MATCH_DATA) {
            val legacy = prefs.getString(KEY_MATCH_DATA, null)
            if (legacy != null) {
                try {
                    val candidate = json.decodeFromString<OngoingMatchData>(legacy)
                    val uidClean = accountUid.trim().lowercase()
                    if (candidate.accountUid.isNotBlank()) {
                        // Explicit owner exists! ONLY migrate if it matches the signed-in account!
                        if (candidate.accountUid.equals(uidClean, ignoreCase = true)) {
                            if (!prefs.edit().putString(scopedKey, legacy).remove(KEY_MATCH_DATA).commit()) return null
                            serialized = legacy
                        }
                        // Explicit owner mismatch: reject migration even if uidClean is in participants!
                    } // Ownerless legacy records are preserved, never adopted by a participant.
                } catch (_: Exception) {}
            }
        }

        if (serialized == null) return null

        return try {
            val data = json.decodeFromString<OngoingMatchData>(serialized)
            if (!accountUid.isNullOrBlank() && data.accountUid.isNotBlank() && !data.accountUid.equals(accountUid.trim(), ignoreCase = true)) {
                return null
            }

            // Check if this match has already concluded via a durable result tombstone (HB11, F002)
            val tombstone = getMatchResultTombstone(context, data.matchSeed, data.accountUid)
            if (tombstone != null && data.reliableSnapshot == null && data.simplifiedRulesVersion == 0) {
                clearOngoingMatch(context, accountUid)
                return null
            }

            val age = System.currentTimeMillis() - data.lastUpdatedAt
            // A local old checkpoint cannot establish remote inactivity. Version-3 recovery
            // validates the retained peer state before deciding expiry, including a local final result.
            val isExpired = data.isExpired || (data.simplifiedRulesVersion == 0 &&
                if (data.is1v1Match) age >= (24 * 60 * 60 * 1000L) else age >= MAX_MATCH_AGE_MS)
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
    @Synchronized
    fun clearOngoingMatch(context: Context? = null, accountUid: String? = null): Boolean {
        return try {
            val prefs = getPrefs(context)
            val editor = prefs.edit()
            if (!accountUid.isNullOrBlank()) {
                editor.remove(getMatchKey(accountUid))
            } else {
                editor.remove(KEY_MATCH_DATA)
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    /** A delayed lookup for an older game may never clear its replacement. */
    @Synchronized
    fun clearMatchIfCurrent(context: Context?, accountUid: String, roomCode: String, seed: Long): Boolean {
        val current = getOngoingMatch(context, accountUid) ?: return false
        if (current.matchSeed != seed || current.roomCode != roomCode) return false
        return clearOngoingMatch(context, accountUid)
    }

    /**
     * Durably stores a LOCAL immutable accepted result tombstone keyed by match seed (HB11, F002).
     * Rejects conflicting writes and allows idempotent duplicate writes.
     */
    @Synchronized
    fun recordMatchResultTombstone(
        context: Context?,
        roomCode: String = "",
        matchSeed: Long,
        winnerUid: String,
        winReason: String,
        episodeCount: Int = 0,
        accountUid: String? = null,
        runnerPlayerIds: List<String> = emptyList()
    ): Boolean {
        if (matchSeed == 0L) return false
        val existing = getMatchResultTombstone(context, matchSeed, accountUid)
        if (existing != null) {
            // Idempotent: identical result returns true
            if (existing.winnerUid == winnerUid && existing.winReason == winReason) {
                return true
            }
            // Conflicting second write rejected!
            return false
        }
        return try {
            val tombstone = MatchResultTombstone(
                matchSeed = matchSeed,
                roomCode = roomCode,
                winnerUid = winnerUid,
                winReason = winReason,
                episodeCount = episodeCount,
                accountUid = accountUid ?: "",
                runnerPlayerIds = runnerPlayerIds
            )
            val serialized = json.encodeToString(tombstone)
            val committed = getPrefs(context).edit().putString(getTombstoneKey(matchSeed, accountUid), serialized).commit()
            if (!committed) {
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }


    /**
     * Retrieves an immutable final result tombstone if one exists for this match seed (HB11, F002).
     */
    fun getMatchResultTombstone(context: Context?, matchSeed: Long, accountUid: String? = null): MatchResultTombstone? {
        if (matchSeed == 0L) return null
        val local = try {
            val prefs = getPrefs(context)
            val serialized = prefs.getString(getTombstoneKey(matchSeed, accountUid), null)
                ?: prefs.getString(getTombstoneKey(matchSeed), null)
            if (serialized != null) json.decodeFromString<MatchResultTombstone>(serialized) else null
        } catch (_: Exception) {
            null
        }
        if (local != null && (accountUid.isNullOrBlank() || local.accountUid.isBlank() || local.accountUid == accountUid)) {
            return local
        }
        return null
    }

    /** A delayed cancellation may clear only its own provisional checkpoint. */
    @Synchronized
    fun clearScheduledStart(context: Context?, uid: String, seed: Long, epoch: String): Boolean {
        if (uid.isBlank() || epoch.isBlank()) return false
        val prefs = try {
            getPrefs(context)
        } catch (_: Exception) {
            return false
        }
        val raw = prefs.getString(getMatchKey(uid), null) ?: return false
        val current = runCatching { json.decodeFromString<OngoingMatchData>(raw) }.getOrNull() ?: return false
        if (current.accountUid != uid || current.matchSeed != seed || current.pendingStartEpoch != epoch) return false
        return prefs.edit().remove(getMatchKey(uid)).commit()
    }

    @Synchronized
    fun enqueueExit(context: Context?, exit: PendingMatchExit): Boolean {
        if (exit.accountUid.isBlank() || exit.roomCode.isBlank() || exit.packet.seed == 0L || exit.packet.type != "SURRENDER") return false
        val saved = getPrefs(context).edit().putString("$KEY_PENDING_EXIT_PREFIX${exit.accountUid}:${exit.packet.seed}",
            json.encodeToString(exit)).commit()
        // Only durable, intentional surrender retires the writer. Network loss never does.
        if (saved) RoomMetadataApi.retireLocalWriter(exit)
        return saved
    }

    @Synchronized
    fun pendingExits(context: Context?, accountUid: String): List<PendingMatchExit> = getPrefs(context).all
        .filterKeys { it.startsWith("$KEY_PENDING_EXIT_PREFIX$accountUid:") }.values.mapNotNull {
            runCatching { json.decodeFromString<PendingMatchExit>(it as String) }.getOrNull()
        }

    @Synchronized
    fun acknowledgeExit(context: Context?, accountUid: String, seed: Long): Boolean =
        getPrefs(context).edit().remove("$KEY_PENDING_EXIT_PREFIX$accountUid:$seed").commit()
}
