package com.bingo.multiplayer.domain.network

import android.util.Log
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID

object FriendRequestManager {
    private const val TAG = "FriendRequestManager"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BROKER_URL = NetworkConfig.BROKER_URL
    private val firebaseStore by lazy { FirebaseFriendsStore(FirestoreSyncManager.getInstance().friendDocuments()) }
    @androidx.annotation.VisibleForTesting internal var storeOverride: FirebaseFriendsStore? = null
    private val store get() = storeOverride ?: firebaseStore
    private val mutationMutex = kotlinx.coroutines.sync.Mutex()

    /** Persist in Firebase before emitting the MQTT notice and optional push. */
    suspend fun sendFriendRequest(request: FriendRequest): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withLock false
        if (!store.addRequest(target, request)) return@withLock false
        publishMqttPacket(target, FriendRequestPacket(type = "FRIEND_REQUEST", request = request))
        BingoFcmManager.sendFriendRequestPush(target, request)
        true
    }

    val activeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

    suspend fun awaitActiveJobs() {
        activeJobs.toList().forEach { it.join() }
    }

    /** Nullable variant lets reconciliation retain pending requests during a network failure. */
    suspend fun fetchRequestsForUserOrNull(username: String): List<FriendRequest>? = store.fetchRequests(username)

    /** One Firebase transaction saves both friendships and resolves the pending request. */
    suspend fun acceptFriendRequest(request: FriendRequest, myProfile: UserProfile): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        val sender = request.fromUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank() || sender.isBlank() || target != myProfile.username.trim().lowercase().removePrefix("@")) return@withLock false
        if (!store.accept(request, myProfile)) return@withLock false
        val acceptor = Friend(myProfile.uid, myProfile.username, myProfile.displayName,
            myProfile.avatarBase64?.takeIf { it.isNotBlank() } ?: myProfile.avatarUrl)
        publishMqttPacket(sender, FriendRequestPacket("FRIEND_ACCEPT",
            request.copy(status = FriendRequestStatus.ACCEPTED), acceptor))
        BingoFcmManager.sendFriendAcceptedPush(sender, request)
        true
    }

    suspend fun declineFriendRequest(request: FriendRequest): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withLock false
        if (!store.decline(request)) return@withLock false
        publishMqttPacket(request.fromUsername.trim().lowercase().removePrefix("@"),
            FriendRequestPacket("FRIEND_DECLINE", request.copy(status = FriendRequestStatus.DECLINED)))
        true
    }

    suspend fun outgoingStatuses(username: String, targets: Set<String>): Map<String, FriendRequestStatus?>? =
        store.outgoingStatuses(username, targets)

    suspend fun fetchCloudFriends(username: String): List<Friend>? = store.fetchFriends(username)

    internal suspend fun fetchCloudFriendsWithVersion(username: String): FriendsCloudStore.VersionedSnapshot<Friend>? =
        store.fetchFriendsWithVersion(username)

    suspend fun saveCloudFriends(username: String, friends: List<Friend>, expectedVersion: String? = null): Boolean =
        store.saveFriends(username, friends, expectedVersion = expectedVersion)

    suspend fun removeCloudFriend(username: String, friendIdentifier: String): Boolean = store.removeFriend(username, friendIdentifier)

    internal fun publishMqttPacket(targetUsername: String, packet: FriendRequestPacket) {
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val topic = "bingo/v3/friend_requests/$targetUsername"
            val payload = json.encodeToString(packet)
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)

            // 1. Fast-path via persistent EMQX socket
            if (EmqxTransport.wire.isHealthy) {
                try {
                    EmqxTransport.wire.publish(topic, bytes, retained = false)
                    Log.d(TAG, "Dispatched friend request packet (${packet.type}) to $topic via persistent EMQX")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Persistent EMQX publish failed, falling back: ${e.message}")
                }
            }

            // 2. Fallback via ephemeral client
            try {
                val clientId = "freq_pub_${UUID.randomUUID().toString().take(8)}"
                val mqttClient = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 5
                    NetworkConfig.applyMqttOptions(this)
                }
                mqttClient.connect(options).waitForCompletion(4000L)
                val msg = MqttMessage(bytes).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient.publish(topic, msg).waitForCompletion(4000L)
                try {
                    MqttClientShutdown.close(mqttClient)
                } catch (_: Exception) {}
                Log.d(TAG, "Dispatched friend request packet (${packet.type}) to $topic via fallback MQTT")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback MQTT friend request publish failed: ${e.message}")
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    /**
     * Subscribes to real-time friend requests and acceptances via MQTT.
     */
    fun startRequestListener(
        myUsername: String,
        onPacketReceived: (FriendRequestPacket) -> Unit
    ): AutoCloseable {
        val clean = myUsername.trim().lowercase().removePrefix("@")
        var client: MqttAsyncClient? = null
        try {
            val clientId = "freq_sub_${clean}_${UUID.randomUUID().toString().take(6)}"
            client = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                isAutomaticReconnect = true
                NetworkConfig.applyMqttOptions(this)
            }
            val topic = "bingo/v3/friend_requests/$clean"
            client.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        client?.subscribe(topic, 1) { _, message ->
                            try {
                                // Old retained broker packets are not fresh friend requests.
                                if (message.isRetained) return@subscribe
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val packet = json.decodeFromString<FriendRequestPacket>(payload)
                                onPacketReceived(packet)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing incoming friend request packet: ${e.message}")
                            }
                        }
                        Log.d(TAG, "Subscribed to friend requests on $topic")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to subscribe to friend requests on $topic: ${e.message}")
                    }
                }

                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })
            client.connect(options)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MQTT friend request listener: ${e.message}")
        }

        return AutoCloseable {
            // Disposing a UI listener must not block the UI or leave Paho's
            // asynchronous disconnect worker accessing released queues.
            val cleanup = MqttClientShutdown.closeAsync(client)
            activeJobs.add(cleanup)
            cleanup.invokeOnCompletion { activeJobs.remove(cleanup) }
        }
    }
}
