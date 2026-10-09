package com.bingo.multiplayer.domain.network

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BingoFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM registration token received: ${token.take(15)}...")
        BingoFcmManager.saveToken(applicationContext, token)

        val localUser = BroadcastMessageManager.getLocalUsername(applicationContext)
        if (localUser.isNotBlank()) {
            BingoFcmManager.syncTokenToCloud(applicationContext, localUser, token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "FCM message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"].orEmpty()
            Log.d(TAG, "FCM message data type: $type")

            when (type) {
                "GAME_INVITE" -> {
                    val roomCode = data["roomCode"].orEmpty()
                    val fromUsername = data["fromUsername"].orEmpty()
                    val fromDisplayName = data["fromDisplayName"].orEmpty().ifBlank { fromUsername }
                    val fromAvatarUrl = data["fromAvatarUrl"] ?: data["avatarUrl"]
                    val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

                    if (roomCode.isNotBlank() && fromUsername.isNotBlank()) {
                        val invite = GameInvite(
                            fromUsername = fromUsername,
                            fromDisplayName = fromDisplayName,
                            fromAvatarUrl = fromAvatarUrl,
                            roomCode = roomCode,
                            timestamp = timestamp
                        )
                        val isSelfTest = roomCode == "FCMTEST" || fromUsername == "fcm_test"
                        val inForeground = AppLifecycleObserver.isAppInForeground.value

                        if (!isSelfTest && inForeground) {
                            // Rule 1: App is open (in foreground) - In-app notification only
                            Log.d(TAG, "Game invite received via FCM while app in foreground — delivering in-app only")
                            GameInviteManager.deliverForegroundInvite(invite)
                        } else {
                            // Rule 2 & 3: App is in background (recents) or completely closed
                            // Notify via push notification AND stage in-app invite
                            Log.d(TAG, "Game invite received via FCM while app in background/closed — posting push notification and staging invite")
                            BingoNotificationManager.showInviteNotification(applicationContext, invite, forceShow = isSelfTest)
                            GameInviteManager.deliverForegroundInvite(invite)
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "FCM message type '$type' suppressed (focusing strictly on game invites)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BingoFcmService"
    }
}
