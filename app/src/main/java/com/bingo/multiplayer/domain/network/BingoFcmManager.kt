package com.bingo.multiplayer.domain.network

import android.content.Context
import android.util.Log
import com.bingo.multiplayer.domain.model.FriendRequest
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object BingoFcmManager {
    private const val TAG = "BingoFcmManager"
    private const val PREFS_NAME = "bingo_fcm_prefs"
    private const val KEY_FCM_TOKEN = "fcm_device_token"

    private val httpClient = NetworkConfig.httpClient
    private val appKey = NetworkConfig.KEYVALUE_APP_KEY
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.i(TAG, "Fetched FCM registration token: ${token.take(15)}...")
                    saveToken(context, token)
                    val localUser = BroadcastMessageManager.getLocalUsername(context)
                    if (localUser.isNotBlank()) {
                        syncTokenToCloud(context, localUser, token)
                    }
                } else {
                    Log.w(TAG, "Failed to fetch FCM registration token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseMessaging init warning: ${e.message}")
        }
    }

    fun saveToken(context: Context, token: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FCM_TOKEN, token.trim())
                .apply()
        } catch (_: Exception) {}
    }

    fun getSavedToken(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FCM_TOKEN, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Publishes this user's FCM device token to the Cloud Key-Value store.
     * This enables any opponent or friend to look up this device's token and send a push.
     */
    fun syncTokenToCloud(context: Context, username: String, token: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank() || token.isBlank()) return

        scope.launch {
            try {
                val encKey = URLEncoder.encode("fcm_$clean", "UTF-8")
                val encVal = URLEncoder.encode(token.trim(), "UTF-8")
                val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody(null))
                    .header("Content-Length", "0")
                    .build()
                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Synced FCM token to cloud directory for @$clean")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync FCM token to cloud: ${e.message}")
            }
        }
    }

    /**
     * Ensures this user's FCM device token is synced to the Cloud Key-Value store.
     * Can be safely called upon login, resume, presence start, or account switch.
     */
    fun syncCurrentUserToken(context: Context, username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        val saved = getSavedToken(context)
        if (!saved.isNullOrBlank()) {
            syncTokenToCloud(context, clean, saved)
        } else {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful && !task.result.isNullOrBlank()) {
                        val token = task.result
                        saveToken(context, token)
                        syncTokenToCloud(context, clean, token)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch fresh token for sync: ${e.message}")
            }
        }
    }

    /**
     * Fetches the FCM device token for a specific user from Cloud Key-Value store.
     */
    suspend fun fetchTokenForUser(username: String): String? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null
        try {
            val encKey = URLEncoder.encode("fcm_$clean", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$appKey/$encKey"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()?.trim()?.removeSurrounding("\"")
                if (!body.isNullOrBlank() && body != "null") body else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends a High-Priority Data-Only FCM push notification via the Cloudflare Worker relay.
     * Keeps all Google Cloud Service Account credentials securely off client binaries.
     */
    fun sendPushNotification(
        targetToken: String,
        type: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        if (targetToken.isBlank()) {
            onComplete?.invoke(false, "Target device token is empty")
            return
        }

        scope.launch {
            try {
                val dataObj = JSONObject().apply {
                    put("type", type)
                    put("title", title)
                    put("body", body)
                    data.forEach { (k, v) -> put(k, v) }
                }

                val payload = JSONObject().apply {
                    put("token", targetToken)
                    put("type", type)
                    put("title", title)
                    put("body", body)
                    put("data", dataObj)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(NetworkConfig.FCM_RELAY_URL)
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful
                    val respBody = resp.body?.string().orEmpty()
                    if (isSuccess) {
                        Log.i(TAG, "FCM push dispatched successfully via relay: $type")
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(true, "Sent successfully via FCM relay")
                        }
                    } else {
                        Log.e(TAG, "FCM dispatch error HTTP ${resp.code}: $respBody")
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(false, "FCM relay returned HTTP ${resp.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM dispatch exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Unknown error")
                }
            }
        }
    }

    fun sendInvitePush(targetUsername: String, invite: GameInvite) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                sendPushNotification(
                    targetToken = token,
                    type = "GAME_INVITE",
                    title = "🎮 Game Invite from ${invite.fromDisplayName}",
                    body = "@${invite.fromUsername} invited you to play a Bingo match! (Room ${invite.roomCode})",
                    data = mapOf(
                        "roomCode" to invite.roomCode,
                        "fromUsername" to invite.fromUsername,
                        "fromDisplayName" to invite.fromDisplayName,
                        "timestamp" to invite.timestamp.toString()
                    )
                )
            } else {
                Log.d(TAG, "No FCM token registered for @$targetUsername; opponent must be online via MQTT")
            }
        }
    }

    /**
     * Sends an FCM friend request push to target user.
     */
    fun sendFriendRequestPush(targetUsername: String, request: FriendRequest) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = request.fromDisplayName.ifBlank { request.fromUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_REQUEST",
                    title = "👥 Friend Request from $displayName",
                    body = "@${request.fromUsername} sent you a friend request!",
                    data = mapOf(
                        "fromUsername" to request.fromUsername,
                        "fromDisplayName" to displayName,
                        "fromUid" to request.fromUid,
                        "type" to "FRIEND_REQUEST"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; friend request delivered via MQTT/Cloud")
            }
        }
    }

    /**
     * Sends an FCM friend accepted push to target user.
     */
    fun sendFriendAcceptedPush(targetUsername: String, myUsername: String, myDisplayName: String) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = myDisplayName.ifBlank { myUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_ACCEPTED",
                    title = "🎉 Friend Request Accepted",
                    body = "$displayName accepted your friend request!",
                    data = mapOf(
                        "fromUsername" to myUsername,
                        "fromDisplayName" to displayName,
                        "type" to "FRIEND_ACCEPTED"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; acceptance delivered via MQTT/Cloud")
            }
        }
    }

    /**
     * Sends an FCM push to a friend notifying them that this user is online.
     */
    fun sendFriendOnlinePush(targetUsername: String, myUsername: String, myDisplayName: String) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = myDisplayName.ifBlank { myUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_ONLINE",
                    title = "🟢 $displayName is online",
                    body = "Your friend is online. Tap to challenge them to a match!",
                    data = mapOf(
                        "username" to myUsername,
                        "displayName" to displayName,
                        "timestamp" to System.currentTimeMillis().toString(),
                        "type" to "FRIEND_ONLINE"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; presence delivered via MQTT")
            }
        }
    }

    /**
     * Self-test trigger: Sends an FCM push to this exact device with a delay
     * so user can minimize/lock their phone and watch FCM wake the device.
     */
    fun sendSelfTestPush(context: Context, onResult: (Boolean, String) -> Unit) {
        val token = getSavedToken(context)
        if (token.isNullOrBlank()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    val freshToken = task.result
                    saveToken(context, freshToken)
                    dispatchSelfTest(freshToken, onResult)
                } else {
                    onResult(false, "Could not fetch FCM device token: ${task.exception?.message}")
                }
            }
        } else {
            dispatchSelfTest(token, onResult)
        }
    }

    private fun dispatchSelfTest(token: String, onResult: (Boolean, String) -> Unit) {
        sendPushNotification(
            targetToken = token,
            type = "GAME_INVITE",
            title = "🎮 Test FCM Push",
            body = "FCM High-Priority push successfully woke your device!",
            data = mapOf(
                "roomCode" to "FCMTEST",
                "fromUsername" to "fcm_test",
                "fromDisplayName" to "FCM Cloud Diagnostics",
                "timestamp" to System.currentTimeMillis().toString()
            ),
            onComplete = onResult
        )
    }
}
