package com.bingo.multiplayer.domain.network

import android.util.Log
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class LanP2pSessionManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile private var sessionGeneration = 0L
    private val _connectionReady = MutableStateFlow(false)
    val connectionReady: StateFlow<Boolean> = _connectionReady.asStateFlow()
    private var reconnectHostIp = ""
    private var reconnectPort = 8999
    private var reconnectFallbackIp = ""

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outWriter: PrintWriter? = null
    private var inReader: BufferedReader? = null

    private val clientWriters = java.util.concurrent.CopyOnWriteArrayList<PrintWriter>()
    private val clientSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val clientJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private val socketToPlayerId = ConcurrentHashMap<Socket, String>()

    private var serverJob: Job? = null
    private var clientReadJob: Job? = null
    private var clientConnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null

    var localPlayer: Player? = null
    var isHostInLobby = false

    private val playerRegistry = ConcurrentHashMap<String, Player>()
    private val kickedPlayerIds = ConcurrentHashMap.newKeySet<String>()
    private val appPresenceStates = ConcurrentHashMap<String, String>()
    private val silentPlayerIds = ConcurrentHashMap.newKeySet<String>()
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _measuredPingMs = MutableStateFlow<Long>(0L)
    val measuredPingMs: StateFlow<Long> = _measuredPingMs.asStateFlow()

    private val _lastPingTimestampEpochMs = MutableStateFlow<Long>(0L)
    val lastPingTimestampEpochMs: StateFlow<Long> = _lastPingTimestampEpochMs.asStateFlow()

    val isPingFresh: Boolean
        get() = _measuredPingMs.value > 0L && (System.currentTimeMillis() - _lastPingTimestampEpochMs.value) < NetworkStatusMapper.FRESHNESS_WINDOW_MS

    val boundPort: Int
        get() = serverSocket?.localPort?.takeIf { it > 0 } ?: 8999

    private val _incomingPackets = MutableSharedFlow<RoomMessagePacket>(replay = 1, extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<RoomMessagePacket> = _incomingPackets.asSharedFlow()

    private val lastDirectHeartbeatTimestamps = ConcurrentHashMap<String, Long>()

    fun recordDirectHeartbeat(playerId: String, username: String = "") {
        if (playerId.isBlank()) return
        val now = System.currentTimeMillis()
        val cleanId = playerId.trim().lowercase().removePrefix("u_")
        lastDirectHeartbeatTimestamps[playerId] = now
        lastDirectHeartbeatTimestamps[cleanId] = now
        if (username.isNotBlank()) {
            val cleanUser = username.trim().lowercase().removePrefix("@")
            lastDirectHeartbeatTimestamps[cleanUser] = now
        }
    }

    fun getLastDirectHeartbeat(playerId: String): Long {
        if (playerId.isBlank()) return 0L
        val clean = playerId.trim().lowercase().removePrefix("u_")
        val ts = lastDirectHeartbeatTimestamps[playerId]
            ?: lastDirectHeartbeatTimestamps[clean]
        if (ts != null && ts > 0L) return ts
        playerRegistry.values.firstOrNull { p ->
            val pClean = p.id.trim().lowercase().removePrefix("u_")
            val pUser = p.username.trim().lowercase().removePrefix("@")
            p.id == playerId || pClean == clean || (pUser.isNotBlank() && pUser == clean)
        }?.let { matched ->
            return lastDirectHeartbeatTimestamps[matched.id]
                ?: lastDirectHeartbeatTimestamps[matched.username.trim().lowercase().removePrefix("@")]
                ?: 0L
        }
        return 0L
    }

    fun resetInGameHeartbeats(participantIds: Collection<String>) {
        participantIds.forEach { id ->
            recordDirectHeartbeat(id)
        }
    }

    private data class Outgoing(val generation: Long, val packet: RoomMessagePacket, val written: CompletableDeferred<Unit>? = null)
    private var outgoingJob: Job? = null
    private val outgoingPackets = Channel<Outgoing>(Channel.UNLIMITED)

    init {
        activeInstance = this
        outgoingJob = scope.launch(Dispatchers.IO) {
            for ((generation, packet, written) in outgoingPackets) {
                if (generation == sessionGeneration) writePacket(generation, packet)
                written?.complete(Unit)
            }
        }
    }

    fun updateLocalAvatar(newAvatarUrl: String?) {
        val p = localPlayer ?: return
        val updated = p.copy(avatarUrl = newAvatarUrl)
        localPlayer = updated
        playerRegistry[p.id] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        broadcastPacket(
            RoomMessagePacket(
                type = "HEARTBEAT",
                playerId = updated.id,
                displayName = updated.displayName,
                username = updated.username,
                isHost = updated.isHost,
                avatarUrl = updated.avatarUrl,
                gamesPlayed = updated.gamesPlayed,
                gamesWon = updated.gamesWon,
                currentStreak = updated.currentStreak,
                level = updated.level,
                timestamp = System.currentTimeMillis(),
                readyStatus = updated.lobbyReadyStatus,
                readyVersion = updated.readyVersion
            )
        )
    }

    fun onRemoteAvatarUpdated(username: String, avatarUrl: String?) {
        val clean = username.trim().lowercase().removePrefix("@")
        val found = playerRegistry.values.find { it.username.trim().lowercase().removePrefix("@") == clean }
        if (found != null && found.avatarUrl != avatarUrl) {
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(clean)
            if (!avatarUrl.isNullOrBlank()) {
                val bmp = com.bingo.multiplayer.presentation.common.decodeAvatarBitmap(avatarUrl, null)
                if (bmp != null) {
                    com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:$clean", bmp.asImageBitmap())
                }
            }
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(clean)
            playerRegistry[found.id] = found.copy(avatarUrl = avatarUrl)
            _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        }
    }

    fun connectAsHost(hostPlayer: Player, port: Int = 8999) {
        disconnect()
        localPlayer = hostPlayer
        val generation = sessionGeneration
        isHostInLobby = false
        playerRegistry[hostPlayer.id] = hostPlayer
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                for (candidatePort in port..(port + 5)) {
                    try {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(java.net.InetSocketAddress(candidatePort))
                        }
                        break
                    } catch (e: java.net.BindException) {
                        if (candidatePort == port + 5) throw e
                    }
                }
                _connectionReady.value = true
                while (isActive && sessionGeneration == generation) {
                    val socket = serverSocket?.accept() ?: break
                    socket.tcpNoDelay = true
                    try { socket.trafficClass = 0x10 } catch (_: Exception) {}
                    clientSockets.add(socket)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    clientWriters.add(writer)

                    // Transmit host info and existing players immediately to the newly connected client
                    try {
                        val hostHeartbeat = RoomMessagePacket(
                            type = "HEARTBEAT",
                            playerId = hostPlayer.id,
                            displayName = hostPlayer.displayName,
                            username = hostPlayer.username,
                            isHost = true,
                            avatarUrl = hostPlayer.avatarUrl,
                            gamesPlayed = hostPlayer.gamesPlayed,
                            gamesWon = hostPlayer.gamesWon,
                            currentStreak = hostPlayer.currentStreak,
                            level = hostPlayer.level,
                            readyStatus = hostPlayer.lobbyReadyStatus,
                            timestamp = System.currentTimeMillis()
                        )
                        writer.println(json.encodeToString(hostHeartbeat))

                        for (peer in playerRegistry.values) {
                            if (peer.id != hostPlayer.id) {
                                val peerPacket = RoomMessagePacket(
                                    type = "HEARTBEAT",
                                    playerId = peer.id,
                                    displayName = peer.displayName,
                                    username = peer.username,
                                    isHost = peer.isHost,
                                    avatarUrl = peer.avatarUrl,
                                    gamesPlayed = peer.gamesPlayed,
                                    gamesWon = peer.gamesWon,
                                    currentStreak = peer.currentStreak,
                                    level = peer.level,
                                    readyStatus = peer.lobbyReadyStatus,
                                    timestamp = System.currentTimeMillis()
                                )
                                writer.println(json.encodeToString(peerPacket))
                            }
                        }
                    } catch (_: Exception) {}

                    if (isHostInLobby) {
                        try {
                            val goToLobbyPacket = RoomMessagePacket(
                                type = "GO_TO_LOBBY",
                                playerId = hostPlayer.id
                            )
                            writer.println(json.encodeToString(goToLobbyPacket))
                        } catch (_: Exception) {}
                    }

                    val job = scope.launch(Dispatchers.IO) {
                        try {
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            while (isActive && sessionGeneration == generation) {
                                val line = reader.readLine() ?: break
                                val packet = json.decodeFromString<RoomMessagePacket>(line)
                                val senderId = packet.senderPlayerId.takeIf { it.isNotBlank() } ?: packet.playerId
                                val isHostId = (hostPlayer != null && (senderId == hostPlayer.id || packet.playerId == hostPlayer.id))
                                if (packet.type == "JOIN" && senderId.isNotBlank() && !isHostId) {
                                    socketToPlayerId[socket] = senderId
                                } else if (!socketToPlayerId.containsKey(socket) && senderId.isNotBlank() && !isHostId && packet.type != "PONG" && packet.type != "PING") {
                                    socketToPlayerId[socket] = senderId
                                }
                                handleIncomingPacket(packet)

                                // Host relays to all other connected clients
                                val payload = json.encodeToString(packet)
                                for (otherWriter in clientWriters) {
                                    if (otherWriter !== writer) {
                                        try { otherWriter.println(payload) } catch (_: Exception) {}
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("LanP2p", "Client socket disconnected: ${e.message}")
                        } finally {
                            val leavingPlayerId = socketToPlayerId.remove(socket)
                            clientWriters.remove(writer)
                            clientSockets.remove(socket)
                            try { socket.close() } catch (_: Exception) {}
                            val hasReplacementSocket = socketToPlayerId.values.any { it == leavingPlayerId }
                            if (sessionGeneration == generation && !hasReplacementSocket && !leavingPlayerId.isNullOrBlank() &&
                                appPresenceStates[leavingPlayerId] != "LEFT") {
                                // An EOF is transport loss. Only an explicit LEAVE/SURRENDER is permanent.
                                val leavePacket = RoomMessagePacket(
                                    type = "PLAYER_PRESENCE",
                                    playerId = leavingPlayerId,
                                    readyStatus = "OFFLINE",
                                    timestamp = System.currentTimeMillis()
                                )
                                handleIncomingPacket(leavePacket)
                                val leavePayload = json.encodeToString(leavePacket)
                                for (otherWriter in clientWriters) {
                                    try { otherWriter.println(leavePayload) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    clientJobs.add(job)
                }
            } catch (e: Exception) {
                Log.e("LanP2p", "Host server error", e)
            }
        }
        startHeartbeat()
    }

    fun connectAsClient(hostIp: String, clientPlayer: Player, port: Int = 8999, fallbackIp: String = "") {
        disconnect()
        localPlayer = clientPlayer
        isHostInLobby = false
        playerRegistry[clientPlayer.id] = clientPlayer
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

        reconnectHostIp = hostIp
        reconnectPort = port
        reconnectFallbackIp = fallbackIp
        startClientConnectionLoop(sessionGeneration)
        startHeartbeat()
    }

    /** Retain the same room/player and retry sockets; reconnect is never a lobby departure. */
    private fun startClientConnectionLoop(generation: Long) {
        clientConnectJob = scope.launch(Dispatchers.IO) {
            val gateway = HotspotAndWifiManager.getLocalIpAddress().takeIf { it.contains('.') }
                ?.substringBeforeLast('.')?.plus(".1").orEmpty()
            val candidateIps = (listOf(reconnectHostIp, reconnectFallbackIp, gateway) + listOf(
                "192.168.43.1", "192.168.44.1", "192.168.49.1", "192.168.50.1",
                "192.168.125.1", "192.168.137.1", "172.20.10.1"
            )).filter { it.isNotBlank() }.distinct()
            while (isActive && generation == sessionGeneration && localPlayer?.isHost == false) {
                var socket: Socket? = null
                for (targetIp in candidateIps) {
                    if (!isActive || generation != sessionGeneration) break
                    val candidate = Socket()
                    try {
                        candidate.connect(java.net.InetSocketAddress(targetIp, reconnectPort), 1200)
                        candidate.tcpNoDelay = true
                        socket = candidate
                        break
                    } catch (_: Exception) { try { candidate.close() } catch (_: Exception) {} }
                }
                if (socket == null) { delay(750L); continue }
                if (generation != sessionGeneration) { socket.close(); break }
                clientSocket = socket
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    outWriter = writer
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    inReader = reader
                    _connectionReady.value = true
                    sendJoinPacket()
                    while (isActive && generation == sessionGeneration) {
                        val line = reader.readLine() ?: break
                        handleIncomingPacket(json.decodeFromString<RoomMessagePacket>(line))
                    }
                } catch (e: Exception) {
                    if (generation == sessionGeneration) Log.w("LanP2p", "Host socket interrupted: ${e.message}")
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                    if (generation == sessionGeneration && clientSocket === socket) {
                        _connectionReady.value = false
                        clientSocket = null; outWriter = null; inReader = null
                        _incomingPackets.tryEmit(RoomMessagePacket(type = "NEARBY_TRANSPORT_LOST", transportOnly = true))
                    }
                }
                delay(750L)
            }
        }
    }

    fun onForegroundResume() {
        // Break a half-open socket; the existing connection loop reconnects automatically.
        if (localPlayer?.isHost == false && !isPingFresh) {
            try { clientSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
                if (!AppLifecycleObserver.isAppInForeground.value) {
                    continue
                }
                localPlayer?.let { p ->
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "HEARTBEAT",
                            playerId = p.id,
                            displayName = p.displayName,
                            username = p.username,
                            isHost = p.isHost,
                            avatarUrl = p.avatarUrl,
                            gamesPlayed = p.gamesPlayed,
                            gamesWon = p.gamesWon,
                            currentStreak = p.currentStreak,
                            level = p.level,
                            readyStatus = p.lobbyReadyStatus,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "PING",
                            playerId = p.id,
                            pingTimestamp = System.currentTimeMillis()
                        )
                    )
                    playerRegistry[p.id] = p.copy(lastSeenTimestamp = System.currentTimeMillis())
                }
            }
        }
        livenessJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L)
                checkPeerLiveness()
                _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            }
        }
    }

    /** Existing heartbeat cadence; silence changes presence, never match eligibility. */
    internal fun checkPeerLiveness(now: Long = System.currentTimeMillis()) {
        for (peer in playerRegistry.values) {
            if (peer.id == localPlayer?.id || appPresenceStates[peer.id] in setOf("AWAY", "LEFT")) continue
            val lastSeen = getLastDirectHeartbeat(peer.id)
            if (lastSeen <= 0L || now - lastSeen < 15_000L || !silentPlayerIds.add(peer.id)) continue
            appPresenceStates[peer.id] = "OFFLINE"
            _incomingPackets.tryEmit(RoomMessagePacket(type = "PLAYER_PRESENCE", playerId = peer.id,
                displayName = peer.displayName, readyStatus = "OFFLINE", timestamp = now))
            if (peer.isHost && localPlayer?.isHost == false && AppLifecycleObserver.isAppInForeground.value) {
                try { clientSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendJoinPacket() {
        val p = localPlayer ?: return
        broadcastPacket(
            RoomMessagePacket(
                type = "JOIN",
                presenceStatus = if (AppLifecycleObserver.isAppInForeground.value) "ONLINE" else "AWAY",
                playerId = p.id,
                displayName = p.displayName,
                username = p.username,
                isHost = p.isHost,
                avatarUrl = p.avatarUrl,
                gamesPlayed = p.gamesPlayed,
                gamesWon = p.gamesWon,
                currentStreak = p.currentStreak,
                level = p.level,
                readyStatus = p.lobbyReadyStatus,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun broadcastPacket(packet: RoomMessagePacket) {
        outgoingPackets.trySend(Outgoing(sessionGeneration, packet))
    }

    /** Flush a voluntary exit after already queued moves, without using an Internet service. */
    suspend fun sendBeforeDisconnect(packet: RoomMessagePacket) {
        val written = CompletableDeferred<Unit>()
        outgoingPackets.send(Outgoing(sessionGeneration, packet, written))
        withTimeoutOrNull(2_000L) { written.await() }
    }

    private fun writePacket(generation: Long, packet: RoomMessagePacket) {
        val clientWriter = outWriter
        val serverWriters = clientWriters.toList()
        if (generation != sessionGeneration) return
        try {
            val payload = json.encodeToString(packet)
            clientWriter?.let { writer ->
                writer.println(payload)
                if (writer.checkError()) try { clientSocket?.close() } catch (_: Exception) {}
            }
            for (writer in serverWriters) {
                try { writer.println(payload) } catch (_: Exception) {}
            }
            if (generation == sessionGeneration && packet.type in setOf("GAME_SYNC", "HEARTBEAT", "JOIN", "LEAVE")) handleIncomingPacket(packet)
        } catch (e: Exception) {
            Log.e("LanP2p", "Broadcast error", e)
        }
    }

    internal fun handleIncomingPacket(packet: RoomMessagePacket) {
        val pCleanUser = packet.username.trim().lowercase().removePrefix("@")
        val pCleanDisplay = packet.displayName.trim().lowercase()
        val isSenderKicked = packet.playerId in kickedPlayerIds ||
                (pCleanUser.isNotBlank() && pCleanUser in kickedPlayerIds) ||
                (pCleanDisplay.isNotBlank() && pCleanDisplay in kickedPlayerIds)

        if (isSenderKicked) {
            if (localPlayer?.isHost == true) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "KICK_PLAYER",
                        targetPlayerId = packet.playerId,
                        playerId = localPlayer?.id ?: ""
                    )
                )
            }
            return
        }

        if (packet.playerId.isNotBlank()) {
            when (packet.type) {
                "PLAYER_PRESENCE" -> {
                    appPresenceStates[packet.playerId] = packet.readyStatus
                    if (packet.readyStatus == "OFFLINE") silentPlayerIds.add(packet.playerId)
                    else silentPlayerIds.remove(packet.playerId)
                }
                "JOIN" -> {
                    appPresenceStates[packet.playerId] = packet.presenceStatus.ifBlank { "ONLINE" }
                    silentPlayerIds.remove(packet.playerId)
                }
                "LEAVE", "HOST_LEFT", "SURRENDER" -> appPresenceStates[packet.playerId] = "LEFT"
                "HEARTBEAT" -> if (silentPlayerIds.remove(packet.playerId)) {
                    appPresenceStates[packet.playerId] = "ONLINE"
                    _incomingPackets.tryEmit(packet.copy(type = "PLAYER_PRESENCE", readyStatus = "ONLINE"))
                }
            }
        }

        // Live Heartbeat/Packet Touch: Any incoming packet confirms player is actively communicating
        val isDepartOrTimeout = packet.type in listOf("TURN_TIMEOUT", "LEAVE", "HOST_LEFT", "SURRENDER", "KICK_PLAYER")
        if (!isDepartOrTimeout && packet.playerId.isNotBlank() && packet.playerId != localPlayer?.id) {
            val now = System.currentTimeMillis()
            recordDirectHeartbeat(packet.playerId, packet.username)
            val clean = packet.playerId.trim().lowercase().removePrefix("u_")
            val pCleanU = packet.username.trim().lowercase().removePrefix("@")
            playerRegistry.entries.forEach { (k, v) ->
                val cleanK = k.trim().lowercase().removePrefix("u_")
                val cleanUser = v.username.trim().lowercase().removePrefix("@")
                if (k == packet.playerId || cleanK == clean || (pCleanU.isNotBlank() && (cleanUser == pCleanU || cleanK == pCleanU))) {
                    playerRegistry[k] = v.copy(lastSeenTimestamp = now)
                }
            }
        }

        when (packet.type) {
            "JOIN", "HEARTBEAT" -> {
                if (packet.playerId.isNotEmpty()) {
                    val existing = playerRegistry[packet.playerId]
                    val isLocal = packet.playerId == localPlayer?.id

                    val effectiveAvatar = when {
                        isLocal -> localPlayer?.avatarUrl?.takeIf { it.isNotBlank() } ?: existing?.avatarUrl
                        !packet.avatarUrl.isNullOrBlank() -> packet.avatarUrl
                        else -> existing?.avatarUrl
                    }
                    val effectiveUsername = when {
                        isLocal -> localPlayer?.username?.takeIf { it.isNotBlank() } ?: existing?.username ?: ""
                        packet.username.isNotBlank() -> packet.username
                        else -> existing?.username ?: ""
                    }
                    if (!effectiveAvatar.isNullOrBlank() && effectiveAvatar != existing?.avatarUrl && !isLocal) {
                        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(effectiveUsername)
                        val bmp = com.bingo.multiplayer.presentation.common.decodeAvatarBitmap(effectiveAvatar, null)
                        if (bmp != null) {
                            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:${effectiveUsername.trim().lowercase().removePrefix("@")}", bmp.asImageBitmap())
                        }
                        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(effectiveUsername)
                    }
                    val effectiveDisplayName = when {
                        isLocal -> localPlayer?.displayName?.takeIf { it.isNotBlank() } ?: existing?.displayName ?: packet.displayName
                        packet.displayName.isNotBlank() -> packet.displayName
                        else -> existing?.displayName ?: "Player"
                    }
                    val effectiveGamesPlayed = if (packet.gamesPlayed > 0) packet.gamesPlayed else (existing?.gamesPlayed ?: 0)
                    val effectiveGamesWon = if (packet.gamesWon > 0) packet.gamesWon else (existing?.gamesWon ?: 0)
                    val effectiveStreak = if (packet.currentStreak > 0) packet.currentStreak else (existing?.currentStreak ?: 0)
                    val effectiveLevel = if (packet.level > 1) packet.level else (existing?.level ?: 1)

                    val effectiveReady = if (packet.readyStatus.isNotBlank()) {
                        packet.readyStatus
                    } else {
                        existing?.lobbyReadyStatus ?: if (packet.isHost) "READY" else "NOT_READY"
                    }

                    val updated = Player(
                        id = packet.playerId,
                        displayName = effectiveDisplayName,
                        username = effectiveUsername,
                        isHost = packet.isHost,
                        avatarUrl = effectiveAvatar,
                        gamesPlayed = effectiveGamesPlayed,
                        gamesWon = effectiveGamesWon,
                        currentStreak = effectiveStreak,
                        level = effectiveLevel,
                        lobbyReadyStatus = effectiveReady,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    playerRegistry[packet.playerId] = updated
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    if (packet.type == "JOIN" && !isLocal) {
                        _incomingPackets.tryEmit(packet.copy(type = "PLAYER_PRESENCE",
                            readyStatus = packet.presenceStatus.ifBlank { "ONLINE" }))
                    }

                    if (packet.type == "JOIN" && localPlayer?.isHost == true && packet.playerId != localPlayer?.id) {
                        localPlayer?.let { h ->
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "HEARTBEAT",
                                    playerId = h.id,
                                    displayName = h.displayName,
                                    username = h.username,
                                    isHost = true,
                                    avatarUrl = h.avatarUrl,
                                    gamesPlayed = h.gamesPlayed,
                                    gamesWon = h.gamesWon,
                                    currentStreak = h.currentStreak,
                                    level = h.level,
                                    readyStatus = h.lobbyReadyStatus,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                        // If host is already in lobby, immediately inform the new client
                        if (isHostInLobby) {
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "GO_TO_LOBBY",
                                    playerId = localPlayer?.id ?: ""
                                )
                            )
                        }
                    }
                }
            }

            "READY_STATUS" -> {
                if (packet.playerId.isNotEmpty()) {
                    val existing = playerRegistry[packet.playerId]
                        ?: playerRegistry.values.find {
                            (packet.username.isNotBlank() && it.username.equals(packet.username, ignoreCase = true)) ||
                            (packet.displayName.isNotBlank() && it.displayName.equals(packet.displayName, ignoreCase = true))
                        }
                    val updated = LobbyLifecycleEngine.onRemoteReadyStatusPacket(packet, existing)
                    if (updated != null) {
                        playerRegistry[updated.id] = updated
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    }
                }
            }

            "KICK_PLAYER" -> {
                val expectedHost = playerRegistry.values.firstOrNull { it.isHost }?.id ?: localPlayer?.takeIf { it.isHost }?.id
                val senderId = packet.senderPlayerId.ifBlank { packet.playerId }
                if (expectedHost.isNullOrBlank() || packet.playerId != expectedHost || (senderId.isNotBlank() && senderId != expectedHost)) {
                    Log.w("LanP2p", "Rejected unauthorized Nearby KICK_PLAYER from ${packet.playerId} (expected host: $expectedHost)")
                    return
                }
                val targetId = packet.targetPlayerId.ifBlank { packet.playerId }
                if (targetId.isNotEmpty()) {
                    playerRegistry.remove(targetId)
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                }
            }

            "LEAVE" -> {
                val senderId = packet.senderPlayerId.ifBlank { packet.playerId }
                if (senderId.isNotBlank() && senderId != packet.playerId) {
                    Log.w("LanP2p", "Rejected spoofed Nearby LEAVE packet: sender $senderId != actor ${packet.playerId}")
                    return
                }
                if (packet.playerId.isNotEmpty()) {
                    val existing = playerRegistry[packet.playerId]
                    if (existing != null) {
                        if (existing.isHost) {
                            val expectedHost = playerRegistry.values.firstOrNull { it.isHost }?.id ?: localPlayer?.takeIf { it.isHost }?.id
                            if (packet.playerId != expectedHost || (senderId.isNotBlank() && senderId != expectedHost)) {
                                Log.w("LanP2p", "Rejected unauthorized Nearby host LEAVE from ${packet.playerId}")
                                return
                            }
                            playerRegistry.remove(packet.playerId)
                        } else {
                            playerRegistry[packet.playerId] = LobbyLifecycleEngine.onPlayerLeave(existing)
                        }
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    }
                }
            }

            "PING" -> {
                if (packet.playerId != localPlayer?.id && packet.pingTimestamp > 0L) {
                    val myId = localPlayer?.id ?: ""
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "PONG",
                            playerId = myId,
                            senderPlayerId = myId,
                            targetPlayerId = packet.playerId,
                            pingTimestamp = packet.pingTimestamp
                        )
                    )
                }
            }

            "PONG" -> {
                val myId = localPlayer?.id ?: ""
                val isForMe = (packet.targetPlayerId == myId) || (packet.targetPlayerId.isBlank() && packet.playerId == myId)
                if (isForMe && packet.pingTimestamp > 0L) {
                    val now = System.currentTimeMillis()
                    val rtt = (now - packet.pingTimestamp).coerceAtLeast(1L)
                    val cur = _measuredPingMs.value
                    val smoothed = if (cur <= 0L) rtt else ((cur * 0.60) + (rtt * 0.40)).toLong().coerceAtLeast(1L)
                    _measuredPingMs.value = smoothed
                    _lastPingTimestampEpochMs.value = now
                }
            }
        }

        // Only deliver gameplay and room lifecycle packets to listeners (ignore self packets as logic handles it already or we don't want duplicates)
        if (packet.type != "HEARTBEAT" && packet.type != "JOIN" && packet.type != "READY_STATUS" && packet.type != "PING" && packet.type != "PONG") {
            _incomingPackets.tryEmit(packet)
        }
    }

    fun updateLocalReadyStatus(status: String) {
        val p = localPlayer ?: return
        val updated = LobbyLifecycleEngine.onLocalStatusChange(p, status)
        localPlayer = updated
        playerRegistry[p.id] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        broadcastPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = p.id,
                readyStatus = updated.lobbyReadyStatus,
                readyVersion = updated.readyVersion,
                username = p.username,
                displayName = p.displayName
            )
        )
    }

    fun updatePlayerReadyStatus(playerId: String, status: String) {
        val existing = playerRegistry[playerId] ?: return
        val updated = LobbyLifecycleEngine.onLocalStatusChange(existing, status)
        playerRegistry[playerId] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
    }

    fun removePlayer(playerId: String) {
        val targetPlayer = playerRegistry[playerId]
        kickedPlayerIds.add(playerId)
        if (targetPlayer != null && targetPlayer.username.isNotBlank()) {
            kickedPlayerIds.add(targetPlayer.username.trim().lowercase().removePrefix("@"))
        }
        playerRegistry.remove(playerId)
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        scope.launch(Dispatchers.IO) {
            val kickPacket = RoomMessagePacket(
                type = "KICK_PLAYER",
                targetPlayerId = playerId,
                playerId = localPlayer?.id ?: ""
            )
            broadcastPacket(kickPacket)
            delay(150L)
            broadcastPacket(kickPacket)
            delay(300L)
            broadcastPacket(kickPacket)
        }
    }

    fun disconnect() {
        sessionGeneration += 1L
        _connectionReady.value = false
        val p = localPlayer
        if (p != null) {
            val packet = RoomMessagePacket(
                type = if (p.isHost) "HOST_LEFT" else "LEAVE",
                playerId = p.id,
                timestamp = System.currentTimeMillis()
            )
            try {
                val payload = json.encodeToString(packet)
                outWriter?.println(payload)
                outWriter?.flush()
                for (w in clientWriters) {
                    try {
                        w.println(payload)
                        w.flush()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        heartbeatJob?.cancel()
        livenessJob?.cancel()
        clientReadJob?.cancel()
        clientConnectJob?.cancel()
        serverJob?.cancel()
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()

        heartbeatJob = null
        livenessJob = null
        clientReadJob = null
        clientConnectJob = null
        serverJob = null

        // Close the socket before its reader: readLine may hold the reader's lock.
        try { clientSocket?.close() } catch (_: Exception) {}
        try { outWriter?.close() } catch (_: Exception) {}
        try { inReader?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        for (w in clientWriters) {
            try { w.close() } catch (_: Exception) {}
        }
        clientWriters.clear()

        for (s in clientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        clientSockets.clear()

        socketToPlayerId.clear()
        outWriter = null
        inReader = null
        clientSocket = null
        serverSocket = null

        isHostInLobby = false
        localPlayer = null
        lastDirectHeartbeatTimestamps.clear()
        playerRegistry.clear()
        kickedPlayerIds.clear()
        appPresenceStates.clear()
        silentPlayerIds.clear()
        _players.value = emptyList()
        _measuredPingMs.value = 0L
        _lastPingTimestampEpochMs.value = 0L
    }

    @androidx.annotation.VisibleForTesting
    suspend fun drainForTests() {
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        livenessJob?.cancelAndJoin()
        livenessJob = null
        clientReadJob?.cancelAndJoin()
        clientReadJob = null
        clientConnectJob?.cancelAndJoin()
        clientConnectJob = null
        serverJob?.cancelAndJoin()
        serverJob = null
        clientJobs.forEach { it.cancelAndJoin() }
        clientJobs.clear()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { child ->
            if (child.isActive && child !== outgoingJob) child.join()
        }
    }

    fun getLastSeenTimestamp(playerId: String): Long {
        if (playerId.isBlank()) return 0L
        val clean = playerId.trim().lowercase().removePrefix("u_")
        var maxTimestamp = 0L
        playerRegistry.entries.forEach { (k, v) ->
            val cleanK = k.trim().lowercase().removePrefix("u_")
            val cleanUser = v.username.trim().lowercase().removePrefix("@")
            val cleanDisplay = v.displayName.trim().lowercase()
            if (cleanK == clean || (cleanUser.isNotBlank() && cleanUser == clean) || (cleanDisplay.isNotBlank() && cleanDisplay == clean)) {
                if (v.lastSeenTimestamp > maxTimestamp) {
                    maxTimestamp = v.lastSeenTimestamp
                }
            }
        }
        val direct = getLastDirectHeartbeat(playerId)
        return maxOf(maxTimestamp, direct)
    }

    companion object {
        @Volatile var activeInstance: LanP2pSessionManager? = null
    }
}
