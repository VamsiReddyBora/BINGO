package com.bingo.multiplayer.domain.network

import android.util.Base64
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
    private val client = NetworkConfig.httpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BASE_URL = NetworkConfig.KEYVALUE_API_URL
    private val API_KEY = NetworkConfig.KEYVALUE_APP_KEY
    private val BROKER_URL = NetworkConfig.BROKER_URL

    private fun encodeBase64Url(raw: String): String {
        return Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trim()
    }

    private fun decodeBase64Url(b64: String): String {
        val clean = b64.trim().removeSurrounding("\"")
        val bytes = Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private val store by lazy {
        FriendsCloudStore(client, BASE_URL, API_KEY, ::encodeBase64Url, ::decodeBase64Url)
    }
    private val mutationMutex = kotlinx.coroutines.sync.Mutex()

    /** Persist first; an unavailable read must never replace another player's existing requests. */
    suspend fun sendFriendRequest(request: FriendRequest): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withLock false
        val existing = store.fetchRequests(target)?.toMutableList() ?: return@withLock false
        existing.removeAll { it.fromUsername.trim().removePrefix("@").equals(request.fromUsername.trim().removePrefix("@"), true) }
        existing.add(0, request)
        if (!store.saveRequests(target, existing)) return@withLock false
        publishMqttPacket(target, FriendRequestPacket(type = "FRIEND_REQUEST", request = request))
        BingoFcmManager.sendFriendRequestPush(target, request)
        true
    }

    /** Nullable variant lets reconciliation retain pending requests during a network failure. */
    suspend fun fetchRequestsForUserOrNull(username: String): List<FriendRequest>? = store.fetchRequests(username)

    /** Keep the request retryable until both friendships and request removal have been saved. */
    suspend fun acceptFriendRequest(request: FriendRequest, myProfile: UserProfile): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        val sender = request.fromUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank() || sender.isBlank() || target != myProfile.username.trim().lowercase().removePrefix("@")) return@withLock false
        val existing = store.fetchRequests(target)?.toMutableList() ?: return@withLock false
        val senderFriend = Friend(request.fromUid, request.fromUsername, request.fromDisplayName, request.fromAvatarUrl)
        val acceptor = Friend(myProfile.uid, myProfile.username, myProfile.displayName,
            myProfile.avatarBase64?.takeIf { it.isNotBlank() } ?: myProfile.avatarUrl)
        if (!addFriendToCloudList(target, senderFriend) || !addFriendToCloudList(sender, acceptor)) return@withLock false
        val firestore = FirestoreSyncManager.getInstance()
        if (!firestore.restoreFriend(myProfile.uid, senderFriend) ||
            !firestore.restoreFriend(request.fromUid, acceptor)) return@withLock false
        existing.removeAll { it.id == request.id || it.fromUsername.trim().removePrefix("@").equals(sender, true) }
        if (!store.saveRequests(target, existing)) return@withLock false
        publishMqttPacket(sender, FriendRequestPacket("FRIEND_ACCEPT",
            request.copy(status = FriendRequestStatus.ACCEPTED), acceptor))
        BingoFcmManager.sendFriendAcceptedPush(sender, myProfile.username, myProfile.displayName)
        true
    }

    suspend fun declineFriendRequest(request: FriendRequest): Boolean = mutationMutex.withLock {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withLock false
        val existing = store.fetchRequests(target)?.toMutableList() ?: return@withLock false
        existing.removeAll { it.id == request.id }
        store.saveRequests(target, existing)
    }

    suspend fun fetchCloudFriends(username: String): List<Friend>? = store.fetchFriends(username)

    suspend fun saveCloudFriends(username: String, friends: List<Friend>): Boolean = store.saveFriends(username, friends)

    private suspend fun addFriendToCloudList(username: String, friend: Friend): Boolean {
        // A failed GET used to become emptyList(), overwriting every friendship with this one friend.
        return store.addFriend(username, friend)
    }

    private fun publishMqttPacket(targetUsername: String, packet: FriendRequestPacket) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientId = "freq_pub_${UUID.randomUUID().toString().take(8)}"
                val mqttClient = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 5
                    NetworkConfig.applyMqttOptions(this)
                }
                mqttClient.connect(options).waitForCompletion(4000L)
                val topic = "bingo/v3/friend_requests/$targetUsername"
                val payload = json.encodeToString(packet)
                val msg = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient.publish(topic, msg).waitForCompletion(4000L)
                try {
                    mqttClient.disconnect().waitForCompletion(1000L)
                    mqttClient.close()
                } catch (_: Exception) {}
                Log.d(TAG, "Dispatched friend request packet (${packet.type}) to $topic")
            } catch (e: Exception) {
                Log.w(TAG, "MQTT friend request publish warning: ${e.message}")
            }
        }
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
            try {
                if (client?.isConnected == true) {
                    client.disconnect()
                }
                client?.close()
            } catch (_: Exception) {}
        }
    }
}
