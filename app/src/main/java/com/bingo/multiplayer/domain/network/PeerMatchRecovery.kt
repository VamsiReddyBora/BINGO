package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.domain.engine.MatchPick

/** Retained, match-scoped recovery. This is transport/storage, not a match referee. */
object PeerMatchRecovery {
    fun topic(room: String, seed: Long) = "bingo/emqx7/match/${room.trim().uppercase()}/$seed"
    data class Lookup(val available: Boolean, val packets: List<RoomMessagePacket> = emptyList())

    /** Validates retained data before it may refresh inactivity, change a board or establish finality. */
    fun merge(local: OngoingMatchData, packets: List<RoomMessagePacket>, now: Long): OngoingMatchData {
        if (local.isMatchFinished) return local
        var data = local
        val ids = local.participants.map { it.id }
        val acceptedPackets = packets.map { MatchPacketClock.localize(it, local.clockOffsetMillis) }.filter { SimplifiedMatchPacketGate.accepts(it, local.matchSeed, local.roomCode, ids.toSet()) }
        for (packet in acceptedPackets.filter { it.type == "GAME_SYNC" }.sortedWith(
            compareBy<RoomMessagePacket> { it.turnNumber }.thenBy { it.timestamp }.thenBy { it.sequence })) {
            val player = data.playerBoard ?: continue
            val opponent = data.opponentBoard ?: continue
            var boards = data.allPlayerBoards
            var valid = true
            for ((id, numbers) in packet.boardNumbers) {
                if (id !in ids || !ManualBoardEngine.isBoardComplete(numbers, data.boardSize) ||
                    (boards[id] != null && boards[id]?.cells?.map { it.number } != numbers)) { valid = false; break }
                if (boards[id] == null) boards = boards + (id to ManualBoardEngine.buildBoard(numbers, data.boardSize))
            }
            if (!valid) continue
            val applied = MultiplayerSnapshotReconciler.reconcile(packet, data.matchSeed, "", data.accountUid,
                data.multiplayerSnapshotCheckpoint, data.pickedNumbers.mapIndexed { i, n ->
                    MatchPick(n, data.pickedByPlayers.getOrNull(i).orEmpty(), data.turnIds.getOrNull(i) ?: i + 1)
                }, player, opponent, boards, true, ids, data.randomizedTurnOrder.map { it.id }) ?: continue
            val departed = data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds + applied.checkpoint.surrenderedPlayerIds
            val result = MultiplayerResultRules.evaluate(applied.allPlayerBoards, ids, departed,
                applied.picks.lastOrNull()?.playerId.orEmpty(), data.randomizedTurnOrder.map { it.id })
            val pauseIsNewer = packet.pauseEpisode >= data.pauseControlRevision
            val chat = packet.chatMessages.map { m -> m.copy(isSelf = !m.isSystemMessage && m.senderPlayerId == data.accountUid,
                senderName = if (m.isSystemMessage || m.senderPlayerId == data.accountUid) null else
                    data.participants.firstOrNull { it.id == m.senderPlayerId }?.displayName ?: m.senderName) }
            var presence = data.presenceState
            packet.playerPresences.filterKeys { it in ids }.forEach { (id, record) -> presence = presence.update(id, record) }
            data = data.copy(playerBoard = applied.playerBoard, opponentBoard = applied.opponentBoard,
                allPlayerBoards = applied.allPlayerBoards, pickedNumbers = applied.picks.map { it.number },
                pickedByPlayers = applied.picks.map { it.playerId }, turnIds = applied.picks.map { it.turn },
                turnNumber = if (applied.hasPendingPicks) data.turnNumber else packet.turnNumber,
                currentTurnPlayerId = if (applied.hasPendingPicks) data.currentTurnPlayerId else packet.currentTurnPlayerId,
                multiplayerSnapshotCheckpoint = applied.checkpoint,
                turnClock = if (!applied.hasPendingPicks && pauseIsNewer) packet.turnClock?.let { incoming ->
                    data.turnClock?.takeIf { it.turnNumber == incoming.turnNumber && it.playerId == incoming.playerId }?.merge(incoming) ?: incoming
                } ?: data.turnClock else data.turnClock,
                remainingTurnMillis = if (!applied.hasPendingPicks && pauseIsNewer &&
                    (packet.turnNumber > data.turnNumber || packet.timestamp >= local.lastUpdatedAt ||
                        (packet.turnClock?.changedAt ?: 0L) > (data.turnClock?.changedAt ?: 0L)))
                    packet.turnClock?.frozenRemainingMillis ?: packet.remainingTurnMillis else data.remainingTurnMillis,
                pauseControlRevision = if (pauseIsNewer) packet.pauseEpisode else data.pauseControlRevision,
                isPaused = if (pauseIsNewer) packet.isPaused else data.isPaused,
                pauseOwnerId = if (pauseIsNewer) packet.pauseOwnerId else data.pauseOwnerId,
                chatMessages = (data.chatMessages + chat).distinctBy { it.id }.sortedBy { it.timestamp },
                lastPlayerActivityAt = maxOf(data.lastPlayerActivityAt, packet.lastPlayerActivityAt.coerceAtMost(now)),
                presenceState = presence, matchRevision = if (packet.playerId == data.accountUid) maxOf(data.matchRevision, packet.sequence) else data.matchRevision,
                isMatchFinished = result != null, winnerPlayerId = result?.winnerId.orEmpty(),
                runnerPlayerIds = result?.runnerIds ?: emptyList(), winReason = result?.reason.orEmpty(),
                lastUpdatedAt = maxOf(data.lastUpdatedAt, packet.timestamp))
            if (data.isMatchFinished) return data
        }
        val pendingCommands = prioritizeAndSortCommands(acceptedPackets).toMutableList()
        var advanced = true
        while (advanced && pendingCommands.isNotEmpty() && !data.isMatchFinished) {
            advanced = false
            val iterator = pendingCommands.iterator()
            while (iterator.hasNext()) {
                val packet = iterator.next()
                val effectiveTurn = if (packet.closedTurnNumber > 0) packet.closedTurnNumber else packet.turnNumber
                if (packet.type == "SURRENDER" && effectiveTurn > data.turnNumber) {
                    // Future surrender belonging to a later turn; defer until that turn is reached
                    continue
                }
                val player = data.playerBoard ?: break
                val opponent = data.opponentBoard ?: break
                val ctx = MultiplayerGameplayContext(data.matchSeed, data.accountUid, "", data.participants,
                    data.randomizedTurnOrder, data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
                    allowPeerTimeouts = true, strictIdentity = true)
                val currentClock = data.turnClock
                val closedClock = packet.closedTurnClock
                val subsequentTurnExists = pendingCommands.any { cmdTurn(it) > cmdTurn(packet) }
                val confirmedPicks = acceptedPackets.flatMap { it.pickedHistory }.toSet()
                val turnHasConfirmedPick = pendingCommands.any {
                    it.type == "PICK_NUMBER" && cmdTurn(it) == cmdTurn(packet) &&
                    (it.recoveryReplay || confirmedPicks.contains(it.number))
                }
                val isAcceptedTimeout = packet.recoveryReplay || (subsequentTurnExists && !turnHasConfirmedPick)
                val matchesWelcomeBack = currentClock == null ||
                    (currentClock.welcomeBackGranted == closedClock?.welcomeBackGranted) ||
                    (closedClock?.welcomeBackGranted == true)
                val isResumedClock = currentClock != null && closedClock != null &&
                    currentClock.frozenRemainingMillis != null &&
                    closedClock.frozenRemainingMillis == null &&
                    closedClock.changedAt >= currentClock.changedAt &&
                    closedClock.initialBudgetMillis == currentClock.initialBudgetMillis

                val reconstructedClock = if (data.isPaused) currentClock else {
                    if (packet.type == "TURN_TIMEOUT" && closedClock != null && currentClock != null &&
                        currentClock.turnNumber == closedClock.turnNumber &&
                        currentClock.playerId == closedClock.playerId &&
                        closedClock.valid() &&
                        isAcceptedTimeout &&
                        matchesWelcomeBack &&
                        (isResumedClock || currentClock.frozenRemainingMillis == null)) {
                        closedClock
                    } else {
                        currentClock?.copy(frozenRemainingMillis = null)
                    }
                }
                val before = MultiplayerGameplayState(data.pickedNumbers.mapIndexed { i, n ->
                    MatchPick(n, data.pickedByPlayers.getOrNull(i).orEmpty(), data.turnIds.getOrNull(i) ?: i + 1)
                }, player, opponent, data.allPlayerBoards, data.turnNumber, data.currentTurnPlayerId,
                    data.remainingTurnMillis ?: 0L, isPaused = data.isPaused,
                    surrenderedPlayerIds = data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
                    turnClock = reconstructedClock)
                val decision = when (packet.type) {
                    "PICK_NUMBER" -> MultiplayerGameplayReducer.pick(packet, ctx, before)
                    "TURN_TIMEOUT" -> MultiplayerGameplayReducer.timeout(packet, ctx, before)
                    else -> MultiplayerGameplayReducer.surrender(packet, ctx, before)
                }
                if (!decision.accepted) {
                    val closedTurn = if (packet.closedTurnNumber > 0) packet.closedTurnNumber else (packet.turnNumber - 1)
                    val isAlreadySurrendered = packet.type == "SURRENDER" && packet.playerId in data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds
                    val isAlreadyPicked = packet.type == "PICK_NUMBER" && data.pickedNumbers.contains(packet.number)
                    val isObsoleteTurn = (closedTurn in 1 until data.turnNumber) || (packet.turnNumber in 1 until data.turnNumber)
                    val isStaleTimeoutProposal = packet.type == "TURN_TIMEOUT" && !packet.recoveryReplay &&
                        currentClock != null &&
                        currentClock.turnNumber == (closedClock?.turnNumber ?: packet.turnNumber) &&
                        currentClock.playerId == (closedClock?.playerId ?: packet.playerId) &&
                        (!matchesWelcomeBack || (closedClock != null && currentClock.changedAt > closedClock.changedAt) || (currentClock.frozenRemainingMillis != null && !isResumedClock))
                    if (isAlreadySurrendered || isAlreadyPicked || isObsoleteTurn || isStaleTimeoutProposal) {
                        iterator.remove()
                    }
                    continue
                }
                iterator.remove()
                advanced = true
                val next = decision.state
                val changedTurn = next.turnNumber != data.turnNumber || next.currentPlayerId != data.currentTurnPlayerId
                var resolvedPresence = data.presenceState
                packet.playerPresences.forEach { (id, record) ->
                    resolvedPresence = resolvedPresence.update(id, record)
                }
                val isAbsent = resolvedPresence.isAbsent(next.currentPlayerId)
                val clock = if (changedTurn) {
                    resolveTransitionClock(packet, next.turnNumber, next.currentPlayerId, now, isAbsent, data.turnClock)
                } else {
                    data.turnClock ?: MultiplayerTurnClock.start(next.turnNumber, next.currentPlayerId, now, isAbsent)
                }
                val result = MultiplayerResultRules.evaluate(next.allPlayerBoards, ids, next.surrenderedPlayerIds,
                    next.picks.lastOrNull()?.playerId.orEmpty(), data.randomizedTurnOrder.map { it.id })
                val notice = if (packet.type == "SURRENDER") com.bingo.multiplayer.domain.model.InGameChatMessage(
                    id = ("${data.matchSeed}:exit:${packet.playerId}").hashCode().toLong(),
                    text = "🏳️ ${data.participants.firstOrNull { it.id == packet.playerId }?.displayName ?: "Player"} surrendered",
                    isSelf = false, isSystemMessage = true, timestamp = packet.timestamp) else null
                val remainingMillis = if (!changedTurn) {
                    data.remainingTurnMillis ?: clock.frozenRemainingMillis ?: clock.remainingMillis(now).takeIf { it > 0L } ?: clock.initialBudgetMillis
                } else {
                    clock.frozenRemainingMillis
                        ?: packet.turnClock?.frozenRemainingMillis
                        ?: packet.remainingTurnMillis.takeIf { it in 1L..clock.initialBudgetMillis }
                        ?: clock.remainingMillis(now).takeIf { it > 0L }
                        ?: clock.initialBudgetMillis
                }
                data = data.copy(playerBoard = next.playerBoard, opponentBoard = next.opponentBoard, allPlayerBoards = next.allPlayerBoards,
                    pickedNumbers = next.picks.map { it.number }, pickedByPlayers = next.picks.map { it.playerId }, turnIds = next.picks.map { it.turn },
                    turnNumber = next.turnNumber, currentTurnPlayerId = next.currentPlayerId, turnClock = clock,
                    remainingTurnMillis = remainingMillis,
                    chatMessages = if (notice != null) (data.chatMessages + notice).distinctBy { it.id } else data.chatMessages,
                    multiplayerSnapshotCheckpoint = data.multiplayerSnapshotCheckpoint.copy(turnNumber = next.turnNumber, picks = next.picks,
                        surrenderedPlayerIds = next.surrenderedPlayerIds),
                    lastPlayerActivityAt = if (packet.type != "TURN_TIMEOUT") maxOf(data.lastPlayerActivityAt, packet.timestamp.coerceAtMost(now))
                        else data.lastPlayerActivityAt,
                    isMatchFinished = result != null, winnerPlayerId = result?.winnerId.orEmpty(),
                    runnerPlayerIds = result?.runnerIds ?: emptyList(), winReason = result?.reason.orEmpty())
                if (data.isMatchFinished) return data
                break
            }
        }
        var presence = data.presenceState
        for (p in acceptedPackets.filter { it.type in setOf("PLAYER_DISCONNECTED", "PLAYER_PRESENCE") }) {
            val rec = p.playerPresences[p.playerId] ?: MultiplayerPresenceRecord(
                p.connectionGeneration, p.senderInstanceId, p.lifecycleSequence, p.timestamp,
                if (p.type == "PLAYER_DISCONNECTED") "OFFLINE" else p.presenceStatus.ifBlank { p.readyStatus }
            )
            presence = presence.update(p.playerId, rec, isLastWill = p.type == "PLAYER_DISCONNECTED")
        }
        val eligible = data.participants.filter { it.id !in data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds }
        var finalClock = data.turnClock
        if (!data.isPaused && eligible.isNotEmpty() && eligible.all { presence.isAbsent(it.id) }) {
            if (finalClock?.frozenRemainingMillis == null) {
                val freezeRemainder = data.remainingTurnMillis ?: finalClock?.remainingMillis(now)
                finalClock = finalClock?.copy(frozenRemainingMillis = freezeRemainder)
            }
        }
        return data.copy(presenceState = presence, turnClock = finalClock)
    }

    suspend fun read(room: String, seed: Long): Lookup = withContext(Dispatchers.IO) {
        val client = MqttAsyncClient(NetworkConfig.BROKER_URL, "recover_${UUID.randomUUID()}", MemoryPersistence())
        try {
            val packets = mutableListOf<RoomMessagePacket>()
            val done = CompletableDeferred<Unit>()
            val marker = "${topic(room, seed)}/probe/${UUID.randomUUID()}"
            val listener = org.eclipse.paho.client.mqttv3.IMqttMessageListener { t, message ->
                println("PeerMatchRecovery.read arrived: topic=$t retained=${message?.isRetained}")
                if (t == marker) { done.complete(Unit); return@IMqttMessageListener }
                val bytes = message?.payload?.takeIf { it.isNotEmpty() } ?: return@IMqttMessageListener
                val decoded = FastPacketCodec.decode(bytes.toString(Charsets.UTF_8))
                val isOutcome = t?.contains("/outcome/") == true
                val isAcceptedDeparture = t?.contains("/accepted_exit/") == true
                val isExitReceipt = (t?.contains("/exit/") == true || decoded.closedTurnNumber > 0 || decoded.recoveryReplay) && decoded.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT")
                val packet = decoded.copy(
                    recoveryReplay = decoded.recoveryReplay || isOutcome || isAcceptedDeparture || isExitReceipt,
                    isRetainedDelivery = message.isRetained)
                if (packet.seed == seed && (packet.roomCode.isBlank() || packet.roomCode == room.trim().uppercase()) &&
                    packet.type in setOf("GAME_SYNC", "SURRENDER", "PICK_NUMBER", "TURN_TIMEOUT")) {
                    synchronized(packets) { packets.add(packet.copy(roomCode = room.trim().uppercase())) }
                }
            }
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) { done.completeExceptionally(cause ?: IllegalStateException("Connection lost")) }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun messageArrived(t: String?, message: MqttMessage?) {
                    listener.messageArrived(t, message)
                }
            })
            client.connect(MqttConnectOptions().apply {
                isCleanSession = true; connectionTimeout = 4; NetworkConfig.applyMqttOptions(this)
            }).waitForCompletion(5_000L)
            client.subscribe("${topic(room, seed)}/#", 1, listener).waitForCompletion(3_000L)
            delay(1500L)
            client.publish(marker, MqttMessage(byteArrayOf(1)).apply { qos = 1 }).waitForCompletion(3_000L)
            withTimeout(4_000L) { done.await() }
            delay(500L)
            Lookup(true, synchronized(packets) { packets.toList() })
        } catch (_: Exception) { Lookup(false) }
        finally {
            MqttClientShutdown.close(client, quiesceMillis = 500L, disconnectMillis = 500L)
        }
    }

    /** An independent connection makes old exit retries unable to disconnect a replacement lobby. */
    suspend fun publishExit(exit: PendingMatchExit): Boolean = withContext(Dispatchers.IO) {
        // A successor follows the existing gameplay authority only after this exit is seen.
        // Drain the retired writer first so its earlier publish cannot arrive after handoff.
        RoomMetadataApi.prepareExitDelivery(exit)
        val client = MqttAsyncClient(NetworkConfig.BROKER_URL, "exit_${UUID.randomUUID()}", MemoryPersistence())
        try {
            val packet = exit.packet.copy(roomCode = exit.roomCode.trim().uppercase(), senderPlayerId = exit.accountUid,
                senderInstanceId = "exit:${exit.accountUid}:${exit.packet.seed}")
            client.connect(MqttConnectOptions().apply {
                isCleanSession = true; connectionTimeout = 4; NetworkConfig.applyMqttOptions(this)
            }).waitForCompletion(5_000L)
            val payload = FastPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
            // Store the receipt before notifying live subscribers. All returning peers subscribe to this prefix.
            client.publish("${topic(exit.roomCode, packet.seed)}/exit/${exit.accountUid}",
                MqttMessage(payload).apply { qos = 1; isRetained = true }).waitForCompletion(4_000L)
            client.publish("bingo/emqx7/room/${exit.roomCode.trim().uppercase()}",
                MqttMessage(payload).apply { qos = 1 }).waitForCompletion(4_000L)
            true
        } catch (_: Exception) { false }
        finally {
            MqttClientShutdown.close(client, quiesceMillis = 500L, disconnectMillis = 500L)
        }
    }

    fun cmdTurn(packet: RoomMessagePacket): Int {
        if (packet.closedTurnNumber > 0) return packet.closedTurnNumber
        return if (packet.turnNumber > 0) packet.turnNumber else 0
    }

    fun resolveTransitionClock(
        packet: RoomMessagePacket,
        nextTurnNumber: Int,
        nextPlayerId: String,
        now: Long,
        isAbsent: Boolean,
        currentClock: MultiplayerTurnClock? = null
    ): MultiplayerTurnClock {
        val isDeparture = packet.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT")
        val isAcceptedDeparture = isDeparture && (packet.recoveryReplay || packet.closedTurnNumber > 0)
        val isUnchangedTurn = currentClock != null && currentClock.turnNumber == nextTurnNumber && currentClock.playerId == nextPlayerId
        val matchingPacketClock = packet.turnClock?.takeIf {
            it.valid() && it.turnNumber == nextTurnNumber && it.playerId == nextPlayerId
        }
        return if (isDeparture && isUnchangedTurn) {
            currentClock!!
        } else if (isDeparture && !isAcceptedDeparture) {
            MultiplayerTurnClock.start(nextTurnNumber, nextPlayerId, now, isAbsent)
        } else {
            matchingPacketClock
                ?: currentClock?.takeIf { it.turnNumber == nextTurnNumber && it.playerId == nextPlayerId }
                ?: if (isDeparture) {
                    MultiplayerTurnClock.start(nextTurnNumber, nextPlayerId, now, isAbsent)
                } else {
                    MultiplayerTurnClock.start(nextTurnNumber, nextPlayerId, packet.timestamp, isAbsent)
                }
        }
    }

    fun buildAcceptedDepartureReceipt(
        packet: RoomMessagePacket,
        preTurnNumber: Int,
        preTurnPlayerId: String,
        nextTurnNumber: Int,
        nextPlayerId: String,
        successorClock: MultiplayerTurnClock?
    ): RoomMessagePacket {
        val closesTurn = nextTurnNumber > preTurnNumber || (nextPlayerId != preTurnPlayerId && packet.playerId == preTurnPlayerId)
        return packet.copy(
            turnNumber = if (closesTurn) nextTurnNumber else preTurnNumber,
            closedTurnNumber = if (closesTurn) preTurnNumber else 0,
            turnClock = if (closesTurn) successorClock else packet.turnClock,
            recoveryReplay = true
        )
    }

    fun prioritizeAndSortCommands(
        commands: List<RoomMessagePacket>
    ): List<RoomMessagePacket> {
        val deduplicatedCommands = mutableListOf<RoomMessagePacket>()
        for (cmd in commands.filter { it.type in setOf("PICK_NUMBER", "TURN_TIMEOUT", "SURRENDER") }) {
            val existingIndex = deduplicatedCommands.indexOfFirst {
                if (cmd.type == "SURRENDER") {
                    it.type == cmd.type && it.playerId == cmd.playerId
                } else {
                    it.type == cmd.type &&
                    it.closedTurnNumber == cmd.closedTurnNumber &&
                    (if (it.turnNumber > 0) it.turnNumber else 0) == (if (cmd.turnNumber > 0) cmd.turnNumber else 0) &&
                    it.playerId == cmd.playerId &&
                    it.number == cmd.number &&
                    it.sequence == cmd.sequence &&
                    it.timestamp == cmd.timestamp
                }
            }
            if (existingIndex < 0) {
                deduplicatedCommands.add(cmd)
            } else if ((cmd.recoveryReplay && !deduplicatedCommands[existingIndex].recoveryReplay) ||
                (!deduplicatedCommands[existingIndex].recoveryReplay && cmd.closedTurnNumber > deduplicatedCommands[existingIndex].closedTurnNumber) ||
                (cmd.recoveryReplay && deduplicatedCommands[existingIndex].recoveryReplay && cmd.closedTurnNumber > deduplicatedCommands[existingIndex].closedTurnNumber)) {
                deduplicatedCommands[existingIndex] = cmd
            }
        }

        val confirmedPicks = deduplicatedCommands.flatMap { it.pickedHistory }.toSet()
        val turnOwners = mutableMapOf<Int, String>()
        val turnsWithPicks = mutableSetOf<Int>()
        val allTurns = deduplicatedCommands.map { cmdTurn(it) }.distinct()
        for (turn in allTurns) {
            val committedTurnOutcome = deduplicatedCommands.firstOrNull {
                it.recoveryReplay && it.type in setOf("PICK_NUMBER", "TURN_TIMEOUT") && cmdTurn(it) == turn
            }
            val ownerId = committedTurnOutcome?.playerId
                ?: deduplicatedCommands.firstOrNull {
                    it.type in setOf("PICK_NUMBER", "TURN_TIMEOUT") && cmdTurn(it) == turn
                }?.playerId
                ?: deduplicatedCommands.firstOrNull {
                    it.recoveryReplay && it.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT") && it.closedTurnNumber == turn
                }?.playerId
            if (ownerId != null) turnOwners[turn] = ownerId
            if (deduplicatedCommands.any {
                it.type == "PICK_NUMBER" && cmdTurn(it) == turn &&
                (it.recoveryReplay || confirmedPicks.contains(it.number))
            }) {
                turnsWithPicks.add(turn)
            }
        }
        val maxTurn = allTurns.maxOrNull() ?: 0

        fun commandPriority(packet: RoomMessagePacket): Int {
            val turn = cmdTurn(packet)
            val isDeparture = packet.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT")
            val turnPlayerId = turnOwners[turn]
            val turnHasConfirmedPick = turnsWithPicks.contains(turn)
            val precedingPickBySamePlayer = isDeparture && deduplicatedCommands.any {
                it.type == "PICK_NUMBER" && it.playerId == packet.playerId &&
                cmdTurn(it) == turn && (it.recoveryReplay || confirmedPicks.contains(it.number) || turnHasConfirmedPick) &&
                it.timestamp <= packet.timestamp
            }
            val isOffTurnSurrender = isDeparture && (
                (packet.recoveryReplay && packet.closedTurnNumber == 0) ||
                (!precedingPickBySamePlayer && (turnPlayerId == null || packet.playerId != turnPlayerId))
            )
            val subsequentTurnExists = maxTurn > turn
            return when {
                isOffTurnSurrender -> 0
                packet.recoveryReplay && packet.type == "PICK_NUMBER" -> 1
                packet.recoveryReplay -> 1
                packet.type == "PICK_NUMBER" && (confirmedPicks.contains(packet.number) || turnHasConfirmedPick) -> 2
                packet.type == "TURN_TIMEOUT" && subsequentTurnExists && !turnHasConfirmedPick -> 3
                packet.type == "TURN_TIMEOUT" && packet.closedTurnClock != null && packet.timestamp >= packet.closedTurnClock.deadlineAt -> 4
                isDeparture && precedingPickBySamePlayer -> 5
                else -> 5
            }
        }

        return deduplicatedCommands
            .mapIndexed { index, packet ->
                PrioritizedCommand(packet, cmdTurn(packet), commandPriority(packet), packet.timestamp, index)
            }
            .sortedWith(
                compareBy<PrioritizedCommand> { it.turn }
                    .thenBy { it.priority }
                    .thenBy { it.timestamp }
                    .thenBy { it.originalIndex }
            )
            .map { it.packet }
    }
}

private data class PrioritizedCommand(
    val packet: RoomMessagePacket,
    val turn: Int,
    val priority: Int,
    val timestamp: Long,
    val originalIndex: Int
)

class PeerRecoverySession(
    initialData: OngoingMatchData,
    val localUid: String,
    val isColdRejoin: Boolean = true,
    val isGamePaused: Boolean = initialData.isPaused,
    val pendingHumanReturn: Boolean = false,
    val pendingHumanReturnAt: Long = 0L,
    val initialFenceCompleted: Boolean = false
) {
    var data: OngoingMatchData = initialData
        private set
    var recoveryFenceCompleted: Boolean = initialFenceCompleted
        private set
    var isReconciling: Boolean = true
        private set
    var awaitingHostStateSync: Boolean = true
        private set
    val bufferedCommands = mutableListOf<RoomMessagePacket>()
    val publishedSnapshots = mutableListOf<Long>()
    var isExpired: Boolean = false
        private set
    var freshPeerResponse: Boolean = false
        private set

    var recoveryRemainingMillis: Long? = initialData.remainingTurnMillis
    var recoveryRemainingTurn: Int = initialData.turnNumber
    var recoveryRemainingPlayerId: String = initialData.currentTurnPlayerId
    var recoveryRemainingSeed: Long = initialData.matchSeed
    var recoveryRemainingSavedAt: Long = initialData.lastUpdatedAt

    fun bufferCommand(packet: RoomMessagePacket) {
        val existingIndex = bufferedCommands.indexOfFirst {
            if (packet.type == "SURRENDER") {
                it.type == packet.type && it.playerId == packet.playerId
            } else {
                it.type == packet.type && it.closedTurnNumber == packet.closedTurnNumber &&
                (if (it.turnNumber > 0) it.turnNumber else 0) == (if (packet.turnNumber > 0) packet.turnNumber else 0) &&
                it.playerId == packet.playerId && it.number == packet.number &&
                it.sequence == packet.sequence && it.timestamp == packet.timestamp
            }
        }
        if (existingIndex < 0) {
            bufferedCommands.add(packet)
        } else if ((packet.recoveryReplay && !bufferedCommands[existingIndex].recoveryReplay) ||
            (!bufferedCommands[existingIndex].recoveryReplay && packet.closedTurnNumber > bufferedCommands[existingIndex].closedTurnNumber) ||
            (packet.recoveryReplay && bufferedCommands[existingIndex].recoveryReplay && packet.closedTurnNumber > bufferedCommands[existingIndex].closedTurnNumber)) {
            bufferedCommands[existingIndex] = packet
        }
    }

    fun drainBufferedCommands(now: Long) {
        if (!recoveryFenceCompleted) return
        val sorted = PeerMatchRecovery.prioritizeAndSortCommands(bufferedCommands)
        bufferedCommands.clear()
        bufferedCommands.addAll(sorted)

        var advanced = true
        while (advanced && bufferedCommands.isNotEmpty() && !data.isMatchFinished) {
            advanced = false
            val iterator = bufferedCommands.iterator()
            while (iterator.hasNext()) {
                val packet = iterator.next()
                val effectiveTurn = if (packet.closedTurnNumber > 0) packet.closedTurnNumber else packet.turnNumber
                if (packet.type == "SURRENDER" && effectiveTurn > data.turnNumber) continue

                val player = data.playerBoard ?: break
                val opponent = data.opponentBoard ?: break
                val ids = data.participants.map { it.id }
                val ctx = MultiplayerGameplayContext(data.matchSeed, localUid, "", data.participants,
                    data.randomizedTurnOrder, data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
                    allowPeerTimeouts = true, strictIdentity = false)
                val currentClock = data.turnClock
                val closedClock = packet.closedTurnClock
                val subsequentTurnExists = bufferedCommands.any { PeerMatchRecovery.cmdTurn(it) > PeerMatchRecovery.cmdTurn(packet) }
                val confirmedPicks = bufferedCommands.flatMap { it.pickedHistory }.toSet()
                val turnHasConfirmedPick = bufferedCommands.any {
                    it.type == "PICK_NUMBER" && PeerMatchRecovery.cmdTurn(it) == PeerMatchRecovery.cmdTurn(packet) &&
                    (it.recoveryReplay || confirmedPicks.contains(it.number))
                }
                val isAcceptedTimeout = packet.recoveryReplay || (subsequentTurnExists && !turnHasConfirmedPick)
                val matchesWelcomeBack = currentClock == null ||
                    (currentClock.welcomeBackGranted == closedClock?.welcomeBackGranted) ||
                    (closedClock?.welcomeBackGranted == true)
                val isResumedClock = currentClock != null && closedClock != null &&
                    currentClock.frozenRemainingMillis != null &&
                    closedClock.frozenRemainingMillis == null &&
                    closedClock.changedAt >= currentClock.changedAt &&
                    closedClock.initialBudgetMillis == currentClock.initialBudgetMillis

                val reconstructedClock = if (isGamePaused) currentClock else {
                    if (packet.type == "TURN_TIMEOUT" && closedClock != null && currentClock != null &&
                        currentClock.turnNumber == closedClock.turnNumber &&
                        currentClock.playerId == closedClock.playerId &&
                        closedClock.valid() &&
                        isAcceptedTimeout &&
                        matchesWelcomeBack &&
                        (isResumedClock || currentClock.frozenRemainingMillis == null)) {
                        closedClock
                    } else {
                        currentClock?.copy(frozenRemainingMillis = null)
                    }
                }
                val before = MultiplayerGameplayState(
                    data.pickedNumbers.mapIndexed { i, n -> MatchPick(n, data.pickedByPlayers.getOrNull(i).orEmpty(), data.turnIds.getOrNull(i) ?: i + 1) },
                    player, opponent, data.allPlayerBoards, data.turnNumber, data.currentTurnPlayerId,
                    recoveryRemainingMillis ?: 30_000L, isPaused = isGamePaused,
                    surrenderedPlayerIds = data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
                    turnClock = reconstructedClock
                )
                val decision = when (packet.type) {
                    "PICK_NUMBER" -> MultiplayerGameplayReducer.pick(packet, ctx, before)
                    "TURN_TIMEOUT" -> MultiplayerGameplayReducer.timeout(packet, ctx, before)
                    else -> MultiplayerGameplayReducer.surrender(packet, ctx, before)
                }
                if (decision.accepted) {
                    iterator.remove()
                    advanced = true
                    val next = decision.state
                    val changedTurn = next.turnNumber != data.turnNumber || next.currentPlayerId != data.currentTurnPlayerId
                    var resolvedPresence = data.presenceState
                    packet.playerPresences.forEach { (id, record) ->
                        resolvedPresence = resolvedPresence.update(id, record)
                    }
                    val isAbsent = resolvedPresence.isAbsent(next.currentPlayerId)
                    val nextClock = if (changedTurn) {
                        PeerMatchRecovery.resolveTransitionClock(packet, next.turnNumber, next.currentPlayerId, now, isAbsent, data.turnClock)
                    } else {
                        data.turnClock ?: MultiplayerTurnClock.start(next.turnNumber, next.currentPlayerId, now, isAbsent)
                    }
                    if (isReconciling && (changedTurn || packet.timestamp >= recoveryRemainingSavedAt)) {
                        val budget = nextClock.initialBudgetMillis
                        recoveryRemainingMillis = if (changedTurn) {
                            packet.turnClock?.frozenRemainingMillis ?: nextClock.frozenRemainingMillis ?: budget
                        } else {
                            nextClock.frozenRemainingMillis ?: recoveryRemainingMillis ?: nextClock.remainingMillis(now)
                        }
                        recoveryRemainingSavedAt = packet.timestamp
                        recoveryRemainingTurn = next.turnNumber
                        recoveryRemainingPlayerId = next.currentPlayerId
                        recoveryRemainingSeed = data.matchSeed
                    }
                    val updatedActivity = if (packet.type == "PICK_NUMBER" || packet.type == "SURRENDER") {
                        maxOf(data.lastPlayerActivityAt, packet.timestamp.coerceAtMost(now))
                    } else data.lastPlayerActivityAt
                    val result = MultiplayerResultRules.evaluate(next.allPlayerBoards, ids, next.surrenderedPlayerIds,
                        next.picks.lastOrNull()?.playerId.orEmpty(), data.randomizedTurnOrder.map { it.id })
                    data = data.copy(
                        playerBoard = next.playerBoard, opponentBoard = next.opponentBoard, allPlayerBoards = next.allPlayerBoards,
                        pickedNumbers = next.picks.map { it.number }, pickedByPlayers = next.picks.map { it.playerId }, turnIds = next.picks.map { it.turn },
                        turnNumber = next.turnNumber, currentTurnPlayerId = next.currentPlayerId, turnClock = nextClock,
                        remainingTurnMillis = recoveryRemainingMillis,
                        multiplayerSnapshotCheckpoint = data.multiplayerSnapshotCheckpoint.copy(
                            turnNumber = next.turnNumber, picks = next.picks, surrenderedPlayerIds = next.surrenderedPlayerIds
                        ),
                        lastPlayerActivityAt = updatedActivity,
                        isMatchFinished = result != null, winnerPlayerId = result?.winnerId.orEmpty(),
                        runnerPlayerIds = result?.runnerIds ?: emptyList(), winReason = result?.reason.orEmpty()
                    )
                    break
                } else {
                    val closedTurn = if (packet.closedTurnNumber > 0) packet.closedTurnNumber else (packet.turnNumber - 1)
                    val isAlreadySurrendered = packet.type == "SURRENDER" && packet.playerId in data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds
                    val isAlreadyPicked = packet.type == "PICK_NUMBER" && data.pickedNumbers.contains(packet.number)
                    val isObsoleteTurn = (closedTurn in 1 until data.turnNumber) || (packet.turnNumber in 1 until data.turnNumber)
                    val isStaleTimeoutProposal = packet.type == "TURN_TIMEOUT" && !packet.recoveryReplay &&
                        currentClock != null &&
                        currentClock.turnNumber == (closedClock?.turnNumber ?: packet.turnNumber) &&
                        currentClock.playerId == (closedClock?.playerId ?: packet.playerId) &&
                        (!matchesWelcomeBack || (closedClock != null && currentClock.changedAt > closedClock.changedAt) || (currentClock.frozenRemainingMillis != null && !isResumedClock))
                    if (isAlreadySurrendered || isAlreadyPicked || isObsoleteTurn || isStaleTimeoutProposal) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    fun completeRecoveryFence(now: Long) {
        recoveryFenceCompleted = true
        drainBufferedCommands(now)
        val effectiveActivityCheckTime = if (pendingHumanReturn && pendingHumanReturnAt > 0L) pendingHumanReturnAt else now
        val isMatchAlive = (!data.isMatchFinished && effectiveActivityCheckTime - data.lastPlayerActivityAt < MultiplayerTurnClock.INACTIVITY_MILLIS)
        if (!data.isMatchFinished && !isMatchAlive) {
            isExpired = true
            return
        }
        if (pendingHumanReturn && isMatchAlive) {
            data = data.copy(lastPlayerActivityAt = effectiveActivityCheckTime)
        }
        if (isColdRejoin && !data.isMatchFinished && !freshPeerResponse) {
            var peerClock = data.turnClock
            if (isGamePaused) {
                peerClock = peerClock?.freeze(now)
            } else {
                val validRemaining = recoveryRemainingMillis?.takeIf {
                    recoveryRemainingSeed == data.matchSeed &&
                    recoveryRemainingTurn == data.turnNumber &&
                    recoveryRemainingPlayerId == data.currentTurnPlayerId
                }
                if (validRemaining != null) {
                    peerClock = peerClock?.copy(frozenRemainingMillis = validRemaining)
                } else {
                    val isAbsent = data.presenceState.isAbsent(data.currentTurnPlayerId)
                    val budget = peerClock?.initialBudgetMillis?.takeIf { it in setOf(5_000L, 30_000L) }
                        ?: (if (isAbsent) MultiplayerTurnClock.ABSENCE_MILLIS else MultiplayerTurnClock.ONLINE_MILLIS)
                    val fallback = peerClock?.frozenRemainingMillis ?: peerClock?.remainingMillis(now) ?: budget
                    peerClock = peerClock?.copy(frozenRemainingMillis = fallback)
                }
            }
            data = data.copy(turnClock = peerClock)
        }
        if (!data.isMatchFinished) {
            var peerClock = data.turnClock
            if (isGamePaused) {
                peerClock = peerClock?.freeze(now)
            } else {
                peerClock = peerClock?.resume(now)
            }
            data = data.copy(turnClock = peerClock)
        }
        if (bufferedCommands.isEmpty()) {
            isReconciling = false
            awaitingHostStateSync = false
        }
        val rem = if (isGamePaused) data.turnClock?.frozenRemainingMillis ?: 0L else (data.turnClock?.remainingMillis(now) ?: data.remainingTurnMillis ?: 0L)
        publishedSnapshots.add(rem)
    }

    fun processPacket(packet: RoomMessagePacket, now: Long) {
        when (packet.type) {
            "GAME_SYNC" -> {
                if (data.isMatchFinished) return
                val ids = data.participants.map { it.id }
                var candidateBoards = data.allPlayerBoards
                for ((id, numbers) in packet.boardNumbers) {
                    if (id !in ids || !ManualBoardEngine.isBoardComplete(numbers, data.boardSize)) return
                    val existing = candidateBoards[id]
                    if (existing != null && existing.cells.map { it.number } != numbers) return
                    if (existing == null) candidateBoards = candidateBoards + (id to ManualBoardEngine.buildBoard(numbers, data.boardSize))
                }
                val application = MultiplayerSnapshotReconciler.reconcile(
                    packet, data.matchSeed, "", localUid,
                    data.multiplayerSnapshotCheckpoint,
                    data.pickedNumbers.mapIndexed { i, n -> MatchPick(n, data.pickedByPlayers.getOrNull(i).orEmpty(), data.turnIds.getOrNull(i) ?: i + 1) },
                    data.playerBoard ?: return, data.opponentBoard ?: return,
                    candidateBoards, true, ids, data.randomizedTurnOrder.map { it.id }
                ) ?: return
                val changedTurn = data.turnNumber != packet.turnNumber || data.currentTurnPlayerId != packet.currentTurnPlayerId
                if (isColdRejoin && !packet.isRetainedDelivery && !packet.recoveryReplay && packet.playerId != localUid) {
                    freshPeerResponse = true
                }
                var presence = data.presenceState
                packet.playerPresences.filterKeys { it in ids }.forEach { (id, record) -> presence = presence.update(id, record) }
                val updatedActivity = maxOf(data.lastPlayerActivityAt, packet.lastPlayerActivityAt.coerceAtMost(now))
                val result = MultiplayerResultRules.evaluate(application.allPlayerBoards, ids,
                    data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds + application.checkpoint.surrenderedPlayerIds,
                    application.picks.lastOrNull()?.playerId.orEmpty(), data.randomizedTurnOrder.map { it.id })
                data = data.copy(
                    playerBoard = application.playerBoard, opponentBoard = application.opponentBoard, allPlayerBoards = application.allPlayerBoards,
                    pickedNumbers = application.picks.map { it.number }, pickedByPlayers = application.picks.map { it.playerId }, turnIds = application.picks.map { it.turn },
                    turnNumber = if (application.hasPendingPicks) data.turnNumber else packet.turnNumber,
                    currentTurnPlayerId = if (application.hasPendingPicks) data.currentTurnPlayerId else packet.currentTurnPlayerId,
                    multiplayerSnapshotCheckpoint = application.checkpoint,
                    presenceState = presence,
                    lastPlayerActivityAt = updatedActivity,
                    isMatchFinished = result != null, winnerPlayerId = result?.winnerId.orEmpty(),
                    runnerPlayerIds = result?.runnerIds ?: emptyList(), winReason = result?.reason.orEmpty()
                )
                if (isReconciling && (changedTurn || packet.timestamp >= recoveryRemainingSavedAt)) {
                    recoveryRemainingMillis = packet.turnClock?.frozenRemainingMillis ?: packet.remainingTurnMillis
                    recoveryRemainingSavedAt = packet.timestamp
                    recoveryRemainingTurn = packet.turnNumber
                    recoveryRemainingPlayerId = packet.currentTurnPlayerId
                    recoveryRemainingSeed = data.matchSeed
                }
                drainBufferedCommands(now)
                if (recoveryFenceCompleted && bufferedCommands.isEmpty()) {
                    isReconciling = false
                    awaitingHostStateSync = false
                }
            }
            "PEER_RECOVERY_READY" -> {
                completeRecoveryFence(now)
            }
            "PICK_NUMBER", "TURN_TIMEOUT", "SURRENDER" -> {
                if (data.isMatchFinished) return
                bufferCommand(packet)
                if (recoveryFenceCompleted) {
                    drainBufferedCommands(now)
                    if (bufferedCommands.isEmpty()) {
                        isReconciling = false
                        awaitingHostStateSync = false
                    }
                }
            }
        }
    }
}
