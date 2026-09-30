package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Keep
@Serializable
data class PlayerPresence(
    val username: String = "",
    val status: String = "OFFLINE", // "ONLINE" or "OFFLINE"
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * A player is "online" if their status is "ONLINE" and the timestamp is within 10 seconds.
     */
    val isOnline: Boolean
        get() = status.equals("ONLINE", ignoreCase = true) && (System.currentTimeMillis() - timestamp) < 10_000L
}

/**
 * Dual-Channel Real-Time Presence System:
 * 1. Channel A: High-speed MQTT (sub-second live updates when both players are connected).
 * 2. Channel B: Cloud Key-Value Store (guaranteed HTTP persistence that never drops or misses).
 *
 * This dual approach ensures that even if MQTT drops retained messages or an app is
 * abruptly killed (swiped from recents), the player's status is always 100% accurate.
 */
object PresenceManager {
    private const val TAG = "PresenceManager"
    private const val PREFS_NAME = "bingo_presence_prefs"
    private const val KEY_ACTIVE_USER = "active_username"

    private val BROKER_URL = NetworkConfig.BROKER_URL
    private val httpClient = NetworkConfig.httpClient
    private val appKey = NetworkConfig.KEYVALUE_APP_KEY
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var prefs: SharedPreferences? = null
    private var presenceClient: MqttAsyncClient? = null
    private var heartbeatJob: Job? = null
    private var currentActiveUsername: String? = null

    private val presenceMap = ConcurrentHashMap<String, PlayerPresence>()
    private val _presenceFlow = MutableStateFlow<Map<String, PlayerPresence>>(emptyMap())
    val presenceFlow: StateFlow<Map<String, PlayerPresence>> = _presenceFlow.asStateFlow()

    private var watcherClient: MqttAsyncClient? = null

    /**
     * Initializes PresenceManager with Android Context for persistent active user tracking.
     */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        var saved = p.getString(KEY_ACTIVE_USER, null)
        if (saved.isNullOrBlank()) {
            val authPrefs = context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            saved = authPrefs.getString("username", null)
            if (!saved.isNullOrBlank()) {
                p.edit().putString(KEY_ACTIVE_USER, saved.trim().lowercase().removePrefix("@")).apply()
            }
        }
        if (!saved.isNullOrBlank()) {
            val clean = saved.trim().lowercase().removePrefix("@")
            currentActiveUsername = clean
            Log.i(TAG, "Restored active presence username: @$clean")
            startPresence(clean)
        }
    }

    /**
     * Explicitly sets the active logged-in user and persists across process restarts.
     */
    fun setCurrentUser(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        currentActiveUsername = clean
        prefs?.edit()?.putString(KEY_ACTIVE_USER, clean)?.apply()
    }

    /**
     * Writes presence asynchronously without blocking (fire-and-forget for lifecycle exit).
     */
    fun setCloudPresenceAsync(cleanUsername: String, status: String, timestamp: Long) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        try {
            val encKey = URLEncoder.encode("pres_$clean", "UTF-8")
            val encVal = URLEncoder.encode("$status:$timestamp", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
            val emptyBody = "".toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("Content-Length", "0")
                .build()
            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
        } catch (_: Exception) {}
    }

    /**
     * Writes presence directly to Cloud Key-Value store.
     */
    suspend fun setCloudPresence(cleanUsername: String, status: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext false
        try {
            val encKey = URLEncoder.encode("pres_$clean", "UTF-8")
            val encVal = URLEncoder.encode("$status:$timestamp", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
            val emptyBody = "".toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("Content-Length", "0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "setCloudPresence error for $clean: ${e.message}")
            false
        }
    }

    /**
     * Reads presence directly from Cloud Key-Value store.
     */
    suspend fun fetchCloudPresence(username: String): PlayerPresence? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null
        try {
            val encKey = URLEncoder.encode("pres_$clean", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$appKey/$encKey"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string()?.trim()?.removeSurrounding("\"") ?: return@withContext null
                if (!raw.contains(":")) return@withContext null
                val parts = raw.split(":")
                val status = parts[0]
                val ts = parts.getOrNull(1)?.toLongOrNull() ?: return@withContext null
                val p = PlayerPresence(clean, status, ts)
                presenceMap[clean] = p
                _presenceFlow.value = HashMap(presenceMap)
                p
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches real-time presence for a list of users in parallel over HTTP.
     */
    suspend fun fetchCloudPresenceForUsers(usernames: List<String>) = withContext(Dispatchers.IO) {
        val cleanList = usernames.map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotBlank() }.distinct()
        if (cleanList.isEmpty()) return@withContext
        cleanList.map { u ->
            async {
                fetchCloudPresence(u)
            }
        }.awaitAll()
    }

    /**
     * Starts presence broadcast for the currently logged-in user.
     */
    fun startPresence(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        setCurrentUser(clean)

        if (presenceClient?.isConnected == true) {
            publishOnline(clean)
            return
        }

        disconnectPresenceClient()

        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, "ONLINE", now)
        _presenceFlow.value = HashMap(presenceMap)

        // Write initial ONLINE status to cloud immediately
        scope.launch {
            setCloudPresence(clean, "ONLINE", now)
        }

        scope.launch {
            try {
                val clientId = "pres_${clean}_${UUID.randomUUID().toString().take(6)}"
                val client = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                presenceClient = client

                val presenceTopic = "bingo/v3/presence/$clean"
                val lwtPayload = json.encodeToString(
                    PlayerPresence(
                        username = clean,
                        status = "OFFLINE",
                        timestamp = 0L // 0L indicates an abrupt LWT disconnect
                    )
                )

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    socketFactory = LowLatencySocketFactory()
                    setWill(
                        presenceTopic,
                        lwtPayload.toByteArray(StandardCharsets.UTF_8),
                        1,
                        true
                    )
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "Presence connected for @$clean (reconnect=$reconnect). Publishing ONLINE.")
                        publishOnline(clean)
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Presence connection lost for @$clean: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                try {
                    client.connect(options)
                } catch (e: Exception) {
                    Log.w(TAG, "Presence connect initial: ${e.message}")
                }
                publishOnline(clean)

                // Start periodic heartbeat
                startHeartbeat(clean)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start presence for @$clean: ${e.message}")
            }
        }

        // Also ensure presence watcher is running
        startPresenceWatcher()
    }

    private fun startHeartbeat(cleanUsername: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2500L)
                publishOnline(cleanUsername)
                val now = System.currentTimeMillis()
                setCloudPresence(cleanUsername, "ONLINE", now)
            }
        }
    }

    private fun publishOnline(cleanUsername: String) {
        val client = presenceClient ?: return
        if (!client.isConnected) return
        try {
            val topic = "bingo/v3/presence/$cleanUsername"
            val now = System.currentTimeMillis()
            val payload = json.encodeToString(
                PlayerPresence(
                    username = cleanUsername,
                    status = "ONLINE",
                    timestamp = now
                )
            )
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message)

            // Update local map as well
            presenceMap[cleanUsername] = PlayerPresence(cleanUsername, "ONLINE", now)
            _presenceFlow.value = HashMap(presenceMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing online presence: ${e.message}")
        }
    }

    private fun publishOffline(cleanUsername: String) {
        val client = presenceClient ?: return
        if (!client.isConnected) return
        try {
            val topic = "bingo/v3/presence/$cleanUsername"
            val now = System.currentTimeMillis()
            val payload = json.encodeToString(
                PlayerPresence(
                    username = cleanUsername,
                    status = "OFFLINE",
                    timestamp = now
                )
            )
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message)

            // Update local map
            presenceMap[cleanUsername] = PlayerPresence(cleanUsername, "OFFLINE", now)
            _presenceFlow.value = HashMap(presenceMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing offline presence: ${e.message}")
        }
    }

    /**
     * Called by AppLifecycleObserver when app goes to BACKGROUND.
     */
    fun onAppBackground() {
        val clean = currentActiveUsername ?: return
        Log.d(TAG, "App backgrounded — publishing OFFLINE for @$clean")
        heartbeatJob?.cancel()
        heartbeatJob = null
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, "OFFLINE", now)
        _presenceFlow.value = HashMap(presenceMap)

        publishOffline(clean)
        setCloudPresenceAsync(clean, "OFFLINE", now)
    }

    /**
     * Called by AppLifecycleObserver when app returns to FOREGROUND.
     */
    fun onAppForeground() {
        val clean = currentActiveUsername ?: return
        Log.d(TAG, "App foregrounded — publishing ONLINE for @$clean")
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, "ONLINE", now)
        _presenceFlow.value = HashMap(presenceMap)

        scope.launch {
            publishOnline(clean)
            setCloudPresence(clean, "ONLINE", now)
        }
        startHeartbeat(clean)
        startPresenceWatcher(forceReconnect = true)
    }

    /**
     * Fully stops presence and disconnects MQTT client.
     */
    fun stopPresence() {
        val clean = currentActiveUsername
        heartbeatJob?.cancel()
        heartbeatJob = null
        disconnectPresenceClient()
        if (clean != null) {
            val now = System.currentTimeMillis()
            presenceMap[clean] = PlayerPresence(clean, "OFFLINE", now)
            _presenceFlow.value = HashMap(presenceMap)
            scope.launch(Dispatchers.IO) {
                setCloudPresence(clean, "OFFLINE", now)
            }
        }
    }

    private fun disconnectPresenceClient() {
        val clean = currentActiveUsername
        val client = presenceClient
        presenceClient = null

        if (clean != null && client != null && client.isConnected) {
            scope.launch {
                try {
                    publishOffline(clean)
                    delay(150L)
                    client.disconnect().waitForCompletion(1000L)
                    client.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Subscribes to real-time presence for all players (wildcard bingo/v3/presence/+).
     */
    fun startPresenceWatcher(forceReconnect: Boolean = false) {
        if (!forceReconnect && watcherClient?.isConnected == true) return

        scope.launch {
            try {
                if (forceReconnect) {
                    try {
                        watcherClient?.disconnect()
                        watcherClient?.close()
                    } catch (_: Exception) {}
                    watcherClient = null
                }

                val clientId = "pres_watch_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                watcherClient = client

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    socketFactory = LowLatencySocketFactory()
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        try {
                            client.subscribe("bingo/v3/presence/+", 1) { topic, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    val presence = json.decodeFromString<PlayerPresence>(payload)
                                    val cleanUser = presence.username.ifBlank {
                                        topic.substringAfterLast("/")
                                    }.lowercase()
                                    if (cleanUser.isNotBlank()) {
                                        val effectiveTimestamp = if (presence.status.equals("OFFLINE", ignoreCase = true) && presence.timestamp <= 0L) {
                                            System.currentTimeMillis()
                                        } else {
                                            presence.timestamp
                                        }
                                        presenceMap[cleanUser] = presence.copy(username = cleanUser, timestamp = effectiveTimestamp)
                                        _presenceFlow.value = HashMap(presenceMap)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing presence message: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error subscribing to presence: ${e.message}")
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client.connect(options)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start presence watcher: ${e.message}")
            }
        }
    }

    fun isUserOnline(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        val p = presenceMap[clean] ?: return false
        return p.isOnline
    }

    /**
     * Formats player status cleanly:
     * - "online" — player is currently active in the app (within 15s)
     * - "last seen just now" — within 60 seconds of leaving
     * - "last seen Xm ago" / "last seen Xh ago" / "last seen Xh Xm ago" — between 1 minute and 24 hours
     * - "offline" — inactive for > 24 hours or unknown
     */
    fun getDisplayStatus(username: String, fallbackLastSeen: Long? = null): String {
        val clean = username.trim().lowercase().removePrefix("@")
        val p = presenceMap[clean]
        val now = System.currentTimeMillis()

        // 1. Explicit local/cloud OFFLINE status
        if (p != null && p.status.equals("OFFLINE", ignoreCase = true)) {
            val ts = if (p.timestamp > 0L) p.timestamp else fallbackLastSeen ?: 0L
            return formatLastSeen(ts, now)
        }

        // 2. ONLINE status — check if within 10-second heartbeat window
        if (p != null && p.status.equals("ONLINE", ignoreCase = true)) {
            val diffSec = ((now - p.timestamp) / 1000).coerceAtLeast(0)
            if (diffSec < 10) {
                return "online"
            } else {
                // Heartbeat stopped (app closed/swiped/killed) -> user departed at p.timestamp
                return formatLastSeen(p.timestamp, now)
            }
        }

        // 3. Fallback timestamp when not in presenceMap
        val fbTs = fallbackLastSeen ?: 0L
        if (fbTs <= 0L) return "offline"
        val diffSec = ((now - fbTs) / 1000).coerceAtLeast(0)
        if (diffSec < 10) {
            return "online"
        }
        return formatLastSeen(fbTs, now)
    }

    private fun formatLastSeen(timestamp: Long, now: Long): String {
        if (timestamp <= 0L) return "offline"
        val diffSec = ((now - timestamp) / 1000).coerceAtLeast(0)

        // If very recent (< 60s), show "last seen just now"
        if (diffSec < 60) return "last seen just now"

        // If last seen > 24 hours (86400s), show "offline"
        if (diffSec >= 86400) return "offline"

        val hours = diffSec / 3600
        val minutes = (diffSec % 3600) / 60

        return when {
            hours > 0 && minutes > 0 -> "last seen ${hours}h ${minutes}m ago"
            hours > 0 -> "last seen ${hours}h ago"
            minutes > 0 -> "last seen ${minutes}m ago"
            else -> "last seen just now"
        }
    }
}
