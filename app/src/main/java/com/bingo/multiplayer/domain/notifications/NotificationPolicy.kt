package com.bingo.multiplayer.domain.notifications

import kotlinx.serialization.Serializable
import java.security.MessageDigest

fun notificationUsername(value: String) = value.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)

@Serializable
enum class NotificationKind(val lifetimeMillis: Long) {
    GAME_INVITE(120_000L), FRIEND_REQUEST(86_400_000L), FRIEND_ACCEPTED(86_400_000L), FRIEND_ONLINE(15_000L)
}

@Serializable
data class NotificationAccount(val uid: String, val username: String, val generation: String, val installationId: String, val sequence: Long = 1L)

@Serializable
data class NotificationEvent(
    val id: String,
    val kind: NotificationKind,
    val senderUid: String,
    val senderUsername: String,
    val senderDisplayName: String,
    val recipientUid: String,
    val recipientUsername: String,
    val createdAt: Long,
    val expiresAt: Long,
    val roomCode: String = "",
    val requestId: String = "",
    val requestTimestamp: Long = 0L,
    val presenceSessionId: String = "",
    val installationId: String = "",
    val generation: String = ""
)

object NotificationPolicy {
    const val FUTURE_TOLERANCE = 5_000L
    fun fresh(event: NotificationEvent, now: Long): Boolean =
        event.createdAt > 0 && event.createdAt <= now + FUTURE_TOLERANCE &&
            event.expiresAt > now && event.expiresAt > event.createdAt &&
            event.expiresAt - event.createdAt <= event.kind.lifetimeMillis

    fun eligible(event: NotificationEvent, account: NotificationAccount?, now: Long, push: Boolean = false): Boolean {
        if (account == null || !fresh(event, now)) return false
        if (event.recipientUid.isNotBlank() && event.recipientUid != account.uid) return false
        if (notificationUsername(event.recipientUsername) != account.username) return false
        if (event.senderUid.isBlank() || event.senderUid == account.uid || notificationUsername(event.senderUsername) == account.username) return false
        if (event.id.isBlank() || event.id.length > 200 || event.senderDisplayName.length > 80) return false
        if (event.kind == NotificationKind.GAME_INVITE && !event.roomCode.matches(Regex("[A-Z0-9]{6}"))) return false
        if (event.kind in setOf(NotificationKind.FRIEND_REQUEST, NotificationKind.FRIEND_ACCEPTED) && event.requestId.isBlank()) return false
        return !push || (event.installationId == account.installationId && event.generation == account.generation)
    }

    fun priority(kind: NotificationKind) = when (kind) {
        NotificationKind.GAME_INVITE -> 0
        NotificationKind.FRIEND_ONLINE -> 1
        NotificationKind.FRIEND_ACCEPTED -> 2
        NotificationKind.FRIEND_REQUEST -> 3
    }

    /** Notification actions must never replace a running game, even when its screen is obscured. */
    fun canJoinInvite(activeMatch: Boolean, sameRoom: Boolean, authenticated: Boolean) = authenticated && !activeMatch && !sameRoom

    fun eventId(kind: String, vararg parts: String): String = kind.lowercase(java.util.Locale.ROOT) + ":" +
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
