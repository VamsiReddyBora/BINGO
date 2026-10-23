package com.bingo.multiplayer.presentation.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.PlayerProfileQrPayload
import com.bingo.multiplayer.domain.network.QrCodeHelper
import com.bingo.multiplayer.presentation.common.PlayerAvatar

@Composable
fun ShareProfileDialog(
    user: UserProfile,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    val cleanUsername = user.username.ifBlank { user.playerId }.trim().lowercase().removePrefix("@")
    val shareLink = "https://vamsireddybora.github.io/BINGO/?user=$cleanUsername"

    val qrBitmap = remember(cleanUsername, user.uid) {
        val payload = PlayerProfileQrPayload(
            username = cleanUsername,
            uid = user.uid,
            displayName = user.displayName,
            level = user.level
        )
        val qrContent = QrCodeHelper.createProfileQrContent(payload)
        QrCodeHelper.generateQrBitmap(qrContent, sizePx = 480)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Share Player Profile",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = tokens.cellNeutralText.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Player Identity ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PlayerAvatar(
                        avatarPathOrUri = user.avatarBase64?.takeIf { it.isNotBlank() } ?: user.avatarUrl,
                        displayName = user.displayName,
                        size = 48.dp,
                        borderColor = tokens.accentBrand,
                        username = cleanUsername
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = user.displayName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                        Text(
                            text = "@$cleanUsername • Level ${user.level} ${user.rankTitle}",
                            fontSize = 11.5.sp,
                            color = tokens.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── QR Code Card ──
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Player Profile QR Code",
                            modifier = Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Scan with BINGO camera to add friend instantly",
                    fontSize = 11.sp,
                    color = tokens.textMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // ── Action Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Copy Link / Handle Button
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("BINGO Profile Handle", "@$cleanUsername")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied @$cleanUsername to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = tokens.cellNeutralText
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy @User",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.cellNeutralText
                        )
                    }

                    // System Share Sheet Button
                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Let's play BINGO together! Add my profile: @$cleanUsername\nPlay now: $shareLink"
                                )
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Share BINGO Profile")
                            context.startActivity(shareIntent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonBg,
                            contentColor = tokens.primaryButtonText
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = tokens.primaryButtonText
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Link",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.primaryButtonText
                        )
                    }
                }
            }
        }
    }
}
