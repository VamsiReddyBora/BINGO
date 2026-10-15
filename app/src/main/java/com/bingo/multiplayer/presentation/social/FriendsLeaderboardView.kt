package com.bingo.multiplayer.presentation.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.UserProfile
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.bingo.multiplayer.domain.network.LeaderboardSyncManager
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import kotlinx.coroutines.launch

data class LeaderboardEntry(
    val uid: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val gamesPlayed: Int,
    val gamesWon: Int,
    val currentStreak: Int,
    val level: Int,
    val isCurrentUser: Boolean
)

@Composable
fun FriendsLeaderboardView(
    currentUser: UserProfile,
    friends: List<Friend>,
    onViewProfile: (PlayerProfileData) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    var selectedMetric by remember { mutableIntStateOf(0) } // 0 = Victories, 1 = Streak, 2 = Level

    // Observe reactive cache updates so leaderboard updates instantly when background fetches finish
    val cacheVersion by com.bingo.multiplayer.domain.network.AccountSessionManager.registryCacheVersion.collectAsState()
    val isSyncComplete by LeaderboardSyncManager.isSyncComplete.collectAsState()
    val syncProgress by LeaderboardSyncManager.syncProgress.collectAsState()
    val syncStageText by LeaderboardSyncManager.syncStageText.collectAsState()

    var isManualRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Trigger parallel refresh on mount in background
    LaunchedEffect(Unit) {
        if (friends.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.refreshFriendsStats(friends)
            }
        }
    }

    // Build consolidated participant list (Current User + Friends)
    val entries = remember(currentUser, friends, selectedMetric, cacheVersion) {
        val list = mutableListOf<LeaderboardEntry>()

        // Add current user
        list.add(
            LeaderboardEntry(
                uid = currentUser.uid,
                username = currentUser.username.ifBlank { currentUser.playerId },
                displayName = currentUser.displayName,
                avatarUrl = currentUser.avatarBase64?.takeIf { it.isNotBlank() } ?: currentUser.avatarUrl,
                gamesPlayed = currentUser.gamesPlayed,
                gamesWon = currentUser.gamesWon,
                currentStreak = currentUser.currentStreak,
                level = currentUser.level,
                isCurrentUser = true
            )
        )

        // Career totals and the latest current streak come from the friend's profile.
        friends.forEach { friend ->
            val clean = friend.username.trim().lowercase().removePrefix("@")
            val cached = com.bingo.multiplayer.domain.network.AccountSessionManager.getCachedPlayer(clean)
                ?: com.bingo.multiplayer.domain.network.AccountSessionManager.getCachedPlayer(friend.uid)

            val effective = cached?.let {
                com.bingo.multiplayer.domain.engine.FriendCareerStats.merge(friend, it)
            } ?: friend
            val won = effective.gamesWon
            val played = effective.gamesPlayed
            val streak = effective.currentStreak
            val lvl = effective.level.coerceAtLeast(1)
            val avatar = effective.avatarUrl
            list.add(
                LeaderboardEntry(
                    uid = friend.uid,
                    username = clean.ifBlank { friend.displayName },
                    displayName = friend.displayName,
                    avatarUrl = avatar,
                    gamesPlayed = played,
                    gamesWon = won,
                    currentStreak = streak,
                    level = lvl,
                    isCurrentUser = false
                )
            )
        }

        // Sort based on selected metric
        when (selectedMetric) {
            0 -> list.sortedWith(compareByDescending<LeaderboardEntry> { it.gamesWon }.thenByDescending { it.level })
            1 -> list.sortedWith(compareByDescending<LeaderboardEntry> { it.currentStreak }.thenByDescending { it.gamesWon })
            else -> list.sortedWith(compareByDescending<LeaderboardEntry> { it.level }.thenByDescending { it.gamesWon })
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header & Cloud Sync Status Badge ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = Color(0xFFEAB308),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FRIENDS LEADERBOARD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.textMuted
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isSyncComplete) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFEAB308).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEAB308))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "SYNCING CLOUD",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEAB308),
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    } else {
                        if (isManualRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = tokens.accentBrand
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    if (!isManualRefreshing) {
                                        isManualRefreshing = true
                                        coroutineScope.launch {
                                            try {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.refreshFriendsStats(friends)
                                                }
                                            } finally {
                                                isManualRefreshing = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Leaderboard",
                                    tint = tokens.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!isSyncComplete) {
                // Attractive loading screen with progress bar on the card itself
                LeaderboardLoadingCard(
                    progress = syncProgress,
                    statusText = syncStageText
                )
            } else {
                // Metric Tabs: Victories | Streak | Level
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tokens.backgroundSecondary,
                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        MetricTabButton(
                            text = "Victories",
                            icon = Icons.Default.EmojiEvents,
                            selected = selectedMetric == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedMetric = 0 }
                        )
                        MetricTabButton(
                            text = "Streak",
                            icon = Icons.Default.LocalFireDepartment,
                            selected = selectedMetric == 1,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedMetric = 1 }
                        )
                        MetricTabButton(
                            text = "Level",
                            icon = Icons.Default.Star,
                            selected = selectedMetric == 2,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedMetric = 2 }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Podium for Top 3 ──
                if (entries.isNotEmpty()) {
                    val first = entries.getOrNull(0)
                    val second = entries.getOrNull(1)
                    val third = entries.getOrNull(2)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // 2nd Place (Silver)
                        if (second != null) {
                            PodiumPlayer(
                                entry = second,
                                rank = 2,
                                badgeColor = Color(0xFF94A3B8), // Silver
                                metricText = getMetricDisplay(second, selectedMetric),
                                onClick = { onViewProfile(entryToProfileData(second)) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 1st Place (Gold - Taller/Center)
                        if (first != null) {
                            PodiumPlayer(
                                entry = first,
                                rank = 1,
                                badgeColor = Color(0xFFEAB308), // Gold
                                metricText = getMetricDisplay(first, selectedMetric),
                                onClick = { onViewProfile(entryToProfileData(first)) },
                                modifier = Modifier.weight(1.15f)
                            )
                        }

                        // 3rd Place (Bronze)
                        if (third != null) {
                            PodiumPlayer(
                                entry = third,
                                rank = 3,
                                badgeColor = Color(0xFFD97706), // Bronze
                                metricText = getMetricDisplay(third, selectedMetric),
                                onClick = { onViewProfile(entryToProfileData(third)) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ── Ranked List (4th place and beyond) ──
                if (entries.size > 3) {
                    HorizontalDivider(color = tokens.surfaceBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 3 until entries.size) {
                            val item = entries[i]
                            RankedListRow(
                                entry = item,
                                rank = i + 1,
                                metricText = getMetricDisplay(item, selectedMetric),
                                onClick = { onViewProfile(entryToProfileData(item)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTabButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors

    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) tokens.surface else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, tokens.surfaceBorder) else null
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) (if (tokens.isDark) Color.White else tokens.accentBrand) else tokens.textMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) (if (tokens.isDark) Color.White else tokens.cellNeutralText) else tokens.textMuted
            )
        }
    }
}

@Composable
private fun PodiumPlayer(
    entry: LeaderboardEntry,
    rank: Int,
    badgeColor: Color,
    metricText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val avatarSize = if (rank == 1) 54.dp else 44.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Rank Crown / Badge
        Surface(
            shape = CircleShape,
            color = badgeColor,
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$rank",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        PlayerAvatar(
            avatarPathOrUri = entry.avatarUrl,
            displayName = entry.displayName,
            size = avatarSize,
            borderWidth = 2.dp,
            borderColor = badgeColor,
            username = entry.username
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (entry.isCurrentUser) "You" else entry.displayName,
            fontSize = 11.5.sp,
            fontWeight = if (entry.isCurrentUser) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (entry.isCurrentUser) (if (tokens.isDark) Color.White else tokens.accentBrand) else tokens.cellNeutralText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = metricText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )
    }
}

@Composable
private fun RankedListRow(
    entry: LeaderboardEntry,
    rank: Int,
    metricText: String,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (entry.isCurrentUser) tokens.backgroundSecondary else Color.Transparent,
        border = if (entry.isCurrentUser) BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.accentBrand.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$rank",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.textMuted,
                modifier = Modifier.width(26.dp)
            )

            PlayerAvatar(
                avatarPathOrUri = entry.avatarUrl,
                displayName = entry.displayName,
                size = 32.dp,
                username = entry.username
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (entry.isCurrentUser) "${entry.displayName} (You)" else entry.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Medium,
                    color = tokens.cellNeutralText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${entry.username}",
                    fontSize = 10.sp,
                    color = tokens.textMuted
                )
            }

            Text(
                text = metricText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.cellNeutralText
            )
        }
    }
}

private fun getMetricDisplay(entry: LeaderboardEntry, selectedMetric: Int): String {
    return when (selectedMetric) {
        0 -> "${entry.gamesWon}W"
        1 -> "${entry.currentStreak} 🔥"
        else -> "Lv ${entry.level}"
    }
}

private fun entryToProfileData(entry: LeaderboardEntry): PlayerProfileData {
    return PlayerProfileData(
        playerId = entry.uid,
        username = entry.username,
        displayName = entry.displayName,
        avatarUrl = entry.avatarUrl,
        gamesPlayed = entry.gamesPlayed,
        gamesWon = entry.gamesWon,
        currentStreak = entry.currentStreak,
        level = entry.level
    )
}

@Composable
private fun LeaderboardLoadingCard(
    progress: Float,
    statusText: String
) {
    val tokens = BingoTheme.colors

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "leaderboardSyncProgress"
    )
    val pctInt = (animatedProgress * 100).toInt()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glowing animated trophy circle
        Box(
            modifier = Modifier
                .size(54.dp)
                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFEAB308).copy(alpha = 0.35f),
                            Color(0xFFEAB308).copy(alpha = 0.08f)
                        )
                    )
                )
                .border(1.5.dp, Color(0xFFEAB308).copy(alpha = pulseAlpha), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFEAB308),
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "SYNCING CLOUD STANDINGS",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            color = Color(0xFFEAB308)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Stage description & Percentage readout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                fontSize = 11.sp,
                color = tokens.textMuted,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$pctInt%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEAB308)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Custom Gradient Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.backgroundSecondary)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animatedProgress.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFEAB308),
                                Color(0xFFF59E0B),
                                Color(0xFF10B981)
                            )
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Shimmering Silhouette Podium Placeholders (Rank 2, Rank 1, Rank 3)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            PodiumSilhouette(
                rank = 2,
                podiumHeight = 44.dp,
                alpha = pulseAlpha * 0.7f,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            PodiumSilhouette(
                rank = 1,
                podiumHeight = 60.dp,
                alpha = pulseAlpha,
                modifier = Modifier.weight(1.1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            PodiumSilhouette(
                rank = 3,
                podiumHeight = 34.dp,
                alpha = pulseAlpha * 0.6f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PodiumSilhouette(
    rank: Int,
    podiumHeight: androidx.compose.ui.unit.Dp,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val accentColor = when (rank) {
        1 -> Color(0xFFEAB308)
        2 -> Color(0xFF94A3B8)
        else -> Color(0xFFB45309)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar circle silhouette
        Box(
            modifier = Modifier
                .size(if (rank == 1) 40.dp else 34.dp)
                .clip(CircleShape)
                .background(tokens.backgroundSecondary.copy(alpha = alpha.coerceIn(0.3f, 0.9f)))
                .border(1.dp, accentColor.copy(alpha = alpha * 0.5f), CircleShape)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Name placeholder bar
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.backgroundSecondary.copy(alpha = alpha.coerceIn(0.2f, 0.7f)))
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Podium step box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(podiumHeight)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = (alpha * 0.25f).coerceIn(0.08f, 0.4f)),
                            tokens.backgroundSecondary.copy(alpha = 0.5f)
                        )
                    )
                )
                .border(
                    BorderStroke(1.dp, accentColor.copy(alpha = (alpha * 0.4f).coerceIn(0.1f, 0.5f))),
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "#$rank",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor.copy(alpha = alpha.coerceIn(0.4f, 1f))
            )
        }
    }
}
