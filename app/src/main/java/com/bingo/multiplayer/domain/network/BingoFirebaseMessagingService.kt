package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BingoFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { BingoFcmManager.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) {
        // FCM is a wake-up hint. Durable work verifies the canonical inbox before presenting any kind.
        val account = NotificationRuntime.account(applicationContext) ?: return
        val event = NotificationPushIngress.event(message.data, account, System.currentTimeMillis()) { uid, id ->
            NotificationRuntime.ledger(applicationContext).handled(uid, id)
        } ?: return
        NotificationWork.sync(applicationContext, account, event.id)
    }
    override fun onDeletedMessages() {
        NotificationRuntime.account(applicationContext)?.let { NotificationWork.sync(applicationContext, it, "deleted-messages") }
    }
}
