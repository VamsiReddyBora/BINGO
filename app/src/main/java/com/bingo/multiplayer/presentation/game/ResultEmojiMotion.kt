package com.bingo.multiplayer.presentation.game

import com.bingo.multiplayer.core.designsystem.AnimationFeatureFlags
import com.bingo.multiplayer.core.designsystem.ResultAnimationTuning
import kotlin.math.roundToLong
import kotlin.random.Random

/** The production trajectory used by the Canvas renderer; coordinates are screen proportions. */
internal data class ResultEmojiParticle(
    val emoji: String,
    val sizeDp: Float,
    val delayMillis: Long,
    val riseMillis: Long,
    val fallMillis: Long,
    val startX: Float,
    val endX: Float,
    val apexY: Float,
    val startRotation: Float,
    val rotationDegrees: Float
) {
    val endMillis: Long get() = delayMillis + riseMillis + fallMillis
    val apexX: Float get() = startX + (endX - startX) * riseMillis / (riseMillis + fallMillis)

    fun frame(elapsedMillis: Float): ResultEmojiFrame? = ResultEmojiFrame().takeIf { writeFrame(elapsedMillis, it) }

    /** The renderer reuses one output object for all particles, avoiding per-frame allocations. */
    fun writeFrame(elapsedMillis: Float, output: ResultEmojiFrame): Boolean {
        val age = elapsedMillis - delayMillis
        if (!age.isFinite() || age < 0f || age >= riseMillis + fallMillis) return false
        // Cubic ascent is fast at launch and eases out. Both velocity and acceleration meet
        // continuously at the apex; sideways drift and rotation never acquire a hold phase.
        val y = if (age < riseMillis) {
            val t = age / riseMillis
            val remaining = 1f - t
            apexY + (ResultEmojiMotion.START_Y - apexY) * remaining * remaining * remaining
        } else {
            val t = (age - riseMillis) / fallMillis
            apexY + (ResultEmojiMotion.END_Y - apexY) * t * t * t
        }
        val t = age / (riseMillis + fallMillis)
        output.x = startX + (endX - startX) * t
        output.y = y
        output.rotation = startRotation + rotationDegrees * t
        return true
    }
}

internal data class ResultEmojiFrame(var x: Float = 0f, var y: Float = 0f, var rotation: Float = 0f)

internal data class ResultEmojiBurst(val particles: List<ResultEmojiParticle>) {
    val durationMillis: Long get() = particles.maxOf { it.endMillis }
}

internal object ResultEmojiMotion {
    const val DURATION_MILLIS = 4_000L
    const val LAUNCH_WINDOW_MILLIS = ResultAnimationTuning.DEFAULT_LAUNCH_SPREAD_MILLIS
    const val START_Y = 1.06f
    const val END_Y = 1.15f
    val winningEmojis = listOf("🥳", "😍", "🤩", "💥", "🎊", "🎉", "🔥", "🩵", "💚", "💛", "❤️‍🔥", "🎈", "🎁", "🎇", "🎆")
    val losingEmojis = listOf("🤐", "😵‍💫", "😩", "🤧", "💀", "🙊", "💔", "🤦🏻‍♂️", "🥀", "🙆🏻‍♂️", "😱", "🫪", "😑", "😭", "🙄")

    fun create(result: StampResultType, seed: Long): ResultEmojiBurst = create(result, seed, ResultAnimationTuning())

    fun create(result: StampResultType, seed: Long, tuning: ResultAnimationTuning): ResultEmojiBurst {
        require(result == StampResultType.WON || result == StampResultType.LOST)
        val random = Random(seed)
        val controls = tuning.normalized()
        val pool = if (result == StampResultType.WON) winningEmojis else losingEmojis
        val count = controls.emojiCount
        // Draw complete emoji sequences independently for a fresh mix on every result.
        // Keep this RNG separate so changing the palette cannot change the approved motion.
        val emojiRandom = Random(seed xor 0x454D4F4A49L)
        val emojis = List(count) { pool.random(emojiRandom) }.shuffled(random)
        // Shuffle one random point from each bottom strip: broad coverage without concentrating
        // the launches at the corners or sending every particle toward a shared centre.
        val origins = List(count) { (.06f + (it + random.nextFloat()) / count * .88f) }.shuffled(random)
        // A separate permutation fills the full vertical area without consuming the motion RNG.
        val heightSlots = (0 until count).shuffled(Random(seed xor 0x41524541L))
        fun areaX(legacyX: Float) = (controls.areaLeft + (legacyX - .06f) / .88f * controls.areaWidth)
            .coerceIn(controls.areaLeft, controls.areaRight)
        return ResultEmojiBurst(List(count) { index ->
            val launchAt = random.nextLong(index * controls.launchSpreadMillis / count,
                (index + 1) * controls.launchSpreadMillis / count)
            val finishAt = if (index == count - 1) DURATION_MILLIS
                else random.nextLong(3_200L, DURATION_MILLIS + 1L)
            val flightMillis = finishAt - launchAt
            val apex = controls.areaTop + (heightSlots[index] + random.nextFloat()) / count *
                (controls.areaBottom - controls.areaTop)
            val baseRiseMillis = (flightMillis * .42f).roundToLong()
            val riseMillis = (baseRiseMillis / controls.upwardSpeed).roundToLong().coerceAtLeast(1L)
            val fallMillis = ((flightMillis - baseRiseMillis) / controls.fallingSpeed).roundToLong().coerceAtLeast(1L)
            val legacyStart = origins[index]
            val direction = when {
                legacyStart < .25f -> 1f
                legacyStart > .75f -> -1f
                random.nextBoolean() -> 1f
                else -> -1f
            }
            // Preserve the existing timing, size and spin random sequence. Only the area changes.
            val particleSize = (22f + random.nextFloat() * 28f) * controls.sizeScale
            val legacyEnd = (legacyStart + direction * (.06f + random.nextFloat() * .16f)).coerceIn(.05f, .95f)
            val start = areaX(legacyStart)
            val end = (start + (areaX(legacyEnd) - start) * controls.sidewaysDrift)
                .coerceIn(controls.areaLeft, controls.areaRight)
            ResultEmojiParticle(
                emoji = emojis[index],
                sizeDp = particleSize,
                delayMillis = launchAt,
                riseMillis = riseMillis,
                fallMillis = fallMillis,
                startX = start,
                endX = end,
                apexY = apex,
                startRotation = random.nextFloat() * 360f,
                rotationDegrees = (180f + random.nextFloat() * 540f) * controls.rotationScale * if (random.nextBoolean()) 1f else -1f
            )
        })
    }
}

internal data class ResultEmojiDecision(val consumed: Boolean, val launch: StampResultType? = null)

internal object ResultEmojiPlayback {
    /** Consume the finish even when disabled/hidden, so toggling or resuming cannot replay it. */
    fun decide(
        consumed: Boolean,
        isGameOver: Boolean,
        result: StampResultType,
        exiting: Boolean,
        preferred: Boolean,
        visible: Boolean,
        buildEnabled: Boolean = AnimationFeatureFlags.WINNING_EMOJI_ANIMATION_ENABLED
    ): ResultEmojiDecision {
        if (!isGameOver) return ResultEmojiDecision(false)
        val supported = result == StampResultType.WON || result == StampResultType.LOST
        return ResultEmojiDecision(true, result.takeIf {
            !consumed && !exiting && preferred && visible && buildEnabled && supported
        })
    }
}
