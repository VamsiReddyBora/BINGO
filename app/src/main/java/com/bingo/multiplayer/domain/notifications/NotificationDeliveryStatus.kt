package com.bingo.multiplayer.domain.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-actionable registration status. No credentials, raw server bodies or private events. */
object NotificationDeliveryStatus {
    private val state = MutableStateFlow<String?>(null)
    private val reconnect = MutableStateFlow(false)
    val message = state.asStateFlow()
    val needsGoogleAuthorization = reconnect.asStateFlow()
    fun clear() { state.value = null; reconnect.value = false }
    fun failed(error: NotificationApiException) {
        reconnect.value = error.needsGoogleApproval
        state.value = when {
            error.failure == NotificationFailure.GOOGLE_CANCELLED -> "Notification reconnection cancelled. Your player account is unchanged."
            error.failure == NotificationFailure.GOOGLE_CONFIGURATION -> "Google notification sign-in needs a developer configuration fix (code ${error.googleStatus ?: 10}). Please report this through Developer Note."
            error.failure == NotificationFailure.GOOGLE_ACCOUNT_MISMATCH -> "Choose the same Google account you already use for this player."
            error.status == 401 && error.message.orEmpty().contains("older guest") ->
                "This older guest profile needs a new guest sign-in or Google sign-in for background invitations."
            error.needsGoogleApproval -> "Reconnect Google to enable background invitations and friend alerts. Your player data will be kept."
            error.status == 401 || error.status == 403 ->
                "Sign out and sign in again to enable background invitations and friend alerts."
            else -> "Background notifications are temporarily unavailable. They will retry automatically."
        }
    }
    fun pushUnavailable() {
        // Keep an actionable authentication/configuration error until registration really succeeds.
        if (state.value == null) state.value = "Online invitations still work. A background push could not be sent; check notifications and retry."
    }
}
