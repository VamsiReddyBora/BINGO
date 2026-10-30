package com.bingo.multiplayer.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.AnimationFeatureFlags
import com.bingo.multiplayer.core.designsystem.AnimationPreferences
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences

@Composable
internal fun ResultAnimationSettingsContent() {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val available = AnimationFeatureFlags.WINNING_EMOJI_ANIMATION_ENABLED
    val enabled = available && AnimationPreferences.winningEmojiAnimationEnabled
    val accent = if (tokens.isDark && tokens.accentBrand == Color.White)
        ThemePreferences.getPalette(ThemePreferences.accentColorId.value).primaryDark else tokens.accentBrand
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Winning emoji animation", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                Text(if (available) "Celebrate wins and react to losses." else "Not available in this version.",
                    fontSize = 11.sp, color = tokens.textMuted)
            }
            Switch(checked = enabled, enabled = available, onCheckedChange = {
                AnimationPreferences.setWinningEmojiAnimationEnabled(context, it)
            }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent,
                checkedBorderColor = Color.Transparent, uncheckedThumbColor = tokens.textMuted,
                uncheckedTrackColor = tokens.cellNeutralBorder.copy(alpha = .25f),
                uncheckedBorderColor = tokens.cellNeutralBorder.copy(alpha = .35f)))
        }
    }
}
