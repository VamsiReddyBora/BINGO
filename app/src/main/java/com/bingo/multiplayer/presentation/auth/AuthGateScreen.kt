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

    val iconScale = remember { Animatable(1.0f) }
    val iconAlpha = remember { Animatable(1.0f) }

    LaunchedEffect(Unit) {
        // 1. Background ongoing match refresh & cleanup
        val refreshJob = launch(Dispatchers.IO) {
            try {
                val ongoing = OngoingMatchStore.getOngoingMatch(context)
                if (ongoing != null) {
                    val session = OnlineRoomRegistry.getRoom(ongoing.roomCode)
                    if (session != null && session.status == "CLOSED") {
                        OngoingMatchStore.clearOngoingMatch(context)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Keep launch icon visible during opening (~900ms)
        delay(950L)
        refreshJob.join()

        // 4. Ensure auth state is resolved
        var currentAuth = authRepository.authState.value
        var waitIterations = 0
        while (currentAuth is AuthState.Loading && waitIterations < 10) {
            delay(100L)
            currentAuth = authRepository.authState.value
            waitIterations++
        }

        // 5. Smooth cinematic zoom-in transition on app opening exit
        launch {
            iconScale.animateTo(
                targetValue = 1.35f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
            )
        }
        iconAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 350, easing = LinearEasing)
        )

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
                .size(280.dp)
                .scale(iconScale.value)
                .alpha(iconAlpha.value)
        )
    }
}
