package com.bingo.multiplayer.core.diagnostics

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bingo.multiplayer.core.designsystem.AnimationPreferences
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import java.lang.ref.WeakReference

/** Live, user-copied window state. A weak reference never keeps a destroyed activity alive. */
internal object SystemBarDiagnostics {
    @Volatile private var observed = WeakReference<Window>(null)

    fun observe(window: Window) { observed = WeakReference(window) }

    fun read(context: Context): String = buildString {
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Bingo dark theme: ${ThemePreferences.isDarkTheme.value}\n")
        append("Hide status bar: ${AnimationPreferences.hideStatusBar}\n")
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        append("Phone night-mode setting: ${manager?.nightMode}\n")
        append("Application resource night: ${isNight(context.applicationContext)}\n")
        val window = observed.get()
        if (window == null) {
            append("Native window: unavailable\n")
            return@buildString
        }
        val decor = window.decorView
        append("Window resource night: ${isNight(window.context)}\n")
        append("Window native light theme: ${isLight(window.context)}\n")
        append("Decor native light theme: ${isLight(decor.context)}\n")
        append("Status-bar colour readback: #${Integer.toHexString(window.statusBarColor)}\n")
        append("Dark status icons requested: ${WindowCompat.getInsetsController(window, decor).isAppearanceLightStatusBars}\n")
        append("Window flags: 0x${Integer.toHexString(window.attributes.flags)}\n")
        append("Decor system-UI flags: 0x${Integer.toHexString(decor.systemUiVisibility)}\n")
        append("Decor attached/focused: ${decor.isAttachedToWindow}/${decor.hasWindowFocus()}\n")
        append("Decor size: ${decor.width}x${decor.height}\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            append("Force Dark allowed: ${decor.isForceDarkAllowed}\n")
            append("Status-bar contrast enforced: ${window.isStatusBarContrastEnforced}\n")
        }
        ViewCompat.getRootWindowInsets(decor)?.let { insets ->
            append("Status bar visible: ${insets.isVisible(WindowInsetsCompat.Type.statusBars())}\n")
            append("Status-bar top inset: ${insets.getInsets(WindowInsetsCompat.Type.statusBars()).top}\n")
        }
    }

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun isLight(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable before Android 10"
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        return try { attrs.getBoolean(0, false).toString() } finally { attrs.recycle() }
    }
}
