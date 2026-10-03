package com.bingo.multiplayer.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver triggered upon device boot or app update.
 * Automatically starts the background notification daemon and schedules alarms
 * so friend online alerts and invitations work without ever opening the app.
 */
class BingoBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BingoBootReceiver", "Boot/Update detected ($action); initializing notification services")
            val app = context.applicationContext
            try {
                BingoNotificationDaemon.start(app)
                BingoAlarmReceiver.scheduleAlarm(app)
            } catch (e: Exception) {
                Log.w("BingoBootReceiver", "Failed to start services on boot: ${e.message}")
            }
        }
    }
}
