package com.bingo.multiplayer

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.bingo.multiplayer.core.designsystem.BingoAppTheme
import com.bingo.multiplayer.core.designsystem.AnimationPreferences
import com.bingo.multiplayer.core.designsystem.AppSystemBars
import com.bingo.multiplayer.core.designsystem.AppNativeTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.BingoNotificationManager
import com.bingo.multiplayer.domain.network.BingoFcmManager
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.network.UpdateCheckWorker
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.components.AppUpdateDialog
import com.bingo.multiplayer.presentation.components.BroadcastMessageDialog
import com.bingo.multiplayer.domain.network.BroadcastMessageManager
import com.bingo.multiplayer.presentation.navigation.RootNavGraph

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendsRepository: FriendsRepository
    private val launchReady = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Select the platform theme before Activity creates its native window/decor.
        AppNativeTheme.apply(this, AppNativeTheme.savedDark(this))
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Initialize Theme & Notification Preferences from persistent storage immediately before composing UI
        ThemePreferences.init(applicationContext)
        com.bingo.multiplayer.core.designsystem.NotificationPreferences.init(applicationContext)
        com.bingo.multiplayer.core.designsystem.SoundPreferences.init(applicationContext)
        AnimationPreferences.init(applicationContext)

        applySystemBarStyle()

        // Initialize Notification Channels and background WorkManager checks
        BingoNotificationManager.init(applicationContext)
        BingoFcmManager.init(applicationContext)
        UpdateCheckWorker.schedule(applicationContext)

        // Request runtime notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize presence manager with application context for persistent active user tracking
        PresenceManager.init(applicationContext)
        // Initialize disk cache for player avatars
        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.init(applicationContext)
        // Initialize app-lifecycle-based presence tracking (foreground/background detection)
        AppLifecycleObserver.init()

        authRepository = AuthRepository(applicationContext)
        friendsRepository = FriendsRepository(applicationContext)

        authRepository.getPersistedUserSync()?.takeIf { it.username.isNotBlank() }?.let { user ->
            com.bingo.multiplayer.domain.network.LeaderboardSyncManager.startBootSync(
                applicationContext, user.username, user.uid, friendsRepository, authRepository
            )
        }

        // Process notification launch actions
        handleNotificationIntent(intent)

        // A fixed sleep cannot tell whether startup has finished. Wait for a rendered destination.
        lifecycleScope.launch {
            launchReady.first { it }
            delay(500L)
            AppUpdateManager.checkForUpdates(applicationContext, manual = false)
            delay(500L)
            BroadcastMessageManager.checkForBroadcast(applicationContext)
        }

        setContent {
            val isDark = ThemePreferences.isDarkTheme.value
            val accentId = ThemePreferences.accentColorId.value
            val hideStatusBar = AnimationPreferences.hideStatusBar

            SideEffect {
                AppNativeTheme.apply(this@MainActivity, isDark)
                AppSystemBars.apply(window, isDark, hideStatusBar)
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
                val readyForDialogs by launchReady.collectAsState()
                RootNavGraph(
                    authRepository = authRepository,
                    friendsRepository = friendsRepository,
                    onLaunchReady = { launchReady.value = true }
                )
                if (readyForDialogs) {
                    AppUpdateDialog()
                    BroadcastMessageDialog()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarStyle()
        AppLifecycleObserver.onForegroundImmediate()
        BingoNotificationManager.onAppForeground(applicationContext)
        AppUpdateManager.resumePendingInstall(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarStyle()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarStyle()
        // uiMode is handled without recreating this activity. Apply once more after the
        // framework finishes dispatching its native theme/decor configuration update.
        window.decorView.post { applySystemBarStyle() }
    }

    private fun applySystemBarStyle() {
        AppNativeTheme.apply(this, ThemePreferences.isDarkTheme.value)
        AppSystemBars.apply(window, ThemePreferences.isDarkTheme.value, AnimationPreferences.hideStatusBar)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        AppLifecycleObserver.onBackgroundImmediate()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val action = BingoNotificationManager.decodeAction(applicationContext, intent)
        // Consume launch extras once. Activity recreation cannot repeat an already handled action.
        intent.removeExtra(BingoNotificationManager.EXTRA_EVENT)
        if (action != null) BingoNotificationManager.pendingAction.value = action
        val account = com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(applicationContext)
        val noticeAccount = intent.getStringExtra(BingoNotificationManager.EXTRA_ACCOUNT)
        val noticeGeneration = intent.getStringExtra(BingoNotificationManager.EXTRA_GENERATION)
        if (intent.getBooleanExtra(BingoNotificationManager.EXTRA_SHOW_UPDATE, false) &&
            (noticeAccount == null || (account?.uid == noticeAccount && account.generation == noticeGeneration))) {
            intent.removeExtra(BingoNotificationManager.EXTRA_SHOW_UPDATE)
            AppUpdateManager.checkForUpdates(applicationContext, manual = true)
        }
    }
}
