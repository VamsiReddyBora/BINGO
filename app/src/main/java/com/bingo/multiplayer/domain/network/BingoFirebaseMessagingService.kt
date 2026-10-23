package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BingoFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { BingoFcmManager.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) {
        NotificationDiagnostics.record(applicationContext, null, "FCM_RECEIVED")
        val account = NotificationRuntime.account(applicationContext) ?: return
        val accepted = NotificationPushIngress.dispatch(message.data, account, System.currentTimeMillis(),
            handled = { uid, id -> NotificationRuntime.ledger(applicationContext).handled(uid, id) },
            present = { NotificationRuntime.receivePush(applicationContext, it) },
            synchronize = { NotificationWork.sync(applicationContext, account, it.id) })
        if (!accepted) NotificationDiagnostics.record(applicationContext, null, "PUSH_REJECTED")
    }
    override fun onDeletedMessages() {
        NotificationRuntime.account(applicationContext)?.let { NotificationWork.sync(applicationContext, it, "deleted-messages") }
    }
}
