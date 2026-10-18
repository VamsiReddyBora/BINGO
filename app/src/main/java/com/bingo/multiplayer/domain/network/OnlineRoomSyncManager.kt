package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.InGameChatMessage
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
    val turnIds: List<Int> = emptyList(),
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
    // Originating device account; playerId can instead identify a skipped player.
    val senderPlayerId: String = "",
    val closedTurnNumber: Int = 0,
    val winnerPlayerId: String = "",
    val winReason: String = "",
    val runnerPlayerIds: List<String> = emptyList(),
    val protocolVersion: Int = 0,
    val reliablePolicyVersion: Int = 0,
    val hostWaitVersion: Int = 0,
    val matchId: String = "",
    val peerUid: String = "",
    val connectionGeneration: String = "",
    val sequence: Long = 0L,
    val challengeId: String = "",
    val heartbeatAction: String = "",
    val turnId: String = "",
    val remainingTurnMillis: Long = 0L,
    val controllerEpoch: Long = 0L,
    val matchRevision: Long = 0L,
    val isPaused: Boolean = false,
    val pauseReason: String = "",
    val pauseOwnerId: String = "",
    val claimEpisodeId: String = "",
    val claimEpisodeCount: Int = 0,
    val roomCode: String = "",
    val requestId: String = "",
    val ownerId: String = "",
    val reliableSnapshot: com.bingo.multiplayer.domain.engine.ReliableMatchSnapshot? = null,
    val hostWaitSnapshot: com.bingo.multiplayer.domain.engine.ReliableMatchSnapshot? = null,
    val matchIntent: com.bingo.multiplayer.domain.engine.MatchIntent? = null,
    val pauseRemainingSeconds: Int = 0,
    val pauseEpisode: Int = 0,
    val eliminatedPlayerIds: List<String> = emptyList(),
    val surrenderedPlayerIds: List<String> = emptyList(),
    // Transport disconnect hints never represent an explicit match exit.
    val transportOnly: Boolean = false,
    val lifecycleSequence: Long = 0L,
    val isMatchFinished: Boolean = false,
    val chatMessages: List<InGameChatMessage> = emptyList()
)

class OnlineRoomSyncManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val roomRegistry: RoomRegistryGateway = CloudRoomRegistryGateway
) {

    init {
        activeInstance = this
    }

    val instanceId: String = UUID.randomUUID().toString()
    private data class VerifiedLobbyPeer(val version: Int, val probeAt: Long)
    private val verifiedLobbyPeers = ConcurrentHashMap<String, VerifiedLobbyPeer>()
    fun canStartReliableMatch(participants: List<String>, localUid: String): Boolean =
        isConnected && isSubscribed && reliableCompatibilityStatus(participants, localUid) ==
            com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.COMPATIBLE

    fun reliableCompatibilityStatus(participants: List<String>, localUid: String): com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status {
        if (participants.size != 2 || participants.any { it.isBlank() } ||
            participants.distinct().size != 2 || localUid !in participants) return com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.INVALID_PARTICIPANTS
        val proof = verifiedLobbyPeers[participants.single { it != localUid }] ?: return com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.WAITING
        if (System.nanoTime() - proof.probeAt > 15_000_000_000L) return com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.WAITING
        return when (proof.version) {
            com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION -> com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.COMPATIBLE
            0 -> com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.WAITING
            else -> com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.INCOMPATIBLE
        }
    }

    private data class LobbyChallenge(val room: String, val generation: String, val uid: String, val createdAt: Long)
    private val lobbyChallenges = ConcurrentHashMap<String, LobbyChallenge>()
    val roomSessionId: String get() = "$roomConnectionId/$activeMatchConnectionGeneration"
    @Volatile private var roomConnectionId = UUID.randomUUID().toString()

    /** Small avatar-free MQTT exchange; no HTTP or guessed app-version comparison. */
    fun requestLobbyCompatibility() {
        val code = currentRoomCode ?: return
        val player = localPlayer ?: return
        if (!isConnected || !isSubscribed || is1v1MatchActive) return
        val now = System.nanoTime()
        lobbyChallenges.entries.removeIf { now - it.value.createdAt > 5_000_000_000L }
        val id = UUID.randomUUID().toString()
        lobbyChallenges[id] = LobbyChallenge(code, roomConnectionId, player.id, now)
        broadcastPacket(RoomMessagePacket(type = "LOBBY_CAPS_REQUEST", playerId = player.id,
            roomCode = code, requestId = id, connectionGeneration = roomConnectionId, protocolVersion = 2))
    }

    suspend fun awaitReliableCompatibility(participants: List<String>, localUid: String, timeoutMillis: Long = 3_000L): Boolean {
        val code = currentRoomCode ?: return false
        val generation = roomConnectionId
        return withTimeoutOrNull(timeoutMillis) {
            while (currentRoomCode == code && roomConnectionId == generation && localPlayer?.id == localUid) {
                if (canStartReliableMatch(participants, localUid)) return@withTimeoutOrNull true
                if (reliableCompatibilityStatus(participants, localUid) == com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.INVALID_PARTICIPANTS) return@withTimeoutOrNull false
                requestLobbyCompatibility()
                delay(300L)
            }
            false
        } ?: false
    }

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

    // Dedicated 1v1 Match Heartbeat & Availability State
    var activeMatchConnectionGeneration: String = UUID.randomUUID().toString()
        private set
    var is1v1MatchActive: Boolean = false
        private set
    var currentMatchId: String = ""
        private set
    var currentPeerUid: String = ""
        private set
    var matchHeartbeatPolicy: com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicy? = null
        private set
    var lastBrokerReceiveEchoAt: Long = 0L
        private set

    private var matchHeartbeatJob: Job? = null
    private var matchWatchdogJob: Job? = null
    private val outstandingChallenges = ConcurrentHashMap<String, Long>()
    private var heartbeatSequence: Long = 0L

    private val _matchHeartbeatDecisions = MutableSharedFlow<com.bingo.multiplayer.domain.engine.HeartbeatDecision>(extraBufferCapacity = 64)
    val matchHeartbeatDecisions: SharedFlow<com.bingo.multiplayer.domain.engine.HeartbeatDecision> = _matchHeartbeatDecisions.asSharedFlow()

    fun isLocalObservationHealthy(nowMonotonic: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        val inForeground = AppLifecycleObserver.isAppInForeground.value
        val connected = isConnected
        val subscribed = isSubscribed
        val brokerEchoFresh = (nowMonotonic - lastBrokerReceiveEchoAt) <= 5000L
        return inForeground && connected && subscribed && (lastBrokerReceiveEchoAt > 0L && brokerEchoFresh)
    }

    fun startMatchHeartbeat(
        matchId: String,
        peerUid: String,
        policy: com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicy? = null
    ) {
        if (policy == null && is1v1MatchActive && currentMatchId == matchId && currentPeerUid == peerUid && matchHeartbeatPolicy != null) {
            // Already active for this match and peer; idempotent
            return
        }
        stopMatchHeartbeat()
        is1v1MatchActive = true
        currentMatchId = matchId
        currentPeerUid = peerUid
        matchHeartbeatPolicy = policy ?: com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicy(
            matchId = matchId,
            peerUid = peerUid,
            connectionGeneration = activeMatchConnectionGeneration
        )
        lastBrokerReceiveEchoAt = android.os.SystemClock.elapsedRealtime()
        outstandingChallenges.clear()

        // If a restored policy was already UNREACHABLE, emit decision immediately so UI is updated on frame 1
        val activePolicy = matchHeartbeatPolicy
        if (activePolicy != null && activePolicy.state == com.bingo.multiplayer.domain.engine.PeerAvailabilityState.UNREACHABLE) {
            val snap = activePolicy.toSnapshot()
            _matchHeartbeatDecisions.tryEmit(
                com.bingo.multiplayer.domain.engine.HeartbeatDecision.BecameUnreachable(
                    episodeId = snap.currentEpisodeId ?: "ep_${matchId}_${snap.committedEpisodeCount}_${android.os.SystemClock.elapsedRealtime()}",
                    episodeCount = snap.committedEpisodeCount,
                    isClaimEligible = snap.isClaimVictoryEligible
                )
            )
        }

        // Dedicated 2.5s probe loop decoupled from cloud reconciliation
        matchHeartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (AppLifecycleObserver.isAppInForeground.value) {
                    val client = mqttClient
                    if (client != null && client.isConnected && isSubscribed) {
                        val p = localPlayer
                        if (p != null) {
                            val challenge = UUID.randomUUID().toString().take(8)
                            val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                            outstandingChallenges[challenge] = nowMonotonic
                            outstandingChallenges.entries.removeIf { (nowMonotonic - it.value) > 5000L }

                            heartbeatSequence++
                            val probePacket = RoomMessagePacket(
                                type = "MATCH_HEARTBEAT",
                                protocolVersion = 1,
                                matchId = matchId,
                                playerId = p.id,
                                peerUid = peerUid,
                                connectionGeneration = activeMatchConnectionGeneration,
                                sequence = heartbeatSequence,
                                challengeId = challenge,
                                heartbeatAction = "PROBE",
                                timestamp = System.currentTimeMillis()
                            )
                            broadcastPacket(probePacket)
                        }
                    }
                }
                delay(2500L)
            }
        }

        // Dedicated watchdog loop checking at most every 250ms
        matchWatchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(250L)
                checkMatchWatchdog()
            }
        }
    }

    fun stopMatchHeartbeat() {
        matchHeartbeatJob?.cancel()
        matchHeartbeatJob = null
        matchWatchdogJob?.cancel()
        matchWatchdogJob = null
        is1v1MatchActive = false
        matchHeartbeatPolicy = null
        outstandingChallenges.clear()
    }

    fun notifyStateSynchronized() {
        val decision = matchHeartbeatPolicy?.onStateSynchronized() ?: com.bingo.multiplayer.domain.engine.HeartbeatDecision.NoOp
        if (decision !is com.bingo.multiplayer.domain.engine.HeartbeatDecision.NoOp) {
            _matchHeartbeatDecisions.tryEmit(decision)
        }
    }

    fun claimMatchVictory(localUid: String = localPlayer?.id ?: "local_player"): com.bingo.multiplayer.domain.engine.ClaimResult? {
        val policy = matchHeartbeatPolicy ?: return null
        return policy.claimVictory(actorUid = localUid)
    }

    private fun checkMatchWatchdog() {
        val policy = matchHeartbeatPolicy ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val healthy = isLocalObservationHealthy(now)
        if (!healthy) {
            if (policy.isLocalHealthy) {
                val decision = policy.onLocalHealthLost()
                _matchHeartbeatDecisions.tryEmit(decision)
            }
        } else {
            if (!policy.isLocalHealthy) {
                val decision = policy.onLocalHealthRestored(now)
                _matchHeartbeatDecisions.tryEmit(decision)
            }
            val decision = policy.onTick(now)
            if (decision !is com.bingo.multiplayer.domain.engine.HeartbeatDecision.NoOp) {
                _matchHeartbeatDecisions.tryEmit(decision)
            }
        }
    }


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
        val connectionId = roomConnectionId
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
                if (roomConnectionId != connectionId || currentRoomCode != cleanCode) { client.close(); return@launch }
                mqttClient = client

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    NetworkConfig.applyMqttOptions(this)
                    try {
                        val willPacket = RoomMessagePacket(
                            type = "PLAYER_DISCONNECTED",
                            transportOnly = true, roomCode = cleanCode,
                            connectionGeneration = connectionId, senderInstanceId = instanceId,
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
                        if (mqttClient !== client || roomConnectionId != connectionId) return
                        activeMatchConnectionGeneration = UUID.randomUUID().toString()
                        val policy = matchHeartbeatPolicy
                        if (policy != null && !policy.isLocalHealthy) {
                            val decision = policy.onLocalHealthRestored()
                            _matchHeartbeatDecisions.tryEmit(decision)
                        }
                        scope.launch(Dispatchers.IO) {
                            if (mqttClient !== client || roomConnectionId != connectionId) return@launch
                            subscribeToRoom(cleanCode)
                            requestLobbyCompatibility()
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
                        if (mqttClient !== client || roomConnectionId != connectionId) return
                        val payloadBytes = message?.payload ?: return
                        try {
                            val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
                            val packet = FastPacketCodec.decode(payloadStr)
                            if (message.isRetained && topic != null) {
                                reliableRetainedChecks[topic]?.let { (expected, received) ->
                                    if (packet.type == "MATCH_V2_STATE" && packet.reliableSnapshot == expected) received.complete(true)
                                }
                            }
                            if (!message.isRetained || packet.type !in setOf("LOBBY_CAPS_REQUEST", "LOBBY_CAPS_RESPONSE")) {
                                handleIncomingPacket(packet)
                            }
                        } catch (_: Exception) { }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        if (mqttClient !== client || roomConnectionId != connectionId) return
                        isSubscribed = false
                        verifiedReliableTopics.clear()
                        matchHeartbeatPolicy?.let { policy ->
                            if (policy.isLocalHealthy) {
                                val decision = policy.onLocalHealthLost()
                                _matchHeartbeatDecisions.tryEmit(decision)
                            }
                        }
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
                if (!is1v1MatchActive) requestLobbyCompatibility()
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
                lastBrokerReceiveEchoAt = android.os.SystemClock.elapsedRealtime()
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

    private var reliableStateTopic: String? = null
    private val verifiedReliableTopics = ConcurrentHashMap.newKeySet<String>()
    private val reliableRetainedChecks = ConcurrentHashMap<String, Pair<com.bingo.multiplayer.domain.engine.ReliableMatchSnapshot, CompletableDeferred<Boolean>>>()

    /** Confirmed retained transport used only by the version-2 controller. No HTTP per move. */
    suspend fun reliableSubscribe(seed: Long): Boolean = withContext(Dispatchers.IO) {
        val code = currentRoomCode ?: return@withContext false
        val client = mqttClient ?: return@withContext false
        try {
            if (!client.isConnected || !isSubscribed) return@withContext false
            val topic = "bingo/v4/match/$code/$seed/state"
            reliableStateTopic?.takeIf { it != topic }?.let { client.unsubscribe(it).waitForCompletion(3000) }
            client.subscribe(topic, 1).waitForCompletion(3000)
            reliableStateTopic = topic
            true
        } catch (_: Exception) { false }
    }

    suspend fun reliablePublish(packet: RoomMessagePacket, retained: Boolean = false, stateTopic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val code = currentRoomCode ?: return@withContext false
        val client = mqttClient ?: return@withContext false
        try {
            if (!client.isConnected || !isSubscribed) return@withContext false
            val message = MqttMessage(json.encodeToString(packet.copy(senderInstanceId = instanceId)).toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = retained
            }
            val topic = if (stateTopic) "bingo/v4/match/$code/${packet.seed}/state" else getTopic(code)
            client.publish(topic, message).waitForCompletion(3000)
            if (retained && stateTopic && (topic !in verifiedReliableTopics || packet.reliableSnapshot?.final != null)) {
                val expected = packet.reliableSnapshot ?: return@withContext false
                val received = CompletableDeferred<Boolean>()
                reliableRetainedChecks[topic] = expected to received
                try {
                    // A live self echo/PUBACK alone does not prove the broker retained the record.
                    // Resubscription must return the exact snapshot with the MQTT RETAIN flag.
                    client.subscribe(topic, 1).waitForCompletion(3000)
                    withTimeout(3000) { received.await() }
                    verifiedReliableTopics.add(topic)
                } finally { reliableRetainedChecks.remove(topic) }
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Publishes a message packet with optimized QoS routing:
     * High-speed moves (PICK_NUMBER, PING, PONG) use QoS 0 for instant, zero-ACK delivery.
     * Room control packets (START_GAME, PLAY_AGAIN, ROOM_STATE, SURRENDER, LEAVE) use QoS 1 for guaranteed delivery.
     */
    fun broadcastPacket(packet: RoomMessagePacket) {
        val code = currentRoomCode ?: return
        val generation = roomConnectionId
        val scopedPacket = packet.copy(roomCode = packet.roomCode.ifBlank { code })

        scope.launch(Dispatchers.IO) {
            try {
                if (roomConnectionId != generation || currentRoomCode != code) return@launch
                var retryCount = 0
                var client = mqttClient
                val maxRetries = if (packet.type in setOf("MATCH_HEARTBEAT", "MULTIPLAYER_PRESENCE_REQUEST", "MULTIPLAYER_PRESENCE_RESPONSE")) 0 else if (packet.type == "PICK_NUMBER" || packet.type == "PING" || packet.type == "PONG") 5 else 30
                while ((client == null || !client.isConnected) && retryCount < maxRetries) {
                    delay(100L)
                    retryCount++
                    client = mqttClient
                }
                if (client != null && client.isConnected && roomConnectionId == generation && currentRoomCode == code) {
                    val outgoing = scopedPacket.copy(senderInstanceId = packet.senderInstanceId.ifBlank { instanceId },
                        reliablePolicyVersion = com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION,
                        hostWaitVersion = 1,
                        protocolVersion = if (packet.type in setOf("START_GAME", "PLAY_AGAIN") && packet.players.size == 2) 2 else packet.protocolVersion)
                    val payload = FastPacketCodec.encode(outgoing)
                    val qosLevel = when (packet.type) {
                        "PICK_NUMBER", "PING", "PONG", "MATCH_HEARTBEAT", "MULTIPLAYER_PRESENCE_REQUEST", "MULTIPLAYER_PRESENCE_RESPONSE" -> 0 // Instant line-rate flight, zero ACK wait, non-retained, disposable
                        "TURN_TIMEOUT", "GAME_SYNC", "START_GAME", "PLAY_AGAIN", "ROOM_STATE", "SURRENDER", "LEAVE" -> 1 // Guaranteed delivery
                        else -> 1 // Guaranteed delivery for room control & state (START_GAME, PLAY_AGAIN, BOARD_READY, ROOM_STATE, etc.)
                    }
                    val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                        qos = qosLevel
                        if (packet.isMatchFinished || packet.winnerPlayerId.isNotBlank()) {
                            isRetained = true
                        }
                    }
                    client.publish(getTopic(code), message)
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Publishes a message immediately and awaits confirmation/acknowledgment.
     * Guaranteed delivery for permanent departures (SURRENDER, HOST_LEFT).
     */
    suspend fun sendImmediateAndAcknowledge(packet: RoomMessagePacket, timeoutMs: Long = 800L): Boolean = withContext(Dispatchers.IO) {
        val code = currentRoomCode ?: return@withContext false
        val generation = roomConnectionId
        val scopedPacket = packet.copy(roomCode = packet.roomCode.ifBlank { code })
        try {
            val client = mqttClient ?: return@withContext false
            if (!client.isConnected || roomConnectionId != generation || currentRoomCode != code) return@withContext false
            val outgoing = scopedPacket.copy(
                senderInstanceId = packet.senderInstanceId.ifBlank { instanceId },
                reliablePolicyVersion = com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION,
                hostWaitVersion = 1,
                protocolVersion = if (packet.type in setOf("START_GAME", "PLAY_AGAIN") && packet.players.size == 2) 2 else packet.protocolVersion
            )
            val payload = FastPacketCodec.encode(outgoing)
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                if (packet.isMatchFinished || packet.winnerPlayerId.isNotBlank()) {
                    isRetained = true
                }
            }
            val token = client.publish(getTopic(code), message)
            token?.waitForCompletion(timeoutMs)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Periodic and on-demand cloud state reconciliation:
     * Guarantees 100% reliable discovery of joined players and host presence
     * even if MQTT packets are delayed or dropped on cellular networks.
     */
    private suspend fun reconcileWithCloud(code: String, localP: Player) {
        val generation = roomConnectionId
        try {
            val cloudSession = roomRegistry.sync(code, localP, playerRegistry.values.toList(), kickedPlayerIds) ?: return
            if (currentRoomCode != code || roomConnectionId != generation) return
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
                    val isLiveMqttPeer = existing != null && (now - existing.lastSeenTimestamp) < 15_000L && existing.readyVersion >= p.readyVersion
                    val (effReady, effVer) = when {
                        isLocal -> Pair(localP.lobbyReadyStatus, localP.readyVersion)
                        isLiveMqttPeer -> Pair(existing!!.lobbyReadyStatus, existing.readyVersion)
                        else -> LobbyLifecycleEngine.reconcileReadyStatus(
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
            // If the host has started the game ("PLAYING"), local player is a guest,
            // and seed is valid, unstarted, and not completed, transition into game!
            if (cloudSession.status == "PLAYING" &&
                !localP.isHost &&
                cloudSession.currentSeed != 0L &&
                localP.lobbyReadyStatus == com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.STATUS_READY &&
                cloudSession.currentSeed != lastStartedMatchSeed &&
                !completedMatchSeeds.contains(cloudSession.currentSeed)
            ) {
                val participants = cloudSession.players.distinctBy { it.id }
                val isTwoPlayer = participants.size == 2
                if (isTwoPlayer && !canStartReliableMatch(participants.map { it.id }, localP.id)) {
                    requestLobbyCompatibility()
                    return
                }
                lastStartedMatchSeed = cloudSession.currentSeed
                _incomingPackets.tryEmit(
                    RoomMessagePacket(
                        type = "START_GAME",
                        boardSize = cloudSession.boardSize,
                        seed = cloudSession.currentSeed,
                        matchId = cloudSession.currentSeed.toString(),
                        playerId = cloudSession.hostId,
                        protocolVersion = if (isTwoPlayer) 2 else 0,
                        reliablePolicyVersion = if (isTwoPlayer) com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION else 0,
                        hostWaitVersion = if (isTwoPlayer) 1 else 0,
                        players = participants,
                        isManualBoard = cloudSession.isManualBoard,
                        isDynamicBoard = cloudSession.isDynamicBoard
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

    internal fun handleIncomingPacket(packet: RoomMessagePacket) {
        if (packet.roomCode.isNotBlank() && packet.roomCode != currentRoomCode) return
        if (packet.type in setOf("LOBBY_CAPS_REQUEST", "LOBBY_CAPS_RESPONSE")) {
            val code = currentRoomCode ?: return
            val me = localPlayer ?: return
            if (packet.roomCode != code || packet.playerId.isBlank() || packet.playerId == me.id ||
                packet.senderInstanceId.isBlank() || packet.senderInstanceId == instanceId ||
                (packet.targetPlayerId.isNotBlank() && packet.targetPlayerId != me.id)) return
            if (packet.type == "LOBBY_CAPS_REQUEST") {
                if (packet.requestId.isBlank() || packet.connectionGeneration.isBlank()) return
                broadcastPacket(RoomMessagePacket(type = "LOBBY_CAPS_RESPONSE", playerId = me.id,
                    targetPlayerId = packet.playerId, roomCode = code, requestId = packet.requestId,
                    connectionGeneration = packet.connectionGeneration, protocolVersion = 2,
                    reliablePolicyVersion = com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION,
                    hostWaitVersion = 1))
            } else {
                val expected = lobbyChallenges[packet.requestId] ?: return
                if (expected.room != code || expected.generation != roomConnectionId || expected.uid != me.id ||
                    packet.targetPlayerId != me.id || packet.connectionGeneration != expected.generation ||
                    System.nanoTime() - expected.createdAt > 5_000_000_000L) return
                if (packet.reliablePolicyVersion == com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.VERSION &&
                    (packet.protocolVersion != 2 || packet.hostWaitVersion != 1)) return
                verifiedLobbyPeers.compute(packet.playerId) { _, previous ->
                    if (previous != null && previous.probeAt > expected.createdAt) previous
                    else VerifiedLobbyPeer(packet.reliablePolicyVersion, expected.createdAt)
                }
                // One broadcast probe can receive a response from each lobby participant.
            }
            return
        }
        // Advertisements can be delayed across reconnects. Only a response to our
        // current room/connection nonce is proof of the peer's current capabilities.
        if (packet.type.startsWith("MATCH_V2_")) {
            _incomingPackets.tryEmit(packet)
            return
        }
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

        // Live Heartbeat/Packet Touch: Any incoming packet confirms player is actively communicating and broker connection is healthy
        lastBrokerReceiveEchoAt = android.os.SystemClock.elapsedRealtime()
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
                                roomRegistry.ready(code, packet.playerId, updated.lobbyReadyStatus, updated.readyVersion)
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
                                roomRegistry.ready(code, targetKey, updated.lobbyReadyStatus, updated.readyVersion)
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
                if (is1v1MatchActive) {
                    // Protected by 1v1 heartbeat policy: transport disconnects or Last Will
                    // must never alter immutable match participants or cause premature departure
                    return
                }
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
                                    roomRegistry.ready(code, packet.playerId, updated.lobbyReadyStatus, updated.readyVersion)
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
                        val brokerRtt = System.currentTimeMillis() - packet.pingTimestamp
                        if (brokerRtt in 0L..5000L) {
                            lastBrokerReceiveEchoAt = android.os.SystemClock.elapsedRealtime()
                            val cur = _pingMs.value
                            val smoothed = if (cur <= 0L) brokerRtt.coerceAtLeast(1L) else ((cur * 0.60) + (brokerRtt.coerceAtLeast(1L) * 0.40)).toLong().coerceAtLeast(1L)
                            _pingMs.value = smoothed
                            NetworkPingMonitor.recordExternalPing(smoothed)
                        }
                    } else {
                        lastBrokerReceiveEchoAt = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }

            "MATCH_HEARTBEAT" -> {
                val policy = matchHeartbeatPolicy
                if (policy != null && packet.matchId == policy.matchId) {
                    val myId = localPlayer?.id ?: ""
                    val isForMe = packet.peerUid == myId || com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.peerUid, myId)
                    val isFromPeer = packet.playerId == policy.peerUid || com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, policy.peerUid)
                    if (isForMe && isFromPeer) {
                        if (packet.heartbeatAction == "PROBE") {
                            // Foreground peer sent PROBE; reply immediately with ACK addressing requesting generation
                            if (AppLifecycleObserver.isAppInForeground.value) {
                                val ackPacket = RoomMessagePacket(
                                    type = "MATCH_HEARTBEAT",
                                    protocolVersion = packet.protocolVersion,
                                    matchId = packet.matchId,
                                    playerId = myId,
                                    peerUid = packet.playerId,
                                    connectionGeneration = packet.connectionGeneration,
                                    sequence = packet.sequence,
                                    challengeId = packet.challengeId,
                                    heartbeatAction = "ACK",
                                    timestamp = System.currentTimeMillis()
                                )
                                broadcastPacket(ackPacket)
                            }
                        } else if (packet.heartbeatAction == "ACK") {
                            // Verify generation matches our active connection generation
                            if (packet.connectionGeneration == activeMatchConnectionGeneration) {
                                val challengeSentAt = outstandingChallenges.remove(packet.challengeId)
                                val nowMonotonic = android.os.SystemClock.elapsedRealtime()
                                if (challengeSentAt != null && (nowMonotonic - challengeSentAt) <= 5000L) {
                                    val decision = policy.onFreshPeerResponse(nowMonotonic)
                                    if (decision !is com.bingo.multiplayer.domain.engine.HeartbeatDecision.NoOp) {
                                        _matchHeartbeatDecisions.tryEmit(decision)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "PONG" -> {
                // Legacy PONG handling ignored to prevent multi-device ping jitter
            }
        }

        // Note: Generic fallback for non-heartbeat packets was removed per HB12.
        // Only dedicated challenge-response ACKs with valid generation and latency <= 5s
        // establish peer liveness. State synchronization is notified when validated and applied by coordinator.

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
        requestLobbyCompatibility()
        scope.launch(Dispatchers.IO) {
            delay(150L)
            broadcastPacket(packet)
            delay(300L)
            broadcastPacket(packet)
        }
        val code = currentRoomCode
        if (code != null) {
            scope.launch(Dispatchers.IO) {
                roomRegistry.ready(code, p.id, updated.lobbyReadyStatus, updated.readyVersion)
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
                roomRegistry.ready(code, playerId, updated.lobbyReadyStatus, updated.readyVersion)
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
                roomRegistry.remove(code, playerId)
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
        roomConnectionId = UUID.randomUUID().toString()
        lobbyChallenges.clear()
        verifiedLobbyPeers.clear()
        val p = localPlayer
        val code = currentRoomCode
        val client = mqttClient

        if (client != null && client.isConnected && p != null && code != null) {
            try {
                val leavePacket = RoomMessagePacket(
                    type = "LEAVE",
                    transportOnly = true, roomCode = code,
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
                    roomRegistry.close(code)
                }
            }
        }

        stopMatchHeartbeat()
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
                    client.disconnect().waitForCompletion(1500L)
                }
            } catch (_: Exception) {
                try { client?.disconnectForcibly(1000L, 1000L, false) } catch (_: Exception) { }
            } finally {
                // Closing before asynchronous DISCONNECT completes can leave Paho's ping timer alive.
                try { client?.close(true) } catch (_: Exception) { }
            }
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
