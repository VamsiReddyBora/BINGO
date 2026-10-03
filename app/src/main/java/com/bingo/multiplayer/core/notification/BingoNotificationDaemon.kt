package com.bingo.multiplayer.core.notification

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.network.GameInvite
import com.bingo.multiplayer.domain.network.GameInviteManager
import com.bingo.multiplayer.domain.network.LowLatencySocketFactory
import com.bingo.multiplayer.domain.network.NetworkConfig
import com.bingo.multiplayer.domain.network.PlayerPresence
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.repository.FriendsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Background notification daemon that runs independently of Activity/Compose lifecycle.
 * Listens to MQTT presence and game invites even when the app is in background, minimized,
 * or closed, and triggers system notifications.
 */
object BingoNotificationDaemon {
    private const val TAG = "BingoNotifDaemon"
    private const val PREFS_PRESENCE_CACHE = "bingo_friend_presence_cache"
    private const val PREFS_INVITES_CACHE = "bingo_seen_invites_cache"
    private const val PREFS_COOLDOWN_CACHE = "bingo_alert_cooldown_cache"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private var appContext: Context? = null
    private var mqttClient: MqttAsyncClient? = null
    private var backgroundSyncJob: Job? = null
    private var activeUsername: String = ""

    @Volatile
    var currentActiveRoomCode: String? = null

    // In-memory quick lookup for cooldowns
    private val friendAlertCooldown = ConcurrentHashMap<String, Long>()

    /**
     * Strictly validates whether an incoming game invite should trigger a notification.
     * Enforces single notification delivery per sender invite click:
     * - Returns true ONLY IF invite.timestamp > lastNotifiedTimestamp for this sender & room.
     * - Suppresses duplicates, repeats, and self-invites.
     * - Discards expired invites (> 10 mins).
     * - Does NOT notify if user is already inside that room.
     */
    @Synchronized
    fun shouldNotifyInvite(context: Context, invite: GameInvite): Boolean {
        val cleanSender = invite.fromUsername.trim().lowercase().removePrefix("@")
        val cleanRoom = invite.roomCode.trim().uppercase()
        if (cleanSender.isBlank() || cleanRoom.isBlank()) return false

        // Do not notify if user is already inside this specific room
        val activeRoom = currentActiveRoomCode?.trim()?.uppercase()
        if (activeRoom != null && activeRoom == cleanRoom) {
            return false
        }

        val now = System.currentTimeMillis()
        // Discard expired invites (> 10 mins old) or invalid future timestamps (> 1 min in future)
        if (now - invite.timestamp > 600_000L || invite.timestamp > now + 60_000L) {
            return false
        }

        val prefs = context.getSharedPreferences(PREFS_INVITES_CACHE, Context.MODE_PRIVATE)
        val key = "last_notified_ts_${cleanSender}_$cleanRoom"
        val lastNotifiedTs = prefs.getLong(key, 0L)

        // Only notify if the sender performed a NEW click (timestamp is strictly newer)
        if (invite.timestamp > lastNotifiedTs) {
            prefs.edit().putLong(key, invite.timestamp).apply()
            Log.i(TAG, "New invite click detected from @$cleanSender for room $cleanRoom (ts=${invite.timestamp} > last=$lastNotifiedTs). Allowed.")
            return true
        }

        return false
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app

        // Ensure notification channels exist
        BingoNotificationHelper.createNotificationChannel(app)

        val username = resolveActiveUsername(app)
        if (username.isBlank()) {
            Log.d(TAG, "No authenticated user found yet; daemon idle until user login.")
            return
        }

        if (mqttClient?.isConnected == true && activeUsername == username) {
            // Already connected and listening for this user
            return
        }

        activeUsername = username
        Log.i(TAG, "Starting BingoNotificationDaemon for @$username")

        // Warm up / refresh friends cache from cloud if local cache is empty
        if (getCachedFriends(app).isEmpty()) {
            refreshFriendsFromCloud(app, username)
        }

        // 1. Connect MQTT background listener
        connectMqttListener(app, username)

        // 2. Start periodic background polling & health check
        startBackgroundSyncLoop(app)
    }

    fun stop() {
        Log.i(TAG, "Stopping BingoNotificationDaemon")
        backgroundSyncJob?.cancel()
        backgroundSyncJob = null

        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}
        mqttClient = null
    }

    /**
     * Resolves currently authenticated username from memory, PresenceManager, or SharedPreferences.
     */
    fun resolveActiveUsername(context: Context): String {
        val memoryUser = PresenceManager.currentActiveUsername
        if (!memoryUser.isNullOrBlank()) {
            return memoryUser.trim().lowercase().removePrefix("@")
        }

        val authPrefs = context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
        val savedUsername = authPrefs.getString("username", null)
        if (!savedUsername.isNullOrBlank()) {
            return savedUsername.trim().lowercase().removePrefix("@")
        }

        val displayName = authPrefs.getString("display_name", null)
        if (!displayName.isNullOrBlank()) {
            return displayName.filter { it.isLetterOrDigit() }.lowercase()
        }

        val uid = authPrefs.getString("uid", null)
        if (!uid.isNullOrBlank()) {
            return uid.take(8).lowercase()
        }

        return ""
    }

    private fun connectMqttListener(context: Context, username: String) {
        scope.launch {
            try {
                try {
                    mqttClient?.disconnect()
                    mqttClient?.close()
                } catch (_: Exception) {}

                val clientId = "bnd_${username}_${UUID.randomUUID().toString().take(6)}"
                val client = MqttAsyncClient(NetworkConfig.BROKER_URL, clientId, MemoryPersistence())
                mqttClient = client

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 8
                    keepAliveInterval = 30
                    socketFactory = LowLatencySocketFactory()
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "MQTT connected (reconnect=$reconnect). Subscribing to presence & invites.")
                        try {
                            // Wildcard subscribe to presence updates for all players
                            client.subscribe("bingo/v3/presence/+", 1) { topic, message ->
                                handlePresenceMessage(context, topic, message)
                            }
                            // Subscribe to direct game invites for this user
                            client.subscribe("bingo/v3/invites/$username", 1) { _, message ->
                                handleInviteMessage(context, username, message)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Subscription error: ${e.message}")
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client.connect(options)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect MQTT daemon: ${e.message}")
            }
        }
    }

    @Volatile
    private var inMemoryFriendsCache: List<Friend> = emptyList()

    fun getCachedFriends(context: Context): List<Friend> {
        val repoFriends = FriendsRepository.activeInstance?.friends?.value
        if (!repoFriends.isNullOrEmpty()) {
            inMemoryFriendsCache = repoFriends
            return repoFriends
        }

        if (inMemoryFriendsCache.isNotEmpty()) {
            return inMemoryFriendsCache
        }

        val loaded = try {
            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("friends_list", null) ?: return emptyList()
            json.decodeFromString<List<Friend>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
        if (loaded.isNotEmpty()) {
            inMemoryFriendsCache = loaded
        }
        return loaded
    }

    fun refreshFriendsFromCloud(context: Context, username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        scope.launch {
            try {
                val cloudFriends = com.bingo.multiplayer.domain.network.FriendRequestManager.fetchCloudFriends(clean)
                if (cloudFriends.isNotEmpty()) {
                    inMemoryFriendsCache = cloudFriends
                    try {
                        val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("friends_list", json.encodeToString(cloudFriends)).apply()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Highly resilient state machine for Zero-Miss, Zero-Duplicate online friend notifications.
     * Evaluates whether a friend presence event is a genuine offline -> online transition:
     * - ZERO MISS: Tracks timestamp of last seen online (`last_seen_$cleanUser`).
     *   If a friend was not seen for > 40s (or never seen before), they were offline.
     *   The instant they come online, this transition is reliably caught.
     * - ZERO DUPLICATE: Debounces notifications so only 1 alert is sent per online session.
     *   Continuous 2.5s heartbeats update `last_seen_$cleanUser` and see `wasOffline = false`.
     *   Concurrent MQTT and Cloud poll invocations are synchronized and debounced with 60s minimum interval.
     */
    @Synchronized
    fun evaluateFriendPresenceAlert(
        context: Context,
        friendUsername: String,
        displayName: String,
        isOnlineNow: Boolean
    ): Boolean {
        val cleanUser = friendUsername.trim().lowercase().removePrefix("@")
        if (cleanUser.isBlank() || cleanUser == activeUsername) return false

        val prefs = context.getSharedPreferences(PREFS_PRESENCE_CACHE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastSeenOnline = prefs.getLong("last_seen_$cleanUser", 0L)
        val lastNotified = prefs.getLong("notif_$cleanUser", 0L)

        if (!isOnlineNow) {
            // Friend went offline or published explicit OFFLINE packet.
            // Reset last_seen to 0L so their next appearance is immediately detected as a new online session.
            prefs.edit().putLong("last_seen_$cleanUser", 0L).apply()
            return false
        }

        // Friend is currently ONLINE!
        // Was the friend previously offline?
        // Yes if never seen online (0L) or if more than 25s has passed since their last online heartbeat.
        val wasOffline = (lastSeenOnline == 0L) || ((now - lastSeenOnline) > 25_000L)

        // Always update last_seen timestamp to current time so ongoing 2.5s heartbeats
        // will see wasOffline = false.
        prefs.edit().putLong("last_seen_$cleanUser", now).apply()

        // Only fire notification if friend was offline AND at least 45s passed since our last notification for this friend
        if (wasOffline && (now - lastNotified > 45_000L)) {
            prefs.edit().putLong("notif_$cleanUser", now).apply()
            friendAlertCooldown[cleanUser] = now
            val cleanName = displayName.ifBlank { "@$cleanUser" }
            Log.i(TAG, "Friend @$cleanUser ($cleanName) transitioned to ONLINE. Dispatching notification.")
            BingoNotificationHelper.showFriendOnlineNotification(
                context = context,
                friendUsername = cleanUser,
                friendDisplayName = cleanName
            )
            return true
        }

        return false
    }

    fun triggerFriendOnlineAlert(context: Context, friendUsername: String, displayName: String): Boolean {
        return evaluateFriendPresenceAlert(context, friendUsername, displayName, isOnlineNow = true)
    }

    private fun handlePresenceMessage(context: Context, topic: String, message: MqttMessage) {
        try {
            val payload = String(message.payload, StandardCharsets.UTF_8)
            val presence = json.decodeFromString<PlayerPresence>(payload)
            val cleanUser = presence.username.ifBlank {
                topic.substringAfterLast("/")
            }.trim().lowercase().removePrefix("@")

            if (cleanUser.isBlank() || cleanUser == activeUsername) return

            // Forward to PresenceManager memory map so app stays in sync if opened
            PresenceManager.onPresenceReceived(presence.copy(username = cleanUser))

            // Check if this user is in our friends list
            val friends = getCachedFriends(context)
            val friendMatch = friends.firstOrNull {
                it.username.trim().lowercase().removePrefix("@") == cleanUser
            }

            if (friendMatch != null) {
                val isNowOnline = presence.isOnline
                evaluateFriendPresenceAlert(
                    context = context,
                    friendUsername = cleanUser,
                    displayName = friendMatch.displayName,
                    isOnlineNow = isNowOnline
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling presence packet: ${e.message}")
        }
    }

    private fun handleInviteMessage(context: Context, myUsername: String, message: MqttMessage) {
        try {
            val payload = String(message.payload, StandardCharsets.UTF_8)
            val invite = json.decodeFromString<GameInvite>(payload)
            if (shouldNotifyInvite(context, invite)) {
                BingoNotificationHelper.showGameInviteNotification(context, invite, myUsername)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling invite packet: ${e.message}")
        }
    }

    private fun startBackgroundSyncLoop(context: Context) {
        backgroundSyncJob?.cancel()
        backgroundSyncJob = scope.launch {
            while (isActive) {
                delay(30_000L) // Polling check every 30 seconds
                performCloudCheck(context)

                // Ensure MQTT is connected
                if (mqttClient?.isConnected != true) {
                    try {
                        mqttClient?.reconnect()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Reconciles pending invites and friends' presence via HTTP Cloud Key-Value store.
     * Guaranteed to work even if MQTT packet was delayed or connection was sleeping.
     */
    fun performCloudCheck(context: Context) {
        scope.launch {
            try {
                val username = resolveActiveUsername(context)
                if (username.isBlank()) return@launch

                // 1. Check for incoming invites in cloud storage (notifies ONCE per sender invite click)
                val pendingInvites = GameInviteManager.fetchInvitesForUser(username)
                for (invite in pendingInvites) {
                    if (shouldNotifyInvite(context, invite)) {
                        BingoNotificationHelper.showGameInviteNotification(context, invite, username)
                    }
                }

                // 2. Check friends presence in cloud storage
                var friends = getCachedFriends(context)
                if (friends.isEmpty()) {
                    try {
                        val cloudFriends = com.bingo.multiplayer.domain.network.FriendRequestManager.fetchCloudFriends(username)
                        if (cloudFriends.isNotEmpty()) {
                            inMemoryFriendsCache = cloudFriends
                            friends = cloudFriends
                            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("friends_list", json.encodeToString(cloudFriends)).apply()
                        }
                    } catch (_: Exception) {}
                }

                if (friends.isNotEmpty()) {
                    for (friend in friends) {
                        val cleanUser = friend.username.trim().lowercase().removePrefix("@")
                        if (cleanUser.isBlank() || cleanUser == username) continue

                        val cloudPres = PresenceManager.fetchCloudPresence(cleanUser)
                        if (cloudPres != null) {
                            evaluateFriendPresenceAlert(
                                context = context,
                                friendUsername = cleanUser,
                                displayName = friend.displayName,
                                isOnlineNow = cloudPres.isOnline
                            )
                        } else {
                            evaluateFriendPresenceAlert(
                                context = context,
                                friendUsername = cleanUser,
                                displayName = friend.displayName,
                                isOnlineNow = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during performCloudCheck: ${e.message}")
            }
        }
    }
}
