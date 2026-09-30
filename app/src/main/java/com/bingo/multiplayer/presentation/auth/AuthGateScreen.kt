package com.bingo.multiplayer.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.repository.AuthRepository

/**
 * Minimal Clean Authentication Gatekeeper Screen.
 */
@Composable
fun AuthGateScreen(
    authRepository: AuthRepository,
    onNavigateToLogin: () -> Unit,
    onNavigateToMenu: () -> Unit
) {
    val authState by authRepository.authState.collectAsState()
    val tokens = BingoTheme.colors

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Authenticated -> onNavigateToMenu()
            is AuthState.Unauthenticated -> onNavigateToLogin()
            is AuthState.Loading -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.bingo.multiplayer.R.drawable.ic_bingo_logo),
                contentDescription = "App Icon",
                modifier = Modifier.size(76.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "B I N G O",
                style = BingoTheme.typography.logoTitle,
                color = tokens.cellNeutralText
            )

            Spacer(modifier = Modifier.height(32.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = tokens.accentBrand,
                strokeWidth = 2.5.dp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Checking authentication...",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal,
                color = tokens.cellNeutralText.copy(alpha = 0.5f)
            )
        }
    }
}
