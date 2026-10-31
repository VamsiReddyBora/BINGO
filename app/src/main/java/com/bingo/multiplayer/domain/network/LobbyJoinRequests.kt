package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.UUID

data class LobbyJoinIdentity(val uid: String, val username: String, val displayName: String)
@Serializable
data class LobbyJoinRequest(
    val id: String, val requesterId: String, val requesterUsername: String, val requesterName: String,
    val hostId: String, val roomCode: String, val lobbySessionId: String,
    val createdAt: Long, val expiresAt: Long
)
@Serializable
internal data class LobbyJoinSignal(val type: String, val senderId: String, val request: LobbyJoinRequest,
    val accepted: Boolean = false)

/** Live approval only. Actual membership still goes through the existing room admission path. */
class LobbyJoinRequests(
    private val identity: LobbyJoinIdentity,
    private val wire: EmqxWire = EmqxTransport.connection,
    private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subscription: AutoCloseable? = null
    private val received = MutableStateFlow<LobbyJoinRequest?>(null)
    private val sent = MutableStateFlow<LobbyJoinRequest?>(null)
    private val granted = MutableStateFlow<LobbyJoinRequest?>(null)
    val incoming = received.asStateFlow()
    val outgoing = sent.asStateFlow()
    val accepted = granted.asStateFlow()
    private val notifications = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = notifications.asSharedFlow()
    private var location = ""
    private var hostedRoom = ""
    private var hostedSession = ""
    private var hostOpen = false
    private var closed = false
    private val handled = mutableSetOf<String>()
    private fun topic(uid: String) = "bingo/emqx7/social/lobby_join/" + URLEncoder.encode(uid, "UTF-8")
    private fun alive(request: LobbyJoinRequest) = request.id.length in 16..80 && request.roomCode.matches(Regex("[A-Z0-9]{6}")) &&
        request.requesterId.isNotBlank() && request.hostId.isNotBlank() && request.requesterId != request.hostId &&
        request.lobbySessionId.isNotBlank() && request.expiresAt == request.createdAt + 60_000L &&
        request.createdAt <= now() + 30_000L && request.expiresAt > now()

    fun start() {
        scope.launch {
            while (isActive && !closed) {
                try {
                    subscription = wire.subscribe(topic(identity.uid)) { bytes, retained ->
                        if (!retained && bytes.size <= 8192) runCatching {
                            receive(json.decodeFromString<LobbyJoinSignal>(bytes.toString(Charsets.UTF_8)))
                        }
                    }
                    break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { delay(1000L) }
            }
        }
    }
    @Synchronized fun updateLocation(key: String, room: String = "", session: String = "", canHost: Boolean = false) {
        if (location != key) {
            sent.value?.let { old -> scope.launch { runCatching { publish("CANCEL", old, old.hostId) } } }
            sent.value = null; granted.value = null
        }
        location = key; hostedRoom = room; hostedSession = session; hostOpen = canHost
        received.value?.takeIf { !canApprove(it) }?.let { received.value = null }
    }
    @Synchronized fun canApprove(request: LobbyJoinRequest) = !closed && alive(request) && hostOpen &&
        request.hostId == identity.uid && request.roomCode == hostedRoom && request.lobbySessionId == hostedSession

    suspend fun request(room: OnlineRoomSession): Result<LobbyJoinRequest> {
        val value = synchronized(this) {
            if (closed || room.hostId == identity.uid || room.status != "WAITING" || room.isTerminalClosed || room.sessionId.isBlank())
                return Result.failure(IllegalStateException("That lobby is no longer available to join."))
            sent.value?.takeIf(::alive)?.let { return Result.failure(IllegalStateException("A join request is already pending.")) }
            if (received.value != null || granted.value != null) return Result.failure(IllegalStateException("Respond to the current join request first."))
            val time = now()
            LobbyJoinRequest(UUID.randomUUID().toString(), identity.uid, identity.username, identity.displayName,
                room.hostId, room.roomCode, room.sessionId, time, time + 60_000L).also { sent.value = it }
        }
        return try {
            publish("REQUEST", value, value.hostId)
            expire(value)
            Result.success(value)
        } catch (cancelled: CancellationException) { synchronized(this) { if (sent.value == value) sent.value = null }; throw cancelled }
        catch (error: Exception) { synchronized(this) { if (sent.value == value) sent.value = null }; Result.failure(error) }
    }
    suspend fun respond(request: LobbyJoinRequest, approve: Boolean): Boolean {
        synchronized(this) {
            if (received.value != request || (approve && !canApprove(request))) return false
            // Prevent the same pair from making both rooms the destination.
            if (approve && sent.value?.hostId == request.requesterId) return false
        }
        try { publish("RESPONSE", request, request.requesterId, approve) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        synchronized(this) { handled.add(request.id); if (received.value == request) received.value = null }
        return true
    }
    @Synchronized fun consumeAcceptance(request: LobbyJoinRequest): Boolean {
        if (granted.value != request || !alive(request)) return false
        granted.value = null; return true
    }
    @Synchronized internal fun receive(signal: LobbyJoinSignal) {
        if (closed || !alive(signal.request)) return
        val request = signal.request
        when (signal.type) {
            "REQUEST" -> {
                if (signal.senderId != request.requesterId || request.id in handled || !canApprove(request)) return
                val opposite = sent.value?.takeIf { it.hostId == request.requesterId && it.requesterId == request.hostId && alive(it) }
                if (opposite != null) {
                    // Both devices choose the same UUID winner, independent of arrival order.
                    if (opposite.id < request.id) {
                        scope.launch { runCatching { publish("RESPONSE", request, request.requesterId, false) } }
                        handled.add(request.id); return
                    }
                    sent.value = null
                }
                if (received.value != null && received.value != request) {
                    scope.launch { runCatching { publish("RESPONSE", request, request.requesterId, false) } }; return
                }
                received.value = request; expire(request)
            }
            "RESPONSE" -> {
                if (signal.senderId != request.hostId || request.requesterId != identity.uid || sent.value != request) return
                sent.value = null; handled.add(request.id)
                if (signal.accepted) granted.value = request
                else notifications.tryEmit("Your join request was declined. Your current lobby is unchanged.")
            }
            "CANCEL" -> if (signal.senderId == request.requesterId && received.value == request) received.value = null
        }
    }
    private fun expire(request: LobbyJoinRequest) {
        scope.launch {
            delay((request.expiresAt - now()).coerceAtLeast(0L))
            synchronized(this@LobbyJoinRequests) {
                if (received.value == request) received.value = null
                if (sent.value == request) {
                    sent.value = null
                    notifications.tryEmit("Join request expired. Your current lobby is unchanged.")
                }
                if (granted.value == request) granted.value = null
            }
        }
    }
    private suspend fun publish(type: String, request: LobbyJoinRequest, target: String, approved: Boolean = false) {
        wire.publish(topic(target), json.encodeToString(LobbyJoinSignal(type, identity.uid, request, approved)).toByteArray(), retained = false)
    }
    override fun close() {
        synchronized(this) { closed = true; received.value = null; sent.value = null; granted.value = null }
        scope.cancel(); subscription?.close()
    }
}
