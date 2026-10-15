package com.bingo.multiplayer.domain.engine

import androidx.annotation.Keep
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.Serializable

@Keep @Serializable
data class MatchPick(val number: Int, val playerId: String, val turn: Int)
@Keep @Serializable
data class MatchChat(val id: String, val playerId: String, val name: String, val text: String, val timestamp: Long) {
    fun localId(): Long = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))).long
}
@Keep @Serializable
data class MatchEpisode(val count: Int = 0, val id: String = "", val open: Boolean = false, val unreachable: Boolean = false)
@Keep @Serializable
data class MatchFinal(val id: String, val winnerId: String, val reason: String, val episodeId: String = "")
@Keep @Serializable
data class ReliableMatchSnapshot(
    val version: Int = 2,
    val matchId: String,
    val roomCode: String,
    val seed: Long,
    val hostId: String,
    val ownerId: String,
    val participants: List<Player>,
    val boards: Map<String, Board>,
    val order: List<String>,
    val turn: Int = 1,
    val currentPlayerId: String,
    val picks: List<MatchPick> = emptyList(),
    val chat: List<MatchChat> = emptyList(),
    val episodes: Map<String, MatchEpisode> = emptyMap(),
    val revision: Long = 0,
    val sequence: Long = 0,
    val paused: Boolean = false,
    val pauseOwnerId: String = "",
    val remainingMillis: Long = 30_000,
    val elapsedPlayMillis: Long = 0,
    val recoveryId: String = "",
    val final: MatchFinal? = null,
    val acceptedActions: List<String> = emptyList()
) {
    fun valid(): Boolean {
        val ids = participants.map { it.id }
        if (version != 2 || seed == 0L || matchId != seed.toString() || roomCode.isBlank() || ownerId.isBlank()) return false
        if (ids.size != 2 || ids.distinct().size != 2 || ids.any { it.isBlank() } || hostId !in ids) return false
        if (participants.count { it.isHost } != 1 || participants.first { it.isHost }.id != hostId) return false
        if (boards.keys != ids.toSet() || order.toSet() != ids.toSet() || order.size != 2 || currentPlayerId !in ids) return false
        if (turn < 1 || revision < 0 || sequence < 0 || elapsedPlayMillis < 0 || remainingMillis !in 0L..30_000L) return false
        val size = boards.values.first().size
        if (size !in 3..10 || boards.values.any { it.size != size || it.cells.map { c -> c.number }.toSet() != (1..size*size).toSet() }) return false
        if (picks.map { it.number }.distinct().size != picks.size || picks.zipWithNext().any { it.first.turn >= it.second.turn }) return false
        if (picks.any { it.playerId !in ids || it.turn !in 1 until turn || it.number !in 1..size*size }) return false
        if (episodes.any { it.key !in ids || it.value.count < 0 || (it.value.open && it.value.id.isBlank()) }) return false
        if (final != null && (final.id.isBlank() || final.winnerId !in ids || final.reason !in setOf("BINGO", "TURN_PICK", "SURRENDER", "UNREACHABLE"))) return false
        if (acceptedActions.any { it.isBlank() } || acceptedActions.distinct().size != acceptedActions.size || acceptedActions.size > 512) return false
        if (chat.map { it.id }.distinct().size != chat.size || chat.any { it.id.isBlank() || it.playerId !in ids || it.text.isBlank() || it.text.length > 1000 }) return false
        if (final?.reason == "BINGO" && boards[final.winnerId]?.isBingo != true) return false
        if (final?.reason == "TURN_PICK" && (boards.values.any { !it.isBingo } || picks.lastOrNull()?.playerId != final.winnerId)) return false
        if (final?.reason == "UNREACHABLE") {
            val loser = ids.first { it != final.winnerId }
            val episode = episodes[loser] ?: return false
            if (!episode.open || !episode.unreachable || episode.count < 3 || episode.id != final.episodeId) return false
        }

        // Verify marks from complete layouts and actual accepted turn IDs, not pick indices.
        val engine = BingoEngine()
        return boards.all { (uid, board) ->
            var replay = board.copy(cells = board.cells.map { it.copy(markState = CellMarkState.Unmarked, isPartOfCompletedLine = false, isRecentPick = false) }, completedLines = emptySet())
            picks.forEach { replay = engine.markCell(replay, it.number, it.playerId, it.playerId == uid, it.turn) }
            replay == board
        }
    }
}

@Keep @Serializable
data class MatchIntent(
    val id: String,
    val matchId: String,
    val actorId: String,
    val expectedRevision: Long,
    val turn: Int,
    val kind: String,
    val number: Int = 0,
    val text: String = "",
    val timestamp: Long = 0,
    val episodeId: String = ""
)

/** Single production owner. All callers, including the timer, use this same serialized path.
 * The commit port must persist pending state, confirm retained MQTT publication, then persist acceptance.
 * No takeover is inferred from heartbeat silence. A failed/ambiguous commit blocks further writes.
 */
class ReliableMatchCoordinator(
    initial: ReliableMatchSnapshot,
    private val localUid: String,
    private val ownerId: String,
    private val clock: Clock,
    private val commit: (ReliableMatchSnapshot) -> Boolean
) {
    var state: ReliableMatchSnapshot = initial
        private set
    var ready: Boolean = false
        private set
    private var deadline: Long? = null
    private var suspendedRemaining = initial.remainingMillis
    private var elapsedBase = initial.elapsedPlayMillis
    private var runningSince: Long? = null
    val isOwner: Boolean get() = localUid == state.hostId && ownerId == state.ownerId

    init { require(initial.valid() || initial.boards.isEmpty()) }

    @Synchronized fun resumeObservation() {
        if (!state.valid() || ready) return
        ready = true
        val running = !state.paused && state.final == null && state.recoveryId.isEmpty()
        deadline = if (running) clock.elapsedRealtime() + suspendedRemaining else null
        runningSince = if (running) clock.elapsedRealtime() else null
    }
    @Synchronized fun suspendObservation() {
        suspendedRemaining = remainingMillis()
        elapsedBase = elapsedMillis()
        runningSince = null
        deadline = null
        ready = false
    }
    @Synchronized fun remainingMillis(): Long =
        deadline?.let { (it - clock.elapsedRealtime()).coerceIn(0, 30_000) } ?: suspendedRemaining
    private fun elapsedMillis(): Long = elapsedBase + (runningSince?.let { (clock.elapsedRealtime() - it).coerceAtLeast(0) } ?: 0)
    @Synchronized fun sampled(): ReliableMatchSnapshot = state.copy(remainingMillis = remainingMillis(), elapsedPlayMillis = elapsedMillis())

    /** Fixed host only. Recovery is a system hold, independent of the user's manual pause. */
    @Synchronized fun holdForRecovery(id: String, frozen: ReliableMatchSnapshot? = null): Boolean {
        if (!isOwner || !state.valid() || id.isBlank() || state.final != null) return false
        suspendObservation()
        var next = sampled()
        if (frozen != null && samePosition(next, frozen)) {
            next = next.copy(remainingMillis = minOf(next.remainingMillis, frozen.remainingMillis),
                elapsedPlayMillis = maxOf(next.elapsedPlayMillis, frozen.elapsedPlayMillis))
        }
        next = next.copy(recoveryId = id, revision = state.revision + 1, sequence = state.sequence + 1)
        if (!commit(next)) return false
        state = next
        suspendedRemaining = next.remainingMillis
        elapsedBase = next.elapsedPlayMillis
        ready = true
        return true
    }

    @Synchronized fun resumeAfterRecovery(id: String): Boolean {
        if (!isOwner || !ready || state.recoveryId != id || id.isEmpty()) return false
        val next = sampled().copy(recoveryId = "", revision = state.revision + 1, sequence = state.sequence + 1)
        if (!commit(next)) { suspendObservation(); return false }
        state = next
        suspendedRemaining = next.remainingMillis
        elapsedBase = next.elapsedPlayMillis
        ready = false
        resumeObservation()
        return true
    }

    private fun samePosition(a: ReliableMatchSnapshot, b: ReliableMatchSnapshot): Boolean =
        b.valid() && a.matchId == b.matchId && a.roomCode == b.roomCode && a.ownerId == b.ownerId && a.hostId == b.hostId && a.order == b.order &&
            a.turn == b.turn && a.currentPlayerId == b.currentPlayerId && a.boards == b.boards &&
            a.picks == b.picks && a.paused == b.paused && a.pauseOwnerId == b.pauseOwnerId && b.final == null

    @Synchronized fun acceptRemote(snapshot: ReliableMatchSnapshot): Boolean {
        if (!snapshot.valid() || snapshot.matchId != state.matchId || snapshot.roomCode != state.roomCode || snapshot.hostId != state.hostId || snapshot.ownerId != state.ownerId) return false
        if (snapshot.participants.map { it.id }.toSet() != state.participants.map { it.id }.toSet()) return false
        if (snapshot.revision < state.revision || snapshot.sequence <= state.sequence) return false
        if (state.final != null && snapshot.final != state.final) return false
        state = snapshot
        suspendedRemaining = snapshot.remainingMillis
        elapsedBase = snapshot.elapsedPlayMillis
        val running = ready && !snapshot.paused && snapshot.final == null && snapshot.recoveryId.isEmpty()
        deadline = if (running) clock.elapsedRealtime() + snapshot.remainingMillis else null
        runningSince = if (running) clock.elapsedRealtime() else null
        return true
    }

    @Synchronized fun dispatch(intent: MatchIntent, healthy: Boolean = true): Boolean {
        if (!isOwner || !ready || !healthy || intent.matchId != state.matchId || intent.actorId !in state.order || intent.id.isBlank()) return false
        if (intent.id in state.acceptedActions) return true
        if (state.final != null || state.recoveryId.isNotEmpty() || intent.expectedRevision != state.revision || intent.turn != state.turn) return false
        val now = clock.elapsedRealtime()
        var next = sampled()
        val peer = state.order.first { it != intent.actorId }
        when (intent.kind) {
            "PICK" -> {
                if (state.paused || remainingMillis() == 0L || intent.actorId != state.currentPlayerId || intent.number !in 1..state.boards.values.first().maxNumber || state.picks.any { it.number == intent.number }) return false
                val boards = state.boards.mapValues { (uid, b) -> BingoEngine().markCell(b, intent.number, intent.actorId, uid == intent.actorId, state.turn) }
                val winners = boards.filterValues { it.isBingo }.keys
                val winner = if (winners.size == 2) intent.actorId else winners.firstOrNull()
                next = next.copy(boards = boards, picks = state.picks + MatchPick(intent.number, intent.actorId, state.turn), turn = state.turn + 1,
                    currentPlayerId = peer, remainingMillis = 30_000,
                    final = winner?.let { MatchFinal("${state.matchId}:${intent.id}", it, if (winners.size == 2) "TURN_PICK" else "BINGO") })
            }
            "TIMEOUT" -> {
                if (intent.actorId != localUid || state.paused || remainingMillis() != 0L) return false
                next = next.copy(turn = state.turn + 1, currentPlayerId = state.order.first { it != state.currentPlayerId }, remainingMillis = 30_000)
            }
            "PAUSE" -> {
                if (state.paused) return false
                next = next.copy(paused = true, pauseOwnerId = intent.actorId)
            }
            "RESUME" -> {
                if (!state.paused) return false
                next = next.copy(paused = false, pauseOwnerId = "")
            }
            "SURRENDER" -> next = next.copy(final = MatchFinal("${state.matchId}:${intent.id}", peer, "SURRENDER"))
            "CLAIM" -> {
                // Only the healthy active owner can establish/commit an absence claim on this broker.
                val e = state.episodes[peer] ?: return false
                if (intent.actorId != localUid || !e.open || !e.unreachable || e.count < 3 || e.id != intent.episodeId) return false
                next = next.copy(final = MatchFinal("${state.matchId}:${intent.id}", intent.actorId, "UNREACHABLE", e.id))
            }
            "CHAT" -> {
                if (intent.text.isBlank() || intent.text.length > 1000) return false
                val name = state.participants.first { it.id == intent.actorId }.displayName
                next = next.copy(chat = state.chat + MatchChat(intent.id, intent.actorId, name, intent.text, intent.timestamp))
            }
            else -> return false
        }
        next = next.copy(revision = state.revision + 1, sequence = state.sequence + 1, acceptedActions = (state.acceptedActions + intent.id).takeLast(512))
        if (!next.valid() || !commit(next)) { suspendObservation(); return false }
        val changedTurn = next.turn != state.turn
        state = next
        suspendedRemaining = next.remainingMillis
        elapsedBase = next.elapsedPlayMillis
        runningSince = if (next.paused || next.final != null) null else now
        deadline = if (next.paused || next.final != null) null else if (changedTurn || intent.kind == "RESUME") now + next.remainingMillis else deadline
        return true
    }

    @Synchronized fun updatePeerEpisode(peer: String, episode: MatchEpisode): Boolean {
        if (!isOwner || !ready || peer == localUid || peer !in state.order || state.final != null || state.recoveryId.isNotEmpty()) return false
        if (state.episodes[peer] == episode) return true
        val previous = state.episodes[peer]
        if (previous != null && episode.count < previous.count) return false
        val next = sampled().copy(episodes = state.episodes + (peer to episode), revision = state.revision + 1, sequence = state.sequence + 1)
        if (!next.valid() || !commit(next)) { suspendObservation(); return false }
        elapsedBase = elapsedMillis()
        state = next
        runningSince = if (deadline != null) clock.elapsedRealtime() else null
        return true
    }

    @Synchronized fun checkpoint(): Boolean {
        if (!isOwner || !ready) return false
        val next = sampled().copy(sequence = state.sequence + 1)
        if (!commit(next)) { suspendObservation(); return false }
        elapsedBase = elapsedMillis()
        state = next
        runningSince = if (deadline != null) clock.elapsedRealtime() else null
        return true
    }
}
