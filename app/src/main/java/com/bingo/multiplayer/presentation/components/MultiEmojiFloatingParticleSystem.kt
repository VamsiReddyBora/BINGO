package com.bingo.multiplayer.presentation.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Highly configurable parameter bag for the multi-emoji floating particle animation.
 */
data class EmojiParticleConfig(
    val emojiList: List<String> = listOf("❤️", "😂", "🔥", "😍", "😎", "🎉", "🤯", "💀", "👀", "✨", "🚀", "💯"),
    val particleCount: Int = 12,              // Base count per burst (8-15)
    val spawnRate: Float = 14f,               // Target emission rate (particles per second during active bursts)
    val minSize: Float = 18f,                 // Small emojis for background/depth (sp)
    val maxSize: Float = 48f,                 // Large emojis for visual emphasis (sp)
    val minSpeed: Float = 170f,               // Minimum upward speed (dp/s)
    val maxSpeed: Float = 360f,               // Maximum upward speed (dp/s)
    val horizontalSpread: Float = 0.44f,      // Spawn horizontal distribution ratio across center
    val driftStrength: Float = 38f,           // Harmonic horizontal drift magnitude (dp)
    val rotationSpeed: Float = 45f,           // Max rotation speed (deg/s)
    val particleLifetime: Long = 3200L,       // Average particle lifetime (ms)
    val fadeDuration: Long = 650L,            // Fade-out duration near end of life (ms)
    val scaleDuration: Long = 260L,           // Scale-in spawn duration (ms)
    val gravity: Float = -20f,                // Subtle upward buoyancy / vertical deceleration (dp/s^2)
    val windStrength: Float = 10f,            // Ambient lateral wind drift (dp/s)
    val maxActiveParticles: Int = 90          // Reusable object pool capacity (zero runtime allocations)
)

/**
 * Reusable particle object in the object pool.
 * Mutated in-place to eliminate garbage collector allocations during the 60+ FPS animation loop.
 */
internal class FloatingEmojiParticle {
    var isActive: Boolean = false
    var emoji: String = "✨"

    // Coordinates and Trajectory (in pixels)
    var originX: Float = 0f
    var originY: Float = 0f
    var vyPx: Float = 0f
    var accelYPx: Float = 0f
    var curvaturePx: Float = 0f
    var driftAmpPx: Float = 0f
    var driftFreq: Float = 1.0f
    var driftPhase: Float = 0f
    var windPx: Float = 0f

    // Rotation
    var initialRotationDeg: Float = 0f
    var rotationSpeedDeg: Float = 0f

    // Sizing and Depth
    var depthTier: Int = 1 // 0: Small (background), 1: Medium (primary), 2: Large (emphasis)
    var fontSizePx: Float = 32f
    var baselineOffset: Float = 0f
    var baseAlpha: Float = 1.0f

    // Timing and Lifecycle (in nanoseconds)
    var spawnTimeNanos: Long = 0L
    var lifetimeNanos: Long = 0L
    var scaleInDurationNanos: Long = 0L
    var fadeOutDurationNanos: Long = 0L

    // Organic scaling variations
    var scaleInOvershoot: Float = 1.08f
    var endScaleMultiplier: Float = 1.12f
}

/**
 * High-Quality Multi-Emoji Floating Particle Animation.
 *
 * Core Features:
 * - Fluid, organic fountain-like distribution spreading horizontally as particles float upward.
 * - Randomizes emoji type, size, speed, drift, curvature, rotation, opacity, and lifetime.
 * - Multi-depth visual layers (small background, medium primary, large visual emphasis).
 * - Lifecycle: Spawn → Scale In → Float/Drift/Rotate → Scale Out → Fade Out → Recycle.
 * - Emits natural bursts of 8–15 emojis with tiny staggered delays.
 * - Reusable particle object pool with zero GC allocation in the render loop.
 * - Native Canvas hardware-accelerated single-pass rendering targeting 60/120 FPS.
 */
@Composable
fun MultiEmojiFloatingParticleSystem(
    modifier: Modifier = Modifier,
    config: EmojiParticleConfig = remember { EmojiParticleConfig() },
    isContinuous: Boolean = true,
    totalDurationMillis: Long? = null,
    onApexReached: () -> Unit = {},
    onAnimationFinished: () -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        if (screenW <= 0f || screenH <= 0f) return@BoxWithConstraints

        // Pre-allocate recyclable particle pool (Zero allocation during render)
        val particlePool = remember(config.maxActiveParticles) {
            Array(config.maxActiveParticles) { FloatingEmojiParticle() }
        }

        var animationTimeNanos by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

        LaunchedEffect(config, isContinuous, totalDurationMillis, screenW, screenH) {
            val random = Random(System.currentTimeMillis())
            val tempPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            // Convert config dp units to px
            val minSpeedPx = with(density) { config.minSpeed.toDp().toPx() }
            val maxSpeedPx = with(density) { config.maxSpeed.toDp().toPx() }
            val driftStrengthPx = with(density) { config.driftStrength.toDp().toPx() }
            val gravityPx = with(density) { config.gravity.toDp().toPx() }
            val windStrengthPx = with(density) { config.windStrength.toDp().toPx() }

            val startFrame = withFrameNanos { it }
            var nextBurstNanos = 0L
            var hasTriggeredApex = false
            val apexTriggerNanos = 950_000_000L // ~950ms when the initial fountain covers the mid-screen

            // Helper to spawn a single particle into an available slot
            fun spawnParticle(scheduledSpawnNanos: Long) {
                // Find inactive slot in the pool
                var slot: FloatingEmojiParticle? = null
                for (p in particlePool) {
                    if (!p.isActive) {
                        slot = p
                        break
                    }
                }
                if (slot == null) return

                val emoji = if (config.emojiList.isNotEmpty()) {
                    config.emojiList[random.nextInt(config.emojiList.size)]
                } else "✨"

                // Depth tier distribution:
                // ~35% Small (Background), ~50% Medium (Primary), ~15% Large (Emphasis)
                val depthRoll = random.nextFloat()
                val depthTier = when {
                    depthRoll < 0.35f -> 0
                    depthRoll < 0.85f -> 1
                    else -> 2
                }

                val sizeSp = when (depthTier) {
                    0 -> config.minSize + random.nextFloat() * ((config.maxSize - config.minSize) * 0.25f)
                    1 -> config.minSize + (config.maxSize - config.minSize) * 0.30f + random.nextFloat() * ((config.maxSize - config.minSize) * 0.40f)
                    else -> config.minSize + (config.maxSize - config.minSize) * 0.75f + random.nextFloat() * ((config.maxSize - config.minSize) * 0.25f)
                }
                val fontSizePx = with(density) { sizeSp.toDp().toPx() }

                tempPaint.textSize = fontSizePx
                val fm = tempPaint.fontMetrics
                val baselineOffset = -(fm.ascent + fm.descent) / 2f

                // Speed inversely/directly mapped to depth tier:
                // Small particles float faster, large particles drift more gracefully
                val speedMultiplier = when (depthTier) {
                    0 -> 1.15f + random.nextFloat() * 0.25f
                    1 -> 0.90f + random.nextFloat() * 0.20f
                    else -> 0.72f + random.nextFloat() * 0.18f
                }
                val upwardSpeed = (minSpeedPx + random.nextFloat() * (maxSpeedPx - minSpeedPx)) * speedMultiplier

                // Base opacity by depth tier
                val baseAlpha = when (depthTier) {
                    0 -> 0.55f + random.nextFloat() * 0.20f
                    1 -> 0.82f + random.nextFloat() * 0.14f
                    else -> 0.95f + random.nextFloat() * 0.05f
                }

                // Screen positioning: Originates from lower-mid area, spreads horizontally like a fountain
                val centerOffset = (random.nextFloat() - 0.5f) * (screenW * config.horizontalSpread)
                val originX = (screenW * 0.50f + centerOffset).coerceIn(24f, screenW - 24f)
                val originY = screenH * 0.84f + (random.nextFloat() * screenH * 0.08f)

                // Trajectory variation: curvature, harmonic drift, and wind
                val curvatureSign = if (centerOffset >= 0f) 1f else -1f
                val curvaturePx = (curvatureSign * (random.nextFloat() * 45f + 15f)) * density.density
                val driftAmpPx = driftStrengthPx * (0.6f + random.nextFloat() * 0.8f)
                val driftFreq = 1.2f + random.nextFloat() * 1.6f
                val driftPhase = random.nextFloat() * (2f * PI.toFloat())
                val windPx = windStrengthPx * (0.8f + random.nextFloat() * 0.4f)

                // Rotation
                val initialRot = (random.nextFloat() - 0.5f) * 40f
                val rotSpeed = (random.nextFloat() - 0.5f) * (config.rotationSpeed * 2f)

                // Lifetime
                val lifetimeVariation = (random.nextFloat() - 0.5f) * 600f
                val lifetimeMs = (config.particleLifetime + lifetimeVariation).toLong().coerceAtLeast(1800L)
                val lifetimeNanos = lifetimeMs * 1_000_000L

                // Assign to slot
                slot.isActive = true
                slot.emoji = emoji
                slot.originX = originX
                slot.originY = originY
                slot.vyPx = upwardSpeed
                slot.accelYPx = gravityPx
                slot.curvaturePx = curvaturePx
                slot.driftAmpPx = driftAmpPx
                slot.driftFreq = driftFreq
                slot.driftPhase = driftPhase
                slot.windPx = windPx
                slot.initialRotationDeg = initialRot
                slot.rotationSpeedDeg = rotSpeed
                slot.depthTier = depthTier
                slot.fontSizePx = fontSizePx
                slot.baselineOffset = baselineOffset
                slot.baseAlpha = baseAlpha
                slot.spawnTimeNanos = scheduledSpawnNanos
                slot.lifetimeNanos = lifetimeNanos
                slot.scaleInDurationNanos = config.scaleDuration * 1_000_000L
                slot.fadeOutDurationNanos = config.fadeDuration * 1_000_000L
                slot.scaleInOvershoot = 1.05f + random.nextFloat() * 0.08f
                slot.endScaleMultiplier = 1.08f + random.nextFloat() * 0.12f
            }

            // Function to schedule a natural burst containing 8–15 emojis with tiny staggered delays
            fun scheduleBurst(burstBaseElapsedNanos: Long) {
                val burstCount = random.nextInt(8, 16)
                for (i in 0 until burstCount) {
                    // Tiny random staggered delay (15ms - 45ms per particle)
                    val staggerMs = (i * random.nextInt(18, 40)) + random.nextInt(0, 20)
                    val scheduledTime = burstBaseElapsedNanos + (staggerMs * 1_000_000L)
                    spawnParticle(scheduledTime)
                }
            }

            // Trigger the initial burst at start (relative 0L)
            scheduleBurst(0L)
            nextBurstNanos = random.nextInt(650, 950) * 1_000_000L

            var isRunning = true
            while (isRunning) {
                val currentNanos = withFrameNanos { it }
                val elapsedNanos = currentNanos - startFrame
                animationTimeNanos = elapsedNanos

                // Check apex event
                if (!hasTriggeredApex && elapsedNanos >= apexTriggerNanos) {
                    hasTriggeredApex = true
                    onApexReached()
                }

                // Check continuous bursts scheduling
                val shouldEmitMore = isContinuous || (totalDurationMillis != null && elapsedNanos < (totalDurationMillis * 1_000_000L))
                if (shouldEmitMore && elapsedNanos >= nextBurstNanos) {
                    // Schedule next burst interval (~650ms to 950ms)
                    val burstIntervalNanos = random.nextInt(650, 950) * 1_000_000L
                    nextBurstNanos = elapsedNanos + burstIntervalNanos
                    scheduleBurst(elapsedNanos)
                }

                // Check total duration completion
                if (totalDurationMillis != null && elapsedNanos >= (totalDurationMillis * 1_000_000L)) {
                    // Check if all active particles have faded out
                    var anyActive = false
                    for (p in particlePool) {
                        if (p.isActive) {
                            val particleElapsed = elapsedNanos - p.spawnTimeNanos
                            if (particleElapsed < p.lifetimeNanos) {
                                anyActive = true
                                break
                            }
                        }
                    }
                    if (!anyActive) {
                        isRunning = false
                        onAnimationFinished()
                    }
                }
            }
        }

        // Shared paint objects for hardware-accelerated drawing
        val textPaint = remember {
            Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                // Subtle soft shadow/glow for premium depth
                setShadowLayer(5f, 0f, 2f, 0x33000000)
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentElapsedNanos = animationTimeNanos
            if (currentElapsedNanos < 0L) return@Canvas

            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas

                for (p in particlePool) {
                    if (!p.isActive) continue

                    val particleElapsedNanos = currentElapsedNanos - p.spawnTimeNanos
                    if (particleElapsedNanos < 0L) continue // Staggered delay not reached yet

                    if (particleElapsedNanos >= p.lifetimeNanos) {
                        p.isActive = false // Recycle expired particle
                        continue
                    }

                    val progress = (particleElapsedNanos.toFloat() / p.lifetimeNanos.toFloat()).coerceIn(0f, 1f)
                    val ageSec = particleElapsedNanos.toFloat() / 1_000_000_000f

                    // ── 1. Vertical Upward Motion with Deceleration ──
                    // y = originY - (vy * t + 0.5 * accelY * t^2)
                    val verticalDisplacement = (p.vyPx * ageSec) + (0.5f * p.accelYPx * ageSec * ageSec)
                    val currentY = p.originY - verticalDisplacement

                    // ── 2. Fluid Horizontal Drifting & Curvature ──
                    // fountain spreading curvature + harmonic sine sway + subtle wind
                    val driftCurvature = p.curvaturePx * progress
                    val harmonicSway = sin(p.driftFreq * ageSec * (2f * PI.toFloat()) + p.driftPhase) * p.driftAmpPx
                    val windDrift = p.windPx * ageSec
                    val currentX = p.originX + driftCurvature + harmonicSway + windDrift

                    // ── 3. Lifecycle Scaling (Spawn → Scale In → Drift → Scale Out) ──
                    val scale: Float
                    if (particleElapsedNanos < p.scaleInDurationNanos) {
                        // Quick scale in from 0.35x to overshoot using ease-out curve
                        val u = (particleElapsedNanos.toFloat() / p.scaleInDurationNanos.toFloat()).coerceIn(0f, 1f)
                        val easeOut = 1f - (1f - u) * (1f - u)
                        scale = 0.35f + (p.scaleInOvershoot - 0.35f) * easeOut
                    } else {
                        // Smoothly transition from overshoot to 1.0x, then gentle expansion near end of life
                        val remainingNanos = p.lifetimeNanos - particleElapsedNanos
                        if (remainingNanos < p.fadeOutDurationNanos) {
                            val v = 1f - (remainingNanos.toFloat() / p.fadeOutDurationNanos.toFloat()).coerceIn(0f, 1f)
                            scale = 1.0f + (p.endScaleMultiplier - 1.0f) * v
                        } else {
                            scale = 1.0f
                        }
                    }

                    // ── 4. Lifecycle Opacity (Fade In & Fade Out) ──
                    val remainingNanos = p.lifetimeNanos - particleElapsedNanos
                    val alpha = when {
                        // Rapid emergence (first 120ms)
                        particleElapsedNanos < 120_000_000L -> {
                            val u = particleElapsedNanos.toFloat() / 120_000_000f
                            p.baseAlpha * u
                        }
                        // Gentle graceful fade out near end of life
                        remainingNanos < p.fadeOutDurationNanos -> {
                            val v = remainingNanos.toFloat() / p.fadeOutDurationNanos.toFloat()
                            val easeFade = v * v // quadratic ease-out
                            p.baseAlpha * easeFade
                        }
                        // Disappears gracefully near upper screen edge
                        currentY < (screenH * 0.16f) -> {
                            val edgeFade = ((currentY - (screenH * 0.04f)) / (screenH * 0.12f)).coerceIn(0f, 1f)
                            p.baseAlpha * edgeFade
                        }
                        else -> p.baseAlpha
                    }.coerceIn(0f, 1f)

                    if (alpha <= 0.005f) continue

                    // ── 5. Subtle Rotation ──
                    val rotationDeg = p.initialRotationDeg + (p.rotationSpeedDeg * ageSec)

                    // ── 6. Single-pass Native Canvas Rendering ──
                    nativeCanvas.save()
                    nativeCanvas.translate(currentX, currentY)
                    if (rotationDeg != 0f) {
                        nativeCanvas.rotate(rotationDeg)
                    }
                    if (scale != 1.0f) {
                        nativeCanvas.scale(scale, scale)
                    }

                    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
                    val needsAlphaLayer = alpha < 0.995f
                    if (needsAlphaLayer) {
                        nativeCanvas.saveLayerAlpha(null, alphaInt)
                    }

                    textPaint.textSize = p.fontSizePx
                    textPaint.alpha = alphaInt
                    nativeCanvas.drawText(p.emoji, 0f, p.baselineOffset, textPaint)

                    if (needsAlphaLayer) {
                        nativeCanvas.restore()
                    }
                    nativeCanvas.restore()
                }
            }
        }
    }
}
