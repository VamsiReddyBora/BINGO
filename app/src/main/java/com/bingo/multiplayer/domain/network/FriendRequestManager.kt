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
import kotlinx.coroutines.withContext
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

    /**
     * Sends a friend request to a target user.
     * Persists to cloud storage and broadcasts via MQTT for instant real-time delivery.
     */
    suspend fun sendFriendRequest(request: FriendRequest): Boolean = withContext(Dispatchers.IO) {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withContext false

        try {
            // 1. Fetch existing pending requests, filter duplicates
            val existing = fetchRequestsForUser(target).toMutableList()
            existing.removeAll { it.fromUsername.equals(request.fromUsername, ignoreCase = true) }
            existing.add(0, request)

            val jsonString = json.encodeToString(existing)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")

            val httpRequest = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/freq_$target?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()

            val success = client.newCall(httpRequest).execute().use { it.isSuccessful }

            // 2. Real-time MQTT notification dispatch
            publishMqttPacket(target, FriendRequestPacket(type = "FRIEND_REQUEST", request = request))

            // 3. High-Priority FCM Push dispatch to wake target device if app is killed or backgrounded
            BingoFcmManager.sendFriendRequestPush(target, request)

            Log.i(TAG, "Sent friend request from @${request.fromUsername} to @$target: success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send friend request: ${e.message}", e)
            false
        }
    }

    /**
     * Fetches pending incoming friend requests for a user.
     */
    suspend fun fetchRequestsForUser(username: String): List<FriendRequest> = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()

        try {
            val req = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/freq_$clean")
                .get()
                .build()

            val raw = client.newCall(req).execute().use { response ->
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
                    val list = json.decodeFromString<List<FriendRequest>>(jsonStr)
                    return@withContext list.filter { it.status == FriendRequestStatus.PENDING }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch friend requests: ${e.message}")
        }
        emptyList()
    }

    /**
     * Accepts a friend request.
     * Removes request from cloud, notifies requester via MQTT, and saves mutual friendship to cloud.
     */
    suspend fun acceptFriendRequest(
        request: FriendRequest,
        myProfile: UserProfile
    ): Boolean = withContext(Dispatchers.IO) {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        val sender = request.fromUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank() || sender.isBlank()) return@withContext false

        try {
            // 1. Remove from pending requests
            val existing = fetchRequestsForUser(target).toMutableList()
            existing.removeAll { it.id == request.id || it.fromUsername.equals(sender, ignoreCase = true) }
            val jsonString = json.encodeToString(existing)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")

            val updateReq = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/freq_$target?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(updateReq).execute().close()

            // 2. Sync to cloud friends lists for both users
            addFriendToCloudList(
                target,
                Friend(
                    uid = request.fromUid,
                    username = request.fromUsername,
                    displayName = request.fromDisplayName,
                    avatarUrl = request.fromAvatarUrl?.takeIf { it.length <= 120 },
                    isOnline = true
                )
            )

            addFriendToCloudList(
                sender,
                Friend(
                    uid = myProfile.uid,
                    username = myProfile.username,
                    displayName = myProfile.displayName,
                    avatarUrl = myProfile.avatarUrl?.takeIf { it.length <= 120 },
                    isOnline = true
                )
            )

            // 3. Dispatch real-time MQTT ACCEPT packet to sender with full acceptor profile
            val acceptFriend = Friend(
                uid = myProfile.uid,
                username = myProfile.username,
                displayName = myProfile.displayName,
                avatarUrl = myProfile.avatarUrl?.takeIf { it.length <= 120 },
                isOnline = true
            )
            val acceptPacket = FriendRequestPacket(
                type = "FRIEND_ACCEPT",
                request = request.copy(status = FriendRequestStatus.ACCEPTED),
                acceptorFriend = acceptFriend
            )
            publishMqttPacket(sender, acceptPacket)

            // 4. High-Priority FCM Push dispatch to notify requester even if their app is killed
            BingoFcmManager.sendFriendAcceptedPush(sender, myProfile.username, myProfile.displayName)

            Log.i(TAG, "Accepted friend request from @$sender for @$target")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept friend request: ${e.message}", e)
            false
        }
    }

    /**
     * Declines / Ignores a friend request.
     */
    suspend fun declineFriendRequest(request: FriendRequest): Boolean = withContext(Dispatchers.IO) {
        val target = request.toUsername.trim().lowercase().removePrefix("@")
        val sender = request.fromUsername.trim().lowercase().removePrefix("@")
        if (target.isBlank()) return@withContext false

        try {
            val existing = fetchRequestsForUser(target).toMutableList()
            existing.removeAll { it.id == request.id || it.fromUsername.equals(sender, ignoreCase = true) }
            val jsonString = json.encodeToString(existing)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")

            val updateReq = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/freq_$target?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(updateReq).execute().close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decline friend request: ${e.message}", e)
            false
        }
    }

    /**
     * Cloud sync: fetches cloud-persisted friends list for username.
     * Returns null on network/server failures to protect local cache when offline.
     * Returns a valid List<Friend> (including emptyList()) when cloud responded successfully.
     */
    suspend fun fetchCloudFriends(username: String): List<Friend>? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()

        try {
            val req = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/friends_$clean")
                .get()
                .build()

            val raw = client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.trim()?.removeSurrounding("\"")
            }

            if (raw == null) return@withContext null

            if (raw.isBlank() || raw == "null") {
                return@withContext emptyList()
            }

            val jsonStr = try {
                decodeBase64Url(raw)
            } catch (_: Exception) {
                raw
            }
            if (jsonStr.startsWith("[")) {
                return@withContext json.decodeFromString<List<Friend>>(jsonStr)
            }
            return@withContext emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch cloud friends for @$clean: ${e.message}")
            null
        }
    }

    /**
     * Fetches authoritative registered user directory from database.
     * Used to automatically prune friends whose accounts have been deleted.
     */
    suspend fun fetchValidUsernames(): Set<String>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/user_directory")
                .get()
                .build()

            val raw = client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.trim()?.removeSurrounding("\"")
            }

            if (!raw.isNullOrBlank() && raw != "null") {
                val decoded = if (raw.contains(",")) raw else {
                    try { decodeBase64Url(raw) } catch (_: Exception) { raw }
                }
                return@withContext decoded.split(",")
                    .map { it.trim().lowercase().removePrefix("@") }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch valid user directory: ${e.message}")
        }
        null
    }

    /**
     * Cloud sync: saves full friends list to cloud.
     */
    suspend fun saveCloudFriends(username: String, friends: List<Friend>): Boolean = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext false

        try {
            val jsonString = json.encodeToString(friends)
            val b64 = encodeBase64Url(jsonString)
            val encVal = java.net.URLEncoder.encode(b64, "UTF-8")

            val req = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/friends_$clean?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()

            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cloud friends for @$clean: ${e.message}", e)
            false
        }
    }

    private suspend fun addFriendToCloudList(username: String, friend: Friend) {
        val current = (fetchCloudFriends(username) ?: emptyList()).toMutableList()
        current.removeAll { it.uid == friend.uid || it.username.equals(friend.username, ignoreCase = true) }
        current.add(0, friend)
        saveCloudFriends(username, current)
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
