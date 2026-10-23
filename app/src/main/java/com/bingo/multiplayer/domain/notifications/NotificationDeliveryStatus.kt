package com.bingo.multiplayer.domain.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-actionable registration status. No credentials, raw server bodies or private events. */
object NotificationDeliveryStatus {
    private val state = MutableStateFlow<String?>(null)
    val message = state.asStateFlow()
    fun clear() { state.value = null }
    fun failed(error: NotificationApiException) {
        state.value = when {
            error.status == 401 && error.message.orEmpty().contains("older guest") ->
                "This older guest profile needs a new guest sign-in or Google sign-in for background invitations."
            error.status == 401 || error.status == 403 ->
                "Sign out and sign in again to enable background invitations and friend alerts."
            else -> "Background notifications are temporarily unavailable. They will retry automatically."
        }
    }
}
