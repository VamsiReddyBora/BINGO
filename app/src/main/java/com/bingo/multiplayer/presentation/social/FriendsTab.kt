@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bingo.multiplayer.presentation.social

import com.bingo.multiplayer.presentation.common.noRippleClickable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.network.PlayerPresence
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.domain.network.AccountSessionManager
import com.bingo.multiplayer.domain.network.ActionCooldownManager
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Friends tab displaying friend search, presence status, actions (invite, remove), and filter chips.
 */
@Composable
fun FriendsTabContent(
    friends: List<Friend>,
    searchUsernameInput: String,
    onSearchUsernameChanged: (String) -> Unit,
    isSearching: Boolean,
    foundPlayer: PlayerRegistryEntry?,
    searchAttempted: Boolean,
    onSearch: () -> Unit,
    onOpenQrScanner: () -> Unit,
    isAlreadyFriend: (String) -> Boolean,
    hasSentRequest: (String) -> Boolean,
    onSendFriendRequest: (PlayerRegistryEntry) -> Unit,
    onInviteFriend: (Friend) -> Unit,
    onRequestRemoveFriend: (Friend) -> Unit,
    presenceMap: Map<String, PlayerPresence>,
    onViewProfile: (PlayerProfileData) -> Unit
) {
    val tokens = BingoTheme.colors

    // Live ticker to automatically re-evaluate elapsed last-seen strings every 5 seconds
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000L)
            ticker = System.currentTimeMillis()
        }
    }

    // Prefetch friends career records in background so profile dialogs open with zero delay
    LaunchedEffect(friends) {
        friends.forEach { friend ->
            if (friend.username.isNotBlank()) {
                AccountSessionManager.prefetchPlayer(friend.username, this)
            }
        }
    }

    // ── In-List Filter and Priority Sorting ──
    var friendsFilterQuery by remember { mutableStateOf("") }
    var selectedFilterChip by remember { mutableIntStateOf(0) } // 0 = All, 1 = Online, 2 = In-Game

    val totalOnlineCount = remember(friends, presenceMap, ticker) {
        friends.count {
            val cleanF = it.username.trim().lowercase().removePrefix("@")
            val pres = presenceMap[cleanF]
            pres?.isOnline ?: (it.lastSeenTimestamp > 0L && (System.currentTimeMillis() - it.lastSeenTimestamp) < 15_000L)
        }
    }
    val totalInGameCount = remember(friends, presenceMap, ticker) {
        friends.count {
            val cleanF = it.username.trim().lowercase().removePrefix("@")
            val pres = presenceMap[cleanF]
            val isOnline = pres?.isOnline ?: (it.lastSeenTimestamp > 0L && (System.currentTimeMillis() - it.lastSeenTimestamp) < 15_000L)
            isOnline && pres?.status.equals("PLAYING", ignoreCase = true)
        }
    }

    val sortedFriends = remember(friends, presenceMap, ticker) {
        friends.sortedWith(
            compareByDescending<Friend> { friend ->
                val clean = friend.username.trim().lowercase().removePrefix("@")
                val pres = presenceMap[clean]
                val isOnline = pres?.isOnline ?: (friend.lastSeenTimestamp > 0L && (System.currentTimeMillis() - friend.lastSeenTimestamp) < 15_000L)
                if (isOnline) {
                    when (pres?.status?.uppercase()) {
                        "PLAYING" -> 3
                        "IN_LOBBY" -> 4
                        "ONLINE" -> 5
                        else -> 2
                    }
                } else {
                    0
                }
            }.thenByDescending { friend ->
                val clean = friend.username.trim().lowercase().removePrefix("@")
                val pres = presenceMap[clean]
                pres?.timestamp?.takeIf { it > 0L } ?: friend.lastSeenTimestamp
            }
        )
    }

    val filteredFriends = remember(sortedFriends, friendsFilterQuery, selectedFilterChip, presenceMap) {
        sortedFriends.filter { friend ->
            val clean = friend.username.trim().lowercase().removePrefix("@")
            val pres = presenceMap[clean]
            val isOnline = pres?.isOnline ?: (friend.lastSeenTimestamp > 0L && (System.currentTimeMillis() - friend.lastSeenTimestamp) < 15_000L)
            val isPlaying = isOnline && pres?.status.equals("PLAYING", ignoreCase = true)

            val matchesChip = when (selectedFilterChip) {
                1 -> isOnline
                2 -> isPlaying
                else -> true
            }
            val matchesQuery = friendsFilterQuery.isBlank() ||
                    friend.displayName.contains(friendsFilterQuery, ignoreCase = true) ||
                    friend.username.contains(friendsFilterQuery, ignoreCase = true)

            matchesChip && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── 1. Search & Add Friend Card ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SEARCH PLAYER TO ADD FRIEND",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchUsernameInput,
                        onValueChange = onSearchUsernameChanged,
                        placeholder = {
                            Text("Enter player's @username", fontSize = 13.sp, color = tokens.cellNeutralText.copy(alpha = 0.4f))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentBrand,
                            unfocusedBorderColor = tokens.surfaceBorder
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onOpenQrScanner,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.backgroundSecondary)
                            .border(1.dp, tokens.surfaceBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Player QR Code",
                            tint = tokens.accentBrand,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val keyboardController = LocalSoftwareKeyboardController.current
                    val isSearchEnabled = !isSearching && searchUsernameInput.isNotBlank()
                    val searchContentColor = if (isSearchEnabled) tokens.primaryButtonText else tokens.textMuted
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            onSearch()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonBg,
                            contentColor = tokens.primaryButtonText,
                            disabledContainerColor = if (tokens.isDark) Color(0xFF27272A) else Color(0xFFE2E8F0),
                            disabledContentColor = tokens.textMuted
                        ),
                        enabled = isSearchEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tokens.primaryButtonText)
                        } else {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(16.dp), tint = searchContentColor)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Search", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = searchContentColor)
                        }
                    }
                }

                if (foundPlayer != null) {
                    val player = foundPlayer
                    val cleanFoundUser = player.username.trim().lowercase().removePrefix("@")
                    val already = isAlreadyFriend(cleanFoundUser)
                    val requested = hasSentRequest(cleanFoundUser)
                    if (ticker >= 0L) Unit
                    val livePresence = presenceMap[cleanFoundUser]
                    val effectiveLastSeen = livePresence?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                    val statusText = PresenceManager.getRichDisplayStatus(cleanFoundUser, effectiveLastSeen)
                    val displayStatus = statusText
                    val statusColor = PresenceManager.getStatusColor(statusText)

                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .noRippleClickable {
                                        val freshPlayer = player.copy(lastSeenTimestamp = effectiveLastSeen)
                                        onViewProfile(PlayerProfileData.fromRegistryEntry(freshPlayer))
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlayerAvatar(
                                    avatarPathOrUri = player.avatarUrl,
                                    displayName = player.displayName,
                                    size = 40.dp,
                                    username = player.username
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = player.displayName,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.cellNeutralText,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(iterations = Int.MAX_VALUE)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = displayStatus,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = statusColor,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(iterations = Int.MAX_VALUE)
                                    )
                                }
                            }

                            when {
                                already -> {
                                    // If already friends, no need to display anything
                                }
                                requested -> {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = tokens.badgeSurface,
                                        border = BorderStroke(1.dp, tokens.badgeOutline)
                                    ) {
                                        Text(
                                            text = "Sent",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.badgeContent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                else -> {
                                    val isOnCooldown = ActionCooldownManager.isFriendRequestOnCooldown(cleanFoundUser)
                                    IconButton(
                                        onClick = {
                                            ActionCooldownManager.startFriendRequestCooldown(cleanFoundUser)
                                            onSendFriendRequest(player)
                                        },
                                        enabled = !isOnCooldown,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        val iconTint = if (isOnCooldown) {
                                            tokens.textMuted
                                        } else {
                                            if (tokens.isDark) Color.White else tokens.accentBrand
                                        }
                                        Icon(
                                            imageVector = Icons.Default.PersonAdd,
                                            contentDescription = "Add Friend",
                                            tint = iconTint,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (searchAttempted && !isSearching) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No player found with username \"$searchUsernameInput\"",
                        fontSize = 12.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 2. Friends List Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MY FRIENDS (${friends.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = tokens.textMuted
            )

            if (totalOnlineCount > 0) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = tokens.badgeSurface,
                    border = BorderStroke(1.dp, tokens.badgeOutline)
                ) {
                    Text(
                        text = "$totalOnlineCount online",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.badgeContent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (friends.isNotEmpty()) {
            OutlinedTextField(
                value = friendsFilterQuery,
                onValueChange = { friendsFilterQuery = it },
                placeholder = {
                    Text("Filter friends by name...", fontSize = 12.sp, color = tokens.cellNeutralText.copy(alpha = 0.4f))
                },
                leadingIcon = {
                    Icon(Icons.Default.FilterList, contentDescription = null, tint = tokens.cellNeutralText.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (friendsFilterQuery.isNotBlank()) {
                        IconButton(onClick = { friendsFilterQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = tokens.cellNeutralText.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.accentBrand,
                    unfocusedBorderColor = tokens.surfaceBorder
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips (All, Online, In-Game)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChipItem(
                    label = "All (${friends.size})",
                    selected = selectedFilterChip == 0,
                    onClick = { selectedFilterChip = 0 }
                )
                FilterChipItem(
                    label = "Online ($totalOnlineCount)",
                    selected = selectedFilterChip == 1,
                    onClick = { selectedFilterChip = 1 }
                )
                FilterChipItem(
                    label = "In-Game ($totalInGameCount)",
                    selected = selectedFilterChip == 2,
                    onClick = { selectedFilterChip = 2 }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (friends.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(imageVector = Icons.Default.GroupAdd, contentDescription = null, tint = tokens.cellNeutralText.copy(alpha = 0.35f), modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "No friends added yet", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Search a player by @username above and send a friend request to connect!", fontSize = 11.5.sp, textAlign = TextAlign.Center, color = tokens.cellNeutralText.copy(alpha = 0.5f))
                }
            }
        } else if (filteredFriends.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.backgroundSecondary,
                border = BorderStroke(1.dp, tokens.surfaceBorder)
            ) {
                Text(
                    text = "No friends match your current filter.",
                    fontSize = 12.5.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredFriends.forEach { friend ->
                    FriendCard(
                        friend = friend,
                        onInvite = { onInviteFriend(friend) },
                        onRemove = { onRequestRemoveFriend(friend) },
                        onClickProfile = { onViewProfile(PlayerProfileData.fromFriend(friend)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(84.dp))
    }
}

@Composable
fun FilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors
    val chipBg = if (selected) tokens.primaryButtonBg else tokens.surface
    val chipBorder = if (selected) tokens.primaryButtonBg else if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder
    val chipTextColor = if (selected) tokens.primaryButtonText else tokens.cellNeutralText
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = chipBg,
        border = BorderStroke(1.dp, chipBorder),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = chipTextColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun FriendCard(
    friend: Friend,
    onInvite: () -> Unit,
    onRemove: () -> Unit,
    onClickProfile: () -> Unit
) {
    val tokens = BingoTheme.colors
    val presenceMap by PresenceManager.presenceFlow.collectAsState()
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2000L)
            ticker = System.currentTimeMillis()
        }
    }
    if (ticker >= 0L) Unit
    val cleanFriend = friend.username.trim().lowercase().removePrefix("@")
    val livePres = presenceMap[cleanFriend]
    val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: friend.lastSeenTimestamp.takeIf { it > 0L }
    val displayStatus = PresenceManager.getRichDisplayStatus(cleanFriend, effectiveTs)
    val statusColor = PresenceManager.getStatusColor(displayStatus)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .noRippleClickable(onClick = onClickProfile)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerAvatar(
                    avatarPathOrUri = friend.avatarUrl,
                    displayName = friend.displayName,
                    size = 42.dp,
                    borderColor = tokens.accentBrand,
                    username = friend.username
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = friend.displayName,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = displayStatus,
                        fontSize = 10.5.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val availability = com.bingo.multiplayer.domain.network.FriendPlayAvailability.of(livePres)
                val isRequest = availability == com.bingo.multiplayer.domain.network.FriendPlayAvailability.BUSY
                val isInviteCooldown = if (isRequest) ActionCooldownManager.isPlayRequestOnCooldown(cleanFriend) else ActionCooldownManager.isInviteOnCooldown(cleanFriend)
                Button(
                    onClick = {
                        if (isRequest) ActionCooldownManager.startPlayRequestCooldown(cleanFriend) else ActionCooldownManager.startInviteCooldown(cleanFriend)
                        onInvite()
                    },
                    enabled = !isInviteCooldown && availability.canContact,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInviteCooldown) (if (tokens.isDark) Color(0xFF222222) else Color(0xFFE2E8F0)) else tokens.primaryButtonBg,
                        contentColor = if (isInviteCooldown) tokens.textMuted else tokens.primaryButtonText,
                        disabledContainerColor = if (tokens.isDark) Color(0xFF222222) else Color(0xFFE2E8F0),
                        disabledContentColor = tokens.textMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (isInviteCooldown) tokens.textMuted else tokens.primaryButtonText
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isRequest) { if (isInviteCooldown) "Requested" else "Request" } else { if (isInviteCooldown) "Invited" else "Invite" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isInviteCooldown) tokens.textMuted else tokens.primaryButtonText
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Remove", tint = tokens.cellNeutralText.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
