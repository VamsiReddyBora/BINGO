package com.bingo.multiplayer.domain.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class GameInvite(
    val fromUsername: String,
    val fromDisplayName: String,
    val fromAvatarUrl: String? = null,
    val roomCode: String,
    val timestamp: Long = System.currentTimeMillis()
)

object GameInviteManager {
    private val client = NetworkConfig.httpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BASE_URL = NetworkConfig.KEYVALUE_API_URL
    private val API_KEY = NetworkConfig.KEYVALUE_APP_KEY
    private val BROKER_URL = NetworkConfig.BROKER_URL

    @Volatile
    var stagedInvite: GameInvite? = null

    val foregroundInviteFlow = kotlinx.coroutines.flow.MutableSharedFlow<GameInvite>(
        replay = 1,
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    fun deliverForegroundInvite(invite: GameInvite) {
        stagedInvite = invite
        foregroundInviteFlow.tryEmit(invite)
    }

    fun consumeStagedInvite(): GameInvite? {
        val inv = stagedInvite
        stagedInvite = null
        return inv
    }

    fun clearForegroundInvite() {
        stagedInvite = null
        foregroundInviteFlow.resetReplayCache()
    }

    private fun encodeBase64Url(raw: String): String {
        return Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trim()
    }

    private fun decodeBase64Url(b64: String): String {
        val clean = b64.trim().removeSurrounding("\"")
        if (clean.startsWith("[")) return clean
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            clean
        }
    }

    /**
     * Sends an in-game match invitation to target user.
     * Broadcasts via retained MQTT immediately for sub-second delivery,
     * then asynchronously persists to cloud storage backup.
     */
    suspend fun sendInvite(targetUsername: String, invite: GameInvite): Boolean = withContext(Dispatchers.IO) {
        val clean = targetUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext false

        try {
            // Sanitize invite to ensure no large base64 avatars inflate payload beyond limits
            val sanitizedInvite = if ((invite.fromAvatarUrl?.length ?: 0) > 120) {
                invite.copy(fromAvatarUrl = null)
            } else {
                invite
            }

            // 1. INSTANT real-time dispatch via retained MQTT (delivers in < 100ms)
            publishMqttInvite(clean, sanitizedInvite)

            // 2. High-priority FCM Cloud Push to wake the target device if the app was killed
            BingoFcmManager.sendInvitePush(clean, sanitizedInvite)

            // 2. Cloud KeyValue persistence backup (purge invites older than 2 minutes)
            val now = System.currentTimeMillis()
            val existingList = fetchInvitesForUser(clean).filter { (now - it.timestamp) < 120_000L }.toMutableList()
            existingList.removeAll { it.roomCode.equals(sanitizedInvite.roomCode, ignoreCase = true) || it.fromUsername.equals(sanitizedInvite.fromUsername, ignoreCase = true) }
            existingList.add(0, sanitizedInvite) // latest first

            val jsonString = json.encodeToString(existingList)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")

            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/inv_$clean?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()

            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("GameInviteManager", "Failed to send invite: ${e.message}", e)
            false
        }
    }

    /**
     * Fetches pending game invites for a specific username (only valid if < 2 minutes old).
     */
    suspend fun fetchInvitesForUser(username: String): List<GameInvite> = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()

        try {
            // Primary key: inv_{clean}
            val request = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/inv_$clean")
                .get()
                .build()

            val raw = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.trim()?.removeSurrounding("\"")
            }

            if (!raw.isNullOrBlank() && raw != "null") {
                val jsonStr = try {
                    decodeBase64Url(raw)
                } catch (_: Exception) {
                    raw
                }
                if (jsonStr.startsWith("[")) {
                    val list = json.decodeFromString<List<GameInvite>>(jsonStr)
                    val now = System.currentTimeMillis()
                    return@withContext list.filter { (now - it.timestamp) < 120_000L && it.roomCode.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            Log.w("GameInviteManager", "Failed to fetch invites from inv_$clean: ${e.message}")
        }

        // Fallback: check legacy key invites_{clean}
        try {
            val reqLegacy = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/invites_$clean")
                .get()
                .build()
            val rawLegacy = client.newCall(reqLegacy).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.trim()?.removeSurrounding("\"")
            }
            if (!rawLegacy.isNullOrBlank() && rawLegacy != "null") {
                val unescaped = rawLegacy.replace("\\\"", "\"")
                if (unescaped.startsWith("[")) {
                    val list = json.decodeFromString<List<GameInvite>>(unescaped)
                    val now = System.currentTimeMillis()
                    return@withContext list.filter { (now - it.timestamp) < 120_000L && it.roomCode.isNotBlank() }
                }
            }
        } catch (_: Exception) {}

        emptyList()
    }

    /**
     * Removes an accepted or declined invite from cloud storage and clears retained MQTT.
     */
    suspend fun removeInvite(targetUsername: String, roomCode: String) = withContext(Dispatchers.IO) {
        val clean = targetUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext

        try {
            clearMqttRetainedInvite(clean)
            val existingList = fetchInvitesForUser(clean).toMutableList()
            existingList.removeAll { it.roomCode.equals(roomCode.trim(), ignoreCase = true) }
            val jsonString = json.encodeToString(existingList)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/inv_$clean?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e("GameInviteManager", "Failed to remove invite", e)
        }
    }

    /**
     * Clears all pending invites for a user from cloud storage and clears retained MQTT.
     */
    suspend fun clearInvites(username: String) = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext
        try {
            clearMqttRetainedInvite(clean)
            val emptyB64 = encodeBase64Url("[]")
            val encVal = java.net.URLEncoder.encode(emptyB64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/inv_$clean?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    /**
     * Clears retained MQTT invite message for a user.
     */
    fun clearMqttRetainedInvite(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientId = "inv_clr_${UUID.randomUUID().toString().take(8)}"
                val mqttClient = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                mqttClient.connect(options).waitForCompletion(2000L)
                val emptyMsg = MqttMessage(ByteArray(0)).apply {
                    qos = 1
                    isRetained = true
                }
                mqttClient.publish("bingo/v3/invites/$clean", emptyMsg).waitForCompletion(2000L)
                mqttClient.disconnect()
                mqttClient.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Dispatches real-time MQTT message with retention to subscriber.
     */
    private fun publishMqttInvite(targetUsername: String, invite: GameInvite) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientId = "inv_pub_${UUID.randomUUID().toString().take(8)}"
                val mqttClient = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                mqttClient.connect(options).waitForCompletion(2500L)
                val topic = "bingo/v3/invites/$targetUsername"
                val payload = json.encodeToString(invite)
                val msg = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                mqttClient.publish(topic, msg).waitForCompletion(2500L)
                try {
                    mqttClient.disconnect().waitForCompletion(1000L)
                    mqttClient.close()
                } catch (_: Exception) {}
                Log.d("GameInviteManager", "Real-time invite dispatched to MQTT: $topic")
            } catch (e: Exception) {
                Log.w("GameInviteManager", "MQTT invite publish warning: ${e.message}")
            }
        }
    }

    /**
     * Subscribes to real-time incoming game invites via MQTT.
     */
    fun startInviteListener(myUsername: String, onInviteReceived: (GameInvite) -> Unit): AutoCloseable {
        val clean = myUsername.trim().lowercase().removePrefix("@")
        var client: MqttAsyncClient? = null
        try {
            val clientId = "inv_sub_${clean}_${UUID.randomUUID().toString().take(6)}"
            client = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                isAutomaticReconnect = true
                NetworkConfig.applyMqttOptions(this)
            }
            val topic = "bingo/v3/invites/$clean"
            client.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        client?.subscribe(topic, 1) { _, message ->
                            try {
                                if (message.payload.isEmpty()) return@subscribe
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val invite = json.decodeFromString<GameInvite>(payload)
                                val now = System.currentTimeMillis()
                                if (now - invite.timestamp < 120_000L) {
                                    try {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            onInviteReceived(invite)
                                        }
                                    } catch (_: Exception) {
                                        onInviteReceived(invite)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("GameInviteManager", "Error parsing incoming invite: ${e.message}")
                            }
                        }
                        Log.d("GameInviteManager", "Listening for game invites on $topic")
                    } catch (e: Exception) {
                        Log.w("GameInviteManager", "Subscribe to invites failed: ${e.message}")
                    }
                }

                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })
            client.connect(options)
        } catch (e: Exception) {
            Log.w("GameInviteManager", "Could not start MQTT invite listener: ${e.message}")
        }

        return AutoCloseable {
            try {
                if (client?.isConnected == true) {
                    client.disconnect()
                }
                client?.close()
            } catch (_: Exception) {}
        }
    }
}
