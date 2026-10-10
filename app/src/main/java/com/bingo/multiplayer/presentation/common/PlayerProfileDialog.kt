package com.bingo.multiplayer.presentation.common

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.PlayerOnlineStatus
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.domain.repository.FriendsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure gaming career profile data (PUBG-Style).
 * Strictly contains gaming battle records.
 * STRICTLY NO personal info (NO email, NO phone ID, NO account provider, NO friend list).
 */
data class PlayerProfileData(
    val playerId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val level: Int = 1,
    val isOnline: Boolean = false,
    val lastSeenDisplay: String = "offline",
    val lastSeenTimestamp: Long = 0L
) {
    val winRatePercentage: Int
        get() = if (gamesPlayed > 0) ((gamesWon.toFloat() / gamesPlayed) * 100).toInt() else 0

    val rankTitle: String
        get() = when {
            level >= 8 -> "Grandmaster"
            level >= 5 -> "Gold Master"
            level >= 3 -> "Silver Competitor"
            else -> "Bronze Player"
        }

    companion object {
        fun fromPlayer(p: Player, currentUser: com.bingo.multiplayer.domain.model.UserProfile? = null): PlayerProfileData {
            val isMe = p.id == currentUser?.uid || (currentUser?.username?.isNotBlank() == true && (p.username.equals(currentUser.username, ignoreCase = true) || p.displayName.equals(currentUser.username, ignoreCase = true)))
            val effAvatar = if (isMe) {
                currentUser?.avatarBase64?.takeIf { it.isNotBlank() } ?: currentUser?.avatarUrl ?: p.avatarUrl
            } else {
                p.avatarUrl
            }
            val cleanUser = p.username.ifBlank {
                if (isMe) currentUser?.username ?: "" else ""
            }.ifBlank {
                p.displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { p.id.take(8) }
            }
            val statusText = if (isMe) {
                when (com.bingo.multiplayer.domain.network.PresenceManager.currentActivityState) {
                    com.bingo.multiplayer.domain.network.AppActivityState.IN_LOBBY -> "in-lobby"
                    com.bingo.multiplayer.domain.network.AppActivityState.PLAYING -> "playing"
                    com.bingo.multiplayer.domain.network.AppActivityState.ONLINE -> "online"
                }
            } else {
                com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, p.lastSeenTimestamp)
            }
            val isOnline = com.bingo.multiplayer.domain.network.PresenceManager.isStatusOnline(statusText)
            val cached = if (!isMe) com.bingo.multiplayer.domain.network.AccountSessionManager.getCachedPlayer(cleanUser) else null
            return PlayerProfileData(
                playerId = p.id,
                username = cleanUser,
                displayName = if (isMe && currentUser != null) currentUser.displayName else p.displayName,
                avatarUrl = effAvatar,
                gamesPlayed = if (isMe && currentUser != null) currentUser.gamesPlayed else (if (p.gamesPlayed > 0) p.gamesPlayed else (cached?.gamesPlayed ?: 0)),
                gamesWon = if (isMe && currentUser != null) currentUser.gamesWon else (if (p.gamesWon > 0) p.gamesWon else (cached?.gamesWon ?: 0)),
                currentStreak = if (isMe && currentUser != null) currentUser.currentStreak else (if (p.currentStreak > 0) p.currentStreak else (cached?.currentStreak ?: 0)),
                bestStreak = if (isMe && currentUser != null) currentUser.bestStreak else (if (p.bestStreak > 0) p.bestStreak else (cached?.bestStreak ?: 0)),
                level = if (isMe && currentUser != null) currentUser.level else (if (p.level > 1) p.level else (cached?.level?.coerceAtLeast(1) ?: 1)),
                isOnline = isOnline,
                lastSeenDisplay = statusText,
                lastSeenTimestamp = p.lastSeenTimestamp
            )
        }

        fun fromRegistryEntry(entry: PlayerRegistryEntry): PlayerProfileData {
            val cleanUser = entry.username.trim().lowercase().removePrefix("@")
            val statusText = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, entry.lastSeenTimestamp)
            val isOnline = com.bingo.multiplayer.domain.network.PresenceManager.isStatusOnline(statusText)
            return PlayerProfileData(
                playerId = entry.uid,
                username = entry.username,
                displayName = entry.displayName,
                avatarUrl = entry.avatarUrl,
                gamesPlayed = entry.gamesPlayed,
                gamesWon = entry.gamesWon,
                currentStreak = entry.currentStreak,
                bestStreak = entry.bestStreak,
                level = entry.level,
                isOnline = isOnline,
                lastSeenDisplay = statusText,
                lastSeenTimestamp = entry.lastSeenTimestamp
            )
        }

        fun fromFriend(friend: com.bingo.multiplayer.domain.model.Friend): PlayerProfileData {
            val cleanUser = friend.username.ifBlank {
                friend.displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { friend.uid.take(8) }
            }.trim().lowercase().removePrefix("@")
            val statusText = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, friend.lastSeenTimestamp)
            val isOnline = com.bingo.multiplayer.domain.network.PresenceManager.isStatusOnline(statusText)
            val cached = com.bingo.multiplayer.domain.network.AccountSessionManager.getCachedPlayer(cleanUser)
            return if (cached != null) {
                PlayerProfileData(
                    playerId = cached.uid.ifBlank { friend.uid },
                    username = cleanUser,
                    displayName = cached.displayName.ifBlank { friend.displayName },
                    avatarUrl = cached.avatarUrl?.takeIf { it.isNotBlank() } ?: friend.avatarUrl,
                    gamesPlayed = cached.gamesPlayed,
                    gamesWon = cached.gamesWon,
                    currentStreak = cached.currentStreak,
                    bestStreak = cached.bestStreak,
                    level = cached.level.coerceAtLeast(1),
                    isOnline = isOnline,
                    lastSeenDisplay = statusText,
                    lastSeenTimestamp = friend.lastSeenTimestamp
                )
            } else {
                PlayerProfileData(
                    playerId = friend.uid,
                    username = cleanUser,
                    displayName = friend.displayName,
                    avatarUrl = friend.avatarUrl,
                    gamesPlayed = 0,
                    gamesWon = 0,
                    currentStreak = 0,
                    bestStreak = 0,
                    level = 1,
                    isOnline = isOnline,
                    lastSeenDisplay = statusText,
                    lastSeenTimestamp = friend.lastSeenTimestamp
                )
            }
        }
    }
}

/**
 * PUBG-style player battle record dashboard dialog.
 * Displayed when clicking a player's profile picture or name in lobby or search.
 */
@Composable
fun PlayerProfileDialog(
    playerData: PlayerProfileData,
    currentUser: UserProfile?,
    friendsRepository: FriendsRepository?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val coroutineScope = rememberCoroutineScope()

    val myUsername = currentUser?.username?.trim()?.lowercase()?.removePrefix("@") ?: ""
    val targetUsername = playerData.username.trim().lowercase().removePrefix("@")
    val isSelf = myUsername.isNotBlank() && myUsername == targetUsername ||
            (currentUser?.uid != null && currentUser.uid == playerData.playerId)

    val isAlreadyFriend = remember(playerData.username, playerData.playerId) {
        friendsRepository?.isFriend(playerData.username) == true ||
                friendsRepository?.isFriend(playerData.playerId) == true
    }

    var requestSentLocally by remember {
        mutableStateOf(friendsRepository?.hasSentRequest(playerData.username) == true)
    }
    var isSendingRequest by remember { mutableStateOf(false) }

    var effectiveData by remember(playerData) { mutableStateOf(playerData) }

    LaunchedEffect(playerData.username, playerData.playerId, isSelf) {
        if (isSelf && currentUser != null) {
            effectiveData = effectiveData.copy(
                displayName = currentUser.displayName,
                avatarUrl = currentUser.avatarBase64?.takeIf { it.isNotBlank() } ?: currentUser.avatarUrl ?: effectiveData.avatarUrl,
                gamesPlayed = currentUser.gamesPlayed,
                gamesWon = currentUser.gamesWon,
                currentStreak = currentUser.currentStreak,
                bestStreak = currentUser.bestStreak,
                level = currentUser.level
            )
        } else if (targetUsername.isNotBlank()) {
            val cached = com.bingo.multiplayer.domain.network.AccountSessionManager.getCachedPlayer(targetUsername)
            if (cached != null) {
                effectiveData = effectiveData.copy(
                    displayName = cached.displayName.ifBlank { effectiveData.displayName },
                    avatarUrl = cached.avatarUrl?.takeIf { it.isNotBlank() } ?: effectiveData.avatarUrl,
                    gamesPlayed = cached.gamesPlayed,
                    gamesWon = cached.gamesWon,
                    currentStreak = cached.currentStreak,
                    bestStreak = cached.bestStreak,
                    level = cached.level.coerceAtLeast(1)
                )
            }
            try {
                val sessionManager = com.bingo.multiplayer.domain.network.AccountSessionManager()
                val entry = sessionManager.searchPlayerByUsername(targetUsername, forceRefresh = false)
                if (entry != null) {
                    effectiveData = effectiveData.copy(
                        displayName = entry.displayName.ifBlank { effectiveData.displayName },
                        avatarUrl = entry.avatarUrl?.takeIf { it.isNotBlank() } ?: effectiveData.avatarUrl,
                        gamesPlayed = entry.gamesPlayed,
                        gamesWon = entry.gamesWon,
                        currentStreak = entry.currentStreak,
                        bestStreak = entry.bestStreak,
                        level = entry.level.coerceAtLeast(1)
                    )
                }
            } catch (_: Exception) {}
        }
    }

    val presenceMap by com.bingo.multiplayer.domain.network.PresenceManager.presenceFlow.collectAsState()
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(3000L)
            ticker = System.currentTimeMillis()
        }
    }

    LaunchedEffect(targetUsername) {
        if (targetUsername.isNotBlank() && !isSelf) {
            com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresence(targetUsername)
            while (isActive) {
                delay(3000L)
                com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresence(targetUsername)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Header Row with Close Icon ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PLAYER RECORD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = tokens.accentBrand
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = tokens.cellNeutralText.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Player Avatar & Basic Info ──
                PlayerAvatar(
                    avatarPathOrUri = effectiveData.avatarUrl,
                    displayName = effectiveData.displayName,
                    size = 76.dp,
                    borderWidth = 2.dp,
                    borderColor = tokens.accentBrand,
                    username = effectiveData.username
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = effectiveData.displayName,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "@${effectiveData.username}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.accentBrand
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Rank Medal Emoji and Level Chips (Strictly medal emoji 🎖️🏅🥇🥈🥉, no text)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val medalEmoji = when {
                        effectiveData.level >= 12 -> "🎖️"
                        effectiveData.level >= 8 -> "🏅"
                        effectiveData.level >= 5 -> "🥇"
                        effectiveData.level >= 3 -> "🥈"
                        else -> "🥉"
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.badgeSurface,
                        border = BorderStroke(1.dp, tokens.badgeOutline)
                    ) {
                        Text(
                            text = medalEmoji,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Text(
                            text = "LVL ${effectiveData.level}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.cellNeutralText.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (ticker >= 0L) Unit
                    val livePres = presenceMap[targetUsername]
                    val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: effectiveData.lastSeenTimestamp
                    val displayStatus = if (isSelf) {
                        when (com.bingo.multiplayer.domain.network.PresenceManager.currentActivityState) {
                            com.bingo.multiplayer.domain.network.AppActivityState.IN_LOBBY -> "in-lobby"
                            com.bingo.multiplayer.domain.network.AppActivityState.PLAYING -> "playing"
                            com.bingo.multiplayer.domain.network.AppActivityState.ONLINE -> "online"
                        }
                    } else {
                        com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(targetUsername, effectiveTs)
                    }
                    val statusColor = com.bingo.multiplayer.domain.network.PresenceManager.getStatusColor(displayStatus)

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Text(
                            text = displayStatus,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── PUBG-Style Career Battle Stats Grid ──
                Text(
                    text = "BATTLE PERFORMANCE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tokens.textMuted,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BattleStatCard(
                        title = "Matches",
                        value = "${effectiveData.gamesPlayed}",
                        icon = Icons.Default.SportsEsports,
                        modifier = Modifier.weight(1f)
                    )
                    BattleStatCard(
                        title = "Victories",
                        value = "${effectiveData.gamesWon}",
                        icon = Icons.Default.EmojiEvents,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BattleStatCard(
                        title = "Active Streak",
                        value = if (effectiveData.currentStreak > 0) "${effectiveData.currentStreak} 🔥" else "0",
                        icon = Icons.Default.Whatshot,
                        modifier = Modifier.weight(1f)
                    )
                    BattleStatCard(
                        title = "Best Streak",
                        value = if (effectiveData.bestStreak > 0) "${effectiveData.bestStreak} 🏆" else "0",
                        icon = Icons.Default.EmojiEvents,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BattleStatCard(
                        title = "Win Rate",
                        value = "${effectiveData.winRatePercentage}%",
                        icon = Icons.Default.Analytics,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Action / Add Friend Button ──
                if (isSelf) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "This is your gaming profile",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else if (isAlreadyFriend) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.badgeSurface,
                        border = BorderStroke(1.dp, tokens.badgeOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = tokens.badgeContent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Friends",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.badgeContent
                            )
                        }
                    }
                } else if (requestSentLocally) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = null,
                                tint = tokens.cellNeutralText.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Friend Request Sent",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.cellNeutralText.copy(alpha = 0.7f)
                            )
                        }
                    }
                    val isOnCooldown = com.bingo.multiplayer.domain.network.ActionCooldownManager.isFriendRequestOnCooldown(effectiveData.username)
                    Button(
                        onClick = {
                            if (currentUser == null) {
                                Toast.makeText(context, "Sign in to add friends", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            com.bingo.multiplayer.domain.network.ActionCooldownManager.startFriendRequestCooldown(effectiveData.username)
                            isSendingRequest = true
                            coroutineScope.launch {
                                val success = friendsRepository?.sendFriendRequest(
                                    targetUsername = effectiveData.username,
                                    targetDisplayName = effectiveData.displayName,
                                    targetUid = effectiveData.playerId,
                                    currentUser = currentUser
                                ) ?: false
                                isSendingRequest = false
                                if (success) {
                                    requestSentLocally = true
                                    Toast.makeText(context, "Friend request sent to @${effectiveData.username}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not send friend request. Try again.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSendingRequest && !isOnCooldown,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isOnCooldown) (if (tokens.isDark) Color(0xFF222222) else Color(0xFFE2E8F0)) else tokens.primaryButtonBg,
                            contentColor = if (isOnCooldown) tokens.textMuted else tokens.primaryButtonText,
                            disabledContainerColor = if (tokens.isDark) Color(0xFF222222) else Color(0xFFE2E8F0),
                            disabledContentColor = tokens.textMuted
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSendingRequest) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = tokens.primaryButtonText,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sending Request...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                        } else {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tokens.primaryButtonText
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Friend", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BattleStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tokens.backgroundSecondary,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (tokens.isDark) Color.White else tokens.accentBrand,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textMuted
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = tokens.cellNeutralText
            )
        }
    }
}
