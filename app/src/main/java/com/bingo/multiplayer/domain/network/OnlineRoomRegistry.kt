package com.bingo.multiplayer.domain.network

import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Keep
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
    val players: List<Player> = emptyList(),
    val currentSeed: Long = 0L,
    val isManualBoard: Boolean = false,
    val isDynamicBoard: Boolean = false,
    val currentTurnPlayerId: String = "",
    val turnClock: MultiplayerTurnClock? = null,
    val simplifiedRulesVersion: Int = 1,
    val protocolVersion: Int = 0,
    val startCommitDeadline: Long = 0L,
    val canonicalStartPacketJson: String? = null,
    @kotlinx.serialization.SerialName("mp")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val manualPreparationComplete: Boolean? = null,
    @kotlinx.serialization.SerialName("mv")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val metadataProtocolVersion: Int = 0,
    @kotlinx.serialization.SerialName("r") val revision: Long = 0L,
    @kotlinx.serialization.SerialName("tc") val isTerminalClosed: Boolean = false
)

sealed interface RoomJoinResult {
    data class Success(val room: OnlineRoomSession) : RoomJoinResult
    data class NotFound(val message: String) : RoomJoinResult
    data class AlreadyFull(val message: String) : RoomJoinResult
    data class AlreadyStarted(val message: String) : RoomJoinResult
    data class Expired(val message: String) : RoomJoinResult
    data class Error(val message: String) : RoomJoinResult
}

sealed interface MutationResult {
    data class Applied(val session: OnlineRoomSession) : MutationResult
    data class Rejected(val reason: String) : MutationResult
    data class Unavailable(val error: Throwable) : MutationResult
    val sessionOrNull: OnlineRoomSession? get() = (this as? Applied)?.session
}

sealed interface InvitationReadinessResult {
    object Ready : InvitationReadinessResult
    data class MirrorPending(val session: OnlineRoomSession) : InvitationReadinessResult
    data class TransientUnavailable(val reason: String) : InvitationReadinessResult
    data class Rejected(val reason: String) : InvitationReadinessResult
}

object OnlineRoomRegistry {
    @androidx.annotation.VisibleForTesting
    internal var client = NetworkConfig.httpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val BASE_URL = NetworkConfig.KEYVALUE_API_URL
    private val API_KEY = NetworkConfig.KEYVALUE_APP_KEY
    private const val TAG = "OnlineRoomRegistry"

    internal data class CachedRoom(val session: OnlineRoomSession, val timestamp: Long)
    internal val recentRoomCache = java.util.concurrent.ConcurrentHashMap<String, CachedRoom>()
    internal val terminallyClosedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val roomStatusMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun rememberCanonicalRoom(session: OnlineRoomSession) {
        val code = session.roomCode
        recentRoomCache.compute(code) { _, previous ->
            if (previous != null && previous.session.metadataProtocolVersion == 3 &&
                previous.session.revision > session.revision) previous
            else CachedRoom(session, System.currentTimeMillis())
        }
        roomStatusMap[code] = "${session.status}:${session.currentSeed}:${session.revision}"
        if (session.status == "CLOSED" || session.isTerminalClosed) terminallyClosedRooms.add(code)
        pendingClosures[code]?.let { pending ->
            if (pending.expectedSeed != null && session.currentSeed != 0L && pending.expectedSeed != session.currentSeed) {
                pendingClosures.remove(code, pending)
                persistPendingClosures()
            }
        }
    }

    private suspend fun readCanonicalRoom(code: String): Result<OnlineRoomSession?> {
        val result = RoomMetadataApi.read(code)
        result.getOrNull()?.let(::rememberCanonicalRoom)
        return result
    }

    private suspend fun publishCanonicalMutation(code: String, result: MutationResult): MutationResult {
        if (result is MutationResult.Applied) {
            rememberCanonicalRoom(result.session)
            if (result.session.status == "CLOSED") {
                clearRoomMetaMqtt(code)
                pendingClosures.remove(code)
                persistPendingClosures()
            } else publishRoomMetaMqtt(result.session)
        }
        return result
    }

    private const val PREFS_CLOSURES = "bingo_pending_closures"
    @androidx.annotation.VisibleForTesting
    var customPrefs: android.content.SharedPreferences? = null
    @androidx.annotation.VisibleForTesting
    var simulatedHttpException: Exception? = null
    @androidx.annotation.VisibleForTesting
    var simulatedMqttSession: OnlineRoomSession? = null

    private fun getPrefs(): android.content.SharedPreferences? {
        if (customPrefs != null) return customPrefs
        val ctx = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()
        return ctx?.getSharedPreferences(PREFS_CLOSURES, android.content.Context.MODE_PRIVATE)
    }

    private fun persistPendingClosures() {
        try {
            val p = getPrefs() ?: return
            val jsonList = pendingClosures.values.map { entry ->
                "${entry.roomCode},${entry.expectedSeed ?: 0L},${entry.timestamp},${entry.attempts}"
            }.toSet()
            p.edit().putStringSet("pending_closures_set", jsonList).apply()
        } catch (_: Exception) {}
    }

    internal fun reloadDurableClosures() {
        try {
            val p = getPrefs() ?: return
            val saved = p.getStringSet("pending_closures_set", null) ?: return
            saved.forEach { line ->
                val parts = line.split(",")
                if (parts.isNotEmpty()) {
                    val code = parts[0].trim().uppercase()
                    val seed = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L }
                    val ts = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis()
                    val attempts = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    if (code.isNotBlank() && !pendingClosures.containsKey(code)) {
                        val entry = PendingClosure(code, seed, ts, attempts)
                        pendingClosures[code] = entry
                        queueDurableClosureRetry(code, seed)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    internal data class PendingClosure(
        val roomCode: String,
        val expectedSeed: Long?,
        val timestamp: Long,
        var attempts: Int = 0
    )
    internal val pendingClosures = java.util.concurrent.ConcurrentHashMap<String, PendingClosure>()
    private val scope = CoroutineScope(Dispatchers.IO)

    internal fun hasPendingClosure(cleanCode: String, session: OnlineRoomSession? = recentRoomCache[cleanCode]?.session): Boolean {
        val pending = pendingClosures[cleanCode] ?: return false
        return pending.expectedSeed == null || session == null || session.currentSeed == 0L ||
            pending.expectedSeed == session.currentSeed
    }

    internal fun queueDurableClosureRetry(cleanCode: String, expectedSeed: Long?) {
        val currentSeed = recentRoomCache[cleanCode]?.session?.currentSeed
        if (expectedSeed != null && currentSeed != null && currentSeed != 0L && currentSeed != expectedSeed) return
        val entry = pendingClosures.computeIfAbsent(cleanCode) {
            PendingClosure(cleanCode, expectedSeed, System.currentTimeMillis())
        }
        persistPendingClosures()
        scope.launch {
            var delayMs = 500L
            while (isActive && pendingClosures[cleanCode] === entry) {
                entry.attempts++
                persistPendingClosures()
                val success = executeClosurePersist(cleanCode, entry.expectedSeed)
                if (success) {
                    pendingClosures.remove(cleanCode, entry)
                    persistPendingClosures()
                    Log.i(TAG, "Durable closure succeeded for $cleanCode on attempt ${entry.attempts}")
                    break
                }
                kotlinx.coroutines.delay(delayMs)
                delayMs = minOf((delayMs * 1.5).toLong(), 30000L)
            }
        }
    }

    internal suspend fun executeClosurePersist(cleanCode: String, expectedSeed: Long?): Boolean = withContext(Dispatchers.IO) {
        if (RoomMetadataApi.enabled) {
            val result = RoomMetadataApi.coordinator().mutate(cleanCode, expectedSeed = expectedSeed,
                allowAlreadyClosed = true) { it.copy(status = "CLOSED", isTerminalClosed = true) }
            publishCanonicalMutation(cleanCode, result)
            if (result is MutationResult.Rejected) {
                pendingClosures[cleanCode]?.takeIf { it.expectedSeed == expectedSeed }?.let {
                    pendingClosures.remove(cleanCode, it); persistPendingClosures()
                }
            }
            return@withContext result is MutationResult.Applied
        }
        try {
            val fetchedRoom = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1500L).getOrNull()
            val mqttRoom = if (fetchedRoom == null) getRoomMqtt(cleanCode, 1500L) else null
            val validatedRoom = fetchedRoom ?: mqttRoom ?: recentRoomCache[cleanCode]?.session
            if (validatedRoom == null) {
                if (expectedSeed != null) {
                    Log.w(TAG, "Closure retry deferred for $cleanCode: no validated room context during outage")
                    return@withContext false
                }
            } else {
                val activeSeed = validatedRoom.currentSeed
                if (expectedSeed != null && activeSeed != 0L && activeSeed != expectedSeed) {
                    Log.w(TAG, "Closure retry aborted for $cleanCode: seed changed from $expectedSeed to $activeSeed")
                    pendingClosures.remove(cleanCode)
                    persistPendingClosures()
                    return@withContext false
                }
            }

            // R9-F03: Require fresh transport evidence (HTTP or MQTT) before performing destructive closure writes.
            // If only stale cache is available during an outage, defer destructive closure work!
            if (fetchedRoom == null && mqttRoom == null) {
                Log.w(TAG, "Closure retry deferred for $cleanCode: no fresh transport context available during outage")
                return@withContext false
            }

            val closedSession = (validatedRoom ?: OnlineRoomSession(
                roomCode = cleanCode,
                hostId = "",
                hostUsername = "",
                hostDisplayName = "Host",
                status = "CLOSED",
                isTerminalClosed = true,
                createdAt = System.currentTimeMillis()
            )).copy(
                status = "CLOSED",
                isTerminalClosed = true,
                lastHeartbeat = System.currentTimeMillis()
            )
            val jsonStr = json.encodeToString(closedSession)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val roomReq = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            val roomOk = try {
                val call = client.newCall(roomReq)
                suspendCancellableCoroutine<Boolean> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            val ok = response.isSuccessful
                            response.close()
                            if (cont.isActive) cont.resume(ok) {}
                        }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { false }

            val tombReq = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_closed_$cleanCode?value=true")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            val tombOk = try {
                val call = client.newCall(tombReq)
                suspendCancellableCoroutine<Boolean> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            val ok = response.isSuccessful
                            response.close()
                            if (cont.isActive) cont.resume(ok) {}
                        }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { false }

            if (roomOk && tombOk) {
                terminallyClosedRooms.add(cleanCode)
                setRoomStatusHttp(cleanCode, "CLOSED", expectedSeed ?: 0L, closedSession.revision + 1L)
                clearRoomMetaMqtt(cleanCode)
                recentRoomCache.remove(cleanCode)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeBase64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).trim()
    }

    private fun safeBase64Decode(clean: String): ByteArray {
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                clean.toByteArray(StandardCharsets.UTF_8)
            }
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
        if (clean.startsWith("{") || clean.startsWith("[")) {
            return clean
        }
        if (clean.startsWith("GZ:")) {
            try {
                val b64 = clean.removePrefix("GZ:")
                val compressed = safeBase64Decode(b64)
                return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(compressed)).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {}
        }
        return try {
            val bytes = safeBase64Decode(clean)
            val decoded = String(bytes, StandardCharsets.UTF_8)
            if (decoded.startsWith("{") || decoded.startsWith("[")) decoded else clean
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
    private suspend fun publishRoomMetaMqtt(session: OnlineRoomSession): Boolean {
        currentCoroutineContext().ensureActive()
        val activeSync = OnlineRoomSyncManager.activeInstance
        if (activeSync != null && activeSync.isConnected) {
            val ok = activeSync.publishRetainedRoomMetaAwaited(session)
            if (ok) return true
        }
        return withContext(Dispatchers.IO) {
            ensureActive()
            var client: MqttAsyncClient? = null
            try {
                client = MqttAsyncClient(NetworkConfig.BROKER_URL, "reg_meta_${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                val connected = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation {
                            try { client.disconnectForcibly(0L, 50L) } catch (_: Exception) {}
                        }
                        try {
                            val token = client.connect(options, null, object : IMqttActionListener {
                                override fun onSuccess(asyncActionToken: IMqttToken?) {
                                    if (cont.isActive) cont.resume(true) {}
                                }
                                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                            })
                            if (token.isComplete && cont.isActive) {
                                cont.resume(token.exception == null) {}
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                } ?: false
                if (!connected) return@withContext false
                ensureActive()
                val jsonStr = json.encodeToString(session)
                val message = MqttMessage(jsonStr.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                val published = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation {
                            try { client.disconnectForcibly(0L, 50L) } catch (_: Exception) {}
                        }
                        try {
                            val token = client.publish("bingo/v3/room_meta/${session.roomCode}", message, null, object : IMqttActionListener {
                                override fun onSuccess(asyncActionToken: IMqttToken?) {
                                    if (cont.isActive) cont.resume(true) {}
                                }
                                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                            })
                            if (token.isComplete && cont.isActive) {
                                cont.resume(token.exception == null) {}
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                } ?: false
                published
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                try {
                    client?.disconnectForcibly(0L, 50L)
                } catch (_: Exception) {}
                try {
                    client?.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Clears retained MQTT room metadata when room is closed.
     */
    private suspend fun clearRoomMetaMqtt(roomCode: String): Boolean {
        currentCoroutineContext().ensureActive()
        val activeSync = OnlineRoomSyncManager.activeInstance
        if (activeSync != null && activeSync.isConnected) {
            val ok = activeSync.clearRetainedRoomMetaAwaited(roomCode)
            if (ok) return true
        }
        return withContext(Dispatchers.IO) {
            ensureActive()
            var client: MqttAsyncClient? = null
            try {
                client = MqttAsyncClient(NetworkConfig.BROKER_URL, "close_meta_${UUID.randomUUID().toString().take(8)}", MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                val connected = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation {
                            try { client.disconnectForcibly(0L, 50L) } catch (_: Exception) {}
                        }
                        try {
                            val token = client.connect(options, null, object : IMqttActionListener {
                                override fun onSuccess(asyncActionToken: IMqttToken?) {
                                    if (cont.isActive) cont.resume(true) {}
                                }
                                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                            })
                            if (token.isComplete && cont.isActive) {
                                cont.resume(token.exception == null) {}
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                } ?: false
                if (!connected) return@withContext false
                ensureActive()
                val message = MqttMessage(ByteArray(0)).apply {
                    qos = 1
                    isRetained = true
                }
                val published = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation {
                            try { client.disconnectForcibly(0L, 50L) } catch (_: Exception) {}
                        }
                        try {
                            val token = client.publish("bingo/v3/room_meta/$roomCode", message, null, object : IMqttActionListener {
                                override fun onSuccess(asyncActionToken: IMqttToken?) {
                                    if (cont.isActive) cont.resume(true) {}
                                }
                                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                            })
                            if (token.isComplete && cont.isActive) {
                                cont.resume(token.exception == null) {}
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                } ?: false
                published
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                try {
                    client?.disconnectForcibly(0L, 50L)
                } catch (_: Exception) {}
                try {
                    client?.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Retrieves room session from MQTT retained topic.
     * Truly cancellable coroutine that forcibly disconnects and closes client on cancellation without quiesce delay.
     */
    internal suspend fun getRoomMqtt(cleanCode: String, timeoutMs: Long = 1500L): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val testSession = simulatedMqttSession
        if (testSession != null && (testSession.roomCode.isBlank() || testSession.roomCode == cleanCode)) {
            if (testSession.status == "CLOSED" || testSession.isTerminalClosed) {
                terminallyClosedRooms.add(cleanCode)
                recentRoomCache.remove(cleanCode)
                return@withContext null
            }
            return@withContext testSession
        }
        withTimeoutOrNull(timeoutMs) {
            var client: MqttAsyncClient? = null
            try {
                val clientId = "query_meta_${UUID.randomUUID().toString().take(8)}"
                val mClient = MqttAsyncClient(NetworkConfig.BROKER_URL, clientId, MemoryPersistence())
                client = mClient
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = (timeoutMs / 1000).toInt().coerceAtLeast(1)
                    NetworkConfig.applyMqttOptions(this)
                }
                kotlinx.coroutines.suspendCancellableCoroutine<OnlineRoomSession?> { cont ->
                    cont.invokeOnCancellation {
                        try {
                            mClient.disconnectForcibly(0L, 100L)
                        } catch (_: Exception) {}
                        try {
                            mClient.close()
                        } catch (_: Exception) {}
                    }
                    try {
                        mClient.connect(options, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                try {
                                    mClient.subscribe("bingo/v3/room_meta/$cleanCode", 1, null, object : IMqttActionListener {
                                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                                            // Subscribed. If topic is empty, withTimeoutOrNull will complete cleanly.
                                        }
                                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                            if (cont.isActive) cont.resume(null, null)
                                        }
                                    }, IMqttMessageListener { _, msg ->
                                        try {
                                            val raw = String(msg.payload, StandardCharsets.UTF_8).trim()
                                            if (raw.isNotBlank() && raw.startsWith("{")) {
                                                val session = json.decodeFromString<OnlineRoomSession>(raw)
                                                if (session.status != "CLOSED" && !session.isTerminalClosed) {
                                                    if (cont.isActive) cont.resume(session, null)
                                                    return@IMqttMessageListener
                                                } else {
                                                    terminallyClosedRooms.add(cleanCode)
                                                    recentRoomCache.remove(cleanCode)
                                                    if (cont.isActive) cont.resume(null, null)
                                                    return@IMqttMessageListener
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        if (cont.isActive) cont.resume(null, null)
                                    })
                                } catch (_: Exception) {
                                    if (cont.isActive) cont.resume(null, null)
                                }
                            }
                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                if (cont.isActive) cont.resume(null, null)
                            }
                        })
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null, null)
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    if (client?.isConnected == true) {
                        client?.disconnectForcibly(0L, 100L)
                    }
                } catch (_: Exception) {}
                try {
                    client?.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Unified generation-aware readiness and mirror reconciliation for game invitations.
     * Enforces host authority, WAITING status, MQTT discoverability, and HTTP mirror presence.
     * Prevents stale invite callers/workers from recreating closed rooms.
     */
    suspend fun ensureInvitationReadinessDetailed(
        roomCode: String,
        hostPlayer: Player,
        allowCreation: Boolean = true
    ): InvitationReadinessResult = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext InvitationReadinessResult.Rejected("Blank room code")
        if (RoomMetadataApi.enabled) {
            val fetched = readCanonicalRoom(cleanCode)
            if (fetched.isFailure) return@withContext InvitationReadinessResult.TransientUnavailable("Room metadata is unavailable")
            var room = fetched.getOrNull()
            if (room == null && allowCreation) {
                if (!createRoom(cleanCode, hostPlayer)) return@withContext InvitationReadinessResult.TransientUnavailable("Room creation is unavailable")
                room = readCanonicalRoom(cleanCode).getOrNull()
            }
            val ready = room ?: return@withContext InvitationReadinessResult.Rejected("Room does not exist")
            if (ready.status != "WAITING" || ready.isTerminalClosed || ready.hostId != hostPlayer.id)
                return@withContext InvitationReadinessResult.Rejected("Room is closed, already started or owned by another host")
            return@withContext if (publishRoomMetaMqtt(ready)) InvitationReadinessResult.Ready
                else InvitationReadinessResult.TransientUnavailable("MQTT room publication is unavailable")
        }
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode) || isRoomClosedHttpCancellable(cleanCode, 350L)) {
            return@withContext InvitationReadinessResult.Rejected("Room is terminally closed")
        }

        val cached = recentRoomCache[cleanCode]?.session ?: getRoomCachedOnly(cleanCode)
        if (cached != null) {
            if (cached.status == "CLOSED" || cached.isTerminalClosed) {
                terminallyClosedRooms.add(cleanCode)
                recentRoomCache.remove(cleanCode)
                return@withContext InvitationReadinessResult.Rejected("Room is terminally closed")
            }
            if (cached.status == "PLAYING") {
                return@withContext InvitationReadinessResult.Rejected("Room is already in PLAYING status")
            }
        }

        val httpFetchResult = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1500L)
        val httpSession = httpFetchResult.getOrNull()
        val isHttpUnavailable = httpFetchResult.exceptionOrNull() != null

        if (httpSession != null) {
            if (httpSession.status == "CLOSED" || httpSession.isTerminalClosed) {
                terminallyClosedRooms.add(cleanCode)
                recentRoomCache.remove(cleanCode)
                return@withContext InvitationReadinessResult.Rejected("Room is terminally closed on server")
            }
            if (httpSession.status == "PLAYING") {
                recentRoomCache[cleanCode] = CachedRoom(httpSession, System.currentTimeMillis())
                return@withContext InvitationReadinessResult.Rejected("Room is already in PLAYING status")
            }
        }

        val mqttSession = if (cached == null && httpSession == null) getRoomMqtt(cleanCode, 1500L) else null
        if (mqttSession != null) {
            if (mqttSession.status == "CLOSED" || mqttSession.isTerminalClosed) {
                terminallyClosedRooms.add(cleanCode)
                recentRoomCache.remove(cleanCode)
                return@withContext InvitationReadinessResult.Rejected("Room is terminally closed on MQTT")
            }
            if (mqttSession.status == "PLAYING") {
                recentRoomCache[cleanCode] = CachedRoom(mqttSession, System.currentTimeMillis())
                return@withContext InvitationReadinessResult.Rejected("Room is already in PLAYING status")
            }
        }

        val session = if (httpSession != null && (cached == null || httpSession.revision >= cached.revision)) {
            httpSession
        } else {
            cached ?: httpSession ?: mqttSession
        }

        if (session != null) {
            if (session.status != "WAITING" || session.isTerminalClosed) {
                return@withContext InvitationReadinessResult.Rejected("Room status is ${session.status}")
            }
            val isHost = session.hostId == hostPlayer.id ||
                session.players.any { it.isHost && (it.id == hostPlayer.id || it.username.equals(hostPlayer.username, ignoreCase = true)) }
            if (!isHost) {
                return@withContext InvitationReadinessResult.Rejected("Host authority mismatch")
            }

            // Dual-channel verification: ensure both HTTP mirror exists and MQTT is published
            var httpMirrorOk = httpSession != null
            if (!httpMirrorOk && !isHttpUnavailable) {
                val statusTag = getRoomStatusHttp(cleanCode)
                if (statusTag != null) {
                    val parts = statusTag.split(":")
                    val st = parts.getOrNull(0) ?: ""
                    val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    if (st.startsWith("CLOSED") || st.startsWith("PLAYING") || sRev > session.revision) {
                        return@withContext InvitationReadinessResult.Rejected("Room transitioned concurrently to $statusTag")
                    }
                }
                putRoomRevisionHttp(cleanCode, session)
                val jsonStr = json.encodeToString(session)
                val b64 = encodeBase64Url(jsonStr)
                val encVal = URLEncoder.encode(b64, "UTF-8")
                val request = Request.Builder()
                    .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                    .post("".toRequestBody(null))
                    .header("Content-Length", "0")
                    .build()
                val call = client.newCall(request)
                httpMirrorOk = try {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                val ok = response.isSuccessful
                                response.close()
                                if (cont.isActive) cont.resume(ok) {}
                            }
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
                if (httpMirrorOk) {
                    setRoomStatusHttp(cleanCode, session.status, session.currentSeed, session.revision)
                }
            }

            val mqttOk = publishRoomMetaMqtt(session)
            recentRoomCache[cleanCode] = CachedRoom(session, System.currentTimeMillis())

            if (mqttOk && httpMirrorOk) {
                InvitationReadinessResult.Ready
            } else if (mqttOk) {
                queueMirrorReconciliationRetry(cleanCode, session)
                InvitationReadinessResult.MirrorPending(session)
            } else {
                InvitationReadinessResult.TransientUnavailable("MQTT publication failed")
            }
        } else {
            if (!allowCreation) {
                if (isHttpUnavailable) {
                    InvitationReadinessResult.TransientUnavailable("HTTP unavailable and no existing room found")
                } else {
                    InvitationReadinessResult.Rejected("Room does not exist or was closed")
                }
            } else {
                val created = createRoom(cleanCode, hostPlayer)
                if (created) {
                    val fresh = getRoomCachedOnly(cleanCode)
                    val httpExists = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1000L).getOrNull() != null
                    if (fresh != null && httpExists) {
                        InvitationReadinessResult.Ready
                    } else {
                        val fallback = fresh ?: OnlineRoomSession(roomCode = cleanCode, hostId = hostPlayer.id, hostUsername = hostPlayer.username, hostDisplayName = hostPlayer.displayName)
                        queueMirrorReconciliationRetry(cleanCode, fallback)
                        InvitationReadinessResult.MirrorPending(fallback)
                    }
                } else {
                    val isClosed = isRoomClosedHttpCancellable(cleanCode, 350L) || terminallyClosedRooms.contains(cleanCode)
                    if (isClosed) {
                        InvitationReadinessResult.Rejected("Room is closed")
                    } else {
                        InvitationReadinessResult.TransientUnavailable("Failed to create room: transport error")
                    }
                }
            }
        }
    }

    suspend fun ensureInvitationReadiness(
        roomCode: String,
        hostPlayer: Player
    ): Boolean = withContext(Dispatchers.IO) {
        ensureInvitationReadinessDetailed(roomCode, hostPlayer) is InvitationReadinessResult.Ready
    }

    internal fun queueMirrorReconciliationRetry(cleanCode: String, session: OnlineRoomSession) {
        if (RoomMetadataApi.enabled) return // Canonical records are committed atomically; legacy mirrors are not authoritative.
        scope.launch {
            var delayMs = 500L
            for (attempt in 1..6) {
                delay(delayMs)
                currentCoroutineContext().ensureActive()
                if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode) || isRoomClosedHttpCancellable(cleanCode, 350L)) break
                val current = getRoomCachedOnly(cleanCode) ?: session
                if (current.status != "WAITING" || current.isTerminalClosed) break
                val currentTag = getRoomStatusHttp(cleanCode)
                if (currentTag != null) {
                    val parts = currentTag.split(":")
                    val st = parts.getOrNull(0) ?: ""
                    val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    if (st.startsWith("CLOSED") || st.startsWith("PLAYING") || sRev > current.revision) break
                }
                try {
                    putRoomRevisionHttp(cleanCode, current)
                    val jsonStr = json.encodeToString(current)
                    val b64 = encodeBase64Url(jsonStr)
                    val encVal = URLEncoder.encode(b64, "UTF-8")
                    val request = Request.Builder()
                        .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                        .post("".toRequestBody(null))
                        .header("Content-Length", "0")
                        .build()
                    val call = client.newCall(request)
                    val ok = try {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            cont.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : okhttp3.Callback {
                                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                    val ok = response.isSuccessful
                                    response.close()
                                    if (cont.isActive) cont.resume(ok) {}
                                }
                            })
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { false }
                    if (ok) {
                        setRoomStatusHttp(cleanCode, current.status, current.currentSeed, current.revision)
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                delayMs = minOf(delayMs * 2, 5000L)
            }
        }
    }

    /**
     * Registers a new room created by a host in cloud storage and MQTT.
     * Synchronously confirms discoverable MQTT publication before cloud return.
     */
    suspend fun createRoom(
        roomCode: String,
        hostPlayer: Player,
        boardSize: Int = 5
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext false
        if (RoomMetadataApi.enabled) {
            val host = sanitizePlayer(hostPlayer.copy(isHost = true))
            val initial = OnlineRoomSession(cleanCode, host.id, host.username, host.displayName,
                boardSize = boardSize, players = listOf(host))
            val result = RoomMetadataApi.coordinator().mutate(cleanCode, initial = initial) { current ->
                if (current.hostId != host.id || current.status != "WAITING") null
                else current.copy(lastHeartbeat = System.currentTimeMillis(),
                    players = if (current.players.any { it.id == host.id }) current.players else current.players + host)
            }
            if (result !is MutationResult.Applied) return@withContext false
            rememberCanonicalRoom(result.session)
            return@withContext publishRoomMetaMqtt(result.session)
        }
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode) || isRoomClosedHttpCancellable(cleanCode, 350L)) {
            return@withContext false
        }
        val initialStatusTag = getRoomStatusHttp(cleanCode)
        if (initialStatusTag != null) {
            val parts = initialStatusTag.split(":")
            val st = parts.getOrNull(0) ?: ""
            if (st == "CLOSED" || st == "PLAYING") {
                return@withContext false
            }
        }

        try {
            val existing = getRoomCachedOnly(cleanCode) ?: fetchRoomDirectHttpDetailedCancellable(cleanCode, 1500L).getOrNull() ?: getRoomMqtt(cleanCode, 1500L)
            if (existing != null) {
                if (existing.status == "CLOSED" || existing.isTerminalClosed) return@withContext false
                if (existing.status == "PLAYING") {
                    recentRoomCache[cleanCode] = CachedRoom(existing, System.currentTimeMillis())
                    return@withContext true
                }
                if (existing.status == "WAITING") {
                    val preReconcileTag = getRoomStatusHttp(cleanCode)
                    if (preReconcileTag != null) {
                        val parts = preReconcileTag.split(":")
                        val st = parts.getOrNull(0) ?: ""
                        val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        if (st.startsWith("CLOSED") || st.startsWith("PLAYING") || sRev > existing.revision) {
                            return@withContext false
                        }
                    }
                    // Reconcile existing room without replacing it with host-only WAITING snapshot
                    val isHostAlready = existing.players.any { it.id == hostPlayer.id || (it.username.isNotBlank() && it.username.equals(hostPlayer.username, ignoreCase = true)) }
                    val updatedSession = if (isHostAlready) {
                        existing.copy(lastHeartbeat = System.currentTimeMillis())
                    } else {
                        existing.copy(players = existing.players + sanitizePlayer(hostPlayer.copy(isHost = true)), lastHeartbeat = System.currentTimeMillis())
                    }
                    recentRoomCache[cleanCode] = CachedRoom(updatedSession, System.currentTimeMillis())
                    val mqttOk = publishRoomMetaMqtt(updatedSession)
                    putRoomRevisionHttp(cleanCode, updatedSession)
                    val jsonStr = json.encodeToString(updatedSession)
                    val b64 = encodeBase64Url(jsonStr)
                    val encVal = URLEncoder.encode(b64, "UTF-8")
                    val request = Request.Builder()
                        .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                        .post("".toRequestBody(null))
                        .header("Content-Length", "0")
                        .build()
                    val call = client.newCall(request)
                    val httpOk = try {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            cont.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : okhttp3.Callback {
                                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                    val ok = response.isSuccessful
                                    response.close()
                                    if (cont.isActive) cont.resume(ok) {}
                                }
                            })
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { false }
                    if (httpOk) {
                        setRoomStatusHttp(cleanCode, updatedSession.status, updatedSession.currentSeed, updatedSession.revision)
                    } else {
                        queueMirrorReconciliationRetry(cleanCode, updatedSession)
                    }
                    return@withContext mqttOk
                }
            }

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
                players = listOf(safeHost),
                revision = 1L
            )

            terminallyClosedRooms.remove(cleanCode)
            pendingClosures.remove(cleanCode)

            // 1. Immediately publish retained MQTT and await completion so joiners can discover it
            val mqttOk = publishRoomMetaMqtt(session)

            // 2. Persist to KeyValue cloud storage with retry
            val preCreateTag = getRoomStatusHttp(cleanCode)
            if (preCreateTag != null) {
                val parts = preCreateTag.split(":")
                val st = parts.getOrNull(0) ?: ""
                val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                if (st == "CLOSED" || st == "PLAYING" || sRev >= session.revision) {
                    return@withContext false
                }
            }

            putRoomRevisionHttp(cleanCode, session)
            val jsonStr = json.encodeToString(session)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")

            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()

            var success = false
            for (attempt in 1..2) {
                ensureActive()
                try {
                    val call = client.newCall(request)
                    success = suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                val ok = response.isSuccessful
                                response.close()
                                if (cont.isActive) cont.resume(ok) {}
                            }
                        })
                    }
                    if (success) {
                        setRoomStatusHttp(cleanCode, session.status, session.currentSeed, session.revision)
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            if (success) {
                recentRoomCache[cleanCode] = CachedRoom(session, System.currentTimeMillis())
            } else if (mqttOk) {
                recentRoomCache[cleanCode] = CachedRoom(session, System.currentTimeMillis())
                queueMirrorReconciliationRetry(cleanCode, session)
            }
            Log.i(TAG, "Created room $cleanCode in cloud registry: httpSuccess=$success, mqttOk=$mqttOk")
            mqttOk
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
            val canonicalLookup = RoomMetadataApi.enabled
            var session = if (canonicalLookup) {
                val fetched = readCanonicalRoom(cleanCode)
                if (fetched.isFailure) return@withContext RoomJoinResult.Error(
                    "The room service is temporarily unavailable. Please try again.")
                fetched.getOrNull()?.takeIf { it.status != "CLOSED" && !it.isTerminalClosed }
            } else getRoom(cleanCode)
            if (session == null && !canonicalLookup) {
                // Network jitter or host just created room: retry up to 3 times
                for (retry in 1..3) {
                    kotlinx.coroutines.delay(400L)
                    session = getRoom(cleanCode)
                    if (session != null) break
                }
            }

            if (session == null) {
                return@withContext RoomJoinResult.NotFound("No active room found for code $cleanCode.")
            }

            if (session.status == "CLOSED" || session.isTerminalClosed || terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) {
                return@withContext RoomJoinResult.NotFound("Room $cleanCode has been closed by the host.")
            }

            val existingInSession = session.players.find {
                it.id == joiner.id || (it.username.isNotBlank() && it.username.equals(joiner.username, ignoreCase = true))
            }

            if (session.status == "PLAYING") {
                if (existingInSession == null) {
                    return@withContext RoomJoinResult.AlreadyStarted("Match in room $cleanCode has already started.")
                }
                // Existing participant rejoining active match: admit immediately
                val safeJoiner = sanitizePlayer(LobbyLifecycleEngine.onPlayerJoinSession(joiner.copy(isHost = false), existingInSession))
                scope.launch { persistJoinBackup(cleanCode, safeJoiner) }
                return@withContext RoomJoinResult.Success(session)
            }

            val now = System.currentTimeMillis()
            // If host has been silent > 10 minutes, the room is considered expired
            if (session.status != "PLAYING" && (now - session.lastHeartbeat) > 600_000L) {
                return@withContext RoomJoinResult.Expired("Room $cleanCode has expired or the host has left.")
            }

            val safeJoiner = sanitizePlayer(LobbyLifecycleEngine.onPlayerJoinSession(joiner.copy(isHost = false), existingInSession))
            val isAlreadyInRoom = existingInSession != null

            if (!isAlreadyInRoom && session.players.size >= 8) {
                return@withContext RoomJoinResult.AlreadyFull("Room $cleanCode is full (max 8 players).")
            }

            // Centralized atomic join mutation: verifies room is still WAITING and not CLOSED before admitting joiner
            val mutation = mutateCloudSession(cleanCode) { current ->
                if (current.status != "WAITING" || current.isTerminalClosed) return@mutateCloudSession null
                val isAlready = current.players.any { it.id == safeJoiner.id || (it.username.isNotBlank() && it.username.equals(safeJoiner.username, ignoreCase = true)) }
                val newPlayers = if (isAlready) {
                    current.players.map { p ->
                        if (p.id == safeJoiner.id || (p.username.isNotBlank() && p.username.equals(safeJoiner.username, ignoreCase = true))) {
                            safeJoiner
                        } else if (p.isHost) {
                            p.copy(lastSeenTimestamp = now)
                        } else p
                    }
                } else {
                    current.players.map { p ->
                        if (p.isHost) p.copy(lastSeenTimestamp = now) else p
                    } + safeJoiner
                }
                current.copy(players = newPlayers, lastHeartbeat = now)
            }

            when (mutation) {
                is MutationResult.Applied -> {
                    return@withContext RoomJoinResult.Success(mutation.session)
                }
                is MutationResult.Unavailable -> {
                    // MQTT-backed admission when HTTP mirror is unavailable
                    val newPlayers = if (isAlreadyInRoom) {
                        session.players.map { p ->
                            if (p.id == safeJoiner.id || (p.username.isNotBlank() && p.username.equals(safeJoiner.username, ignoreCase = true))) {
                                safeJoiner
                            } else p
                        }
                    } else {
                        session.players + safeJoiner
                    }
                    val fallbackAdmitted = session.copy(
                        players = newPlayers,
                        lastHeartbeat = now,
                        revision = session.revision + 1L
                    )
                    recentRoomCache[cleanCode] = CachedRoom(fallbackAdmitted, now)
                    publishRoomMetaMqtt(fallbackAdmitted)
                    return@withContext RoomJoinResult.Success(fallbackAdmitted)
                }
                is MutationResult.Rejected -> {
                    val currentDirect = getRoomCachedOnly(cleanCode)
                    if (currentDirect?.status == "CLOSED" || terminallyClosedRooms.contains(cleanCode)) {
                        return@withContext RoomJoinResult.NotFound("Room $cleanCode is closed.")
                    }
                    if (currentDirect?.status == "PLAYING") {
                        return@withContext RoomJoinResult.AlreadyStarted("Game has already started in room $cleanCode.")
                    }
                    return@withContext RoomJoinResult.Error("Could not join room $cleanCode: ${mutation.reason}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating room $cleanCode: ${e.message}", e)
            return@withContext RoomJoinResult.Error("Network error while validating room $cleanCode. Please check your connection.")
        }
    }

    internal suspend fun fetchRoomDirectHttpDetailedCancellable(cleanCode: String, timeoutMs: Long = 1500L): Result<OnlineRoomSession?> = withContext(Dispatchers.IO) {
        if (cleanCode.isBlank()) return@withContext Result.success(null)
        if (RoomMetadataApi.enabled) return@withContext readCanonicalRoom(cleanCode)
        val testEx = simulatedHttpException
        if (testEx != null) {
            return@withContext Result.failure(testEx)
        }
        try {
            val timedResult = withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder()
                    .url("$BASE_URL/GetValue/$API_KEY/room_$cleanCode")
                    .get()
                    .build()
                val call = client.newCall(request)
                try {
                    kotlinx.coroutines.suspendCancellableCoroutine<Result<OnlineRoomSession?>> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(Result.failure(e)) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                response.use { resp ->
                                    val code = resp.code
                                    val raw = resp.body?.string()?.trim()?.removeSurrounding("\"")
                                    if (code in 500..599) {
                                        if (cont.isActive) cont.resume(Result.failure(java.io.IOException("HTTP $code server error"))) {}
                                        return
                                    }
                                    if (code == 404 || raw.isNullOrBlank() || raw == "null" || raw.contains("error", ignoreCase = true)) {
                                        if (cont.isActive) cont.resume(Result.success(null)) {}
                                        return
                                    }
                                    val jsonStr = try { decodeBase64Url(raw) } catch (_: Exception) { raw }
                                    if (jsonStr.startsWith("{")) {
                                        try {
                                            val session = json.decodeFromString<OnlineRoomSession>(jsonStr)
                                            if (cont.isActive) cont.resume(Result.success(session)) {}
                                        } catch (e: Exception) {
                                            if (cont.isActive) cont.resume(Result.failure(e)) {}
                                        }
                                    } else {
                                        if (cont.isActive) cont.resume(Result.success(null)) {}
                                    }
                                }
                            }
                        })
                    }
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                }
            }
            val baseRes = timedResult ?: Result.failure(java.io.IOException("HTTP read timed out after ${timeoutMs}ms"))
            val session = baseRes.getOrNull()
            val statusTag = getRoomStatusHttp(cleanCode)
            if (statusTag != null) {
                val sRev = statusTag.split(":").getOrNull(2)?.toLongOrNull() ?: 0L
                if (sRev > (session?.revision ?: 0L)) {
                    val newer = fetchRoomRevisionHttp(cleanCode, sRev, timeoutMs = minOf(timeoutMs, 1000L))
                    if (newer != null) {
                        return@withContext Result.success(newer)
                    }
                }
            }
            baseRes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun fetchRoomDirectHttpDetailed(cleanCode: String): Result<OnlineRoomSession?> {
        return runBlocking { fetchRoomDirectHttpDetailedCancellable(cleanCode, 5000L) }
    }

    internal fun fetchRoomDirectHttp(cleanCode: String): OnlineRoomSession? {
        return fetchRoomDirectHttpDetailed(cleanCode).getOrNull()
    }

    internal fun isRoomClosedHttp(cleanCode: String): Boolean {
        return runBlocking { isRoomClosedHttpCancellable(cleanCode, 350L) }
    }

    internal suspend fun getRoomStatusHttp(cleanCode: String, timeoutMs: Long = 500L): String? = withContext(Dispatchers.IO) {
        if (RoomMetadataApi.enabled) return@withContext readCanonicalRoom(cleanCode).getOrNull()?.let {
            "${it.status}:${it.currentSeed}:${it.revision}"
        }
        try {
            val remote = withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder()
                    .url("$BASE_URL/GetValue/$API_KEY/room_status_$cleanCode")
                    .get()
                    .build()
                val call = client.newCall(request)
                try {
                    kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(null) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                response.use { resp ->
                                    val raw = resp.body?.string()?.trim()?.removeSurrounding("\"")
                                    if (cont.isActive) cont.resume(raw?.takeIf { it.isNotBlank() && it != "null" }) {}
                                }
                            }
                        })
                    }
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                }
            }
            if (remote != null) {
                roomStatusMap[cleanCode] = remote
                return@withContext remote
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        roomStatusMap[cleanCode]
    }

    internal suspend fun setRoomStatusHttp(cleanCode: String, status: String, seed: Long, revision: Long) = withContext(Dispatchers.IO) {
        if (RoomMetadataApi.enabled) return@withContext
        ensureActive()
        val statusVal = "$status:$seed:$revision"
        roomStatusMap[cleanCode] = statusVal
        try {
            val encVal = URLEncoder.encode(statusVal, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_status_$cleanCode?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            val call = client.newCall(request)
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    internal suspend fun putRoomRevisionHttp(cleanCode: String, session: OnlineRoomSession) = withContext(Dispatchers.IO) {
        if (RoomMetadataApi.enabled) return@withContext
        val rev = session.revision
        if (rev <= 0L) return@withContext
        try {
            val jsonStr = json.encodeToString(session)
            val b64 = encodeBase64Url(jsonStr)
            val encVal = URLEncoder.encode(b64, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/UpdateValue/$API_KEY/room_rev_${cleanCode}_$rev?value=$encVal")
                .post("".toRequestBody(null))
                .header("Content-Length", "0")
                .build()
            val call = client.newCall(request)
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    internal suspend fun fetchRoomRevisionHttp(cleanCode: String, rev: Long, timeoutMs: Long = 1000L): OnlineRoomSession? = withContext(Dispatchers.IO) {
        if (cleanCode.isBlank() || rev <= 0L) return@withContext null
        try {
            withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder()
                    .url("$BASE_URL/GetValue/$API_KEY/room_rev_${cleanCode}_$rev")
                    .get()
                    .build()
                val call = client.newCall(request)
                suspendCancellableCoroutine<OnlineRoomSession?> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            if (cont.isActive) cont.resume(null) {}
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            response.use { resp ->
                                val raw = resp.body?.string()?.trim()?.removeSurrounding("\"")
                                if (resp.isSuccessful && !raw.isNullOrBlank() && raw != "null") {
                                    val jsonStr = try { decodeBase64Url(raw) } catch (_: Exception) { raw }
                                    if (jsonStr.startsWith("{")) {
                                        try {
                                            val s = json.decodeFromString<OnlineRoomSession>(jsonStr)
                                            if (cont.isActive) cont.resume(s) {}
                                            return
                                        } catch (_: Exception) {}
                                    }
                                }
                                if (cont.isActive) cont.resume(null) {}
                            }
                        }
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
    }

    internal suspend fun isRoomClosedHttpCancellable(cleanCode: String, timeoutMs: Long = 350L): Boolean = withContext(Dispatchers.IO) {
        if (RoomMetadataApi.enabled) return@withContext readCanonicalRoom(cleanCode).getOrNull()?.let {
            it.status == "CLOSED" || it.isTerminalClosed
        } ?: false
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) return@withContext true
        try {
            withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder()
                    .url("$BASE_URL/GetValue/$API_KEY/room_closed_$cleanCode")
                    .get()
                    .build()
                val call = client.newCall(request)
                try {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                response.use { resp ->
                                    val raw = resp.body?.string()?.trim()?.removeSurrounding("\"")
                                    if (cont.isActive) cont.resume(raw == "true") {}
                                }
                            }
                        })
                    }
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                }
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private val metadataMutation = Mutex()

    /**
     * Centralized cloud room mutation with revision awareness and terminal closure protection.
     * Local mutations are serialized; remote revision checks are best effort because the
     * current key/value service does not provide compare-and-set commits.
     */
    internal suspend fun mutateCloudSession(
        cleanCode: String,
        allowResetToWaiting: Boolean = false,
        allowClosing: Boolean = false,
        expectedSeed: Long? = null,
        transform: (current: OnlineRoomSession) -> OnlineRoomSession?
    ): MutationResult = withContext(Dispatchers.IO) {
        if (cleanCode.isBlank()) return@withContext MutationResult.Rejected("Blank room code")
        if (RoomMetadataApi.enabled) {
            val result = RoomMetadataApi.coordinator().mutate(cleanCode, expectedSeed = expectedSeed,
                allowReset = allowResetToWaiting, allowAlreadyClosed = allowClosing, transform = transform)
            if (result is MutationResult.Unavailable && allowClosing) queueDurableClosureRetry(cleanCode, expectedSeed)
            return@withContext publishCanonicalMutation(cleanCode, result)
        }
        if ((terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) && !allowClosing) {
            recentRoomCache.remove(cleanCode)
            return@withContext MutationResult.Rejected("Room is terminally closed")
        }
        metadataMutation.withLock {
            try {
                if (isRoomClosedHttpCancellable(cleanCode, 350L) && !allowClosing) {
                    terminallyClosedRooms.add(cleanCode)
                    recentRoomCache.remove(cleanCode)
                    return@withContext MutationResult.Rejected("Room is closed on server")
                }
                val cached = recentRoomCache[cleanCode]?.session
                val httpFetchResult = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1500L)
                val httpSession = httpFetchResult.getOrNull()
                val transportError = httpFetchResult.exceptionOrNull()

                if (transportError != null && cached == null) {
                    return@withContext MutationResult.Unavailable(transportError)
                }

                if (httpSession == null && cached == null && transportError == null) {
                    return@withContext MutationResult.Rejected("Room not found")
                }

                var current = if (cached != null && (httpSession == null || cached.revision > httpSession.revision)) {
                    cached
                } else {
                    httpSession!!
                }

                // Reject an obsolete generation before queuing any outage fallback.
                if (expectedSeed != null && current.currentSeed != 0L && current.currentSeed != expectedSeed) {
                    return@withContext MutationResult.Rejected("Seed mismatch: current=${current.currentSeed}, expected=$expectedSeed")
                }

                if (allowClosing) {
                    val hasFreshHttp = (httpSession != null)
                    val mqttLive = if (!hasFreshHttp) getRoomMqtt(cleanCode, 1500L) else null
                    if (mqttLive != null) {
                        if (expectedSeed != null && mqttLive.currentSeed != 0L && mqttLive.currentSeed != expectedSeed) {
                            return@withContext MutationResult.Rejected("Active MQTT seed ${mqttLive.currentSeed} != expected $expectedSeed")
                        }
                        if (mqttLive.revision > current.revision) {
                            current = mqttLive
                        }
                    } else if (!hasFreshHttp) {
                        queueDurableClosureRetry(cleanCode, expectedSeed)
                        return@withContext MutationResult.Unavailable(java.io.IOException("Cannot close room $cleanCode: no fresh transport evidence available from HTTP or MQTT"))
                    }
                }

                if ((current.status == "CLOSED" || current.isTerminalClosed || terminallyClosedRooms.contains(cleanCode)) && !allowClosing) {
                    recentRoomCache.remove(cleanCode)
                    return@withContext MutationResult.Rejected("Room is closed")
                }

                if (expectedSeed != null && current.currentSeed != expectedSeed) {
                    return@withContext MutationResult.Rejected("Seed mismatch: current=${current.currentSeed}, expected=$expectedSeed")
                }

                val transformed = transform(current) ?: return@withContext MutationResult.Rejected("Transform returned null")

                if ((current.status == "CLOSED" || current.isTerminalClosed) && transformed.status != "CLOSED" && !allowClosing) {
                    Log.w(TAG, "Prevented stale write reopening closed room $cleanCode")
                    return@withContext MutationResult.Rejected("Cannot reopen closed room")
                }

                if (current.status == "PLAYING" && transformed.status == "WAITING" && !allowResetToWaiting) {
                    Log.w(TAG, "Prevented status regression from PLAYING to WAITING for $cleanCode")
                    return@withContext MutationResult.Rejected("Cannot downgrade status from PLAYING to WAITING")
                }

                val baseStatus = current.status
                val baseSeed = current.currentSeed
                val baseRevision = current.revision
                val isClosing = transformed.status == "CLOSED" || transformed.isTerminalClosed
                val newRevision = maxOf(current.revision, 0L) + 1L
                val effectiveNewRevision = if (transformed.revision in 1 until current.revision) {
                    transformed.revision + 1L
                } else {
                    newRevision
                }

                if (transformed.revision in 1 until current.revision) {
                    Log.w(TAG, "Stale revision write on $cleanCode: base ${transformed.revision} < current ${current.revision}")
                    return@withContext MutationResult.Rejected("Concurrent newer revision committed")
                }

                val serverStatusPre = getRoomStatusHttp(cleanCode)
                if (serverStatusPre != null) {
                    val parts = serverStatusPre.split(":")
                    val sStatus = parts.getOrNull(0) ?: ""
                    val sSeed = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    if (sStatus == "CLOSED" && !isClosing) {
                        terminallyClosedRooms.add(cleanCode)
                        recentRoomCache.remove(cleanCode)
                        return@withContext MutationResult.Rejected("Room was terminally closed concurrently")
                    }
                    if (baseStatus == "WAITING" && transformed.status == "WAITING" && sStatus == "PLAYING" && !allowResetToWaiting) {
                        val baseSession = (if (sRev > 0L) fetchRoomRevisionHttp(cleanCode, sRev) else null)
                            ?: fetchRoomDirectHttpDetailedCancellable(cleanCode, 1000L).getOrNull() ?: current
                        val repairPlaying = if (baseSession.status == "PLAYING") baseSession else baseSession.copy(
                            status = "PLAYING",
                            currentSeed = if (sSeed != 0L) sSeed else baseSession.currentSeed,
                            revision = maxOf(sRev, baseSession.revision)
                        )
                        recentRoomCache[cleanCode] = CachedRoom(repairPlaying, System.currentTimeMillis())
                        return@withContext MutationResult.Rejected("Room transitioned to PLAYING concurrently")
                    }
                    if (sRev >= effectiveNewRevision) {
                        Log.w(TAG, "Concurrent write on $cleanCode committed revision $sRev >= $effectiveNewRevision")
                        return@withContext MutationResult.Rejected("Concurrent newer revision committed")
                    }
                    if (expectedSeed != null && sSeed != 0L && sSeed != expectedSeed) {
                        return@withContext MutationResult.Rejected("Seed mismatch: storage=$sSeed, expected=$expectedSeed")
                    }
                }

                val freshPreWrite = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1000L).getOrNull()
                if (freshPreWrite != null) {
                    if ((freshPreWrite.status == "CLOSED" || freshPreWrite.isTerminalClosed) && !isClosing) {
                        terminallyClosedRooms.add(cleanCode)
                        recentRoomCache.remove(cleanCode)
                        return@withContext MutationResult.Rejected("Room was terminally closed concurrently")
                    }
                    if (baseStatus == "WAITING" && transformed.status == "WAITING" && freshPreWrite.status == "PLAYING" && !allowResetToWaiting) {
                        recentRoomCache[cleanCode] = CachedRoom(freshPreWrite, System.currentTimeMillis())
                        return@withContext MutationResult.Rejected("Room transitioned to PLAYING concurrently")
                    }
                    if (freshPreWrite.revision >= newRevision) {
                        Log.w(TAG, "Concurrent write on $cleanCode committed revision ${freshPreWrite.revision} >= $newRevision")
                        return@withContext MutationResult.Rejected("Concurrent newer revision committed")
                    }
                    if (expectedSeed != null && freshPreWrite.currentSeed != 0L && freshPreWrite.currentSeed != expectedSeed) {
                        return@withContext MutationResult.Rejected("Seed mismatch: storage=${freshPreWrite.currentSeed}, expected=$expectedSeed")
                    }
                }

                val finalSession = transformed.copy(
                    revision = newRevision,
                    isTerminalClosed = isClosing
                )

                putRoomRevisionHttp(cleanCode, finalSession)
                val jsonStr = json.encodeToString(finalSession)
                val b64 = encodeBase64Url(jsonStr)
                val encVal = URLEncoder.encode(b64, "UTF-8")
                val request = Request.Builder()
                    .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=$encVal")
                    .post("".toRequestBody(null))
                    .header("Content-Length", "0")
                    .build()

                var writeSuccess = false
                try {
                    val call = client.newCall(request)
                    writeSuccess = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                val ok = response.isSuccessful
                                response.close()
                                if (cont.isActive) cont.resume(ok) {}
                            }
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isClosing) {
                        queueDurableClosureRetry(cleanCode, expectedSeed)
                    }
                    return@withContext MutationResult.Unavailable(e)
                }

                if (!writeSuccess) {
                    if (isClosing) {
                        queueDurableClosureRetry(cleanCode, expectedSeed)
                    }
                    return@withContext MutationResult.Unavailable(java.io.IOException("HTTP write failed"))
                }

                if (isClosing) {
                    terminallyClosedRooms.add(cleanCode)
                    setRoomStatusHttp(cleanCode, "CLOSED", expectedSeed ?: 0L, newRevision)
                    var tombstoneSuccess = false
                    try {
                        ensureActive()
                        val tombstoneReq = Request.Builder()
                            .url("$BASE_URL/UpdateValue/$API_KEY/room_closed_$cleanCode?value=true")
                            .post("".toRequestBody(null))
                            .header("Content-Length", "0")
                            .build()
                        val call = client.newCall(tombstoneReq)
                        tombstoneSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                            cont.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : okhttp3.Callback {
                                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                    if (cont.isActive) cont.resume(false) {}
                                }
                                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                    val ok = response.isSuccessful
                                    response.close()
                                    if (cont.isActive) cont.resume(ok) {}
                                }
                            })
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    if (!tombstoneSuccess) {
                        queueDurableClosureRetry(cleanCode, expectedSeed)
                    } else {
                        pendingClosures.remove(cleanCode)
                        persistPendingClosures()
                    }
                    clearRoomMetaMqtt(cleanCode)
                    recentRoomCache.remove(cleanCode)
                } else {
                    val isClosedConcurrently = terminallyClosedRooms.contains(cleanCode) ||
                        hasPendingClosure(cleanCode) ||
                        isRoomClosedHttpCancellable(cleanCode, 350L)
                    if (isClosedConcurrently) {
                        terminallyClosedRooms.add(cleanCode)
                        recentRoomCache.remove(cleanCode)
                        setRoomStatusHttp(cleanCode, "CLOSED", expectedSeed ?: 0L, newRevision + 1L)
                        val repairClosed = current.copy(status = "CLOSED", isTerminalClosed = true, revision = newRevision + 1L)
                        val closedJson = json.encodeToString(repairClosed)
                        val b64 = encodeBase64Url(closedJson)
                        val repairReq = Request.Builder()
                            .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=${URLEncoder.encode(b64, "UTF-8")}")
                            .post("".toRequestBody(null)).header("Content-Length", "0").build()
                        val call = client.newCall(repairReq)
                        try {
                            suspendCancellableCoroutine<Unit> { cont ->
                                cont.invokeOnCancellation { call.cancel() }
                                call.enqueue(object : okhttp3.Callback {
                                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                        if (cont.isActive) cont.resume(Unit) {}
                                    }
                                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                        response.close()
                                        if (cont.isActive) cont.resume(Unit) {}
                                    }
                                })
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                        return@withContext MutationResult.Rejected("Room was terminally closed concurrently")
                    }

                    val serverStatusTag = getRoomStatusHttp(cleanCode)
                    if (serverStatusTag != null) {
                        val parts = serverStatusTag.split(":")
                        val sStatus = parts.getOrNull(0) ?: ""
                        val sSeed = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val sRev = parts.getOrNull(2)?.toLongOrNull() ?: 0L

                        if (sStatus == "CLOSED") {
                            terminallyClosedRooms.add(cleanCode)
                            recentRoomCache.remove(cleanCode)
                            return@withContext MutationResult.Rejected("Room was terminally closed concurrently")
                        }
                        if ((baseStatus == "WAITING" && transformed.status == "WAITING" &&
                            sStatus == "PLAYING" && !allowResetToWaiting) ||
                            sRev > newRevision) {
                            Log.w(TAG, "Prevented stale write on $cleanCode (sStatus=$sStatus, sRev=$sRev vs newRev=$newRevision); restoring authoritative session")
                            val authSession = (if (sRev > 0L) fetchRoomRevisionHttp(cleanCode, sRev) else null)
                                ?: getRoomMqtt(cleanCode, 1500L)
                                ?: recentRoomCache[cleanCode]?.session?.takeIf { it.revision >= sRev || it.status == "PLAYING" }
                                ?: fetchRoomDirectHttpDetailedCancellable(cleanCode, 1000L).getOrNull()

                            val resolvedAuth = authSession ?: current.copy(
                                status = if (sStatus == "PLAYING") "PLAYING" else current.status,
                                currentSeed = if (sSeed != 0L) sSeed else current.currentSeed,
                                revision = maxOf(sRev, newRevision) + 1L
                            )
                            val repairRev = maxOf(sRev, resolvedAuth.revision, newRevision + 1L)
                            val completeRepaired = resolvedAuth.copy(revision = repairRev)

                            val playingJson = json.encodeToString(completeRepaired)
                            val b64 = encodeBase64Url(playingJson)
                            val repairReq = Request.Builder()
                                .url("$BASE_URL/UpdateValue/$API_KEY/room_$cleanCode?value=${URLEncoder.encode(b64, "UTF-8")}")
                                .post("".toRequestBody(null)).header("Content-Length", "0").build()
                            val call = client.newCall(repairReq)
                            try {
                                suspendCancellableCoroutine<Unit> { cont ->
                                    cont.invokeOnCancellation { call.cancel() }
                                    call.enqueue(object : okhttp3.Callback {
                                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                            if (cont.isActive) cont.resume(Unit) {}
                                        }
                                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                            response.close()
                                            if (cont.isActive) cont.resume(Unit) {}
                                        }
                                    })
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {}
                            putRoomRevisionHttp(cleanCode, completeRepaired)
                            setRoomStatusHttp(cleanCode, completeRepaired.status, completeRepaired.currentSeed, completeRepaired.revision)
                            recentRoomCache[cleanCode] = CachedRoom(completeRepaired, System.currentTimeMillis())
                            publishRoomMetaMqtt(completeRepaired)
                            return@withContext MutationResult.Rejected(if (sStatus == "PLAYING") "Room transitioned to PLAYING concurrently" else "Concurrent newer revision committed")
                        }
                        if (sRev >= newRevision) {
                            Log.w(TAG, "Concurrent write on $cleanCode committed revision $sRev >= $newRevision")
                            return@withContext MutationResult.Rejected("Concurrent newer revision committed")
                        }
                        if (expectedSeed != null && sSeed != 0L && sSeed != expectedSeed) {
                            return@withContext MutationResult.Rejected("Seed mismatch: storage=$sSeed, expected=$expectedSeed")
                        }
                    }

                    ensureActive()
                    putRoomRevisionHttp(cleanCode, finalSession)
                    setRoomStatusHttp(cleanCode, finalSession.status, finalSession.currentSeed, finalSession.revision)
                    ensureActive()
                    recentRoomCache[cleanCode] = CachedRoom(finalSession, System.currentTimeMillis())
                    publishRoomMetaMqtt(finalSession)
                }
                return@withContext MutationResult.Applied(finalSession)
            } catch (e: Exception) {
                Log.w(TAG, "mutateCloudSession error for $cleanCode: ${e.message}")
                return@withContext MutationResult.Unavailable(e)
            }
        }
    }

    /**
     * Managed background join backup. Runs through mutateCloudSession.
     * Revalidates current room state before persisting a join. Cross-device ordering still
     * depends on the storage protocol; this local mutex is not a distributed transaction.
     */
    internal suspend fun persistJoinBackup(cleanCode: String, joiner: Player) = withContext(Dispatchers.IO) {
        mutateCloudSession(cleanCode) { current ->
            if (current.status == "CLOSED" || current.isTerminalClosed) return@mutateCloudSession null
            val now = System.currentTimeMillis()
            val exists = current.players.any {
                it.id == joiner.id || (it.username.isNotBlank() && it.username.equals(joiner.username, ignoreCase = true))
            }
            val updatedPlayers = if (exists) {
                current.players.map { p ->
                    if (p.id == joiner.id || (p.username.isNotBlank() && p.username.equals(joiner.username, ignoreCase = true))) {
                        joiner
                    } else p
                }
            } else {
                current.players + joiner
            }
            current.copy(
                players = updatedPlayers,
                lastHeartbeat = now
            )
        }
    }

    /**
     * Fetches current snapshot of a room.
     * High-speed path: checks memory cache first, then MQTT retained metadata (<2000ms budget).
     * Fallback: checks KeyValue HTTP cloud storage if MQTT times out or returns null.
     */
    fun getRoomCachedOnly(roomCode: String): OnlineRoomSession? {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank() || terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) return null
        val cached = recentRoomCache[cleanCode] ?: return null
        if (cached.session.status == "CLOSED" || cached.session.isTerminalClosed) return null
        return cached.session
    }

    suspend fun getRoom(roomCode: String): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext null
        if (RoomMetadataApi.enabled) return@withContext readCanonicalRoom(cleanCode).getOrNull()?.takeIf {
            it.status != "CLOSED" && !it.isTerminalClosed
        }
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) return@withContext null

        val cached = recentRoomCache[cleanCode]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 3000L && cached.session.status != "CLOSED" && !cached.session.isTerminalClosed) {
            return@withContext cached.session
        }

        if (isRoomClosedHttpCancellable(cleanCode, 350L)) {
            terminallyClosedRooms.add(cleanCode)
            recentRoomCache.remove(cleanCode)
            return@withContext null
        }

        // 1. Fast MQTT Retained Query with measured budget (up to 2000ms)
        try {
            val mqttSession = withTimeoutOrNull(2000L) {
                getRoomMqtt(cleanCode, 1500L)
            }
            if (mqttSession != null && mqttSession.status != "CLOSED" && !mqttSession.isTerminalClosed) {
                val newest = if (cached != null && cached.session.revision > mqttSession.revision) cached.session else mqttSession
                recentRoomCache[cleanCode] = CachedRoom(newest, System.currentTimeMillis())
                return@withContext newest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        // 2. Fallback to KeyValue HTTP Cloud Storage
        try {
            if (isRoomClosedHttpCancellable(cleanCode, 350L)) {
                terminallyClosedRooms.add(cleanCode)
                recentRoomCache.remove(cleanCode)
                return@withContext null
            }
            val httpSession = fetchRoomDirectHttpDetailedCancellable(cleanCode, 1200L).getOrNull()
            if (httpSession != null && httpSession.status != "CLOSED" && !httpSession.isTerminalClosed) {
                val curCached = recentRoomCache[cleanCode]?.session
                val newest = if (curCached != null && curCached.revision > httpSession.revision) curCached else httpSession
                recentRoomCache[cleanCode] = CachedRoom(newest, System.currentTimeMillis())
                return@withContext newest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        // 3. Final fallback: retry MQTT retained topic with bounded 1500ms timeout
        try {
            val fallback = getRoomMqtt(cleanCode, 1500L)
            if (fallback != null && fallback.status != "CLOSED" && !fallback.isTerminalClosed) {
                val curCached = recentRoomCache[cleanCode]?.session
                val newest = if (curCached != null && curCached.revision > fallback.revision) curCached else fallback
                recentRoomCache[cleanCode] = CachedRoom(newest, System.currentTimeMillis())
                return@withContext newest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        return@withContext null
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
        knownPlayers: List<Player> = emptyList(),
        bannedPlayerIds: Set<String> = emptySet()
    ): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext null
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode) || isRoomClosedHttpCancellable(cleanCode, 350L)) {
            return@withContext null
        }

        try {
            var session = getRoom(cleanCode)
            if (session == null && !localPlayer.isHost) {
                session = getRoomMqtt(cleanCode)
            }
            if (session == null || session.status == "CLOSED" || session.isTerminalClosed) return@withContext null

            // If caller is in banned set, do not re-add or sync
            val cleanLocalUser = localPlayer.username.trim().lowercase().removePrefix("@")
            val cleanLocalDisplay = localPlayer.displayName.trim().lowercase()
            if (localPlayer.id in bannedPlayerIds ||
                (cleanLocalUser.isNotBlank() && cleanLocalUser in bannedPlayerIds) ||
                (cleanLocalDisplay.isNotBlank() && cleanLocalDisplay in bannedPlayerIds)
            ) {
                return@withContext null
            }

            // Filter out any banned/kicked players from the session
            if (bannedPlayerIds.isNotEmpty()) {
                session = session.copy(
                    players = session.players.filterNot { p ->
                        p.id in bannedPlayerIds ||
                        (p.username.isNotBlank() && p.username.trim().lowercase().removePrefix("@") in bannedPlayerIds) ||
                        (p.displayName.isNotBlank() && p.displayName.trim().lowercase() in bannedPlayerIds)
                    }
                )
            }

            val now = System.currentTimeMillis()
            val isLocalActuallyHost = localPlayer.isHost || (session.hostId.isNotBlank() && (localPlayer.id == session.hostId || (localPlayer.username.isNotBlank() && session.players.any { it.isHost && it.username.equals(localPlayer.username, ignoreCase = true) })))
            val safeLocal = sanitizePlayer(localPlayer).copy(
                isHost = isLocalActuallyHost,
                lastSeenTimestamp = now
            )

            val isHostAlive = session.status == "PLAYING" || (now - session.lastHeartbeat) < 600_000L

            val playerExists = session.players.any {
                it.id == safeLocal.id || (it.username.isNotBlank() && it.username.equals(safeLocal.username, ignoreCase = true))
            }
            val updatedPlayers = if (playerExists) {
                session.players.map { existing ->
                    if (existing.id == safeLocal.id || (existing.username.isNotBlank() && existing.username.equals(safeLocal.username, ignoreCase = true))) {
                        safeLocal
                    } else if (existing.id == session.hostId || (session.hostUsername.isNotBlank() && existing.username.equals(session.hostUsername, ignoreCase = true))) {
                        existing.copy(isHost = true, lastSeenTimestamp = if (isHostAlive) now else existing.lastSeenTimestamp)
                    } else if (isLocalActuallyHost) {
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
                    if (existing.id == session.hostId || (session.hostUsername.isNotBlank() && existing.username.equals(session.hostUsername, ignoreCase = true))) {
                        existing.copy(isHost = true, lastSeenTimestamp = if (isHostAlive) now else existing.lastSeenTimestamp)
                    } else if (existing.isHost && isHostAlive) {
                        existing.copy(lastSeenTimestamp = now)
                    } else {
                        existing
                    }
                } + safeLocal
            }

            val newHeartbeat = if (isLocalActuallyHost) now else session.lastHeartbeat
            val updatedSession = session.copy(
                players = updatedPlayers,
                lastHeartbeat = newHeartbeat
            )

            val localStatusChanged = session.players.any {
                (it.id == safeLocal.id || (it.username.isNotBlank() && it.username.equals(safeLocal.username, ignoreCase = true))) &&
                it.lobbyReadyStatus != safeLocal.lobbyReadyStatus
            }

            // Write back to cloud & MQTT if host, if local player was not present, or if local ready status changed
            if (isLocalActuallyHost || !playerExists || localStatusChanged) {
                val synced = mutateCloudSession(cleanCode) { cur ->
                    if (cur.status == "CLOSED" || cur.isTerminalClosed) return@mutateCloudSession null
                    val mergedPlayers = if (isLocalActuallyHost) {
                        cur.players.map { cp ->
                            val up = updatedSession.players.find { it.id == cp.id }
                            up ?: cp
                        }
                    } else {
                        cur.players.map { cp ->
                            if (cp.id == safeLocal.id || (cp.username.isNotBlank() && cp.username.equals(safeLocal.username, ignoreCase = true))) {
                                safeLocal
                            } else cp
                        }
                    }
                    cur.copy(
                        players = mergedPlayers,
                        lastHeartbeat = if (isLocalActuallyHost) now else cur.lastHeartbeat
                    )
                }.sessionOrNull
                if (synced != null) {
                    return@withContext synced
                }
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
            val now = System.currentTimeMillis()
            var modified = false
            val result = mutateCloudSession(cleanCode) { session ->
                if (session.status == "CLOSED" || session.isTerminalClosed) return@mutateCloudSession null
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
                if (!modified) session else session.copy(players = updatedPlayers, lastHeartbeat = now)
            }
            result is MutationResult.Applied
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
            mutateCloudSession(cleanCode) { current ->
                if (current.status == "CLOSED" || current.isTerminalClosed) return@mutateCloudSession null
                current.copy(lastHeartbeat = System.currentTimeMillis())
            }
        } catch (_: Exception) {}
    }

    /**
     * Updates status (e.g. "PLAYING" when game starts, "CLOSED" when host leaves).
     * Synchronizes currentSeed, isManualBoard, and boardSize to guarantee dual-channel game start delivery.
     */
    suspend fun updateRoomStatus(
        roomCode: String,
        status: String,
        seed: Long = 0L,
        isManualBoard: Boolean = false,
        boardSize: Int = 5,
        isDynamicBoard: Boolean = false,
        administrativeHost: Player? = null,
        departedIds: Set<String> = emptySet(),
        expectedSeed: Long? = null,
        currentTurnPlayerId: String = "",
        turnClock: MultiplayerTurnClock? = null,
        simplifiedRulesVersion: Int = 1,
        protocolVersion: Int = 0,
        startCommitDeadline: Long = 0L,
        canonicalStartPacketJson: String? = null,
        manualPreparationComplete: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext
        val cachedBefore = recentRoomCache[cleanCode]?.session
        try {
            val isClosing = (status == "CLOSED")
            val allowReset = (status == "WAITING")
            val effectiveExpectedSeed = expectedSeed ?: (if (isClosing && seed != 0L) seed else null)
            val mutationResult = mutateCloudSession(cleanCode, allowResetToWaiting = allowReset, allowClosing = isClosing, expectedSeed = effectiveExpectedSeed) { current ->
                if (effectiveExpectedSeed != null && current.currentSeed != effectiveExpectedSeed) return@mutateCloudSession null
                if (current.status == "CLOSED" && status != "CLOSED") return@mutateCloudSession null
                if (status == "PLAYING" && seed != 0L && seed != current.currentSeed && !canonicalStartPacketJson.isNullOrBlank()) {
                    val acceptedRoster = FastPacketCodec.decode(canonicalStartPacketJson).players.map { it.id }.toSet()
                    val liveRoster = current.players.filter { it.id !in departedIds }.map { it.id }.toSet()
                    if (acceptedRoster.isNotEmpty() && acceptedRoster != liveRoster) return@mutateCloudSession null
                }
                val now = System.currentTimeMillis()
                val host = administrativeHost ?: current.players.firstOrNull { it.id == current.hostId }
                val eligiblePlayers = current.players.filter { it.id !in departedIds }.map { p ->
                    if (administrativeHost != null) p.copy(isHost = p.id == administrativeHost.id) else p
                }
                val updatedPlayers = if (status == "PLAYING") {
                    eligiblePlayers.map { p ->
                        if (p.isHost || p.id == current.hostId) {
                            p.copy(lobbyReadyStatus = "IN_GAME", lastSeenTimestamp = now)
                        } else p
                    }
                } else eligiblePlayers

                current.copy(
                    status = status,
                    hostId = administrativeHost?.id ?: current.hostId,
                    hostUsername = host?.username ?: current.hostUsername,
                    hostDisplayName = host?.displayName ?: current.hostDisplayName,
                    hostAvatarUrl = host?.avatarUrl ?: current.hostAvatarUrl,
                    currentSeed = if (seed != 0L) seed else if (status == "WAITING") 0L else current.currentSeed,
                    isManualBoard = if (status == "PLAYING") isManualBoard else (if (status == "WAITING") false else current.isManualBoard),
                    boardSize = if (boardSize > 0) boardSize else current.boardSize,
                    isDynamicBoard = if (status == "PLAYING") isDynamicBoard else (if (status == "WAITING") false else current.isDynamicBoard),
                    currentTurnPlayerId = if (status == "PLAYING" && currentTurnPlayerId.isNotBlank()) currentTurnPlayerId else current.currentTurnPlayerId,
                    turnClock = if (status == "PLAYING" && turnClock != null) turnClock else current.turnClock,
                    simplifiedRulesVersion = if (status == "PLAYING") simplifiedRulesVersion else current.simplifiedRulesVersion,
                    protocolVersion = if (status == "PLAYING") protocolVersion else current.protocolVersion,
                    startCommitDeadline = if (status == "PLAYING" && startCommitDeadline > 0L) startCommitDeadline else current.startCommitDeadline,
                    canonicalStartPacketJson = if (status == "PLAYING" && !canonicalStartPacketJson.isNullOrBlank()) canonicalStartPacketJson else current.canonicalStartPacketJson,
                    manualPreparationComplete = if (status == "WAITING") null else manualPreparationComplete ?: current.manualPreparationComplete,
                    lastHeartbeat = now,
                    players = updatedPlayers,
                    isTerminalClosed = isClosing
                )
            }
            when (mutationResult) {
                is MutationResult.Applied -> {
                    // Applied successfully to cloud and MQTT
                }
                is MutationResult.Rejected -> {
                    // Semantic rejection: NEVER fall back to MQTT or modify cache!
                    Log.w(TAG, "updateRoomStatus rejected for $cleanCode: ${mutationResult.reason}")
                    return@withContext
                }
                is MutationResult.Unavailable -> {
                    if (RoomMetadataApi.enabled) {
                        // Never republish an uncommitted candidate after an unavailable CAS.
                        // A match start is retried by the lobby; closure intent remains durable.
                        if (isClosing) queueDurableClosureRetry(cleanCode, effectiveExpectedSeed)
                        return@withContext
                    }
                    // Query fresh MQTT evidence FIRST before checking stale cache
                    val mqttLive = getRoomMqtt(cleanCode, 1500L)
                    if (isClosing) {
                        if (mqttLive != null) {
                            if (effectiveExpectedSeed != null && mqttLive.currentSeed != 0L && mqttLive.currentSeed != effectiveExpectedSeed) {
                                Log.w(TAG, "Closure fallback aborted for $cleanCode: active MQTT seed ${mqttLive.currentSeed} != expected $effectiveExpectedSeed")
                                return@withContext
                            }
                            if (mqttLive.status == "PLAYING" && (effectiveExpectedSeed == null || mqttLive.currentSeed != effectiveExpectedSeed)) {
                                Log.w(TAG, "Closure fallback aborted for $cleanCode: active MQTT rematch in progress")
                                return@withContext
                            }
                            terminallyClosedRooms.add(cleanCode)
                            clearRoomMetaMqtt(cleanCode)
                            recentRoomCache.remove(cleanCode)
                            queueDurableClosureRetry(cleanCode, effectiveExpectedSeed)
                        } else {
                            // Fresh transport evidence is unavailable. Defer closure to durable retry without clearing MQTT or destroying discovery!
                            Log.w(TAG, "Closure fallback deferred for $cleanCode: fresh transport evidence unavailable during outage")
                            queueDurableClosureRetry(cleanCode, effectiveExpectedSeed)
                            return@withContext
                        }
                    } else {
                        val validatedSession = mqttLive ?: cachedBefore
                        if (validatedSession == null) {
                            Log.w(TAG, "Status commit fallback deferred for $cleanCode: no validated room context during outage")
                            return@withContext
                        }
                        if (expectedSeed != null && validatedSession.currentSeed != 0L && validatedSession.currentSeed != expectedSeed) {
                            Log.w(TAG, "MQTT fallback aborted: validated seed ${validatedSession.currentSeed} != expected $expectedSeed")
                            return@withContext
                        }
                        if (validatedSession.status == "CLOSED" || validatedSession.isTerminalClosed) {
                            Log.w(TAG, "MQTT fallback aborted: room is already closed")
                            return@withContext
                        }
                        val now = System.currentTimeMillis()
                        val host = administrativeHost ?: validatedSession.players.firstOrNull { it.id == validatedSession.hostId }
                        val eligiblePlayers = validatedSession.players.filter { it.id !in departedIds }.map { p ->
                            if (administrativeHost != null) p.copy(isHost = p.id == administrativeHost.id) else p
                        }
                        val updatedPlayers = if (status == "PLAYING") {
                            eligiblePlayers.map { p ->
                                if (p.isHost || p.id == validatedSession.hostId) {
                                    p.copy(lobbyReadyStatus = "IN_GAME", lastSeenTimestamp = now)
                                } else p
                            }
                        } else eligiblePlayers

                        val fallbackSession = validatedSession.copy(
                            status = status,
                            hostId = administrativeHost?.id ?: validatedSession.hostId,
                            hostUsername = host?.username ?: validatedSession.hostUsername,
                            hostDisplayName = host?.displayName ?: validatedSession.hostDisplayName,
                            hostAvatarUrl = host?.avatarUrl ?: validatedSession.hostAvatarUrl,
                            currentSeed = if (seed != 0L) seed else validatedSession.currentSeed,
                            isManualBoard = if (status == "PLAYING") isManualBoard else validatedSession.isManualBoard,
                            boardSize = if (boardSize > 0) boardSize else validatedSession.boardSize,
                            isDynamicBoard = if (status == "PLAYING") isDynamicBoard else validatedSession.isDynamicBoard,
                            currentTurnPlayerId = if (status == "PLAYING" && currentTurnPlayerId.isNotBlank()) currentTurnPlayerId else validatedSession.currentTurnPlayerId,
                            turnClock = if (status == "PLAYING" && turnClock != null) turnClock else validatedSession.turnClock,
                            simplifiedRulesVersion = if (status == "PLAYING") simplifiedRulesVersion else validatedSession.simplifiedRulesVersion,
                            protocolVersion = if (status == "PLAYING") protocolVersion else validatedSession.protocolVersion,
                            startCommitDeadline = if (status == "PLAYING" && startCommitDeadline > 0L) startCommitDeadline else validatedSession.startCommitDeadline,
                            canonicalStartPacketJson = if (status == "PLAYING" && !canonicalStartPacketJson.isNullOrBlank()) canonicalStartPacketJson else validatedSession.canonicalStartPacketJson,
                            manualPreparationComplete = if (status == "WAITING") null else manualPreparationComplete ?: validatedSession.manualPreparationComplete,
                            lastHeartbeat = now,
                            players = updatedPlayers,
                            revision = validatedSession.revision + 1L
                        )
                        recentRoomCache[cleanCode] = CachedRoom(fallbackSession, now)
                        publishRoomMetaMqtt(fallbackSession)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Removes a player from a room (e.g. when kicked or left).
     */
    suspend fun removePlayerFromRoom(roomCode: String, playerId: String) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        val cleanTarget = playerId.trim().lowercase().removePrefix("@")
        if (cleanCode.isBlank() || playerId.isBlank()) return@withContext
        try {
            mutateCloudSession(cleanCode) { current ->
                if (current.status == "CLOSED" || current.isTerminalClosed) return@mutateCloudSession null
                val updatedPlayers = current.players.filterNot { p ->
                    p.id == playerId ||
                    p.id.equals(cleanTarget, ignoreCase = true) ||
                    (p.username.isNotBlank() && p.username.trim().lowercase().removePrefix("@") == cleanTarget) ||
                    (p.displayName.isNotBlank() && p.displayName.trim().lowercase() == cleanTarget)
                }
                current.copy(players = updatedPlayers, lastHeartbeat = System.currentTimeMillis())
            }
        } catch (_: Exception) {}
    }

    /**
     * Closes the room completely. Clears cloud registry and MQTT metadata.
     */
    suspend fun closeRoom(roomCode: String, expectedSeed: Long? = null) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext
        updateRoomStatus(cleanCode, "CLOSED", expectedSeed = expectedSeed)
    }
}
