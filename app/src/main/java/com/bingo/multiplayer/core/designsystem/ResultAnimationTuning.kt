package com.bingo.multiplayer.core.designsystem

/** Fixed cosmetic preset chosen by the owner; never changes gameplay clocks. */
data class ResultAnimationTuning(
    val emojiCount: Int = DEFAULT_EMOJI_COUNT,
    val upwardSpeed: Float = DEFAULT_UPWARD_SPEED,
    val fallingSpeed: Float = DEFAULT_FALLING_SPEED,
    val launchSpreadMillis: Long = DEFAULT_LAUNCH_SPREAD_MILLIS,
    val sizeScale: Float = DEFAULT_SIZE_SCALE,
    val rotationScale: Float = DEFAULT_ROTATION_SCALE,
    val areaTop: Float = .20f,
    val areaBottom: Float = .90f,
    val areaWidth: Float = .96f,
    val areaCenterX: Float = .50f,
    val sidewaysDrift: Float = 1.95f
) {
    fun normalized(): ResultAnimationTuning {
        val top = areaTop.safeRange(.05f, .80f, .20f)
        val width = areaWidth.safeRange(.20f, .96f, .96f)
        val bottomMin = (top + .10f).coerceAtMost(.90f)
        val centerMin = width / 2f + .02f
        val centerMax = (.98f - width / 2f).coerceAtLeast(centerMin)
        return copy(
        emojiCount = emojiCount.coerceIn(10, 80),
        upwardSpeed = upwardSpeed.safeRange(.5f, 2.5f, DEFAULT_UPWARD_SPEED),
        fallingSpeed = fallingSpeed.safeRange(.5f, 2.5f, DEFAULT_FALLING_SPEED),
        launchSpreadMillis = launchSpreadMillis.coerceIn(100L, 900L),
        sizeScale = sizeScale.safeRange(.6f, 1.5f, DEFAULT_SIZE_SCALE),
        rotationScale = rotationScale.safeRange(0f, 2f, DEFAULT_ROTATION_SCALE),
        areaTop = top,
        areaBottom = areaBottom.safeRange(bottomMin, .90f, .90f.coerceAtLeast(bottomMin)),
        areaWidth = width,
        areaCenterX = areaCenterX.safeRange(centerMin, centerMax, .50f),
        sidewaysDrift = sidewaysDrift.safeRange(0f, 2f, 1.95f)
        )
    }

    val areaLeft: Float get() = areaCenterX - areaWidth / 2f
    val areaRight: Float get() = areaCenterX + areaWidth / 2f

    private fun Float.safeRange(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback

    companion object {
        const val DEFAULT_EMOJI_COUNT = 50
        const val DEFAULT_UPWARD_SPEED = 2f
        const val DEFAULT_FALLING_SPEED = .7f
        const val DEFAULT_LAUNCH_SPREAD_MILLIS = 900L
        const val DEFAULT_SIZE_SCALE = .8f
        const val DEFAULT_ROTATION_SCALE = 2f
    }
}
