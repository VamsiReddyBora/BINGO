package com.bingo.multiplayer.core.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NotificationAction {
    data class SendInvite(val friendUsername: String, val friendDisplayName: String) : NotificationAction
    data class AcceptInvite(val roomCode: String, val hostUsername: String) : NotificationAction
    data class InviteDeclined(val roomCode: String) : NotificationAction
}

/**
 * Thread-safe event bus bridging Android Notification interactions and Compose navigation/state machine.
 */
object NotificationActionBus {
    private val _pendingAction = MutableStateFlow<NotificationAction?>(null)
    val pendingAction: StateFlow<NotificationAction?> = _pendingAction.asStateFlow()

    fun postAction(action: NotificationAction) {
        _pendingAction.value = action
    }

    fun consumeAction(): NotificationAction? {
        val current = _pendingAction.value
        _pendingAction.value = null
        return current
    }

    fun clear() {
        _pendingAction.value = null
    }
}
