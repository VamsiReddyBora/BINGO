package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.presentation.common.PlayerAvatar

internal enum class MatchNoticeTone { NEUTRAL, INFO, SUCCESS, WARNING, DISCONNECTED }
internal data class MatchNotice(val text: String, val tone: MatchNoticeTone)

/** Display-only translation. Stored text, message IDs and match events remain unchanged. */
internal object MatchStatusPresentation {
    fun notice(raw: String): MatchNotice {
        val text = raw.trim()
        return when {
            text == "Synchronizing match…" -> MatchNotice("🔄 Updating your match…", MatchNoticeTone.INFO)
            text == "Match synchronized." -> MatchNotice("✅ Let’s play!", MatchNoticeTone.SUCCESS)
            text == "Checking connection…" -> MatchNotice("🟠 Checking your connection…", MatchNoticeTone.WARNING)
            text == "Could not save or synchronize match. Retrying…" -> MatchNotice("🟠 Couldn’t update the match. Retrying…", MatchNoticeTone.WARNING)
            text == "Waiting for the match host to reconnect…" -> MatchNotice("⏳ Waiting for the host to return…", MatchNoticeTone.WARNING)
            text.startsWith("Synchronizing with ") -> MatchNotice("🔄 Updating the match with ${text.removePrefix("Synchronizing with ")}", MatchNoticeTone.INFO)
            text.endsWith(" reconnected; synchronizing…") -> MatchNotice("🔄 ${text.removeSuffix(" reconnected; synchronizing…")} is back. Updating the match…", MatchNoticeTone.INFO)
            text.endsWith(" reconnected") -> MatchNotice("🟢 ${text.removeSuffix(" reconnected")} is back!", MatchNoticeTone.SUCCESS)
            text.endsWith(" online") -> MatchNotice("🟢 ${text.removeSuffix(" online")} is online", MatchNoticeTone.SUCCESS)
            text.contains(" disconnected (warning ") && text.endsWith(")") -> MatchNotice(
                "🔴 ${text.substringBeforeLast(" disconnected (warning ")} disconnected · Warning ${text.substringAfterLast(" disconnected (warning ").dropLast(1)}", MatchNoticeTone.DISCONNECTED)
            text.endsWith(" disconnected") -> MatchNotice("🔴 $text", MatchNoticeTone.DISCONNECTED)
            text.endsWith(" paused the match.") -> MatchNotice("⏸️ ${text.dropLast(1)}", MatchNoticeTone.WARNING)
            text.endsWith(" resumed the match.") -> MatchNotice("▶️ ${text.dropLast(1)}", MatchNoticeTone.SUCCESS)
            else -> MatchNotice(raw, MatchNoticeTone.NEUTRAL)
        }
    }
}

internal fun MatchNoticeTone.color(dark: Boolean): Color = when (this) {
    MatchNoticeTone.INFO -> if (dark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
    MatchNoticeTone.SUCCESS -> if (dark) Color(0xFF86EFAC) else Color(0xFF166534)
    MatchNoticeTone.WARNING -> if (dark) Color(0xFFFCD34D) else Color(0xFF92400E)
    MatchNoticeTone.DISCONNECTED -> if (dark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    MatchNoticeTone.NEUTRAL -> if (dark) Color(0xFFCBD5E1) else Color(0xFF475569)
}

@Composable
internal fun MatchStatusChip(text: String, tone: MatchNoticeTone, modifier: Modifier = Modifier) {
    val tokens = BingoTheme.colors
    val color by animateColorAsState(tone.color(tokens.isDark), tween(220), label = "matchStatusColor")
    Surface(modifier = modifier, color = color.copy(alpha = if (tokens.isDark) 0.13f else 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f)), shape = RoundedCornerShape(12.dp)) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
internal fun MatchConnectionAvatar(name: String, avatarUrl: String?, username: String?, tone: MatchNoticeTone) {
    val color by animateColorAsState(tone.color(BingoTheme.colors.isDark), tween(220), label = "connectionAvatarRing")
    PlayerAvatar(avatarPathOrUri = avatarUrl, displayName = name, username = username, size = 72.dp,
        borderColor = color, borderWidth = 3.dp)
}
