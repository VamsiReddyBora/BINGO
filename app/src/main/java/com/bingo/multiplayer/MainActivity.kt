package com.bingo.multiplayer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendsRepository: FriendsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)

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
        // Initialize app-lifecycle-based presence tracking (foreground/background detection)
        AppLifecycleObserver.init()

        authRepository = AuthRepository(applicationContext)
        friendsRepository = FriendsRepository(applicationContext)

        // Process notification launch actions
        handleNotificationIntent(intent)

        // Asynchronously check for app updates and active broadcast silently after launch splash animation completes
        lifecycleScope.launch {
            delay(3500)
            AppUpdateManager.checkForUpdates(applicationContext, manual = false)
            delay(500)
            BroadcastMessageManager.checkForBroadcast(applicationContext)
        }

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
                BroadcastMessageDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleObserver.onForegroundImmediate()
        BingoNotificationManager.onAppForeground(applicationContext)
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
        BingoNotificationManager.onAppForeground(applicationContext)

        val action = intent.getStringExtra(BingoNotificationManager.EXTRA_ACTION)
        val roomCode = intent.getStringExtra(BingoNotificationManager.EXTRA_ROOM_CODE)
            ?: intent.getStringExtra("roomCode")
        val fromUsername = intent.getStringExtra(BingoNotificationManager.EXTRA_FROM_USERNAME)
            ?: intent.getStringExtra("fromUsername").orEmpty()
        val fromDisplayName = intent.getStringExtra(BingoNotificationManager.EXTRA_FROM_DISPLAY_NAME)
            ?: intent.getStringExtra("fromDisplayName").orEmpty()
        val timestamp = intent.getLongExtra(BingoNotificationManager.EXTRA_TIMESTAMP, System.currentTimeMillis())

        when (action) {
            BingoNotificationManager.ACTION_ACCEPT_INVITE -> {
                // Rule 3A: User tapped "Accept" on notification.
                // Cancel notification, clear any popup, queue direct lobby join.
                BingoNotificationManager.cancelInviteNotification(applicationContext)
                com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
                if (!roomCode.isNullOrBlank()) {
                    BingoNotificationManager.pendingJoinRoomCode.value = roomCode
                }
            }
            BingoNotificationManager.ACTION_VIEW_INVITE -> {
                // Rule 3B: User tapped notification body without clicking Accept or Decline.
                // Cancel OS tray notification, but show the in-app popup dialog!
                BingoNotificationManager.cancelInviteNotification(applicationContext)
                BingoNotificationManager.pendingJoinRoomCode.value = null
                if (!roomCode.isNullOrBlank()) {
                    val invite = com.bingo.multiplayer.domain.network.GameInvite(
                        fromUsername = fromUsername.ifBlank { "Friend" },
                        fromDisplayName = fromDisplayName.ifBlank { fromUsername.ifBlank { "Friend" } },
                        roomCode = roomCode,
                        timestamp = timestamp
                    )
                    com.bingo.multiplayer.domain.network.GameInviteManager.deliverForegroundInvite(invite)
                }
            }
            BingoNotificationManager.ACTION_VIEW_FRIENDS -> {
                BingoNotificationManager.pendingNavigateToFriends.value = true
            }
            else -> {
                // Normal app launch: do not auto-queue join room.
            }
        }
    }
}
