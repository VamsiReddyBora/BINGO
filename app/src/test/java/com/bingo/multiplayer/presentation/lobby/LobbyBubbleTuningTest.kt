package com.bingo.multiplayer.presentation.lobby

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LobbyBubbleTuningTest {
    @Test fun corruptNumericValuesAreSafeAndNormalizationIsIdempotent() {
        val bad = LobbyBubbleTuning(maxWidthDp = Float.NaN, minHeightDp = -10f,
            fontSizeSp = Float.POSITIVE_INFINITY, xDp = Float.NEGATIVE_INFINITY, yDp = -500f,
            topGapDp = -4f, tailHeightDp = Float.NaN, durationMs = 0, startScale = Float.NaN,
            bounce = 20f, bounces = 100, travelDp = -100f, fadePortion = 0f, scrollPauseMs = -1)
        val safe = bad.normalized()
        assertEquals(safe, safe.normalized())
        for (i in 0..1000) {
            val frame = LobbyBubbleMotion.frame(i / 1000f, safe)
            assertTrue(frame.scale.isFinite() && frame.scale > 0f)
            assertTrue(frame.translationYDp.isFinite())
            assertTrue(frame.alpha in 0f..1f)
        }
    }

    @Test fun popBeginsAtTheProfileAndFinishesAtRestWithoutResidualMotion() {
        val t = LobbyBubbleTuning()
        val first = LobbyBubbleMotion.frame(0f, t)
        assertEquals(t.startScale, first.scale, .0001f)
        assertEquals(t.travelDp, first.translationYDp, .0001f)
        assertEquals(0f, first.alpha, .0001f)
        for (p in listOf(1f, 1.1f, 20f)) assertEquals(LobbyBubbleFrame(1f, 0f, 1f), LobbyBubbleMotion.frame(p, t))
        assertEquals(first, LobbyBubbleMotion.frame(Float.NaN, t))
    }

    @Test fun riseAndBounceJoinContinuouslyAndIncludeOvershoot() {
        val t = LobbyBubbleTuning()
        val left = LobbyBubbleMotion.frame(.4199f, t)
        val right = LobbyBubbleMotion.frame(.4201f, t)
        assertTrue(abs(left.scale - right.scale) < .001f)
        assertTrue(abs(left.translationYDp - right.translationYDp) < .001f)
        val frames = (0..1000).map { LobbyBubbleMotion.frame(it / 1000f, t) }
        assertTrue(frames.any { it.scale > 1.1f })
        assertTrue(frames.drop(420).any { it.scale < 1f })
        assertTrue(abs(frames[999].scale - 1f) < .001f)
        assertTrue(abs(frames[999].translationYDp) < .001f)
    }

    @Test fun disablingAnimationAlwaysLeavesTheMessageFullyVisible() {
        val t = LobbyBubbleTuning(animate = false)
        for (p in listOf(-1f, 0f, .2f, .6f, 1f, Float.NaN))
            assertEquals(LobbyBubbleFrame(1f, 0f, 1f), LobbyBubbleMotion.frame(p, t))
    }

}
