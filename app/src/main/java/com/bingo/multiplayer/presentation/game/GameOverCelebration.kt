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
 * Radial Starburst emoji particle data for the 360° celebration blast.
 */
private data class RadialBurstParticle(
    val emoji: String,
    val startX: Float,
    val startY: Float,
    val angleRad: Float,
    val radiusX: Float,
    val radiusY: Float,
    val delayNanos: Long,
    val durationNanos: Long,
    val peakRatio: Float,
    val rotationTarget: Float,
    val fontSizeSp: Float,
    val horizontalSway: Float
)

/**
 * Fullscreen 360° Radial Starburst Blast Celebration:
 * - Epicenter at screen center/stamp area: all emojis detonate outward in a 360° halo.
 * - Slower, majestic pacing: explosive ease-out bloom over ~750ms, brief hover at full radius,
 *   followed by organic fluttering gravity fallout over ~1.5s (total duration ~2.35s).
 * - Stamp synchronization: When the radial halo reaches peak bloom (~750ms), the rough chalk
 *   rubber stamp badge slams down forcefully with haptic feedback and dust puff.
 * - Confetti drift: Emojis float and sway gently side-to-side as they fall off-screen.
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

        // 360° Radial particles blossoming outward from screen epicenter
        val particles = remember(resultType) {
            val list = mutableListOf<RadialBurstParticle>()
            val count = 54
            val random = Random(42)

            // Epicenter centered between top bar and board (stamp altitude)
            val startX = screenWidth * 0.50f
            val startY = screenHeight * 0.28f

            for (i in 0 until count) {
                val emoji = emojiPool[random.nextInt(emojiPool.size)]

                // 360° radial distribution with slight organic angle jitter
                val baseAngleDeg = i * (360f / count)
                val jitterAngleDeg = (random.nextFloat() - 0.5f) * 6f
                val angleRad = ((baseAngleDeg + jitterAngleDeg) * (Math.PI / 180.0)).toFloat()

                // Elliptical radial expansion: broad horizontal reach across screen width
                val radiusX = screenWidth * (0.34f + random.nextFloat() * 0.28f)
                val isUpward = sin(angleRad) < 0
                val radiusY = if (isUpward) {
                    screenHeight * (0.18f + random.nextFloat() * 0.12f)
                } else {
                    screenHeight * (0.15f + random.nextFloat() * 0.10f)
                }

                // Slower, graceful pacing: ~2.2s - 2.4s total duration
                val durationMillis = random.nextInt(2150, 2400).toLong()
                val peakRatio = 0.35f + random.nextFloat() * 0.04f // Peaks at ~750-800ms
                val delayMillis = random.nextInt(0, 20).toLong() // Simultaneous blast

                val rotationTarget = (random.nextFloat() - 0.5f) * 540f
                val fontSizeSp = 22f + random.nextFloat() * 12f
                val horizontalSway = screenWidth * (0.05f + random.nextFloat() * 0.05f)

                list.add(
                    RadialBurstParticle(
                        emoji = emoji,
                        startX = startX,
                        startY = startY,
                        angleRad = angleRad,
                        radiusX = radiusX,
                        radiusY = radiusY,
                        delayNanos = delayMillis * 1_000_000L,
                        durationNanos = durationMillis * 1_000_000L,
                        peakRatio = peakRatio,
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

        LaunchedEffect(resultType) {
            elapsedTimeNanos = 0f
            hasTriggeredApex = false
            val startFrame = withFrameNanos { it }
            var isRunning = true
            while (isRunning) {
                val currentFrame = withFrameNanos { it }
                val elapsed = (currentFrame - startFrame).toFloat()
                elapsedTimeNanos = elapsed

                // Apex timing: When radial halo reaches peak bloom (~750ms), slam the stamp down!
                if (!hasTriggeredApex && elapsed >= 750_000_000L) {
                    hasTriggeredApex = true
                    onApexReached()
                }

                // Finish celebration when all emojis have landed (~2.35s)
                if (elapsed >= 2_350_000_000L) {
                    isRunning = false
                    onBurstFinished()
                }
            }
        }

        // Render each particle with 360° explosive bloom, hover, and gravity fallout
        particles.forEach { p ->
            val particleElapsed = elapsedTimeNanos - p.delayNanos
            if (particleElapsed > 0) {
                val progress = (particleElapsed / p.durationNanos.toFloat()).coerceIn(0f, 1f)
                if (progress < 1f) {
                    val curX: Float
                    val curY: Float
                    val scale: Float

                    if (progress <= p.peakRatio) {
                        // ── Phase 1: 360° Explosive Radial Bloom ──
                        val u = (progress / p.peakRatio).coerceIn(0f, 1f)
                        // Smooth cubic ease-out: fast burst out, gentle deceleration to apex
                        val easeOut = 1f - (1f - u) * (1f - u) * (1f - u)
                        curX = p.startX + cos(p.angleRad) * p.radiusX * easeOut
                        curY = p.startY + sin(p.angleRad) * p.radiusY * easeOut
                        // Pop scale from 0.35 to 1.15 at peak
                        scale = 0.35f + 0.80f * easeOut
                    } else {
                        // ── Phase 2: Hover & Graceful Confetti Gravity Fallout ──
                        val v = ((progress - p.peakRatio) / (1f - p.peakRatio)).coerceIn(0f, 1f)
                        // Brief hover moment during first 10% of fallout
                        val gravityProg = ((v - 0.10f) / 0.90f).coerceAtLeast(0f)
                        val gravityDrop = (gravityProg * gravityProg) * screenHeight * 0.75f
                        val flutter = sin(v * (Math.PI * 3.0).toFloat() + p.angleRad) * p.horizontalSway

                        curX = p.startX + cos(p.angleRad) * p.radiusX + flutter
                        curY = p.startY + sin(p.angleRad) * p.radiusY + gravityDrop
                        scale = (1.15f - v * 0.25f).coerceAtLeast(0.80f)
                    }

                    // Quick fade in at start, vibrant flight, smooth fade out at screen bottom
                    val alpha = when {
                        progress < 0.05f -> progress / 0.05f
                        progress > 0.85f -> (1f - progress) / 0.15f
                        else -> 1f
                    }.coerceIn(0f, 1f)

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
