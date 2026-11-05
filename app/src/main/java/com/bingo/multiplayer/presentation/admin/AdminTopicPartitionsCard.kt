package com.bingo.multiplayer.presentation.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoColors

internal data class AdminTopicPartition(val icon: String, val name: String, val path: String, val count: Int)

internal fun adminTopicRows(partitions: List<AdminTopicPartition>, columns: Int): List<List<AdminTopicPartition>> =
    partitions.chunked(columns.coerceIn(1, 3))

internal fun adminTopicShare(count: Int, total: Int): Float =
    if (total > 0) (count.toFloat() / total).coerceIn(0f, 1f) else 0f

@Composable
internal fun AdminTopicPartitionsCard(
    partitions: List<AdminTopicPartition>,
    total: Int,
    adjustments: AdminDashboardLayout,
    tokens: BingoColors
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(adjustments.topicCardCornerDp.dp),
        color = adminUiColor(adjustments.topicCardColorHex, tokens.surface),
        border = BorderStroke(1.dp, adminUiColor(adjustments.topicBorderColorHex, tokens.surfaceBorder))
    ) {
        Column(
            modifier = Modifier.padding(adjustments.topicCardPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(adjustments.topicRowGapDp.dp)
        ) {
            Column {
                Text("TOPIC PARTITIONS ($total TOTAL)", fontSize = adjustments.topicHeaderFontSizeSp.sp,
                    fontWeight = FontWeight.Bold, color = tokens.textMuted, letterSpacing = 0.6.sp)
                if (adjustments.topicShowHeaderDetail) {
                    Text("Active MQTT Wildcards", fontSize = 9.sp, color = tokens.textMuted)
                }
            }
            adminTopicRows(partitions, adjustments.topicColumns).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(adjustments.topicColumnGapDp.dp)) {
                    row.forEach { item ->
                        AdminTopicTile(item, total, adjustments, tokens, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTopicTile(
    item: AdminTopicPartition,
    total: Int,
    a: AdminDashboardLayout,
    tokens: BingoColors,
    modifier: Modifier
) {
    val haptic = LocalHapticFeedback.current
    val share = adminTopicShare(item.count, total)
    Surface(
        modifier = modifier.heightIn(min = a.topicTileMinHeightDp.dp)
            .bounceClick { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        shape = RoundedCornerShape(a.topicTileCornerDp.dp),
        color = adminUiColor(a.topicTileColorHex, tokens.badgeSurface),
        border = BorderStroke(1.dp, adminUiColor(a.topicTileBorderColorHex, tokens.badgeOutline))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = a.topicTilePaddingHorizontalDp.dp, vertical = a.topicTilePaddingVerticalDp.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (a.topicStackCount) {
                AdminTopicLabel(item, a, tokens)
                AdminTopicCount(item.count, share, a, tokens)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.weight(1f)) { AdminTopicLabel(item, a, tokens) }
                    AdminTopicCount(item.count, share, a, tokens)
                }
            }
            if (a.topicShowSlugs) {
                Text(item.path, fontSize = a.topicSlugSizeSp.sp, color = tokens.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (a.topicShowBars) {
                Box(Modifier.fillMaxWidth().height(a.topicBarHeightDp.dp)
                    .clip(RoundedCornerShape(a.topicBarHeightDp.dp / 2))
                    .background(tokens.badgeOutline)) {
                    if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight()
                        .background(adminUiColor(a.topicBarColorHex, if (tokens.isDark) Color.White else tokens.accentBrand)))
                }
            }
        }
    }
}

@Composable
private fun AdminTopicLabel(item: AdminTopicPartition, a: AdminDashboardLayout, tokens: BingoColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (a.topicShowIcons) Text(item.icon, fontSize = a.topicIconSizeSp.sp)
        Text(item.name, fontSize = a.topicTileLabelSizeSp.sp, color = tokens.textSecondary,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdminTopicCount(count: Int, share: Float, a: AdminDashboardLayout, tokens: BingoColors) {
    Column(horizontalAlignment = Alignment.End) {
        Text(count.toString(), fontSize = a.topicCountFontSizeSp.sp, fontWeight = FontWeight.Bold,
            color = adminUiColor(a.topicCountColorHex, tokens.textPrimary))
        if (a.topicShowPercentages) Text("${(share * 100).toInt()}%", fontSize = a.topicPercentSizeSp.sp, color = tokens.textMuted)
    }
}
