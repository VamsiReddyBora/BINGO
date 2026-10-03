package com.bingo.multiplayer.presentation.menu

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.FeedbackManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen Developer Note & Feedback Screen.
 * Displays a heartfelt gratitude message for Bingo lovers and allows
 * users to type and transmit bugs/suggestions directly to the developer.
 */
@Composable
fun DeveloperNoteScreen(
    currentUser: UserProfile?,
    onBack: () -> Unit
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var messageText by remember { mutableStateOf("") }
    var isTransmitting by remember { mutableStateOf(false) }

    // Intercept hardware back button & gestures with smooth return
    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // ── Top Navigation Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBack()
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(tokens.surface)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = tokens.cellNeutralText,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Text(
                    text = "Developer note ☕",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Heartfelt Gratitude & Developer Love Card ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "❤️",
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Made for Bingo lovers",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Thank you so much for playing Bingo Multiplayer! This game was created out of pure love to bring the nostalgic joy of paper Bingo into a fast, real-time multiplayer experience with friends and players worldwide.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 20.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.9f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "If you are facing any bugs, strange glitches, or have ideas and suggestions to make the gameplay better, kindly reach out below. Every word you type is sent directly to me, and your feedback will directly shape our upcoming updates!",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 20.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.9f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "— With gratitude & warmth ☕\nVamsi Reddy Bora",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Large Message Input Field ──
            Text(
                text = "YOUR MESSAGE OR SUGGESTION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = tokens.textMuted
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = {
                    Text(
                        text = "Type any bugs, issues, suggestions, or thoughts here...",
                        fontSize = 13.5.sp,
                        color = tokens.textMuted
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 280.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = tokens.surface,
                    unfocusedContainerColor = tokens.surface,
                    focusedBorderColor = if (tokens.isDark) Color.White else tokens.accentBrand,
                    unfocusedBorderColor = tokens.surfaceBorder,
                    focusedTextColor = tokens.cellNeutralText,
                    unfocusedTextColor = tokens.cellNeutralText
                ),
                maxLines = 10
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Transmit Button ──
            Button(
                onClick = {
                    val clean = messageText.trim()
                    if (clean.isBlank()) {
                        Toast.makeText(context, "Please type a message before transmitting 💌", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isTransmitting) return@Button

                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isTransmitting = true

                    coroutineScope.launch {
                        // Background backup to Email and Cloud storage
                        FeedbackManager.transmitFeedback(
                            context = context,
                            username = currentUser?.username ?: "player",
                            displayName = currentUser?.displayName ?: "Player",
                            message = clean
                        )

                        // Open direct WhatsApp chat to +918688869780
                        FeedbackManager.openDirectWhatsAppChat(
                            context = context,
                            username = currentUser?.username ?: "player",
                            displayName = currentUser?.displayName ?: "Player",
                            message = clean
                        )

                        isTransmitting = false
                        Toast.makeText(context, "Transmitted with love! 💌 Opening WhatsApp...", Toast.LENGTH_LONG).show()
                        messageText = ""
                        delay(600L)
                        onBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.primaryButtonBg,
                    contentColor = tokens.primaryButtonText
                ),
                enabled = !isTransmitting
            ) {
                if (isTransmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = tokens.primaryButtonText,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Transmitting...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.primaryButtonText
                    )
                } else {
                    Text(
                        text = "Transmit 💌",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.primaryButtonText
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
