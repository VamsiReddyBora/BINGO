package com.bingo.multiplayer.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bingo.multiplayer.MainActivity
import com.bingo.multiplayer.R

/**
 * Foreground Service keeping BingoNotificationDaemon alive so push notifications
 * for friend alerts and game invitations work 24/7 even when the app is in the background,
 * swiped away, or completely closed.
 */
class BingoPushNotificationService : Service() {

    companion object {
        const val SERVICE_NOTIFICATION_ID = 40402
        const val SERVICE_CHANNEL_ID = "bingo_background_sync_channel"
        private const val TAG = "BingoPushService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BingoPushNotificationService onCreate: promoting to foreground")
        createServiceNotificationChannel()
        startInForeground()

        BingoNotificationDaemon.start(applicationContext)
        BingoAlarmReceiver.scheduleAlarm(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BingoPushNotificationService onStartCommand: ensuring foreground daemon active")
        createServiceNotificationChannel()
        startInForeground()

        BingoNotificationDaemon.start(applicationContext)
        BingoAlarmReceiver.scheduleAlarm(applicationContext)
        return START_STICKY
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Background Connection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps multiplayer invites and friend online alerts connected"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startInForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bingo Multiplayer")
            .setContentText("Connected for invites & friend alerts")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground error: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App swiped from recent apps; maintaining notification daemon")
        BingoNotificationDaemon.start(applicationContext)
        BingoNotificationDaemon.performCloudCheck(applicationContext)
        BingoAlarmReceiver.scheduleAlarm(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Service destroyed; scheduling alarm wake-up")
        BingoAlarmReceiver.scheduleAlarm(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
