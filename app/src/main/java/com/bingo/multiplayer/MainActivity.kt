package com.bingo.multiplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.bingo.multiplayer.core.designsystem.BingoAppTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.core.notification.BingoNotificationHelper
import com.bingo.multiplayer.core.notification.NotificationAction
import com.bingo.multiplayer.core.notification.NotificationActionBus
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.navigation.RootNavGraph

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendsRepository: FriendsRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission response handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Initialize high-priority notification channel for game invites and friend online alerts
        BingoNotificationHelper.createNotificationChannel(applicationContext)

        // Request runtime notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Handle any incoming notification launch intent
        handleNotificationIntent(intent)

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

        // Start background notification daemon, service & periodic wake-up alarms
        com.bingo.multiplayer.core.notification.BingoNotificationDaemon.start(applicationContext)
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, com.bingo.multiplayer.core.notification.BingoPushNotificationService::class.java)
            )
        } catch (_: Exception) {}
        com.bingo.multiplayer.core.notification.BingoAlarmReceiver.scheduleAlarm(applicationContext)

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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            BingoNotificationHelper.ACTION_SEND_INVITE -> {
                val friendUsername = intent.getStringExtra(BingoNotificationHelper.EXTRA_FRIEND_USERNAME) ?: return
                val friendDisplayName = intent.getStringExtra(BingoNotificationHelper.EXTRA_FRIEND_DISPLAY_NAME) ?: friendUsername
                BingoNotificationHelper.cancelFriendOnlineNotification(this, friendUsername)
                NotificationActionBus.postAction(NotificationAction.SendInvite(friendUsername, friendDisplayName))
            }
            BingoNotificationHelper.ACTION_ACCEPT_INVITE -> {
                val roomCode = intent.getStringExtra(BingoNotificationHelper.EXTRA_ROOM_CODE) ?: return
                val hostUsername = intent.getStringExtra(BingoNotificationHelper.EXTRA_HOST_USERNAME) ?: ""
                BingoNotificationHelper.cancelInviteNotification(this, roomCode)
                NotificationActionBus.postAction(NotificationAction.AcceptInvite(roomCode, hostUsername))
            }
        }
    }
}
