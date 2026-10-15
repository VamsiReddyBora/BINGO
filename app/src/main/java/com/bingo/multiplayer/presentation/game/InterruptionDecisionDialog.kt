package com.bingo.multiplayer.presentation.game

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.InterruptionPromptState
import kotlinx.coroutines.delay

internal object MatchDecisionDialogSpec {
    val properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
}

/** One fixed deadline per persisted outage. Dismissal never sends a pause/resume intent. */
@Composable
internal fun InterruptionDecisionDialog(
    prompt: InterruptionPromptState,
    opponentName: String,
    opponentUnreachable: Boolean,
    connectionStatus: String,
    pauseEnabled: Boolean,
    claimEligible: Boolean,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onClaim: () -> Unit
) {
    val continueAction by rememberUpdatedState(onContinue)
    var remaining by remember(prompt.episodeId) { mutableIntStateOf(prompt.remainingSeconds(System.currentTimeMillis())) }
    var confirmingClaim by remember(prompt.episodeId) { mutableStateOf(false) }
    LaunchedEffect(prompt.episodeId, prompt.deadlineMillis, prompt.decided) {
        if (prompt.episodeId.isBlank() || prompt.decided) return@LaunchedEffect
        while (true) {
            remaining = prompt.remainingSeconds(System.currentTimeMillis())
            if (remaining == 0) { continueAction(); break }
            delay(100L)
        }
    }
    if (prompt.episodeId.isBlank() || prompt.decided || remaining == 0) return
    val tokens = BingoTheme.colors
    fun beforeDeadline(action: () -> Unit) {
        if (prompt.remainingSeconds(System.currentTimeMillis()) == 0) continueAction() else action()
    }
    AlertDialog(
        onDismissRequest = {},
        properties = MatchDecisionDialogSpec.properties,
        containerColor = tokens.surface,
        shape = RoundedCornerShape(20.dp),
        title = { Text(if (confirmingClaim && claimEligible) "Claim Victory?" else if (opponentUnreachable) "$opponentName disconnected" else connectionStatus.ifBlank { "Checking connection…" }, color = tokens.textPrimary) },
        text = {
            Column {
                Text(if (confirmingClaim && claimEligible)
                    "There have been ${prompt.episodeCount} interruptions and $opponentName is still unreachable. Claim victory?"
                    else "Warning ${prompt.episodeCount}. Choose what happens next.", color = tokens.textSecondary)
                Spacer(Modifier.height(12.dp))
                Text("Continue countdown automatically in $remaining seconds.", color = tokens.textSecondary, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmingClaim && claimEligible) {
                    Button(onClick = { beforeDeadline(onClaim) }, modifier = Modifier.fillMaxWidth()) { Text("Confirm claim") }
                    TextButton(onClick = { confirmingClaim = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to choices") }
                } else {
                    Button(onClick = { beforeDeadline(onPause) }, enabled = pauseEnabled, modifier = Modifier.fillMaxWidth()) { Text("Pause game") }
                    OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue countdown") }
                    if (claimEligible) Button(onClick = { beforeDeadline { confirmingClaim = true } }, modifier = Modifier.fillMaxWidth()) { Text("Claim victory") }
                }
            }
        }
    )
}
