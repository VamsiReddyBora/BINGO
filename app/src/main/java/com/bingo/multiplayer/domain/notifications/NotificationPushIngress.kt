package com.bingo.multiplayer.domain.notifications

import kotlinx.serialization.json.Json

/** Shared entry used by FirebaseMessagingService; payloads stay account/expiry scoped. */
object NotificationPushIngress {
    private val json = Json { ignoreUnknownKeys = true }
    /** FCM is delivered by our authenticated relay; invites/online alerts need no second network hop. */
    fun dispatch(data: Map<String, String>, account: NotificationAccount?, now: Long,
        handled: (String, String) -> Boolean, present: (NotificationEvent) -> Unit,
        synchronize: (NotificationEvent) -> Unit): Boolean {
        val event = event(data, account, now, handled) ?: return false
        try {
            if (event.kind == NotificationKind.GAME_INVITE || event.kind == NotificationKind.FRIEND_ONLINE) present(event)
        } finally { synchronize(event) }
        return true
    }
    fun event(data: Map<String, String>, account: NotificationAccount?, now: Long,
        handled: (String, String) -> Boolean): NotificationEvent? {
        if (data["version"] != "2") return null
        val event = runCatching { json.decodeFromString<NotificationEvent>(data["event"] ?: return null) }.getOrNull() ?: return null
        if (!NotificationPolicy.eligible(event, account, now, push = true)) return null
        if (handled(account!!.uid, event.id)) return null
        return event
    }
}
