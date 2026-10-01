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

enum class CelebrationAnimStyle(val id: Int, val title: String, val subtitle: String) {
    STYLE_1(1, "Clock-Arc Crossfire", "4-8 o'clock arc, multi-directional diagonal crossfire"),
    STYLE_2(2, "Dual Corner Cannons", "Twin bottom-corner cannons firing to top center"),
    STYLE_3(3, "Center Vortex Geyser", "Bottom-center volcanic spiral fountain erupting upward"),
    STYLE_4(4, "Sky Rainstorm Cascade", "Top cloud confetti fluttering and rocking downward"),
    STYLE_5(5, "Radial Starburst Blast", "360° explosive shockwave outward from center")
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
 * Kinematic projectile emoji particle data supporting all 5 distinct celebration styles.
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
    val horizontalSway: Float,
    val styleType: Int = 0, // 0: Clock-Arc Crossfire, 1: Cannons flutter, 2: Spiral geyser, 3: Cloud rainstorm, 4: Radial shockwave
    val angleRad: Float = 0f,
    val radiusParam: Float = 0f
)

/**
 * Fullscreen Emoji Projectile Burst supporting 5 genuinely distinct animation styles:
 * 1. Clock-Arc Crossfire: 4-8 o'clock lower arc, multi-directional diagonal crossfire
 * 2. Dual Corner Cannons: Twin bottom corners firing to top center to collide and flutter down
 * 3. Center Vortex Geyser: Bottom-center volcanic spiral fountain erupting upward
 * 4. Sky Rainstorm Cascade: Top cloud confetti fluttering and rocking downward
 * 5. Radial Starburst Blast: 360° explosive shockwave outward from center
 */
@Composable
fun GameOverEmojiProjectileBurst(
    resultType: StampResultType,
    modifier: Modifier = Modifier,
    style: CelebrationAnimStyle = CelebrationAnimStyle.STYLE_1,
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

        // Generate particles uniquely tailored to the selected animation style
        val particles = remember(resultType, style) {
            val list = mutableListOf<EmojiParticle>()
            val random = Random(42)

            when (style) {
                CelebrationAnimStyle.STYLE_1 -> {
                    // Style 1: Clock-Arc Crossfire (Arc from 4 to 8 o'clock, multi-directional crossfire)
                    val count = 52
                    for (i in 0 until count) {
                        val emoji = emojiPool[random.nextInt(emojiPool.size)]
                        val archetype = i % 4
                        val clockAngleDeg = when (archetype) {
                            0 -> 110f + random.nextFloat() * 45f // 7 to 8 o'clock
                            1 -> 25f + random.nextFloat() * 45f  // 4 to 5 o'clock
                            else -> 25f + random.nextFloat() * 130f // 4 to 8 o'clock
                        }
                        val clockAngleRad = (clockAngleDeg * (Math.PI / 180.0)).toFloat()
                        val clockRadiusX = screenWidth * (0.42f + random.nextFloat() * 0.08f)
                        val clockRadiusY = screenHeight * (0.40f + random.nextFloat() * 0.08f)
                        val clockCenterX = screenWidth * 0.50f
                        val clockCenterY = screenHeight * 0.52f

                        val startX = (clockCenterX + clockRadiusX * cos(clockAngleRad)).coerceIn(screenWidth * 0.02f, screenWidth * 0.98f)
                        val startY = (clockCenterY + clockRadiusY * sin(clockAngleRad)).coerceIn(screenHeight * 0.66f, screenHeight * 0.98f)

                        val apexX: Float
                        val apexY: Float
                        val endX: Float
                        val endY: Float

                        when (archetype) {
                            0 -> { // 7/8 to 2/3 o'clock
                                apexX = screenWidth * (0.64f + random.nextFloat() * 0.28f)
                                apexY = screenHeight * (0.16f + random.nextFloat() * 0.22f)
                                endX = screenWidth * (0.76f + random.nextFloat() * 0.26f)
                                endY = screenHeight * (0.92f + random.nextFloat() * 0.12f)
                            }
                            1 -> { // 4/5 to 8/9/10 o'clock
                                apexX = screenWidth * (0.08f + random.nextFloat() * 0.28f)
                                apexY = screenHeight * (0.16f + random.nextFloat() * 0.22f)
                                endX = screenWidth * (-0.05f + random.nextFloat() * 0.26f)
                                endY = screenHeight * (0.92f + random.nextFloat() * 0.12f)
                            }
                            2 -> { // High Skyward Rockets to Stamp
                                apexX = screenWidth * (0.12f + random.nextFloat() * 0.76f)
                                apexY = screenHeight * (0.04f + random.nextFloat() * 0.08f)
                                endX = screenWidth * (0.08f + random.nextFloat() * 0.84f)
                                endY = screenHeight * (0.95f + random.nextFloat() * 0.15f)
                            }
                            else -> { // Mid-Board Fountains
                                apexX = screenWidth * (0.18f + random.nextFloat() * 0.64f)
                                apexY = screenHeight * (0.22f + random.nextFloat() * 0.16f)
                                endX = screenWidth * (0.08f + random.nextFloat() * 0.84f)
                                endY = screenHeight * (0.92f + random.nextFloat() * 0.15f)
                            }
                        }

                        val speedTier = i % 3
                        val (durationMillis, peakRatio) = when (speedTier) {
                            0 -> Pair(random.nextInt(920, 1150).toLong(), 0.35f + random.nextFloat() * 0.03f)
                            1 -> Pair(random.nextInt(1220, 1460).toLong(), 0.43f + random.nextFloat() * 0.04f)
                            else -> Pair(random.nextInt(1550, 1850).toLong(), 0.48f + random.nextFloat() * 0.04f)
                        }
                        val delayMillis = random.nextInt(0, 30).toLong()

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
                                rotationTarget = (random.nextFloat() - 0.5f) * 600f,
                                fontSizeSp = (20f + random.nextFloat() * 14f),
                                horizontalSway = (random.nextFloat() - 0.5f) * screenWidth * 0.10f,
                                styleType = 0
                            )
                        )
                    }
                }
                CelebrationAnimStyle.STYLE_2 -> {
                    // Style 2: Dual Corner Cannons (Twin bottom corners fire upward to collide at top center)
                    val count = 50
                    for (i in 0 until count) {
                        val emoji = emojiPool[random.nextInt(emojiPool.size)]
                        val isLeftCannon = (i % 2 == 0)
                        val startX = if (isLeftCannon) {
                            screenWidth * (0.02f + random.nextFloat() * 0.08f)
                        } else {
                            screenWidth * (0.90f + random.nextFloat() * 0.08f)
                        }
                        val startY = screenHeight * (0.92f + random.nextFloat() * 0.06f)

                        // Both cannons aim inward and high up to collide near top center
                        val apexX = screenWidth * (0.38f + random.nextFloat() * 0.24f)
                        val apexY = screenHeight * (0.05f + random.nextFloat() * 0.10f)

                        // Descent spreads out across the entire screen width
                        val endX = screenWidth * (0.04f + random.nextFloat() * 0.92f)
                        val endY = screenHeight * (1.02f + random.nextFloat() * 0.08f)

                        // Machine-gun rapid cannon volley pairs over ~450ms
                        val volleyIndex = i / 2
                        val delayMillis = (volleyIndex * 18L) + random.nextInt(0, 8)
                        val durationMillis = random.nextInt(1550, 1850).toLong()

                        list.add(
                            EmojiParticle(
                                emoji = emoji,
                                startX = startX,
                                startY = startY,
                                apexX = apexX,
                                apexY = apexY,
                                endX = endX,
                                endY = endY,
                                peakRatio = 0.40f + random.nextFloat() * 0.04f,
                                delayNanos = delayMillis * 1_000_000L,
                                durationNanos = durationMillis * 1_000_000L,
                                rotationTarget = (random.nextFloat() - 0.5f) * 720f,
                                fontSizeSp = (22f + random.nextFloat() * 12f),
                                horizontalSway = screenWidth * (0.08f + random.nextFloat() * 0.08f),
                                styleType = 1,
                                angleRad = (random.nextFloat() * Math.PI * 2.0).toFloat()
                            )
                        )
                    }
                }
                CelebrationAnimStyle.STYLE_3 -> {
                    // Style 3: Center Vortex Geyser (Bottom center spiral fountain erupting upward)
                    val count = 54
                    for (i in 0 until count) {
                        val emoji = emojiPool[random.nextInt(emojiPool.size)]
                        val startX = screenWidth * (0.45f + random.nextFloat() * 0.10f)
                        val startY = screenHeight * (0.94f + random.nextFloat() * 0.05f)

                        val apexX = screenWidth * (0.25f + random.nextFloat() * 0.50f)
                        val apexY = screenHeight * (0.06f + random.nextFloat() * 0.16f)

                        val endX = screenWidth * (0.05f + random.nextFloat() * 0.90f)
                        val endY = screenHeight * (1.02f + random.nextFloat() * 0.08f)

                        val delayMillis = random.nextInt(0, 160).toLong()
                        val durationMillis = random.nextInt(1400, 1700).toLong()
                        val angleRad = (i * 35f * (Math.PI / 180.0)).toFloat()
                        val radiusParam = screenWidth * (0.22f + random.nextFloat() * 0.22f)

                        list.add(
                            EmojiParticle(
                                emoji = emoji,
                                startX = startX,
                                startY = startY,
                                apexX = apexX,
                                apexY = apexY,
                                endX = endX,
                                endY = endY,
                                peakRatio = 0.38f + random.nextFloat() * 0.04f,
                                delayNanos = delayMillis * 1_000_000L,
                                durationNanos = durationMillis * 1_000_000L,
                                rotationTarget = (random.nextFloat() - 0.5f) * 600f,
                                fontSizeSp = (22f + random.nextFloat() * 12f),
                                horizontalSway = screenWidth * 0.06f,
                                styleType = 2,
                                angleRad = angleRad,
                                radiusParam = radiusParam
                            )
                        )
                    }
                }
                CelebrationAnimStyle.STYLE_4 -> {
                    // Style 4: Sky Rainstorm Cascade (Top cloud confetti fluttering and rocking down)
                    val count = 56
                    for (i in 0 until count) {
                        val emoji = emojiPool[random.nextInt(emojiPool.size)]
                        val startX = screenWidth * (0.04f + random.nextFloat() * 0.92f)
                        val startY = screenHeight * (-0.04f + random.nextFloat() * 0.06f)

                        val apexX = startX
                        val apexY = startY
                        val endX = (startX + (random.nextFloat() - 0.5f) * screenWidth * 0.25f).coerceIn(0f, screenWidth)
                        val endY = screenHeight * (1.04f + random.nextFloat() * 0.08f)

                        // Cascading rain drop timings over ~700ms
                        val delayMillis = (i * 14L) + random.nextInt(0, 15)
                        val durationMillis = random.nextInt(1650, 2100).toLong()
                        val angleRad = (random.nextFloat() * Math.PI * 2.0).toFloat()

                        list.add(
                            EmojiParticle(
                                emoji = emoji,
                                startX = startX,
                                startY = startY,
                                apexX = apexX,
                                apexY = apexY,
                                endX = endX,
                                endY = endY,
                                peakRatio = 0.10f,
                                delayNanos = delayMillis * 1_000_000L,
                                durationNanos = durationMillis * 1_000_000L,
                                rotationTarget = (random.nextFloat() - 0.5f) * 480f,
                                fontSizeSp = (22f + random.nextFloat() * 12f),
                                horizontalSway = screenWidth * (0.07f + random.nextFloat() * 0.09f),
                                styleType = 3,
                                angleRad = angleRad
                            )
                        )
                    }
                }
                CelebrationAnimStyle.STYLE_5 -> {
                    // Style 5: Radial Starburst Blast (360° explosive shockwave outward from center)
                    val count = 52
                    for (i in 0 until count) {
                        val emoji = emojiPool[random.nextInt(emojiPool.size)]
                        val startX = screenWidth * 0.50f
                        val startY = screenHeight * 0.38f // Center / stamp epicenter

                        val apexX = startX
                        val apexY = startY
                        val endX = startX
                        val endY = screenHeight * (1.02f + random.nextFloat() * 0.08f)

                        val angleRad = (i * (360f / count) + random.nextFloat() * 7f) * (Math.PI / 180.0).toFloat()
                        val radiusParam = screenWidth * (0.35f + random.nextFloat() * 0.30f)

                        // Instantaneous simultaneous detonation: 0ms delay!
                        val delayMillis = 0L
                        val durationMillis = random.nextInt(1250, 1500).toLong()

                        list.add(
                            EmojiParticle(
                                emoji = emoji,
                                startX = startX,
                                startY = startY,
                                apexX = apexX,
                                apexY = apexY,
                                endX = endX,
                                endY = endY,
                                peakRatio = 0.40f,
                                delayNanos = delayMillis * 1_000_000L,
                                durationNanos = durationMillis * 1_000_000L,
                                rotationTarget = (random.nextFloat() - 0.5f) * 540f,
                                fontSizeSp = (23f + random.nextFloat() * 13f),
                                horizontalSway = screenWidth * 0.05f,
                                styleType = 4,
                                angleRad = angleRad,
                                radiusParam = radiusParam
                            )
                        )
                    }
                }
            }
            list
        }

        var elapsedTimeNanos by remember { mutableFloatStateOf(0f) }
        var hasTriggeredApex by remember { mutableStateOf(false) }

        val (apexTimeNanos, totalDurationNanos) = remember(style) {
            when (style) {
                CelebrationAnimStyle.STYLE_1 -> Pair(500_000_000L, 1_950_000_000L) // Clock-Arc Crossfire (~1.95s)
                CelebrationAnimStyle.STYLE_2 -> Pair(600_000_000L, 2_250_000_000L) // Dual Corner Cannons (~2.25s)
                CelebrationAnimStyle.STYLE_3 -> Pair(450_000_000L, 1_900_000_000L) // Center Vortex Geyser (~1.90s)
                CelebrationAnimStyle.STYLE_4 -> Pair(200_000_000L, 2_500_000_000L) // Sky Rainstorm Cascade (~2.50s)
                CelebrationAnimStyle.STYLE_5 -> Pair(400_000_000L, 1_600_000_000L) // Radial Starburst Blast (~1.60s)
            }
        }

        LaunchedEffect(resultType, style) {
            elapsedTimeNanos = 0f
            hasTriggeredApex = false
            val startFrame = withFrameNanos { it }
            var isRunning = true
            while (isRunning) {
                val currentFrame = withFrameNanos { it }
                val elapsed = (currentFrame - startFrame).toFloat()
                elapsedTimeNanos = elapsed

                // Style-dependent apex timing
                if (!hasTriggeredApex && elapsed >= apexTimeNanos) {
                    hasTriggeredApex = true
                    onApexReached()
                }

                // Finish celebration when all style emojis have landed
                if (elapsed >= totalDurationNanos) {
                    isRunning = false
                    onBurstFinished()
                }
            }
        }

        // Render each flying emoji along its exact kinematic projectile path for its style
        particles.forEach { p ->
            val particleElapsed = elapsedTimeNanos - p.delayNanos
            if (particleElapsed > 0) {
                val progress = (particleElapsed / p.durationNanos.toFloat()).coerceIn(0f, 1f)
                if (progress < 1f) {
                    val curX: Float
                    val curY: Float

                    when (p.styleType) {
                        0 -> {
                            // Style 1: Clock-Arc Crossfire (Ballistic Rise & Fall)
                            if (progress <= p.peakRatio) {
                                val u = (progress / p.peakRatio).coerceIn(0f, 1f)
                                val riseFactor = sin(u * (Math.PI / 2.0).toFloat())
                                curY = p.startY - (p.startY - p.apexY) * riseFactor
                                curX = p.startX + (p.apexX - p.startX) * riseFactor
                            } else {
                                val v = ((progress - p.peakRatio) / (1f - p.peakRatio)).coerceIn(0f, 1f)
                                val fallFactor = v * v
                                curY = p.apexY + (p.endY - p.apexY) * fallFactor
                                curX = p.apexX + (p.endX - p.apexX) * v + sin(v * Math.PI.toFloat()) * p.horizontalSway
                            }
                        }
                        1 -> {
                            // Style 2: Dual Corner Cannons (Angled Cannon Volley + Flutter Sway)
                            if (progress <= p.peakRatio) {
                                val u = (progress / p.peakRatio).coerceIn(0f, 1f)
                                val riseFactor = sin(u * (Math.PI / 2.0).toFloat())
                                curY = p.startY - (p.startY - p.apexY) * riseFactor
                                curX = p.startX + (p.apexX - p.startX) * riseFactor
                            } else {
                                val v = ((progress - p.peakRatio) / (1f - p.peakRatio)).coerceIn(0f, 1f)
                                val fallFactor = v * v * 0.8f + v * 0.2f
                                curY = p.apexY + (p.endY - p.apexY) * fallFactor
                                curX = p.apexX + (p.endX - p.apexX) * v + sin(v * (Math.PI * 4.0).toFloat() + p.angleRad) * p.horizontalSway
                            }
                        }
                        2 -> {
                            // Style 3: Center Vortex Geyser (Bottom Center Volcanic Spiral Fountain)
                            if (progress <= p.peakRatio) {
                                val u = (progress / p.peakRatio).coerceIn(0f, 1f)
                                val riseFactor = sin(u * (Math.PI / 2.0).toFloat())
                                val baseY = p.startY - (p.startY - p.apexY) * riseFactor
                                val spiralR = p.radiusParam * (u * u)
                                curX = p.startX + sin(p.angleRad + u * (Math.PI * 4.0).toFloat()) * spiralR
                                curY = baseY
                            } else {
                                val v = ((progress - p.peakRatio) / (1f - p.peakRatio)).coerceIn(0f, 1f)
                                val fallFactor = v * v
                                curY = p.apexY + (p.endY - p.apexY) * fallFactor
                                val outwardSpread = (p.endX - p.startX) * v
                                curX = p.apexX + outwardSpread + sin(p.angleRad + (1f + v) * (Math.PI * 2.0).toFloat()) * (p.radiusParam * (1f - v * 0.4f))
                            }
                        }
                        3 -> {
                            // Style 4: Sky Rainstorm Cascade (Top Cloud Confetti Shower Floating Down)
                            if (progress < 0.10f) {
                                val popProg = progress / 0.10f
                                val popJump = sin(popProg * Math.PI.toFloat()) * 30f
                                curY = p.startY - popJump
                                curX = p.startX + (p.endX - p.startX) * progress
                            } else {
                                val v = ((progress - 0.10f) / 0.90f).coerceIn(0f, 1f)
                                val fallFactor = 0.5f * v + 0.5f * (v * v)
                                curY = p.startY + (p.endY - p.startY) * fallFactor
                                curX = p.startX + (p.endX - p.startX) * v + cos(v * (Math.PI * 4.0).toFloat() + p.angleRad) * p.horizontalSway
                            }
                        }
                        else -> {
                            // Style 5: Radial Starburst Blast (360° Outward Shockwave Detonation)
                            if (progress <= 0.40f) {
                                val u = (progress / 0.40f).coerceIn(0f, 1f)
                                val easeOut = 1f - (1f - u) * (1f - u) * (1f - u)
                                curX = p.startX + cos(p.angleRad) * p.radiusParam * easeOut
                                curY = p.startY + sin(p.angleRad) * (p.radiusParam * 0.85f) * easeOut
                            } else {
                                val v = ((progress - 0.40f) / 0.60f).coerceIn(0f, 1f)
                                val gravityDrop = v * v * (p.endY - p.startY)
                                curX = p.startX + cos(p.angleRad) * p.radiusParam + sin(v * (Math.PI * 2.0).toFloat()) * p.horizontalSway
                                curY = p.startY + sin(p.angleRad) * (p.radiusParam * 0.85f) + gravityDrop
                            }
                        }
                    }

                    // Quick fade in at start, stay vibrant, fade out as it reaches end
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
