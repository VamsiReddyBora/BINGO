package com.bingo.multiplayer.presentation.components

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.UpdateState
import java.util.Locale

@Composable
fun AppUpdateDialog() {
    val updateState by AppUpdateManager.updateState.collectAsState()
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    // Only display dialog for actionable states: update available, downloading, ready to install, or manual error
    if (updateState is UpdateState.Idle || updateState is UpdateState.UpToDate || updateState is UpdateState.Checking) {
        return
    }

    Dialog(
        onDismissRequest = {
            if (updateState !is UpdateState.Downloading) {
                AppUpdateManager.dismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = updateState !is UpdateState.Downloading,
            dismissOnClickOutside = updateState !is UpdateState.Downloading
        )
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
                when (val state = updateState) {

                    is UpdateState.UpdateAvailable -> {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(tokens.accentBrand.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Update Available!",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = tokens.accentBrand.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = state.info.latestVersionTag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.accentBrand,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (state.info.apkSize > 0) {
                            val sizeMb = String.format(Locale.US, "%.1f MB", state.info.apkSize / (1024f * 1024f))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Size: $sizeMb",
                                fontSize = 12.sp,
                                color = tokens.textMuted
                            )
                        }

                        if (state.info.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = tokens.badgeSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = "What's New:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tokens.cellNeutralText
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.info.releaseNotes,
                                        fontSize = 12.sp,
                                        color = tokens.textMuted,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { AppUpdateManager.dismiss() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Later", color = tokens.textMuted, fontSize = 14.sp)
                            }
                            Button(
                                onClick = {
                                    AppUpdateManager.startDownload(context, state.info.downloadUrl)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Update", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(tokens.accentBrand.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Downloading Update...",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = tokens.accentBrand,
                            trackColor = tokens.badgeSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        val percent = (state.progress * 100).toInt()
                        val dlMb = String.format(Locale.US, "%.1f", state.downloadedBytes / (1024f * 1024f))
                        val totalMb = String.format(Locale.US, "%.1f MB", state.totalBytes / (1024f * 1024f))
                        Text(
                            text = "$percent% ($dlMb / $totalMb)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.textMuted
                        )
                    }

                    is UpdateState.ReadyToInstall -> {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Download Complete! 🎉",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap install to complete the update. Your game data and settings will be preserved.",
                            fontSize = 13.sp,
                            color = tokens.textMuted,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { AppUpdateManager.dismiss() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Close", color = tokens.textMuted, fontSize = 14.sp)
                            }
                            Button(
                                onClick = {
                                    AppUpdateManager.installApk(context, state.apkFile)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("Install Now", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is UpdateState.Error -> {
                        Text(
                            text = "Update Check Failed",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            fontSize = 13.sp,
                            color = tokens.textMuted
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { AppUpdateManager.dismiss() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("OK")
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
