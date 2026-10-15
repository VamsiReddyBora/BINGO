package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface ReliableMatchTransport {
    val connected: Boolean
    val healthy: Boolean
    suspend fun subscribe(seed: Long): Boolean
    suspend fun publish(packet: RoomMessagePacket, retained: Boolean = false, stateTopic: Boolean = false): Boolean
}

interface ReliableMatchCheckpoint {
    fun save(snapshot: ReliableMatchSnapshot, pending: Boolean): Boolean
    fun saveHostWait(snapshot: ReliableMatchSnapshot?): Boolean
}

data class ReliableMatchView(
    val snapshot: ReliableMatchSnapshot? = null,
    val remainingMillis: Long = 30_000,
    val waiting: Boolean = true,
    val message: String = "Synchronizing match…",
    val hostDisconnected: Boolean = false
)

/** Actual application protocol, also exercised with independent transports/stores in integration tests.
 * The broker retains a complete accepted snapshot, including an immutable terminal result.
 * There is one fixed host installation; there is deliberately no heartbeat-based owner promotion.
 */
class ReliableMatchClient(
    initial: ReliableMatchSnapshot,
    private val localUid: String,
    private val installationId: String,
    private val clock: Clock,
    private val transport: ReliableMatchTransport,
    private val checkpoint: ReliableMatchCheckpoint,
    parentScope: CoroutineScope,
    private val previouslyAccepted: Boolean = false,
    private val onSynchronized: () -> Unit = {},
    initialHostWait: ReliableMatchSnapshot? = null
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var generation = UUID.randomUUID().toString()
    private var syncRequest = UUID.randomUUID().toString()
    private var barrier = ""
    private var subscribed = false
    private var barrierComplete = false
    private var negotiated = false
    private var locallyHealthy = false
    private var retained: ReliableMatchSnapshot? = null
    private var lastSend = Long.MIN_VALUE / 2
    private var lastCheckpoint = Long.MIN_VALUE / 2
    private var peerGeneration = ""
    private var peerSynchronizedGeneration = ""
    private data class PendingAck(val sentAt: Long, val generation: String, val snapshot: ReliableMatchSnapshot)
    private val pendingAcks = mutableMapOf<String, PendingAck>()
    private var pendingIntent: MatchIntent? = null
    private var lastIntentSent = Long.MIN_VALUE / 2
    private var hostWaitSnapshot: ReliableMatchSnapshot? = initialHostWait
    private var holdNeedsSave = false
    private var hostDisconnected = localUid != initial.hostId && previouslyAccepted && initial.final == null
    private var awaitingReady: RoomMessagePacket? = null
    private var readyRequest = ""
    private var ownerHoldStartedAt = 0L
    private var lastPeerRecoveryAt = Long.MIN_VALUE / 2
    private var hasAcceptedState = previouslyAccepted
    // A locally staged candidate is not accepted authority after a crash before publication.
    private var pendingAtLaunch = localUid == initial.hostId && !previouslyAccepted && initial.sequence > 0
    // Invalid/legacy preview data cannot authorize a game or crash recovery. Preserve identity,
    // wait for a full validated host snapshot, and never generate a replacement board.
    private var coordinator: ReliableMatchCoordinator = makeCoordinator(if (initial.valid()) initial else initial.copy(
        boards = emptyMap(), picks = emptyList(), chat = emptyList(), episodes = emptyMap(),
        revision = 0, sequence = 0, turn = 1, final = null, acceptedActions = emptyList()))
    private val mutableView = MutableStateFlow(ReliableMatchView())
    val view: StateFlow<ReliableMatchView> = mutableView
    val activeMatchId: String = initial.matchId
    val accountId: String get() = localUid
    val isHost: Boolean get() = localUid == coordinator.state.hostId

    init {
        if (hostWaitSnapshot != null && (!hostWaitSnapshot!!.valid() || hostWaitSnapshot!!.matchId != activeMatchId ||
                hostWaitSnapshot!!.ownerId != initial.ownerId || hostWaitSnapshot!!.roomCode != initial.roomCode)) hostWaitSnapshot = null
        if (hostWaitSnapshot != null) hostDisconnected = true
        if (previouslyAccepted && initial.final != null && initial.valid()) {
            hostWaitSnapshot = null
            hostDisconnected = false
            holdNeedsSave = true
            coordinator.resumeObservation()
            updateView()
        }
        if (hostDisconnected) {
            if (hostWaitSnapshot == null && initial.valid()) hostWaitSnapshot = initial
            holdNeedsSave = true
            updateView()
        }
        scope.launch {
            for (event in events) {
                try { event() } catch (_: Exception) { waitForRecovery("Could not save or synchronize match. Retrying…") }
            }
        }
        scope.launch { while (isActive) { events.send { tick() }; delay(100) } }
    }

    private fun makeCoordinator(state: ReliableMatchSnapshot): ReliableMatchCoordinator = ReliableMatchCoordinator(state, localUid, installationId, clock) { candidate ->
        // This callback runs only on the IO event loop. An ambiguous publication blocks further
        // mutations until a fresh subscription barrier reconciles the broker's retained truth.
        checkpoint.save(candidate, true) && runBlocking {
            transport.publish(packet("STATE", snapshot = candidate), retained = true, stateTopic = true)
        } && checkpoint.save(candidate, false)
    }

    private fun packet(kind: String, request: String = "", snapshot: ReliableMatchSnapshot? = null, intent: MatchIntent? = null) = RoomMessagePacket(
        type = "MATCH_V2_$kind", playerId = localUid, protocolVersion = 2, hostWaitVersion = 1,
        seed = coordinator.state.seed, matchId = activeMatchId, connectionGeneration = generation,
        roomCode = coordinator.state.roomCode, requestId = request, ownerId = coordinator.state.ownerId, reliableSnapshot = snapshot, matchIntent = intent,
        hostWaitSnapshot = if (!isHost && kind in setOf("HELLO", "SYNC_REQUEST")) hostWaitSnapshot else null
    )

    fun receive(packet: RoomMessagePacket) { events.trySend { received(packet) } }
    fun request(kind: String, number: Int = 0, text: String = "", episodeId: String = "") {
        events.trySend {
            if (mutableView.value.waiting || coordinator.state.final != null || !transport.healthy) return@trySend
            val intent = MatchIntent(UUID.randomUUID().toString(), activeMatchId, localUid, coordinator.state.revision,
                coordinator.state.turn, kind, number, text, System.currentTimeMillis(), episodeId)
            if (isHost) {
                coordinator.dispatch(intent, transport.healthy)
                if (!coordinator.ready) beginRecovery()
                updateView()
            } else if (pendingIntent == null) {
                pendingIntent = intent
                sendIntent()
            }
        }
    }

    fun waitForHost() { events.trySend {
        if (!isHost && coordinator.state.final == null && !hostDisconnected) {
            coordinator.suspendObservation()
            hostWaitSnapshot = coordinator.sampled()
            hostDisconnected = true
            holdNeedsSave = true
            beginRecovery()
            holdNeedsSave = !checkpoint.saveHostWait(hostWaitSnapshot)
        }
    } }
    fun requestSync() { events.trySend { beginRecovery() } }
    fun peerEpisode(peer: String, episode: MatchEpisode) {
        events.trySend {
            if (!episode.unreachable) {
                // Fresh response immediately revokes claim before the durable episode update.
                freshPeers.add(peer)
            } else freshPeers.remove(peer)
            desiredEpisodes[peer] = episode
            if (isHost && coordinator.ready && coordinator.state.recoveryId.isEmpty()) {
                if (coordinator.updatePeerEpisode(peer, episode)) desiredEpisodes.remove(peer)
                if (!coordinator.ready) beginRecovery()
                updateView()
            }
        }
    }
    private val freshPeers = mutableSetOf<String>()
    private val desiredEpisodes = mutableMapOf<String, MatchEpisode>()

    /** Used by local UI claims so the current verified policy must agree with the committed episode. */
    fun claim(episodeId: String) {
        events.trySend {
            val peer = coordinator.state.order.first { it != localUid }
            if (!isHost || peer in freshPeers || !transport.healthy || mutableView.value.waiting) return@trySend
            val s = coordinator.state
            coordinator.dispatch(MatchIntent(UUID.randomUUID().toString(), activeMatchId, localUid, s.revision, s.turn, "CLAIM", episodeId = episodeId), true)
            if (!coordinator.ready) beginRecovery()
            updateView()
        }
    }

    fun leave() {
        events.trySend {
            if (isHost && coordinator.ready && transport.healthy) coordinator.checkpoint()
            coordinator.suspendObservation()
            scope.cancel()
        }
    }
    fun close() { scope.cancel() }

    private fun waitForRecovery(message: String) {
        coordinator.suspendObservation()
        updateView(message)
    }
    private fun beginRecovery() {
        waitForRecovery("Synchronizing match…")
        generation = UUID.randomUUID().toString()
        syncRequest = UUID.randomUUID().toString()
        pendingIntent = null
        subscribed = false
        barrierComplete = false
        negotiated = false
        pendingAcks.clear()
        peerSynchronizedGeneration = ""
        peerGeneration = ""
        awaitingReady = null
        readyRequest = ""
        lastSend = Long.MIN_VALUE / 2
    }

    private suspend fun tick() {
        if (holdNeedsSave) holdNeedsSave = !checkpoint.saveHostWait(hostWaitSnapshot)
        if (coordinator.state.final != null && coordinator.ready) { updateView(); return }
        if (!transport.connected || !transport.healthy) {
            if (locallyHealthy) {
                beginRecovery()
                if (isHost) checkpoint.save(coordinator.sampled(), true)
            }
            locallyHealthy = false
            return
        }
        if (!locallyHealthy) { locallyHealthy = true; beginRecovery() }
        val now = clock.elapsedRealtime()
        if (!subscribed) {
            if (!transport.subscribe(coordinator.state.seed)) return
            subscribed = true
            barrier = UUID.randomUUID().toString()
            if (!transport.publish(packet("BARRIER", barrier), stateTopic = true)) { subscribed = false; return }
        }
        if (now - lastSend >= 2500 && (!coordinator.ready || coordinator.state.recoveryId.isNotEmpty() || (isHost && peerSynchronizedGeneration != peerGeneration))) {
            lastSend = now
            transport.publish(packet("HELLO", syncRequest))
        }
        if (barrierComplete && isHost && !coordinator.ready && (negotiated || previouslyAccepted || retained != null)) activateOwner()
        if (coordinator.ready && isHost && coordinator.state.recoveryId.isNotEmpty() && hasAcceptedState &&
            now - maxOf(ownerHoldStartedAt, lastPeerRecoveryAt) >= 15_000) {
            // Host returning alone may continue under the existing guest-absence policy.
            // No elapsed offline time is replayed; all fresh turns still receive 30 seconds.
            if (!coordinator.resumeAfterRecovery(coordinator.state.recoveryId)) beginRecovery()
            else onSynchronized()
        }
        if (coordinator.ready && isHost && coordinator.state.recoveryId.isEmpty() && coordinator.state.final == null) {
            for ((peer, episode) in desiredEpisodes.toMap()) {
                if (coordinator.updatePeerEpisode(peer, episode)) desiredEpisodes.remove(peer)
                if (!coordinator.ready) break
            }
            if (!coordinator.state.paused && coordinator.remainingMillis() == 0L) {
                val s = coordinator.state
                coordinator.dispatch(MatchIntent("timeout:${s.matchId}:${s.turn}", s.matchId, localUid, s.revision, s.turn, "TIMEOUT"), true)
            } else if (now - lastCheckpoint >= 2500) {
                lastCheckpoint = now
                coordinator.checkpoint()
            }
            if (!coordinator.ready) beginRecovery()
        }
        if (pendingIntent != null && now - lastIntentSent >= 2500) sendIntent()
        updateView()
    }

    private suspend fun activateOwner() {
        val localFrozen = coordinator.sampled()
        val recovered = retained
        if (pendingAtLaunch && recovered == null) {
            waitForRecovery("Waiting for the last accepted match checkpoint…")
            return
        }
        if (recovered != null) {
            if (recovered.ownerId != installationId) { waitForRecovery("Reopen this match on its original host device."); return }
            if (pendingAtLaunch || recovered.sequence >= coordinator.state.sequence) coordinator = makeCoordinator(recovered)
            hasAcceptedState = true
        }
        pendingAtLaunch = false
        if (coordinator.state.final != null) {
            if (!checkpoint.save(coordinator.state, false)) { beginRecovery(); return }
            coordinator.resumeObservation(); updateView(); return
        }
        if (!coordinator.holdForRecovery(generation, localFrozen)) { beginRecovery(); return }
        ownerHoldStartedAt = clock.elapsedRealtime()
        updateView()
    }

    private suspend fun sendIntent() {
        val intent = pendingIntent ?: return
        if (!coordinator.ready || !transport.healthy) return
        lastIntentSent = clock.elapsedRealtime()
        transport.publish(packet("INTENT", intent = intent))
    }

    private fun validSnapshot(s: ReliableMatchSnapshot): Boolean {
        val current = coordinator.state
        if (current.boards.isNotEmpty() && (s.turn < current.turn || s.order != current.order)) return false
        if (current.boards.isNotEmpty() && current.boards.any { (uid, b) -> b.cells.map { it.number } != s.boards[uid]?.cells?.map { it.number } }) return false
        return sameMatchIdentity(s)
    }

    private fun validRetainedSnapshot(s: ReliableMatchSnapshot): Boolean {
        val current = coordinator.state
        if (isHost && !coordinator.ready && pendingAtLaunch) {
            return s.valid() && s.matchId == current.matchId && s.roomCode == current.roomCode && s.hostId == current.hostId &&
                s.ownerId == current.ownerId && s.order == current.order && s.participants.map { it.id }.toSet() == current.participants.map { it.id }.toSet() &&
                current.boards.all { (uid, board) -> board.cells.map { it.number } == s.boards[uid]?.cells?.map { it.number } }
        }
        // An old retained timing/chat/pause sample cannot replace a known accepted checkpoint.
        return validSnapshot(s) && (!isHost || (s.sequence >= current.sequence && s.revision >= current.revision &&
            (s.sequence != current.sequence || s.copy(remainingMillis = current.remainingMillis,
                elapsedPlayMillis = current.elapsedPlayMillis) == current)))
    }

    private fun sameMatchIdentity(s: ReliableMatchSnapshot): Boolean {
        val current = coordinator.state
        return s.valid() && s.matchId == activeMatchId && s.roomCode == current.roomCode && s.hostId == current.hostId &&
            s.participants.map { it.id }.toSet() == current.participants.map { it.id }.toSet() &&
            (current.ownerId == "awaiting-host" || s.ownerId == current.ownerId) &&
            (current.final == null || current.final == s.final)
    }

    private fun applySnapshot(s: ReliableMatchSnapshot, synchronize: Boolean): Boolean {
        val previous = coordinator.state
        if (!validSnapshot(s) || s.revision < previous.revision || s.sequence < previous.sequence) return false
        if (s.sequence == previous.sequence) {
            if (s.copy(remainingMillis = previous.remainingMillis, elapsedPlayMillis = previous.elapsedPlayMillis) != previous) return false
            if (!synchronize && s.final == null) return true
            if (s.remainingMillis > previous.remainingMillis) return false
        }
        if (!checkpoint.save(s, false)) { waitForRecovery("Could not save recovered match. Retrying…"); return false }
        if (coordinator.state.ownerId == "awaiting-host" || (synchronize && s.sequence == previous.sequence)) coordinator = makeCoordinator(s)
        else if (s.sequence > coordinator.state.sequence) coordinator.acceptRemote(s)
        if (synchronize || s.final != null) {
            val cleared = isHost || checkpoint.saveHostWait(null)
            if (!cleared && s.final == null) { beginRecovery(); return false }
            hostWaitSnapshot = null
            holdNeedsSave = !cleared
            hostDisconnected = false
            coordinator.resumeObservation()
        }
        pendingIntent?.let { if (it.id in s.acceptedActions) pendingIntent = null }
        updateView()
        return true
    }

    private suspend fun received(p: RoomMessagePacket) {
        val s = coordinator.state
        if (!p.type.startsWith("MATCH_V2_") || p.protocolVersion != 2 || p.hostWaitVersion != 1 || p.seed != s.seed || p.matchId != activeMatchId || p.roomCode != s.roomCode || p.playerId !in s.order) return
        if (p.type == "MATCH_V2_BARRIER") {
            if (p.playerId == localUid && p.connectionGeneration == generation && p.requestId == barrier) barrierComplete = true
            return
        }
        if (p.type == "MATCH_V2_STATE") {
            if (p.playerId != s.hostId) return
            val snapshot = p.reliableSnapshot ?: return
            if (!validRetainedSnapshot(snapshot)) return
            if (retained == null || snapshot.sequence > retained!!.sequence) retained = snapshot
            if (snapshot.final != null && !pendingAtLaunch) applySnapshot(snapshot, true)
            else if (!isHost && coordinator.ready) applySnapshot(snapshot, false)
            return
        }
        if (p.playerId == localUid || !transport.healthy) return
        when (p.type) {
            "MATCH_V2_HELLO", "MATCH_V2_SYNC_REQUEST" -> {
                if (p.requestId.isBlank() || p.connectionGeneration.isBlank()) return
                negotiated = true
                lastPeerRecoveryAt = clock.elapsedRealtime()
                if (!isHost && p.playerId == s.hostId) {
                    if (coordinator.ready && peerGeneration != p.connectionGeneration) beginRecovery()
                    transport.publish(packet("SYNC_REQUEST", syncRequest))
                }
                peerGeneration = p.connectionGeneration
                if (isHost && barrierComplete) {
                    if (!coordinator.ready) activateOwner()
                    if (coordinator.ready) {
                        val frozen = p.hostWaitSnapshot
                        if (frozen != null && validSnapshot(frozen) && frozen.final == null &&
                            (coordinator.state.recoveryId.isEmpty() || frozen.remainingMillis < coordinator.remainingMillis())) {
                            if (!coordinator.holdForRecovery(generation, frozen)) { beginRecovery(); return }
                            ownerHoldStartedAt = clock.elapsedRealtime()
                            pendingAcks.clear()
                        }
                        val previous = pendingAcks[p.requestId]
                        if (previous != null && previous.generation == p.connectionGeneration && clock.elapsedRealtime() - previous.sentAt < 500) return
                        val pending = if (previous != null && previous.generation == p.connectionGeneration && clock.elapsedRealtime() - previous.sentAt <= 5000)
                            previous else PendingAck(clock.elapsedRealtime(), p.connectionGeneration, coordinator.sampled())
                        pendingAcks[p.requestId] = pending
                        transport.publish(packet("SYNC", p.requestId, pending.snapshot))
                    }
                }
            }
            "MATCH_V2_SYNC" -> {
                if (isHost || p.playerId != s.hostId || p.requestId != syncRequest || p.connectionGeneration.isBlank()) return
                val snapshot = p.reliableSnapshot ?: return
                val alreadyNewer = coordinator.ready && snapshot.sequence <= coordinator.state.sequence && snapshot.revision <= coordinator.state.revision
                if (alreadyNewer) {
                    if (!sameMatchIdentity(snapshot)) return
                } else if (!applySnapshot(snapshot, false)) return
                if (!coordinator.ready || coordinator.state.recoveryId.isNotEmpty()) awaitingReady = p
                peerGeneration = p.connectionGeneration
                transport.publish(packet("SYNC_ACK", p.requestId, snapshot))
            }
            "MATCH_V2_SYNC_ACK" -> {
                if (!isHost || p.connectionGeneration != peerGeneration) return
                val challenge = pendingAcks[p.requestId] ?: return
                if (challenge.generation != p.connectionGeneration || clock.elapsedRealtime() - challenge.sentAt > 5000) return
                val applied = p.reliableSnapshot ?: return
                if (applied != challenge.snapshot || applied.ownerId != installationId) return
                pendingAcks.remove(p.requestId)
                peerSynchronizedGeneration = p.connectionGeneration
                if (coordinator.state.recoveryId.isNotEmpty()) {
                    if (applied.recoveryId != coordinator.state.recoveryId) return
                    if (!coordinator.resumeAfterRecovery(applied.recoveryId)) { beginRecovery(); return }
                }
                if (!transport.publish(packet("READY", p.requestId, coordinator.sampled()))) { beginRecovery(); return }
                hasAcceptedState = true
                onSynchronized()
                updateView()
            }
            "MATCH_V2_READY" -> {
                val pending = awaitingReady ?: return
                if (isHost || p.playerId != s.hostId || p.requestId != syncRequest || readyRequest == p.requestId ||
                    p.connectionGeneration != pending.connectionGeneration) return
                val snapshot = p.reliableSnapshot ?: return
                if (snapshot.recoveryId.isNotEmpty() || snapshot.sequence < pending.reliableSnapshot!!.sequence) return
                if (!applySnapshot(snapshot, true)) return
                readyRequest = p.requestId
                hasAcceptedState = true
                awaitingReady = null
                retained?.takeIf { it.sequence > snapshot.sequence && it.recoveryId.isEmpty() }?.let { applySnapshot(it, false) }
                onSynchronized()
            }
            "MATCH_V2_INTENT" -> {
                if (!isHost || p.connectionGeneration != peerSynchronizedGeneration) return
                val intent = p.matchIntent ?: return
                if (intent.actorId != p.playerId || intent.matchId != activeMatchId || p.ownerId != installationId || intent.kind == "TIMEOUT" || intent.kind == "CLAIM") return
                val accepted = coordinator.dispatch(intent, transport.healthy)
                if (!coordinator.ready) { beginRecovery(); return }
                transport.publish(packet(if (accepted) "STATE" else "REJECT", p.requestId, coordinator.sampled(), intent))
                updateView()
            }
            "MATCH_V2_REJECT" -> {
                if (isHost || p.playerId != s.hostId || p.matchIntent?.id != pendingIntent?.id) return
                pendingIntent = null
                p.reliableSnapshot?.let { applySnapshot(it, false) }
                mutableView.value = mutableView.value.copy(message = "Match advanced. Choose again on your next turn.")
            }
        }
    }

    private fun updateView(overrideMessage: String = "") {
        val waiting = !coordinator.ready || coordinator.state.recoveryId.isNotEmpty()
        val message = if (waiting) {
            if (isHost) "Synchronizing match; opponent must use the updated app…" else "Waiting for the match host to reconnect…"
        } else ""
        val displayed = if (hostDisconnected) hostWaitSnapshot ?: coordinator.sampled() else coordinator.sampled()
        mutableView.value = ReliableMatchView(displayed, displayed.remainingMillis, waiting,
            overrideMessage.ifEmpty { message }, hostDisconnected)
    }
}
