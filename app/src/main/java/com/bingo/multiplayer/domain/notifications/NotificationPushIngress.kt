package com.bingo.multiplayer.domain.notifications

import kotlinx.serialization.json.Json

/** Shared entry used by FirebaseMessagingService; payloads stay account/expiry scoped. */
object NotificationPushIngress {
    private val json = Json { ignoreUnknownKeys = true }
    fun event(data: Map<String, String>, account: NotificationAccount?, now: Long,
        handled: (String, String) -> Boolean): NotificationEvent? {
        if (data["version"] != "2") return null
        val event = runCatching { json.decodeFromString<NotificationEvent>(data["event"] ?: return null) }.getOrNull() ?: return null
        if (!NotificationPolicy.eligible(event, account, now, push = true)) return null
        if (handled(account!!.uid, event.id)) return null
        return event
    }
}
