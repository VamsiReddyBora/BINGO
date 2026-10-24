package com.bingo.multiplayer.presentation.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bingo.multiplayer.domain.engine.MatchSystemMessages
import com.bingo.multiplayer.domain.network.OngoingMatchData

/** Read-only result viewer. Never connects, advertises a room, sends a move, or starts a timer. */
@Composable
internal fun SavedMatchResultScreen(result: OngoingMatchData, viewerId: String, onClose: () -> Unit) {
    val board = result.playerBoard ?: result.allPlayerBoards[viewerId]
    if (board == null) {
        BackHandler(onBack = onClose)
        Column {
            Text("This match has finished. Its saved board is unavailable.")
            Button(onClick = onClose) { Text("Back to Home") }
        }
        return
    }
    val me = result.participants.firstOrNull { it.id == viewerId }
    val opponent = result.participants.firstOrNull { it.id != viewerId }
    GameScreen(
        board = board,
        opponentBoard = result.opponentBoard,
        isMyTurn = false,
        turnTimeRemaining = 0,
        recentPick = null,
        opponentName = opponent?.displayName ?: "Opponent",
        isGameOver = true,
        didPlayerWin = result.winnerPlayerId == viewerId,
        isRunner = viewerId in result.runnerPlayerIds,
        winnerPlayerId = result.winnerPlayerId,
        onCellPicked = {},
        onPlayAgain = {},
        onBackToMenu = onClose,
        isHost = result.isHost,
        isLobbyAvailable = false,
        lobbyStatusMessage = "Saved match result",
        myPlayerId = viewerId,
        myDisplayName = me?.displayName,
        myUsername = me?.username,
        myAvatarUrl = me?.avatarUrl,
        opponentUsername = opponent?.username,
        opponentAvatarUrl = opponent?.avatarUrl,
        useAcceptedChatHistory = true,
        initialChatMessages = MatchSystemMessages.merge(result.chatMessages, result.systemMessages),
        pickedNumbersHistory = result.pickedNumbers,
        matchSeed = result.matchSeed,
        players = result.participants,
        allPlayerBoards = result.allPlayerBoards,
        currentTurnPlayerId = result.currentTurnPlayerId
    )
}
