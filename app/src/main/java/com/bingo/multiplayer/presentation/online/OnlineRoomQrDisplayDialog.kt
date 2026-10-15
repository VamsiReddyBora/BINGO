package com.bingo.multiplayer.presentation.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.OnlineRoomQrPayload
import com.bingo.multiplayer.domain.network.QrCodeHelper

/**
 * Clean, modern dialog presenting a scannable QR code for an Online Bingo match.
 * Players nearby or in video calls can scan this QR code directly using the Bingo camera scanner.
 */
@Composable
fun OnlineRoomQrDisplayDialog(
    roomCode: String,
    hostName: String = "",
    boardSize: Int = 5,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    val qrBitmap: Bitmap? = remember(roomCode, hostName, boardSize) {
        try {
            val payload = OnlineRoomQrPayload(
                roomCode = roomCode,
                hostName = hostName,
                boardSize = boardSize
            )
            val content = QrCodeHelper.createRoomQrContent(payload)
            QrCodeHelper.generateQrBitmap(content, sizePx = 512)
        } catch (_: Exception) {
            null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = null,
                            tint = tokens.accentBrand,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Room QR Code",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = tokens.cellNeutralText.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Scan with the Bingo in-app camera or enter room code below to join.",
                    fontSize = 12.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // QR Code Container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(2.dp, tokens.accentBrand.copy(alpha = 0.4f)),
                    shadowElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Room QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = tokens.accentBrand
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Room Code Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.backgroundSecondary,
                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ROOM CODE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = tokens.accentBrand
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = roomCode,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp,
                            color = tokens.cellNeutralText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Copy & Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Bingo Room Code", roomCode))
                            Toast.makeText(context, "Room code $roomCode copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "Join my Bingo room! Room code: $roomCode")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Room Code"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonBg,
                            contentColor = tokens.primaryButtonText
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = tokens.primaryButtonText
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.primaryButtonText)
                    }
                }
            }
        }
    }
}
