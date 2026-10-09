package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import androidx.compose.ui.graphics.asImageBitmap
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
    val pickedByHistory: List<String> = emptyList(),
    val boardHash: Long = 0L,
    val currentTurnPlayerId: String = "",
    val pingTimestamp: Long = 0L,
    val players: List<Player> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val readyStatus: String = "",
    val targetPlayerId: String = "",
    val readyVersion: Long = 0L,
    val isManualBoard: Boolean = false,
    val isDynamicBoard: Boolean = false,
    val senderInstanceId: String = "",
    val winnerPlayerId: String = "",
    val winReason: String = "",
    val runnerPlayerIds: List<String> = emptyList()
)

class OnlineRoomSyncManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    init {
        activeInstance = this
    }

    val instanceId: String = UUID.randomUUID().toString()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val brokerUrl = NetworkConfig.BROKER_URL
    private var mqttClient: MqttAsyncClient? = null

    val isConnected: Boolean
        get() = mqttClient?.isConnected == true

    fun publishRetainedRoomMeta(session: OnlineRoomSession) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        scope.launch(Dispatchers.IO) {
            try {
                val jsonStr = json.encodeToString(session)
                val message = MqttMessage(jsonStr.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                client.publish("bingo/v3/room_meta/${session.roomCode}", message)
            } catch (_: Exception) {}
        }
    }

    fun clearRetainedRoomMeta(roomCode: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        scope.launch(Dispatchers.IO) {
            try {
                val message = MqttMessage(ByteArray(0)).apply {
                    qos = 1
                    isRetained = true
                }
                client.publish("bingo/v3/room_meta/$roomCode", message)
            } catch (_: Exception) {}
        }
    }

    private var currentRoomCode: String? = null
    var localPlayer: Player? = null
        private set
    @Volatile var currentHostId: String? = null
        private set

    private val playerRegistry = ConcurrentHashMap<String, Player>()
    private val kickedPlayerIds = ConcurrentHashMap.newKeySet<String>()

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

    @Volatile private var isSubscribed: Boolean = false
    @Volatile private var lastStartedMatchSeed: Long = 0L
    private val completedMatchSeeds = ConcurrentHashMap.newKeySet<Long>()

    fun recordStartedSeed(seed: Long) {
        if (seed != 0L) {
            lastStartedMatchSeed = seed
        }
    }

    fun recordCompletedSeed(seed: Long) {
        if (seed != 0L) {
            completedMatchSeeds.add(seed)
            lastStartedMatchSeed = seed
        }
    }

    fun isSeedCompleted(seed: Long): Boolean {
        return seed != 0L && completedMatchSeeds.contains(seed)
    }

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
        val now = System.currentTimeMillis()
        participantIds.forEach { id ->
            recordDirectHeartbeat(id)
        }
    }

    init {
        activeInstance = this
    }

    fun resetMatchSession() {
        lastStartedMatchSeed = 0L
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
        val isPlayerHost = player.isHost || (currentHostId != null && player.id == currentHostId)
        val safePlayer = player.copy(isHost = isPlayerHost)
        localPlayer = safePlayer

        playerRegistry.clear()
        kickedPlayerIds.clear()
        initialPlayers.forEach { p ->
            if (p.id.isNotBlank()) {
                val isPActuallyHost = p.isHost || (currentHostId != null && p.id == currentHostId)
                val isSameAsLocal = (p.id == safePlayer.id) ||
                    (safePlayer.username.isNotBlank() && p.username.equals(safePlayer.username, ignoreCase = true))
                if (!isSameAsLocal) {
                    playerRegistry[p.id] = p.copy(
                        isHost = isPActuallyHost,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                }
            }
        }
        playerRegistry[safePlayer.id] = safePlayer.copy(lastSeenTimestamp = System.currentTimeMillis())
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
                    keepAliveInterval = 30
                    NetworkConfig.applyMqttOptions(this)
                    try {
                        val willPacket = RoomMessagePacket(
                            type = "LEAVE",
                            playerId = player.id,
                            displayName = player.displayName,
                            username = player.username,
                            isHost = player.isHost,
                            timestamp = System.currentTimeMillis()
                        )
                        val willTopic = "bingo/v3/room/$cleanCode"
                        val willPayload = FastPacketCodec.encode(willPacket).toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                        setWill(willTopic, willPayload, 1, false)
                    } catch (_: Exception) {}
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        scope.launch(Dispatchers.IO) {
                            subscribeToRoom(cleanCode)
                            sendJoinPacket()
                            // If local player was already toggled to READY before/during connection, immediately broadcast READY_STATUS!
                            localPlayer?.let { lp ->
                                if (lp.lobbyReadyStatus == LobbyLifecycleEngine.STATUS_READY) {
                                    val readyPacket = RoomMessagePacket(
                                        type = "READY_STATUS",
                                        playerId = lp.id,
                                        displayName = lp.displayName,
                                        username = lp.username,
                                        isHost = lp.isHost,
                                        avatarUrl = lp.avatarUrl,
                                        readyStatus = lp.lobbyReadyStatus,
                                        readyVersion = lp.readyVersion,
                                        timestamp = lp.lastSeenTimestamp
                                    )
                                    broadcastPacket(readyPacket)
                                }
                            }
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
                        isSubscribed = false
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

        // Periodic Presence Heartbeat & Cloud Room Reconciliation (Every 2.5s)
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500L)
                if (!AppLifecycleObserver.isAppInForeground.value) {
                    continue
                }
                val p = localPlayer ?: continue
                val code = currentRoomCode ?: continue

                // Check MQTT connection & subscription health
                val client = mqttClient
                if (client != null) {
                    if (!client.isConnected) {
                        try {
                            client.reconnect()
                        } catch (_: Exception) {}
                    } else if (!isSubscribed) {
                        subscribeToRoom(code)
                    }
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
                        readyStatus = p.lobbyReadyStatus,
                        readyVersion = p.readyVersion
                    )
                )
                playerRegistry[p.id] = p.copy(lastSeenTimestamp = System.currentTimeMillis())

                // 2. High-Reliability Cloud Dual-Channel Sync
                reconcileWithCloud(code, p)
            }
        }

        // Periodic Ping loop (Every 1.0s to measure real-time latency)
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (AppLifecycleObserver.isAppInForeground.value) {
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
                delay(1000L)
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
                client.subscribe(getTopic(roomCode), 1).waitForCompletion(2000L)
                isSubscribed = true
            }
        } catch (_: Exception) {
            isSubscribed = false
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
                timestamp = System.currentTimeMillis(),
                readyStatus = p.lobbyReadyStatus,
                readyVersion = p.readyVersion
            )
        )
    }

    /**
     * Publishes a message packet with optimized QoS routing:
     * High-speed moves (PICK_NUMBER, PING, PONG) use QoS 0 for instant, zero-ACK delivery.
     * Room control packets (START_GAME, PLAY_AGAIN, ROOM_STATE, SURRENDER, LEAVE) use QoS 1 for guaranteed delivery.
     */
    fun broadcastPacket(packet: RoomMessagePacket) {
        val code = currentRoomCode ?: return

        scope.launch(Dispatchers.IO) {
            try {
                var retryCount = 0
                var client = mqttClient
                val maxRetries = if (packet.type == "PICK_NUMBER" || packet.type == "PING" || packet.type == "PONG") 5 else 30
                while ((client == null || !client.isConnected) && retryCount < maxRetries) {
                    delay(100L)
                    retryCount++
                    client = mqttClient
                }
                if (client != null && client.isConnected) {
                    val outgoing = if (packet.senderInstanceId.isBlank()) packet.copy(senderInstanceId = instanceId) else packet
                    val payload = FastPacketCodec.encode(outgoing)
                    val qosLevel = when (packet.type) {
                        "PICK_NUMBER", "PING", "PONG" -> 0 // Instant line-rate flight, zero ACK wait
                        "TURN_TIMEOUT", "GAME_SYNC", "START_GAME", "PLAY_AGAIN", "ROOM_STATE", "SURRENDER", "LEAVE" -> 1 // Guaranteed delivery
                        else -> 1 // Guaranteed delivery for room control & state (START_GAME, PLAY_AGAIN, BOARD_READY, ROOM_STATE, etc.)
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
            val cloudSession = OnlineRoomRegistry.syncRoom(code, localP, playerRegistry.values.toList(), kickedPlayerIds) ?: return
            if (cloudSession.hostId.isNotBlank()) {
                currentHostId = cloudSession.hostId
            }
            val now = System.currentTimeMillis()
            var hasNewPlayer = false

            cloudSession.players.forEach { p ->
                val pCleanUser = p.username.trim().lowercase().removePrefix("@")
                val pCleanDisplay = p.displayName.trim().lowercase()
                if (p.id.isNotBlank() && p.id !in kickedPlayerIds && pCleanUser !in kickedPlayerIds && pCleanDisplay !in kickedPlayerIds) {
                    val isLocal = (p.id == localP.id) ||
                        (localP.username.isNotBlank() && p.username.equals(localP.username, ignoreCase = true))
                    val existing = if (isLocal) (playerRegistry[localP.id] ?: playerRegistry[p.id]) else playerRegistry[p.id]

                    if (existing == null && !isLocal) {
                        hasNewPlayer = true
                    }

                    val isPlayerActuallyHost = p.isHost ||
                        (cloudSession.hostId.isNotBlank() && p.id == cloudSession.hostId) ||
                        (cloudSession.hostUsername.isNotBlank() && p.username.equals(cloudSession.hostUsername, ignoreCase = true)) ||
                        existing?.isHost == true

                    if (isLocal && isPlayerActuallyHost && localPlayer?.isHost != true) {
                        localPlayer = localP.copy(isHost = true)
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

                    // If player is host and room heartbeat is alive (< 5 mins), record host liveness
                    val isHostAlive = (isPlayerActuallyHost && (now - cloudSession.lastHeartbeat) < 300_000L)
                    val effectiveLastSeen = when {
                        isLocal -> now
                        isHostAlive -> maxOf(existing?.lastSeenTimestamp ?: 0L, cloudSession.lastHeartbeat)
                        existing != null -> maxOf(existing.lastSeenTimestamp, p.lastSeenTimestamp)
                        else -> p.lastSeenTimestamp
                    }

                    // Prevent status toggling / clobbering via isolated LobbyLifecycleEngine:
                    val (effReady, effVer) = if (isLocal) {
                        Pair(localP.lobbyReadyStatus, localP.readyVersion)
                    } else {
                        LobbyLifecycleEngine.reconcileReadyStatus(
                            currentStatus = existing?.lobbyReadyStatus ?: LobbyLifecycleEngine.STATUS_NOT_READY,
                            currentVersion = existing?.readyVersion ?: 0L,
                            incomingStatus = p.lobbyReadyStatus,
                            incomingVersion = p.readyVersion
                        )
                    }

                    val targetId = if (isLocal) localP.id else p.id
                    if (isLocal && p.id != localP.id) {
                        playerRegistry.remove(p.id)
                    }

                    playerRegistry[targetId] = p.copy(
                        id = targetId,
                        isHost = isPlayerActuallyHost,
                        displayName = effDisplay,
                        username = effUsername,
                        avatarUrl = effAvatar,
                        lastSeenTimestamp = effectiveLastSeen,
                        lobbyReadyStatus = effReady,
                        readyVersion = effVer
                    )
                }
            }

            _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

            // 3. Dual-Channel Cloud Room Status Fallback:
            // ONLY if the host has started the game ("PLAYING"), local player is a guest,
            // guest is actively in STATUS_READY in the lobby, and seed is valid, unstarted, and not completed!
            if (cloudSession.status == "PLAYING" &&
                !localP.isHost &&
                cloudSession.currentSeed != 0L &&
                localP.lobbyReadyStatus == com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.STATUS_READY &&
                cloudSession.currentSeed != lastStartedMatchSeed &&
                !completedMatchSeeds.contains(cloudSession.currentSeed)
            ) {
                lastStartedMatchSeed = cloudSession.currentSeed
                _incomingPackets.tryEmit(
                    RoomMessagePacket(
                        type = "START_GAME",
                        boardSize = cloudSession.boardSize,
                        seed = cloudSession.currentSeed,
                        playerId = cloudSession.hostId,
                        isManualBoard = cloudSession.isManualBoard
                    )
                )
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
     * Called when app transitions from background to foreground during an active room/match.
     * Immediately reconnects MQTT if dropped, resubscribes, sends fresh heartbeat and refreshes state.
     */
    fun onForegroundResume() {
        val code = currentRoomCode ?: return
        val p = localPlayer ?: return
        scope.launch(Dispatchers.IO) {
            val client = mqttClient
            if (client != null) {
                if (!client.isConnected) {
                    try {
                        client.reconnect()
                    } catch (_: Exception) {}
                }
                if (!isSubscribed) {
                    subscribeToRoom(code)
                }
            }
            // Send fast presence heartbeat immediately
            broadcastPacket(
                RoomMessagePacket(
                    type = "HEARTBEAT",
                    playerId = p.id,
                    displayName = p.displayName,
                    username = p.username,
                    isHost = p.isHost,
                    avatarUrl = p.avatarUrl,
                    timestamp = System.currentTimeMillis()
                )
            )
            refreshNow()
        }
    }

    /**
     * Manual refresh button action: rebroadcasts presence and updates UI state.
     */
    suspend fun refreshNow() {
        val code = currentRoomCode ?: return
        val localP = localPlayer ?: return
        _isRefreshing.value = true

        withContext(Dispatchers.IO) {
            val client = mqttClient
            if (client != null) {
                if (!client.isConnected) {
                    try {
                        client.reconnect()
                    } catch (_: Exception) {}
                }
                if (!isSubscribed) {
                    subscribeToRoom(code)
                }
            }
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
        if (packet.senderInstanceId.isNotBlank() && packet.senderInstanceId == instanceId && packet.type != "PING") {
            return
        }

        val pCleanUser = packet.username.trim().lowercase().removePrefix("@")
        val isSenderKicked = packet.playerId in kickedPlayerIds ||
                (pCleanUser.isNotBlank() && pCleanUser in kickedPlayerIds)

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
            "START_GAME", "PLAY_AGAIN" -> {
                if (packet.seed != 0L) {
                    recordStartedSeed(packet.seed)
                }
                if (isSeedCompleted(packet.seed)) {
                    return
                }
            }

            "ROOM_STATE" -> {
                packet.players.forEach { p ->
                    val pcUser = p.username.trim().lowercase().removePrefix("@")
                    val pcDisplay = p.displayName.trim().lowercase()
                    if (p.id.isNotBlank() && p.id !in kickedPlayerIds && pcUser !in kickedPlayerIds && pcDisplay !in kickedPlayerIds) {
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
                            p.lobbyReadyStatus.isNotBlank() && p.readyVersion >= (existing?.readyVersion ?: 0L) -> p.lobbyReadyStatus
                            existing?.lobbyReadyStatus != null -> existing.lobbyReadyStatus
                            p.lobbyReadyStatus.isNotBlank() -> p.lobbyReadyStatus
                            p.isHost -> "READY"
                            else -> "NOT_READY"
                        }
                        val effVersion = if (isLocal) (localPlayer?.readyVersion ?: 0L) else maxOf(p.readyVersion, existing?.readyVersion ?: 0L)
                        if (!effAvatar.isNullOrBlank() && effAvatar != existing?.avatarUrl && !isLocal) {
                            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(p.username)
                            val bmp = com.bingo.multiplayer.presentation.common.decodeAvatarBitmap(effAvatar, null)
                            if (bmp != null) {
                                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:${p.username.trim().lowercase().removePrefix("@")}", bmp.asImageBitmap())
                            }
                            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(p.username)
                        }
                        playerRegistry[p.id] = p.copy(
                            displayName = effDisplay,
                            username = effUsername,
                            avatarUrl = effAvatar,
                            lastSeenTimestamp = System.currentTimeMillis(),
                            lobbyReadyStatus = effReady,
                            readyVersion = effVersion
                        )
                    }
                }
                _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
            }

            "JOIN", "HEARTBEAT" -> {
                if (packet.playerId.isNotEmpty()) {
                    val clean = packet.playerId.trim().lowercase().removePrefix("u_")
                    val pCleanU = packet.username.trim().lowercase().removePrefix("@")
                    val existingKey = if (playerRegistry.containsKey(packet.playerId)) {
                        packet.playerId
                    } else {
                        playerRegistry.entries.firstOrNull { (k, v) ->
                            val cleanK = k.trim().lowercase().removePrefix("u_")
                            val cleanUser = v.username.trim().lowercase().removePrefix("@")
                            cleanK == clean || (pCleanU.isNotBlank() && (cleanUser == pCleanU || cleanK == pCleanU))
                        }?.key ?: packet.playerId
                    }
                    val existing = playerRegistry[existingKey]

                    if (!packet.avatarUrl.isNullOrBlank() && packet.avatarUrl != existing?.avatarUrl && packet.playerId != localPlayer?.id) {
                        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(packet.username)
                        val bmp = com.bingo.multiplayer.presentation.common.decodeAvatarBitmap(packet.avatarUrl, null)
                        if (bmp != null) {
                            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:${packet.username.trim().lowercase().removePrefix("@")}", bmp.asImageBitmap())
                        }
                        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(packet.username)
                    }

                    val updated = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(packet, existing)
                    if (existingKey != packet.playerId) {
                        playerRegistry.remove(existingKey)
                    }
                    playerRegistry[packet.playerId] = updated
                    _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }

                    // If Host receives a JOIN packet, Host immediately updates cloud registry and replies with a ROOM_STATE containing all players!
                    if (packet.type == "JOIN" && localPlayer?.isHost == true && packet.playerId != localPlayer?.id) {
                        val code = currentRoomCode
                        if (code != null) {
                            scope.launch(Dispatchers.IO) {
                                OnlineRoomRegistry.updatePlayerReadyStatus(code, packet.playerId, updated.lobbyReadyStatus, updated.readyVersion)
                            }
                        }
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
                        ?: playerRegistry.values.find {
                            (packet.username.isNotBlank() && it.username.equals(packet.username, ignoreCase = true)) ||
                            (packet.displayName.isNotBlank() && it.displayName.equals(packet.displayName, ignoreCase = true))
                        }
                    val targetKey = existing?.id ?: packet.playerId

                    val updated = LobbyLifecycleEngine.onRemoteReadyStatusPacket(packet, existing)
                    if (updated != null) {
                        playerRegistry[targetKey] = updated
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                        val code = currentRoomCode
                        if (code != null && localPlayer?.isHost == true) {
                            scope.launch(Dispatchers.IO) {
                                OnlineRoomRegistry.updatePlayerReadyStatus(code, targetKey, updated.lobbyReadyStatus, updated.readyVersion)
                            }
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
                            val updated = LobbyLifecycleEngine.onPlayerLeave(existing)
                            playerRegistry[packet.playerId] = updated
                            val code = currentRoomCode
                            if (code != null && localPlayer?.isHost == true) {
                                scope.launch(Dispatchers.IO) {
                                    OnlineRoomRegistry.updatePlayerReadyStatus(code, packet.playerId, updated.lobbyReadyStatus, updated.readyVersion)
                                }
                            }
                        }
                        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
                    }
                }
            }

            "PING" -> {
                if (packet.playerId == localPlayer?.id || com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, localPlayer?.id ?: "")) {
                    // Direct broker round-trip echo: provides exact client-to-broker network ping
                    if (packet.pingTimestamp > 0L) {
                        val brokerRtt = (System.currentTimeMillis() - packet.pingTimestamp).coerceAtLeast(1L)
                        val cur = _pingMs.value
                        val smoothed = if (cur <= 0L) brokerRtt else ((cur * 0.60) + (brokerRtt * 0.40)).toLong().coerceAtLeast(1L)
                        _pingMs.value = smoothed
                        NetworkPingMonitor.recordExternalPing(smoothed)
                    }
                }
            }

            "PONG" -> {
                // Legacy PONG handling ignored to prevent multi-device ping jitter
            }
        }

        // Deliver game packets to listeners (START_GAME, PICK_NUMBER, GAME_SYNC, PLAY_AGAIN_REQUEST, SURRENDER, LEAVE, etc.)
        _incomingPackets.tryEmit(packet)
    }

    fun updateLocalReadyStatus(status: String) {
        val p = localPlayer ?: return
        val updated = LobbyLifecycleEngine.onLocalStatusChange(p, status)
        localPlayer = updated
        playerRegistry[p.id] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        val packet = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = p.id,
            displayName = p.displayName,
            username = p.username,
            isHost = p.isHost,
            avatarUrl = p.avatarUrl,
            readyStatus = updated.lobbyReadyStatus,
            readyVersion = updated.readyVersion,
            timestamp = updated.lastSeenTimestamp
        )
        broadcastPacket(packet)
        scope.launch(Dispatchers.IO) {
            delay(150L)
            broadcastPacket(packet)
            delay(300L)
            broadcastPacket(packet)
        }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.updatePlayerReadyStatus(code, p.id, updated.lobbyReadyStatus, updated.readyVersion)
            }
        }
    }

    fun updatePlayerReadyStatus(playerId: String, status: String) {
        val existing = playerRegistry[playerId] ?: return
        val updated = LobbyLifecycleEngine.onLocalStatusChange(existing, status)
        playerRegistry[playerId] = updated
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.updatePlayerReadyStatus(code, playerId, updated.lobbyReadyStatus, updated.readyVersion)
            }
        }
    }

    fun removePlayer(playerId: String) {
        val targetPlayer = playerRegistry[playerId]
        kickedPlayerIds.add(playerId)
        if (targetPlayer != null && targetPlayer.username.isNotBlank()) {
            kickedPlayerIds.add(targetPlayer.username.trim().lowercase().removePrefix("@"))
        }
        playerRegistry.remove(playerId)
        _players.value = playerRegistry.values.toList().sortedByDescending { it.isHost }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                OnlineRoomRegistry.removePlayerFromRoom(code, playerId)
            }
        }
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
        val p = localPlayer
        val code = currentRoomCode
        val client = mqttClient

        if (client != null && client.isConnected && p != null && code != null) {
            try {
                val leavePacket = RoomMessagePacket(
                    type = "LEAVE",
                    playerId = p.id,
                    senderInstanceId = instanceId,
                    timestamp = System.currentTimeMillis()
                )
                val payload = FastPacketCodec.encode(leavePacket)
                val msg = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                }
                client.publish(getTopic(code), msg).waitForCompletion(500L)
            } catch (_: Exception) {}

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
        currentHostId = null
        isSubscribed = false
        lastStartedMatchSeed = 0L
        completedMatchSeeds.clear()
        lastDirectHeartbeatTimestamps.clear()
        playerRegistry.clear()
        kickedPlayerIds.clear()
        _players.value = emptyList()
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
        @Volatile var activeInstance: OnlineRoomSyncManager? = null
    }
}
