package com.bingo.multiplayer.domain.notifications

import android.content.Context
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.bingo.multiplayer.domain.network.BingoFcmManager
import com.bingo.multiplayer.domain.network.BingoNotificationManager
import java.security.MessageDigest

/** Private bounded delivery stages. No tokens, names, account IDs or message content. */
object NotificationDiagnostics {
    fun record(context: Context, event: NotificationEvent?, stage: String, status: Int = 0) =
        recordReference(context, event?.id, event?.kind?.name, stage, status)
    @Synchronized fun recordReference(context: Context, eventId: String?, kind: String?, stage: String, status: Int = 0) {
        runCatching {
            val prefs = context.getSharedPreferences("bingo_notification_diagnostics", Context.MODE_PRIVATE)
            val id = eventId?.let { value -> MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .take(6).joinToString("") { "%02x".format(it) } } ?: "-"
            val safeKind = NotificationKind.entries.firstOrNull { it.name == kind }?.name ?: "-"
            val row = "${System.currentTimeMillis()} ${stage.take(40)} $safeKind $id status=$status"
            val rows = prefs.getString("stages", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(23) + row
            prefs.edit().putString("stages", rows.joinToString("\n")).apply()
        }
    }
    fun read(context: Context): String = buildString {
        append("Android notifications enabled: ${NotificationManagerCompat.from(context).areNotificationsEnabled()}\n")
        append("FCM registration token present: ${!BingoFcmManager.getSavedToken(context).isNullOrBlank()}\n")
        append("Notification account available: ${NotificationRuntime.account(context) != null}\n")
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            append("Invitation channel importance: ${manager?.getNotificationChannel(BingoNotificationManager.CHANNEL_INVITES)?.importance}\n")
            append("Social channel importance: ${manager?.getNotificationChannel(BingoNotificationManager.CHANNEL_SOCIAL)?.importance}\n")
        }
        append(context.getSharedPreferences("bingo_notification_diagnostics", Context.MODE_PRIVATE).getString("stages", "No delivery stages recorded."))
        append('\n')
    }
}
