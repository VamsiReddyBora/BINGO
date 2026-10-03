package com.bingo.multiplayer.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.bingo.multiplayer.domain.network.GameInviteManager
import com.bingo.multiplayer.domain.network.PresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver handling background notification action buttons like "Decline" without requiring activity launch.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == BingoNotificationHelper.ACTION_DECLINE_INVITE) {
            val roomCode = intent.getStringExtra(BingoNotificationHelper.EXTRA_ROOM_CODE) ?: ""
            val notificationId = intent.getIntExtra(BingoNotificationHelper.EXTRA_NOTIFICATION_ID, -1)
            val myUsernameExtra = intent.getStringExtra(BingoNotificationHelper.EXTRA_MY_USERNAME) ?: ""

            if (notificationId != -1) {
                try {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                } catch (e: Exception) {
                    Log.w("NotificationReceiver", "Failed to cancel notification: ${e.message}")
                }
            }

            if (roomCode.isNotBlank()) {
                NotificationActionBus.postAction(NotificationAction.InviteDeclined(roomCode))
                val targetUser = myUsernameExtra.ifBlank { PresenceManager.currentActiveUsername ?: "" }
                if (targetUser.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            GameInviteManager.removeInvite(targetUser, roomCode)
                        } catch (e: Exception) {
                            Log.w("NotificationReceiver", "Failed to remove invite from cloud: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
