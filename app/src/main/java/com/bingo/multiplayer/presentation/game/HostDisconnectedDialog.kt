package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme

internal object HostDisconnectedDialogSpec {
    const val title = "🔴 Host disconnected"
    const val message = "Your board and remaining time are preserved."
    val properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
}

/** Visibility remains owned by synchronized match state; this card has no dismissal action. */
@Composable
internal fun HostDisconnectedDialog(hostName: String, avatarUrl: String?, username: String?) {
    val tokens = BingoTheme.colors
    AlertDialog(
        onDismissRequest = {},
        properties = HostDisconnectedDialogSpec.properties,
        containerColor = tokens.surface,
        shape = RoundedCornerShape(28.dp),
        icon = { MatchConnectionAvatar(hostName, avatarUrl, username, MatchNoticeTone.DISCONNECTED) },
        title = { Text(HostDisconnectedDialogSpec.title, color = MatchNoticeTone.DISCONNECTED.color(tokens.isDark),
            fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                MatchStatusChip("$hostName · Host", MatchNoticeTone.NEUTRAL)
                Text("Waiting for $hostName to return.\nYour match is on hold.", color = tokens.textPrimary,
                    fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                Text(HostDisconnectedDialogSpec.message, color = tokens.textSecondary,
                    fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                HostWaitingIndicator()
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun HostWaitingIndicator() {
    val color = MatchNoticeTone.INFO.color(BingoTheme.colors.isDark)
    val transition = rememberInfiniteTransition(label = "waitingForHost")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val alpha = transition.animateFloat(initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650, delayMillis = index * 160), RepeatMode.Reverse),
                label = "hostWaitingDot$index")
            Box(Modifier.size(5.dp).graphicsLayer { this.alpha = alpha.value }.background(color, CircleShape))
        }
        Spacer(Modifier.width(4.dp))
        Text("Waiting for host", color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
