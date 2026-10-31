package com.bingo.multiplayer.presentation.lobby

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Fixed cosmetic preset selected by the owner; experimental saved overrides are no longer read. */
internal data class LobbyBubbleTuning(
    val fitShortMessages: Boolean = true,
    val maxWidthDp: Float = 200f,
    val minHeightDp: Float = 22f,
    val fontSizeSp: Float = 12f,
    val paddingDp: Float = 10f,
    val cornerDp: Float = 11f,
    val xDp: Float = 35f,
    val yDp: Float = 13f,
    val topGapDp: Float = 0f,
    val avatarGapDp: Float = 0f,
    val tailWidthDp: Float = 10f,
    val tailHeightDp: Float = 6f,
    val animate: Boolean = true,
    val durationMs: Int = 537,
    val startScale: Float = .20f,
    val bounce: Float = .21f,
    val bounces: Int = 1,
    val travelDp: Float = 40f,
    val bounceLiftDp: Float = 2f,
    val fadePortion: Float = .25f,
    val scrollSpeedDp: Float = 20f,
    val scrollPauseMs: Int = 1500
) {
    fun normalized(): LobbyBubbleTuning = copy(
        maxWidthDp = maxWidthDp.safe(80f, 280f, 200f),
        minHeightDp = minHeightDp.safe(22f, 56f, 22f),
        fontSizeSp = fontSizeSp.safe(10f, 20f, 12f),
        paddingDp = paddingDp.safe(4f, 20f, 10f),
        cornerDp = cornerDp.safe(4f, 28f, 11f),
        xDp = xDp.safe(0f, 96f, 35f), yDp = yDp.safe(-24f, 24f, 13f),
        topGapDp = topGapDp.safe(0f, 16f, 0f), avatarGapDp = avatarGapDp.safe(0f, 16f, 0f),
        tailWidthDp = tailWidthDp.safe(6f, 24f, 10f), tailHeightDp = tailHeightDp.safe(0f, 14f, 6f),
        durationMs = durationMs.coerceIn(200, 1600), startScale = startScale.safe(.05f, .8f, .2f),
        bounce = bounce.safe(0f, .45f, .21f), bounces = bounces.coerceIn(1, 4),
        travelDp = travelDp.safe(0f, 64f, 40f), bounceLiftDp = bounceLiftDp.safe(0f, 24f, 2f),
        fadePortion = fadePortion.safe(.05f, .7f, .25f),
        scrollSpeedDp = scrollSpeedDp.safe(10f, 70f, 20f), scrollPauseMs = scrollPauseMs.coerceIn(300, 3500)
    )

    private fun Float.safe(min: Float, max: Float, fallback: Float) =
        if (isFinite()) coerceIn(min, max) else fallback
}

internal data class LobbyBubbleFrame(val scale: Float, val translationYDp: Float, val alpha: Float)

/** One finite cosmetic pop; its progress never reads or writes a match timer. */
internal object LobbyBubbleMotion {
    fun frame(progress: Float, tuning: LobbyBubbleTuning): LobbyBubbleFrame {
        if (!tuning.animate) return LobbyBubbleFrame(1f, 0f, 1f)
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p >= 1f) return LobbyBubbleFrame(1f, 0f, 1f)
        val rise = (p / .42f).coerceIn(0f, 1f)
        val easedRise = 1f - (1f - rise).pow(3)
        val settle = ((p - .42f) / .58f).coerceIn(0f, 1f)
        val envelope = ((1f + cos(PI * settle)) * .5).toFloat()
        val wave = (2 * PI * tuning.bounces * settle)
        val scale = if (p < .42f) tuning.startScale + (1f + tuning.bounce - tuning.startScale) * easedRise
            else 1f + tuning.bounce * cos(wave).toFloat() * envelope
        val bob = if (p < .42f) 0f else
            -tuning.bounceLiftDp * sin(wave).toFloat() * sin(PI * settle).toFloat().pow(2)
        return LobbyBubbleFrame(scale, tuning.travelDp * (1f - easedRise) + bob,
            (p / tuning.fadePortion).coerceIn(0f, 1f))
    }
}

