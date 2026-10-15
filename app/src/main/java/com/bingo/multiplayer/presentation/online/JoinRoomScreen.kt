package com.bingo.multiplayer.presentation.online

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.QrCodeHelper

/**
 * Simple screen for a player to enter a room code and join a host's lobby.
 */
@Composable
fun JoinRoomScreen(
    onJoinRoom: suspend (roomCode: String) -> String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var roomCode by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }

    fun triggerJoin(codeToJoin: String) {
        val clean = codeToJoin.trim().uppercase()
        if (!isValidating && clean.length == 6) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isValidating = true
            errorMessage = null
            coroutineScope.launch {
                val err = onJoinRoom(clean)
                if (err != null) {
                    errorMessage = err
                    isValidating = false
                }
            }
        }
    }

    if (showQrScannerDialog) {
        RoomQrScannerDialog(
            onDismiss = { showQrScannerDialog = false },
            onRoomScanned = { scannedCode ->
                showQrScannerDialog = false
                roomCode = scannedCode
                errorMessage = null
                triggerJoin(scannedCode)
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

                Text(
                    text = "Join a Game",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Card with room code input
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ENTER ROOM CODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Ask the host for the 6-character room code to join their game, or scan their room QR code.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { input ->
                            val clean = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            roomCode = clean
                            errorMessage = null
                            if (clean.length == 6 && !isValidating) {
                                triggerJoin(clean)
                            }
                        },
                        placeholder = {
                            Text(
                                text = "ABC123",
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 4.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.25f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentOpponent,
                            unfocusedBorderColor = tokens.surfaceBorder
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (roomCode.length == 6) {
                                    triggerJoin(roomCode)
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Helpers: Paste from Clipboard & Scan QR Code
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clipText = if ((clipboard?.primaryClip?.itemCount ?: 0) > 0) {
                                    clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                } else ""
                                val extracted = QrCodeHelper.extractRoomCode(clipText)
                                if (!extracted.isNullOrBlank() && extracted.length == 6) {
                                    roomCode = extracted
                                    errorMessage = null
                                    Toast.makeText(context, "Pasted code $extracted!", Toast.LENGTH_SHORT).show()
                                    triggerJoin(extracted)
                                } else {
                                    Toast.makeText(context, "No 6-character room code in clipboard", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Paste", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tokens.cellNeutralText)
                        }

                        OutlinedButton(
                            onClick = {
                                showQrScannerDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (tokens.isDark) Color.White else tokens.accentBrand
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tokens.cellNeutralText)
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = tokens.accentOpponent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, tokens.accentOpponent.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = tokens.accentOpponent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val isJoinEnabled = roomCode.length == 6 && !isValidating
                    Button(
                        onClick = {
                            if (roomCode.length == 6) {
                                triggerJoin(roomCode)
                            }
                        },
                        enabled = isJoinEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonBg,
                            contentColor = tokens.primaryButtonText,
                            disabledContainerColor = if (tokens.isDark) Color(0xFF27272A) else Color(0xFFE2E8F0),
                            disabledContentColor = tokens.textMuted
                        )
                    ) {
                        if (isValidating) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = tokens.primaryButtonText,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isValidating) "Validating Room…" else "Join Room",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isJoinEnabled) tokens.primaryButtonText else tokens.textMuted
                        )
                    }
                }
            }
        }
    }
}
