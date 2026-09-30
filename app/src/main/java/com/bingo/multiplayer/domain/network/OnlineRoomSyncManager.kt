package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import androidx.annotation.Keep

@Keep
@Serializable
data class RoomMessagePacket(
    val type: String, // "JOIN", "HEARTBEAT", "START_GAME", "PICK_NUMBER", "GAME_SYNC", "SYNC_REQUEST", "LEAVE", "PLAY_AGAIN", "PLAY_AGAIN_REQUEST", "SURRENDER", "PING", "PONG", "GAME_PAUSED", "GAME_RESUMED"
    val playerId: String = "",
    val displayName: String = "",
    val username: String = "",
    val isHost: Boolean = false,
    val avatarUrl: String? = null,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val level: Int = 1,
    val boardSize: Int = 5,
    val number: Int = 0,
    val turnNumber: Int = 0,
    val seed: Long = 0L,
    val pickedHistory: List<Int> = emptyList(),
    val currentTurnPlayerId: String = "",
    val pingTimestamp: Long = 0L,
    val players: List<Player> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val readyStatus: String = "",
    val targetPlayerId: String = ""
)

class OnlineRoomSyncManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val brokerUrl = NetworkConfig.BROKER_URL
    private var mqttClient: MqttAsyncClient? = null

    private var currentRoomCode: String? = null
    private var localPlayer: Player? = null

    private val playerRegistry = ConcurrentHashMap<String, Player>()

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<RoomMessagePacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<RoomMessagePacket> = _incomingPackets.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _pingMs = MutableStateFlow<Long>(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null
    private var pingJob: Job? = null

    private fun getTopic(code: String): String =
        "bingo/v3/room/${code.trim().uppercase()}"

    /**
     * Connects to high-speed MQTT room topic and begins presence heartbeat and ping measurement.
     * Optionally pre-loads existing players discovered via cloud room registry.
     */
    fun connectToRoom(roomCode: String, player: Player, initialPlayers: List<Player> = emptyList()) {
        disconnect()

        val cleanCode = roomCode.trim().uppercase()
        currentRoomCode = cleanCode
        localPlayer = player

        playerRegistry.clear()
        initialPlayers.forEach { p ->
            if (p.id.isNotBlank()) {
                playerRegistry[p.id] = p.copy(lastSeenTimestamp = System.currentTimeMillis())
            }
        }
        playerRegistry[player.id] = player.copy(lastSeenTimestamp = System.currentTimeMillis())
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

        val clientId = "bingo_${player.id}_${UUID.randomUUID().toString().take(6)}"

        // Immediately perform initial cloud sync to discover players already registered
        scope.launch(Dispatchers.IO) {
            reconcileWithCloud(cleanCode, player)
        }

        scope.launch(Dispatchers.IO) {
            try {
                val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
                mqttClient = client

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    socketFactory = LowLatencySocketFactory()
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        scope.launch(Dispatchers.IO) {
                            subscribeToRoom(cleanCode)
                            sendJoinPacket()
                            if (!player.isHost) {
                                delay(500L)
                                if (playerRegistry.values.none { it.isHost }) {
                                    sendJoinPacket()
                                }
                                delay(1000L)
                                if (playerRegistry.values.none { it.isHost }) {
                                    sendJoinPacket()
                                }
                            }
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payloadBytes = message?.payload ?: return
                        try {
                            val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
                            val packet = FastPacketCodec.decode(payloadStr)
                            handleIncomingPacket(packet)
                        } catch (_: Exception) { }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        scope.launch(Dispatchers.IO) {
                            delay(1500L)
                            val code = currentRoomCode
                            val activeClient = mqttClient
                            if (code != null && activeClient != null && !activeClient.isConnected) {
                                try {
                                    activeClient.reconnect()
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) { }
                })

                client.connect(options)
            } catch (_: Exception) { }
        }

        // Periodic Presence Heartbeat & Cloud Room Reconciliation (Every 2.0s)
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
                val p = localPlayer ?: continue
                val code = currentRoomCode ?: continue

                // Check MQTT connection health
                val client = mqttClient
                if (client != null && !client.isConnected) {
                    try {
                        client.reconnect()
                    } catch (_: Exception) {}
                }

                // 1. MQTT Fast Heartbeat
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
                        timestamp = System.currentTimeMillis(),
                        readyStatus = p.lobbyReadyStatus
                    )
                )
                playerRegistry[p.id] = p.copy(lastSeenTimestamp = System.currentTimeMillis())

                // 2. High-Reliability Cloud Dual-Channel Sync
                reconcileWithCloud(code, p)
            }
        }

        // Periodic Ping loop (Every 2.0s to measure real-time latency)
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
                localPlayer?.let { p ->
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "PING",
                            playerId = p.id,
                            pingTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // Liveness Checker (Updates Online vs Last seen display every 1.5s)
        livenessJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L)
                _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            }
        }
    }

    private fun subscribeToRoom(roomCode: String) {
        val client = mqttClient ?: return
        try {
            if (client.isConnected) {
                client.subscribe(getTopic(roomCode), 1).waitForCompletion(1500L)
            }
        } catch (_: Exception) { }
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
                timestamp = System.currentTimeMillis(),
                readyStatus = p.lobbyReadyStatus
            )
        )
    }

    /**
     * Publishes a message packet with optimized QoS routing:
     * High-speed moves (PICK_NUMBER, PING, PONG) use QoS 0 for instant, zero-ACK delivery.
     * Room control packets (START_GAME, ROOM_STATE, SURRENDER, LEAVE) use QoS 1 for guaranteed delivery.
     */
    fun broadcastPacket(packet: RoomMessagePacket) {
        val code = currentRoomCode ?: return
        val client = mqttClient ?: return

        scope.launch(Dispatchers.IO) {
            try {
                var retryCount = 0
                while (!client.isConnected && retryCount < 5) {
                    delay(100L)
                    retryCount++
                }
                if (client.isConnected) {
                    val payload = FastPacketCodec.encode(packet)
                    val qosLevel = when (packet.type) {
                        "PICK_NUMBER", "PING", "PONG" -> 0 // Instant line-rate flight, zero ACK wait
                        else -> 1 // Guaranteed delivery for room control & state
                    }
                    val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                        qos = qosLevel
                    }
                    client.publish(getTopic(code), message)
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Periodic and on-demand cloud state reconciliation:
     * Guarantees 100% reliable discovery of joined players and host presence
     * even if MQTT packets are delayed or dropped on cellular networks.
     */
    private suspend fun reconcileWithCloud(code: String, localP: Player) {
        try {
            val cloudSession = OnlineRoomRegistry.syncRoom(code, localP, playerRegistry.values.toList()) ?: return
            val now = System.currentTimeMillis()
            var hasNewPlayer = false

            cloudSession.players.forEach { p ->
                if (p.id.isNotBlank()) {
                    val isLocal = (p.id == localP.id)
                    val existing = playerRegistry[p.id]

                    if (existing == null && !isLocal) {
                        hasNewPlayer = true
                    }

                    val effAvatar = when {
                        isLocal -> localP.avatarUrl?.takeIf { it.isNotBlank() } ?: p.avatarUrl ?: existing?.avatarUrl
                        !p.avatarUrl.isNullOrBlank() -> p.avatarUrl
                        else -> existing?.avatarUrl
                    }
                    val effUsername = when {
                        isLocal -> localP.username.takeIf { it.isNotBlank() } ?: p.username.ifBlank { existing?.username ?: "" }
                        p.username.isNotBlank() -> p.username
                        else -> existing?.username ?: ""
                    }
                    val effDisplay = when {
                        isLocal -> localP.displayName.takeIf { it.isNotBlank() } ?: p.displayName.ifBlank { existing?.displayName ?: "Player" }
                        p.displayName.isNotBlank() -> p.displayName
                        else -> existing?.displayName ?: "Player"
                    }

                    // If player is host and room heartbeat is alive (< 5 mins), refresh lastSeenTimestamp
                    val isHostAlive = (p.isHost && (now - cloudSession.lastHeartbeat) < 300_000L)
                    // Any player present in the cloud room session is actively connected.
                    // Refresh their lastSeenTimestamp to now so they show "Online" in the lobby.
                    val effectiveLastSeen = when {
                        isLocal -> now
                        isHostAlive -> now
                        // If their cloud lastSeenTimestamp is within 5 minutes, they're actively syncing
                        (now - p.lastSeenTimestamp) < 300_000L -> now
                        else -> existing?.lastSeenTimestamp ?: p.lastSeenTimestamp
                    }

                    // Prevent status toggling / clobbering:
                    // 1. Local player's own status is authoritative.
                    // 2. Recent in-memory MQTT status (received within 5s) takes precedence over delayed cloud response.
                    // 3. Cloud session ready status takes effect if no recent MQTT packet has arrived.
                    val isRecentMqtt = existing != null && (now - existing.lastSeenTimestamp) < 5_000L && existing.lobbyReadyStatus.isNotBlank()
                    val effReady = when {
                        isLocal -> localP.lobbyReadyStatus
                        isRecentMqtt -> existing!!.lobbyReadyStatus
                        p.lobbyReadyStatus.isNotBlank() -> p.lobbyReadyStatus
                        existing?.lobbyReadyStatus != null -> existing.lobbyReadyStatus
                        p.isHost -> "READY"
                        else -> "NOT_READY"
                    }

                    playerRegistry[p.id] = p.copy(
                        displayName = effDisplay,
                        username = effUsername,
                        avatarUrl = effAvatar,
                        lastSeenTimestamp = effectiveLastSeen,
                        lobbyReadyStatus = effReady
                    )
                }
            }

            _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

            // Dual-channel game start synchronization: if cloud status is PLAYING and guest hasn't started yet
            if (!localP.isHost && cloudSession.status == "PLAYING") {
                val startPacket = RoomMessagePacket(
                    type = "START_GAME",
                    playerId = cloudSession.hostId,
                    boardSize = cloudSession.boardSize,
                    timestamp = cloudSession.lastHeartbeat
                )
                handleIncomingPacket(startPacket)
            }

            // If host discovers a new player via cloud, broadcast ROOM_STATE over MQTT so joiner is also immediately updated
            if (localP.isHost && hasNewPlayer) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "ROOM_STATE",
                        playerId = localP.id,
                        displayName = localP.displayName,
                        username = localP.username,
                        isHost = true,
                        avatarUrl = localP.avatarUrl,
                        gamesPlayed = localP.gamesPlayed,
                        gamesWon = localP.gamesWon,
                        currentStreak = localP.currentStreak,
                        level = localP.level,
                        players = playerRegistry.values.toList(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Manual refresh button action: rebroadcasts presence and updates UI state.
     */
    suspend fun refreshNow() {
        val code = currentRoomCode ?: return
        val localP = localPlayer ?: return
        _isRefreshing.value = true

        withContext(Dispatchers.IO) {
            reconcileWithCloud(code, localP)

            if (localP.isHost) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "ROOM_STATE",
                        playerId = localP.id,
                        displayName = localP.displayName,
                        username = localP.username,
                        isHost = true,
                        avatarUrl = localP.avatarUrl,
                        gamesPlayed = localP.gamesPlayed,
                        gamesWon = localP.gamesWon,
                        currentStreak = localP.currentStreak,
                        level = localP.level,
                        players = playerRegistry.values.toList(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                sendJoinPacket()
            }
            delay(200L)
            _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            _isRefreshing.value = false
        }
    }

    private fun handleIncomingPacket(packet: RoomMessagePacket) {
        when (packet.type) {
            "ROOM_STATE" -> {
                packet.players.forEach { p ->
                    if (p.id.isNotBlank()) {
                        val existing = playerRegistry[p.id]
                        val isLocal = p.id == localPlayer?.id
                        val effAvatar = when {
                            isLocal -> localPlayer?.avatarUrl?.takeIf { it.isNotBlank() } ?: p.avatarUrl ?: existing?.avatarUrl
                            !p.avatarUrl.isNullOrBlank() -> p.avatarUrl
                            else -> existing?.avatarUrl
                        }
                        val effUsername = when {
                            isLocal -> localPlayer?.username?.takeIf { it.isNotBlank() } ?: p.username.ifBlank { existing?.username ?: "" }
                            p.username.isNotBlank() -> p.username
                            else -> existing?.username ?: ""
                        }
                        val effDisplay = when {
                            isLocal -> localPlayer?.displayName?.takeIf { it.isNotBlank() } ?: p.displayName.ifBlank { existing?.displayName ?: "Player" }
                            p.displayName.isNotBlank() -> p.displayName
                            else -> existing?.displayName ?: "Player"
                        }
                        val effReady = when {
                            isLocal -> localPlayer?.lobbyReadyStatus ?: p.lobbyReadyStatus
                            p.lobbyReadyStatus.isNotBlank() -> p.lobbyReadyStatus
                            existing?.lobbyReadyStatus != null -> existing.lobbyReadyStatus
                            p.isHost -> "READY"
                            else -> "NOT_READY"
                        }
                        playerRegistry[p.id] = p.copy(
                            displayName = effDisplay,
                            username = effUsername,
                            avatarUrl = effAvatar,
                            lastSeenTimestamp = System.currentTimeMillis(),
                            lobbyReadyStatus = effReady
                        )
                    }
                }
                _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            }

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
                    val effectiveReadyStatus = when {
                        packet.readyStatus.isNotBlank() -> packet.readyStatus
                        existing?.lobbyReadyStatus != null -> existing.lobbyReadyStatus
                        packet.isHost -> "READY"
                        else -> "NOT_READY"
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
                        lastSeenTimestamp = System.currentTimeMillis(),
                        lobbyReadyStatus = effectiveReadyStatus
                    )
                    playerRegistry[packet.playerId] = updated
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

                    // If Host receives a JOIN packet, Host immediately replies with a ROOM_STATE containing all players!
                    if (packet.type == "JOIN" && localPlayer?.isHost == true && packet.playerId != localPlayer?.id) {
                        localPlayer?.let { h ->
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "ROOM_STATE",
                                    playerId = h.id,
                                    displayName = h.displayName,
                                    username = h.username,
                                    isHost = true,
                                    avatarUrl = h.avatarUrl,
                                    gamesPlayed = h.gamesPlayed,
                                    gamesWon = h.gamesWon,
                                    currentStreak = h.currentStreak,
                                    level = h.level,
                                    players = playerRegistry.values.toList(),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }

            "READY_STATUS" -> {
                if (packet.playerId.isNotEmpty()) {
                    val existing = playerRegistry[packet.playerId]
                    val updated = if (existing != null) {
                        existing.copy(
                            lobbyReadyStatus = packet.readyStatus,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    } else {
                        Player(
                            id = packet.playerId,
                            displayName = packet.displayName.ifBlank { "Player" },
                            username = packet.username,
                            isHost = packet.isHost,
                            avatarUrl = packet.avatarUrl,
                            lobbyReadyStatus = packet.readyStatus,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    }
                    playerRegistry[packet.playerId] = updated
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    val code = currentRoomCode
                    if (code != null && localPlayer?.isHost == true) {
                        scope.launch(Dispatchers.IO) {
                            OnlineRoomRegistry.updatePlayerReadyStatus(code, packet.playerId, packet.readyStatus)
                        }
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
                            // Non-host player left the lobby: mark as LEFT_LOBBY so other players and host see ❌
                            // Do NOT automatically delete from the lobby registry!
                            playerRegistry[packet.playerId] = existing.copy(
                                lobbyReadyStatus = "LEFT_LOBBY"
                            )
                        }
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    }
                }
            }

            "PING" -> {
                if (packet.playerId != localPlayer?.id) {
                    // Reply immediately with PONG echoing the sender's timestamp
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "PONG",
                            playerId = localPlayer?.id ?: "",
                            pingTimestamp = packet.pingTimestamp
                        )
                    )
                }
            }

            "PONG" -> {
                if (packet.playerId != localPlayer?.id && packet.pingTimestamp > 0L) {
                    val rtt = (System.currentTimeMillis() - packet.pingTimestamp).coerceAtLeast(1L)
                    _pingMs.value = rtt
                }
            }
        }

        // Deliver game packets to listeners (START_GAME, PICK_NUMBER, GAME_SYNC, PLAY_AGAIN_REQUEST, SURRENDER, LEAVE, etc.)
        _incomingPackets.tryEmit(packet)
    }

    fun updateLocalReadyStatus(status: String) {
        val p = localPlayer ?: return
        val now = System.currentTimeMillis()
        val updated = p.copy(lobbyReadyStatus = status, lastSeenTimestamp = now)
        localPlayer = updated
        playerRegistry[p.id] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        broadcastPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = p.id,
                displayName = p.displayName,
                username = p.username,
                isHost = p.isHost,
                avatarUrl = p.avatarUrl,
                readyStatus = status,
                timestamp = now
            )
        )
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.updatePlayerReadyStatus(code, p.id, status)
            }
        }
    }

    fun updatePlayerReadyStatus(playerId: String, status: String) {
        val existing = playerRegistry[playerId] ?: return
        playerRegistry[playerId] = existing.copy(lobbyReadyStatus = status)
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.updatePlayerReadyStatus(code, playerId, status)
            }
        }
    }

    fun removePlayer(playerId: String) {
        playerRegistry.remove(playerId)
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.removePlayerFromRoom(code, playerId)
            }
        }
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
        val code = currentRoomCode
        if (p != null && code != null) {
            broadcastPacket(
                RoomMessagePacket(
                    type = "LEAVE",
                    playerId = p.id,
                    timestamp = System.currentTimeMillis()
                )
            )
            if (p.isHost) {
                scope.launch(Dispatchers.IO) {
                    OnlineRoomRegistry.closeRoom(code)
                }
            }
        }

        heartbeatJob?.cancel()
        livenessJob?.cancel()
        pingJob?.cancel()
        heartbeatJob = null
        livenessJob = null
        pingJob = null
        _pingMs.value = 0L

        val client = mqttClient
        mqttClient = null
        scope.launch(Dispatchers.IO) {
            try {
                if (client?.isConnected == true) {
                    client.disconnect()
                }
                client?.close()
            } catch (_: Exception) { }
        }

        currentRoomCode = null
        localPlayer = null
        playerRegistry.clear()
        _players.value = emptyList()
    }
}
