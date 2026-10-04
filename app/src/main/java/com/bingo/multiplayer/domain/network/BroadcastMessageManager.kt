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
    val timestamp: Long = 0L,
    val active: Boolean = true
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("title", title)
        json.put("message", message)
        json.put("type", type)
        json.put("author", author)
        json.put("timestamp", timestamp)
        json.put("active", active)
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
                    timestamp = json.optLong("timestamp", 0L),
                    active = json.optBoolean("active", true)
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
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Checks for any active broadcast message on app startup.
     * Silent and strictly respects the per-session dismissal rule.
     */
    fun checkForBroadcast() {
        if (hasDismissedInSession) {
            Log.d(TAG, "Broadcast already dismissed in this session, skipping check")
            return
        }

        scope.launch {
            try {
                val msg = fetchCurrentActiveBroadcast()
                if (msg != null && msg.active && msg.message.isNotBlank()) {
                    if (!hasDismissedInSession) {
                        _activeBroadcast.value = msg
                        Log.i(TAG, "Active broadcast received on startup: ${msg.title}")
                    }
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
    fun onBroadcastReceived(jsonPayload: String) {
        if (hasDismissedInSession) {
            Log.d(TAG, "Broadcast already dismissed in this session, ignoring live update")
            return
        }
        try {
            val msg = BroadcastMessage.fromJsonString(jsonPayload)
            if (msg != null && msg.active && msg.message.isNotBlank()) {
                if (!hasDismissedInSession) {
                    _activeBroadcast.value = msg
                    Log.i(TAG, "Live broadcast received: ${msg.title}")
                }
            } else {
                _activeBroadcast.value = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse live broadcast: ${e.message}")
        }
    }

    /**
     * Dismisses the broadcast dialog for the current app session.
     * Will NOT pop up again until the app is restarted fresh.
     */
    fun dismiss() {
        hasDismissedInSession = true
        _activeBroadcast.value = null
    }

    /**
     * Fetches the current broadcast message from the cloud KeyValue store.
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
     * Transmits a new broadcast message from the admin page.
     * Persists to KeyValue cloud store and publishes to all connected players via MQTT.
     */
    suspend fun publishBroadcast(
        title: String,
        message: String,
        type: String = "INFO",
        author: String = "Admin"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val broadcast = BroadcastMessage(
                id = UUID.randomUUID().toString().take(8),
                title = title.trim(),
                message = message.trim(),
                type = type,
                author = author.trim().ifBlank { "Admin" },
                timestamp = System.currentTimeMillis(),
                active = true
            )
            val jsonStr = broadcast.toJsonString()

            // 1. Persist to KeyValue store for users opening the app later
            val encKey = URLEncoder.encode(KEYVALUE_BROADCAST_KEY, "UTF-8")
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

            Log.i(TAG, "Broadcast published successfully: ${broadcast.title}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish broadcast: ${e.message}", e)
            false
        }
    }

    /**
     * Clears or deactivates any existing broadcast from the cloud.
     */
    suspend fun clearBroadcast(): Boolean = withContext(Dispatchers.IO) {
        try {
            val emptyMsg = BroadcastMessage(
                id = "",
                title = "",
                message = "",
                type = "INFO",
                author = "Admin",
                timestamp = System.currentTimeMillis(),
                active = false
            )
            val jsonStr = emptyMsg.toJsonString()

            val encKey = URLEncoder.encode(KEYVALUE_BROADCAST_KEY, "UTF-8")
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
            Log.i(TAG, "Broadcast cleared successfully")
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
