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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.EmojiPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val swayPhase: Float = ((0..60).random().toFloat() / 10f),
    val scaleMultiplier: Float = 1.0f
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
                                AnimatedEmoji(emoji = emoji, fontSize = 20.sp)
                            }
                        }

                        // Close Button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(tokens.backgroundSecondary)
                                .clickable { isExpanded = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = tokens.cellNeutralText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Optional Quick-Chat Messages inside Floating Emote Bar
                    if (customPhrases.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            customPhrases.take(4).forEach { phrase ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = tokens.backgroundSecondary,
                                    border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onPhraseSelected(phrase)
                                            isExpanded = false
                                        }
                                ) {
                                    Text(
                                        text = phrase,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tokens.cellNeutralText,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Collapsed Floating Button (Shows a reaction emoji)
            Surface(
                shape = CircleShape,
                color = tokens.surface,
                border = BorderStroke(1.5.dp, tokens.accentBrand),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isExpanded = true
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    AnimatedEmoji(emoji = "🔥", fontSize = 22.sp)
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
 * - FAVORITES ALWAYS SHOW FIRST: configured in Settings, pinned at the very start of the strip.
 * - RECENTLY PICKED EMOJIS follow immediately after favorites.
 * - ANIMATED EMOJIS EVERYWHERE: WhatsApp / Telegram dynamic animated emoji stickers.
 * - Interactive long-press scaling from 1.0x to 2.85x with haptic milestone notches.
 */
@Composable
fun EmojiReactionStripWithChat(
    onSendEmote: (String, Float) -> Unit,
    onToggleQuickChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var emojiList by remember { mutableStateOf(EmojiPreferences.getComposedReactionStrip(context)) }

    var pressingEmoji by remember { mutableStateOf<String?>(null) }
    var pressingScale by remember { mutableFloatStateOf(1.0f) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = tokens.surface.copy(alpha = 0.95f),
            border = BorderStroke(0.4.dp, tokens.surfaceBorder.copy(alpha = 0.35f)),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Horizontal scrolling emoji strip
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    emojiList.forEach { emoji ->
                        val isBeingPressed = pressingEmoji == emoji
                        val localScale = if (isBeingPressed) (pressingScale * 0.40f + 0.60f).coerceIn(1.0f, 1.45f) else 1.0f

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .pointerInput(emoji) {
                                    detectTapGestures(
                                        onPress = {
                                            var currentScale = 1.0f
                                            var isLongPress = false
                                            var lastHapticTier = 0

                                            val growJob = coroutineScope.launch {
                                                delay(160)
                                                isLongPress = true
                                                val holdStart = System.currentTimeMillis()
                                                while (isActive) {
                                                    val elapsed = System.currentTimeMillis() - holdStart
                                                    val progress = (elapsed / 1400f).coerceIn(0f, 1f)
                                                    currentScale = 1.0f + 1.85f * progress

                                                    pressingEmoji = emoji
                                                    pressingScale = currentScale

                                                    val tier = when {
                                                        currentScale >= 2.8f -> 3
                                                        currentScale >= 2.0f -> 2
                                                        currentScale >= 1.5f -> 1
                                                        else -> 0
                                                    }
                                                    if (tier > lastHapticTier) {
                                                        lastHapticTier = tier
                                                        try {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        } catch (_: Exception) {}
                                                    }
                                                    delay(16)
                                                }
                                            }

                                            val released = tryAwaitRelease()
                                            growJob.cancel()
                                            pressingEmoji = null
                                            pressingScale = 1.0f

                                            if (released) {
                                                try {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                } catch (_: Exception) {}

                                                val finalScale = if (isLongPress) currentScale else 1.0f
                                                onSendEmote(emoji, finalScale)

                                                // Record used emoji for subsequent sessions without jumping active items under thumb
                                                EmojiPreferences.recordUsedEmoji(context, emoji)
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedEmoji(
                                emoji = emoji,
                                fontSize = 21.sp,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = localScale
                                    scaleY = localScale
                                }
                            )
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

                // Quick chat trigger button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleQuickChat()
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    coroutineScope.launch {
                                        scrollState.animateScrollTo(
                                            value = 0,
                                            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                        )
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "💬", fontSize = 19.sp)
                }
            }
        }

        // Live Magnification Preview Bubble showing the growing animated emoji in real-time
        AnimatedVisibility(
            visible = pressingEmoji != null && pressingScale > 1.08f,
            enter = fadeIn(tween(90)) + scaleIn(tween(110)),
            exit = fadeOut(tween(90)) + scaleOut(tween(110)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-68).dp)
        ) {
            if (pressingEmoji != null) {
                val previewScale = (pressingScale * 0.72f).coerceIn(1.0f, 2.1f)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .graphicsLayer {
                            scaleX = previewScale
                            scaleY = previewScale
                        }
                ) {
                    AnimatedEmoji(
                        emoji = pressingEmoji!!,
                        fontSize = (32 * previewScale).sp
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen overlay that renders all currently floating reaction emotes.
 */
@Composable
fun FloatingEmotesOverlay(
    activeEmotes: List<FloatingEmoteItem>,
    onEmoteFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeEmotes.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = maxWidth.value
        val screenH = maxHeight.value

        activeEmotes.forEach { emoteItem ->
            key(emoteItem.id) {
                SingleFloatingEmoteBubble(
                    item = emoteItem,
                    screenW = screenW,
                    screenH = screenH,
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
            animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
        )
        onFinished()
    }

    val p = progress.value

    val startY = screenH * 0.82f
    val endY = screenH * 0.18f
    val currentY = startY + (endY - startY) * p

    val baseStartX = (screenW * item.startXRatio).coerceIn(30f, (screenW - 54f).coerceAtLeast(30f))
    val swayX = sin((p * item.swayFrequency * PI) + item.swayPhase).toFloat() * item.swayAmplitude
    val boundPad = (18f * item.scaleMultiplier).coerceIn(16f, 48f)
    val currentX = (baseStartX + (item.driftX * p) + swayX).coerceIn(boundPad, (screenW - boundPad).coerceAtLeast(boundPad))

    val baseScale = when {
        p < 0.18f -> (p / 0.18f) * 1.35f
        p < 0.32f -> 1.35f - ((p - 0.18f) / 0.14f) * 0.30f
        else -> 1.05f
    }
    val scale = baseScale * item.scaleMultiplier

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
            Text(
                text = item.emoji,
                fontSize = 32.sp
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.surface.copy(alpha = 0.96f),
                    border = BorderStroke(0.6.dp, tokens.surfaceBorder),
                    shadowElevation = 3.dp,
                    modifier = Modifier.wrapContentSize()
                ) {
                    val displayText = if (item.emoji.startsWith("💬") || item.emoji.startsWith("📢") || item.emoji.startsWith("🟢") || item.emoji.startsWith("👑") || item.emoji == "Your turn") {
                        item.emoji
                    } else {
                        "💬 ${item.emoji}"
                    }
                    Text(
                        text = displayText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.cellNeutralText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (!item.isSelf && !item.senderName.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.surface.copy(alpha = 0.85f),
                        border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = item.senderName,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
