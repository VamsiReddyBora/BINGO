package com.bingo.multiplayer.domain.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bingo.multiplayer.domain.notifications.NotificationRuntime

/** Local dismissal is committed before returning; remote acknowledgement is durable WorkManager work. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = BingoNotificationManager.decodeAction(context, intent) ?: return
        if (action.action != BingoNotificationManager.ACTION_DECLINE_INVITE) return
        NotificationRuntime.finishInvite(context, action.event.toInvite())
    }
}
