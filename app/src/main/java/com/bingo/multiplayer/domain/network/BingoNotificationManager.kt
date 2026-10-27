package com.bingo.multiplayer.domain.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bingo.multiplayer.MainActivity
import com.bingo.multiplayer.R
import com.bingo.multiplayer.core.designsystem.NotificationPreferences
import com.bingo.multiplayer.domain.notifications.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    const val ACTION_ACCEPT_INVITE = "action_accept_invite"
    const val ACTION_DECLINE_INVITE = "action_decline_invite"
    const val ACTION_VIEW_INVITE = "action_view_invite"
    const val ACTION_INVITE_FRIEND = "action_invite_friend"
    const val ACTION_VIEW_FRIENDS = "action_view_friends"
    const val EXTRA_ACTION = "extra_notification_action"
    const val EXTRA_EVENT = "extra_notification_event_v2"
    const val EXTRA_ACCOUNT = "extra_notification_account"
    const val EXTRA_GENERATION = "extra_notification_generation"
    const val EXTRA_SHOW_UPDATE = "extra_show_update"
    private var isInitialized = false
    data class Action(val action: String, val event: NotificationEvent, val generation: String)
    val pendingAction = MutableStateFlow<Action?>(null)
    fun clearPendingActions() { pendingAction.value = null }
    fun isFriend(context: Context, username: String) = NotificationRuntime.isFriend(context, username)
    fun getFriendDisplayName(context: Context, username: String): String {
        val account = NotificationRuntime.account(context) ?: return username
        return NotificationRuntime.friends(context, account).firstOrNull { notificationUsername(it.username) == notificationUsername(username) }?.displayName ?: username
    }
    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

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


    private fun tag(event: NotificationEvent) = "bingo:${event.recipientUid}:${event.kind}:${event.id}"
    private fun id(kind: NotificationKind) = when (kind) {
        NotificationKind.GAME_INVITE -> NOTIFICATION_ID_INVITE
        NotificationKind.FRIEND_ONLINE -> NOTIFICATION_ID_FRIEND_ONLINE
        NotificationKind.FRIEND_REQUEST -> NOTIFICATION_ID_FRIEND_REQUEST
        NotificationKind.FRIEND_ACCEPTED -> NOTIFICATION_ID_FRIEND_ACCEPTED
    }
    private fun intent(context: Context, event: NotificationEvent, action: String, receiver: Boolean = false): PendingIntent {
        val account = NotificationRuntime.account(context)!!
        val target = if (receiver) NotificationActionReceiver::class.java else MainActivity::class.java
        val intent = Intent(context, target).apply {
            this.action = action
            data = Uri.Builder().scheme("bingo").authority("notification").appendPath(account.uid).appendPath(account.generation)
                .appendPath(event.id).appendPath(action).build()
            if (!receiver) flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACTION, action); putExtra(EXTRA_EVENT, Json.encodeToString(event))
            putExtra(EXTRA_ACCOUNT, account.uid); putExtra(EXTRA_GENERATION, account.generation)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (receiver) PendingIntent.getBroadcast(context, 0, intent, flags) else PendingIntent.getActivity(context, 0, intent, flags)
    }
    fun decodeAction(context: Context, intent: Intent): Action? {
        val account = NotificationRuntime.account(context) ?: return null
        if (intent.getStringExtra(EXTRA_ACCOUNT) != account.uid || intent.getStringExtra(EXTRA_GENERATION) != account.generation) return null
        val event = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<NotificationEvent>(intent.getStringExtra(EXTRA_EVENT) ?: return null) }.getOrNull() ?: return null
        if (!NotificationPolicy.eligible(event, account, System.currentTimeMillis()) || NotificationRuntime.ledger(context).handled(account.uid, event.id)) return null
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return null
        val valid = when(event.kind) {
            NotificationKind.GAME_INVITE -> action in setOf(ACTION_ACCEPT_INVITE, ACTION_DECLINE_INVITE, ACTION_VIEW_INVITE)
            NotificationKind.FRIEND_ONLINE -> action == ACTION_INVITE_FRIEND
            else -> action == ACTION_VIEW_FRIENDS
        }
        return if (valid) Action(action, event, account.generation) else null
    }
    fun canPost(context: Context, channel: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }
    fun showEvent(context: Context, event: NotificationEvent) {
        init(context)
        val account = NotificationRuntime.account(context) ?: return
        if (!NotificationPolicy.eligible(event, account, System.currentTimeMillis(),
                push = event.installationId.isNotBlank() || event.generation.isNotBlank()) || AppLifecycleObserver.isAppInForeground.value) return
        if (event.kind == NotificationKind.GAME_INVITE && NotificationRuntime.isBusy(context)) {
            NotificationDiagnostics.record(context, event, "MATCH_BUSY"); return
        }
        if (event.kind == NotificationKind.FRIEND_ONLINE && !isFriend(context, event.senderUsername)) return
        val enabled = when(event.kind) {
            NotificationKind.GAME_INVITE -> NotificationPreferences.canShowSystemInviteNotification(context)
            NotificationKind.FRIEND_ONLINE -> NotificationPreferences.canShowSystemPlayerOnlineNotification(context)
            else -> { NotificationPreferences.init(context); NotificationPreferences.systemNotificationsEnabled.value }
        }
        val channel = if (event.kind == NotificationKind.GAME_INVITE) CHANNEL_INVITES else CHANNEL_SOCIAL
        if (!enabled) { NotificationDiagnostics.record(context, event, "APP_SETTINGS_BLOCKED"); return }
        if (!canPost(context, channel)) { NotificationDiagnostics.record(context, event, "ANDROID_BLOCKED"); return }
        val ledger = NotificationRuntime.ledger(context)
        val claim = ledger.present(event, system = true)
        if (!claim.show) { NotificationDiagnostics.record(context, event, "ALREADY_SHOWN"); return }
        val action = when(event.kind) {
            NotificationKind.GAME_INVITE -> ACTION_VIEW_INVITE
            NotificationKind.FRIEND_ONLINE -> ACTION_INVITE_FRIEND
            else -> ACTION_VIEW_FRIENDS
        }
        val notificationText = com.bingo.multiplayer.domain.notifications.NotificationPresentation.systemText(event)
        try {
            val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notificationText.title)
                .setContentIntent(intent(context, event, action)).setAutoCancel(true).setOnlyAlertOnce(true)
                .setTimeoutAfter((event.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setSilent(!claim.alert)
            notificationText.body?.let { body ->
                builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
            if (event.kind == NotificationKind.GAME_INVITE) {
                builder.setCategory(NotificationCompat.CATEGORY_EVENT)
                    .addAction(R.drawable.ic_notification, "Accept", intent(context, event, ACTION_ACCEPT_INVITE))
                    .addAction(R.drawable.ic_notification, "Decline", intent(context, event, ACTION_DECLINE_INVITE, receiver = true))
                    .setDeleteIntent(intent(context, event, ACTION_DECLINE_INVITE, receiver = true))
            }
            if (!NotificationRuntime.withAccount(context, account) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(tag(event), id(event.kind), builder.build())
                NotificationDiagnostics.record(context, event, "SYSTEM_POSTED")
            }) ledger.presentationFailed(event, true, !claim.alert)
        } catch (_: RuntimeException) {
            ledger.presentationFailed(event, true, !claim.alert)
            NotificationDiagnostics.record(context, event, "DISPLAY_FAILED")
        }
    }
    fun cancelEvent(context: Context, event: NotificationEvent) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(tag(event), id(event.kind))
    }
    fun cancelInvites(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 23) manager.activeNotifications.filter { it.id == NOTIFICATION_ID_INVITE }.forEach { manager.cancel(it.tag, it.id) }
        manager.cancel(NOTIFICATION_ID_INVITE)
    }
    fun cancelAccountNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.activeNotifications.filter { it.tag?.startsWith("bingo:") == true }.forEach { manager.cancel(it.tag, it.id) }
        listOf(NOTIFICATION_ID_INVITE, NOTIFICATION_ID_FRIEND_ONLINE, NOTIFICATION_ID_FRIEND_REQUEST, NOTIFICATION_ID_FRIEND_ACCEPTED,
            NOTIFICATION_ID_BROADCAST).forEach { manager.cancel(it) }
    }
    fun cancelBroadcastNotification(context: Context) { (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID_BROADCAST) }
    fun cancelUpdateNotification(context: Context) { (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID_UPDATE) }
    fun onAppForeground(context: Context) { NotificationRuntime.onForeground(context) }

    fun showUpdate(context: Context, info: AppUpdateInfo) {
        val expected = NotificationRuntime.account(context)
        if (!AppUpdateManager.isOfferForCurrentAccount(context, info)) return
        val currentUid = NotificationRuntime.profile(context)?.uid.orEmpty()
        if (!ApkUpdateOfferPolicy.matchesAccount(info.accountUid, currentUid, expected?.uid)) return
        if (AppLifecycleObserver.isAppInForeground.value || !info.hasUpdate) return
        showNotice(context, ApkUpdateOfferPolicy.noticeKey(info.versionCode, info.latestVersionTag), CHANNEL_UPDATES, NOTIFICATION_ID_UPDATE,
            "Bingo ${info.latestVersionName} is available", "Tap to see what's new and update.", showUpdate = true, expected = expected)
    }
    fun showBroadcast(context: Context, message: BroadcastMessage) {
        val expected = NotificationRuntime.account(context) ?: return
        if (AppLifecycleObserver.isAppInForeground.value || !message.active || message.id.isBlank() ||
            !BroadcastMessageManager.isTargetMatch(message, context) || BroadcastMessageManager.hasMessageBeenSeen(context, message.id)) return
        showNotice(context, "broadcast:${message.id}", CHANNEL_BROADCASTS, NOTIFICATION_ID_BROADCAST, message.title, message.message, expected = expected)
    }
    private fun showNotice(context: Context, key: String, channel: String, id: Int, title: String, body: String, showUpdate: Boolean = false,
        expected: NotificationAccount? = NotificationRuntime.account(context)) {
        init(context); NotificationPreferences.init(context)
        if (!NotificationPreferences.systemNotificationsEnabled.value || !canPost(context, channel)) return
        val account = expected
        val prefs = context.getSharedPreferences("bingo_notice_prefs_v2", Context.MODE_PRIVATE)
        val scopedKey = "${account?.uid ?: "public"}:$key"
        if (prefs.getBoolean(scopedKey, false)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.Builder().scheme("bingo").authority("notice").appendPath(scopedKey).build()
            putExtra(EXTRA_SHOW_UPDATE, showUpdate)
            if (account != null) { putExtra(EXTRA_ACCOUNT, account.uid); putExtra(EXTRA_GENERATION, account.generation) }
        }
        try {
            val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.take(150)).setContentText(body.take(1000))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
                .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            NotificationRuntime.withAccount(context, account) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
                prefs.edit().putBoolean(scopedKey, true).apply()
            }
        } catch (_: RuntimeException) { }
    }
}
