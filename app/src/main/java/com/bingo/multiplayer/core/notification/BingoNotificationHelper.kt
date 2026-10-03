package com.bingo.multiplayer.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bingo.multiplayer.MainActivity
import com.bingo.multiplayer.R
import com.bingo.multiplayer.domain.network.GameInvite
import kotlin.math.absoluteValue

object BingoNotificationHelper {
    const val CHANNEL_ID = "bingo_alerts_channel"
    private const val CHANNEL_NAME = "Game Alerts & Invitations"
    private const val CHANNEL_DESC = "Notifications for friends coming online and multiplayer game invitations"

    const val ACTION_SEND_INVITE = "com.bingo.multiplayer.ACTION_SEND_INVITE"
    const val ACTION_ACCEPT_INVITE = "com.bingo.multiplayer.ACTION_ACCEPT_INVITE"
    const val ACTION_DECLINE_INVITE = "com.bingo.multiplayer.ACTION_DECLINE_INVITE"

    const val EXTRA_FRIEND_USERNAME = "extra_friend_username"
    const val EXTRA_FRIEND_DISPLAY_NAME = "extra_friend_display_name"
    const val EXTRA_ROOM_CODE = "extra_room_code"
    const val EXTRA_HOST_USERNAME = "extra_host_username"
    const val EXTRA_MY_USERNAME = "extra_my_username"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    /**
     * Creates high-priority notification channel for game invites and friend online alerts.
     */
    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        // Explicitly cancel and purge any old foreground service notification and channel
        manager?.cancel(40402)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                manager?.deleteNotificationChannel("bingo_background_sync_channel")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Shows a clean, polished notification when a friend comes online.
     * Content: "$friendDisplayName is online"
     * Action: "Invite to Play" -> opens app, hosts an online match, and invites friend.
     */
    fun showFriendOnlineNotification(
        context: Context,
        friendUsername: String,
        friendDisplayName: String
    ) {
        if (!hasNotificationPermission(context)) return

        val cleanUsername = friendUsername.trim().lowercase().removePrefix("@")
        val notificationId = 10000 + (cleanUsername.hashCode().absoluteValue % 10000)

        // PendingIntent for clicking "Send Invite" action button or the notification itself
        val sendInviteIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SEND_INVITE
            putExtra(EXTRA_FRIEND_USERNAME, cleanUsername)
            putExtra(EXTRA_FRIEND_DISPLAY_NAME, friendDisplayName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sendInvitePendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            sendInviteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val displayName = friendDisplayName.ifBlank { "@$cleanUsername" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .apply {
                if (largeIcon != null) setLargeIcon(largeIcon)
            }
            .setContentTitle("👋 $displayName is online")
            .setContentText("Tap to invite and start a Bingo match!")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("👋 $displayName is Online")
                    .bigText("$displayName is active now. Tap below to invite them to a live match!")
            )
            .setColor(0xFF10B981.toInt())
            .setContentIntent(sendInvitePendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                0,
                "Invite to Play",
                sendInvitePendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    /**
     * Shows a clean, polished notification when a match invitation is received.
     * Content: "$displayName invited you to play Bingo in room #$cleanRoomCode"
     * Actions:
     * - "Accept" -> opens app, joins host lobby.
     * - "Decline" -> closes notification, removes cloud invite.
     */
    fun showGameInviteNotification(
        context: Context,
        invite: GameInvite,
        myUsername: String = ""
    ) {
        if (!hasNotificationPermission(context)) return

        val cleanRoomCode = invite.roomCode.trim().uppercase()
        val notificationId = 20000 + (cleanRoomCode.hashCode().absoluteValue % 10000)

        // Accept Action -> Opens MainActivity with ACTION_ACCEPT_INVITE
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_ACCEPT_INVITE
            putExtra(EXTRA_ROOM_CODE, cleanRoomCode)
            putExtra(EXTRA_HOST_USERNAME, invite.fromUsername)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline Action -> Broadcast to NotificationActionReceiver
        val declineIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DECLINE_INVITE
            putExtra(EXTRA_ROOM_CODE, cleanRoomCode)
            putExtra(EXTRA_HOST_USERNAME, invite.fromUsername)
            putExtra(EXTRA_MY_USERNAME, myUsername)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 50000,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val displayName = invite.fromDisplayName.ifBlank { "@${invite.fromUsername}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .apply {
                if (largeIcon != null) setLargeIcon(largeIcon)
            }
            .setContentTitle("🎮 Match Invitation from $displayName")
            .setContentText("Invited you to play in room #$cleanRoomCode")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("🎮 Match Invitation from $displayName")
                    .bigText("$displayName invited you to play Bingo!\nRoom Code: #$cleanRoomCode\nTap Accept to join the lobby.")
            )
            .setColor(0xFF4F46E5.toInt())
            .setContentIntent(acceptPendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .addAction(
                0,
                "Accept",
                acceptPendingIntent
            )
            .addAction(
                0,
                "Decline",
                declinePendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    /**
     * Cancels invitation notification for a specific room.
     */
    fun cancelInviteNotification(context: Context, roomCode: String) {
        val cleanRoomCode = roomCode.trim().uppercase()
        val notificationId = 20000 + (cleanRoomCode.hashCode().absoluteValue % 10000)
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {}
    }

    /**
     * Cancels friend online notification for a specific user.
     */
    fun cancelFriendOnlineNotification(context: Context, friendUsername: String) {
        val cleanUsername = friendUsername.trim().lowercase().removePrefix("@")
        val notificationId = 10000 + (cleanUsername.hashCode().absoluteValue % 10000)
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {}
    }
}
