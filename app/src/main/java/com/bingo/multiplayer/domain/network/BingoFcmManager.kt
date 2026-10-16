package com.bingo.multiplayer.domain.network

import android.content.Context
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.notifications.*
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Device registrations are private to the relay. No public username → token directory is used. */
object BingoFcmManager {
    private const val PREFS = "bingo_fcm_prefs"
    private val tokenRefresh = Mutex()
    fun init(context: Context) {
        NotificationRuntime.account(context)?.let { NotificationWork.register(context, it) }
    }
    suspend fun ensureCurrentToken(context: Context, forceRefresh: Boolean = false): Pair<String, Long> = tokenRefresh.withLock {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = tokenSnapshot(context)
        val rotate = forceRefresh || !prefs.getBoolean("private_directory_migrated", false)
        if (rotate) FirebaseMessaging.getInstance().deleteToken().await()
        val fetched = FirebaseMessaging.getInstance().token.await()
        synchronized(this) {
            // A service callback may have received a newer token while this request was in flight.
            if (tokenSnapshot(context).second == previous.second) saveToken(context, fetched)
            if (rotate) prefs.edit().putBoolean("private_directory_migrated", true).commit()
            tokenSnapshot(context)
        }
    }
    @Synchronized fun saveToken(context: Context, token: String) {
        if (token.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val revision = prefs.getLong("token_revision", 0) + if (prefs.getString("fcm_device_token", null) == token) 0 else 1
        if (!prefs.edit().putString("fcm_device_token", token).putLong("token_revision", revision).commit()) return
        NotificationRuntime.account(context)?.let { NotificationWork.register(context, it, NotificationPolicy.eventId("token", token)) }
    }
    fun getSavedToken(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fcm_device_token", null)
    @Synchronized fun tokenSnapshot(context: Context): Pair<String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("fcm_device_token", "").orEmpty() to prefs.getLong("token_revision", 0)
    }
    fun syncCurrentUserToken(context: Context, username: String) {
        val account = NotificationRuntime.account(context) ?: return
        if (notificationUsername(username) == account.username) NotificationWork.register(context, account)
    }
    private fun queue(kind: NotificationKind, target: String, id: String, createdAt: Long, extra: JSONObject = JSONObject()) {
        val context = NotificationRuntime.context() ?: return
        val account = NotificationRuntime.account(context) ?: return
        val payload = extra.put("id", id).put("kind", kind.name).put("toUsername", notificationUsername(target))
            .put("createdAt", createdAt).put("expiresAt", createdAt + kind.lifetimeMillis)
        NotificationWork.send(context, account, payload)
    }
    fun sendFriendRequestPush(targetUsername: String, request: FriendRequest) {
        queue(NotificationKind.FRIEND_REQUEST, targetUsername, NotificationPolicy.eventId("friend_request", request.id), request.timestamp,
            JSONObject().put("requestId", request.id).put("requestTimestamp", request.timestamp))
    }
    fun sendFriendAcceptedPush(targetUsername: String, request: FriendRequest) {
        queue(NotificationKind.FRIEND_ACCEPTED, targetUsername, NotificationPolicy.eventId("friend_accepted", request.id), System.currentTimeMillis(),
            JSONObject().put("requestId", request.id).put("requestTimestamp", request.timestamp))
    }
    fun sendFriendOnlinePush(targetUsername: String, myUsername: String, sessionId: String, timestamp: Long) {
        queue(NotificationKind.FRIEND_ONLINE, targetUsername, NotificationPolicy.eventId("friend_online", myUsername, sessionId), timestamp,
            JSONObject().put("presenceSessionId", sessionId))
    }
}
