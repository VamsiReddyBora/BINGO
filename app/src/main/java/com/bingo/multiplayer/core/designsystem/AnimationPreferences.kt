package com.bingo.multiplayer.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AnimationFeatureFlags {
    /** Master switch for win/loss emoji cannons. False overrides the saved player toggle. */
    const val WINNING_EMOJI_ANIMATION_ENABLED = true
}

/** Cosmetic, device-local preference: never changes account data or match rules. */
object AnimationPreferences {
    private const val PREFS_NAME = "bingo_animation_prefs"
    private const val KEY_WINNING_EMOJI = "winning_emoji_animation"
    private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"

    var hideStatusBar by mutableStateOf(false)
        private set

    var winningEmojiAnimationEnabled by mutableStateOf(true)
        private set
    fun init(context: Context) = load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    internal fun load(preferences: SharedPreferences) {
        winningEmojiAnimationEnabled = preferences.getBoolean(KEY_WINNING_EMOJI, true)
        hideStatusBar = preferences.getBoolean(KEY_HIDE_STATUS_BAR, false)
    }

    fun setWinningEmojiAnimationEnabled(context: Context, enabled: Boolean) =
        save(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), enabled)

    internal fun save(preferences: SharedPreferences, enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WINNING_EMOJI, enabled).apply()
        winningEmojiAnimationEnabled = enabled
    }

    fun setHideStatusBar(context: Context, enabled: Boolean) =
        saveHideStatusBar(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), enabled)

    internal fun saveHideStatusBar(preferences: SharedPreferences, enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HIDE_STATUS_BAR, enabled).apply()
        hideStatusBar = enabled
    }

}
