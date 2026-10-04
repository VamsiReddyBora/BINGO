package com.bingo.multiplayer.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.BroadcastMessageManager
import com.bingo.multiplayer.domain.network.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BroadcastMessageDialog() {
    val activeBroadcast by BroadcastMessageManager.activeBroadcast.collectAsState()
    val updateState by AppUpdateManager.updateState.collectAsState()
    val tokens = BingoTheme.colors

    // Prevent overlapping dialogs: if an update is actively being shown or downloaded,
    // wait until the update dialog is dismissed before showing the broadcast message.
    if (updateState is UpdateState.UpdateAvailable ||
        updateState is UpdateState.Downloading ||
        updateState is UpdateState.ReadyToInstall
    ) {
        return
    }

    val broadcast = activeBroadcast ?: return

    val isDirect = broadcast.isDirectMessage
    val (badgeText, badgeColor, iconVector) = when {
        isDirect -> Triple("PERSONAL MESSAGE", Color(0xFF8B5CF6), Icons.Default.Campaign)
        broadcast.type.uppercase(Locale.US) == "MAINTENANCE" -> Triple("MAINTENANCE", Color(0xFFF59E0B), Icons.Default.Build)
        broadcast.type.uppercase(Locale.US) == "ALERT" -> Triple("IMPORTANT NOTICE", Color(0xFFEF4444), Icons.Default.Warning)
        else -> Triple("ANNOUNCEMENT", tokens.accentBrand, Icons.Default.Campaign)
    }

    // 3 Distinct popup sizes
    val dialogSize = broadcast.size.uppercase(Locale.US)
    val cardMaxWidth = when (dialogSize) {
        "COMPACT" -> 295.dp
        "EXPANDED" -> 380.dp
        else -> 340.dp
    }
    val cardPadding = when (dialogSize) {
        "COMPACT" -> 16.dp
        "EXPANDED" -> 26.dp
        else -> 22.dp
    }
    val iconBoxSize = when (dialogSize) {
        "COMPACT" -> 44.dp
        "EXPANDED" -> 62.dp
        else -> 54.dp
    }
    val iconSize = when (dialogSize) {
        "COMPACT" -> 22.dp
        "EXPANDED" -> 30.dp
        else -> 26.dp
    }
    val titleSize = when (dialogSize) {
        "COMPACT" -> 16.sp
        "EXPANDED" -> 20.sp
        else -> 18.sp
    }
    val bodyMinHeight = when (dialogSize) {
        "COMPACT" -> 65.dp
        "EXPANDED" -> 150.dp
        else -> 100.dp
    }
    val bodyMaxHeight = when (dialogSize) {
        "COMPACT" -> 110.dp
        "EXPANDED" -> 280.dp
        else -> 160.dp
    }
    val bodyFontSize = when (dialogSize) {
        "COMPACT" -> 12.sp
        "EXPANDED" -> 14.sp
        else -> 13.sp
    }
    val bodyLineHeight = when (dialogSize) {
        "COMPACT" -> 17.sp
        "EXPANDED" -> 21.sp
        else -> 19.sp
    }
    val buttonHeight = when (dialogSize) {
        "COMPACT" -> 38.dp
        "EXPANDED" -> 48.dp
        else -> 44.dp
    }

    Dialog(
        onDismissRequest = { BroadcastMessageManager.dismiss() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = tokens.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = cardMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .border(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(iconBoxSize)
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(iconSize)
                    )
                }

                Spacer(modifier = Modifier.height(if (dialogSize == "COMPACT") 10.dp else 14.dp))

                // Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = if (dialogSize == "COMPACT") 10.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(if (dialogSize == "COMPACT") 8.dp else 10.dp))

                // Broadcast Title
                Text(
                    text = broadcast.title.ifBlank { if (isDirect) "Personal Message" else "Announcement" },
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )

                // Subtitle / Author Info
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val dateFormatted = if (broadcast.timestamp > 0L) {
                        try {
                            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(broadcast.timestamp))
                        } catch (_: Exception) { "" }
                    } else ""
                    val authorText = if (isDirect) {
                        if (dateFormatted.isNotBlank()) "Direct Message from ${broadcast.author} • $dateFormatted" else "Direct Message from ${broadcast.author}"
                    } else if (dateFormatted.isNotBlank()) {
                        "Official • ${broadcast.author} • $dateFormatted"
                    } else {
                        "Official • ${broadcast.author}"
                    }
                    Text(
                        text = authorText,
                        fontSize = 10.5.sp,
                        color = tokens.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(if (dialogSize == "COMPACT") 12.dp else 16.dp))

                // Message Body Container
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.badgeSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = bodyMinHeight, max = bodyMaxHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(if (dialogSize == "COMPACT") 10.dp else 14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = broadcast.message,
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            color = tokens.cellNeutralText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (dialogSize == "COMPACT") 14.dp else 18.dp))

                // Action Button (Got it / Dismiss)
                Button(
                    onClick = { BroadcastMessageManager.dismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = badgeColor)
                ) {
                    Text(
                        text = "Got it",
                        color = Color.White,
                        fontSize = if (dialogSize == "COMPACT") 13.sp else 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
