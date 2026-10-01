package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.R
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class StampResultType {
    WON,
    LOST,
    DRAW
}

/**
 * Rough sketch & chalk font for authentic stamped / chalkboard texture.
 */
private val ChalkSketchFont = FontFamily(
    Font(R.font.cabin_sketch_bold, FontWeight.Bold)
)

/**
 * Authentic rubber-stamp style badge pasted with force from height into the empty space
 * between the top bar and the 5x5 board.
 * - Styled with rough chalk/sketch typography
 * - YOU'VE WON! in Stamped Green
 * - YOU LOST! in Stamped Red
 * - DRAW! in Stamped Yellow / Amber
 * Features: Double-line border, -6.5° rubber stamp angle, slamming impact animation,
 * and radial dust puff particles expanding upon impact.
 */
@Composable
fun VictoryStampBadge(
    resultType: StampResultType,
    modifier: Modifier = Modifier,
    animateStampDrop: Boolean = true
) {
    val haptic = LocalHapticFeedback.current

    // Visual attributes based on stamp type
    val (text, mainColor, bgColor) = when (resultType) {
        StampResultType.WON -> Triple(
            "YOU'VE WON!",
            Color(0xFF15803D), // Forest Stamped Green
            Color(0x1816A34A)
        )
        StampResultType.LOST -> Triple(
            "YOU LOST!",
            Color(0xFFDC2626), // Stamped Red
            Color(0x18DC2626)
        )
        StampResultType.DRAW -> Triple(
            "DRAW!",
            Color(0xFFD97706), // Stamped Amber / Yellow
            Color(0x18F59E0B)
        )
    }

    // Slam animation state: scale 2.6f down to 1.0f with heavy force
    val stampScale = remember { Animatable(if (animateStampDrop) 2.6f else 1.0f) }
    val stampAlpha = remember { Animatable(if (animateStampDrop) 0.0f else 1.0f) }
    var showDustEffect by remember { mutableStateOf(false) }

    LaunchedEffect(resultType, animateStampDrop) {
        if (animateStampDrop) {
            stampScale.snapTo(2.6f)
            stampAlpha.snapTo(0.0f)

            // Rapid forceful slam downwards
            launch {
                stampAlpha.animateTo(1.0f, tween(durationMillis = 180, easing = LinearEasing))
            }
            stampScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            )

            // Impact trigger: Haptic feedback + dust puff burst
            try {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Exception) {}
            showDustEffect = true

            // Subtle recoil bounce on impact
            stampScale.animateTo(
                targetValue = 0.96f,
                animationSpec = tween(durationMillis = 60, easing = FastOutLinearInEasing)
            )
            stampScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
        } else {
            stampScale.snapTo(1.0f)
            stampAlpha.snapTo(1.0f)
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize()
            .graphicsLayer {
                scaleX = stampScale.value
                scaleY = stampScale.value
                alpha = stampAlpha.value
                rotationZ = -6.5f // Classic authentic rubber stamp angle
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer Rubber Stamp Border (Thickness ~2.8dp)
        Box(
            modifier = Modifier
                .border(
                    width = 2.8.dp,
                    color = mainColor,
                    shape = RoundedCornerShape(4.dp)
                )
                .background(
                    color = bgColor,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(3.dp) // Gap between outer and inner border
        ) {
            // Inner Rubber Stamp Border (Thickness ~1.4dp)
            Box(
                modifier = Modifier
                    .border(
                        width = 1.4.dp,
                        color = mainColor,
                        shape = RoundedCornerShape(2.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ChalkSketchFont,
                    letterSpacing = 2.2.sp,
                    color = mainColor
                )
            }
        }

        // Dust / impact particles radiating outward upon slamming down
        if (showDustEffect) {
            StampDustPuff(color = mainColor)
        }
    }
}

/**
 * Dust puff particles that expand radially around the stamp upon impact and quickly fade away.
 */
@Composable
private fun StampDustPuff(color: Color) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
    }

    if (progress.value < 1f) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val p = progress.value
            val center = Offset(size.width / 2f, size.height / 2f)
            val dustCount = 14
            val baseRadius = size.width * 0.48f

            for (i in 0 until dustCount) {
                val angle = (i * (360f / dustCount) + (i * 17f % 30f)) * (Math.PI / 180f).toFloat()
                val dist = baseRadius + p * 38.dp.toPx() * (0.8f + (i % 3) * 0.25f)
                val x = center.x + cos(angle) * dist
                val y = center.y + sin(angle) * (dist * 0.65f) // Slightly oval around rectangular stamp
                val particleRadius = (4.dp.toPx() * (1f - p * 0.5f)).coerceAtLeast(1f)
                val alpha = ((1f - p) * 0.7f).coerceIn(0f, 1f)

                drawCircle(
                    color = Color(0xFF9CA3AF).copy(alpha = alpha), // Neutral dust gray
                    radius = particleRadius,
                    center = Offset(x, y)
                )
                // Also add tiny colored ink specks
                if (i % 2 == 0) {
                    drawCircle(
                        color = color.copy(alpha = alpha * 0.8f),
                        radius = particleRadius * 0.7f,
                        center = Offset(x - 2f, y - 2f)
                    )
                }
            }
        }
    }
}

/**
 * Kinematic projectile emoji particle data.
 */
private data class EmojiParticle(
    val emoji: String,
    val startX: Float,
    val startY: Float,
    val apexX: Float,
    val apexY: Float,
    val endX: Float,
    val endY: Float,
    val peakRatio: Float,
    val delayNanos: Long,
    val durationNanos: Long,
    val rotationTarget: Float,
    val fontSizeSp: Float,
    val horizontalSway: Float
)

/**
 * Fullscreen Emoji Projectile Burst:
 * - Staggered launch waves with small gap intervals to prevent dense clustering/bunching.
 * - Varied launch origins across lower half of screen (left, right, and center).
 * - High-reaching trajectories rising all the way up to the stamp area with varied random heights.
 * - Scattered descent across the entire bottom boundary with zero central overlap.
 * - Winner: Celebration confetti emojis (🎊 🎉 ✨ etc.).
 * - Loser: Defeat & cry emojis (🫪😑😐😵💫😵🤧🫩😩😖).
 * - Draw: Combined celebration + loser emojis.
 */
@Composable
fun GameOverEmojiProjectileBurst(
    resultType: StampResultType,
    modifier: Modifier = Modifier,
    onApexReached: () -> Unit = {},
    onBurstFinished: () -> Unit = {}
) {
    val winnerEmojis = listOf("🎊", "🎉", "✨", "⭐", "🏆", "🥳", "🎈", "🥇", "🌟", "🔥")
    val loserEmojis = listOf("🫪", "😑", "😐", "😵", "💫", "🤧", "🫩", "😩", "😖", "😭", "💔")
    val drawEmojis = listOf("🎊", "🎉", "✨", "🫪", "😑", "😐", "😵", "💫", "🤧", "🫩", "😩", "😖", "🤝")

    val emojiPool = when (resultType) {
        StampResultType.WON -> winnerEmojis
        StampResultType.LOST -> loserEmojis
        StampResultType.DRAW -> drawEmojis
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        // Create particles organized into sequential waves with small gap intervals
        val particles = remember(resultType) {
            val list = mutableListOf<EmojiParticle>()
            val count = 60
            val wavesCount = 6
            val waveSpacingMillis = 280L
            val random = Random(42)

            for (i in 0 until count) {
                val waveIndex = i / (count / wavesCount)
                val indexInWave = i % (count / wavesCount)
                val emoji = emojiPool[random.nextInt(emojiPool.size)]

                // Staggered launch delay with small gap intervals between waves
                val waveBaseDelay = waveIndex * waveSpacingMillis
                val intraWaveJitter = indexInWave * 20L + random.nextInt(-10, 25).toLong()
                val delayMillis = (waveBaseDelay + intraWaveJitter).coerceAtLeast(0L)
                val durationMillis = random.nextInt(1300, 1600).toLong()

                // 1. Varied launch origin: left-half, right-half, or bottom-center
                val sideChoice = random.nextInt(10)
                val (startX, startY) = when {
                    sideChoice < 5 -> {
                        // Left region (from far left edge to 42% width, from middle 45% height down to 96%)
                        val x = screenWidth * (0.01f + random.nextFloat() * 0.40f)
                        val y = screenHeight * (0.45f + random.nextFloat() * 0.52f)
                        Pair(x, y)
                    }
                    sideChoice < 9 -> {
                        // Right region (from 58% to 99% width, from middle 45% height down to 96%)
                        val x = screenWidth * (0.58f + random.nextFloat() * 0.41f)
                        val y = screenHeight * (0.45f + random.nextFloat() * 0.52f)
                        Pair(x, y)
                    }
                    else -> {
                        // Lower-mid region
                        val x = screenWidth * (0.30f + random.nextFloat() * 0.40f)
                        val y = screenHeight * (0.65f + random.nextFloat() * 0.32f)
                        Pair(x, y)
                    }
                }

                // 2. Varied apex spot and height: Shoot up to stamp area with random tiers
                val heightTier = random.nextInt(4)
                val apexY = when (heightTier) {
                    0 -> screenHeight * (0.04f + random.nextFloat() * 0.05f) // Level with/beside the stamp!
                    1 -> screenHeight * (0.09f + random.nextFloat() * 0.06f) // Just below stamp / B-I-N-G-O letters
                    2 -> screenHeight * (0.16f + random.nextFloat() * 0.07f) // Upper board (Row 0 / Row 1)
                    else -> screenHeight * (0.24f + random.nextFloat() * 0.08f) // Mid board (Row 2)
                }

                // Apex horizontal spot distributed across the full width of the screen
                val apexX = screenWidth * (0.04f + random.nextFloat() * 0.92f)

                // Landing position distributed across the entire bottom width
                val endX = screenWidth * (0.02f + random.nextFloat() * 0.96f)
                val endY = screenHeight * (1.02f + random.nextFloat() * 0.10f)

                val peakRatio = 0.44f + random.nextFloat() * 0.08f
                val rotationTarget = (random.nextFloat() - 0.5f) * 540f
                val fontSizeSp = (22f + random.nextFloat() * 12f)
                val horizontalSway = (random.nextFloat() - 0.5f) * screenWidth * 0.14f

                list.add(
                    EmojiParticle(
                        emoji = emoji,
                        startX = startX,
                        startY = startY,
                        apexX = apexX,
                        apexY = apexY,
                        endX = endX,
                        endY = endY,
                        peakRatio = peakRatio,
                        delayNanos = delayMillis * 1_000_000L,
                        durationNanos = durationMillis * 1_000_000L,
                        rotationTarget = rotationTarget,
                        fontSizeSp = fontSizeSp,
                        horizontalSway = horizontalSway
                    )
                )
            }
            list
        }

        var elapsedTimeNanos by remember { mutableFloatStateOf(0f) }
        var hasTriggeredApex by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val startFrame = withFrameNanos { it }
            var isRunning = true
            while (isRunning) {
                val currentFrame = withFrameNanos { it }
                val elapsed = (currentFrame - startFrame).toFloat()
                elapsedTimeNanos = elapsed

                // Apex timing: When wave 0 reaches top (~650ms) and begins descent
                if (!hasTriggeredApex && elapsed >= 650_000_000L) {
                    hasTriggeredApex = true
                    onApexReached()
                }

                // Finish celebration after ~3.2s
                if (elapsed >= 3_200_000_000L) {
                    isRunning = false
                    onBurstFinished()
                }
            }
        }

        // Render each flying emoji along its exact kinematic projectile path
        particles.forEach { p ->
            val particleElapsed = elapsedTimeNanos - p.delayNanos
            if (particleElapsed > 0) {
                val progress = (particleElapsed / p.durationNanos.toFloat()).coerceIn(0f, 1f)
                if (progress < 1f) {
                    val curX: Float
                    val curY: Float

                    if (progress <= p.peakRatio) {
                        // Rise Phase: Decelerates as it reaches the peak height (apexY)
                        val u = (progress / p.peakRatio).coerceIn(0f, 1f)
                        val riseFactor = sin(u * (Math.PI / 2.0).toFloat())
                        curY = p.startY - (p.startY - p.apexY) * riseFactor
                        curX = p.startX + (p.apexX - p.startX) * riseFactor
                    } else {
                        // Fall Phase: Accelerates under gravity towards the bottom (endY)
                        val v = ((progress - p.peakRatio) / (1f - p.peakRatio)).coerceIn(0f, 1f)
                        val fallFactor = v * v
                        curY = p.apexY + (p.endY - p.apexY) * fallFactor
                        curX = p.apexX + (p.endX - p.apexX) * v + sin(v * Math.PI.toFloat()) * p.horizontalSway
                    }

                    // Quick fade in at start, stay vibrant, fade out as it reaches bottom
                    val alpha = when {
                        progress < 0.06f -> progress / 0.06f
                        progress > 0.86f -> (1f - progress) / 0.14f
                        else -> 1f
                    }.coerceIn(0f, 1f)

                    val scale = when {
                        progress < 0.08f -> 0.4f + (progress / 0.08f) * 0.6f
                        progress > 0.90f -> (1f - progress) / 0.10f
                        else -> 1f
                    }.coerceAtLeast(0.1f)

                    val rotation = p.rotationTarget * progress

                    Text(
                        text = p.emoji,
                        fontSize = p.fontSizeSp.sp,
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = curX
                                translationY = curY
                                scaleX = scale
                                scaleY = scale
                                rotationZ = rotation
                                this.alpha = alpha
                            }
                    )
                }
            }
        }
    }
}
