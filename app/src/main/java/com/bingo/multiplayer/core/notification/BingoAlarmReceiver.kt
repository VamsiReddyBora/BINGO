package com.bingo.multiplayer.core.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by AlarmManager to ensure BingoNotificationDaemon
 * remains active and reconciles invites/presence even when the app is completely closed.
 * Uses goAsync() to hold a process lock while network checks execute.
 */
class BingoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("BingoAlarmReceiver", "Alarm fired — executing goAsync background sync")
        val app = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                BingoNotificationDaemon.start(app)
                BingoNotificationDaemon.performCloudCheck(app)

                // Restart foreground service if not already running
                try {
                    val serviceIntent = Intent(app, BingoPushNotificationService::class.java)
                    ContextCompat.startForegroundService(app, serviceIntent)
                } catch (e: Exception) {
                    Log.d("BingoAlarmReceiver", "Foreground service start note: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("BingoAlarmReceiver", "Error during alarm execution: ${e.message}")
            } finally {
                // Re-arm next alarm for continuous 45-60s heartbeat
                scheduleAlarm(app)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 40401

        fun scheduleAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, BingoAlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = System.currentTimeMillis() + 45_000L // 45 seconds interval

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                Log.w("BingoAlarmReceiver", "Failed to schedule alarm: ${e.message}")
            }
        }
    }
}
