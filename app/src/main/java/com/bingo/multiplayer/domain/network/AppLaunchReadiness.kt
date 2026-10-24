package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Startup depends only on local authentication. Room recovery belongs to the visible menu. */
internal object AppLaunchReadiness {
    suspend fun awaitLocalAuth(auth: StateFlow<AuthState>): AuthState {
        delay(1000L) // Preserve the existing opening animation.
        return withTimeoutOrNull(500L) { auth.first { it !is AuthState.Loading } } ?: auth.value
    }
}

/** A completed checkpoint is a local result, never a request to rejoin its old room. */
internal object SavedMatchOpening {
    fun showResultIfFinished(data: OngoingMatchData, showResult: (OngoingMatchData) -> Unit): Boolean {
        if (!data.isMatchFinished) return false
        showResult(data)
        return true
    }
}
