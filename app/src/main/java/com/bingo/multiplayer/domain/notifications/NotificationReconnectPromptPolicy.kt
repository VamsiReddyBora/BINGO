package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.network.UpdateState

/** A reminder is an explicit foreground choice, never an automatic Google sign-in. */
internal object NotificationReconnectPromptPolicy {
    private val processSession = java.util.UUID.randomUUID().toString()
    // A restored task must not carry yesterday's Later choice into a fresh process.
    fun accountKey(account: NotificationAccount, session: String = processSession): String =
        "$session:${account.uid}:${account.generation}"

    fun pendingAccount(current: NotificationAccount?, savedKey: String?): NotificationAccount? =
        current?.takeIf { savedKey != null && accountKey(it) == savedKey }

    fun shouldShow(account: NotificationAccount?, isGoogle: Boolean, needsApproval: Boolean,
                   renderedHome: Boolean, foreground: Boolean, blocked: Boolean,
                   handledAccount: String?): Boolean =
        account != null && isGoogle && needsApproval && renderedHome && foreground && !blocked &&
            handledAccount != accountKey(account)

    fun updateBlocksPrompt(state: UpdateState): Boolean =
        state !is UpdateState.Idle && state !is UpdateState.UpToDate
}
