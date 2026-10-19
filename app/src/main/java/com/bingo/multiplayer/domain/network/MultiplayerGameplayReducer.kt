package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.MatchPick
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player

data class MultiplayerGameplayContext(
    val seed: Long,
    val localId: String,
    val hostId: String,
    val participants: List<Player>,
    val turnOrder: List<Player>,
    val excludedPlayerIds: Set<String> = emptySet(),
    val allowPeerTimeouts: Boolean = false,
    val isActive: Boolean = true,
    val requiresHostResultConfirmation: Boolean = false,
    val strictIdentity: Boolean = false
) {
    fun participantId(id: String): String? = participants.firstOrNull { it.id == id }?.id
        ?: if (strictIdentity) null else participants.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it.id, id) }?.id

    fun nextPlayer(picker: String, surrendered: Set<String> = emptySet()): String {
        if (!strictIdentity) return LobbyLifecycleEngine.calculateNextTurnPlayerId(
            participants, excludedPlayerIds + surrendered, picker, localId, seed, turnOrder)
        val ids = (turnOrder.ifEmpty { participants }).map { it.id }.distinct()
        val excluded = excludedPlayerIds + surrendered
        val start = ids.indexOf(picker)
        return (1..ids.size).map { ids[(start + it).mod(ids.size)] }
            .firstOrNull { it !in excluded } ?: localId
    }
}

data class MultiplayerGameplayState(
    val picks: List<MatchPick>,
    val playerBoard: Board,
    val opponentBoard: Board,
    val allPlayerBoards: Map<String, Board>,
    val turnNumber: Int,
    val currentPlayerId: String,
    val remainingTurnMillis: Long,
    val isPaused: Boolean = false,
    val isSuspended: Boolean = false,
    val isGameOver: Boolean = false,
    val disconnectedPlayerIds: Set<String> = emptySet(),
    val missedTurns: Map<String, Int> = emptyMap(),
    val surrenderedPlayerIds: Set<String> = emptySet(),
    val turnClock: MultiplayerTurnClock? = null
)

data class MultiplayerGameplayDecision(
    val state: MultiplayerGameplayState,
    val accepted: Boolean,
    val reason: String = "",
    val winnerId: String = ""
)

/** Packet validation and immutable state application used by the real screen callback. */
object MultiplayerGameplayReducer {
    val gameplayTypes = setOf("PICK_NUMBER", "TURN_TIMEOUT", "BINGO_CLAIMED", "GAME_OVER", "SURRENDER")
    val reliable1v1BlockedTypes = gameplayTypes + setOf("GAME_SYNC", "GAME_SYNC_ACK", "GAME_PAUSED", "GAME_RESUMED",
        "CLAIM_VICTORY", "REJOIN_GAME", "SYNC_REQUEST", "CHAT_MESSAGE", "PLAYER_DISCONNECTED", "LEAVE", "HOST_LEFT")

    fun sender(packet: RoomMessagePacket): String = packet.senderPlayerId.ifBlank { packet.playerId }

    fun belongsToMatch(packet: RoomMessagePacket, context: MultiplayerGameplayContext): Boolean =
        context.isActive && context.seed != 0L && packet.seed == context.seed &&
            (packet.matchId.isBlank() || packet.matchId == context.seed.toString()) &&
            context.participantId(sender(packet)) != null && context.participantId(packet.playerId) != null

    /** Capture match identity at construction; preserve it on a delayed resend. */
    fun prepareOutgoing(packet: RoomMessagePacket, seed: Long, senderId: String): RoomMessagePacket {
        if (packet.type !in MultiplayerLifecyclePacketGate.scopedTypes) return packet
        val originalSeed = packet.seed.takeIf { it != 0L } ?: seed
        return packet.copy(seed = originalSeed, matchId = packet.matchId.ifBlank { originalSeed.toString() },
            senderPlayerId = senderId,
            closedTurnNumber = if (packet.closedTurnNumber > 0) packet.closedTurnNumber
                else if (packet.type in setOf("PICK_NUMBER", "TURN_TIMEOUT", "BINGO_CLAIMED", "GAME_OVER"))
                    (packet.turnNumber - 1).coerceAtLeast(0) else 0)
    }

    fun timeoutPacket(seed: Long, senderId: String, skippedId: String, closedTurn: Int, nextId: String): RoomMessagePacket =
        prepareOutgoing(RoomMessagePacket(type = "TURN_TIMEOUT", playerId = skippedId,
            turnNumber = closedTurn + 1, currentTurnPlayerId = nextId, closedTurnNumber = closedTurn,
            seed = seed), seed, senderId)

    private fun reject(state: MultiplayerGameplayState, reason: String) = MultiplayerGameplayDecision(state, false, reason)

    fun pick(packet: RoomMessagePacket, context: MultiplayerGameplayContext, state: MultiplayerGameplayState): MultiplayerGameplayDecision {
        if (packet.type != "PICK_NUMBER" || !belongsToMatch(packet, context)) return reject(state, "Wrong match or participant")
        if (state.isGameOver || state.isPaused || state.isSuspended) return reject(state, "Gameplay frozen")
        val picker = context.participantId(packet.playerId) ?: return reject(state, "Unknown picker")
        val origin = context.participantId(sender(packet))
        val closedTurn = packet.closedTurnNumber.takeIf { it > 0 } ?: (packet.turnNumber - 1)
        if (closedTurn != state.turnNumber || packet.turnNumber != state.turnNumber + 1 ||
            !LobbyLifecycleEngine.isPlayerIdMatch(picker, state.currentPlayerId) ||
            picker in context.excludedPlayerIds || picker in state.surrenderedPlayerIds ||
            (origin != picker && (context.strictIdentity || origin != context.hostId))) return reject(state, "Closed or unexpected turn")
        if (packet.number !in 1..(state.playerBoard.size * state.playerBoard.size) || state.picks.any { it.number == packet.number || it.turn == closedTurn })
            return reject(state, "Duplicate or invalid selection")
        if (state.turnClock?.frozenRemainingMillis != null)
            return reject(state, "Turn paused")
        // The history carried by a peer is diagnostic. Only this validated open-turn
        // move can add a mark; missed earlier turns are recovered by a host snapshot.
        val move = MatchPick(packet.number, picker, closedTurn)
        val engine = BingoEngine()
        val next = context.nextPlayer(picker, state.surrenderedPlayerIds)
        return MultiplayerGameplayDecision(state.copy(
            picks = state.picks + move,
            playerBoard = engine.markCell(state.playerBoard, move.number, picker, LobbyLifecycleEngine.isPlayerIdMatch(picker, context.localId), closedTurn),
            opponentBoard = engine.markCell(state.opponentBoard, move.number, picker, !LobbyLifecycleEngine.isPlayerIdMatch(picker, context.localId), closedTurn),
            allPlayerBoards = state.allPlayerBoards.mapValues { (id, board) ->
                engine.markCell(board, move.number, picker, LobbyLifecycleEngine.isPlayerIdMatch(picker, id), closedTurn)
            },
            turnNumber = closedTurn + 1, currentPlayerId = next, remainingTurnMillis = 30_000L
        ), true)
    }

    fun timeout(packet: RoomMessagePacket, context: MultiplayerGameplayContext, state: MultiplayerGameplayState): MultiplayerGameplayDecision {
        if (packet.type != "TURN_TIMEOUT" || !belongsToMatch(packet, context)) return reject(state, "Wrong match or participant")
        if (state.isGameOver || state.isPaused || state.isSuspended) return reject(state, "Gameplay frozen")
        val skipped = context.participantId(packet.playerId) ?: return reject(state, "Unknown skipped player")
        val origin = context.participantId(sender(packet))
        val closedTurn = packet.closedTurnNumber.takeIf { it > 0 } ?: (packet.turnNumber - 1)
        if (closedTurn != state.turnNumber || packet.turnNumber != closedTurn + 1 ||
            !LobbyLifecycleEngine.isPlayerIdMatch(skipped, state.currentPlayerId)) return reject(state, "Duplicate, obsolete or missing turn")
        if (!context.allowPeerTimeouts && origin != context.hostId && origin != skipped) return reject(state, "Not a turn controller or self pass")
        state.turnClock?.let { clock ->
            val closedClock = packet.closedTurnClock
            if (closedClock != null) {
                if (closedClock != clock || clock.frozenRemainingMillis != null || packet.timestamp < clock.deadlineAt ||
                    System.currentTimeMillis() < clock.deadlineAt) return reject(state, "Obsolete or premature timeout")
            } else {
                if (clock.frozenRemainingMillis != null) return reject(state, "Turn paused")
            }
        }
        val next = context.nextPlayer(skipped, state.surrenderedPlayerIds)
        if (packet.currentTurnPlayerId.isNotBlank() && packet.currentTurnPlayerId != next) return reject(state, "Inconsistent next player")
        // Not choosing a number is not evidence of a network failure or a penalty.
        return MultiplayerGameplayDecision(state.copy(turnNumber = closedTurn + 1, currentPlayerId = next,
            remainingTurnMillis = 30_000L), true)
    }

    fun surrender(packet: RoomMessagePacket, context: MultiplayerGameplayContext, state: MultiplayerGameplayState): MultiplayerGameplayDecision {
        if (packet.type != "SURRENDER" || !belongsToMatch(packet, context) || state.isGameOver) return reject(state, "Wrong match, participant or completed game")
        val player = context.participantId(packet.playerId) ?: return reject(state, "Unknown participant")
        if (context.participantId(sender(packet)) != player) return reject(state, "Cannot surrender another player")
        if (player in state.surrenderedPlayerIds) return reject(state, "Already surrendered")
        val departed = state.surrenderedPlayerIds + player
        val closesTurn = LobbyLifecycleEngine.isPlayerIdMatch(player, state.currentPlayerId)
        return MultiplayerGameplayDecision(state.copy(surrenderedPlayerIds = departed,
            turnNumber = if (closesTurn) state.turnNumber + 1 else state.turnNumber,
            currentPlayerId = if (closesTurn) context.nextPlayer(player, departed) else state.currentPlayerId,
            remainingTurnMillis = if (closesTurn) 30_000L else state.remainingTurnMillis), true)
    }

    fun result(packet: RoomMessagePacket, context: MultiplayerGameplayContext, state: MultiplayerGameplayState): MultiplayerGameplayDecision {
        if (packet.type !in setOf("BINGO_CLAIMED", "GAME_OVER") || !belongsToMatch(packet, context) || state.isGameOver)
            return reject(state, "Wrong match, participant or completed game")
        val origin = context.participantId(sender(packet))
        if (!MultiplayerResultAuthority.mayFinalize(context, packet)) return reject(state, "Waiting for host result confirmation")
        val claimant = context.participantId(packet.playerId)
        val rawWinner = packet.winnerPlayerId.ifBlank { packet.playerId }
        val winner = context.participantId(rawWinner) ?: if (LobbyLifecycleEngine.isPlayerIdMatch(rawWinner, context.localId)) context.localId else return reject(state, "Unknown winner")
        val isHost = (origin == context.hostId)
        if ((origin != claimant && !isHost) ||
            winner in context.excludedPlayerIds || winner in state.surrenderedPlayerIds)
            return reject(state, "Invalid result authority")
        var candidate = state
        if (packet.number > 0 && state.picks.none { it.number == packet.number }) {
            val index = packet.pickedHistory.indexOf(packet.number)
            val picker = packet.pickedByHistory.getOrNull(index)?.takeIf { it.isNotBlank() } ?: packet.playerId
            val lastTurn = packet.turnIds.getOrNull(index) ?: packet.closedTurnNumber.takeIf { it > 0 } ?: (packet.turnNumber - 1)
            val move = pick(packet.copy(type = "PICK_NUMBER", playerId = picker, senderPlayerId = sender(packet),
                closedTurnNumber = lastTurn), context, state)
            if (!move.accepted) return reject(state, "Winning pick is unaccepted; request host snapshot")
            candidate = move.state
        }
        val boards = candidate.allPlayerBoards.toMutableMap().apply {
            putIfAbsent(context.localId, candidate.playerBoard)
            if (context.participants.size == 2) context.participants.firstOrNull { it.id != context.localId }?.let {
                putIfAbsent(it.id, candidate.opponentBoard)
            }
        }
        val caller = candidate.picks.lastOrNull()?.playerId.orEmpty()
        if (!MultiplayerResultRules.verifies(winner, boards, context.participants.map { it.id },
                context.excludedPlayerIds + candidate.surrenderedPlayerIds, caller, context.turnOrder.map { it.id }))
            return reject(state, "No accepted Bingo or permanent-exit result for winner")
        return MultiplayerGameplayDecision(candidate.copy(isGameOver = true), true, winnerId = winner)
    }
}
