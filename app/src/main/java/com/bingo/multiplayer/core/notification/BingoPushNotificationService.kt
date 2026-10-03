package com.bingo.multiplayer.core.notification

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Legacy service stub retained for backwards compatibility with intents.
 * Immediately stops itself and purges any leftover persistent/silent notification.
 * Background alarms and presence checks are handled cleanly by BingoAlarmReceiver and BingoNotificationDaemon.
 */
class BingoPushNotificationService : Service() {

    companion object {
        const val SERVICE_NOTIFICATION_ID = 40402
        const val SERVICE_CHANNEL_ID = "bingo_background_sync_channel"
    }

    override fun onCreate() {
        super.onCreate()
        purgeSilentNotification()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        purgeSilentNotification()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun purgeSilentNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(SERVICE_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager?.deleteNotificationChannel(SERVICE_CHANNEL_ID)
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
