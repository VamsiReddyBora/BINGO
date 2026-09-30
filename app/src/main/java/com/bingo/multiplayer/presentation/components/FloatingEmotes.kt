package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import kotlin.math.PI
import kotlin.math.sin

val QUICK_EMOTES = listOf("🔥", "😱", "😂", "🎯", "👏")

data class FloatingEmoteItem(
    val id: Long = System.currentTimeMillis() + (0..100000).random(),
    val emoji: String,
    val startXRatio: Float = ((8..86).random() / 100f),
    val isSelf: Boolean = true,
    val senderName: String? = null,
    val swayAmplitude: Float = (14f + (0..28).random().toFloat()),
    val swayFrequency: Float = (2.0f + (0..25).random().toFloat() / 10f),
    val driftX: Float = (-35f + (0..70).random().toFloat()),
    val swayPhase: Float = ((0..60).random().toFloat() / 10f)
)

/**
 * Expandable Floating Emote Action Bar.
 * Allows quick one-tap emoji reactions (🔥, 😱, 😂, 🎯, 👏) during the match.
 */
@Composable
fun FloatingEmoteBar(
    onEmoteSelected: (String) -> Unit,
    customPhrases: List<String> = emptyList(),
    onPhraseSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        if (isExpanded) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QUICK_EMOTES.forEach { emoji ->
                            var isPressed by remember { mutableStateOf(false) }
                            val pressScale by animateFloatAsState(
                                targetValue = if (isPressed) 0.85f else 1.0f,
                                animationSpec = tween(durationMillis = 80),
                                label = "emotePressScale"
                            )

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .scale(pressScale)
                                    .clip(CircleShape)
                                    .background(tokens.backgroundSecondary)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                isPressed = true
                                                tryAwaitRelease()
                                                isPressed = false
                                            },
                                            onTap = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onEmoteSelected(emoji)
                                                isExpanded = false
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 20.sp)
                            }
                        }

                        // Close Button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { isExpanded = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Emotes",
                                tint = tokens.cellNeutralText.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Quick Chat Phrases (Option E)
                    if (customPhrases.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            customPhrases.take(4).chunked(2).forEach { rowPhrases ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    rowPhrases.forEach { phrase ->
                                        Surface(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onPhraseSelected(phrase)
                                                isExpanded = false
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = tokens.backgroundSecondary,
                                            border = BorderStroke(0.5.dp, tokens.surfaceBorder)
                                        ) {
                                            Text(
                                                text = phrase,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = tokens.cellNeutralText,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Collapsed Floating Button
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isExpanded = true
                },
                shape = CircleShape,
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 4.dp,
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💬",
                        fontSize = 19.sp
                    )
                }
            }
        }
    }
}

val ALL_REACTION_EMOJIS = listOf(
    // 1. Smiles and Emotions
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "🥹",
    "😊", "😇", "🙂", "😉", "😌", "😍", "🥰", "😘", "😋", "😛",
    "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳",
    "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😫", "😩",
    "🥺", "😢", "😭", "😮‍💨", "😤", "😠", "😡", "🤬", "🤯", "😳",
    "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫣",
    "🤭", "🫢", "🫡", "🤫", "🫠", "🤥", "😶", "😐", "😑", "😬",
    "🫨", "🥱", "😴", "🤤", "😵", "😵‍💫", "🤐", "🥴", "🤢", "🤮",
    "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👹", "👺",
    "🤡", "💩", "👻", "💀", "☠️", "👽", "👾", "🤖", "🎃",

    // 2. Person Emojis and Hand Gestures
    "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
    "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "🫵",
    "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐",
    "🤲", "🤝", "🙏", "✍️", "🤳", "💪", "👀", "👁️", "👅", "👄",
    "👶", "👦", "👧", "👨", "👩", "🧓", "👴", "👵", "🤦", "🤷",
    "👮", "🕵️", "💂", "🥷", "👷", "🤴", "👸", "👳", "👰", "🤰",
    "👼", "🎅", "🧙", "🧚", "🧛", "🧜", "🧝", "🧞", "🧟", "💃",
    "🕺", "🕴️", "🧗", "🧘", "🏃", "🚶",

    // 3. Activities and Events
    "🎉", "🎊", "🎈", "🎁", "🎀", "🪄", "🎟️", "🎫", "🏆", "🥇",
    "🥈", "🥉", "🏅", "🎖️", "⚽", "🏀", "🏈", "⚾", "🥎", "🎾",
    "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🏏",
    "🥅", "⛳", "🏹", "🎣", "🥊", "🥋", "🛹", "🛼", "🛷", "⛸️",
    "🎿", "⛷️", "🏂", "🏋️", "🤼", "🤸", "⛹️", "🤺", "🤾", "🏌️",
    "🏇", "🏄", "🏊", "🤽", "🚣", "🚵", "🚴", "🎪", "🎭", "🎨",
    "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸", "🎻",
    "🎲", "♟️", "🎯", "🎳", "🎮", "🎰", "🧩",

    // 4. Objects and Symbols
    "👑", "💍", "💎", "💡", "🔦", "🕯️", "💣", "🧨", "🪓", "🔪",
    "🗡️", "⚔️", "🛡️", "🏺", "🔮", "🧿", "🪬", "📿", "💈", "⚗️",
    "🔭", "🔬", "🩹", "🩺", "💊", "💉", "🧬", "🧹", "🧺", "🧻",
    "🧼", "🫧", "🪥", "🪒", "🧽", "🪣", "🧴", "🔑", "🗝️", "🚪",
    "🪑", "🛋️", "🛏️", "🧸", "🖼️", "🪞", "🪟", "🛍️", "🛒", "📱",
    "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "📷", "📸", "📹", "🎥",
    "📽️", "🎞️", "📞", "☎️", "📺", "📻", "🎙️", "🧭", "⏱️", "⏲️",
    "⏰", "🕰️", "⌛", "⏳", "📡", "🔋", "🪫", "🔌", "💵", "🪙",
    "💸", "💳", "🧾", "✉️", "📦", "📬", "📮", "📝", "📁", "📂",
    "📅", "📆", "📈", "📉", "📊", "📌", "📍", "📎", "🔒", "🔓",
    "🔏", "🔐", "🔨", "⚒️", "🛠️", "🔧", "🪛", "🔩", "⚙️", "⚖️",
    "🧲", "🚀", "🛸", "🔥", "⚡", "✨", "🌟", "💫", "💥", "🍿"
)

/**
 * Pill-shaped swipeable emoji reactions strip with quick chat trigger.
 * Horizontally scrollable library of emojis, recent items move to front,
 * borderless emoji buttons.
 */
@Composable
fun EmojiReactionStripWithChat(
    onSendEmote: (String) -> Unit,
    onToggleQuickChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    var emojiList by remember { mutableStateOf(ALL_REACTION_EMOJIS) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = tokens.surface.copy(alpha = 0.95f),
        border = BorderStroke(0.4.dp, tokens.surfaceBorder.copy(alpha = 0.35f)),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Horizontal scrolling emoji strip (swipes to left, recents move to front)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                emojiList.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Move tapped emoji to front of the list
                                emojiList = listOf(emoji) + (emojiList.filter { it != emoji })
                                onSendEmote(emoji)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 21.sp)
                    }
                }
            }

            // Subtle vertical separator
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(tokens.cellNeutralText.copy(alpha = 0.15f))
            )

            Spacer(modifier = Modifier.width(2.dp))

            // Quick chat trigger button (no border)
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleQuickChat()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Text(text = "💬", fontSize = 19.sp)
            }
        }
    }
}

/**
 * Animated Floating Emotes Overlay.
 * Renders floating reaction emojis rising from bottom to top with harmonic sway and fade-out.
 */
@Composable
fun FloatingEmotesOverlay(
    activeEmotes: List<FloatingEmoteItem>,
    onEmoteFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeEmotes.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        activeEmotes.forEach { emoteItem ->
            key(emoteItem.id) {
                SingleFloatingEmoteBubble(
                    item = emoteItem,
                    screenW = screenW.value,
                    screenH = screenH.value,
                    onFinished = { onEmoteFinished(emoteItem.id) }
                )
            }
        }
    }
}

@Composable
private fun SingleFloatingEmoteBubble(
    item: FloatingEmoteItem,
    screenW: Float,
    screenH: Float,
    onFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val tokens = BingoTheme.colors

    LaunchedEffect(item.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1800, easing = LinearEasing)
        )
        onFinished()
    }

    val p = progress.value

    // Physics calculations
    val startY = screenH * 0.78f
    val endY = screenH * 0.18f
    val currentY = startY + (endY - startY) * p

    val baseStartX = (screenW * item.startXRatio).coerceIn(30f, (screenW - 54f).coerceAtLeast(30f))
    // Highly randomized organic trajectory: every emote follows a unique path
    val swayX = sin((p * item.swayFrequency * PI) + item.swayPhase).toFloat() * item.swayAmplitude
    val currentX = (baseStartX + (item.driftX * p) + swayX).coerceIn(16f, (screenW - 54f).coerceAtLeast(16f))

    // Scale spring curve: pops to 1.35f, then settles at 1.05f
    val scale = when {
        p < 0.18f -> (p / 0.18f) * 1.35f
        p < 0.32f -> 1.35f - ((p - 0.18f) / 0.14f) * 0.30f
        else -> 1.05f
    }

    // Alpha: Solid up to 70%, then fades out
    val alpha = when {
        p < 0.08f -> p / 0.08f
        p > 0.70f -> (1f - (p - 0.70f) / 0.30f).coerceIn(0f, 1f)
        else -> 1f
    }

    Box(
        modifier = Modifier
            .offset(x = currentX.dp, y = currentY.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        val isPhrase = item.emoji.length > 3
        if (!isPhrase) {
            // Plain emoji with NO border!
            Text(
                text = item.emoji,
                fontSize = 32.sp
            )
        } else {
            // For text use a thin black border (no purple outline!)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.surface.copy(alpha = 0.96f),
                    border = BorderStroke(0.6.dp, Color.Black.copy(alpha = 0.85f)),
                    shadowElevation = 3.dp,
                    modifier = Modifier.wrapContentSize()
                ) {
                    Text(
                        text = "💬 ${item.emoji}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (!item.isSelf && !item.senderName.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.surface.copy(alpha = 0.85f),
                        border = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = item.senderName,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
