package com.bingo.multiplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class BingoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.bingo.multiplayer.core.diagnostics.AppExitDiagnostics.install(this)
        com.bingo.multiplayer.domain.notifications.NotificationRuntime.init(this)
        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.reloadDurableClosures()
        try {
            val isDark = com.bingo.multiplayer.core.designsystem.AppNativeTheme.savedDark(this)
            com.bingo.multiplayer.core.designsystem.AppNativeTheme.apply(this, isDark)
            val targetMode = if (isDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
            com.bingo.multiplayer.core.designsystem.AppNativeTheme.syncApplicationNightMode(this, isDark)
        } catch (_: Throwable) {
            // Safe fallback if preferences or AppCompatDelegate fail
        }
    }
}
