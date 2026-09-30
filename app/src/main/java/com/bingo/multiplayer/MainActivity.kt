package com.bingo.multiplayer

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bingo.multiplayer.core.designsystem.BingoAppTheme
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.navigation.RootNavGraph

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendsRepository: FriendsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        window.statusBarColor = android.graphics.Color.WHITE

        // Initialize presence manager with application context for persistent active user tracking
        PresenceManager.init(applicationContext)
        // Initialize app-lifecycle-based presence tracking (foreground/background detection)
        AppLifecycleObserver.init()

        authRepository = AuthRepository(applicationContext)
        friendsRepository = FriendsRepository(applicationContext)

        setContent {
            // Default colour mode is Light only as instructed
            BingoAppTheme(darkTheme = false) {
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

    override fun onPause() {
        super.onPause()
        AppLifecycleObserver.onBackgroundImmediate()
    }
}
