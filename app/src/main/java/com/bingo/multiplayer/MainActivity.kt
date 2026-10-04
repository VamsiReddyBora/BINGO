package com.bingo.multiplayer

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import com.bingo.multiplayer.core.designsystem.BingoAppTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.components.AppUpdateDialog
import com.bingo.multiplayer.presentation.navigation.RootNavGraph

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendsRepository: FriendsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Clear any old/stale OS notifications from previous app versions
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        } catch (_: Exception) {}

        // Initialize ThemePreferences with saved theme and accent
        ThemePreferences.init(applicationContext)

        val initialIsDark = ThemePreferences.isDarkTheme.value
        val initialBg = if (initialIsDark) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#FAFAFC")
        window.decorView.setBackgroundColor(initialBg)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(initialBg))

        // Initialize presence manager with application context for persistent active user tracking
        PresenceManager.init(applicationContext)
        // Initialize app-lifecycle-based presence tracking (foreground/background detection)
        AppLifecycleObserver.init()

        authRepository = AuthRepository(applicationContext)
        friendsRepository = FriendsRepository(applicationContext)

        // Asynchronously check for app updates in the background
        AppUpdateManager.checkForUpdates(applicationContext, manual = false)

        setContent {
            val isDark = ThemePreferences.isDarkTheme.value
            val accentId = ThemePreferences.accentColorId.value

            val insetsController = remember(isDark) {
                WindowCompat.getInsetsController(window, window.decorView)
            }

            SideEffect {
                // Status and navigation bar icons compatible with active theme (Light vs Pure Black)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                val effectiveBg = if (isDark) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#FAFAFC")
                window.statusBarColor = effectiveBg
                window.navigationBarColor = effectiveBg
                window.decorView.setBackgroundColor(effectiveBg)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(effectiveBg))
            }

            val customMyPick = ThemePreferences.customMyPickHex.value
            val customOpponentPick = ThemePreferences.customOpponentPickHex.value
            val customRecentPick = ThemePreferences.customRecentPickHex.value
            val customCompletedLine = ThemePreferences.customCompletedLineHex.value
            val cellBorderEnabled = ThemePreferences.cellBorderEnabled.value
            val cellBorderColorHex = ThemePreferences.cellBorderColorHex.value

            BingoAppTheme(
                darkTheme = isDark,
                accentColorId = accentId,
                customMyPickHex = customMyPick,
                customOpponentPickHex = customOpponentPick,
                customRecentPickHex = customRecentPick,
                customCompletedLineHex = customCompletedLine,
                cellBorderEnabled = cellBorderEnabled,
                cellBorderColorHex = cellBorderColorHex
            ) {
                RootNavGraph(
                    authRepository = authRepository,
                    friendsRepository = friendsRepository
                )
                AppUpdateDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleObserver.onForegroundImmediate()
    }

    override fun onStop() {
        super.onStop()
        AppLifecycleObserver.onBackgroundImmediate()
    }
}
