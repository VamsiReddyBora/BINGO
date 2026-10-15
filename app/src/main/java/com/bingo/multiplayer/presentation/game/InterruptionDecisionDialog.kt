package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.InterruptionPromptState
import kotlinx.coroutines.delay

internal object MatchDecisionDialogSpec {
    val properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
}

/** One fixed deadline per persisted outage. The progress bar only visualizes that same deadline. */
@Composable
internal fun InterruptionDecisionDialog(
    prompt: InterruptionPromptState,
    opponentName: String,
    opponentAvatarUrl: String?,
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
    var progress by remember(prompt.episodeId) { mutableFloatStateOf(((prompt.deadlineMillis - System.currentTimeMillis()) / 15_000f).coerceIn(0f, 1f)) }
    var confirmingClaim by remember(prompt.episodeId) { mutableStateOf(false) }
    LaunchedEffect(prompt.episodeId, prompt.deadlineMillis, prompt.decided) {
        if (prompt.episodeId.isBlank() || prompt.decided) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            remaining = prompt.remainingSeconds(now)
            progress = ((prompt.deadlineMillis - now) / 15_000f).coerceIn(0f, 1f)
            if (remaining == 0) { continueAction(); break }
            delay(100L)
        }
    }
    if (prompt.episodeId.isBlank() || prompt.decided || remaining == 0) return
    val tokens = BingoTheme.colors
    val status = if (opponentUnreachable) MatchNotice("🔴 $opponentName disconnected", MatchNoticeTone.DISCONNECTED)
        else MatchStatusPresentation.notice(connectionStatus.ifBlank { "Checking connection…" })
    val titleColor by animateColorAsState(status.tone.color(tokens.isDark), tween(220), label = "disconnectTitleColor")
    val barProgress by animateFloatAsState(progress, tween(100, easing = LinearEasing), label = "disconnectDecisionProgress")
    val warningColor = MatchNoticeTone.WARNING.color(tokens.isDark)
    val dangerColor = MatchNoticeTone.DISCONNECTED.color(tokens.isDark)
    fun beforeDeadline(action: () -> Unit) {
        if (prompt.remainingSeconds(System.currentTimeMillis()) == 0) continueAction() else action()
    }
    AlertDialog(
        onDismissRequest = {},
        properties = MatchDecisionDialogSpec.properties,
        containerColor = tokens.surface,
        shape = RoundedCornerShape(28.dp),
        icon = { MatchConnectionAvatar(opponentName, opponentAvatarUrl, status.tone) },
        title = { Text(if (confirmingClaim && claimEligible) "🏆 Claim victory?" else status.text,
            color = titleColor, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MatchStatusChip("Warning ${prompt.episodeCount}", MatchNoticeTone.WARNING)
                Text(if (confirmingClaim && claimEligible)
                    "There have been ${prompt.episodeCount} interruptions and $opponentName is still disconnected. Claim victory now?"
                    else "What would you like to do?", color = tokens.textSecondary,
                    fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⏳ Auto-continue in ${remaining}s", color = warningColor,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    LinearProgressIndicator(progress = barProgress, color = warningColor,
                        trackColor = warningColor.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)))
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (confirmingClaim && claimEligible) {
                    Button(onClick = { beforeDeadline(onClaim) }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) { Text("🏆 Confirm claim", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { confirmingClaim = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to choices", color = tokens.textSecondary) }
                } else {
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg, contentColor = tokens.primaryButtonText),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) { Text("▶️ Continue countdown", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { beforeDeadline(onPause) }, enabled = pauseEnabled, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.textPrimary),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) { Text("⏸️ Pause match") }
                    if (claimEligible) OutlinedButton(onClick = { beforeDeadline { confirmingClaim = true } }, modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, dangerColor.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = dangerColor),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) { Text("🏆 Claim victory", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    )
}
