package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Board

data class MultiplayerVerifiedResult(val winnerId: String, val runnerIds: List<String>, val reason: String)

/** Availability is never eligibility. The same outcome calculation is used for moves and recovery. */
object MultiplayerResultRules {
    fun evaluate(boards: Map<String, Board>, participantIds: List<String>, departed: Set<String>,
                 callerId: String, turnOrder: List<String> = participantIds): MultiplayerVerifiedResult? {
        val eligible = participantIds.distinct().filter { it !in departed }
        if (eligible.size == 1 && participantIds.size >= 2 && departed.isNotEmpty())
            return MultiplayerVerifiedResult(eligible.single(), emptyList(), "Opponent Surrendered")
        val completed = eligible.filter { boards[it]?.isBingo == true }
        if (completed.isEmpty()) return null
        val caller = completed.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it, callerId) }
        // Preserve the existing line-count / turn-order policy for the as-yet unspecified non-caller tie.
        val winner = caller ?: completed.sortedWith(compareByDescending<String> { boards[it]?.completedLinesCount ?: 0 }
            .thenBy { LobbyLifecycleEngine.calculateTurnDistance(turnOrder, callerId, it) }.thenBy { it }).first()
        return MultiplayerVerifiedResult(winner, completed.filter { it != winner },
            if (caller != null && completed.size > 1) "Turn Pick" else "Bingo")
    }

    fun verifies(winner: String, boards: Map<String, Board>, participants: List<String>, departed: Set<String>, caller: String,
                 order: List<String> = participants): Boolean =
        winner.isNotBlank() && evaluate(boards, participants, departed, caller, order)?.winnerId == winner
}
