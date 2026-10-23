package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Storage manager for custom in-game Quick Chat phrases.
 * Enforces character limits and provides persistence across app sessions.
 * Supports adding custom messages, recent usage reordering, and deleting.
 */
object QuickChatPreferences {
    private const val PREFS_NAME = "bingo_quick_chat_prefs"
    private const val KEY_PHRASES_JSON = "quick_phrases_json"
    private const val KEY_PHRASE_PREFIX = "quick_phrase_"
    const val MAX_PHRASE_LENGTH = 35
    const val MAX_ALLOWED_PHRASES = 20

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
        val jsonStr = prefs.getString(KEY_PHRASES_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    val phrase = jsonArray.optString(i, "").trim()
                    if (phrase.isNotBlank()) {
                        list.add(phrase.take(MAX_PHRASE_LENGTH))
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }

        // Fallback to legacy indexed preferences
        val legacyList = mutableListOf<String>()
        for (i in 0 until 4) {
            val p = prefs.getString("$KEY_PHRASE_PREFIX$i", null)
            if (!p.isNullOrBlank()) {
                legacyList.add(p)
            }
        }
        if (legacyList.isNotEmpty()) {
            saveAllPhrases(context, legacyList)
            return legacyList
        }

        return DEFAULT_PHRASES
    }

    private fun saveAllPhrases(context: Context, phrases: List<String>) {
        val jsonArray = JSONArray()
        phrases.take(MAX_ALLOWED_PHRASES).forEach { phrase ->
            val clean = phrase.trim().take(MAX_PHRASE_LENGTH)
            if (clean.isNotBlank()) {
                jsonArray.put(clean)
            }
        }
        getPrefs(context).edit().putString(KEY_PHRASES_JSON, jsonArray.toString()).apply()
    }

    fun savePhrase(context: Context, index: Int, newPhrase: String) {
        val list = getPhrases(context).toMutableList()
        val cleanPhrase = newPhrase.trim().take(MAX_PHRASE_LENGTH)
        if (cleanPhrase.isBlank()) return

        if (index in 0 until list.size) {
            list[index] = cleanPhrase
        } else if (list.size < MAX_ALLOWED_PHRASES) {
            list.add(cleanPhrase)
        }
        saveAllPhrases(context, list)
    }

    fun addPhrase(context: Context, newPhrase: String): Boolean {
        val list = getPhrases(context).toMutableList()
        val cleanPhrase = newPhrase.trim().take(MAX_PHRASE_LENGTH)
        if (cleanPhrase.isBlank()) return false
        if (list.size >= MAX_ALLOWED_PHRASES) return false

        // Add to front so recently added sits at start
        list.add(0, cleanPhrase)
        saveAllPhrases(context, list)
        return true
    }

    fun deletePhrase(context: Context, index: Int): Boolean {
        val list = getPhrases(context).toMutableList()
        if (index in 0 until list.size && list.size > 1) {
            list.removeAt(index)
            saveAllPhrases(context, list)
            return true
        }
        return false
    }

    fun recordUsedPhrase(context: Context, phrase: String) {
        val list = getPhrases(context).toMutableList()
        if (list.contains(phrase)) {
            list.remove(phrase)
            list.add(0, phrase)
            saveAllPhrases(context, list)
        }
    }

    fun resetToDefaults(context: Context): List<String> {
        val editor = getPrefs(context).edit()
        editor.remove(KEY_PHRASES_JSON)
        for (i in 0 until 10) {
            editor.remove("$KEY_PHRASE_PREFIX$i")
        }
        editor.apply()
        saveAllPhrases(context, DEFAULT_PHRASES)
        return DEFAULT_PHRASES
    }
}
