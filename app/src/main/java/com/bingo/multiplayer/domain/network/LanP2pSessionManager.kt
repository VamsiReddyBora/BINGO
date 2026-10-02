package com.bingo.multiplayer.domain.network

import android.util.Log
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class LanP2pSessionManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outWriter: PrintWriter? = null
    private var inReader: BufferedReader? = null

    private val clientWriters = java.util.concurrent.CopyOnWriteArrayList<PrintWriter>()
    private val clientSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val clientJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

    private var serverJob: Job? = null
    private var clientReadJob: Job? = null
    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null

    var localPlayer: Player? = null
    var isHostInLobby = false

    private val playerRegistry = mutableMapOf<String, Player>()
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<RoomMessagePacket>(replay = 1, extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<RoomMessagePacket> = _incomingPackets.asSharedFlow()

    fun connectAsHost(hostPlayer: Player, port: Int = 8999) {
        disconnect()
        localPlayer = hostPlayer
        isHostInLobby = false
        playerRegistry[hostPlayer.id] = hostPlayer
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    socket.tcpNoDelay = true
                    try { socket.trafficClass = 0x10 } catch (_: Exception) {}
                    clientSockets.add(socket)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    clientWriters.add(writer)

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
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                val packet = json.decodeFromString<RoomMessagePacket>(line)
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
                            clientWriters.remove(writer)
                            clientSockets.remove(socket)
                            try { socket.close() } catch (_: Exception) {}
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

    fun connectAsClient(hostIp: String, clientPlayer: Player, port: Int = 8999) {
        disconnect()
        localPlayer = clientPlayer
        isHostInLobby = false
        playerRegistry[clientPlayer.id] = clientPlayer
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(hostIp, port)
                socket.tcpNoDelay = true
                try { socket.trafficClass = 0x10 } catch (_: Exception) {}
                clientSocket = socket
                setupSocketStreams(socket)
            } catch (e: Exception) {
                Log.e("LanP2p", "Client connect error", e)
            }
        }
        startHeartbeat()
    }

    private fun setupSocketStreams(socket: Socket) {
        try {
            outWriter = PrintWriter(socket.getOutputStream(), true)
            inReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            clientReadJob?.cancel()
            clientReadJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val line = inReader?.readLine()
                        if (line != null) {
                            val packet = json.decodeFromString<RoomMessagePacket>(line)
                            handleIncomingPacket(packet)
                        } else {
                            break // Connection closed
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LanP2p", "Read error", e)
                }
            }

            // Announce presence immediately upon connecting
            sendJoinPacket()
        } catch (e: Exception) {
            Log.e("LanP2p", "Setup streams error", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
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
                    playerRegistry[p.id] = p.copy(lastSeenTimestamp = System.currentTimeMillis())
                }
            }
        }
        livenessJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L)
                _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            }
        }
    }

    private fun sendJoinPacket() {
        val p = localPlayer ?: return
        broadcastPacket(
            RoomMessagePacket(
                type = "JOIN",
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
        scope.launch(Dispatchers.IO) {
            try {
                val payload = json.encodeToString(packet)
                outWriter?.println(payload)
                for (writer in clientWriters) {
                    try { writer.println(payload) } catch (_: Exception) {}
                }

                // If sender is broadcasting, simulate receiving own packet for local updates
                if (packet.type == "GAME_SYNC" || packet.type == "HEARTBEAT" || packet.type == "JOIN" || packet.type == "LEAVE") {
                    handleIncomingPacket(packet)
                }
            } catch (e: Exception) {
                Log.e("LanP2p", "Broadcast error", e)
            }
        }
    }

    private fun handleIncomingPacket(packet: RoomMessagePacket) {
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
                val targetId = packet.targetPlayerId.ifBlank { packet.playerId }
                if (targetId.isNotEmpty()) {
                    playerRegistry.remove(targetId)
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                }
            }

            "LEAVE" -> {
                if (packet.playerId.isNotEmpty()) {
                    val existing = playerRegistry[packet.playerId]
                    if (existing != null) {
                        if (existing.isHost) {
                            playerRegistry.remove(packet.playerId)
                        } else {
                            playerRegistry[packet.playerId] = LobbyLifecycleEngine.onPlayerLeave(existing)
                        }
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    }
                }
            }
        }

        // Only deliver gameplay and room lifecycle packets to listeners (ignore self packets as logic handles it already or we don't want duplicates)
        if (packet.type != "HEARTBEAT" && packet.type != "JOIN" && packet.type != "READY_STATUS") {
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
        playerRegistry.remove(playerId)
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        broadcastPacket(
            RoomMessagePacket(
                type = "KICK_PLAYER",
                targetPlayerId = playerId,
                playerId = localPlayer?.id ?: ""
            )
        )
    }

    fun disconnect() {
        val p = localPlayer
        if (p != null) {
            broadcastPacket(
                RoomMessagePacket(
                    type = "LEAVE",
                    playerId = p.id,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        heartbeatJob?.cancel()
        livenessJob?.cancel()
        clientReadJob?.cancel()
        serverJob?.cancel()
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()

        heartbeatJob = null
        livenessJob = null
        clientReadJob = null
        serverJob = null

        try { outWriter?.close() } catch (_: Exception) {}
        try { inReader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        for (w in clientWriters) {
            try { w.close() } catch (_: Exception) {}
        }
        clientWriters.clear()

        for (s in clientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        clientSockets.clear()

        outWriter = null
        inReader = null
        clientSocket = null
        serverSocket = null

        isHostInLobby = false
        localPlayer = null
        playerRegistry.clear()
        _players.value = emptyList()
    }
}
