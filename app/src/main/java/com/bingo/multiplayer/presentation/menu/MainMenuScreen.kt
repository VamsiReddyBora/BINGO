package com.bingo.multiplayer.presentation.menu

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.isActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.platform.LocalContext
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.AiDifficulty
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.presentation.common.PlayerAvatar

import androidx.compose.foundation.layout.PaddingValues
import com.bingo.multiplayer.domain.network.OngoingMatchData
import com.bingo.multiplayer.domain.network.OngoingMatchStore

/**
 * Very minimal, compact Main Menu Screen.
 * The bulky "Verified Google Account" banner has been removed.
 * Compact profile chip at the top navigates directly to Settings.
 */
@Composable
fun MainMenuScreen(
    authRepository: AuthRepository,
    friendsRepository: com.bingo.multiplayer.domain.repository.FriendsRepository? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit = {},
    onPlayAi: (difficulty: AiDifficulty) -> Unit,
    onPlayOnline: () -> Unit,
    onPlayNearbyNetwork: () -> Unit,
    onOpenDeveloperNote: () -> Unit = {},
    onRejoinMatch: ((OngoingMatchData) -> Unit)? = null
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    val authState by authRepository.authState.collectAsState()
    val userProfile = (authState as? AuthState.Authenticated)?.user ?: UserProfile(
        uid = "guest",
        displayName = "Player"
    )

    var selectedAiDifficulty by remember { mutableStateOf(AiDifficulty.EASY) }
    var ongoingMatch by remember { mutableStateOf(OngoingMatchStore.getOngoingMatch(context)) }

    LaunchedEffect(Unit) {
        ongoingMatch = OngoingMatchStore.getOngoingMatch(context)
    }

    // Proactive background down-moment pre-fetching:
    // Seamlessly load friends, requests, and cloud presence while user is on homescreen
    LaunchedEffect(userProfile.username) {
        val cleanUser = userProfile.username.trim().lowercase().removePrefix("@")
        if (cleanUser.isNotBlank() && cleanUser != "guest") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val repo = friendsRepository ?: com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance
                    repo?.syncFriendsAndRequests(cleanUser)
                    val friendUsernames = repo?.friends?.value?.map { it.username }?.filter { it.isNotBlank() } ?: emptyList()
                    if (friendUsernames.isNotEmpty()) {
                        com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresenceForUsers(friendUsernames)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(ongoingMatch?.roomCode) {
        val ongoing = ongoingMatch ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val session = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.getRoom(ongoing.roomCode)
                    val now = System.currentTimeMillis()
                    // Only clear if the room was successfully queried AND is confirmed closed or dead
                    if (session != null && (session.status == "CLOSED" || (now - session.lastHeartbeat) > 60_000L || session.players.isEmpty())) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            OngoingMatchStore.clearOngoingMatch(context)
                            ongoingMatch = null
                        }
                    }
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(5000L)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        val screenHeight = maxHeight
        // Estimated height of content up to bottom of Nearby Network card:
        // Top padding 20.dp + Top bar 36.dp + Spacers ~40.dp + AI card ~92.dp + Online card ~72.dp + Nearby card ~72.dp ~= 332.dp
        // If ongoing match is present, it adds ~88.dp.
        val topContentHeight = if (ongoingMatch != null) 420.dp else 332.dp
        // Floating bottom navigation pill clearance from bottom: pill height (~52.dp) + bottom padding (16.dp) + margin = ~80.dp
        val navPillClearance = 80.dp
        val buttonHeight = 40.dp
        // Available space between the Nearby Network card and the Floating Bottom Navigation Pill:
        val availableSpace = (screenHeight - topContentHeight - navPillClearance - buttonHeight).coerceAtLeast(32.dp)
        // Split available space equally above and below so the pill button is centered in the gap
        val middleSpacing = availableSpace / 2
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Minimal Compact Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "B I N G O",
                    style = BingoTheme.typography.logoTitle.copy(fontSize = 24.sp),
                    color = tokens.cellNeutralText
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quick Theme Toggle (Light / AMOLED Dark Mode)
                    Surface(
                        onClick = {
                            ThemePreferences.setDarkTheme(context, !tokens.isDark)
                        },
                        shape = CircleShape,
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = if (tokens.isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (tokens.isDark) "Switch to Light Theme" else "Switch to Dark Theme",
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Compact Profile & Settings Button
                    Surface(
                        onClick = onNavigateToSettings,
                        shape = CircleShape,
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Box(modifier = Modifier.padding(3.dp)) {
                            PlayerAvatar(
                                avatarPathOrUri = userProfile.avatarUrl,
                                displayName = userProfile.displayName,
                                size = 36.dp,
                                borderWidth = 1.2.dp,
                                borderColor = tokens.accentBrand,
                                username = userProfile.username
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Ongoing Match Card (Rejoin) ──
            if (ongoingMatch != null) {
                val ongoing = ongoingMatch!!
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (tokens.isDark) Color(0xFF1E242B) else Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF2563EB).copy(alpha = 0.6f) else Color(0xFF93C5FD)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Top Section: Match In Progress status & Room Code
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "MATCH IN PROGRESS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = Color(0xFF10B981)
                                )
                            }
                            Text(
                                text = "Room ${ongoing.roomCode}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom Section: Dismiss and Rejoin buttons side by side
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    OngoingMatchStore.clearOngoingMatch(context)
                                    ongoingMatch = null
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF475569) else Color(0xFFCBD5E1)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Dismiss",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.textSecondary
                                )
                            }

                            Button(
                                onClick = {
                                    onRejoinMatch?.invoke(ongoing)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2563EB),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Rejoin",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── Section Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SELECT MODE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tokens.textMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 1. Play vs AI Card (Compact) ──
            CompactAiModeCard(
                selectedDifficulty = selectedAiDifficulty,
                onDifficultyChanged = { selectedAiDifficulty = it },
                onPlayClicked = { onPlayAi(selectedAiDifficulty) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── 2. Online Match Card (Compact) ──
            CompactMenuCard(
                title = "Online Match",
                description = "Host or join multiplayer rooms with friends.",
                icon = Icons.Default.CastConnected,
                accentColor = if (tokens.isDark) Color.White else tokens.accentOpponent,
                onClick = onPlayOnline
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── 3. Nearby Network Card (Compact) ──
            CompactMenuCard(
                title = "Nearby Network",
                description = "Zero-latency Wi-Fi & Hotspot peer discovery.",
                icon = Icons.Default.Wifi,
                accentColor = if (tokens.isDark) Color.White else tokens.accentOrange,
                onClick = onPlayNearbyNetwork
            )

            // Dynamic vertical gap placing the developer note pill directly in the middle
            // between the Nearby Network card and the bottom navigation pill:
            Spacer(modifier = Modifier.height(middleSpacing))

            // Developer Note Pill Button
            Surface(
                onClick = onOpenDeveloperNote,
                shape = RoundedCornerShape(50),
                color = if (tokens.isDark) Color(0xFF18181B) else Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, tokens.surfaceBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "developer note ☕",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.cellNeutralText
                    )
                }
            }

            // Symmetrical bottom spacing + navPillClearance so distance from button to nav pill is exactly middleSpacing
            Spacer(modifier = Modifier.height(middleSpacing + navPillClearance))
        }
    }
}

/**
 * Clean, compact AI card with Easy/Master difficulty selection.
 */
@Composable
private fun CompactAiModeCard(
    selectedDifficulty: AiDifficulty,
    onDifficultyChanged: (AiDifficulty) -> Unit,
    onPlayClicked: () -> Unit
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = if (tokens.isDark) Color.White else tokens.accentBrand,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Play vs AI",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = "Solo practice match with bot",
                        fontSize = 12.sp,
                        color = tokens.textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Segmented Difficulty Toggle
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = tokens.backgroundSecondary,
                border = BorderStroke(1.dp, tokens.surfaceBorder.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactDifficultyChip(
                        title = "Easy Bot",
                        isSelected = selectedDifficulty == AiDifficulty.EASY,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDifficultyChanged(AiDifficulty.EASY)
                        }
                    )

                    CompactDifficultyChip(
                        title = "Master Bot",
                        isSelected = selectedDifficulty == AiDifficulty.HARD,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDifficultyChanged(AiDifficulty.HARD)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Start Match Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlayClicked()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.primaryButtonBg
                )
            ) {
                Text(
                    text = "Start AI Game (${if (selectedDifficulty == AiDifficulty.EASY) "Easy" else "Master"})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = tokens.primaryButtonText
                )
            }
        }
    }
}

@Composable
private fun CompactDifficultyChip(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) (if (tokens.isDark) Color(0xFF222222) else tokens.surface) else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.dp, if (tokens.isDark) Color.White else tokens.accentBrand, RoundedCornerShape(8.dp))
                else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            color = if (isSelected) tokens.cellNeutralText else tokens.textMuted
        )
    }
}

/**
 * Compact minimal menu card for Online Room and Nearby Network.
 */
@Composable
private fun CompactMenuCard(
    title: String,
    badgeText: String? = null,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "menuCardScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    if (!badgeText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = tokens.badgeSurface,
                            border = BorderStroke(1.dp, tokens.badgeOutline)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.badgeContent,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = tokens.textMuted
                )
            }
        }
    }
}

