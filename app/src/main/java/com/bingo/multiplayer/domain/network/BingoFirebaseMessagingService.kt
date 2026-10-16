package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.json.Json

class BingoFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { BingoFcmManager.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) {
        // FCM is a wake-up hint. Durable work verifies the canonical inbox before presenting any kind.
        if (message.data["version"] != "2") return
        val event = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<NotificationEvent>(message.data["event"] ?: return) }.getOrNull() ?: return
        val account = NotificationRuntime.account(applicationContext) ?: return
        if (!NotificationPolicy.eligible(event, account, System.currentTimeMillis(), push = true)) return
        if (NotificationRuntime.ledger(applicationContext).handled(account.uid, event.id)) return
        NotificationWork.sync(applicationContext, account, event.id)
    }
    override fun onDeletedMessages() {
        NotificationRuntime.account(applicationContext)?.let { NotificationWork.sync(applicationContext, it, "deleted-messages") }
    }
}
