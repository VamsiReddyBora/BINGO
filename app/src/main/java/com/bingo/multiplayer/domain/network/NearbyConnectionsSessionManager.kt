package com.bingo.multiplayer.domain.network

import androidx.annotation.Keep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Keep
@Serializable
data class NetworkSimulatorConfig(
    val minLatencyMs: Long = 0L,
    val maxLatencyMs: Long = 0L,
    val jitterMs: Long = 0L,
    val packetDropRate: Double = 0.0 // 0.0 to 1.0 (e.g. 0.2 = 20% drop)
)

@Keep
@Serializable
sealed interface NearbyPayload {
    @Keep
    @Serializable
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : NearbyPayload

    @Keep
    @Serializable
    data class Pong(val originalTimestamp: Long, val timestamp: Long = System.currentTimeMillis()) : NearbyPayload

    @Keep
    @Serializable
    data class PickNumber(
        val number: Int,
        val turnNumber: Int,
        val playerId: String,
        val pickedHistory: List<Int>,
        val currentTurnPlayerId: String
    ) : NearbyPayload

    @Keep
    @Serializable
    data class StateSync(
        val turnNumber: Int,
        val currentTurnPlayerId: String,
        val pickedHistory: List<Int>
    ) : NearbyPayload

    @Keep
    @Serializable
    data class SyncRequest(val playerId: String) : NearbyPayload

    @Keep
    @Serializable
    data class Disconnect(val playerId: String, val reason: String = "User left") : NearbyPayload
}

@Keep
sealed interface LostPeerAction {
    data object Pause : LostPeerAction
    data object Forfeit : LostPeerAction
}

@Keep
sealed interface PeerStatusEvent {
    data class Connected(val endpointId: String) : PeerStatusEvent
    data class PeerLost(val endpointId: String, val action: LostPeerAction) : PeerStatusEvent
    data class Reconnected(val endpointId: String) : PeerStatusEvent
    data class Disconnected(val endpointId: String) : PeerStatusEvent
}

@Keep
interface NearbyTransport {
    val connectedEndpoints: StateFlow<Set<String>>
    val incomingPayloads: SharedFlow<Pair<String, NearbyPayload>>
    suspend fun sendPayload(endpointId: String, payload: NearbyPayload): Boolean
    fun setSimulatorConfig(config: NetworkSimulatorConfig)
    fun disconnect()
}

@Keep
class SimulatedNearbyTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : NearbyTransport {
    private var simulatorConfig = NetworkSimulatorConfig()
    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    override val connectedEndpoints: StateFlow<Set<String>> = _connectedEndpoints.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Pair<String, NearbyPayload>>(replay = 1, extraBufferCapacity = 128)
    override val incomingPayloads: SharedFlow<Pair<String, NearbyPayload>> = _incomingPayloads.asSharedFlow()

    var peerTransport: SimulatedNearbyTransport? = null
    var myEndpointId: String = "local_p2p"

    override fun setSimulatorConfig(config: NetworkSimulatorConfig) {
        this.simulatorConfig = config
    }

    fun simulateConnection(remoteEndpointId: String, peer: SimulatedNearbyTransport) {
        peerTransport = peer
        _connectedEndpoints.value = _connectedEndpoints.value + remoteEndpointId
    }

    override suspend fun sendPayload(endpointId: String, payload: NearbyPayload): Boolean {
        val peer = peerTransport ?: return false

        // Simulate packet drop
        if (simulatorConfig.packetDropRate > 0.0) {
            val roll = Random.nextDouble(0.0, 1.0)
            if (roll < simulatorConfig.packetDropRate) {
                // Packet dropped in transit
                return true // Return true as send was attempted over wire
            }
        }

        // Simulate latency and jitter
        var delayTime = 0L
        if (simulatorConfig.maxLatencyMs > 0L) {
            val base = if (simulatorConfig.minLatencyMs < simulatorConfig.maxLatencyMs) {
                Random.nextLong(simulatorConfig.minLatencyMs, simulatorConfig.maxLatencyMs)
            } else {
                simulatorConfig.minLatencyMs
            }
            val jitter = if (simulatorConfig.jitterMs > 0L) {
                Random.nextLong(-simulatorConfig.jitterMs, simulatorConfig.jitterMs)
            } else 0L
            delayTime = (base + jitter).coerceAtLeast(0L)
        }

        scope.launch {
            if (delayTime > 0L) {
                delay(delayTime)
            }
            peer.receivePayload(myEndpointId, payload)
        }
        return true
    }

    fun receivePayload(senderEndpointId: String, payload: NearbyPayload) {
        _incomingPayloads.tryEmit(senderEndpointId to payload)
    }

    override fun disconnect() {
        _connectedEndpoints.value = emptySet()
        peerTransport = null
    }
}

/**
 * Nearby Connections Local P2P Session Manager.
 * Features:
 * - Ping/pong heartbeat monitor for detecting lost peers.
 * - Turn-lock watchdog mechanism preventing deadlocks under latency/packet drops.
 * - Network degradation simulation (jitter, packet drops, 150-500ms artificial latency).
 * - Automatic pause/forfeit event triggering on lost peers.
 */
class NearbyConnectionsSessionManager(
    private val transport: NearbyTransport = SimulatedNearbyTransport(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val heartbeatIntervalMs: Long = 1000L,
    private val lostPeerTimeoutMs: Long = 3500L,
    private val turnLockWatchdogTimeoutMs: Long = 2000L
) {
    private val mutex = Mutex()

    private val lastSeenTimestamps = ConcurrentHashMap<String, Long>()
    private val lastPingRtts = ConcurrentHashMap<String, Long>()

    private val _isTurnLocked = MutableStateFlow(false)
    val isTurnLocked: StateFlow<Boolean> = _isTurnLocked.asStateFlow()

    private val _peerStatusEvents = MutableSharedFlow<PeerStatusEvent>(replay = 1, extraBufferCapacity = 64)
    val peerStatusEvents: SharedFlow<PeerStatusEvent> = _peerStatusEvents.asSharedFlow()

    private val _latestPayloads = MutableSharedFlow<Pair<String, NearbyPayload>>(replay = 1, extraBufferCapacity = 128)
    val latestPayloads: SharedFlow<Pair<String, NearbyPayload>> = _latestPayloads.asSharedFlow()

    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var lockWatchdogJob: Job? = null

    init {
        startListening()
        startHeartbeat()
    }

    fun setNetworkSimulatorConfig(config: NetworkSimulatorConfig) {
        transport.setSimulatorConfig(config)
    }

    /**
     * Locks UI turn while pick is in-flight, protected by a watchdog timer
     * to ensure UI turn-locks NEVER deadlock under network packet drops or latency.
     */
    fun lockTurnWithWatchdog(onWatchdogTimeout: () -> Unit = {}) {
        _isTurnLocked.value = true
        lockWatchdogJob?.cancel()
        lockWatchdogJob = scope.launch {
            delay(turnLockWatchdogTimeoutMs)
            if (_isTurnLocked.value) {
                _isTurnLocked.value = false
                onWatchdogTimeout()
            }
        }
    }

    fun unlockTurn() {
        lockWatchdogJob?.cancel()
        _isTurnLocked.value = false
    }

    suspend fun sendPick(
        endpointId: String,
        number: Int,
        turnNumber: Int,
        playerId: String,
        pickedHistory: List<Int>,
        currentTurnPlayerId: String,
        onWatchdogTimeout: () -> Unit = {}
    ): Boolean {
        lockTurnWithWatchdog(onWatchdogTimeout)
        val payload = NearbyPayload.PickNumber(
            number = number,
            turnNumber = turnNumber,
            playerId = playerId,
            pickedHistory = pickedHistory,
            currentTurnPlayerId = currentTurnPlayerId
        )
        return transport.sendPayload(endpointId, payload)
    }


    suspend fun sendStateSync(
        endpointId: String,
        turnNumber: Int,
        currentTurnPlayerId: String,
        pickedHistory: List<Int>
    ): Boolean {
        val payload = NearbyPayload.StateSync(
            turnNumber = turnNumber,
            currentTurnPlayerId = currentTurnPlayerId,
            pickedHistory = pickedHistory
        )
        return transport.sendPayload(endpointId, payload)
    }

    suspend fun requestSync(endpointId: String, playerId: String): Boolean {
        return transport.sendPayload(endpointId, NearbyPayload.SyncRequest(playerId))
    }

    private fun startListening() {
        scope.launch {
            transport.incomingPayloads.collect { (endpointId, payload) ->
                lastSeenTimestamps[endpointId] = System.currentTimeMillis()

                when (payload) {
                    is NearbyPayload.Ping -> {
                        transport.sendPayload(endpointId, NearbyPayload.Pong(payload.timestamp))
                    }
                    is NearbyPayload.Pong -> {
                        val rtt = System.currentTimeMillis() - payload.originalTimestamp
                        lastPingRtts[endpointId] = rtt
                    }
                    is NearbyPayload.PickNumber -> {
                        unlockTurn()
                        _latestPayloads.tryEmit(endpointId to payload)
                    }
                    is NearbyPayload.StateSync -> {
                        unlockTurn()
                        _latestPayloads.tryEmit(endpointId to payload)
                    }
                    is NearbyPayload.SyncRequest -> {
                        _latestPayloads.tryEmit(endpointId to payload)
                    }
                    is NearbyPayload.Disconnect -> {
                        _peerStatusEvents.tryEmit(PeerStatusEvent.Disconnected(endpointId))
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                val peers = transport.connectedEndpoints.value
                for (peer in peers) {
                    transport.sendPayload(peer, NearbyPayload.Ping())
                }
            }
        }

        // Liveness / Lost peer detector
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val lostPeers = mutableSetOf<String>()
            while (isActive) {
                delay(minOf(heartbeatIntervalMs, 200L))
                val now = System.currentTimeMillis()
                val peers = transport.connectedEndpoints.value

                for (peer in peers) {
                    lastSeenTimestamps.putIfAbsent(peer, now)
                    val lastSeen = lastSeenTimestamps[peer] ?: now
                    if (now - lastSeen > lostPeerTimeoutMs) {
                        if (peer !in lostPeers) {
                            lostPeers.add(peer)
                            val action = if (now - lastSeen > lostPeerTimeoutMs * 2) {
                                LostPeerAction.Forfeit
                            } else {
                                LostPeerAction.Pause
                            }
                            _peerStatusEvents.tryEmit(PeerStatusEvent.PeerLost(peer, action))
                        }
                    } else {
                        if (peer in lostPeers) {
                            lostPeers.remove(peer)
                            _peerStatusEvents.tryEmit(PeerStatusEvent.Reconnected(peer))
                        }
                    }
                }
            }
        }
    }

    fun getPingRtt(endpointId: String): Long? = lastPingRtts[endpointId]

    fun disconnect() {
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        lockWatchdogJob?.cancel()
        heartbeatJob = null
        watchdogJob = null
        lockWatchdogJob = null
        transport.disconnect()
        lastSeenTimestamps.clear()
        lastPingRtts.clear()
        _isTurnLocked.value = false
    }
}
