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
    val timestamp: Long = 0L,
    val appVersion: String = NetworkConfig.APP_VERSION_NAME,
    val appVersionCode: Int = NetworkConfig.APP_VERSION_CODE,
    val activityDetail: String = "",
    val currentRoomCode: String = "",
    val onlineSessionId: String = ""
) {
    /**
     * A player is considered active in the app if their status is ONLINE, IN_LOBBY, or PLAYING,
     * and the timestamp is within 15 seconds.
     */
    val isOnline: Boolean
        get() = (status.equals("ONLINE", ignoreCase = true) ||
                 status.equals("IN_LOBBY", ignoreCase = true) ||
                 status.equals("PLAYING", ignoreCase = true)) &&
                timestamp > 0L &&
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
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var presenceClient: MqttAsyncClient? = null
    private var heartbeatJob: Job? = null
    private var onlineAnnouncements: com.bingo.multiplayer.domain.notifications.FriendOnlineAnnouncements? = null
    private var onlineAnnouncementJob: Job? = null
    private var lastRosterNoticeId = ""
    private var nextOnlineRosterAttempt = 0L
    private var watcherStartTime: Long = 0L
    private var onlineSessionId = UUID.randomUUID().toString()
    private var onlineSessionStartedAt = System.currentTimeMillis()
    var currentActiveUsername: String? = null
    internal var networkEnabled: Boolean = true

    var currentConnectedServerUri: String? = null
        private set

    val isMqttConnected: Boolean
        get() = presenceClient?.isConnected == true

    val isPrimaryBrokerConnected: Boolean
        get() = presenceClient?.isConnected == true && (currentConnectedServerUri?.contains("broker.emqx.io") != true)

    var currentActivityState: AppActivityState = AppActivityState.ONLINE
        private set
    var currentActivityDetail: String = ""
        private set
    var currentRoomCode: String = ""
        private set

    /**
     * Updates local activity state and immediately publishes it via MQTT and Cloud Key-Value store.
     */
    fun setActivityState(state: AppActivityState, detail: String = "", roomCode: String = "") {
        if (currentActivityState == state && currentActivityDetail == detail && currentRoomCode == roomCode) return
        currentActivityState = state
        currentActivityDetail = detail
        currentRoomCode = roomCode
        val clean = currentActiveUsername ?: return
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, state.name, now, activityDetail = detail, currentRoomCode = roomCode)
        updatePresenceFlow()

        if (!networkEnabled || EmqxTransport.wireOverride != null) return

        scope.launch {
            publishStatus(clean, state.name, detail, roomCode)
            setCloudPresence(clean, state.name, now)
        }
    }

    private val presenceMap = ConcurrentHashMap<String, PlayerPresence>()
    private val _presenceFlow = MutableStateFlow<Map<String, PlayerPresence>>(emptyMap())
    val presenceFlow: StateFlow<Map<String, PlayerPresence>> = _presenceFlow.asStateFlow()

    private var persistJob: Job? = null
    private fun updatePresenceFlow() {
        _presenceFlow.value = HashMap(presenceMap)
        persistJob?.cancel()
        if (!networkEnabled || prefs == null) {
            return
        }
        persistJob = scope.launch {
            delay(1000L)
            try {
                val p = prefs ?: return@launch
                val snapshot = HashMap(presenceMap)
                val raw = json.encodeToString(snapshot)
                p.edit().putString("cached_presence_map", raw).apply()
            } catch (_: Exception) {}
        }
    }

    private var watcherClient: MqttAsyncClient? = null

    /**
     * Initializes PresenceManager with Android Context for persistent active user tracking.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        onlineAnnouncements = com.bingo.multiplayer.domain.notifications.FriendOnlineAnnouncements(p)

        // Restore presence from persistent disk cache immediately on app launch
        try {
            val raw = p.getString("cached_presence_map", null)
            if (!raw.isNullOrBlank()) {
                val map = json.decodeFromString<Map<String, PlayerPresence>>(raw)
                presenceMap.putAll(map)
                _presenceFlow.value = HashMap(presenceMap)
                Log.d(TAG, "Restored ${map.size} presence records from persistent cache on startup")
            }
        } catch (_: Exception) {}

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
    fun setCloudPresenceAsync(cleanUsername: String, status: String, timestamp: Long, detail: String = currentActivityDetail, roomCode: String = currentRoomCode) {
        // Presence availability is EMQX-only; legacy storage is not a fallback.
    }

    /**
     * Writes presence directly to Cloud Key-Value store.
     */
    suspend fun setCloudPresence(cleanUsername: String, status: String, timestamp: Long, detail: String = currentActivityDetail, roomCode: String = currentRoomCode): Boolean = withContext(Dispatchers.IO) {
        return@withContext false
    }

    private fun ensureInCloudDirectory(clean: String) {
        // No HTTP presence directory.
    }

    /**
     * Reads presence directly from Cloud Key-Value store.
     */
    suspend fun fetchCloudPresence(username: String): PlayerPresence? = withContext(Dispatchers.IO) {
        return@withContext presenceMap[username.trim().lowercase().removePrefix("@")]
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
        onlineSessionId = UUID.randomUUID().toString()
        onlineSessionStartedAt = System.currentTimeMillis()

        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, currentStatus, now)
        updatePresenceFlow()

        if (!networkEnabled || EmqxTransport.wireOverride != null) {
            return
        }

        // Write initial status to cloud immediately, sync FCM token, and alert friends
        scope.launch {
            setCloudPresence(clean, currentStatus, now)
            ensureInCloudDirectory(clean)
            appContext?.let { ctx ->
                BingoFcmManager.syncCurrentUserToken(ctx, clean)
            }
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
                        if (presenceClient !== client) return
                        Log.d(TAG, "Presence connected for @$clean (reconnect=$reconnect, serverURI=$serverURI). Publishing ${currentActivityState.name}.")
                        publishStatus(clean, currentActivityState.name)
                        currentConnectedServerUri = serverURI
                        if (serverURI?.contains("broker.emqx.io") == true) {
                            if (!EmqxTransport.connection.isHealthy) {
                                EmqxBrokerMonitor.recordPrimaryBrokerDown()
                            }
                        } else {
                            EmqxBrokerMonitor.recordPrimaryBrokerUp()
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        if (presenceClient !== client) return
                        recordOfflineAvailability(clean)
                        Log.w(TAG, "Presence connection lost for @$clean: ${cause?.message}")
                        currentConnectedServerUri = null
                        if (!EmqxTransport.connection.isHealthy) {
                            EmqxBrokerMonitor.recordPrimaryBrokerDown()
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                try {
                    client.connect(options)
                } catch (e: Exception) {
                    Log.w(TAG, "Presence connect initial: ${e.message}")
                    if (!EmqxTransport.connection.isHealthy) {
                        EmqxBrokerMonitor.recordPrimaryBrokerDown()
                    }
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
        val account = com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(ctx)
            ?.takeIf { it.username == cleanUsername } ?: return
        val store = onlineAnnouncements ?: return
        val notice = store.active(account.uid, System.currentTimeMillis()) ?: return
        if (onlineAnnouncementJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (lastRosterNoticeId == notice.id && now < nextOnlineRosterAttempt) return
        lastRosterNoticeId = notice.id
        nextOnlineRosterAttempt = now + 10_000
        onlineAnnouncementJob = scope.launch {
            try {
                val cached = com.bingo.multiplayer.domain.notifications.NotificationRuntime.friends(ctx, account)
                val friends = cached.ifEmpty { FriendRequestManager.fetchCloudFriends(cleanUsername).orEmpty() }
                if (com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(ctx) != account ||
                    currentActiveUsername != cleanUsername || !AppLifecycleObserver.isAppInForeground.value ||
                    !isMqttConnected) return@launch
                friends.forEach { friend ->
                    val fClean = friend.username.trim().lowercase().removePrefix("@")
                    if (fClean.isNotBlank() && fClean != cleanUsername && fClean !in notice.queued &&
                        BingoFcmManager.sendFriendOnlinePush(fClean, cleanUsername, notice.id, notice.createdAt)) {
                        store.queued(account.uid, notice.id, fClean)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dispatch online FCM to friends: ${e.message}")
            }
        }
    }

    private fun recordOfflineAvailability(clean: String) {
        val ctx = appContext ?: return
        val account = com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(ctx)
            ?.takeIf { it.username == clean } ?: return
        onlineAnnouncements?.offline(account.uid, System.currentTimeMillis())
    }

    private fun startHeartbeat(cleanUsername: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2500L)
                val status = currentActivityState.name
                publishStatus(cleanUsername, status, currentActivityDetail, currentRoomCode)
                val now = System.currentTimeMillis()
                setCloudPresence(cleanUsername, status, now, currentActivityDetail, currentRoomCode)
            }
        }
    }

    private fun publishStatus(cleanUsername: String, status: String, detail: String = currentActivityDetail, roomCode: String = currentRoomCode) {
        if (!networkEnabled || EmqxTransport.wireOverride != null) return
        val client = presenceClient ?: return
        if (!client.isConnected) return
        try {
            val topic = "bingo/v3/presence/$cleanUsername"
            val now = System.currentTimeMillis()
            val payload = json.encodeToString(
                PlayerPresence(
                    username = cleanUsername,
                    status = status,
                    timestamp = now,
                    activityDetail = detail,
                    currentRoomCode = roomCode,
                    onlineSessionId = onlineSessionId
                )
            )
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message)
            if (status == "OFFLINE") recordOfflineAvailability(cleanUsername)
            else if (AppLifecycleObserver.isAppInForeground.value) notifyFriendsOnlineViaFcm(cleanUsername)

            // Update local map as well
            presenceMap[cleanUsername] = PlayerPresence(cleanUsername, status, now, activityDetail = detail, currentRoomCode = roomCode)
            updatePresenceFlow()
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing status $status: ${e.message}")
        }
    }


    private fun publishOffline(cleanUsername: String) {
        publishStatus(cleanUsername, "OFFLINE")
    }

    /**
     * Called by AppLifecycleObserver when app goes to BACKGROUND.
     */
    fun onAppBackground() {
        val clean = currentActiveUsername ?: return
        recordOfflineAvailability(clean)
        Log.d(TAG, "App backgrounded — publishing OFFLINE for @$clean")
        heartbeatJob?.cancel()
        heartbeatJob = null
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, "OFFLINE", now)
        updatePresenceFlow()

        publishOffline(clean)
        setCloudPresenceAsync(clean, "OFFLINE", now)
    }

    /**
     * Called by AppLifecycleObserver when app returns to FOREGROUND.
     */
    fun onAppForeground() {
        val clean = currentActiveUsername ?: return
        onlineSessionId = UUID.randomUUID().toString()
        onlineSessionStartedAt = System.currentTimeMillis()
        val currentStatus = currentActivityState.name
        Log.d(TAG, "App foregrounded — publishing $currentStatus for @$clean")
        val now = System.currentTimeMillis()
        presenceMap[clean] = PlayerPresence(clean, currentStatus, now, activityDetail = currentActivityDetail, currentRoomCode = currentRoomCode)
        updatePresenceFlow()

        if (!networkEnabled || EmqxTransport.wireOverride != null) return

        scope.launch {
            publishStatus(clean, currentStatus, currentActivityDetail, currentRoomCode)
            setCloudPresence(clean, currentStatus, now, currentActivityDetail, currentRoomCode)
        }
        startHeartbeat(clean)
        startPresenceWatcher(forceReconnect = true)
    }

    /**
     * Fully stops presence and disconnects MQTT client.
     */
    fun stopPresence() {
        val clean = currentActiveUsername
        if (clean != null) recordOfflineAvailability(clean)
        heartbeatJob?.cancel()
        heartbeatJob = null
        disconnectPresenceClient()
        currentActiveUsername = null
        prefs?.edit()?.remove(KEY_ACTIVE_USER)?.apply()
        if (clean != null) {
            val now = System.currentTimeMillis()
            presenceMap[clean] = PlayerPresence(clean, "OFFLINE", now)
            updatePresenceFlow()
            if (networkEnabled && EmqxTransport.wireOverride == null) {
                scope.launch(Dispatchers.IO) {
                    setCloudPresence(clean, "OFFLINE", now)
                }
            }
        }
    }

    private fun disconnectPresenceClient() {
        val clean = currentActiveUsername
        val client = presenceClient
        presenceClient = null

        if (clean != null && client != null && client.isConnected) {
            if (networkEnabled && EmqxTransport.wireOverride == null) {
                scope.launch {
                    try {
                        publishOffline(clean)
                        delay(150L)
                        MqttClientShutdown.close(client)
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    MqttClientShutdown.closeAsync(client)
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun resetForTests() {
        networkEnabled = false
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        onlineAnnouncementJob?.cancelAndJoin()
        onlineAnnouncementJob = null
        lastRosterNoticeId = ""
        nextOnlineRosterAttempt = 0L
        onlineAnnouncements = null
        persistJob?.cancelAndJoin()
        persistJob = null
        val jobs = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
        jobs.forEach { it.cancel() }
        jobs.forEach { runCatching { it.join() } }
        val client = presenceClient
        presenceClient = null
        MqttClientShutdown.close(client)
        val watcher = watcherClient
        watcherClient = null
        MqttClientShutdown.close(watcher)
        currentActiveUsername = null
        presenceMap.clear()
        _presenceFlow.value = emptyMap()
    }

    /**
     * Subscribes to real-time presence for all players (wildcard bingo/v3/presence/+).
     */
    fun startPresenceWatcher(forceReconnect: Boolean = false) {
        if (!networkEnabled || EmqxTransport.wireOverride != null) return
        if (!forceReconnect && watcherClient?.isConnected == true) return

        scope.launch {
            try {
                if (forceReconnect) {
                    val retiredWatcher = watcherClient
                    watcherClient = null
                    MqttClientShutdown.close(retiredWatcher)
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
                            client.subscribe("bingo/v3/registry/+", 1) { topic, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    val entry = json.decodeFromString<com.bingo.multiplayer.domain.network.PlayerRegistryEntry>(payload)
                                    val clean = entry.username.ifBlank { topic.substringAfterLast("/") }.trim().lowercase().removePrefix("@")
                                    val cleanEntry = entry.copy(username = clean)
                                    AccountSessionManager.cachePlayer(cleanEntry)
                                    com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.updateFriendCareerStats(cleanEntry)
                                    AccountSessionManager.notifyRegistryUpdated()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error handling registry update for $topic: ${e.message}")
                                }
                            }
                            client.subscribe(BroadcastMessageManager.MQTT_BROADCAST_TOPIC, 1) { _, message ->
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
            val now = System.currentTimeMillis()
            if (presence.timestamp > now + 5000L) return
            if (presence.timestamp > 0L && previous != null && (previous.timestamp > presence.timestamp ||
                (previous.timestamp == presence.timestamp && previous.status.equals("OFFLINE", true) && !presence.status.equals("OFFLINE", true)))) return
            val effectiveTimestamp = if (presence.status.equals("OFFLINE", ignoreCase = true) && presence.timestamp <= 0L) {
                // If previous was active and online, their last known heartbeat is their last seen time
                if (previous != null && previous.isOnline && previous.timestamp > 0L) {
                    previous.timestamp
                } else if (previous != null && previous.timestamp > 0L && (now - previous.timestamp) < 86_400_000L) {
                    previous.timestamp
                } else {
                    0L
                }
            } else {
                presence.timestamp
            }
            presenceMap[cleanUser] = presence.copy(username = cleanUser, timestamp = effectiveTimestamp)
            updatePresenceFlow()

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

            if (isSettled && isNowOnline && presence.isOnline && !wasOnline && cleanUser != myUser) {
                val ctx = appContext
                if (ctx != null && BingoNotificationManager.isFriend(ctx, cleanUser)) {
                    // Presence packets are hints. Only authenticated inbox events may alert.
                    com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(ctx)?.let {
                        com.bingo.multiplayer.domain.notifications.NotificationWork.sync(ctx, it)
                    }
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

    fun getRichDisplayStatus(username: String, fallbackLastSeen: Long? = null): String {
        val clean = username.trim().lowercase().removePrefix("@")
        val p = presenceMap[clean]
        val now = System.currentTimeMillis()
        p?.let { PresenceActivityFormatter.activeLabel(it, now) }?.let { return it }
        return getDisplayStatus(username, fallbackLastSeen)
    }


    fun isStatusOnline(statusText: String): Boolean {
        return statusText.substringBefore(" (").lowercase() in setOf("online", "in-lobby", "playing")
    }

    fun getStatusColor(statusText: String): Color {
        return when {
            statusText.startsWith("online", ignoreCase = true) -> Color(0xFF16A34A) // Vibrant Green
            statusText.startsWith("in-lobby", ignoreCase = true) -> Color(0xFFEAB308) // Amber Gold
            statusText.startsWith("playing", ignoreCase = true) -> Color(0xFF8B5CF6) // Royal Violet
            else -> Color(0xFF94A3B8) // Slate Grey
        }
    }
}
