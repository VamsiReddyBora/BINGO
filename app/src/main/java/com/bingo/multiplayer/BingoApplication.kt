package com.bingo.multiplayer

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class BingoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val prefs = getSharedPreferences("bingo_theme_prefs", Context.MODE_PRIVATE)
            val authPrefs = getSharedPreferences("bingo_user_profile", Context.MODE_PRIVATE)
            val isDark = if (prefs.contains("is_dark_theme")) {
                prefs.getBoolean("is_dark_theme", false)
            } else {
                authPrefs.getBoolean("settings_dark_theme", false)
            }
            val targetMode = if (isDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        } catch (_: Throwable) {
            // Safe fallback if preferences or AppCompatDelegate fail
        }
    }
}
