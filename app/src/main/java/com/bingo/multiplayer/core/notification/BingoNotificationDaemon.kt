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

    // In-memory quick lookup for cooldowns
    private val friendAlertCooldown = ConcurrentHashMap<String, Long>()

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
            } ?: return

            val isNowOnline = presence.isOnline
            val presenceCache = context.getSharedPreferences(PREFS_PRESENCE_CACHE, Context.MODE_PRIVATE)
            val wasOnline = presenceCache.getBoolean(cleanUser, false)

            if (!wasOnline && isNowOnline) {
                // Friend just transitioned from OFFLINE to ONLINE!
                presenceCache.edit().putBoolean(cleanUser, true).apply()
                triggerFriendOnlineAlert(context, cleanUser, friendMatch.displayName)
            } else if (!isNowOnline) {
                presenceCache.edit().putBoolean(cleanUser, false).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling presence packet: ${e.message}")
        }
    }

    private fun handleInviteMessage(context: Context, myUsername: String, message: MqttMessage) {
        try {
            val payload = String(message.payload, StandardCharsets.UTF_8)
            val invite = json.decodeFromString<GameInvite>(payload)
            val now = System.currentTimeMillis()

            if ((now - invite.timestamp) < 900_000L && invite.roomCode.isNotBlank()) {
                val inviteCache = context.getSharedPreferences(PREFS_INVITES_CACHE, Context.MODE_PRIVATE)
                val key = "seen_${invite.roomCode}"
                val lastSeen = inviteCache.getLong(key, 0L)
                if (now - lastSeen > 60_000L) {
                    inviteCache.edit().putLong(key, now).apply()
                    BingoNotificationHelper.showGameInviteNotification(context, invite, myUsername)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling invite packet: ${e.message}")
        }
    }

    fun triggerFriendOnlineAlert(context: Context, friendUsername: String, displayName: String): Boolean {
        val cleanUser = friendUsername.trim().lowercase().removePrefix("@")
        val now = System.currentTimeMillis()
        val cooldownCache = context.getSharedPreferences(PREFS_COOLDOWN_CACHE, Context.MODE_PRIVATE)
        val lastAlert = cooldownCache.getLong(cleanUser, 0L)

        if (now - lastAlert > 60_000L) {
            cooldownCache.edit().putLong(cleanUser, now).apply()
            friendAlertCooldown[cleanUser] = now
            val cleanName = displayName.ifBlank { "@$cleanUser" }
            BingoNotificationHelper.showFriendOnlineNotification(
                context = context,
                friendUsername = cleanUser,
                friendDisplayName = cleanName
            )
            return true
        }
        return false
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

                val now = System.currentTimeMillis()

                // 1. Check for incoming invites in cloud storage
                val pendingInvites = GameInviteManager.fetchInvitesForUser(username)
                val inviteCache = context.getSharedPreferences(PREFS_INVITES_CACHE, Context.MODE_PRIVATE)
                for (invite in pendingInvites) {
                    if ((now - invite.timestamp) < 900_000L && invite.roomCode.isNotBlank()) {
                        val key = "seen_${invite.roomCode}"
                        val lastSeen = inviteCache.getLong(key, 0L)
                        if (now - lastSeen > 60_000L) {
                            inviteCache.edit().putLong(key, now).apply()
                            BingoNotificationHelper.showGameInviteNotification(context, invite, username)
                        }
                    }
                }

                // 2. Check friends presence in cloud storage
                val friends = getCachedFriends(context)
                if (friends.isNotEmpty()) {
                    val presenceCache = context.getSharedPreferences(PREFS_PRESENCE_CACHE, Context.MODE_PRIVATE)
                    for (friend in friends) {
                        val cleanUser = friend.username.trim().lowercase().removePrefix("@")
                        if (cleanUser.isBlank() || cleanUser == username) continue

                        val cloudPres = PresenceManager.fetchCloudPresence(cleanUser)
                        if (cloudPres != null) {
                            val isNowOnline = cloudPres.isOnline
                            val wasOnline = presenceCache.getBoolean(cleanUser, false)

                            if (!wasOnline && isNowOnline) {
                                presenceCache.edit().putBoolean(cleanUser, true).apply()
                                triggerFriendOnlineAlert(context, cleanUser, friend.displayName)
                            } else if (!isNowOnline) {
                                presenceCache.edit().putBoolean(cleanUser, false).apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during performCloudCheck: ${e.message}")
            }
        }
    }

    private fun getCachedFriends(context: Context): List<Friend> {
        val repoFriends = FriendsRepository.activeInstance?.friends?.value
        if (!repoFriends.isNullOrEmpty()) return repoFriends

        return try {
            val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("friends_list", null) ?: return emptyList()
            json.decodeFromString<List<Friend>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
