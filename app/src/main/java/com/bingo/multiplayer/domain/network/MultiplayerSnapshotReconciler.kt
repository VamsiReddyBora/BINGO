package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.MatchPick
import com.bingo.multiplayer.domain.model.Board
import kotlinx.serialization.Serializable

/** Accepted host state, separate from the guest's still-unconfirmed local moves. */
@Serializable
data class MultiplayerSnapshotCheckpoint(
    val revision: Long = 0L,
    val timestamp: Long = 0L,
    val turnNumber: Int = 1,
    val picks: List<MatchPick> = emptyList(),
    // Explicit departure receipts, independent of transient silence or missed turns.
    val surrenderedPlayerIds: Set<String> = emptySet(),
    val senderRevisions: Map<String, Long> = emptyMap(),
    val senderTimestamps: Map<String, Long> = emptyMap()
)

data class MultiplayerSnapshotApplication(
    val checkpoint: MultiplayerSnapshotCheckpoint,
    val picks: List<MatchPick>,
    val hasPendingPicks: Boolean,
    val playerBoard: Board,
    val opponentBoard: Board,
    val allPlayerBoards: Map<String, Board>,
    val confirmedWinnerId: String = ""
)

/** Validates the whole snapshot before returning any replacement presentation state. */
object MultiplayerSnapshotReconciler {
    fun reconcile(
        packet: RoomMessagePacket,
        matchSeed: Long,
        hostId: String,
        localId: String,
        checkpoint: MultiplayerSnapshotCheckpoint,
        localPicks: List<MatchPick>,
        playerBoard: Board,
        opponentBoard: Board,
        allPlayerBoards: Map<String, Board>,
        allowPeerSnapshots: Boolean = false,
        participantIds: List<String> = allPlayerBoards.keys.toList(),
        turnOrder: List<String> = participantIds
    ): MultiplayerSnapshotApplication? {
        if (packet.type != "GAME_SYNC" || packet.seed != matchSeed || matchSeed == 0L ||
            packet.turnNumber < checkpoint.turnNumber ||
            (if (allowPeerSnapshots) packet.playerId !in participantIds else hostId.isBlank() || packet.playerId != hostId)) return null
        if (allowPeerSnapshots && (packet.currentTurnPlayerId !in participantIds || packet.turnNumber < 1 ||
                packet.pickedByHistory.size != packet.pickedHistory.size || packet.turnIds.size != packet.pickedHistory.size ||
                packet.turnIds.any { it >= packet.turnNumber } ||
                (packet.turnClock != null && (!packet.turnClock.valid() || packet.turnClock.turnNumber != packet.turnNumber ||
                    packet.turnClock.playerId != packet.currentTurnPlayerId)))) return null
        if (allowPeerSnapshots) {
            val origin = MultiplayerGameplayReducer.sender(packet)
            if (origin !in participantIds || origin != packet.playerId) return null
            if (packet.sequence > 0L) {
                if (packet.sequence <= (checkpoint.senderRevisions[origin] ?: 0L)) return null
            } else if (packet.timestamp <= (checkpoint.senderTimestamps[origin] ?: 0L)) return null
        } else if (packet.sequence > 0L) {
            if (packet.sequence <= checkpoint.revision) return null
        } else if (checkpoint.revision > 0L || packet.timestamp <= checkpoint.timestamp) return null

        val accepted = packet.pickedHistory.mapIndexed { index, number ->
            MatchPick(number, packet.pickedByHistory.getOrNull(index)?.takeIf { it.isNotBlank() } ?: hostId,
                packet.turnIds.getOrNull(index) ?: (index + 1))
        }
        if (accepted.any { it.number !in 1..(playerBoard.size * playerBoard.size) || it.turn < 1 || (!packet.isMatchFinished && it.turn >= packet.turnNumber) } ||
            (allowPeerSnapshots && accepted.any { it.playerId !in participantIds }) ||
            accepted.map { it.number }.distinct().size != accepted.size ||
            accepted.map { it.turn }.zipWithNext().any { (a, b) -> b <= a } ||
            accepted.take(checkpoint.picks.size) != checkpoint.picks) return null

        if (packet.surrenderedPlayerIds.any { it !in participantIds }) return null
        val departed = checkpoint.surrenderedPlayerIds + packet.surrenderedPlayerIds
        if (packet.currentTurnPlayerId in departed && packet.winnerPlayerId.isBlank()) return null
        val engine = BingoEngine()
        fun rebuild(board: Board, history: List<MatchPick>, isOwnPick: (String) -> Boolean): Board {
            var result = board
            board.cells.filter { it.isMarked }.forEach { result = engine.unmarkCell(result, it.number) }
            history.forEach { pick ->
                result = engine.markCell(result, pick.number, pick.playerId, isOwnPick(pick.playerId), pick.turn)
            }
            return result
        }
        val acceptedBoards = allPlayerBoards.mapValues { (id, board) ->
            rebuild(board, accepted) { LobbyLifecycleEngine.isPlayerIdMatch(it, id) }
        }.toMutableMap().apply { putIfAbsent(localId, rebuild(playerBoard, accepted) { it == localId }) }
        val result = MultiplayerResultRules.evaluate(acceptedBoards, participantIds, departed,
            accepted.lastOrNull()?.playerId.orEmpty(), turnOrder)
        val confirmedWinner = packet.winnerPlayerId.takeIf { id ->
            id.isNotBlank() && result?.winnerId == id &&
                (result.reason != "Opponent Surrendered" || (packet.isMatchFinished && packet.winReason.contains("surrender", ignoreCase = true)))
        }.orEmpty()
        if (allowPeerSnapshots && packet.isMatchFinished && confirmedWinner.isBlank()) return null
        // A snapshot made before an in-flight pick is not a rejection. Only a closed turn
        // can reject it. Previously accepted host picks can never be rolled back.
        val pending = if (confirmedWinner.isNotBlank()) emptyList() else localPicks.filter { local ->
            accepted.none { it.number == local.number } && local.turn >= packet.turnNumber
        }
        val picks = accepted + pending
        return MultiplayerSnapshotApplication(
            checkpoint = checkpoint.copy(revision = packet.sequence, timestamp = maxOf(checkpoint.timestamp, packet.timestamp),
                turnNumber = packet.turnNumber, picks = accepted,
                surrenderedPlayerIds = departed,
                senderRevisions = checkpoint.senderRevisions + (packet.playerId to packet.sequence),
                senderTimestamps = checkpoint.senderTimestamps + (packet.playerId to packet.timestamp)),
            picks = picks,
            hasPendingPicks = pending.isNotEmpty(),
            playerBoard = rebuild(playerBoard, picks) { LobbyLifecycleEngine.isPlayerIdMatch(it, localId) },
            opponentBoard = rebuild(opponentBoard, picks) { !LobbyLifecycleEngine.isPlayerIdMatch(it, localId) },
            allPlayerBoards = allPlayerBoards.mapValues { (id, board) ->
                rebuild(board, picks) { LobbyLifecycleEngine.isPlayerIdMatch(it, id) }
            },
            confirmedWinnerId = confirmedWinner
        )
    }

    /** A direct host move is also accepted progress, even before the next snapshot. */
    fun recordHostProgress(
        checkpoint: MultiplayerSnapshotCheckpoint,
        packet: RoomMessagePacket
    ): MultiplayerSnapshotCheckpoint {
        if (packet.turnNumber < checkpoint.turnNumber) return checkpoint
        val picks = packet.pickedHistory.mapIndexed { i, number ->
            MatchPick(number, packet.pickedByHistory.getOrNull(i)?.takeIf { it.isNotBlank() } ?: packet.playerId,
                packet.turnIds.getOrNull(i) ?: (i + 1))
        }
        val closedTurn = packet.closedTurnNumber.takeIf { it > 0 } ?: (packet.turnNumber - 1)
        val confirmsDirectPick = packet.number > 0 && picks.lastOrNull() == MatchPick(packet.number, packet.playerId, closedTurn)
        val validHistory = picks.all { it.number > 0 && it.turn in 1 until packet.turnNumber } &&
            picks.map { it.number }.distinct().size == picks.size &&
            picks.map { it.turn }.zipWithNext().all { (a, b) -> b > a }
        // A valid direct move closes this turn even when an older compact sender did
        // not include a full history. Leave accepted history intact until host sync.
        if (!confirmsDirectPick || !validHistory || picks.take(checkpoint.picks.size) != checkpoint.picks)
            return checkpoint.copy(turnNumber = packet.turnNumber)
        return checkpoint.copy(turnNumber = packet.turnNumber, picks = picks)
    }
}
