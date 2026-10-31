package com.bingo.multiplayer.domain

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bingo.multiplayer.R
import com.bingo.multiplayer.core.designsystem.AppSystemBars
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the Android Window implementation used by the activity, without phones/emulators. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppSystemBarsWindowTest {
    private fun withWindow(test: (Window) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java)
        activity.get().setTheme(R.style.Theme_BingoMultiplayer)
        activity.setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        try { test(activity.get().window) }
        finally { activity.pause().stop().destroy() }
    }

    private fun assertStyle(window: Window, dark: Boolean) {
        assertEquals(if (dark) Color.BLACK else Color.rgb(250, 250, 252), window.statusBarColor)
        assertEquals(!dark, WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        assertEquals(if (dark) Color.BLACK else Color.rgb(250, 250, 252),
            (window.decorView.background as ColorDrawable).color)
    }

    @Test @Config(qualifiers = "night")
    fun lightAppUsesLightBarAndDarkIconsEvenWhenAndroidUsesNightResources() = withWindow { window ->
        AppSystemBars.apply(window, dark = false, hidden = false)
        assertStyle(window, dark = false)
    }

    @Test @Config(qualifiers = "notnight")
    fun darkAppUsesBlackBarAndLightIconsEvenWhenAndroidUsesLightResources() = withWindow { window ->
        AppSystemBars.apply(window, dark = true, hidden = false)
        assertStyle(window, dark = true)
    }

    @Test fun focusOrResumeReapplicationRepairsNativeStyleRestoredAfterCompose() = withWindow { window ->
        AppSystemBars.apply(window, dark = false, hidden = false)
        // Reproduce the stale native decor shown in the owner's light-theme screenshot.
        window.statusBarColor = Color.rgb(24, 31, 42)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        AppSystemBars.apply(window, dark = false, hidden = false)
        assertStyle(window, dark = false)
    }

    @Test fun translucentAndForceDarkWindowStateCannotOverrideTheChosenLightBar() = withWindow { window ->
        window.addFlags(LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.decorView.isForceDarkAllowed = true
        window.isStatusBarContrastEnforced = true
        AppSystemBars.apply(window, dark = false, hidden = false)
        assertEquals(0, window.attributes.flags and LayoutParams.FLAG_TRANSLUCENT_STATUS)
        assertTrue(window.attributes.flags and LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS != 0)
        assertFalse(window.decorView.isForceDarkAllowed)
        assertFalse(window.isStatusBarContrastEnforced)
        assertStyle(window, dark = false)
    }

    @Test fun hidingAndShowingPreserveTheSelectedThemeAndRestoreNormalBarBehavior() = withWindow { window ->
        AppSystemBars.apply(window, dark = false, hidden = true)
        assertStyle(window, dark = false)
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
        AppSystemBars.apply(window, dark = true, hidden = true)
        assertStyle(window, dark = true)
        AppSystemBars.apply(window, dark = false, hidden = false)
        assertStyle(window, dark = false)
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
    }

    @Test fun repeatedThemeSwitchesUpdateBothNativeBarColorAndIconContrast() = withWindow { window ->
        for (dark in listOf(false, true, false, true, false)) {
            AppSystemBars.apply(window, dark, hidden = false)
            assertStyle(window, dark)
        }
    }

    @Test @Config(sdk = [27], qualifiers = "night")
    fun olderAndroidAlsoRestoresLightIconsAndBackgroundWithoutCallingNewerViewApis() = withWindow { window ->
        AppSystemBars.apply(window, dark = false, hidden = false)
        assertStyle(window, dark = false)
        AppSystemBars.apply(window, dark = true, hidden = false)
        assertStyle(window, dark = true)
    }

    private fun assertNativeTheme(window: Window, dark: Boolean) {
        val attrs = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.statusBarColor,
            android.R.attr.windowLightStatusBar, android.R.attr.windowDrawsSystemBarBackgrounds,
            android.R.attr.windowTranslucentStatus, android.R.attr.forceDarkAllowed))
        try {
            assertEquals(if (dark) Color.BLACK else Color.rgb(250, 250, 252), attrs.getColor(0, Color.MAGENTA))
            assertEquals(!dark, attrs.getBoolean(1, dark))
            assertTrue(attrs.getBoolean(2, false))
            assertFalse(attrs.getBoolean(3, true))
            assertFalse(attrs.getBoolean(4, true))
        } finally { attrs.recycle() }
    }

    @Test @Config(qualifiers = "notnight")
    fun lightNativeThemeStartsOpaqueWithDarkIconsAndNoAutomaticRecoloring() = withWindow { window ->
        assertNativeTheme(window, dark = false)
    }

    @Test @Config(qualifiers = "night")
    fun darkNativeThemeStartsBlackWithLightIconsAndNoAutomaticRecoloring() = withWindow { window ->
        assertNativeTheme(window, dark = true)
    }
}
