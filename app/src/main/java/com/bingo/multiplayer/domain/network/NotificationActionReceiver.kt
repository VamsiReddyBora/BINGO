package com.bingo.multiplayer.domain.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("NotificationActionRcvr", "Received action: $action")

        when (action) {
            BingoNotificationManager.ACTION_DECLINE_INVITE -> {
                val roomCode = intent.getStringExtra(BingoNotificationManager.EXTRA_ROOM_CODE).orEmpty()
                BingoNotificationManager.cancelInviteNotification(context)
                GameInviteManager.clearForegroundInvite()
                if (roomCode.isNotBlank()) {
                    Log.i("NotificationActionRcvr", "User declined match invite for room $roomCode")
                    val myUsername = BroadcastMessageManager.getLocalUsername(context)
                    if (myUsername.isNotBlank()) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            GameInviteManager.removeInvite(myUsername, roomCode)
                        }
                    }
                }
            }
        }
    }
}
