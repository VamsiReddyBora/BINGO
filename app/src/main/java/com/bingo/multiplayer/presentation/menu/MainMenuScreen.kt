package com.bingo.multiplayer.presentation.menu

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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

/**
 * Very minimal, compact Main Menu Screen.
 * The bulky "Verified Google Account" banner has been removed.
 * Compact profile chip at the top navigates directly to Settings.
 */
@Composable
fun MainMenuScreen(
    authRepository: AuthRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onPlayAi: (difficulty: AiDifficulty) -> Unit,
    onPlayOnline: () -> Unit,
    onPlayNearbyNetwork: () -> Unit
) {
    val tokens = BingoTheme.colors
    val authState by authRepository.authState.collectAsState()
    val userProfile = (authState as? AuthState.Authenticated)?.user ?: UserProfile(
        uid = "guest",
        displayName = "Player"
    )

    var selectedAiDifficulty by remember { mutableStateOf(AiDifficulty.EASY) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
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
                Column {
                    Text(
                        text = "B I N G O",
                        style = BingoTheme.typography.logoTitle.copy(fontSize = 24.sp),
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = "MULTIPLAYER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.accentBrand
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Dashboard & Friends Button
                    Surface(
                        onClick = onNavigateToDashboard,
                        shape = RoundedCornerShape(20.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Insights,
                                contentDescription = "Dashboard",
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Dashboard",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Compact Profile & Settings Button
                    Surface(
                        onClick = onNavigateToSettings,
                        shape = CircleShape,
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Box(modifier = Modifier.padding(4.dp)) {
                            PlayerAvatar(
                                avatarPathOrUri = userProfile.avatarUrl,
                                displayName = userProfile.displayName,
                                size = 28.dp,
                                borderWidth = 1.dp,
                                borderColor = tokens.accentBrand,
                                username = userProfile.username
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Social & Friends Quick Hub Card ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDashboard() },
                shape = RoundedCornerShape(14.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.accentBrand.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Group, contentDescription = null, tint = tokens.accentBrand, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Friends & Social Hub", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
                            Text("Online status, friends list & 1-tap invites", fontSize = 11.sp, color = tokens.cellNeutralText.copy(alpha = 0.5f))
                        }
                    }
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tokens.cellNeutralText.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

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
                    color = tokens.cellNeutralText.copy(alpha = 0.45f)
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
                accentColor = tokens.accentOpponent,
                onClick = onPlayOnline
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── 3. Nearby Network Card (Compact) ──
            CompactMenuCard(
                title = "Nearby Network",
                description = "Zero-latency Wi-Fi & Hotspot peer discovery.",
                icon = Icons.Default.Wifi,
                accentColor = tokens.accentOrange,
                onClick = onPlayNearbyNetwork
            )

            Spacer(modifier = Modifier.height(24.dp))
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
                    tint = tokens.accentBrand,
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
                        color = tokens.cellNeutralText.copy(alpha = 0.55f)
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
                    containerColor = tokens.accentBrand
                )
            ) {
                Text(
                    text = "Start AI Game (${if (selectedDifficulty == AiDifficulty.EASY) "Easy" else "Master"})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
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
            .background(if (isSelected) tokens.surface else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.dp, tokens.accentBrand, RoundedCornerShape(8.dp))
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
            color = if (isSelected) tokens.cellNeutralText else tokens.cellNeutralText.copy(alpha = 0.5f)
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
                            color = accentColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
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
                    color = tokens.cellNeutralText.copy(alpha = 0.55f)
                )
            }
        }
    }
}

