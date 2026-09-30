package com.bingo.multiplayer.domain.network

import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Keep
@Serializable
data class OnlineRoomSession(
    val roomCode: String,
    val hostId: String,
    val hostUsername: String,
    val hostDisplayName: String,
    val hostAvatarUrl: String? = null,
    val status: String = "WAITING", // WAITING, PLAYING, CLOSED
    val boardSize: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val lastHeartbeat: Long = System.currentTimeMillis(),
    val players: List<Player> = emptyList()
)

sealed interface RoomJoinResult {
    data class Success(val room: OnlineRoomSession) : RoomJoinResult
    data class NotFound(val message: String) : RoomJoinResult
    data class AlreadyFull(val message: String) : RoomJoinResult
    data class AlreadyStarted(val message: String) : RoomJoinResult
    data class Expired(val message: String) : RoomJoinResult
    data class Error(val message: String) : RoomJoinResult
}

object OnlineRoomRegistry {
    private val client = NetworkConfig.httpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BASE_URL = NetworkConfig.KEYVALUE_API_URL
    private val API_KEY = NetworkConfig.KEYVALUE_APP_KEY
    private const val TAG = "OnlineRoomRegistry"

    private fun safeBase64Encode(bytes: ByteArray): String {
        val result = try {
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)?.trim()
        } catch (_: Throwable) { null }
        return result ?: java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).trim()
    }

    private fun safeBase64Decode(clean: String): ByteArray {
        val result = try {
            Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Throwable) { null }
        return result ?: try {
            java.util.Base64.getUrlDecoder().decode(clean)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(clean)
        }
    }

    internal fun encodeBase64Url(raw: String): String {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { gzip ->
            gzip.write(raw.toByteArray(StandardCharsets.UTF_8))
        }
        val compressed = baos.toByteArray()
        return "GZ:" + safeBase64Encode(compressed)
    }

    internal fun decodeBase64Url(raw: String): String {
        val clean = raw.trim().removeSurrounding("\"")
        if (clean.startsWith("GZ:")) {
            val b64 = clean.removePrefix("GZ:")
            val compressed = safeBase64Decode(b64)
            return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(compressed)).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        return try {
            val bytes = safeBase64Decode(clean)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            clean
        }
    }

    /**
     * Strips large avatar data and redundant stats from room registry player objects.
     * Keeps the entire OnlineRoomSession ultra-compact (< 400 bytes) so it never
     * exceeds keyvalue.immanuel.co's 1024-byte query parameter limit.
     * Avatars and match stats are resolved via usernames and MQTT packets.
     */
    private fun sanitizePlayer(p: Player): Player {
        return p.copy(
            avatarUrl = null,
            score = 0,
            completedLinesCount = 0,
            gamesPlayed = 0,
            gamesWon = 0,
            currentStreak = 0,
            level = p.level.coerceAtLeast(1)
        )
    }

    /**
     * Publishes room metadata as a retained MQTT message for instant fallback discovery.
     */
    private fun publishRoomMetaMqtt(session: OnlineRoomSession) {
        try {
            val client = MqttAsyncClient(NetworkConfig.BROKER_URL, "reg_meta_${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 3
            }
            client.connect(options).waitForCompletion(2000L)
            val jsonStr = json.encodeToString(session)
            val message = MqttMessage(jsonStr.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish("bingo/v3/room_meta/${session.roomCode}", message).waitForCompletion(2000L)
            client.disconnect()
            client.close()
        } catch (_: Exception) {}
    }

    /**
     * Clears retained MQTT room metadata when room is closed.
     */
    private fun clearRoomMetaMqtt(roomCode: String) {
        try {
            val client = MqttAsyncClient(NetworkConfig.BROKER_URL, "close_meta_${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 3
            }
            client.connect(options).waitForCompletion(2000L)
            val message = MqttMessage(ByteArray(0)).apply {
                qos = 1
                isRetained = true
            }
            client.publish("bingo/v3/room_meta/$roomCode", message).waitForCompletion(2000L)
            client.disconnect()
            client.close()
        } catch (_: Exception) {}
    }

    /**
     * Retrieves room session from MQTT retained topic.
     */
    private suspend fun getRoomMqtt(cleanCode: String): OnlineRoomSession? = withContext(Dispatchers.IO) {
        try {
            val client = MqttAsyncClient(NetworkConfig.BROKER_URL, "query_meta_${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 3
            }
            var result: OnlineRoomSession? = null
            val latch = CountDownLatch(1)
            client.connect(options).waitForCompletion(2000L)
            client.subscribe("bingo/v3/room_meta/$cleanCode", 1) { _, msg ->
                try {
                    val raw = String(msg.payload, StandardCharsets.UTF_8).trim()
                    if (raw.isNotBlank() && raw.startsWith("{")) {
                        val session = json.decodeFromString<OnlineRoomSession>(raw)
                        if (session.status != "CLOSED") {
                            result = session
                        }
                    }
                } catch (_: Exception) {}
                latch.countDown()
            }
            latch.await(1500L, TimeUnit.MILLISECONDS)
            client.disconnect()
            client.close()
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Registers a new room created by a host in cloud storage and MQTT.
     */
    suspend fun createRoom(
        roomCode: String,
        hostPlayer: Player,
        boardSize: Int = 5
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext false

        try {
            val safeHost = sanitizePlayer(hostPlayer.copy(isHost = true))
            val session = OnlineRoomSession(
                roomCode = cleanCode,
                hostId = safeHost.id,
                hostUsername = safeHost.username,
                hostDisplayName = safeHost.displayName,
                hostAvatarUrl = null,
                status = "WAITING",
                boardSize = boardSize,
                createdAt = System.currentTimeMillis(),
                lastHeartbeat = System.currentTimeMillis(),
                players = listOf(safeHost)
            )

            val jsonStr = json.encodeToString(session)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")

            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()

            val success = client.newCall(request).execute().use { it.isSuccessful }
            publishRoomMetaMqtt(session)
            Log.i(TAG, "Created room $cleanCode in cloud registry: success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create room $cleanCode: ${e.message}", e)
            false
        }
    }

    /**
     * Validates a room code against active rooms and adds the joiner if valid.
     * Dual-channel: verifies via KeyValue cloud storage with automatic MQTT retained fallback.
     * Allows any player (new or rejoining) to join as long as room is open and not full.
     */
    suspend fun validateAndJoinRoom(
        roomCode: String,
        joiner: Player
    ): RoomJoinResult = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.length != 6) {
            return@withContext RoomJoinResult.NotFound("Room code must be 6 characters.")
        }

        try {
            var session = getRoom(cleanCode)
            if (session == null) {
                // Dual-channel fallback: query MQTT retained room topic
                session = getRoomMqtt(cleanCode)
            }

            if (session == null) {
                return@withContext RoomJoinResult.NotFound("No active room found for code $cleanCode.")
            }

            if (session.status == "CLOSED") {
                return@withContext RoomJoinResult.NotFound("Room $cleanCode has been closed by the host.")
            }

            if (session.status == "PLAYING") {
                return@withContext RoomJoinResult.AlreadyStarted("Match in room $cleanCode has already started.")
            }

            val now = System.currentTimeMillis()
            // If host has been silent > 10 minutes, the room is considered expired
            if ((now - session.lastHeartbeat) > 600_000L) {
                return@withContext RoomJoinResult.Expired("Room $cleanCode has expired or the host has left.")
            }

            val existingInSession = session.players.find {
                it.id == joiner.id || (it.username.isNotBlank() && it.username.equals(joiner.username, ignoreCase = true))
            }
            val safeJoiner = sanitizePlayer(LobbyLifecycleEngine.onPlayerJoinSession(joiner.copy(isHost = false), existingInSession))
            val isAlreadyInRoom = existingInSession != null

            if (!isAlreadyInRoom && session.players.size >= 8) {
                return@withContext RoomJoinResult.AlreadyFull("Room $cleanCode is full (max 8 players).")
            }

            // Add or update joiner, resetting ready status to NOT_READY so they can rejoin and click ready
            val updatedPlayers = if (isAlreadyInRoom) {
                session.players.map { p ->
                    if (p.id == safeJoiner.id || (p.username.isNotBlank() && p.username.equals(safeJoiner.username, ignoreCase = true))) {
                        safeJoiner
                    } else if (p.isHost) {
                        p.copy(lastSeenTimestamp = now)
                    } else {
                        p
                    }
                }
            } else {
                session.players.map { p ->
                    if (p.isHost) p.copy(lastSeenTimestamp = now)
                    else p
                } + safeJoiner
            }

            val updatedSession = session.copy(
                players = updatedPlayers,
                lastHeartbeat = now
            )

            // Save updated session to cloud & MQTT
            try {
                val updatedJson = json.encodeToString(updatedSession)
                val b64 = encodeBase64Url(updatedJson)
                val encVal = URLEncoder.encode(b64, "UTF-8")
                val updateReq = Request.Builder()
                    .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                    .post("".toRequestBody(null))
                    .header("Content-Length", "0")
                    .build()
                client.newCall(updateReq).execute().close()
                publishRoomMetaMqtt(updatedSession)
            } catch (_: Exception) {}

            return@withContext RoomJoinResult.Success(updatedSession)
        } catch (e: Exception) {
            Log.e(TAG, "Error validating room $cleanCode: ${e.message}", e)
            return@withContext RoomJoinResult.Error("Network error while validating room $cleanCode. Please check your connection.")
        }
    }

    /**
     * Fetches current snapshot of a room.
     */
    suspend fun getRoom(roomCode: String): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/GetValue/$API_KEY/room_$cleanCode")
                .get()
                .build()

            val raw = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.trim()?.removeSurrounding("\"")
            }

            if (!raw.isNullOrBlank() && raw != "null" && !raw.contains("error", ignoreCase = true)) {
                val jsonStr = try { decodeBase64Url(raw) } catch (_: Exception) { raw }
                if (jsonStr.startsWith("{")) {
                    return@withContext json.decodeFromString<OnlineRoomSession>(jsonStr)
                }
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * High-reliability dual-channel room synchronization.
     * Host writes heartbeat and retrieves joined players;
     * Joiner refreshes presence and verifies host heartbeat.
     * Returns full updated OnlineRoomSession or null if closed/expired.
     */
    suspend fun syncRoom(
        roomCode: String,
        localPlayer: Player,
        knownPlayers: List<Player> = emptyList()
    ): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext null

        try {
            var session = getRoom(cleanCode)
            if (session == null && !localPlayer.isHost) {
                session = getRoomMqtt(cleanCode)
            }
            if (session == null || session.status == "CLOSED") return@withContext null

            val now = System.currentTimeMillis()
            val safeLocal = sanitizePlayer(localPlayer).copy(lastSeenTimestamp = now)

            val isHostAlive = (now - session.lastHeartbeat) < 600_000L

            val playerExists = session.players.any {
                it.id == safeLocal.id || (it.username.isNotBlank() && it.username.equals(safeLocal.username, ignoreCase = true))
            }
            val updatedPlayers = if (playerExists) {
                session.players.map { existing ->
                    if (existing.id == safeLocal.id || (existing.username.isNotBlank() && existing.username.equals(safeLocal.username, ignoreCase = true))) {
                        safeLocal
                    } else if (localPlayer.isHost) {
                        val known = knownPlayers.find {
                            it.id == existing.id || (it.username.isNotBlank() && it.username.equals(existing.username, ignoreCase = true))
                        }
                        val (effReady, effVer) = if (known != null) {
                            LobbyLifecycleEngine.reconcileReadyStatus(
                                currentStatus = existing.lobbyReadyStatus,
                                currentVersion = existing.readyVersion,
                                incomingStatus = known.lobbyReadyStatus,
                                incomingVersion = known.readyVersion
                            )
                        } else {
                            Pair(existing.lobbyReadyStatus.ifBlank { LobbyLifecycleEngine.STATUS_NOT_READY }, existing.readyVersion)
                        }
                        existing.copy(
                            lobbyReadyStatus = effReady,
                            readyVersion = effVer,
                            lastSeenTimestamp = maxOf(existing.lastSeenTimestamp, known?.lastSeenTimestamp ?: 0L)
                        )
                    } else if (existing.isHost && isHostAlive) {
                        existing.copy(lastSeenTimestamp = now)
                    } else {
                        existing
                    }
                }
            } else {
                session.players.map { existing ->
                    if (existing.isHost && isHostAlive) {
                        existing.copy(lastSeenTimestamp = now)
                    } else {
                        existing
                    }
                } + safeLocal
            }

            val newHeartbeat = if (localPlayer.isHost) now else session.lastHeartbeat
            val updatedSession = session.copy(
                players = updatedPlayers,
                lastHeartbeat = newHeartbeat
            )

            val localStatusChanged = session.players.any {
                (it.id == safeLocal.id || (it.username.isNotBlank() && it.username.equals(safeLocal.username, ignoreCase = true))) &&
                it.lobbyReadyStatus != safeLocal.lobbyReadyStatus
            }

            // Write back to cloud & MQTT if host, if local player was not present, or if local ready status changed
            if (localPlayer.isHost || !playerExists || localStatusChanged) {
                try {
                    val updatedJson = json.encodeToString(updatedSession)
                    val b64 = encodeBase64Url(updatedJson)
                    val encVal = URLEncoder.encode(b64, "UTF-8")
                    val request = Request.Builder()
                        .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                        .post("".toRequestBody(null))
                        .header("Content-Length", "0")
                        .build()
                    client.newCall(request).execute().close()
                    publishRoomMetaMqtt(updatedSession)
                } catch (_: Exception) {}
            }

            return@withContext updatedSession
        } catch (e: Exception) {
            Log.w(TAG, "syncRoom error for $cleanCode: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Instantly updates a specific player's ready status in the room session across cloud and MQTT.
     */
    suspend fun updatePlayerReadyStatus(
        roomCode: String,
        playerId: String,
        readyStatus: String,
        readyVersion: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank() || playerId.isBlank()) return@withContext false
        try {
            var session = getRoom(cleanCode) ?: getRoomMqtt(cleanCode) ?: return@withContext false
            val now = System.currentTimeMillis()
            var modified = false
            val updatedPlayers = if (session.players.any { it.id == playerId || (it.username.isNotBlank() && it.username.equals(playerId, ignoreCase = true)) }) {
                session.players.map { p ->
                    if (p.id == playerId || (p.username.isNotBlank() && p.username.equals(playerId, ignoreCase = true))) {
                        val (effReady, effVer) = LobbyLifecycleEngine.reconcileReadyStatus(
                            currentStatus = p.lobbyReadyStatus,
                            currentVersion = p.readyVersion,
                            incomingStatus = readyStatus,
                            incomingVersion = readyVersion
                        )
                        if (effReady == p.lobbyReadyStatus && effVer <= p.readyVersion) {
                            p // Ignore out-of-order stale update
                        } else {
                            modified = true
                            p.copy(
                                lobbyReadyStatus = effReady,
                                readyVersion = effVer,
                                lastSeenTimestamp = now
                            )
                        }
                    } else {
                        p
                    }
                }
            } else {
                modified = true
                session.players + Player(
                    id = playerId,
                    displayName = "Player",
                    lobbyReadyStatus = readyStatus,
                    readyVersion = readyVersion,
                    lastSeenTimestamp = now
                )
            }
            if (!modified) return@withContext true
            val updatedSession = session.copy(players = updatedPlayers, lastHeartbeat = now)
            val updatedJson = json.encodeToString(updatedSession)
            val b64 = encodeBase64Url(updatedJson)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
            publishRoomMetaMqtt(updatedSession)
            true
        } catch (e: Exception) {
            Log.w(TAG, "updatePlayerReadyStatus error for $cleanCode: ${e.message}")
            false
        }
    }

    /**
     * Heartbeat room from host so it doesn't expire.
     */
    suspend fun heartbeatRoom(roomCode: String) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext
        try {
            val current = getRoom(cleanCode) ?: return@withContext
            if (current.status == "CLOSED") return@withContext
            val updated = current.copy(lastHeartbeat = System.currentTimeMillis())
            val jsonStr = json.encodeToString(updated)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
            publishRoomMetaMqtt(updated)
        } catch (_: Exception) {}
    }

    /**
     * Updates status (e.g. "PLAYING" when game starts, "CLOSED" when host leaves).
     */
    suspend fun updateRoomStatus(roomCode: String, status: String) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext
        try {
            val current = getRoom(cleanCode) ?: return@withContext
            val updated = current.copy(status = status, lastHeartbeat = System.currentTimeMillis())
            val jsonStr = json.encodeToString(updated)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
            if (status == "CLOSED") {
                clearRoomMetaMqtt(cleanCode)
            } else {
                publishRoomMetaMqtt(updated)
            }
        } catch (_: Exception) {}
    }

    /**
     * Removes a player from a room (e.g. when kicked or left).
     */
    suspend fun removePlayerFromRoom(roomCode: String, playerId: String) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank() || playerId.isBlank()) return@withContext
        try {
            val current = getRoom(cleanCode) ?: return@withContext
            val updatedPlayers = current.players.filterNot { it.id == playerId }
            val updated = current.copy(players = updatedPlayers, lastHeartbeat = System.currentTimeMillis())
            val jsonStr = json.encodeToString(updated)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            client.newCall(request).execute().close()
            publishRoomMetaMqtt(updated)
        } catch (_: Exception) {}
    }

    /**
     * Closes the room completely. Clears cloud registry and MQTT metadata.
     */
    suspend fun closeRoom(roomCode: String) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        updateRoomStatus(cleanCode, "CLOSED")
        clearRoomMetaMqtt(cleanCode)
    }
}
