package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences
import com.bingo.multiplayer.presentation.components.ALL_REACTION_EMOJIS
import org.json.JSONArray

/**
 * Storage manager for User Favorite Emojis & Recent Emojis.
 * Guarantees that in the game reaction strip:
 * 1. Favorite emojis ALWAYS show first at the beginning of the strip (no scrolling required).
 * 2. Recent emojis appear immediately following favorites.
 * 3. All remaining emojis from the library follow.
 */
object EmojiPreferences {
    private const val PREFS_NAME = "bingo_emoji_preferences"
    private const val KEY_FAVORITES_JSON = "favorite_emojis_json"
    private const val KEY_RECENTS_JSON = "recent_emojis_json"

    const val MIN_FAVORITES = 1
    const val MAX_FAVORITES = 10
    const val MAX_RECENTS = 20

    val DEFAULT_FAVORITES = listOf("🔥", "😂", "🎯", "👏", "😱")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getFavoriteEmojis(context: Context): List<String> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_FAVORITES_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    val emoji = jsonArray.optString(i, "").trim()
                    if (emoji.isNotBlank() && !list.contains(emoji)) {
                        list.add(emoji)
                    }
                }
                if (list.isNotEmpty()) return list.take(MAX_FAVORITES)
            } catch (_: Exception) {}
        }
        return DEFAULT_FAVORITES
    }

    fun saveFavoriteEmojis(context: Context, favorites: List<String>) {
        val clean = favorites.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_FAVORITES)
        val finalFavorites = if (clean.isEmpty()) DEFAULT_FAVORITES else clean

        val jsonArray = JSONArray()
        finalFavorites.forEach { jsonArray.put(it) }
        getPrefs(context).edit().putString(KEY_FAVORITES_JSON, jsonArray.toString()).apply()
    }

    fun addFavoriteEmoji(context: Context, emoji: String): Boolean {
        val list = getFavoriteEmojis(context).toMutableList()
        val clean = emoji.trim()
        if (clean.isBlank() || list.contains(clean) || list.size >= MAX_FAVORITES) return false
        list.add(clean)
        saveFavoriteEmojis(context, list)
        return true
    }

    fun removeFavoriteEmoji(context: Context, emoji: String): Boolean {
        val list = getFavoriteEmojis(context).toMutableList()
        val clean = emoji.trim()
        if (list.size > MIN_FAVORITES && list.remove(clean)) {
            saveFavoriteEmojis(context, list)
            return true
        }
        return false
    }

    fun isFavoriteEmoji(context: Context, emoji: String): Boolean {
        return getFavoriteEmojis(context).contains(emoji.trim())
    }

    fun toggleFavoriteEmoji(context: Context, emoji: String): Boolean {
        val clean = emoji.trim()
        return if (isFavoriteEmoji(context, clean)) {
            removeFavoriteEmoji(context, clean)
            false
        } else {
            addFavoriteEmoji(context, clean)
        }
    }

    fun getRecentEmojis(context: Context): List<String> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_RECENTS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    val emoji = jsonArray.optString(i, "").trim()
                    if (emoji.isNotBlank() && !list.contains(emoji)) {
                        list.add(emoji)
                    }
                }
                return list.take(MAX_RECENTS)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    fun recordUsedEmoji(context: Context, emoji: String) {
        val clean = emoji.trim()
        if (clean.isBlank()) return

        val recents = getRecentEmojis(context).toMutableList()
        recents.remove(clean)
        recents.add(0, clean)

        val jsonArray = JSONArray()
        recents.take(MAX_RECENTS).forEach { jsonArray.put(it) }
        getPrefs(context).edit().putString(KEY_RECENTS_JSON, jsonArray.toString()).apply()
    }

    /**
     * Builds the ordered reaction strip for live games:
     * 1. Favorite emojis FIRST (always pinned at the start)
     * 2. Recent emojis SECOND (excluding favorites)
     * 3. Remainder of ALL_REACTION_EMOJIS (excluding favorites and recents)
     */
    fun getComposedReactionStrip(context: Context): List<String> {
        val favorites = getFavoriteEmojis(context)
        val recents = getRecentEmojis(context).filter { !favorites.contains(it) }
        val rest = ALL_REACTION_EMOJIS.filter { !favorites.contains(it) && !recents.contains(it) }
        return favorites + recents + rest
    }

    fun resetFavoritesToDefault(context: Context): List<String> {
        getPrefs(context).edit().remove(KEY_FAVORITES_JSON).apply()
        return DEFAULT_FAVORITES
    }
}
