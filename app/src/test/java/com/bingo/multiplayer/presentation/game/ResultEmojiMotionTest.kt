package com.bingo.multiplayer.presentation.game

import com.bingo.multiplayer.core.designsystem.ResultAnimationTuning
import org.junit.Assert.*
import org.junit.Test

class ResultEmojiMotionTest {
    private val WIN_POOL = listOf("🥳", "😍", "🤩", "💥", "🎊", "🎉", "🔥", "🩵", "💚", "💛", "❤️‍🔥", "🎈", "🎁", "🎇", "🎆")
    private val LOSS_POOL = listOf("🤐", "😵‍💫", "😩", "🤧", "💀", "🙊", "💔", "🤦🏻‍♂️", "🥀", "🙆🏻‍♂️", "😱", "🫪", "😑", "😭", "🙄")
    // Retain the complete pre-selection motion coverage against its original neutral preset.
    private val referenceTuning = ResultAnimationTuning(40, 1f, 1f, 650L, 1f, 1f, areaTop = .28f, areaBottom = .53f, areaWidth = .88f, sidewaysDrift = 1f)
    private fun referenceBurst(result: StampResultType, seed: Long) =
        ResultEmojiMotion.create(result, seed, referenceTuning)

    @Test fun fortyParticlePresetSamplesOnlyTheExactRequestedPools() {
        assertEquals(WIN_POOL, ResultEmojiMotion.winningEmojis)
        assertEquals(LOSS_POOL, ResultEmojiMotion.losingEmojis)
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            val expected = if (result == StampResultType.WON) WIN_POOL else LOSS_POOL
            for (seed in 0L..30L) {
                val particles = referenceBurst(result, seed).particles
                assertEquals(40, particles.size)
                assertTrue(particles.all { it.emoji in expected })
            }
        }
    }

    @Test fun everyResultGetsAFreshRandomMixRatherThanAFixedRepeatingCycle() {
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            val pool = if (result == StampResultType.WON) WIN_POOL else LOSS_POOL
            val bursts = (0L..100L).map { seed -> ResultEmojiMotion.create(result, seed).particles.map { it.emoji } }
            assertEquals(101, bursts.distinct().size)
            assertEquals(pool.toSet(), bursts.flatten().toSet())
            val mixes = bursts.map { it.groupingBy { emoji -> emoji }.eachCount() }
            assertTrue(mixes.distinct().size > 90)
            assertTrue(mixes.any { it.keys.size < pool.toSet().size })
            assertTrue(mixes.any { counts -> counts.values.max() - counts.values.min() > 1 })
            assertTrue(bursts.flatten().all { it in pool })
        }
    }

    @Test fun finalizedPalettesKeepAllFifteenCompleteEmojiSequences() {
        assertEquals(15, ResultEmojiMotion.winningEmojis.size)
        assertEquals(15, ResultEmojiMotion.losingEmojis.size)
        assertEquals(15, ResultEmojiMotion.winningEmojis.distinct().size)
        assertEquals(15, ResultEmojiMotion.losingEmojis.distinct().size)
        assertTrue(WIN_POOL.containsAll(listOf("❤️‍🔥", "🩵", "🎆")))
        assertTrue(LOSS_POOL.containsAll(listOf("🤦🏻‍♂️", "🙆🏻‍♂️", "😵‍💫", "🫪", "🙄")))
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            val expected = if (result == StampResultType.WON) WIN_POOL else LOSS_POOL
            val used = (0L..100L).flatMap { ResultEmojiMotion.create(result, it).particles }.map { it.emoji }
            assertEquals(expected.toSet(), used.toSet())
            assertTrue(used.all { it in expected })
        }
    }

    @Test fun allLaunchesUseTheFullBottomWithinTheFirstSecond() {
        for (seed in 0L..100L) {
            val particles = referenceBurst(StampResultType.WON, seed).particles
            assertEquals((0..4).toSet(), particles.map { (it.startX * 5).toInt() }.toSet())
            assertTrue(particles.first().delayMillis < 50L)
            assertTrue(particles.all { it.delayMillis in 0L..899L })
            for ((a, b) in particles.zipWithNext()) assertTrue(b.delayMillis > a.delayMillis)
            for (p in particles) {
                assertNull(p.frame(p.delayMillis - 1f))
                assertTrue(p.startX in .06f.. .94f)
                assertTrue(p.frame(p.delayMillis.toFloat())!!.y > 1f)
                // The last launch has also visibly entered the screen before the first second ends.
                assertTrue(p.frame(999f)!!.y < 1f)
                assertTrue(p.apexY in .28f.. .53f)
            }
        }
    }

    @Test fun sizesPathsRotationsAndTimingVaryButRemainReproducible() {
        val burst = referenceBurst(StampResultType.WON, 42L)
        assertEquals(burst, referenceBurst(StampResultType.WON, 42L))
        assertNotEquals(burst, referenceBurst(StampResultType.WON, 43L))
        val p = burst.particles
        assertTrue(p.map { it.sizeDp }.distinct().size > 10)
        assertTrue(p.map { it.apexX }.distinct().size > 10)
        assertTrue(p.map { it.riseMillis }.distinct().size > 10)
        assertTrue(p.map { it.startRotation }.distinct().size > 10)
        assertTrue(p.any { it.rotationDegrees < 0f } && p.any { it.rotationDegrees > 0f })
        assertTrue(p.all { it.sizeDp in 22f..50f })
        assertTrue(p.map { (it.apexX * 5).toInt() }.toSet().size >= 4)
        assertTrue(p.map { it.riseMillis / (ResultEmojiMotion.START_Y - it.apexY) }.distinct().size > 10)
    }

    @Test fun emojisKeepMovingAndRotatingThroughTheirApexWithoutAFreeze() {
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            for (p in referenceBurst(result, 7L).particles) {
                val apex = (p.delayMillis + p.riseMillis).toFloat()
                val a = p.frame(apex - 20f)!!
                val b = p.frame(apex)!!
                val c = p.frame(apex + 20f)!!
                assertNotEquals(a.x, b.x)
                assertNotEquals(b.x, c.x)
                assertNotEquals(a.rotation, b.rotation)
                assertNotEquals(b.rotation, c.rotation)
                assertTrue(a.y > b.y && c.y > b.y)
                assertEquals(p.apexX, b.x, .00001f)
                assertEquals(p.apexY, b.y, .00001f)
                // Horizontal velocity and spin stay continuous across the turnaround.
                assertEquals(b.x - a.x, c.x - b.x, .00001f)
                assertEquals(b.rotation - a.rotation, c.rotation - b.rotation, .0001f)
            }
        }
    }

    @Test fun riseSlowsNearTheTopAndFallAcceleratesToBelowTheScreen() {
        for (p in referenceBurst(StampResultType.LOST, 12L).particles) {
            val start = p.delayMillis.toFloat()
            val y0 = p.frame(start)!!.y
            val ym = p.frame(start + p.riseMillis / 2f)!!.y
            val yt = p.frame(start + p.riseMillis)!!.y
            assertTrue(y0 > ym && ym > yt)
            assertTrue(y0 - ym > ym - yt)
            val fall = start + p.riseMillis
            val early = p.frame(fall + p.fallMillis / 4f)!!.y
            val middle = p.frame(fall + p.fallMillis / 2f)!!.y
            val late = p.frame(fall + p.fallMillis * .75f)!!.y
            assertTrue(early > yt && middle > early && late > middle)
            assertTrue(late - middle > middle - early)
            assertTrue(p.frame(p.endMillis - 1f)!!.y > 1f)
            assertNull(p.frame(p.endMillis.toFloat()))
        }
    }

    @Test fun allFramesAreFiniteAndTheEntireBurstFinishesAtExactlyFourSeconds() {
        for (seed in 0L..100L) {
            val burst = referenceBurst(StampResultType.WON, seed)
            assertEquals(4000L, burst.durationMillis)
            assertEquals(4000L, burst.particles.maxOf { it.endMillis })
            for (p in burst.particles) {
                assertTrue(p.endMillis in 3200L..4000L)
                for (t in 0L..burst.durationMillis step 17L) {
                    p.frame(t.toFloat())?.let {
                        assertTrue(it.x.isFinite() && it.y.isFinite() && it.rotation.isFinite())
                        assertTrue(it.x in -.05f..1.05f)
                        assertTrue(it.y in 0f..1.16f)
                    }
                }
                assertNull(p.frame(burst.durationMillis.toFloat()))
                assertNull(p.frame(Float.NaN))
                assertNull(p.frame(Float.POSITIVE_INFINITY))
            }
        }
    }

    @Test fun unsupportedResultTypesCannotCreateAWinOrLossEffect() {
        for (result in listOf(StampResultType.RUNNER, StampResultType.OFFLINE)) {
            assertThrows(IllegalArgumentException::class.java) { referenceBurst(result, 1L) }
        }
    }

    @Test fun ascentIsFasterAtLaunchThenSmoothlyDecelerates() {
        for (p in referenceBurst(StampResultType.WON, 15L).particles) {
            val start = p.delayMillis.toFloat()
            val quarter = p.frame(start + p.riseMillis * .25f)!!
            val progress = (ResultEmojiMotion.START_Y - quarter.y) / (ResultEmojiMotion.START_Y - p.apexY)
            assertEquals(.578125f, progress, .00001f) // Old quadratic ascent covered only .4375.
            val apex = start + p.riseMillis
            val earlyStep = p.frame(start + 16f)!!.y - p.frame(start)!!.y
            val lateStep = p.frame(apex - 16f)!!.y - p.frame(apex - 32f)!!.y
            assertTrue(kotlin.math.abs(earlyStep) > kotlin.math.abs(lateStep) * 10f)
            // Cubic rise/fall acceleration tends to zero on both sides of the turnaround.
            assertTrue(kotlin.math.abs(p.frame(apex - 10f)!!.y - p.apexY) < .00001f)
            assertTrue(kotlin.math.abs(p.frame(apex + 10f)!!.y - p.apexY) < .00001f)
        }
    }

    @Test fun tuningSpeedsIndependentlyChangeTheActualProductionPhases() {
        val base = referenceBurst(StampResultType.WON, 9L)
        val up = ResultEmojiMotion.create(StampResultType.WON, 9L, referenceTuning.copy(upwardSpeed = 2f))
        val fall = ResultEmojiMotion.create(StampResultType.WON, 9L, referenceTuning.copy(fallingSpeed = 2f))
        for (i in base.particles.indices) {
            val p = base.particles[i]
            assertEquals(p.fallMillis, up.particles[i].fallMillis)
            assertEquals(p.riseMillis / 2f, up.particles[i].riseMillis.toFloat(), 1f)
            assertEquals(p.riseMillis, fall.particles[i].riseMillis)
            assertEquals(p.fallMillis / 2f, fall.particles[i].fallMillis.toFloat(), 1f)
        }
        assertTrue(up.durationMillis < base.durationMillis)
        assertTrue(fall.durationMillis < base.durationMillis)
    }

    @Test fun tuningCountSpreadSizeAndRotationReachTheRendererValues() {
        val tuning = referenceTuning.copy(emojiCount = 64, launchSpreadMillis = 200L, sizeScale = 1.5f, rotationScale = 0f)
        val b = ResultEmojiMotion.create(StampResultType.LOST, 1L, tuning)
        assertEquals(64, b.particles.size)
        assertTrue(b.particles.all { it.delayMillis in 0L..199L && it.sizeDp in 33f..75f && it.rotationDegrees == 0f })
        assertEquals(4000L, b.durationMillis)
        val extremes = ResultEmojiMotion.create(StampResultType.WON, 3L,
            ResultAnimationTuning(emojiCount = 999, upwardSpeed = .01f, fallingSpeed = Float.NaN,
                launchSpreadMillis = 1L, sizeScale = Float.POSITIVE_INFINITY))
        assertEquals(80, extremes.particles.size)
        assertTrue(extremes.durationMillis in 1L..8000L)
        assertTrue(extremes.particles.all { it.riseMillis > 0 && it.fallMillis > 0 })
    }

    @Test fun reusableRendererOutputMatchesFramesAt120HzWithoutTimeStepDrift() {
        val out = ResultEmojiFrame()
        for (p in referenceBurst(StampResultType.LOST, 23L).particles) {
            for (step in 0..480) {
                val time = step * (1000f / 120f)
                val value = p.frame(time)
                assertEquals(value != null, p.writeFrame(time, out))
                if (value != null) assertEquals(value, out)
            }
            // Missing render frames must not slow the animation or accumulate integration error.
            val target = p.delayMillis + p.riseMillis * .8f
            p.writeFrame(target / 3f, out)
            p.writeFrame(target, out)
            assertEquals(p.frame(target), out)
        }
    }

    @Test fun newDefaultsExactlyMatchTheUsersSelectedPreset() {
        val selected = ResultAnimationTuning(50, 2f, .7f, 900L, .8f, 2f, .2f, .9f, .96f, .5f, 1.95f)
        assertEquals(selected, ResultAnimationTuning())
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            for (seed in 0L..100L) {
                val burst = ResultEmojiMotion.create(result, seed)
                assertEquals(ResultEmojiMotion.create(result, seed, selected), burst)
                assertEquals(50, burst.particles.size)
                val pool = if (result == StampResultType.WON) WIN_POOL else LOSS_POOL
                assertTrue(burst.particles.all { it.emoji in pool })
                assertTrue(burst.particles.all { it.delayMillis < 900 && it.sizeDp in 17.59f..40f })
                assertTrue(burst.particles.all { kotlin.math.abs(it.rotationDegrees) in 360f..1440f })
                assertTrue(burst.durationMillis in 4000L..4300L)
            }
        }
    }

    @Test fun chosenPresetFramesStayFiniteAndFinishAtTheirOwnDeadline() {
        val frame = ResultEmojiFrame()
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            val burst = ResultEmojiMotion.create(result, 20261029L)
            for (p in burst.particles) {
                for (step in 0..480) {
                    val t = step * (1000f / 120f)
                    if (p.writeFrame(t, frame)) {
                        assertTrue(frame.x.isFinite() && frame.y.isFinite() && frame.rotation.isFinite())
                        assertTrue(frame.x in .019f.. .981f && frame.y in .199f..1.16f)
                    }
                }
                val apex = (p.delayMillis + p.riseMillis).toFloat()
                assertNotEquals(p.frame(apex - 20f), p.frame(apex + 20f))
                assertNull(p.frame(burst.durationMillis.toFloat()))
            }
        }
    }
    @Test fun widerAreaFillsTheRequestedUpperAndLowerRegions() {
        for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
            for (seed in 0L..100L) {
                val tuning = ResultAnimationTuning()
                val p = ResultEmojiMotion.create(result, seed, tuning).particles
                assertTrue(p.maxOf { it.apexY } - p.minOf { it.apexY } > .5f)
                assertEquals((0..4).toSet(), p.map { ((it.apexY - tuning.areaTop) / (tuning.areaBottom - tuning.areaTop) * 5).toInt() }.toSet())
                assertTrue(p.all { it.apexY in tuning.areaTop..tuning.areaBottom })
                assertTrue(p.all { it.startX >= tuning.areaLeft - .00001f && it.endX <= tuning.areaRight + .00001f })
            }
        }
    }

    @Test fun areaAndDriftControlsChangePositionsWithoutChangingTimingOrSpin() {
        val baseTuning = ResultAnimationTuning()
        val base = ResultEmojiMotion.create(StampResultType.WON, 9L, baseTuning)
        val movedTuning = baseTuning.copy(areaTop = .08f, areaBottom = .85f, areaWidth = .3f, areaCenterX = .75f, sidewaysDrift = 0f)
        val moved = ResultEmojiMotion.create(StampResultType.WON, 9L, movedTuning)
        assertEquals(base.durationMillis, moved.durationMillis)
        for ((a, b) in base.particles.zip(moved.particles)) {
            assertEquals(a.delayMillis, b.delayMillis); assertEquals(a.riseMillis, b.riseMillis)
            assertEquals(a.fallMillis, b.fallMillis); assertEquals(a.sizeDp, b.sizeDp)
            assertEquals(a.startRotation, b.startRotation); assertEquals(a.rotationDegrees, b.rotationDegrees)
            assertEquals(a.emoji, b.emoji)
            assertEquals(b.startX, b.endX, 0f)
            assertTrue(b.startX in .5999f.. .9001f && b.apexY in .08f.. .85f)
        }
        assertNotEquals(base.particles.map { it.apexY }, moved.particles.map { it.apexY })
        assertNotEquals(base.particles.map { it.startX }, moved.particles.map { it.startX })
    }

    @Test fun productionRendererFactoryUsesTheFinalPresetForBothOutcomes() {
        val neutral = ResultAnimationTuning(upwardSpeed = 1f, fallingSpeed = 1f)
        for (seed in 0L..100L) {
            for (result in listOf(StampResultType.WON, StampResultType.LOST)) {
                val burst = ResultEmojiMotion.create(result, seed)
                val baseline = ResultEmojiMotion.create(result, seed, neutral)
                val expected = if (result == StampResultType.WON) WIN_POOL else LOSS_POOL
                assertEquals(50, burst.particles.size)
                for ((p, base) in burst.particles.zip(baseline.particles)) {
                    assertTrue(p.emoji in expected)
                    assertEquals(base.riseMillis / 2f, p.riseMillis.toFloat(), 1f)
                    assertEquals(base.fallMillis / .7f, p.fallMillis.toFloat(), 1f)
                    assertTrue(p.apexY in .2f.. .9f)
                    assertTrue(p.startX in .01999f.. .98001f && p.endX in .01999f.. .98001f)
                    assertEquals(base.delayMillis, p.delayMillis)
                    assertEquals(base.startRotation, p.startRotation)
                    assertEquals(base.rotationDegrees, p.rotationDegrees)
                }
            }
        }
    }
}
