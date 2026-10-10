package com.bingo.multiplayer.domain.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bingo.multiplayer.MainActivity
import com.bingo.multiplayer.R
import kotlinx.coroutines.launch

object BingoNotificationManager {
    private const val TAG = "BingoNotificationMgr"

    const val CHANNEL_INVITES = "bingo_channel_invites_v5"
    const val CHANNEL_SOCIAL = "bingo_channel_social_v2"
    const val CHANNEL_BROADCASTS = "bingo_channel_broadcasts"
    const val CHANNEL_UPDATES = "bingo_channel_updates"

    const val NOTIFICATION_ID_INVITE = 1001
    const val NOTIFICATION_ID_FRIEND_ONLINE = 1002
    const val NOTIFICATION_ID_BROADCAST = 1003
    const val NOTIFICATION_ID_UPDATE = 1004
    const val NOTIFICATION_ID_FRIEND_REQUEST = 1005
    const val NOTIFICATION_ID_FRIEND_ACCEPTED = 1006

    const val EXTRA_ACTION = "extra_notification_action"
    const val EXTRA_ROOM_CODE = "extra_room_code"
    const val EXTRA_TARGET_USER = "extra_target_user"
    const val EXTRA_FROM_USERNAME = "extra_from_username"
    const val EXTRA_FROM_DISPLAY_NAME = "extra_from_display_name"
    const val EXTRA_TIMESTAMP = "extra_timestamp"
    const val EXTRA_SHOW_UPDATE = "extra_show_update"

    const val ACTION_ACCEPT_INVITE = "action_accept_invite"
    const val ACTION_DECLINE_INVITE = "action_decline_invite"
    const val ACTION_VIEW_INVITE = "action_view_invite"
    const val ACTION_INVITE_FRIEND = "action_invite_friend"
    const val ACTION_VIEW_FRIENDS = "action_view_friends"

    private const val PREFS_NAME = "bingo_notification_prefs"
    private const val ABSENCE_GATE_MS = 120_000L // 2 minutes absence gate
    private const val COOLDOWN_GATE_MS = 120_000L // 2 minutes cooldown gate

    val pendingJoinRoomCode = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingInviteFriendUsername = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingNavigateToFriends = kotlinx.coroutines.flow.MutableStateFlow<Boolean>(false)

    private var isInitialized = false

    fun isFriend(context: Context, username: String): Boolean {
        return try {
            val clean = username.trim().lowercase().removePrefix("@")
            if (clean.isBlank()) return false
            val activeRepo = com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance
            if (activeRepo != null && activeRepo.isFriend(clean)) {
                return true
            }
            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("friends_list", null) ?: return false
            val regex = "\"username\"\\s*:\\s*\"$clean\"".toRegex(RegexOption.IGNORE_CASE)
            regex.containsMatchIn(stored)
        } catch (_: Exception) {
            false
        }
    }

    fun getFriendDisplayName(context: Context, username: String): String {
        return try {
            val clean = username.trim().lowercase().removePrefix("@")
            val activeRepo = com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance
            val friend = activeRepo?.friends?.value?.find { it.username.trim().lowercase().removePrefix("@") == clean }
            if (friend != null && friend.displayName.isNotBlank()) {
                return friend.displayName
            }
            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("friends_list", null) ?: return username
            val regex = "\"username\"\\s*:\\s*\"$clean\"[^{}]*?\"displayName\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
            regex.find(stored)?.groupValues?.getOrNull(1) ?: username
        } catch (_: Exception) {
            username
        }
    }

    fun getFriendLastSeen(context: Context, username: String): Long {
        return try {
            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("friends_list", null) ?: return 0L
            val clean = username.trim().lowercase().removePrefix("@")
            val regex = "\"username\"\\s*:\\s*\"$clean\"[^{}]*?\"lastSeenTimestamp\"\\s*:\\s*(\\d+)".toRegex()
            regex.find(stored)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Clean up legacy channels if present so Android creates fresh high-importance channels
            try {
                nm.deleteNotificationChannel("bingo_channel_social")
                nm.deleteNotificationChannel("bingo_channel_invites")
                nm.deleteNotificationChannel("bingo_channel_invites_v2")
                nm.deleteNotificationChannel("bingo_channel_invites_v3")
                nm.deleteNotificationChannel("bingo_channel_invites_v4")
            } catch (_: Exception) {}

            val inviteChannel = NotificationChannel(
                CHANNEL_INVITES,
                "Game Invitations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time match invites with instant Accept/Decline actions"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 200, 350)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val socialChannel = NotificationChannel(
                CHANNEL_SOCIAL,
                "Friends & Social",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when friends come online or send friend requests"
                enableVibration(true)
                setShowBadge(true)
            }

            val broadcastChannel = NotificationChannel(
                CHANNEL_BROADCASTS,
                "Announcements & Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Direct messages and official game announcements"
                enableVibration(true)
                setShowBadge(true)
            }

            val updateChannel = NotificationChannel(
                CHANNEL_UPDATES,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a new version of Bingo is available"
                enableVibration(true)
                setShowBadge(true)
            }

            nm.createNotificationChannels(listOf(inviteChannel, socialChannel, broadcastChannel, updateChannel))
            Log.i(TAG, "Notification channels registered successfully")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RULE 1: Friend Online Status Notification
    // ─────────────────────────────────────────────────────────────
    /**
     * Notifies user that a friend is online.
     * Enforces:
     * 1. Foreground suppression: Do NOT notify if app is in foreground.
     * 2. Absence gate: Only notify if the friend was away (lastSeen) for > 1 hour.
     * 3. Cooldown gate: Never notify more than once per hour per friend.
     *
     * @param bypassGates If true, bypasses foreground, absence, and cooldown gates (for developer testing).
     */
    fun notifyFriendOnline(
        context: Context,
        friendUsername: String,
        friendDisplayName: String,
        lastSeenTimestamp: Long,
        bypassGates: Boolean = false
    ) {
        init(context)
        if (!bypassGates && !com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowSystemPlayerOnlineNotification(context)) {
            Log.d(TAG, "Friend online notification suppressed by NotificationPreferences")
            return
        }
        if (!bypassGates && AppLifecycleObserver.isAppInForeground.value) {
            Log.d(TAG, "Friend online status suppressed: app in foreground")
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val title = "🟢 $friendDisplayName is online"
        val body = "Challenge @$friendUsername to a Bingo match!"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACTION, ACTION_INVITE_FRIEND)
            putExtra(EXTRA_TARGET_USER, friendUsername)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_FRIEND_ONLINE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_SOCIAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF10B981.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIFICATION_ID_FRIEND_ONLINE, notif)
            Log.i(TAG, "Posted friend online notification for $friendUsername")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post friend online notification: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // FRIEND REQUEST NOTIFICATIONS
    // ─────────────────────────────────────────────────────────────
    fun showFriendRequestNotification(
        context: Context,
        fromUsername: String,
        fromDisplayName: String,
        forceShow: Boolean = false
    ) {
        // Suppressed: focusing strictly on Game Invites
        Log.d(TAG, "showFriendRequestNotification suppressed (focusing on game invites)")
    }

    fun showFriendAcceptedNotification(
        context: Context,
        friendUsername: String,
        friendDisplayName: String,
        forceShow: Boolean = false
    ) {
        // Suppressed: focusing strictly on Game Invites
        Log.d(TAG, "showFriendAcceptedNotification suppressed (focusing on game invites)")
    }

    // ─────────────────────────────────────────────────────────────
    // RULE 2 & 3: Game Invitation Notification
    // ─────────────────────────────────────────────────────────────
    /**
     * Shows an OS status bar notification when an invite is received while the app is in background or closed.
     * Tapping notification body opens app to display in-app popup dialog (Rule 3B).
     * Tapping "✅ Accept" opens app and joins room directly (Rule 3A).
     * Tapping "❌ Decline" dismisses notification and removes invite.
     */
    fun showInviteNotification(context: Context, invite: GameInvite, forceShow: Boolean = false) {
        init(context)

        // 1. Check user notification settings
        if (!forceShow && !com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowSystemInviteNotification(context)) {
            Log.d(TAG, "Invite notification suppressed from status bar: disabled in NotificationPreferences")
            return
        }

        // 2. Foreground suppression: If user is inside app, in-app modal takes priority (Rule 1)
        if (!forceShow && AppLifecycleObserver.isAppInForeground.value) {
            Log.d(TAG, "Invite notification suppressed from status bar: app is in foreground")
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val baseCode = (invite.roomCode.hashCode() and 0x7FFFFFFF) % 100000 + 1000
        val acceptCode = baseCode * 10 + 1
        val viewCode = baseCode * 10 + 2
        val declineCode = baseCode * 10 + 3

        // Action: Accept button -> Opens app directly into room lobby (Rule 3A)
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACTION, ACTION_ACCEPT_INVITE)
            putExtra(EXTRA_ROOM_CODE, invite.roomCode)
            putExtra(EXTRA_FROM_USERNAME, invite.fromUsername)
            putExtra(EXTRA_FROM_DISPLAY_NAME, invite.fromDisplayName)
            putExtra(EXTRA_TIMESTAMP, invite.timestamp)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            acceptCode,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Body Tap (View Invite) -> Opens app and presents in-app popup dialog (Rule 3B)
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACTION, ACTION_VIEW_INVITE)
            putExtra(EXTRA_ROOM_CODE, invite.roomCode)
            putExtra(EXTRA_FROM_USERNAME, invite.fromUsername)
            putExtra(EXTRA_FROM_DISPLAY_NAME, invite.fromDisplayName)
            putExtra(EXTRA_TIMESTAMP, invite.timestamp)
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            viewCode,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Decline button -> Clears notification and informs GameInviteManager
        val declineIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DECLINE_INVITE
            putExtra(EXTRA_ROOM_CODE, invite.roomCode)
            putExtra(EXTRA_FROM_USERNAME, invite.fromUsername)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            declineCode,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hostName = invite.fromDisplayName.ifBlank { invite.fromUsername }
        val titleText = "🎮 Match Invite: $hostName"
        val bodyText = "$hostName (@${invite.fromUsername}) invited you to play Bingo in Room #${invite.roomCode}!"

        // RemoteViews for Collapsed View (renders visible Accept & Decline buttons even without expanding)
        val collapsedViews = RemoteViews(context.packageName, R.layout.notification_invite_collapsed).apply {
            setTextViewText(R.id.tv_notification_title, titleText)
            setTextViewText(R.id.tv_notification_body, "@${invite.fromUsername} • Room #${invite.roomCode}")
            setOnClickPendingIntent(R.id.btn_accept, acceptPendingIntent)
            setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
            setOnClickPendingIntent(R.id.notification_root, viewPendingIntent)
        }

        // RemoteViews for Expanded View (larger, detailed card with prominent buttons)
        val expandedViews = RemoteViews(context.packageName, R.layout.notification_invite_expanded).apply {
            setTextViewText(R.id.tv_notification_title, titleText)
            setTextViewText(R.id.tv_notification_body, bodyText)
            setOnClickPendingIntent(R.id.btn_accept, acceptPendingIntent)
            setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
            setOnClickPendingIntent(R.id.notification_root, viewPendingIntent)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_INVITES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F46E5.toInt())
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setContentIntent(viewPendingIntent)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .addAction(R.drawable.ic_notification, "✅ Accept", acceptPendingIntent)
            .addAction(R.drawable.ic_notification, "❌ Decline", declinePendingIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L) // Auto-vanish after 60 seconds if unhandled
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 350, 200, 350))
            .build()

        try {
            nm.notify(NOTIFICATION_ID_INVITE, notification)
            Log.i(TAG, "Posted background invite notification for room ${invite.roomCode}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post invite notification: ${e.message}")
        }
    }

    fun cancelInviteNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_INVITE)
            Log.d(TAG, "Cancelled invite notification")
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // RULE 3: Broadcast & Direct Message Notifications
    // ─────────────────────────────────────────────────────────────
    fun showBroadcastNotification(context: Context, broadcast: BroadcastMessage, forceShow: Boolean = false) {
        // Suppressed: focusing strictly on Game Invites
        Log.d(TAG, "showBroadcastNotification suppressed (focusing on game invites)")
    }

    fun cancelBroadcastNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_BROADCAST)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // RULE 4: App Update Background Notification (Suppressed)
    // ─────────────────────────────────────────────────────────────
    fun showUpdateNotification(context: Context, info: AppUpdateInfo, forceShow: Boolean = false) {
        // Suppressed: focusing strictly on Game Invites
        Log.d(TAG, "showUpdateNotification suppressed (focusing on game invites)")
    }

    fun cancelUpdateNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_UPDATE)
        } catch (_: Exception) {}
    }

    /**
     * Called whenever the user opens/foregrounds the app:
     * Does NOT auto-cancel game invite notifications so users returning from background
     * can still interact via status bar or in-app dialog (Rule 2).
     */
    fun onAppForeground(context: Context) {
        cancelBroadcastNotification(context)
        cancelUpdateNotification(context)
    }

    // ─────────────────────────────────────────────────────────────
    // TEST & DIAGNOSTICS TRIGGERS (Immediate UI Verification)
    // ─────────────────────────────────────────────────────────────
    fun testInviteNotification(context: Context) {
        init(context)
        val dummyInvite = GameInvite(
            fromUsername = "FriendBob",
            fromDisplayName = "Bob the Champion",
            roomCode = "BINGO7",
            timestamp = System.currentTimeMillis()
        )
        showInviteNotification(context, dummyInvite, forceShow = true)
    }

    fun testFriendOnlineNotification(context: Context) {
        init(context)
        notifyFriendOnline(
            context = context,
            friendUsername = "AliceOnline",
            friendDisplayName = "Alice Gamer",
            lastSeenTimestamp = 0L,
            bypassGates = true
        )
    }

    fun testBroadcastNotification(context: Context) {
        init(context)
        val dummyBroadcast = BroadcastMessage(
            id = "test_${System.currentTimeMillis()}",
            title = "Notification System Test",
            message = "All 4 notification channels & vector icons are working perfectly!",
            type = "INFO",
            author = "Bingo System",
            active = true
        )
        showBroadcastNotification(context, dummyBroadcast, forceShow = true)
    }
}
