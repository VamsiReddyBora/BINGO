package com.bingo.multiplayer.domain.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID

import com.bingo.multiplayer.domain.notifications.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Serializable
data class GameInvite(
    val fromUsername: String,
    val fromDisplayName: String,
    val fromAvatarUrl: String? = null,
    val roomCode: String,
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: String = NotificationPolicy.eventId("game_invite", fromUsername, roomCode, timestamp.toString()),
    val recipientUid: String = "",
    val recipientUsername: String = "",
    val senderUid: String = "",
    val expiresAt: Long = timestamp + 120_000L
)

fun NotificationEvent.toInvite() = GameInvite(senderUsername, senderDisplayName, roomCode = roomCode,
    timestamp = createdAt, eventId = id, recipientUid = recipientUid, recipientUsername = recipientUsername,
    senderUid = senderUid, expiresAt = expiresAt)
fun GameInvite.toEvent() = NotificationEvent(eventId, NotificationKind.GAME_INVITE,
    senderUid, fromUsername, fromDisplayName, recipientUid, recipientUsername, timestamp, expiresAt, roomCode = roomCode)

enum class InviteSendResult(val message: String) {
    QUEUED("Invitation queued. It will be sent when connected."),
    SENT("Invitation sent."), FAILED("Could not queue invitation. Please try again.")
}

object GameInviteManager {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BROKER_URL = NetworkConfig.BROKER_URL
    @Volatile var stagedInvite: GameInvite? = null
    val foregroundInviteFlow = kotlinx.coroutines.flow.MutableSharedFlow<GameInvite>(replay = 1, extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal fun stageInvite(invite: GameInvite) { stagedInvite = invite; foregroundInviteFlow.tryEmit(invite) }
    fun consumeStagedInvite(): GameInvite? = stagedInvite.also { stagedInvite = null }
    fun clearForegroundInvite() { stagedInvite = null; foregroundInviteFlow.resetReplayCache() }

    suspend fun sendInvite(targetUsername: String, invite: GameInvite): InviteSendResult = withContext(Dispatchers.IO) {
        val context = NotificationRuntime.context() ?: return@withContext InviteSendResult.FAILED
        val account = NotificationRuntime.account(context) ?: return@withContext InviteSendResult.FAILED
        val clean = notificationUsername(targetUsername)
        if (clean.isBlank() || clean == account.username) return@withContext InviteSendResult.FAILED
        val payload = JSONObject().put("id", invite.eventId).put("kind", "GAME_INVITE").put("toUsername", clean)
            .put("createdAt", invite.timestamp).put("expiresAt", invite.expiresAt).put("roomCode", invite.roomCode.trim().uppercase())
        try {
            NotificationApi.call(context, account, "/v2/events", payload)
            publishMqttInvite(clean, invite.copy(fromAvatarUrl = null, recipientUsername = clean, senderUid = account.uid))
            InviteSendResult.SENT
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: NotificationApiException) {
            if ((e.retryable || e.status == 401) && NotificationWork.send(context, account, payload)) InviteSendResult.QUEUED else InviteSendResult.FAILED
        }
    }
    suspend fun fetchInvitesForUser(username: String): List<GameInvite> {
        val context = NotificationRuntime.context() ?: return emptyList()
        val account = NotificationRuntime.account(context) ?: return emptyList()
        if (notificationUsername(username) != account.username) return emptyList()
        return NotificationApi.inbox(context, account).filter { it.kind == NotificationKind.GAME_INVITE &&
            NotificationPolicy.eligible(it, account, System.currentTimeMillis()) && !NotificationRuntime.ledger(context).handled(account.uid, it.id) }.map { it.toInvite() }
    }
    fun removeInvite(targetUsername: String, invite: GameInvite) {
        val context = NotificationRuntime.context() ?: return
        val account = NotificationRuntime.account(context) ?: return
        if (notificationUsername(targetUsername) == account.username) NotificationRuntime.finishInvite(context, invite)
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
                                if (invite.timestamp > 0 && invite.timestamp <= now + 5000L && now - invite.timestamp < 120_000L) {
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
