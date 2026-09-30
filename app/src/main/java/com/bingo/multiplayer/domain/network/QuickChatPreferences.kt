package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Storage manager for custom in-game Quick Chat phrases.
 * Enforces character limits and provides persistence across app sessions.
 */
object QuickChatPreferences {
    private const val PREFS_NAME = "bingo_quick_chat_prefs"
    private const val KEY_PHRASE_PREFIX = "quick_phrase_"
    const val MAX_PHRASE_LENGTH = 25
    const val MAX_PHRASES = 4

    val DEFAULT_PHRASES = listOf(
        "Good pick! 👍",
        "So close! ⚡",
        "Nice move! 🎯",
        "Watch this! 🔥"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPhrases(context: Context): List<String> {
        val prefs = getPrefs(context)
        return (0 until MAX_PHRASES).map { idx ->
            prefs.getString("$KEY_PHRASE_PREFIX$idx", null)
                ?: DEFAULT_PHRASES.getOrElse(idx) { "Good game!" }
        }
    }

    fun savePhrase(context: Context, index: Int, newPhrase: String) {
        if (index !in 0 until MAX_PHRASES) return
        val cleanPhrase = newPhrase.trim().take(MAX_PHRASE_LENGTH)
        getPrefs(context).edit().putString("$KEY_PHRASE_PREFIX$index", cleanPhrase).apply()
    }

    fun resetToDefaults(context: Context): List<String> {
        val editor = getPrefs(context).edit()
        DEFAULT_PHRASES.forEachIndexed { index, phrase ->
            editor.putString("$KEY_PHRASE_PREFIX$index", phrase)
        }
        editor.apply()
        return DEFAULT_PHRASES
    }
}
