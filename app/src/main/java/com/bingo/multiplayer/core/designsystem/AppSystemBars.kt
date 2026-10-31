package com.bingo.multiplayer.core.designsystem

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Native window styling follows the app's theme, including after Android restores window state. */
internal object AppSystemBars {
    fun apply(window: Window, dark: Boolean, hidden: Boolean) {
        AppNativeTheme.apply(window.context, dark)
        com.bingo.multiplayer.core.diagnostics.SystemBarDiagnostics.observe(window)
        val background = if (dark) Color.BLACK else Color.rgb(250, 250, 252)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Compose already renders both themes; Android must not recolour the decor again.
            window.decorView.isForceDarkAllowed = false
            window.isStatusBarContrastEnforced = false
        }
        // Obtain the current controller after window attach/configuration changes, rather
        // than retaining the controller created before the native decor was attached.
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = if (hidden)
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (hidden) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.setBackgroundColor(background)
        window.setBackgroundDrawable(ColorDrawable(background))
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}
