package com.bingo.multiplayer.presentation.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.R
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.network.OngoingMatchStore
import com.bingo.multiplayer.domain.network.OnlineRoomRegistry
import com.bingo.multiplayer.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aesthetic App Opening Splash Screen (<1s) with full-screen icon animation.
 * Refreshes ongoing matches and authenticates in the background so the user
 * experiences zero UI glitches or flickering cards upon entering the main menu.
 */
@Composable
fun AuthGateScreen(
    authRepository: AuthRepository,
    onNavigateToLogin: () -> Unit,
    onNavigateToMenu: () -> Unit
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current

    val iconScale = remember { Animatable(0.82f) }
    val iconAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 1. Smooth opening scale and alpha reveal animation (~650ms)
        launch {
            iconScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
        }
        launch {
            iconAlpha.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 450, easing = LinearEasing)
            )
        }

        // 2. Background ongoing match refresh & cleanup
        val refreshJob = launch(Dispatchers.IO) {
            try {
                val ongoing = OngoingMatchStore.getOngoingMatch(context)
                if (ongoing != null) {
                    val session = OnlineRoomRegistry.getRoom(ongoing.roomCode)
                    val now = System.currentTimeMillis()
                    if (session != null && (session.status == "CLOSED" || (now - session.lastHeartbeat) > 60_000L || session.players.isEmpty())) {
                        OngoingMatchStore.clearOngoingMatch(context)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Keep full-screen icon opening for ~850ms (< 1s)
        delay(850L)
        refreshJob.join()

        // 4. Ensure auth state is resolved
        var currentAuth = authRepository.authState.value
        var waitIterations = 0
        while (currentAuth is AuthState.Loading && waitIterations < 10) {
            delay(100L)
            currentAuth = authRepository.authState.value
            waitIterations++
        }

        withContext(Dispatchers.Main) {
            when (currentAuth) {
                is AuthState.Authenticated -> onNavigateToMenu()
                is AuthState.Unauthenticated -> onNavigateToLogin()
                else -> onNavigateToMenu()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_bingo_logo),
            contentDescription = "Bingo Logo",
            modifier = Modifier
                .size(112.dp)
                .scale(iconScale.value)
                .alpha(iconAlpha.value)
        )
    }
}
