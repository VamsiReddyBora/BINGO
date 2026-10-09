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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap

@Keep
enum class AppActivityState {
    ONLINE,     // Active in app (menu, settings, dashboard, room list)
    IN_LOBBY,   // In a waiting room / lobby
    PLAYING     // Actively playing in a match
}

@Keep
@Serializable
data class AvatarUpdatePayload(
    val username: String = "",
    val avatar: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@Serializable
data class PlayerPresence(
    val username: String = "",
    val status: String = "OFFLINE", // "ONLINE", "IN_LOBBY", "PLAYING", or "OFFLINE"
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = NetworkConfig.APP_VERSION_NAME,
    val appVersionCode: Int = NetworkConfig.APP_VERSION_CODE
) {
    /**
     * A player is considered active in the app if their status is ONLINE, IN_LOBBY, or PLAYING,
     * and the timestamp is within 12 seconds.
     */
    val isOnline: Boolean
        get() = (status.equals("ONLINE", ignoreCase = true) ||
                 status.equals("IN_LOBBY", ignoreCase = true) ||
                 status.equals("PLAYING", ignoreCase = true)) &&
                (System.currentTimeMillis() - timestamp) < 15_000L
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
    private var appContext: Context? = null
    private var presenceClient: MqttAsyncClient? = null
    private var heartbeatJob: Job? = null
    private var watcherStartTime: Long = 0L
    var currentActiveUsername: String? = null
        private set

    var currentActivityState: AppActivityState = AppActivityState.ONLINE
        private set

    /**
     * Updates local activity state and immediately publishes it via MQTT and Cloud Key-Value store.
     */
    fun setActivityState(state: AppActivityState) {
        if (currentActivityState == state) return
        currentActivityState = state
        val clean = currentActiveUsername ?: return
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, state.name, now)
        _presenceFlow.value = HashMap(presenceMap)

        scope.launch {
            publishStatus(clean, state.name)
            setCloudPresence(clean, state.name, now)
        }
    }

    private val presenceMap = ConcurrentHashMap<String, PlayerPresence>()
    private val _presenceFlow = MutableStateFlow<Map<String, PlayerPresence>>(emptyMap())
    val presenceFlow: StateFlow<Map<String, PlayerPresence>> = _presenceFlow.asStateFlow()

    private var watcherClient: MqttAsyncClient? = null

    /**
     * Initializes PresenceManager with Android Context for persistent active user tracking.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
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
            val vName = NetworkConfig.APP_VERSION_NAME
            val vCode = NetworkConfig.APP_VERSION_CODE
            val encVal = URLEncoder.encode("$status:$timestamp:$vName:$vCode", "UTF-8")
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
            val vName = NetworkConfig.APP_VERSION_NAME
            val vCode = NetworkConfig.APP_VERSION_CODE
            val encVal = URLEncoder.encode("$status:$timestamp:$vName:$vCode", "UTF-8")
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

    private fun ensureInCloudDirectory(clean: String) {
        try {
            val encKey = URLEncoder.encode("user_directory", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$appKey/$encKey"
            val request = Request.Builder().url(url).get().build()
            val current = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim()?.removeSurrounding("\"") else null
            }
            if (current.isNullOrBlank() || current == "null") {
                // Network glitch or empty response — NEVER overwrite cloud directory with a single user!
                return
            }
            val users = current.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
            if (users.add(clean)) {
                val newDir = users.sorted().joinToString(",")
                val encVal = URLEncoder.encode(newDir, "UTF-8")
                val updateUrl = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
                val updateReq = Request.Builder().url(updateUrl).post("".toRequestBody(null)).header("Content-Length", "0").build()
                httpClient.newCall(updateReq).execute().close()
            }
        } catch (_: Exception) {}
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
                val vName = parts.getOrNull(2)?.ifBlank { null } ?: NetworkConfig.APP_VERSION_NAME
                val vCode = parts.getOrNull(3)?.toIntOrNull() ?: NetworkConfig.APP_VERSION_CODE
                val p = PlayerPresence(clean, status, ts, appVersion = vName, appVersionCode = vCode)
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

        val currentStatus = currentActivityState.name

        if (presenceClient?.isConnected == true) {
            publishStatus(clean, currentStatus)
            return
        }

        disconnectPresenceClient()

        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, currentStatus, now)
        _presenceFlow.value = HashMap(presenceMap)

        // Write initial status to cloud immediately, sync FCM token, and alert friends
        scope.launch {
            setCloudPresence(clean, currentStatus, now)
            ensureInCloudDirectory(clean)
            appContext?.let { ctx ->
                BingoFcmManager.syncCurrentUserToken(ctx, clean)
            }
            notifyFriendsOnlineViaFcm(clean)
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
                    NetworkConfig.applyMqttOptions(this)
                    setWill(
                        presenceTopic,
                        lwtPayload.toByteArray(StandardCharsets.UTF_8),
                        1,
                        true
                    )
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "Presence connected for @$clean (reconnect=$reconnect). Publishing ${currentActivityState.name}.")
                        publishStatus(clean, currentActivityState.name)
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
                publishStatus(clean, currentActivityState.name)

                // Start periodic heartbeat
                startHeartbeat(clean)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start presence for @$clean: ${e.message}")
            }
        }

        // Also ensure presence watcher is running
        startPresenceWatcher()
    }

    private fun notifyFriendsOnlineViaFcm(cleanUsername: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                val friends = com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.friends?.value
                    ?: FriendRequestManager.fetchCloudFriends(cleanUsername)
                    ?: emptyList()

                if (friends.isEmpty()) return@launch

                val authPrefs = ctx.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
                val myDisplayName = authPrefs.getString("display_name", null)?.takeIf { it.isNotBlank() } ?: cleanUsername

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()

                friends.forEach { friend ->
                    val fClean = friend.username.trim().lowercase().removePrefix("@")
                    if (fClean.isNotBlank() && fClean != cleanUsername) {
                        val lastNotified = prefs.getLong("fcm_notified_online_$fClean", 0L)
                        if ((now - lastNotified) >= 120_000L) {
                            prefs.edit().putLong("fcm_notified_online_$fClean", now).apply()
                            BingoFcmManager.sendFriendOnlinePush(fClean, cleanUsername, myDisplayName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dispatch online FCM to friends: ${e.message}")
            }
        }
    }

    private fun startHeartbeat(cleanUsername: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2500L)
                val status = currentActivityState.name
                publishStatus(cleanUsername, status)
                val now = System.currentTimeMillis()
                setCloudPresence(cleanUsername, status, now)
            }
        }
    }

    private fun publishStatus(cleanUsername: String, status: String) {
        val client = presenceClient ?: return
        if (!client.isConnected) return
        try {
            val topic = "bingo/v3/presence/$cleanUsername"
            val now = System.currentTimeMillis()
            val payload = json.encodeToString(
                PlayerPresence(
                    username = cleanUsername,
                    status = status,
                    timestamp = now
                )
            )
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message)

            // Update local map as well
            presenceMap[cleanUsername] = PlayerPresence(cleanUsername, status, now)
            _presenceFlow.value = HashMap(presenceMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing status $status: ${e.message}")
        }
    }

    private fun publishOnline(cleanUsername: String) {
        publishStatus(cleanUsername, currentActivityState.name)
    }

    private fun publishOffline(cleanUsername: String) {
        publishStatus(cleanUsername, "OFFLINE")
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
        val currentStatus = currentActivityState.name
        Log.d(TAG, "App foregrounded — publishing $currentStatus for @$clean")
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, currentStatus, now)
        _presenceFlow.value = HashMap(presenceMap)

        scope.launch {
            publishStatus(clean, currentStatus)
            setCloudPresence(clean, currentStatus, now)
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
                    NetworkConfig.applyMqttOptions(this)
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        watcherStartTime = System.currentTimeMillis()
                        try {
                            client.subscribe("bingo/v3/presence/+", 1) { topic, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    val presence = json.decodeFromString<PlayerPresence>(payload)
                                    val cleanUser = presence.username.ifBlank {
                                        topic.substringAfterLast("/")
                                    }.lowercase()
                                    onPresenceReceived(presence.copy(username = cleanUser))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing presence message: ${e.message}")
                                }
                            }
                            client.subscribe("bingo/v3/avatar_update/+", 1) { topic, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    val cleanUser = topic.substringAfterLast("/").trim().lowercase().removePrefix("@")
                                    onAvatarUpdateReceived(cleanUser, payload)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error handling avatar update for $topic: ${e.message}")
                                }
                            }
                            client.subscribe(BroadcastMessageManager.MQTT_BROADCAST_TOPIC, 1) { topic, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    BroadcastMessageManager.onBroadcastReceived(payload, appContext)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error handling broadcast message: ${e.message}")
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

    fun onPresenceReceived(presence: PlayerPresence) {
        val cleanUser = presence.username.trim().lowercase().removePrefix("@")
        if (cleanUser.isNotBlank()) {
            val previous = presenceMap[cleanUser]
            val effectiveTimestamp = if (presence.status.equals("OFFLINE", ignoreCase = true) && presence.timestamp <= 0L) {
                System.currentTimeMillis()
            } else {
                presence.timestamp
            }
            presenceMap[cleanUser] = presence.copy(username = cleanUser, timestamp = effectiveTimestamp)
            _presenceFlow.value = HashMap(presenceMap)

            // RULE 1: Friend Online Status Notification
            val isNowOnline = presence.status.equals("ONLINE", ignoreCase = true) ||
                              presence.status.equals("IN_LOBBY", ignoreCase = true) ||
                              presence.status.equals("PLAYING", ignoreCase = true)
            val wasOnline = previous != null && (
                previous.status.equals("ONLINE", ignoreCase = true) ||
                previous.status.equals("IN_LOBBY", ignoreCase = true) ||
                previous.status.equals("PLAYING", ignoreCase = true)
            )

            val myUser = currentActiveUsername
            val isSettled = (System.currentTimeMillis() - watcherStartTime) > 4000L

            if (isSettled && isNowOnline && !wasOnline && cleanUser != myUser) {
                val ctx = appContext
                if (ctx != null && BingoNotificationManager.isFriend(ctx, cleanUser)) {
                    val displayName = BingoNotificationManager.getFriendDisplayName(ctx, cleanUser)
                    val lastSeen = previous?.timestamp ?: BingoNotificationManager.getFriendLastSeen(ctx, cleanUser)
                    BingoNotificationManager.notifyFriendOnline(ctx, cleanUser, displayName, lastSeen)
                }
            }
        }
    }

    fun onAvatarUpdateReceived(cleanUser: String, payload: String) {
        val clean = cleanUser.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        try {
            val update = try {
                json.decodeFromString<AvatarUpdatePayload>(payload)
            } catch (_: Exception) {
                null
            }
            val rawAvatar = update?.avatar?.trim()?.takeIf { it.isNotBlank() }
                ?: try {
                    val match = "\"avatar\"\\s*:\\s*\"([^\"]*)\"".toRegex().find(payload)
                    match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }

            val avatar = if (isLocalFilePath(rawAvatar)) null else rawAvatar

            // 1. Evict memory cache and notify UI
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(clean)
            if (!avatar.isNullOrBlank()) {
                val bmp = com.bingo.multiplayer.presentation.common.decodeAvatarBitmap(avatar, null)
                if (bmp != null) {
                    com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:$clean", bmp.asImageBitmap())
                }
            }
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(clean)

            // 2. Update cloud session cache
            AccountSessionManager.defaultInstance.onRemoteAvatarUpdated(clean, avatar)

            // 3. Update friends repository
            com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.onRemoteAvatarUpdated(clean, avatar)

            // 4. Update online room sync
            com.bingo.multiplayer.domain.network.OnlineRoomSyncManager.activeInstance?.onRemoteAvatarUpdated(clean, avatar)

            // 5. Update LAN P2P session
            com.bingo.multiplayer.domain.network.LanP2pSessionManager.activeInstance?.onRemoteAvatarUpdated(clean, avatar)

            Log.i(TAG, "Applied real-time avatar update for @$clean (hasAvatar=${!avatar.isNullOrBlank()})")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling avatar update for @$clean: ${e.message}")
        }
    }

    fun isUserOnline(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        val p = presenceMap[clean] ?: return false
        return p.isOnline
    }

    /**
     * Formats player status cleanly:
     * - "online" — player is currently active in the app (not in lobby, not in game)
     * - "in-lobby" — player is in a waiting room / lobby
     * - "playing" — player is actively playing a game match
     * - "last seen just now" — within 60 seconds of leaving
     * - "last seen Xm ago" — up to 1 hour (e.g. "last seen 48m ago")
     * - "last seen 5:38pm" — exceeds 1 hour (up to 24 hours) formatted with 12-hour am/pm
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

        // 2. Active status: IN_LOBBY, PLAYING, or ONLINE
        if (p != null && (p.status.equals("ONLINE", ignoreCase = true) ||
                          p.status.equals("IN_LOBBY", ignoreCase = true) ||
                          p.status.equals("PLAYING", ignoreCase = true))) {
            val diffSec = ((now - p.timestamp) / 1000).coerceAtLeast(0)
            if (diffSec < 12) {
                return when {
                    p.status.equals("IN_LOBBY", ignoreCase = true) -> "in-lobby"
                    p.status.equals("PLAYING", ignoreCase = true) -> "playing"
                    else -> "online"
                }
            } else {
                // Heartbeat stopped (app closed/swiped/killed) -> user departed at p.timestamp
                return formatLastSeen(p.timestamp, now)
            }
        }

        // 3. Fallback timestamp when not in presenceMap
        val fbTs = fallbackLastSeen ?: 0L
        if (fbTs <= 0L) return "offline"
        val diffSec = ((now - fbTs) / 1000).coerceAtLeast(0)
        if (diffSec < 12) {
            return "online"
        }
        return formatLastSeen(fbTs, now)
    }

    fun formatLastSeen(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return "offline"
        val diffSec = ((now - timestamp) / 1000).coerceAtLeast(0)

        // Under 60 seconds
        if (diffSec < 60) return "last seen just now"

        // Up to 1 hour (60s to 3599s) -> "last seen 48m ago"
        if (diffSec < 3600) {
            val mins = (diffSec / 60).coerceAtLeast(1)
            return "last seen ${mins}m ago"
        }

        // Exceeds 1 hour, up to 24 hours -> "last seen 5:38pm"
        if (diffSec <= 86400) {
            val timeFormat = SimpleDateFormat("h:mma", Locale.US)
            val timeStr = timeFormat.format(Date(timestamp)).lowercase()
            return "last seen $timeStr"
        }

        // Exceeds 24 hours
        return "offline"
    }

    fun isStatusOnline(statusText: String): Boolean {
        return statusText.equals("online", ignoreCase = true) ||
               statusText.equals("in-lobby", ignoreCase = true) ||
               statusText.equals("playing", ignoreCase = true)
    }

    fun getStatusColor(statusText: String): Color {
        return when {
            statusText.equals("online", ignoreCase = true) -> Color(0xFF16A34A) // Vibrant Green
            statusText.equals("in-lobby", ignoreCase = true) -> Color(0xFFEAB308) // Amber Gold
            statusText.equals("playing", ignoreCase = true) -> Color(0xFF8B5CF6) // Royal Violet
            else -> Color(0xFF94A3B8) // Slate Grey
        }
    }
}
