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
 * - Mental model: Phone screen as a clock face.
 * - Launch Origin: Arc from 4 o'clock through 6 o'clock (bottom) to 8 o'clock.
 * - Simultaneous Launch: All emojis shoot at once (near-zero delay) across the 4-8 o'clock arc.
 * - Multi-directional Crossfire & Diverse Destinations:
 *   1. Left-to-Right diagonal crossfire (7/8 o'clock -> 2/3 o'clock).
 *   2. Right-to-Left diagonal crossfire (4/5 o'clock -> 8/9/10 o'clock).
 *   3. High skyward rockets (towards 11/12/1 o'clock - Stamp Area).
 *   4. Mid-board fountains and short pop arcs.
 * - Natural Staggering via Variable Projectile Speeds and Path Lengths:
 *   Short fast projectiles reach apex (~350ms) and descend while longer skyward projectiles
 *   are still climbing, preventing bunching or clustering even if sharing a destination!
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

        // Create particles distributed across clock arc from 4 o'clock to 8 o'clock
        val particles = remember(resultType) {
            val list = mutableListOf<EmojiParticle>()
            val count = 52
            val random = Random(42)

            for (i in 0 until count) {
                val emoji = emojiPool[random.nextInt(emojiPool.size)]

                // Multi-directional crossfire archetypes:
                // 0: Left-to-Right cross-screen (7/8 o'clock -> 2/3 o'clock)
                // 1: Right-to-Left cross-screen (4/5 o'clock -> 8/9/10 o'clock)
                // 2: High Skyward Rockets (towards 11/12/1 o'clock - Stamp Area)
                // 3: Mid-Board Fountains & Short Pop Arcs
                val archetype = i % 4

                // 1. Clock angle in degrees: 0° = 3 o'clock, 90° = 6 o'clock (bottom), 180° = 9 o'clock
                // 4 o'clock = ~30°, 5 o'clock = ~60°, 6 o'clock = 90°, 7 o'clock = ~120°, 8 o'clock = ~150°
                val clockAngleDeg = when (archetype) {
                    0 -> 110f + random.nextFloat() * 45f // 7 to 8 o'clock (bottom-left to lower-left)
                    1 -> 25f + random.nextFloat() * 45f  // 4 to 5 o'clock (lower-right to bottom-right)
                    2 -> 25f + random.nextFloat() * 130f // Full 4 to 8 o'clock arc
                    else -> 25f + random.nextFloat() * 130f // Full 4 to 8 o'clock arc
                }

                val clockAngleRad = (clockAngleDeg * (Math.PI / 180.0)).toFloat()
                val clockRadiusX = screenWidth * (0.42f + random.nextFloat() * 0.08f)
                val clockRadiusY = screenHeight * (0.40f + random.nextFloat() * 0.08f)
                val clockCenterX = screenWidth * 0.50f
                val clockCenterY = screenHeight * 0.52f

                val startX = (clockCenterX + clockRadiusX * cos(clockAngleRad))
                    .coerceIn(screenWidth * 0.02f, screenWidth * 0.98f)
                val startY = (clockCenterY + clockRadiusY * sin(clockAngleRad))
                    .coerceIn(screenHeight * 0.66f, screenHeight * 0.98f)

                // 2. Destinations & Apex positions based on crossfire archetype
                val apexX: Float
                val apexY: Float
                val endX: Float
                val endY: Float

                when (archetype) {
                    0 -> {
                        // Left-to-Right diagonal crossfire (7/8 o'clock -> 2/3 o'clock)
                        apexX = screenWidth * (0.64f + random.nextFloat() * 0.28f)
                        apexY = screenHeight * (0.16f + random.nextFloat() * 0.22f)
                        endX = screenWidth * (0.76f + random.nextFloat() * 0.26f)
                        endY = screenHeight * (0.92f + random.nextFloat() * 0.12f)
                    }
                    1 -> {
                        // Right-to-Left diagonal crossfire (4/5 o'clock -> 8/9/10 o'clock)
                        apexX = screenWidth * (0.08f + random.nextFloat() * 0.28f)
                        apexY = screenHeight * (0.16f + random.nextFloat() * 0.22f)
                        endX = screenWidth * (-0.05f + random.nextFloat() * 0.26f)
                        endY = screenHeight * (0.92f + random.nextFloat() * 0.12f)
                    }
                    2 -> {
                        // High Skyward Rockets (towards 11/12/1 o'clock - Stamp Area)
                        apexX = screenWidth * (0.12f + random.nextFloat() * 0.76f)
                        apexY = screenHeight * (0.04f + random.nextFloat() * 0.08f) // Stamp altitude!
                        endX = screenWidth * (0.08f + random.nextFloat() * 0.84f)
                        endY = screenHeight * (0.95f + random.nextFloat() * 0.15f)
                    }
                    else -> {
                        // Mid-Board Fountains & Short Pop Arcs
                        apexX = screenWidth * (0.18f + random.nextFloat() * 0.64f)
                        apexY = screenHeight * (0.22f + random.nextFloat() * 0.16f)
                        endX = screenWidth * (0.08f + random.nextFloat() * 0.84f)
                        endY = screenHeight * (0.92f + random.nextFloat() * 0.15f)
                    }
                }

                // 3. Variable projectile speeds and path lengths:
                // Speed tier 0: Short fast path (apex ~350ms, descends early)
                // Speed tier 1: Medium path (apex ~560ms)
                // Speed tier 2: Long soaring path (apex ~820ms)
                // -> Emojis arriving at the same coordinate naturally stagger in time without bunching!
                val speedTier = i % 3
                val (durationMillis, peakRatio) = when (speedTier) {
                    0 -> Pair(
                        random.nextInt(920, 1150).toLong(),
                        0.35f + random.nextFloat() * 0.03f
                    )
                    1 -> Pair(
                        random.nextInt(1220, 1460).toLong(),
                        0.43f + random.nextFloat() * 0.04f
                    )
                    else -> Pair(
                        random.nextInt(1550, 1850).toLong(),
                        0.48f + random.nextFloat() * 0.04f
                    )
                }

                // 4. Simultaneous launch: all emojis shoot at once (near-zero delay)
                val delayMillis = random.nextInt(0, 30).toLong()

                val rotationTarget = (random.nextFloat() - 0.5f) * 600f
                val fontSizeSp = (20f + random.nextFloat() * 14f)
                val horizontalSway = (random.nextFloat() - 0.5f) * screenWidth * 0.10f

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

                // Apex timing: When high soaring emojis reach the stamp altitude (~500ms)
                if (!hasTriggeredApex && elapsed >= 500_000_000L) {
                    hasTriggeredApex = true
                    onApexReached()
                }

                // Finish celebration when all emojis have landed (~1.95s)
                if (elapsed >= 1_950_000_000L) {
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
