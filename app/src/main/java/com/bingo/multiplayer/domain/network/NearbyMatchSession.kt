package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/** Small LAN recovery marker; no authentication, cloud storage or controller election. */
@Serializable
data class NearbyMatchControl(
    val epoch: Long = 0L,
    val hostHeld: Boolean = false,
    val initialTurnSeconds: Int = 30,
    val welcomeBackGranted: Boolean = false
)

internal data class NearbyMatchAvailability(
    val localForeground: Boolean = true,
    val hostUnavailable: Boolean = false,
    val awaitingSnapshot: Boolean = false,
    val epoch: Long = 0L,
    val requestId: String = ""
) {
    val suspended: Boolean get() = !localForeground || hostUnavailable || awaitingSnapshot
}

/** Used by the real lifecycle, timer, packet and board callbacks. Mutated on the UI dispatcher. */
internal class NearbyMatchSession {
    private var seed = 0L
    private var localId = ""
    private var hostId = ""
    private var snapshotSequence = 0L
    var turnClock: MultiplayerTurnClock? = null
        private set
    private val _availability = MutableStateFlow(NearbyMatchAvailability())
    val availability = _availability.asStateFlow()
    val state get() = _availability.value
    val isHost get() = localId.isNotBlank() && localId == hostId

    fun bind(seed: Long, localId: String, hostId: String) {
        if (this.seed == seed && this.localId == localId && this.hostId == hostId) return
        this.seed = seed; this.localId = localId; this.hostId = hostId
        snapshotSequence = 0L
        turnClock = null
        _availability.value = NearbyMatchAvailability()
    }

    fun background(now: Long = System.currentTimeMillis()) {
        turnClock = turnClock?.freeze(now)
        if (!state.localForeground) return
        _availability.value = state.copy(localForeground = false,
            hostUnavailable = state.hostUnavailable || isHost,
            awaitingSnapshot = !isHost,
            epoch = state.epoch + if (isHost) 1 else 0)
    }

    fun foreground(now: Long = System.currentTimeMillis()): String {
        if (isHost) {
            _availability.value = state.copy(localForeground = true, hostUnavailable = false,
                awaitingSnapshot = false, epoch = state.epoch + if (!state.localForeground) 1 else 0)
            turnClock = turnClock?.resume(now)
            return ""
        }
        _availability.value = state.copy(localForeground = true)
        return requestSnapshot()
    }

    fun requestSnapshot(): String {
        if (isHost || seed == 0L) return ""
        val request = UUID.randomUUID().toString()
        _availability.value = state.copy(awaitingSnapshot = true, requestId = request)
        return request
    }

    fun transportLost(now: Long = System.currentTimeMillis()) {
        turnClock = turnClock?.freeze(now)
        if (!isHost) _availability.value = state.copy(hostUnavailable = true,
            awaitingSnapshot = true, requestId = UUID.randomUUID().toString())
    }

    fun hostUnresponsive() = transportLost()

    fun bindTurn(turn: Int, playerId: String, absent: Boolean, now: Long = System.currentTimeMillis()) {
        val old = turnClock
        if (old?.turnNumber == turn && old.playerId == playerId) return
        turnClock = MultiplayerTurnClock.start(turn, playerId, now, absent).let {
            if (state.suspended) it.freeze(now) else it
        }
    }

    fun remainingMillis(now: Long, pickerOnline: Boolean, manuallyPaused: Boolean): Long {
        var next = turnClock ?: return 30_000L
        next = if (state.suspended || manuallyPaused) next.freeze(now) else next.resume(now)
        if (isHost && !state.suspended && !manuallyPaused && pickerOnline) next = next.welcomeBack(now)
        turnClock = next
        return next.remainingMillis(now)
    }

    fun prepareOutgoing(packet: RoomMessagePacket): RoomMessagePacket {
        if (packet.seed != seed || seed == 0L) return packet
        val snapshot = isHost && packet.type in setOf("GAME_SYNC", "HOST_HOLD", "HOST_RESUMED")
        return packet.copy(controllerEpoch = state.epoch,
            sequence = if (snapshot) ++snapshotSequence else packet.sequence,
            nearbyControl = if (snapshot) NearbyMatchControl(state.epoch, state.hostUnavailable,
                ((turnClock?.initialBudgetMillis ?: 30_000L) / 1000L).toInt(),
                turnClock?.welcomeBackGranted == true) else packet.nearbyControl)
    }

    fun normalizePacket(packet: RoomMessagePacket): RoomMessagePacket =
        if (packet.type in setOf("HOST_HOLD", "HOST_RESUMED")) packet.copy(type = "GAME_SYNC") else packet

    fun canApplySnapshot(packet: RoomMessagePacket): Boolean {
        val control = packet.nearbyControl ?: return false
        if (isHost || packet.seed != seed || seed == 0L || packet.playerId != hostId ||
            MultiplayerGameplayReducer.sender(packet) != hostId || packet.type != "GAME_SYNC" ||
            control.epoch != packet.controllerEpoch || control.epoch < state.epoch || control.initialTurnSeconds !in setOf(5, 30) ||
            packet.remainingTurnMillis !in 0L..30_000L || packet.turnNumber < 1) return false
        // A queued snapshot from before reconnect cannot release the reconnect barrier.
        if (!control.hostHeld && state.awaitingSnapshot && control.epoch == state.epoch &&
            packet.requestId != state.requestId) return false
        return true
    }

    /** Call only after MultiplayerSnapshotReconciler accepted the complete snapshot. */
    fun snapshotApplied(packet: RoomMessagePacket, now: Long = System.currentTimeMillis(), preservePendingTurn: Boolean = false) {
        val control = packet.nearbyControl ?: return
        // A pre-pick snapshot can coexist with an optimistic next-turn board until the host confirms it.
        // Recovery/hold snapshots discard pending picks through the real reconciler before calling here.
        if (!preservePendingTurn) turnClock = MultiplayerTurnClock.restore(packet.turnNumber, packet.currentTurnPlayerId, now,
            packet.remainingTurnMillis, control.initialTurnSeconds == 5).copy(welcomeBackGranted = control.welcomeBackGranted).let {
            if (control.hostHeld || packet.isPaused || !state.localForeground) it.freeze(now) else it
        }
        _availability.value = state.copy(epoch = control.epoch, hostUnavailable = control.hostHeld,
            awaitingSnapshot = false, requestId = "")
    }

    fun canAcceptGameplay(packet: RoomMessagePacket): Boolean = !state.suspended &&
        packet.seed == seed && packet.controllerEpoch == state.epoch &&
        (packet.type != "TURN_TIMEOUT" || MultiplayerGameplayReducer.sender(packet) == hostId)

    fun canPublishTimeout(): Boolean = isHost && !state.suspended
}
