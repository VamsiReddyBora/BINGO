package com.bingo.multiplayer.presentation.nearby

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme

/**
 * Screen asking the player to choose their role for Nearby Network (LAN):
 * Host Game or Join Game (identical in philosophy to Online Match choice).
 */
@Composable
fun NearbyChoiceScreen(
    onHostGame: () -> Unit,
    onJoinGame: () -> Unit,
    onBack: () -> Unit
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                Column {
                    Text(
                        text = "Nearby Network",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = "Zero-latency Wi-Fi & Hotspot • No Room Codes",
                        fontSize = 11.5.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.55f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "HOW DO YOU WANT TO PLAY?",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = tokens.cellNeutralText.copy(alpha = 0.45f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Host a Game Card
            RoleCard(
                icon = Icons.Default.WifiTethering,
                title = "Host a Game",
                description = "Broadcast a match on Wi-Fi or Hotspot and let nearby friends join directly.",
                accentColor = tokens.accentBrand,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHostGame()
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Join a Game Card
            RoleCard(
                icon = Icons.Default.Wifi,
                title = "Join a Game",
                description = "Discover nearby friends hosting on Wi-Fi or Hotspot and join with 1 tap.",
                accentColor = tokens.accentOpponent,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onJoinGame()
                }
            )
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val tokens = BingoTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.55f)
                )
            }
        }
    }
}
