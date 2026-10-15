package com.bingo.multiplayer.presentation.social

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import com.bingo.multiplayer.presentation.common.PlayerProfileDialog
import kotlinx.coroutines.launch

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
    var showShareProfileDialog by remember { mutableStateOf(false) }
    var selectedScorecardMatch by remember { mutableStateOf<MatchRecord?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }

    val friends by friendsRepository.friends.collectAsState()
    val pendingRequests by friendsRepository.pendingRequests.collectAsState()
    val sentRequests by friendsRepository.sentRequestUsernames.collectAsState()
    val presenceMap by PresenceManager.presenceFlow.collectAsState()
    val firestoreMatches by remember(user.uid) {
        authRepository.streamFirestoreMatchHistory() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val localMatches = remember { authRepository.getMatchHistory() }
    val matchHistory = remember(localMatches, firestoreMatches) {
        if (firestoreMatches.isEmpty()) {
            localMatches
        } else {
            (localMatches + firestoreMatches)
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
        }
    }

    // Persist matches fetched from Firestore locally so they load in 0ms on next app open
    LaunchedEffect(firestoreMatches) {
        if (firestoreMatches.isNotEmpty()) {
            authRepository.persistMatchHistory(matchHistory)
        }
    }

    // Friend search state
    var searchUsernameInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var foundPlayer by remember { mutableStateOf<PlayerRegistryEntry?>(null) }
    var searchAttempted by remember { mutableStateOf(false) }

    // Initial sync runs ONCE per user session without being cancelled by friends list updates
    LaunchedEffect(user.username, user.uid) {
        if (user.username.isNotBlank()) {
            com.bingo.multiplayer.domain.network.LeaderboardSyncManager.startBootSync(
                context = context,
                username = user.username,
                uid = user.uid,
                friendsRepository = friendsRepository,
                authRepository = authRepository
            )
            friendsRepository.syncFriendsAndRequests(user.username)
            PresenceManager.startPresence(user.username)
            PresenceManager.startPresenceWatcher()
        }
    }

    // Refresh presence for current friends and search results
    LaunchedEffect(friends, foundPlayer) {
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
                        friends = friends,
                        matchHistory = matchHistory,
                        onShareProfile = { showShareProfileDialog = true },
                        onSelectScorecardMatch = { record -> selectedScorecardMatch = record },
                        onViewProfile = { profile -> selectedProfilePlayer = profile }
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
                        onOpenQrScanner = { showQrScannerDialog = true },
                        isAlreadyFriend = { uidOrUsername -> friendsRepository.isFriend(uidOrUsername) },
                        hasSentRequest = { username -> sentRequests.contains(username.trim().lowercase().removePrefix("@")) },
                        onSendFriendRequest = { entry ->
                            coroutineScope.launch {
                                val success = friendsRepository.sendFriendRequest(
                                    targetUsername = entry.username,
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

        // ── Dialogs ──
        if (showShareProfileDialog) {
            ShareProfileDialog(
                user = user,
                onDismiss = { showShareProfileDialog = false }
            )
        }

        selectedScorecardMatch?.let { scorecardRecord ->
            MatchScorecardDialog(
                record = scorecardRecord,
                onDismiss = { selectedScorecardMatch = null },
                onRematch = { opponentNameStr ->
                    selectedScorecardMatch = null
                    val friendMatch = friends.find {
                        it.username.equals(opponentNameStr, ignoreCase = true) ||
                        it.displayName.equals(opponentNameStr, ignoreCase = true) ||
                        (scorecardRecord.opponentUid.isNotBlank() && it.uid == scorecardRecord.opponentUid)
                    }
                    if (friendMatch != null) {
                        onInviteFriendToMatch(friendMatch)
                    } else {
                        val adHoc = Friend(
                            uid = scorecardRecord.opponentUid.ifBlank { opponentNameStr },
                            displayName = opponentNameStr,
                            username = opponentNameStr
                        )
                        onInviteFriendToMatch(adHoc)
                    }
                },
                onViewProfile = { opponentNameStr ->
                    selectedScorecardMatch = null
                    coroutineScope.launch {
                        val cleanName = opponentNameStr.trim().lowercase().removePrefix("@")
                        val found = authRepository.sessionManager.searchPlayerByUsername(cleanName, forceRefresh = false)
                        if (found != null) {
                            selectedProfilePlayer = PlayerProfileData.fromRegistryEntry(found)
                        } else {
                            selectedProfilePlayer = PlayerProfileData(
                                playerId = scorecardRecord.opponentUid.ifBlank { opponentNameStr },
                                username = opponentNameStr,
                                displayName = opponentNameStr,
                                avatarUrl = null
                            )
                        }
                    }
                }
            )
        }

        if (showQrScannerDialog) {
            ProfileQrScannerDialog(
                onDismiss = { showQrScannerDialog = false },
                onProfileScanned = { payload ->
                    showQrScannerDialog = false
                    searchUsernameInput = payload.username
                    isSearching = true
                    coroutineScope.launch {
                        foundPlayer = authRepository.sessionManager.searchPlayerByUsername(payload.username, forceRefresh = true)
                        isSearching = false
                        val entry = foundPlayer
                        if (entry != null) {
                            selectedProfilePlayer = PlayerProfileData.fromRegistryEntry(entry)
                        } else {
                            selectedProfilePlayer = PlayerProfileData(
                                playerId = payload.uid,
                                username = payload.username,
                                displayName = payload.displayName,
                                avatarUrl = null
                            )
                        }
                    }
                }
            )
        }

        val profileToDisplay = selectedProfilePlayer
        if (profileToDisplay != null) {
            PlayerProfileDialog(
                playerData = profileToDisplay,
                currentUser = user,
                friendsRepository = friendsRepository,
                matchHistory = matchHistory,
                onChallengeFriend = { friend ->
                    selectedProfilePlayer = null
                    onInviteFriendToMatch(friend)
                },
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
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() },
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
                tint = if (selected) (if (tokens.isDark) Color.White else tokens.accentBrand) else tokens.textMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) (if (tokens.isDark) Color.White else tokens.cellNeutralText) else tokens.textMuted
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.badgeSurface,
                    border = BorderStroke(1.dp, tokens.badgeOutline)
                ) {
                    Text(
                        text = "$badgeCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.badgeContent,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}
