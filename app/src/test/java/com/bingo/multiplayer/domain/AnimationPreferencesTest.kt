package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.ResultAnimationTuning
import com.bingo.multiplayer.presentation.game.ResultEmojiMotion
import com.bingo.multiplayer.core.designsystem.AnimationPreferences
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AnimationPreferencesTest {
    @After fun restoreDefault() { AnimationPreferences.load(FakeSharedPreferences()) }

    @Test fun aNewInstallEnablesTheOptionalEffect() {
        AnimationPreferences.load(FakeSharedPreferences())
        assertTrue(AnimationPreferences.winningEmojiAnimationEnabled)
    }

    @Test fun turningTheEffectOffSurvivesLoadingPreferencesAgain() {
        val prefs = FakeSharedPreferences()
        AnimationPreferences.load(prefs)
        AnimationPreferences.save(prefs, false)
        assertFalse(AnimationPreferences.winningEmojiAnimationEnabled)
        AnimationPreferences.load(FakeSharedPreferences())
        assertTrue(AnimationPreferences.winningEmojiAnimationEnabled)
        AnimationPreferences.load(prefs)
        assertFalse(AnimationPreferences.winningEmojiAnimationEnabled)
    }

    @Test fun turningItBackOnAlsoPersists() {
        val prefs = FakeSharedPreferences()
        AnimationPreferences.save(prefs, false)
        AnimationPreferences.save(prefs, true)
        AnimationPreferences.load(prefs)
        assertTrue(AnimationPreferences.winningEmojiAnimationEnabled)
        assertEquals(1, prefs.all.size)
    }

    @Test fun oldExperimentalTuningDoesNotChangeTheSavedAnimationToggle() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean("winning_emoji_animation", false)
            .putInt("emoji_count", 80).putFloat("upward_speed", 2.5f)
            .putFloat("falling_speed", .5f).putLong("launch_spread_ms", 100L)
            .putFloat("size_scale", 1.5f).putFloat("rotation_scale", 0f).apply()
        val before = prefs.all.toMap()
        AnimationPreferences.load(prefs)
        assertFalse(AnimationPreferences.winningEmojiAnimationEnabled)
        assertEquals(before, prefs.all)
        AnimationPreferences.save(prefs, true)
        AnimationPreferences.load(prefs)
        assertTrue(AnimationPreferences.winningEmojiAnimationEnabled)
        assertEquals(80, prefs.getInt("emoji_count", 0))
    }
    @Test fun retiredTuningAndEmojiKeysCannotOverrideTheFinalPresetAfterUpgrade() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean("winning_emoji_animation", false)
            .putInt("emoji_count", 10).putFloat("upward_speed", .5f)
            .putFloat("falling_speed", 2.5f).putLong("launch_spread_ms", 100L)
            .putFloat("size_scale", 1.5f).putFloat("rotation_scale", 0f)
            .putFloat("area_top", .05f).putFloat("area_bottom", .25f)
            .putFloat("area_width", .2f).putFloat("area_center_x", .8f)
            .putFloat("sideways_drift", 0f).putString("win_emoji_selection_v1", "🍗|🍿")
            .putString("loss_emoji_selection_v1", "🐷").apply()
        val before = prefs.all.toMap()
        val burst = ResultEmojiMotion.create(com.bingo.multiplayer.presentation.game.StampResultType.WON, 84L)
        AnimationPreferences.load(prefs)
        assertFalse(AnimationPreferences.winningEmojiAnimationEnabled)
        assertEquals(before, prefs.all)
        assertEquals(burst, ResultEmojiMotion.create(com.bingo.multiplayer.presentation.game.StampResultType.WON, 84L))
        assertEquals(ResultAnimationTuning(50, 2f, .7f, 900L, .8f, 2f, .2f, .9f, .96f, .5f, 1.95f), ResultAnimationTuning())
        AnimationPreferences.save(prefs, true)
        AnimationPreferences.load(prefs)
        assertTrue(AnimationPreferences.winningEmojiAnimationEnabled)
        assertEquals(before.filterKeys { it != "winning_emoji_animation" }, prefs.all.filterKeys { it != "winning_emoji_animation" })
    }

    @Test fun spreadAreaBoundsRemainValidAtEverySliderExtreme() {
        for (top in listOf(Float.NaN, -.5f, .05f, .8f, 2f)) {
            for (bottom in listOf(Float.POSITIVE_INFINITY, -.1f, .15f, .9f)) {
                for (width in listOf(Float.NaN, -.1f, .2f, .9f, .96f, 2f)) {
                    val t = ResultAnimationTuning(areaTop = top, areaBottom = bottom, areaWidth = width,
                        areaCenterX = Float.NaN, sidewaysDrift = Float.POSITIVE_INFINITY).normalized()
                    assertTrue(t.areaTop.isFinite() && t.areaBottom.isFinite() && t.areaCenterX.isFinite())
                    assertTrue(t.areaBottom - t.areaTop >= .09999f)
                    assertTrue(t.areaLeft >= .01999f && t.areaRight <= .98001f)
                    assertEquals(t, t.normalized())
                }
            }
        }
    }
}
