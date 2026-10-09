package com.bingo.multiplayer.domain.network

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Keep
data class BroadcastMessage(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val type: String = "INFO", // "INFO", "ALERT", "MAINTENANCE"
    val author: String = "Admin",
    val targetUsername: String = "", // "" or "ALL" for everyone, or specific username like "@john"
    val timestamp: Long = 0L,
    val active: Boolean = true,
    val deliveryMode: String = "STARTUP", // "ONCE" or "STARTUP"
    val size: String = "STANDARD" // "COMPACT", "STANDARD", "EXPANDED"
) {
    val isDirectMessage: Boolean
        get() = targetUsername.isNotBlank() && !targetUsername.equals("ALL", ignoreCase = true)

    val isOnlyOnce: Boolean
        get() = deliveryMode.equals("ONCE", ignoreCase = true)

    fun toJsonString(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("title", title)
        json.put("message", message)
        json.put("type", type)
        json.put("author", author)
        json.put("targetUsername", targetUsername)
        json.put("timestamp", timestamp)
        json.put("active", active)
        json.put("deliveryMode", deliveryMode)
        json.put("size", size)
        return json.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): BroadcastMessage? {
            return try {
                var clean = jsonStr.trim()
                if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length >= 2) {
                    clean = clean.substring(1, clean.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
                val json = JSONObject(clean)
                BroadcastMessage(
                    id = json.optString("id", ""),
                    title = json.optString("title", ""),
                    message = json.optString("message", ""),
                    type = json.optString("type", "INFO"),
                    author = json.optString("author", "Admin"),
                    targetUsername = json.optString("targetUsername", ""),
                    timestamp = json.optLong("timestamp", 0L),
                    active = json.optBoolean("active", true),
                    deliveryMode = json.optString("deliveryMode", "STARTUP"),
                    size = json.optString("size", "STANDARD")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object BroadcastMessageManager {
    private const val TAG = "BroadcastMsgManager"
    const val MQTT_BROADCAST_TOPIC = "bingo/global/broadcast"
    private const val KEYVALUE_BROADCAST_KEY = "global_broadcast_message"

    private val _activeBroadcast = MutableStateFlow<BroadcastMessage?>(null)
    val activeBroadcast: StateFlow<BroadcastMessage?> = _activeBroadcast.asStateFlow()

    // Session-based dismissal flag: Once dismissed, no popups appear until app restart
    private var hasDismissedInSession = false
    private var lastApplicationContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun deliverForegroundBroadcast(msg: BroadcastMessage) {
        if (!hasDismissedInSession && msg.active && msg.message.isNotBlank()) {
            _activeBroadcast.value = msg
        }
    }

    /**
     * Checks if this message has already been viewed and dismissed in ONCE mode.
     */
    fun hasMessageBeenSeen(context: Context?, msgId: String): Boolean {
        val ctx = context ?: lastApplicationContext ?: return false
        if (msgId.isBlank()) return false
        return try {
            val prefs = ctx.getSharedPreferences("bingo_bcast_seen", Context.MODE_PRIVATE)
            val set = prefs.getStringSet("seen_ids", emptySet()) ?: emptySet()
            set.contains(msgId)
        } catch (_: Exception) { false }
    }

    /**
     * Records that a message has been seen and dismissed by the local user.
     */
    fun markMessageAsSeen(context: Context?, msgId: String) {
        val ctx = context ?: lastApplicationContext ?: return
        if (msgId.isBlank()) return
        try {
            val prefs = ctx.getSharedPreferences("bingo_bcast_seen", Context.MODE_PRIVATE)
            val set = prefs.getStringSet("seen_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
            set.add(msgId)
            prefs.edit().putStringSet("seen_ids", set).apply()
        } catch (_: Exception) {}
    }

    /**
     * Retrieves current logged in username from SharedPreferences.
     */
    fun getLocalUsername(context: Context?): String {
        val ctx = context ?: lastApplicationContext
        if (ctx == null) return ""
        return try {
            val prefs = ctx.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            val uname = prefs.getString("username", null)?.trim()
            if (!uname.isNullOrBlank()) uname else (prefs.getString("display_name", "")?.trim() ?: "")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Checks if this message is intended for the local user.
     */
    fun isTargetMatch(msg: BroadcastMessage, context: Context?): Boolean {
        val target = msg.targetUsername.trim().removePrefix("@").lowercase()
        if (target.isBlank() || target == "all") return true
        val localUser = getLocalUsername(context).trim().removePrefix("@").lowercase()
        return target.equals(localUser, ignoreCase = true)
    }

    /**
     * Checks for any active broadcast message on app startup.
     * Silent and strictly respects the per-session dismissal rule.
     */
    fun checkForBroadcast(context: Context? = null) {
        if (context != null) {
            lastApplicationContext = context.applicationContext
        }
        if (hasDismissedInSession) {
            Log.d(TAG, "Broadcast already dismissed in this session, skipping check")
            return
        }

        scope.launch {
            try {
                val localUser = getLocalUsername(context).trim().removePrefix("@").lowercase()
                
                // 1. Check for personal direct message targeting this specific player
                var targetMsg: BroadcastMessage? = null
                if (localUser.isNotBlank()) {
                    targetMsg = fetchDirectMessageFromCloud(localUser)
                }

                // 2. If no direct personal message, check for global broadcast
                val activeMsg = if (targetMsg != null && targetMsg.active && targetMsg.message.isNotBlank()) {
                    targetMsg
                } else {
                    val globalMsg = fetchCurrentActiveBroadcast()
                    if (globalMsg != null && globalMsg.active && globalMsg.message.isNotBlank() && isTargetMatch(globalMsg, context)) {
                        globalMsg
                    } else null
                }

                if (activeMsg != null && !hasDismissedInSession) {
                    if (activeMsg.isOnlyOnce && hasMessageBeenSeen(context, activeMsg.id)) {
                        Log.d(TAG, "Message ${activeMsg.id} was already seen in ONCE mode, suppressing startup popup")
                        _activeBroadcast.value = null
                        return@launch
                    }
                    _activeBroadcast.value = activeMsg
                    Log.i(TAG, "Active broadcast received on startup: ${activeMsg.title} (target=${activeMsg.targetUsername}, mode=${activeMsg.deliveryMode})")
                } else {
                    _activeBroadcast.value = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking broadcast: ${e.message}")
            }
        }
    }

    /**
     * Called when a live MQTT broadcast arrives in real-time.
     */
    fun onBroadcastReceived(jsonPayload: String, context: Context? = null) {
        if (context != null) {
            lastApplicationContext = context.applicationContext
        }
        if (hasDismissedInSession) {
            Log.d(TAG, "Broadcast already dismissed in this session, ignoring live update")
            return
        }
        try {
            val msg = BroadcastMessage.fromJsonString(jsonPayload)
            if (msg != null && msg.active && msg.message.isNotBlank() && isTargetMatch(msg, context)) {
                if (msg.isOnlyOnce && hasMessageBeenSeen(context, msg.id)) {
                    Log.d(TAG, "Live message ${msg.id} was already seen in ONCE mode, ignoring")
                    return
                }
                if (!hasDismissedInSession) {
                    _activeBroadcast.value = msg
                    Log.i(TAG, "Live broadcast received: ${msg.title} (target=${msg.targetUsername}, mode=${msg.deliveryMode})")
                    val effectiveCtx = context ?: lastApplicationContext
                    if (effectiveCtx != null && !AppLifecycleObserver.isAppInForeground.value) {
                        BingoNotificationManager.showBroadcastNotification(effectiveCtx, msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse live broadcast: ${e.message}")
        }
    }

    /**
     * Dismisses the broadcast dialog for the current app session.
     * In ONCE mode, marks the message seen locally and automatically clears
     * targeted cloud mailboxes so it goes inactive in the admin console.
     */
    fun dismiss(context: Context? = null) {
        hasDismissedInSession = true
        val ctx = context ?: lastApplicationContext
        if (ctx != null) {
            BingoNotificationManager.cancelBroadcastNotification(ctx)
        }
        val current = _activeBroadcast.value
        if (current != null) {
            if (current.isOnlyOnce || current.isDirectMessage) {
                markMessageAsSeen(ctx, current.id)
                // If this was a direct targeted message, automatically deactivate it in the cloud!
                if (current.isDirectMessage) {
                    scope.launch {
                        clearBroadcast(current.targetUsername)
                        Log.i(TAG, "Direct message ${current.id} for ${current.targetUsername} acknowledged & deactivated in cloud")
                    }
                }
            }
        }
        _activeBroadcast.value = null
    }

    /**
     * Fetches the current global broadcast message from the cloud KeyValue store.
     */
    suspend fun fetchCurrentActiveBroadcast(): BroadcastMessage? = withContext(Dispatchers.IO) {
        try {
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/${NetworkConfig.KEYVALUE_APP_KEY}/$KEYVALUE_BROADCAST_KEY"
            val request = Request.Builder().url(url).get().build()
            NetworkConfig.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string()?.trim().orEmpty()
                if (raw.isBlank() || raw == "\"\"" || raw == "null") return@withContext null
                BroadcastMessage.fromJsonString(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCurrentActiveBroadcast error: ${e.message}")
            null
        }
    }

    /**
     * Fetches a direct message targeting a specific user.
     */
    suspend fun fetchDirectMessageFromCloud(username: String): BroadcastMessage? = withContext(Dispatchers.IO) {
        try {
            val clean = username.trim().removePrefix("@").lowercase()
            val encKey = URLEncoder.encode("user_msg_$clean", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/${NetworkConfig.KEYVALUE_APP_KEY}/$encKey"
            val request = Request.Builder().url(url).get().build()
            NetworkConfig.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string()?.trim().orEmpty()
                if (raw.isBlank() || raw == "\"\"" || raw == "null") return@withContext null
                BroadcastMessage.fromJsonString(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDirectMessageFromCloud error: ${e.message}")
            null
        }
    }

    /**
     * Transmits a new broadcast message from the admin page.
     * Supports broadcasting to ALL players, or targeting a specific player (@username).
     */
    suspend fun publishBroadcast(
        title: String,
        message: String,
        type: String = "INFO",
        author: String = "Admin",
        targetUsername: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanTarget = targetUsername.trim().removePrefix("@")
            val isSpecificUser = cleanTarget.isNotBlank() && !cleanTarget.equals("ALL", ignoreCase = true)

            val broadcast = BroadcastMessage(
                id = UUID.randomUUID().toString().take(8),
                title = title.trim(),
                message = message.trim(),
                type = type,
                author = author.trim().ifBlank { "Admin" },
                targetUsername = if (isSpecificUser) "@$cleanTarget" else "ALL",
                timestamp = System.currentTimeMillis(),
                active = true
            )
            val jsonStr = broadcast.toJsonString()

            // 1. Determine KeyValue storage key
            val storageKey = if (isSpecificUser) "user_msg_${cleanTarget.lowercase()}" else KEYVALUE_BROADCAST_KEY
            val encKey = URLEncoder.encode(storageKey, "UTF-8")
            val encVal = URLEncoder.encode(jsonStr, "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/${NetworkConfig.KEYVALUE_APP_KEY}/$encKey?value=$encVal"
            val emptyBody = "".toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("Content-Length", "0")
                .build()

            val success = NetworkConfig.httpClient.newCall(request).execute().use { it.isSuccessful }

            // 2. Publish via MQTT to notify currently online players in real-time
            publishMqttBroadcast(jsonStr)

            // 3. Reset dismissal for admin so they can preview it
            hasDismissedInSession = false
            _activeBroadcast.value = broadcast

            Log.i(TAG, "Broadcast published: ${broadcast.title} (target=${broadcast.targetUsername})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish broadcast: ${e.message}", e)
            false
        }
    }

    /**
     * Clears or deactivates any existing broadcast from the cloud.
     */
    suspend fun clearBroadcast(targetUsername: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanTarget = targetUsername.trim().removePrefix("@")
            val isSpecificUser = cleanTarget.isNotBlank() && !cleanTarget.equals("ALL", ignoreCase = true)

            val emptyMsg = BroadcastMessage(
                id = "",
                title = "",
                message = "",
                type = "INFO",
                author = "Admin",
                targetUsername = if (isSpecificUser) "@$cleanTarget" else "ALL",
                timestamp = System.currentTimeMillis(),
                active = false
            )
            val jsonStr = emptyMsg.toJsonString()

            val storageKey = if (isSpecificUser) "user_msg_${cleanTarget.lowercase()}" else KEYVALUE_BROADCAST_KEY
            val encKey = URLEncoder.encode(storageKey, "UTF-8")
            val encVal = URLEncoder.encode(jsonStr, "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/${NetworkConfig.KEYVALUE_APP_KEY}/$encKey?value=$encVal"
            val emptyBody = "".toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("Content-Length", "0")
                .build()

            val success = NetworkConfig.httpClient.newCall(request).execute().use { it.isSuccessful }

            publishMqttBroadcast(jsonStr)
            _activeBroadcast.value = null
            Log.i(TAG, "Broadcast cleared successfully (target=$storageKey)")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear broadcast: ${e.message}", e)
            false
        }
    }

    /**
     * Allows previewing the active broadcast locally.
     */
    fun previewLocally(broadcast: BroadcastMessage) {
        hasDismissedInSession = false
        _activeBroadcast.value = broadcast
    }

    private fun publishMqttBroadcast(jsonPayload: String) {
        scope.launch {
            try {
                val clientId = "bcast_pub_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(NetworkConfig.BROKER_URL, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 5
                    NetworkConfig.applyMqttOptions(this)
                }

                client.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        try {
                            val mqttMsg = MqttMessage(jsonPayload.toByteArray(StandardCharsets.UTF_8)).apply {
                                qos = 1
                                isRetained = true
                            }
                            client.publish(MQTT_BROADCAST_TOPIC, mqttMsg)
                            client.disconnect()
                            client.close()
                        } catch (_: Exception) {}
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        try { client.close() } catch (_: Exception) {}
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "publishMqttBroadcast error: ${e.message}")
            }
        }
    }
}
