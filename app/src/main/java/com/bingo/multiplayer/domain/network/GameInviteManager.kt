package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

import com.bingo.multiplayer.domain.notifications.*
import kotlinx.coroutines.CancellationException

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
    val expiresAt: Long = timestamp + 120_000L,
    val isPlayRequest: Boolean = false,
    val roomSessionId: String = "",
    val senderPublicKey: String? = null
)

fun NotificationEvent.toInvite() = GameInvite(senderUsername, senderDisplayName, roomCode = roomCode,
    timestamp = createdAt, eventId = id, recipientUid = recipientUid, recipientUsername = recipientUsername,
    senderUid = senderUid, expiresAt = expiresAt, roomSessionId = roomSessionId,
    senderPublicKey = installationId.takeIf { it.isNotBlank() })
fun GameInvite.toEvent() = NotificationEvent(eventId, NotificationKind.GAME_INVITE,
    senderUid, fromUsername, fromDisplayName, recipientUid, recipientUsername, timestamp, expiresAt,
    roomCode = roomCode, roomSessionId = roomSessionId, installationId = senderPublicKey.orEmpty())

enum class InviteSendResult(val message: String) {
    QUEUED("Invitation queued. It will be sent when connected."),
    SENT("Invitation sent."), SENT_PUSH_UNAVAILABLE("Invitation sent online. Background notification could not be queued."),
    REQUESTED("Request sent."), FAILED("EMQX unavailable or recipient unavailable. Please try again.")
}

internal enum class InviteDeliveryAttempt { REGISTERED, RETRY, REJECTED }

object GameInviteManager {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val transportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val inbox = PendingInviteStore(transportScope)
    val pendingInvites = inbox.invites
    @Volatile var stagedInvite: GameInvite? = null
    val foregroundInviteFlow = kotlinx.coroutines.flow.MutableSharedFlow<GameInvite>(replay = 1, extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal val playRequestReceipts = kotlinx.coroutines.flow.MutableSharedFlow<GameInvite>(replay = 0, extraBufferCapacity = 8)
    internal fun stageInvite(invite: GameInvite) {
        inbox.put(invite)
        InvitationTrace.mark(invite.eventId, "ui_staged")
        stagedInvite = invite
        foregroundInviteFlow.tryEmit(invite)
    }
    fun consumeStagedInvite(): GameInvite? = stagedInvite.also { stagedInvite = null }
    fun clearForegroundInvite() { stagedInvite = null; foregroundInviteFlow.resetReplayCache() }

    /** Message selection shared by both lobby invitation buttons. */
    internal fun forLobbyRecipient(invite: GameInvite, presence: PlayerPresence?,
        now: Long = System.currentTimeMillis()): GameInvite =
        if (FriendPlayAvailability.of(presence, now) == FriendPlayAvailability.BUSY) {
            invite.copy(roomCode = "", roomSessionId = "", isPlayRequest = true,
                eventId = UUID.randomUUID().toString(), timestamp = now, expiresAt = now + 15_000L)
        } else invite

    suspend fun sendInvite(targetUsername: String, invite: GameInvite): InviteSendResult = withContext(Dispatchers.IO) {
        val context = NotificationRuntime.context() ?: return@withContext InviteSendResult.FAILED
        val account = NotificationRuntime.account(context) ?: return@withContext InviteSendResult.FAILED
        val clean = notificationUsername(targetUsername)
        if (clean.isBlank() || clean == account.username || invite.expiresAt <= System.currentTimeMillis()) return@withContext InviteSendResult.FAILED
        val recipient = NotificationRuntime.friends(context, account).firstOrNull { notificationUsername(it.username) == clean }
        val recipientUid = invite.recipientUid.ifBlank { recipient?.uid.orEmpty() }
        val cleanRoom = invite.roomCode.trim().uppercase()
        val room = if (invite.isPlayRequest) null else {
            OnlineRoomRegistry.getRoomCachedOnly(cleanRoom) ?: OnlineRoomRegistry.getRoom(cleanRoom)
        }
        if (!invite.isPlayRequest && (room == null || room.status == "CLOSED")) return@withContext InviteSendResult.FAILED
        val payload = invite.copy(fromUsername = account.username, senderUid = account.uid,
            recipientUid = recipientUid, recipientUsername = clean, fromAvatarUrl = null,
            roomCode = cleanRoom,
            roomSessionId = room?.sessionId.orEmpty(), senderPublicKey = null)
        try {
            var pushQueued = true
            val sent = dispatchInviteAttempt(
                readiness = { InvitationReadinessResult.Ready },
                isCurrent = { NotificationRuntime.account(context) == account && payload.expiresAt > System.currentTimeMillis() },
                publishPeer = { publishMqttInviteAwaited(clean, payload) },
                registerEvent = {
                    // The MQTT PUBACK completes the live send. Push wakes a closed/background app
                    // asynchronously, using the same identity and expiry rather than delaying joining.
                    if (!payload.isPlayRequest) {
                        pushQueued = BingoFcmManager.sendInvitePush(payload)
                        if (!pushQueued) NotificationDeliveryStatus.pushUnavailable()
                    }
                    InvitationTrace.mark(payload.eventId, "send_complete")
                })
            if (sent != InviteDeliveryAttempt.REGISTERED) InviteSendResult.FAILED
            else if (invite.isPlayRequest) InviteSendResult.REQUESTED
            else if (!pushQueued) InviteSendResult.SENT_PUSH_UNAVAILABLE else InviteSendResult.SENT
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { InviteSendResult.FAILED }
    }

    suspend fun sendPlayRequest(targetUsername: String, targetUid: String, displayName: String): InviteSendResult =
        sendInvite(targetUsername, GameInvite("", displayName, roomCode = "", recipientUid = targetUid,
            isPlayRequest = true, eventId = UUID.randomUUID().toString(), expiresAt = System.currentTimeMillis() + 15_000L))

    /** Shared production dispatch for invitations and transient requests. After PUBACK,
     * registerEvent queues the optional push without waiting for an HTTP notification request. */
    internal suspend fun dispatchInviteAttempt(
        readiness: suspend () -> InvitationReadinessResult,
        isCurrent: () -> Boolean,
        publishPeer: suspend () -> Unit,
        registerEvent: suspend () -> Unit
    ): InviteDeliveryAttempt {
        if (!isCurrent()) return InviteDeliveryAttempt.REJECTED
        val ready = readiness()
        if (!isCurrent() || ready is InvitationReadinessResult.Rejected) return InviteDeliveryAttempt.REJECTED
        if (ready is InvitationReadinessResult.TransientUnavailable) return InviteDeliveryAttempt.RETRY
        try { publishPeer() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return InviteDeliveryAttempt.RETRY }
        if (!isCurrent()) return InviteDeliveryAttempt.REJECTED
        if (ready is InvitationReadinessResult.MirrorPending) return InviteDeliveryAttempt.RETRY
        registerEvent()
        return InviteDeliveryAttempt.REGISTERED
    }
    suspend fun fetchInvitesForUser(username: String): List<GameInvite> {
        val context = NotificationRuntime.context() ?: return emptyList()
        val account = NotificationRuntime.account(context) ?: return emptyList()
        return inbox.invites.value.filter { it.recipientUid == account.uid && it.recipientUsername == notificationUsername(username) &&
            it.expiresAt > System.currentTimeMillis() && !NotificationRuntime.ledger(context).handled(account.uid, it.eventId) }
    }
    fun removeInvite(targetUsername: String, invite: GameInvite) {
        val context = NotificationRuntime.context() ?: return
        if (notificationUsername(targetUsername) == NotificationRuntime.account(context)?.username) NotificationRuntime.finishInvite(context, invite)
    }
    private fun topic(username: String, invite: GameInvite) = "bingo/emqx7/social/${if (invite.isPlayRequest) "request" else "invite"}/$username/${invite.eventId}"
    internal suspend fun publishMqttInviteAwaited(targetUsername: String, invite: GameInvite,
        connection: EmqxWire = EmqxTransport.connection) {
        InvitationTrace.mark(invite.eventId, "publish_begin")
        val payload = json.encodeToString(invite).toByteArray()
        connection.publish(topic(targetUsername, invite), payload, retained = !invite.isPlayRequest)
        if (!invite.isPlayRequest) {
            runCatching { connection.publish("bingo/v3/invites/$targetUsername", payload, retained = true) }
        }
        InvitationTrace.mark(invite.eventId, "broker_puback")
        if (!invite.isPlayRequest) transportScope.launch {
            kotlinx.coroutines.delay((invite.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            runCatching { connection.publish(topic(targetUsername, invite), ByteArray(0), retained = true) }
            runCatching { connection.publish("bingo/v3/invites/$targetUsername", ByteArray(0), retained = true) }
        }
    }
    internal fun publishMqttInvite(targetUsername: String, invite: GameInvite) {
        transportScope.launch { runCatching { publishMqttInviteAwaited(targetUsername, invite) } }
    }
    fun clearRetained(invite: GameInvite) {
        inbox.remove(invite.eventId)
        if (!invite.isPlayRequest) transportScope.launch {
            runCatching { EmqxTransport.connection.publish(topic(invite.recipientUsername, invite), ByteArray(0), retained = true) }
            runCatching { EmqxTransport.connection.publish("bingo/v3/invites/${invite.recipientUsername}", ByteArray(0), retained = true) }
        }
    }
    fun onAccountChanged() { inbox.clear(); clearForegroundInvite() }

    /** All screens share the persistent socket. A listener closes only its own collectors. */
    fun startInviteListener(myUsername: String, onInviteReceived: (GameInvite) -> Unit): AutoCloseable =
        listen(myUsername, EmqxTransport.connection, onInviteReceived)

    internal fun listen(myUsername: String, connection: EmqxWire, onInviteReceived: (GameInvite) -> Unit): AutoCloseable {
        val clean = notificationUsername(myUsername)
        val job = kotlinx.coroutines.SupervisorJob()
        val handles = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()
        val scope = kotlinx.coroutines.CoroutineScope(job + Dispatchers.IO)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun receive(bytes: ByteArray, retained: Boolean) {
            val invite = runCatching { json.decodeFromString<GameInvite>(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
            val now = System.currentTimeMillis()
            if (invite.expiresAt <= now && invite.recipientUsername == clean && !invite.isPlayRequest) {
                val own = NotificationRuntime.context()?.let { NotificationRuntime.account(it) }
                if (own?.uid == invite.recipientUid) clearRetained(invite)
                return
            }
            if (invite.recipientUsername != clean || invite.fromUsername.isBlank() ||
                invite.timestamp !in (now - 120_000L)..(now + 30_000L) || invite.expiresAt <= now ||
                invite.expiresAt > invite.timestamp + 120_000L || (invite.isPlayRequest && retained) || !seen.add(invite.eventId)) return
            InvitationTrace.mark(invite.eventId, "recipient_received")
            val context = NotificationRuntime.context()
            if (context != null) {
                val account = NotificationRuntime.account(context) ?: return
                if (account.username != clean) return
                if (invite.recipientUid.isNotBlank() && account.uid != invite.recipientUid) return
                val friend = NotificationRuntime.friends(context, account).firstOrNull { it.uid == invite.senderUid && notificationUsername(it.username) == invite.fromUsername }
                if (invite.isPlayRequest && friend == null) return
                NotificationRuntime.receiveMqttInvite(context, account, invite)
                if (!invite.isPlayRequest && NotificationRuntime.ledger(context).handled(account.uid, invite.eventId)) return
                if (!invite.isPlayRequest && NotificationRuntime.isBusy(context)) return
            } else if (!invite.isPlayRequest) stageInvite(invite) // headless broker integration seam
            if (invite.isPlayRequest) playRequestReceipts.tryEmit(invite)
            if (!invite.isPlayRequest) {
                stageInvite(invite)
                runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { onInviteReceived(invite) } }
                    .onFailure { onInviteReceived(invite) }
            }
        }
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try {
                    handles.add(connection.subscribe("bingo/emqx7/social/invite/$clean/+", ::receive))
                    handles.add(connection.subscribe("bingo/emqx7/social/request/$clean/+", ::receive))
                    handles.add(connection.subscribe("bingo/v3/invites/$clean", ::receive))
                    break
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { handles.forEach { it.close() }; handles.clear(); kotlinx.coroutines.delay(1000L) }
            }
        }
        return AutoCloseable { job.cancel(); handles.forEach { it.close() } }
    }
}
