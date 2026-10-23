package com.bingo.multiplayer.presentation.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard tab displaying career statistics, player hero profile, and match history.
 */
@Composable
fun DashboardTabContent(
    user: UserProfile,
    friends: List<Friend>,
    matchHistory: List<MatchRecord>,
    onShareProfile: () -> Unit,
    onSelectScorecardMatch: (MatchRecord) -> Unit,
    onViewProfile: (PlayerProfileData) -> Unit
) {
    val tokens = BingoTheme.colors
    var visibleMatchCount by remember { mutableStateOf(10) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── 1. Hero Player Card ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Top-right username display with Share Profile button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onShareProfile() },
                        color = tokens.badgeSurface,
                        border = BorderStroke(1.dp, tokens.badgeOutline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Share Profile QR",
                                tint = tokens.badgeContent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Share Profile",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.badgeContent
                            )
                        }
                    }

                    Text(
                        text = "username: ${user.username.ifBlank { user.playerId }}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerAvatar(
                        avatarPathOrUri = user.avatarBase64?.takeIf { it.isNotBlank() } ?: user.avatarUrl,
                        displayName = user.displayName,
                        size = 64.dp,
                        borderWidth = 2.dp,
                        borderColor = tokens.accentBrand,
                        username = user.username
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = user.displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = tokens.badgeSurface,
                                border = BorderStroke(1.dp, tokens.badgeOutline)
                            ) {
                                Text(
                                    text = user.rankTitle,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.badgeContent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Level ${user.level} • ${user.xp} XP",
                            fontSize = 11.sp,
                            color = tokens.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // XP Progress to next level
                val currentLevelProgress = (user.xp % 400) / 400f
                LinearProgressIndicator(
                    progress = { currentLevelProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (tokens.isDark) Color.White else tokens.accentBrand,
                    trackColor = if (tokens.isDark) Color(0xFF222222) else tokens.backgroundSecondary,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Rank: Level ${user.level}", fontSize = 10.sp, color = tokens.textMuted)
                    Text(text = "${(currentLevelProgress * 100).toInt()}% to Level ${user.level + 1}", fontSize = 10.sp, color = if (tokens.isDark) Color.White else tokens.accentBrand, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 2. Career Statistics Grid (2x2) ──
        Text(
            text = "CAREER PERFORMANCE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.textMuted
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "Total Matches",
                value = "${user.gamesPlayed}",
                icon = Icons.Default.SportsEsports,
                color = tokens.accentBrand,
                modifier = Modifier.weight(1f)
            )

            StatCard(
                title = "Victories",
                value = "${user.gamesWon}",
                icon = Icons.Default.EmojiEvents,
                color = Color(0xFF16A34A),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "Active Streak",
                value = "${user.currentStreak} 🔥",
                icon = Icons.Default.LocalFireDepartment,
                color = tokens.accentOrange,
                modifier = Modifier.weight(1f)
            )

            StatCard(
                title = "Best Streak",
                value = "${user.bestStreak} 🏆",
                icon = Icons.Default.EmojiEvents,
                color = Color(0xFFEAB308),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "Win Rate",
                value = "${user.winRatePercentage}%",
                icon = Icons.Default.PieChart,
                color = tokens.accentOpponent,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Friends Leaderboard ──
        FriendsLeaderboardView(
            currentUser = user,
            friends = friends,
            onViewProfile = onViewProfile
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── 3. Recent Match History ──
        Text(
            text = "RECENT MATCH HISTORY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.cellNeutralText.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (matchHistory.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(imageVector = Icons.Default.History, contentDescription = null, tint = tokens.cellNeutralText.copy(alpha = 0.35f), modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "No matches recorded yet", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tokens.cellNeutralText)
                    Text(text = "Play games against AI or friends to see your match history!", fontSize = 11.5.sp, color = tokens.cellNeutralText.copy(alpha = 0.5f))
                }
            }
        } else {
            val visibleMatches = matchHistory.take(visibleMatchCount)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleMatches.forEach { record ->
                    MatchRecordCard(
                        record = record,
                        onClick = { onSelectScorecardMatch(record) }
                    )
                }

                if (matchHistory.size > visibleMatchCount) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { visibleMatchCount += 50 },
                        shape = RoundedCornerShape(12.dp),
                        color = if (tokens.isDark) Color(0xFF1E1E1E) else tokens.accentBrand.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.accentBrand.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = if (tokens.isDark) Color.White else tokens.accentBrand,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Show More Matches (${visibleMatches.size} of ${matchHistory.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (tokens.isDark) Color.White else tokens.accentBrand
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(84.dp))
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (tokens.isDark) Color(0xFF1E1E1E) else color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (tokens.isDark) Color.White else color,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(text = title, fontSize = 10.5.sp, color = tokens.textMuted, fontWeight = FontWeight.Medium)
                Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
            }
        }
    }
}

@Composable
fun MatchRecordCard(
    record: MatchRecord,
    onClick: () -> Unit = {}
) {
    val tokens = BingoTheme.colors
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeBg = if (tokens.isDark) {
                    Color(0xFF222222)
                } else {
                    when {
                        record.isDraw -> Color(0xFFEAB308).copy(alpha = 0.15f)
                        record.didWin -> Color(0xFF16A34A).copy(alpha = 0.15f)
                        else -> Color(0xFFDC2626).copy(alpha = 0.15f)
                    }
                }
                val badgeColor = if (tokens.isDark) {
                    Color.White
                } else {
                    when {
                        record.isDraw -> Color(0xFFEAB308)
                        record.didWin -> Color(0xFF16A34A)
                        else -> Color(0xFFDC2626)
                    }
                }
                val badgeLetter = when {
                    record.isDraw -> "D"
                    record.didWin -> "W"
                    else -> "L"
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(badgeBg)
                        .then(
                            if (tokens.isDark) Modifier.border(1.dp, Color(0xFF383838), RoundedCornerShape(17.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeLetter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = badgeColor
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    val displayTitle = if (record.matchTitle.isNotBlank()) {
                        record.matchTitle
                    } else if (record.isDraw) {
                        "${record.opponentName} Draw 🤝"
                    } else if (record.didWin) {
                        "Victory vs ${record.opponentName} 🏆"
                    } else {
                        "Defeat vs ${record.opponentName} 😑"
                    }
                    Text(
                        text = displayTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${record.mode} • ${record.boardSize}×${record.boardSize} Grid",
                        fontSize = 11.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(record.timestamp)),
                    fontSize = 10.5.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.45f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View Scorecard",
                    tint = tokens.cellNeutralText.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
