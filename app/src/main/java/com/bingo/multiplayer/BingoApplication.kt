package com.bingo.multiplayer

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class BingoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            com.bingo.multiplayer.core.diagnostics.AppExitDiagnostics.install(this)
        } catch (_: Throwable) {
            // Diagnostics installation failure must never prevent application startup
        }
        try {
            com.bingo.multiplayer.domain.network.AdminControlClient.initialize(this)
        } catch (_: Throwable) {
            // Administrative channel failure must never block normal game launch
        }
        try {
            com.bingo.multiplayer.domain.notifications.NotificationRuntime.init(this)
        } catch (_: Throwable) {
            // Notification initialization failure must never block normal game launch
        }
        try {
            val prefs = getSharedPreferences("bingo_theme_prefs", Context.MODE_PRIVATE)
            val authPrefs = getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
                val targetNightMode = if (isDark) {
                    android.app.UiModeManager.MODE_NIGHT_YES
                } else {
                    android.app.UiModeManager.MODE_NIGHT_NO
                }
                if (uiModeManager != null && uiModeManager.nightMode != targetNightMode) {
                    uiModeManager.setApplicationNightMode(targetNightMode)
                }
            }
        } catch (_: Throwable) {
            // Safe fallback if preferences or AppCompatDelegate fail
        }
    }
}
