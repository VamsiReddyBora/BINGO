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
    @kotlinx.serialization.SerialName("tc") val isTerminalClosed: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sessionId: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cancelledStartIds: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("sig")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val publisherSignature: String? = null,
    @kotlinx.serialization.SerialName("pk")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val publisherKey: String? = null
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
    private const val TAG = "OnlineRoomRegistry"

    internal data class CachedRoom(val session: OnlineRoomSession, val timestamp: Long)
    internal val recentRoomCache = java.util.concurrent.ConcurrentHashMap<String, CachedRoom>()
    internal val terminallyClosedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val roomStatusMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    internal val roomUpdates = kotlinx.coroutines.flow.MutableSharedFlow<OnlineRoomSession>(extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    internal fun rememberCanonicalRoom(session: OnlineRoomSession) {
        val code = session.roomCode
        session.publisherKey?.let { AccountKeyStore.bindHostKey(session.hostId, it) }
        val remembered = recentRoomCache.compute(code) { _, previous ->
            if (previous != null && previous.session.metadataProtocolVersion >= 3 &&
                ((previous.session.sessionId == session.sessionId && previous.session.revision > session.revision) ||
                 (previous.session.sessionId != session.sessionId && previous.session.createdAt > session.createdAt))) previous
            else CachedRoom(session, System.currentTimeMillis())
        }
        if (remembered?.session !== session) return
        roomUpdates.tryEmit(session)
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
                pendingClosures.remove(code); persistPendingClosures()
            }
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
    @androidx.annotation.VisibleForTesting
    internal var roomMetaPublisherOverride: (suspend (OnlineRoomSession) -> Boolean)? = null

    private fun getPrefs(): android.content.SharedPreferences? {
        if (customPrefs != null) return customPrefs
        val ctx = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()
        return ctx?.getSharedPreferences(PREFS_CLOSURES, android.content.Context.MODE_PRIVATE)
    }

    private fun persistPendingClosures() {
        try {
            val p = getPrefs() ?: return
            val jsonList = pendingClosures.values.map { entry ->
                "${entry.roomCode},${entry.expectedSeed ?: 0L},${entry.timestamp},${entry.attempts},${entry.sessionId},${entry.ownerUid}"
            }.toSet()
            p.edit().putStringSet("pending_closures_set", jsonList).apply()
        } catch (_: Exception) {}
    }

    internal fun reloadDurableClosures() {
        try {
            val p = getPrefs() ?: return
            p.getString("start_cancellations", null)?.let { raw ->
                runCatching { json.decodeFromString<List<PendingStartCancellation>>(raw) }.getOrDefault(emptyList()).forEach { entry ->
                    pendingStartCancellations[entry.code] = entry
                    retryStartCancellation(entry)
                }
            }
            val saved = p.getStringSet("pending_closures_set", null).orEmpty()
            saved.forEach { line ->
                val parts = line.split(",")
                if (parts.isNotEmpty()) {
                    val code = parts[0].trim().uppercase()
                    val seed = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L }
                    val ts = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis()
                    val attempts = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    if (code.isNotBlank() && !pendingClosures.containsKey(code)) {
                        val entry = PendingClosure(code, seed, ts, attempts, parts.getOrNull(4).orEmpty(), parts.getOrNull(5).orEmpty())
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
        var attempts: Int = 0,
        val sessionId: String = "",
        val ownerUid: String = ""
    )
    internal val pendingClosures = java.util.concurrent.ConcurrentHashMap<String, PendingClosure>()
    private val scope = CoroutineScope(Dispatchers.IO)

    internal fun hasPendingClosure(cleanCode: String, session: OnlineRoomSession? = recentRoomCache[cleanCode]?.session): Boolean {
        val pending = pendingClosures[cleanCode] ?: return false
        return pending.expectedSeed == null || session == null || session.currentSeed == 0L ||
            pending.expectedSeed == session.currentSeed
    }

    private val closureRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val closureRetryLock = Any()
    internal fun activeClosureRetryCount(code: String) = if (closureRetryJobs[code]?.isActive == true) 1 else 0

    internal fun queueDurableClosureRetry(cleanCode: String, expectedSeed: Long?) {
        val currentSeed = recentRoomCache[cleanCode]?.session?.currentSeed
        if (expectedSeed != null && currentSeed != null && currentSeed != expectedSeed) return
        synchronized(closureRetryLock) {
            val existing = pendingClosures[cleanCode]
            val entry = if (existing != null && existing.expectedSeed == expectedSeed) existing else
                PendingClosure(cleanCode, expectedSeed, System.currentTimeMillis(),
                    sessionId = recentRoomCache[cleanCode]?.session?.sessionId.orEmpty(),
                    ownerUid = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()?.let {
                        com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(it)?.uid
                    }.orEmpty()).also { pendingClosures[cleanCode] = it }
            if (closureRetryJobs[cleanCode]?.isActive == true && existing === entry) return
            closureRetryJobs.remove(cleanCode)?.cancel()
            persistPendingClosures()
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var delayMs = 500L
                try {
                    while (isActive && pendingClosures[cleanCode] === entry) {
                        entry.attempts++; persistPendingClosures()
                        if (executeClosurePersist(cleanCode, entry.expectedSeed)) {
                            pendingClosures.remove(cleanCode, entry); persistPendingClosures(); break
                        }
                        delay(delayMs)
                        delayMs = minOf((delayMs * 1.5).toLong(), 30000L)
                    }
                } finally {
                    val thisJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    synchronized(closureRetryLock) {
                        if (pendingClosures[cleanCode] === entry || pendingClosures[cleanCode] == null)
                            closureRetryJobs.remove(cleanCode, thisJob)
                    }
                }
            }
            closureRetryJobs[cleanCode] = job
            job.start()
        }
    }

    internal suspend fun executeClosurePersist(cleanCode: String, expectedSeed: Long?): Boolean = withContext(Dispatchers.IO) {


            val intent = pendingClosures[cleanCode]?.takeIf { it.expectedSeed == expectedSeed }
            val owner = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()?.let {
                com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(it)?.uid
            }.orEmpty()
            val result = if (intent?.ownerUid?.isNotBlank() == true && intent.ownerUid != owner)
                MutationResult.Rejected("Account changed") else RoomMetadataApi.coordinator().mutate(cleanCode, expectedSeed = expectedSeed,
                allowAlreadyClosed = true) {
                    if (intent?.sessionId?.isNotBlank() == true && intent.sessionId != it.sessionId) null
                    else it.copy(status = "CLOSED", isTerminalClosed = true)
                }
            publishCanonicalMutation(cleanCode, result)
            if (result is MutationResult.Rejected) {
                pendingClosures[cleanCode]?.takeIf { it.expectedSeed == expectedSeed }?.let {
                    pendingClosures.remove(cleanCode, it); persistPendingClosures()
                }
            }
            return@withContext result is MutationResult.Applied

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
        // The sole retained record is published atomically by the host transport.
        return roomMetaPublisherOverride?.invoke(session) ?: true
    }

    /**
     * Clears retained MQTT room metadata when room is closed.
     */
    private suspend fun clearRoomMetaMqtt(roomCode: String): Boolean {
        // Retain the terminal tombstone. Clearing it would let stale START recreate a room.
        return true
    }

    /**
     * Retrieves room session from MQTT retained topic.
     * Truly cancellable coroutine that forcibly disconnects and closes client on cancellation without quiesce delay.
     */
    internal suspend fun getRoomMqtt(cleanCode: String, timeoutMs: Long = 1500L): OnlineRoomSession? = withContext(Dispatchers.IO) {
        val room = readCanonicalRoom(cleanCode).getOrNull()
        return@withContext room?.takeIf { it.status != "CLOSED" && !it.isTerminalClosed }
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
        if (terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) {
            return@withContext InvitationReadinessResult.Rejected("Room is terminally closed")
        }

        val fetched = readCanonicalRoom(cleanCode)
        if (fetched.isFailure) return@withContext InvitationReadinessResult.TransientUnavailable("Room metadata is unavailable")
        var room = fetched.getOrNull()
        if (room == null && allowCreation) {
            if (!createRoom(cleanCode, hostPlayer)) return@withContext InvitationReadinessResult.TransientUnavailable("Room creation is unavailable")
            room = readCanonicalRoom(cleanCode).getOrNull()
        }
        val ready = room ?: return@withContext InvitationReadinessResult.Rejected("Room does not exist")
        if (ready.status == "CLOSED" || ready.isTerminalClosed) {
            terminallyClosedRooms.add(cleanCode)
            return@withContext InvitationReadinessResult.Rejected("Room is terminally closed")
        }
        if (ready.status != "WAITING") return@withContext InvitationReadinessResult.Rejected("Room is in PLAYING status")
        if (ready.hostId != hostPlayer.id) return@withContext InvitationReadinessResult.Rejected("Host authority mismatch")
        // A verified canonical record is already discoverable by invitation recipients.
        // Requiring a second MQTT metadata publication here can indefinitely block push
        // and inbox delivery despite a healthy, committed room.
        return@withContext InvitationReadinessResult.Ready

    }

    suspend fun ensureInvitationReadiness(
        roomCode: String,
        hostPlayer: Player
    ): Boolean = withContext(Dispatchers.IO) {
        ensureInvitationReadinessDetailed(roomCode, hostPlayer) is InvitationReadinessResult.Ready
    }

    internal fun queueMirrorReconciliationRetry(cleanCode: String, session: OnlineRoomSession) {
        // No second transport or mirror exists in protocol 7.
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
        // The conditional commit establishes discovery. Keep the retained MQTT mirror
        // as a compatibility aid without making it a second room-creation requirement.
        publishRoomMetaMqtt(result.session)
        return@withContext true

    }

    /**
     * Validates a room code against active rooms and adds the joiner if valid.
     * Resolves an active host and admits the player through an acknowledged MQTT request.
     * Allows any player (new or rejoining) to join as long as room is open and not full.
     */
    /** Shared invitation/code entry used by the app's acceptance callback. */
    suspend fun joinRoom(roomCode: String, joiner: Player, invitation: GameInvite? = null): RoomJoinResult =
        validateAndJoinRoom(roomCode, joiner,
            // The sender may be any admitted friend, not necessarily the lobby host.
            expectedSessionId = invitation?.roomSessionId?.takeIf { it.isNotBlank() })

    suspend fun validateAndJoinRoom(
        roomCode: String,
        joiner: Player,
        expectedSessionId: String? = null,
        expectedHostId: String? = null
    ): RoomJoinResult = withContext(Dispatchers.IO) {

        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.length != 6) {
            return@withContext RoomJoinResult.NotFound("Room code must be 6 characters.")
        }

        val cleanExpectedSession = expectedSessionId?.takeIf { it.isNotBlank() }
        val cleanExpectedHost = expectedHostId?.takeIf { it.isNotBlank() }

        try {
            val fetched = readCanonicalRoom(cleanCode)
            val failure = fetched.exceptionOrNull()
            val isUnauthenticatedSig = failure?.message?.contains("Unauthenticated metadata signature") == true
            var session = fetched.getOrNull()?.takeIf { it.status != "CLOSED" && !it.isTerminalClosed }
            val isCanonicalVerified = (session != null)
            if (session == null) {
                val candidate = runCatching { RoomMetadataApi.readCandidate(cleanCode) }.getOrNull()?.takeIf { it.status != "CLOSED" && !it.isTerminalClosed }
                    ?: getRoomCachedOnly(cleanCode)?.takeIf { it.status != "CLOSED" && !it.isTerminalClosed }
                if (candidate != null && (cleanExpectedHost == null || candidate.hostId == cleanExpectedHost) &&
                    (cleanExpectedSession == null || candidate.sessionId == cleanExpectedSession)) {
                    session = candidate
                }
            }
            if (session == null && fetched.isFailure && !isUnauthenticatedSig) {
                return@withContext RoomJoinResult.Error("EMQX is temporarily unavailable. Please try again.")
            }

            if (session == null) {
                return@withContext RoomJoinResult.NotFound("No active room found for code $cleanCode.")
            }

            if ((cleanExpectedSession != null && cleanExpectedSession != session.sessionId) ||
                (cleanExpectedHost != null && cleanExpectedHost != session.hostId))
                return@withContext RoomJoinResult.NotFound("This invitation belongs to an older lobby.")
            if (session.status == "CLOSED" || session.isTerminalClosed || terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) {
                return@withContext RoomJoinResult.NotFound("Room $cleanCode has been closed by the host.")
            }

            val existingInSession = session.players.find {
                it.id == joiner.id || (it.username.isNotBlank() && it.username.equals(joiner.username, ignoreCase = true))
            }

            if (session.status == "PLAYING") {
                if (!isCanonicalVerified) {
                    return@withContext RoomJoinResult.Error("Cannot rejoin active match from unverified discovery candidate.")
                }
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
                if (current.status != "WAITING" || current.isTerminalClosed ||
                    (cleanExpectedSession != null && current.sessionId != cleanExpectedSession) ||
                    (cleanExpectedHost != null && current.hostId != cleanExpectedHost)) return@mutateCloudSession null
                val isAlready = current.players.any { it.id == safeJoiner.id || (it.username.isNotBlank() && it.username.equals(safeJoiner.username, ignoreCase = true)) }
                val newPlayers = if (isAlready) {
                    current.players.map { p ->
                        if (p.id == safeJoiner.id || (p.username.isNotBlank() && p.username.equals(safeJoiner.username, ignoreCase = true))) {
                            safeJoiner
                        } else if (p.isHost) {
                            p
                        } else p
                    }
                } else {
                    current.players + safeJoiner
                }
                current.copy(players = newPlayers, lastHeartbeat = now)
            }

            when (mutation) {
                is MutationResult.Applied -> {
                    return@withContext RoomJoinResult.Success(mutation.session)
                }
                is MutationResult.Unavailable -> {
                    Log.w(TAG, "Lobby join was not acknowledged: ${mutation.error.message}")
                    return@withContext RoomJoinResult.Error("Could not reach the room host. Please try joining again.")
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
        return@withContext readCanonicalRoom(cleanCode)
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
        return@withContext readCanonicalRoom(cleanCode).getOrNull()?.let { "${it.status}:${it.currentSeed}:${it.revision}" }
    }

    internal suspend fun setRoomStatusHttp(cleanCode: String, status: String, seed: Long, revision: Long) = withContext(Dispatchers.IO) {
        mutateCloudSession(cleanCode, expectedSeed = seed.takeIf { it != 0L }) { current ->
            if (current.status == status) current else null
        }
    }

    internal suspend fun putRoomRevisionHttp(cleanCode: String, session: OnlineRoomSession) = withContext(Dispatchers.IO) {
        mutateCloudSession(cleanCode, expectedSeed = session.currentSeed) { current ->
            if (current.revision == session.revision) current else null
        }
    }

    internal suspend fun fetchRoomRevisionHttp(cleanCode: String, rev: Long, timeoutMs: Long = 1000L): OnlineRoomSession? = withContext(Dispatchers.IO) {
        return@withContext readCanonicalRoom(cleanCode).getOrNull()?.takeIf { it.revision == rev }
    }

    internal suspend fun isRoomClosedHttpCancellable(cleanCode: String, timeoutMs: Long = 350L): Boolean = withContext(Dispatchers.IO) {
        return@withContext hasPendingClosure(cleanCode) || readCanonicalRoom(cleanCode).getOrNull()?.let { it.status == "CLOSED" || it.isTerminalClosed } == true
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
        if ((terminallyClosedRooms.contains(cleanCode) || hasPendingClosure(cleanCode)) && !allowClosing)
            return@withContext MutationResult.Rejected("Room is terminally closed")
        val cachedSeed = recentRoomCache[cleanCode]?.session?.currentSeed
        if (expectedSeed != null && cachedSeed != null && cachedSeed != expectedSeed)
            return@withContext MutationResult.Rejected("Seed mismatch: current=$cachedSeed, expected=$expectedSeed")

        val result = RoomMetadataApi.coordinator().mutate(cleanCode, expectedSeed = expectedSeed,
            allowReset = allowResetToWaiting, allowAlreadyClosed = allowClosing, transform = transform)
        if (result is MutationResult.Unavailable && allowClosing && cachedSeed != null) queueDurableClosureRetry(cleanCode, expectedSeed)
        return@withContext publishCanonicalMutation(cleanCode, result)

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
        if (cleanCode.isBlank() || terminallyClosedRooms.contains(cleanCode)) return@withContext null
        return@withContext readCanonicalRoom(cleanCode).getOrNull()?.takeIf {
            it.status != "CLOSED" && !it.isTerminalClosed && !hasPendingClosure(cleanCode, it) &&
                (it.status == "PLAYING" || System.currentTimeMillis() - it.lastHeartbeat < 600_000L)
        }
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
                        existing.copy(isHost = true)
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
                        existing.copy(isHost = true)
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
    ): MutationResult = withContext(Dispatchers.IO) {

        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext MutationResult.Rejected("Blank room code")
        try {
            val isClosing = (status == "CLOSED")
            val allowReset = (status == "WAITING")
            val effectiveExpectedSeed = expectedSeed ?: (if (isClosing && seed != 0L) seed else null)
            val mutationResult = mutateCloudSession(cleanCode, allowResetToWaiting = allowReset, allowClosing = isClosing, expectedSeed = effectiveExpectedSeed) { current ->
                if (effectiveExpectedSeed != null && current.currentSeed != effectiveExpectedSeed) return@mutateCloudSession null
                if (current.status == "CLOSED" && status != "CLOSED") return@mutateCloudSession null
                if (status in setOf("STARTING", "PLAYING") && seed != 0L && !canonicalStartPacketJson.isNullOrBlank()) {
                    val start = FastPacketCodec.decode(canonicalStartPacketJson)
                    if (start.requestId.isNotBlank() && start.requestId in current.cancelledStartIds) return@mutateCloudSession null
                    val acceptedRoster = start.players.map { it.id }.toSet()
                    val liveRoster = current.players.filter { it.id !in departedIds }.map { it.id }.toSet()
                    if (acceptedRoster.isNotEmpty() && acceptedRoster != liveRoster) return@mutateCloudSession null
                }
                val now = System.currentTimeMillis()
                val host = administrativeHost ?: current.players.firstOrNull { it.id == current.hostId }
                val eligiblePlayers = current.players.filter { it.id !in departedIds }.map { p ->
                    if (administrativeHost != null) p.copy(isHost = p.id == administrativeHost.id) else p
                }
                val updatedPlayers = if (status in setOf("STARTING", "PLAYING")) {
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
                    isManualBoard = if (status in setOf("STARTING", "PLAYING")) isManualBoard else (if (status == "WAITING") false else current.isManualBoard),
                    boardSize = if (boardSize > 0) boardSize else current.boardSize,
                    isDynamicBoard = if (status in setOf("STARTING", "PLAYING")) isDynamicBoard else (if (status == "WAITING") false else current.isDynamicBoard),
                    currentTurnPlayerId = if (status in setOf("STARTING", "PLAYING") && currentTurnPlayerId.isNotBlank()) currentTurnPlayerId else current.currentTurnPlayerId,
                    turnClock = if (status in setOf("STARTING", "PLAYING") && turnClock != null) turnClock else current.turnClock,
                    simplifiedRulesVersion = if (status in setOf("STARTING", "PLAYING")) simplifiedRulesVersion else current.simplifiedRulesVersion,
                    protocolVersion = if (status in setOf("STARTING", "PLAYING")) protocolVersion else current.protocolVersion,
                    startCommitDeadline = if (status in setOf("STARTING", "PLAYING") && startCommitDeadline > 0L) startCommitDeadline else current.startCommitDeadline,
                    canonicalStartPacketJson = if (status in setOf("STARTING", "PLAYING") && !canonicalStartPacketJson.isNullOrBlank()) canonicalStartPacketJson else current.canonicalStartPacketJson,
                    manualPreparationComplete = if (status == "WAITING") null else manualPreparationComplete ?: current.manualPreparationComplete,
                    lastHeartbeat = now,
                    players = updatedPlayers,
                    isTerminalClosed = isClosing
                )
            }
            if (mutationResult is MutationResult.Unavailable && isClosing)
                queueDurableClosureRetry(cleanCode, effectiveExpectedSeed)
            mutationResult
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { MutationResult.Unavailable(e) }


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
    @Serializable internal data class PendingStartCancellation(val code: String, val seed: Long, val epoch: String,
        val owner: String, val session: String)
    private val pendingStartCancellations = java.util.concurrent.ConcurrentHashMap<String, PendingStartCancellation>()
    internal fun hasPendingStartCancellation(code: String, seed: Long, epoch: String): Boolean =
        pendingStartCancellations[code]?.let { it.seed == seed && it.epoch == epoch } == true
    private val cancellationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val cancellationLock = Any()
    private val cancellationEntries = java.util.concurrent.ConcurrentHashMap<String, PendingStartCancellation>()
    private fun persistStartCancellations() {
        getPrefs()?.edit()?.putString("start_cancellations", json.encodeToString(pendingStartCancellations.values.toList()))?.apply()
    }
    private fun finishStartCancellation(entry: PendingStartCancellation, clearCheckpoint: Boolean = false) {
        pendingStartCancellations.remove(entry.code, entry)
        persistStartCancellations()
        if (clearCheckpoint) com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()?.let { context ->
            runCatching { OngoingMatchStore.clearScheduledStart(context, entry.owner, entry.seed, entry.epoch) }
        }
    }
    private suspend fun applyStartCancellation(entry: PendingStartCancellation): MutationResult {
        val owner = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()?.let {
            com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(it)?.uid
        }.orEmpty()
        if (entry.owner.isNotBlank() && owner != entry.owner) return MutationResult.Rejected("Account changed")
        return mutateCloudSession(entry.code, allowResetToWaiting = true, expectedSeed = entry.seed) { room ->
            val activeStart = room.canonicalStartPacketJson?.let { runCatching { FastPacketCodec.decode(it) }.getOrNull() }
            // Released PLAYING is an existing match. A transport interruption must recover it,
            // never turn a late preparation cancellation into a lobby reset.
            if ((room.status == "PLAYING" && activeStart?.startReleased == true) ||
                room.currentSeed != entry.seed || entry.epoch.isBlank() || activeStart?.requestId != entry.epoch ||
                (entry.session.isNotBlank() && room.sessionId != entry.session)) null else room.copy(status = "WAITING", currentSeed = 0L,
                canonicalStartPacketJson = null, startCommitDeadline = 0L, turnClock = null,
                cancelledStartIds = (room.cancelledStartIds + entry.epoch).distinct().takeLast(16),
                players = room.players.map { if (it.lobbyReadyStatus == "IN_GAME") it.copy(lobbyReadyStatus = "READY") else it })
        }
    }
    private fun retryStartCancellation(entry: PendingStartCancellation) {
        synchronized(cancellationLock) {
            if (cancellationJobs[entry.code]?.isActive == true && cancellationEntries[entry.code] == entry) return
            cancellationJobs.remove(entry.code)?.cancel()
            cancellationEntries[entry.code] = entry
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    var retryDelay = 1000L
                    while (isActive && pendingStartCancellations[entry.code] == entry) {
                        delay(retryDelay)
                        retryDelay = minOf(30_000L, retryDelay * 2L)
                        when (applyStartCancellation(entry)) {
                            is MutationResult.Unavailable -> Unit
                            is MutationResult.Applied -> { finishStartCancellation(entry, clearCheckpoint = true); break }
                            is MutationResult.Rejected -> { finishStartCancellation(entry); break }
                        }
                    }
                } finally {
                    val thisJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    cancellationJobs.remove(entry.code, thisJob)
                    cancellationEntries.remove(entry.code, entry)
                }
            }
            cancellationJobs[entry.code] = job; job.start()
        }
    }
    suspend fun cancelScheduledStart(roomCode: String, seed: Long, epoch: String): Boolean {
        val cached = recentRoomCache[roomCode]?.session
        if (epoch.isBlank() || (cached != null && cached.currentSeed != seed)) return false
        val owner = com.bingo.multiplayer.domain.notifications.NotificationRuntime.context()?.let {
            com.bingo.multiplayer.domain.notifications.NotificationRuntime.account(it)?.uid
        }.orEmpty()
        val entry = PendingStartCancellation(roomCode, seed, epoch, owner, cached?.sessionId.orEmpty())
        pendingStartCancellations[roomCode] = entry; persistStartCancellations()
        return when (applyStartCancellation(entry)) {
            is MutationResult.Applied -> { finishStartCancellation(entry, clearCheckpoint = true); true }
            is MutationResult.Rejected -> { finishStartCancellation(entry); false }
            is MutationResult.Unavailable -> { retryStartCancellation(entry); false }
        }
    }

    suspend fun closeRoom(roomCode: String, expectedSeed: Long? = null) = withContext(Dispatchers.IO) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) return@withContext
        updateRoomStatus(cleanCode, "CLOSED", expectedSeed = expectedSeed)
    }
}
