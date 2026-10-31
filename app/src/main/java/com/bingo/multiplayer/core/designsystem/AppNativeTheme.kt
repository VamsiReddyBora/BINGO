package com.bingo.multiplayer.core.designsystem

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.bingo.multiplayer.R

/** Compose colours and the platform decor must select the same app theme. */
internal object AppNativeTheme {
    fun savedDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences("bingo_theme_prefs", Context.MODE_PRIVATE)
        return if (prefs.contains("is_dark_theme")) prefs.getBoolean("is_dark_theme", false)
        else context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            .getBoolean("settings_dark_theme", false)
    }

    fun apply(context: Context, dark: Boolean) {
        // Explicit resources avoid a dark platform theme underneath a light Compose UI
        // when the phone's uiMode differs from Bingo's independently selected theme.
        context.setTheme(if (dark) R.style.Theme_BingoMultiplayer_Dark else R.style.Theme_BingoMultiplayer_Light)
    }

    fun syncApplicationNightMode(context: Context, dark: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                // nightMode reports the PHONE setting, not the application's override.
                // Always supply the chosen app mode; the system handles unchanged values.
                manager?.setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            } catch (_: RuntimeException) {
                // Native decor and Compose still follow the explicit theme if the OEM rejects this API.
            }
        }
    }
}
