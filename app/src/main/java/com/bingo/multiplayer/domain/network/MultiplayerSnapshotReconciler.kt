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
    val surrenderedPlayerIds: Set<String> = emptySet()
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
        allPlayerBoards: Map<String, Board>
    ): MultiplayerSnapshotApplication? {
        if (packet.type != "GAME_SYNC" || packet.seed != matchSeed || matchSeed == 0L ||
            hostId.isBlank() || packet.playerId != hostId || packet.turnNumber < checkpoint.turnNumber) return null
        if (packet.sequence > 0L) {
            if (packet.sequence <= checkpoint.revision) return null
        } else if (checkpoint.revision > 0L || packet.timestamp <= checkpoint.timestamp) return null

        val accepted = packet.pickedHistory.mapIndexed { index, number ->
            MatchPick(number, packet.pickedByHistory.getOrNull(index)?.takeIf { it.isNotBlank() } ?: hostId,
                packet.turnIds.getOrNull(index) ?: (index + 1))
        }
        if (accepted.any { it.number !in 1..(playerBoard.size * playerBoard.size) || it.turn < 1 || it.turn >= packet.turnNumber } ||
            accepted.map { it.number }.distinct().size != accepted.size ||
            accepted.map { it.turn }.zipWithNext().any { (a, b) -> b <= a } ||
            accepted.take(checkpoint.picks.size) != checkpoint.picks) return null

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
        val winnerBoard = allPlayerBoards[packet.winnerPlayerId]
            ?: playerBoard.takeIf { packet.winnerPlayerId == localId }
        val confirmedWinner = packet.winnerPlayerId.takeIf { id ->
            id.isNotBlank() && id !in checkpoint.surrenderedPlayerIds && id !in packet.surrenderedPlayerIds &&
                id !in packet.eliminatedPlayerIds && winnerBoard != null &&
                (rebuild(winnerBoard, accepted) { it == id }.isBingo ||
                    (packet.isMatchFinished && packet.winReason in setOf("Opponent Surrendered", "Opponent Disconnected", "Opponent Left", "Host Disconnected")))
        }.orEmpty()
        // A snapshot made before an in-flight pick is not a rejection. Only a closed turn
        // can reject it. Previously accepted host picks can never be rolled back.
        val pending = if (confirmedWinner.isNotBlank()) emptyList() else localPicks.filter { local ->
            accepted.none { it.number == local.number } && local.turn >= packet.turnNumber
        }
        val picks = accepted + pending
        return MultiplayerSnapshotApplication(
            checkpoint = checkpoint.copy(revision = packet.sequence, timestamp = maxOf(checkpoint.timestamp, packet.timestamp),
                turnNumber = packet.turnNumber, picks = accepted,
                surrenderedPlayerIds = checkpoint.surrenderedPlayerIds + packet.surrenderedPlayerIds),
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
