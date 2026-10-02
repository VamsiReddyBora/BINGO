package com.bingo.multiplayer.presentation.nearby

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.HotspotAndWifiManager
import com.bingo.multiplayer.domain.network.NearbyHostQrPayload
import com.bingo.multiplayer.domain.network.QrCodeHelper

@Composable
fun NearbyHostQrDisplayDialog(
    payload: NearbyHostQrPayload,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    var ssid by remember {
        mutableStateOf(payload.ssid.ifBlank { HotspotAndWifiManager.getHotspotName(context) })
    }
    var password by remember {
        mutableStateOf(payload.password.ifBlank { HotspotAndWifiManager.getSavedHotspotPassword(context) })
    }
    var isEditing by remember { mutableStateOf(false) }

    val stablePayload = remember(ssid, password, payload.roomCode, payload.hostIp, payload.hostName) {
        payload.copy(ssid = ssid, password = password)
    }

    val qrBitmap: Bitmap? = remember(stablePayload.ssid, stablePayload.password, stablePayload.roomCode) {
        try {
            val content = QrCodeHelper.createQrContent(stablePayload)
            QrCodeHelper.generateQrBitmap(content, sizePx = 480)
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
                    .padding(20.dp),
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
                            text = "Host QR Code",
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
                    text = "Scan this Wi-Fi QR from your friend's phone to connect to your hotspot and enter the match automatically.",
                    fontSize = 11.5.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // QR Code Image Container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.size(230.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Host QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = tokens.accentBrand)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Host network details card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tokens.backgroundSecondary,
                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hotspot SSID:",
                                fontSize = 11.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f)
                            )
                            Text(
                                text = ssid,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Password:",
                                fontSize = 11.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (password.isNotBlank()) password else "None / Open",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (password.isNotBlank()) tokens.accentBrand else tokens.cellNeutralText.copy(alpha = 0.5f)
                            )
                        }

                        if (isEditing) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = ssid,
                                onValueChange = { ssid = it },
                                label = { Text("Hotspot Name (SSID)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Hotspot Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    HotspotAndWifiManager.saveHotspotCredentials(context, ssid, password)
                                    isEditing = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg)
                            ) {
                                Text("Save Hotspot Info", color = tokens.primaryButtonText, fontSize = 12.sp)
                            }
                        } else {
                            TextButton(
                                onClick = { isEditing = true },
                                modifier = Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = tokens.accentBrand)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit Hotspot Info", fontSize = 11.sp, color = tokens.accentBrand)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg)
                ) {
                    Text(
                        text = "Done",
                        color = tokens.primaryButtonText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
