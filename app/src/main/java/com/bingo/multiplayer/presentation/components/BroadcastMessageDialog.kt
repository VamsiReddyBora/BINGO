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

    val (badgeText, badgeColor, iconVector) = when (broadcast.type.uppercase(Locale.US)) {
        "MAINTENANCE" -> Triple("MAINTENANCE", Color(0xFFF59E0B), Icons.Default.Build)
        "ALERT" -> Triple("IMPORTANT NOTICE", Color(0xFFEF4444), Icons.Default.Warning)
        else -> Triple("ANNOUNCEMENT", tokens.accentBrand, Icons.Default.Campaign)
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
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Broadcast Title
                Text(
                    text = broadcast.title.ifBlank { "Announcement" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )

                // Subtitle / Author Info
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = tokens.accentBrand,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val dateFormatted = if (broadcast.timestamp > 0L) {
                        try {
                            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(broadcast.timestamp))
                        } catch (_: Exception) { "" }
                    } else ""
                    val authorText = if (dateFormatted.isNotBlank()) {
                        "Official • ${broadcast.author} • $dateFormatted"
                    } else {
                        "Official • ${broadcast.author}"
                    }
                    Text(
                        text = authorText,
                        fontSize = 11.sp,
                        color = tokens.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Message Body
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.badgeSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = broadcast.message,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = tokens.cellNeutralText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Button (Got it / Dismiss)
                Button(
                    onClick = { BroadcastMessageManager.dismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = badgeColor)
                ) {
                    Text(
                        text = "Got it",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
