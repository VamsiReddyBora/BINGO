package com.bingo.multiplayer.domain.notifications

internal data class NotificationText(val title: String, val body: String?)

/** Shared wording for foreground messages and the Android notification layout. */
internal object NotificationPresentation {
    fun message(event: NotificationEvent): String = when (event.kind) {
        NotificationKind.GAME_INVITE -> "${event.senderDisplayName} invited you to room #${event.roomCode}"
        NotificationKind.FRIEND_REQUEST -> "👥 @${event.senderUsername} sent you a friend request!"
        NotificationKind.FRIEND_ACCEPTED -> "🎉 @${event.senderUsername} accepted your friend request! You are now friends."
        NotificationKind.FRIEND_ONLINE -> "🟢 ${event.senderDisplayName} is online"
    }

    fun systemText(event: NotificationEvent): NotificationText = when (event.kind) {
        NotificationKind.FRIEND_ONLINE -> NotificationText(message(event), null)
        NotificationKind.GAME_INVITE -> NotificationText("🎮 Match invite from ${event.senderDisplayName}", message(event))
        NotificationKind.FRIEND_REQUEST -> NotificationText("👥 New friend request", message(event))
        NotificationKind.FRIEND_ACCEPTED -> NotificationText("🎉 Friend request accepted", message(event))
    }
}
