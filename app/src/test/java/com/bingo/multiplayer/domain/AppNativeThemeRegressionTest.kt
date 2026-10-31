package com.bingo.multiplayer.domain

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.os.Looper
import androidx.core.view.WindowCompat
import com.bingo.multiplayer.R
import com.bingo.multiplayer.core.designsystem.AppSystemBars
import com.bingo.multiplayer.core.designsystem.AppNativeTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.core.diagnostics.SystemBarDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Covers the native theme and real preference setter missing from window-colour tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppNativeThemeRegressionTest {
    private fun lightNativeTheme(context: Context): Boolean {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        return try { attrs.getBoolean(0, false) } finally { attrs.recycle() }
    }

    private fun nativeDarkStatusIcons(context: Context): Boolean {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        return try { attrs.getBoolean(0, false) } finally { attrs.recycle() }
    }

    private fun withActivity(test: (Activity) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java)
        owner.get().setTheme(R.style.Theme_BingoMultiplayer)
        owner.setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        try { test(owner.get()) }
        finally { owner.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "night")
    fun lightAppMakesNativeDecorLightWhilePhoneResourcesAreDark() = withActivity { activity ->
        AppSystemBars.apply(activity.window, dark = false, hidden = false)
        assertTrue("Native decor theme must match the light Compose theme", lightNativeTheme(activity))
        assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
    }

    @Test @Config(qualifiers = "notnight")
    fun darkAppMakesNativeDecorDarkWhilePhoneResourcesAreLight() = withActivity { activity ->
        AppSystemBars.apply(activity.window, dark = true, hidden = false)
        assertFalse("Native decor theme must match the dark Compose theme", lightNativeTheme(activity))
    }

    @Test @Config(qualifiers = "night")
    fun runtimeThemeSwitchesKeepNativeThemeAndStatusIconsInStep() = withActivity { activity ->
        for (dark in listOf(false, true, false)) {
            AppSystemBars.apply(activity.window, dark = dark, hidden = false)
            assertEquals(!dark, lightNativeTheme(activity))
            assertEquals(!dark, WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
        }
        assertFalse("Theme changes must not finish the activity", activity.isFinishing)
    }

    @Test fun selectingLightReplacesStaleDarkAppOverrideEvenWhenPhoneIsAlreadyLight() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        manager.setNightMode(UiModeManager.MODE_NIGHT_NO)
        manager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        ThemePreferences.setDarkThemeDirect(app, false)
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(manager).applicationNightMode)
        assertFalse(ThemePreferences.isDarkTheme.value)
        assertFalse(app.getSharedPreferences("bingo_theme_prefs", 0).getBoolean("is_dark_theme", true))
    }

    @Test fun selectingDarkReplacesStaleLightAppOverrideEvenWhenPhoneIsAlreadyDark() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        manager.setNightMode(UiModeManager.MODE_NIGHT_YES)
        manager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        ThemePreferences.setDarkThemeDirect(app, true)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(manager).applicationNightMode)
        assertTrue(ThemePreferences.isDarkTheme.value)
    }

    @Test @Config(qualifiers = "night")
    fun coldStartSelectsSavedLightThemeBeforeNativeWindowCreation() {
        val owner = Robolectric.buildActivity(Activity::class.java)
        val activity = owner.get()
        activity.getSharedPreferences("bingo_theme_prefs", 0).edit().putBoolean("is_dark_theme", false).commit()
        AppNativeTheme.apply(activity, AppNativeTheme.savedDark(activity))
        owner.setup().visible()
        try {
            assertTrue(lightNativeTheme(activity))
            assertEquals(android.graphics.Color.rgb(250, 250, 252), activity.window.statusBarColor)
        } finally { owner.pause().stop().destroy() }
    }

    @Test @Config(sdk = [27], qualifiers = "night")
    fun legacyAndroidUsesSavedAuthThemeWhenNewThemePreferenceIsAbsent() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("bingo_theme_prefs", 0).edit().clear().commit()
        app.getSharedPreferences("bingo_auth_prefs", 0).edit().putBoolean("settings_dark_theme", false).commit()
        AppNativeTheme.apply(app, AppNativeTheme.savedDark(app))
        // isLightTheme became a public framework attribute in API 29. On API 27,
        // verify the supported native status-bar attribute rather than an absent ID.
        assertTrue(nativeDarkStatusIcons(app))
        app.getSharedPreferences("bingo_auth_prefs", 0).edit().putBoolean("settings_dark_theme", true).commit()
        AppNativeTheme.apply(app, AppNativeTheme.savedDark(app))
        assertFalse(nativeDarkStatusIcons(app))
        withActivity { activity ->
            AppSystemBars.apply(activity.window, dark = false, hidden = false)
            assertTrue(SystemBarDiagnostics.read(app).contains("Window native light theme: unavailable before Android 10"))
        }
    }

    @Test @Config(qualifiers = "night")
    fun diagnosticsReportLiveNativeThemeAndWindowReadback() = withActivity { activity ->
        ThemePreferences.isDarkTheme.value = false
        AppSystemBars.apply(activity.window, dark = false, hidden = false)
        val text = SystemBarDiagnostics.read(activity.applicationContext)
        assertTrue(text.contains("Bingo dark theme: false"))
        assertTrue(text.contains("Window native light theme: true"))
        assertTrue(text.contains("Status-bar colour readback: #fffafafc"))
        assertTrue(text.contains("Dark status icons requested: true"))
    }

    @Test fun diagnosticsExposeActualOverridesInsteadOfRepeatingLastRequestedStyle() = withActivity { activity ->
        AppSystemBars.apply(activity.window, dark = false, hidden = false)
        activity.window.statusBarColor = android.graphics.Color.RED
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
        val text = SystemBarDiagnostics.read(activity.applicationContext)
        assertTrue(text.contains("Status-bar colour readback: #ffff0000"))
        assertTrue(text.contains("Dark status icons requested: false"))
    }
}
