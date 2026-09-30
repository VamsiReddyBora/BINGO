package com.bingo.multiplayer.presentation.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.PlayerPresence
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import com.bingo.multiplayer.presentation.common.PlayerProfileDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardAndFriendsScreen(
    user: UserProfile,
    authRepository: AuthRepository,
    friendsRepository: FriendsRepository,
    onInviteFriendToMatch: (Friend) -> Unit,
    onAcceptInviteToMatch: ((com.bingo.multiplayer.domain.network.GameInvite) -> Unit)? = null,
    onBack: () -> Unit
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Dashboard, 1 = Friends, 2 = Requests
    var selectedProfilePlayer by remember { mutableStateOf<PlayerProfileData?>(null) }

    val friends by friendsRepository.friends.collectAsState()
    val pendingRequests by friendsRepository.pendingRequests.collectAsState()
    val sentRequests by friendsRepository.sentRequestUsernames.collectAsState()
    val presenceMap by PresenceManager.presenceFlow.collectAsState()
    val matchHistory = remember { authRepository.getMatchHistory() }

    // Friend search state
    var searchUsernameInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var foundPlayer by remember { mutableStateOf<PlayerRegistryEntry?>(null) }
    var searchAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(user.username, friends, foundPlayer) {
        friendsRepository.syncFriendsAndRequests(user.username)
        PresenceManager.startPresence(user.username)
        while (isActive) {
            val usersToFetch = mutableListOf<String>()
            friends.forEach { if (it.username.isNotBlank()) usersToFetch.add(it.username) }
            val fp = foundPlayer
            if (fp != null && fp.username.isNotBlank()) {
                usersToFetch.add(fp.username)
            }
            val cleanList = usersToFetch.distinct()
            if (cleanList.isNotEmpty()) {
                PresenceManager.fetchCloudPresenceForUsers(cleanList)
            }
            delay(3000L)
        }
    }

    // Remove friend confirmation dialog
    var friendToRemove by remember { mutableStateOf<Friend?>(null) }

    friendToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            title = { Text("Remove Friend", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove ${target.displayName} from your friends list?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        friendsRepository.removeFriend(target.uid, user.username)
                        friendToRemove = null
                        Toast.makeText(context, "Removed from friends", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Remove", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { friendToRemove = null }) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = tokens.cellNeutralText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Player Dashboard & Social",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Tabs Header ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.backgroundSecondary,
                border = BorderStroke(1.dp, tokens.surfaceBorder)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    TabButton(
                        text = "Dashboard",
                        icon = Icons.Default.Insights,
                        selected = selectedTab == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 0 }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    TabButton(
                        text = "Friends",
                        icon = Icons.Default.People,
                        selected = selectedTab == 1,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 1 }
                    )
                    
                    Spacer(modifier = Modifier.width(6.dp))

                    TabButton(
                        text = "Requests",
                        icon = Icons.Default.Email,
                        badgeCount = pendingRequests.size,
                        selected = selectedTab == 2,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 2 }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Tab Content ──
            when (selectedTab) {
                0 -> {
                    DashboardTabContent(
                        user = user,
                        matchHistory = matchHistory
                    )
                }
                1 -> {
                    FriendsTabContent(
                        friends = friends,
                        searchUsernameInput = searchUsernameInput,
                        onSearchUsernameChanged = { searchUsernameInput = it },
                        isSearching = isSearching,
                        foundPlayer = foundPlayer,
                        searchAttempted = searchAttempted,
                        onSearch = {
                            if (searchUsernameInput.isNotBlank()) {
                                isSearching = true
                                foundPlayer = null
                                searchAttempted = true
                                coroutineScope.launch {
                                    val cleanSearch = searchUsernameInput.trim().lowercase().removePrefix("@")
                                    authRepository.sessionManager.clearRegistryCache(cleanSearch)
                                    PresenceManager.fetchCloudPresence(cleanSearch)
                                    foundPlayer = authRepository.sessionManager.searchPlayerByUsername(searchUsernameInput, forceRefresh = true)
                                    isSearching = false
                                }
                            }
                        },
                        isAlreadyFriend = { uidOrUsername -> friendsRepository.isFriend(uidOrUsername) },
                        hasSentRequest = { username -> sentRequests.contains(username.trim().lowercase().removePrefix("@")) },
                        onSendFriendRequest = { entry ->
                            coroutineScope.launch {
                                val success = friendsRepository.sendFriendRequest(
                                    targetUsername = entry.username,
                                    targetDisplayName = entry.displayName,
                                    targetUid = entry.uid,
                                    currentUser = user
                                )
                                if (success) {
                                    Toast.makeText(context, "Friend request sent to @${entry.username}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not send friend request", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onInviteFriend = onInviteFriendToMatch,
                        onRequestRemoveFriend = { friend -> friendToRemove = friend },
                        presenceMap = presenceMap,
                        onViewProfile = { profile -> selectedProfilePlayer = profile }
                    )
                }
                2 -> {
                    RequestsTabContent(
                        user = user,
                        pendingRequests = pendingRequests,
                        onAcceptFriendRequest = { req ->
                            coroutineScope.launch {
                                val success = friendsRepository.acceptFriendRequest(req, user)
                                if (success) {
                                    Toast.makeText(context, "You and @${req.fromUsername} are now friends!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to accept request", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDeclineFriendRequest = { req ->
                            coroutineScope.launch {
                                friendsRepository.declineFriendRequest(req)
                                Toast.makeText(context, "Friend request ignored", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAcceptInvite = onAcceptInviteToMatch
                    )
                }
            }
        }

        val profileToDisplay = selectedProfilePlayer
        if (profileToDisplay != null) {
            PlayerProfileDialog(
                playerData = profileToDisplay,
                currentUser = user,
                friendsRepository = friendsRepository,
                onDismiss = { selectedProfilePlayer = null }
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    badgeCount: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors

    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(9.dp),
        color = if (selected) tokens.surface else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, tokens.surfaceBorder) else null
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) tokens.accentBrand else tokens.cellNeutralText.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) tokens.cellNeutralText else tokens.cellNeutralText.copy(alpha = 0.6f)
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.accentBrand.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "$badgeCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentBrand,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardTabContent(
    user: UserProfile,
    matchHistory: List<MatchRecord>
) {
    val tokens = BingoTheme.colors

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
                // Top-right username display without @
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "username: ${user.username.ifBlank { user.playerId }}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.cellNeutralText.copy(alpha = 0.55f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerAvatar(
                        avatarPathOrUri = user.avatarUrl,
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
                                color = tokens.cellPlayerPickBg
                            ) {
                                Text(
                                    text = user.rankTitle,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.accentBrand,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Level ${user.level} • ${user.xp} XP",
                            fontSize = 11.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.6f)
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
                    color = tokens.accentBrand,
                    trackColor = tokens.backgroundSecondary,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Rank: Level ${user.level}", fontSize = 10.sp, color = tokens.cellNeutralText.copy(alpha = 0.5f))
                    Text(text = "${(currentLevelProgress * 100).toInt()}% to Level ${user.level + 1}", fontSize = 10.sp, color = tokens.accentBrand, fontWeight = FontWeight.SemiBold)
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
            color = tokens.cellNeutralText.copy(alpha = 0.5f)
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
                title = "Win Rate",
                value = "${user.winRatePercentage}%",
                icon = Icons.Default.PieChart,
                color = tokens.accentOpponent,
                modifier = Modifier.weight(1f)
            )

            StatCard(
                title = "Win Streak",
                value = "${user.currentStreak} 🔥",
                icon = Icons.Default.LocalFireDepartment,
                color = tokens.accentOrange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                matchHistory.take(10).forEach { record ->
                    MatchRecordCard(record = record)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(
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
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(text = title, fontSize = 10.5.sp, color = tokens.cellNeutralText.copy(alpha = 0.55f), fontWeight = FontWeight.Medium)
                Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
            }
        }
    }
}

@Composable
private fun MatchRecordCard(record: MatchRecord) {
    val tokens = BingoTheme.colors
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badgeBg = when {
                    record.isDraw -> Color(0xFFEAB308).copy(alpha = 0.15f)
                    record.didWin -> Color(0xFF16A34A).copy(alpha = 0.15f)
                    else -> Color(0xFFDC2626).copy(alpha = 0.15f)
                }
                val badgeColor = when {
                    record.isDraw -> Color(0xFFEAB308)
                    record.didWin -> Color(0xFF16A34A)
                    else -> Color(0xFFDC2626)
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
                        .background(badgeBg),
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
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = "${record.mode} • ${record.boardSize}×${record.boardSize} Grid",
                        fontSize = 11.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )
                }
            }

            Text(
                text = dateFormat.format(Date(record.timestamp)),
                fontSize = 10.5.sp,
                color = tokens.cellNeutralText.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun FriendsTabContent(
    friends: List<Friend>,
    searchUsernameInput: String,
    onSearchUsernameChanged: (String) -> Unit,
    isSearching: Boolean,
    foundPlayer: PlayerRegistryEntry?,
    searchAttempted: Boolean,
    onSearch: () -> Unit,
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

                    Spacer(modifier = Modifier.width(8.dp))

                    val keyboardController = LocalSoftwareKeyboardController.current
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            onSearch()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                        enabled = !isSearching && searchUsernameInput.isNotBlank()
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Search", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    val statusText = PresenceManager.getDisplayStatus(cleanFoundUser, effectiveLastSeen)
                    val isOnline = statusText.equals("online", ignoreCase = true)
                    val displayStatus = when {
                        isOnline -> "online"
                        statusText.equals("offline", ignoreCase = true) -> "offline"
                        else -> statusText
                    }
                    val statusColor = when {
                        isOnline -> Color(0xFF16A34A)
                        displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
                        else -> Color(0xFFEAB308)
                    }

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
                                    .clickable {
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
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = player.displayName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = displayStatus,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            when {
                                already -> {
                                    // If already friends, no need to display anything
                                }
                                requested -> {
                                    Surface(shape = RoundedCornerShape(8.dp), color = tokens.accentBrand.copy(alpha = 0.15f)) {
                                        Text(text = "Requested", color = tokens.accentBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = { onSendFriendRequest(player) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(tokens.accentBrand.copy(alpha = 0.12f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PersonAdd,
                                            contentDescription = "Add Friend",
                                            tint = tokens.accentBrand,
                                            modifier = Modifier.size(20.dp)
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
                color = tokens.cellNeutralText.copy(alpha = 0.5f)
            )

            val onlineCount = friends.count {
                PresenceManager.getDisplayStatus(it.username, it.lastSeenTimestamp).equals("online", ignoreCase = true)
            }
            if (onlineCount > 0) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF16A34A).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "$onlineCount online",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF16A34A),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                friends.forEach { friend ->
                    FriendCard(
                        friend = friend,
                        onInvite = { onInviteFriend(friend) },
                        onRemove = { onRequestRemoveFriend(friend) },
                        onClickProfile = { onViewProfile(PlayerProfileData.fromFriend(friend)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FriendCard(
    friend: Friend,
    onInvite: () -> Unit,
    onRemove: () -> Unit,
    onClickProfile: () -> Unit
) {
    val tokens = BingoTheme.colors
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2000L)
            ticker = System.currentTimeMillis()
        }
    }
    if (ticker >= 0L) Unit
    val cleanFriend = friend.username.trim().lowercase().removePrefix("@")
    val statusText = PresenceManager.getDisplayStatus(cleanFriend, friend.lastSeenTimestamp)
    val isOnline = statusText.equals("online", ignoreCase = true)
    val displayStatus = when {
        isOnline -> "online"
        statusText.equals("offline", ignoreCase = true) -> "offline"
        else -> statusText
    }
    val statusColor = when {
        isOnline -> Color(0xFF16A34A)
        displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
        else -> Color(0xFFEAB308)
    }

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
                    .clickable(onClick = onClickProfile)
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

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = friend.displayName,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = displayStatus,
                    fontSize = 10.5.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onInvite,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Invite", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Remove", tint = tokens.cellNeutralText.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RequestsTabContent(
    user: UserProfile,
    pendingRequests: List<FriendRequest>,
    onAcceptFriendRequest: (FriendRequest) -> Unit,
    onDeclineFriendRequest: (FriendRequest) -> Unit,
    onAcceptInvite: ((com.bingo.multiplayer.domain.network.GameInvite) -> Unit)? = null
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var invites by remember { mutableStateOf<List<com.bingo.multiplayer.domain.network.GameInvite>>(emptyList()) }
    var loadingInvites by remember { mutableStateOf(true) }

    LaunchedEffect(user.username) {
        while (isActive) {
            val fetched = com.bingo.multiplayer.domain.network.GameInviteManager.fetchInvitesForUser(user.username)
            invites = fetched
            loadingInvites = false
            kotlinx.coroutines.delay(3000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── 1. FRIEND REQUESTS SECTION ──
        Text(
            text = "PENDING FRIEND REQUESTS (${pendingRequests.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.cellNeutralText.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (pendingRequests.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.cellNeutralBg
            ) {
                Text(
                    text = "No pending friend requests.",
                    modifier = Modifier.padding(18.dp),
                    textAlign = TextAlign.Center,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pendingRequests.forEach { req ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerAvatar(
                                avatarPathOrUri = req.fromAvatarUrl,
                                displayName = req.fromDisplayName,
                                size = 42.dp,
                                username = req.fromUsername
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = req.fromDisplayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "wants to be your friend",
                                    fontSize = 11.5.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.6f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onAcceptFriendRequest(req) },
                                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Accept", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { onDeclineFriendRequest(req) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Ignore", fontSize = 12.sp, color = tokens.cellNeutralText)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 2. MATCH INVITATIONS SECTION ──
        Text(
            text = "MATCH INVITATIONS (${invites.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.cellNeutralText.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (loadingInvites) {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accentBrand, modifier = Modifier.size(24.dp))
            }
        } else if (invites.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.cellNeutralBg
            ) {
                Text(
                    text = "No pending game invites.",
                    modifier = Modifier.padding(18.dp),
                    textAlign = TextAlign.Center,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                invites.forEach { invite ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Invite from ${invite.fromDisplayName}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText
                                )
                                Text(
                                    text = "@${invite.fromUsername} invited you to room: #${invite.roomCode}",
                                    fontSize = 12.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.7f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(user.username, invite.roomCode)
                                        }
                                        invites = invites.filter { it.roomCode != invite.roomCode }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.padding(end = 6.dp)
                                ) {
                                    Text("Decline", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(user.username, invite.roomCode)
                                        }
                                        invites = invites.filter { it.roomCode != invite.roomCode }
                                        if (onAcceptInvite != null) {
                                            onAcceptInvite(invite)
                                        } else {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Bingo Room Code", invite.roomCode))
                                            Toast.makeText(context, "Room Code ${invite.roomCode} copied! Join Online Room.", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Accept & Play", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
