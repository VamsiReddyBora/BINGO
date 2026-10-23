package com.bingo.multiplayer.domain.network

import android.content.Context
import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player

/** A durable bootstrap is saved before announcing/acknowledging a scheduled automatic start.
 * It is not a running game until exact EMQX metadata confirms that epoch. */
object ScheduledStartCheckpoint {
    sealed interface Resolution {
        data class Ready(val match: OngoingMatchData) : Resolution
        object Pending : Resolution
        object Cancelled : Resolution
        object Unavailable : Resolution
    }

    fun automaticBoards(size: Int, seed: Long, players: List<Player>, engine: BingoEngine = BingoEngine()): Map<String, Board> =
        players.mapIndexed { index, player ->
            player.id to engine.generateBoard(size, LobbyLifecycleEngine.resolvePlayerBoardSeed(seed, player, index))
        }.toMap()

    fun build(packet: RoomMessagePacket, uid: String, sessionId: String,
        now: Long = System.currentTimeMillis()): OngoingMatchData? {
        val ids = packet.players.map { it.id }
        val clock = packet.turnClock ?: return null
        if (packet.type != "START_GAME" || packet.isManualBoard || packet.seed == 0L || packet.roomCode.isBlank() ||
            sessionId.isBlank() || packet.requestId.isBlank() || packet.connectionGeneration.isBlank() ||
            ids.size !in 2..8 || ids.distinct().size != ids.size || uid !in ids ||
            packet.startAcknowledgedIds.toSet() != ids.toSet() || packet.startClockOffsets.keys != ids.toSet() ||
            packet.playerId != MultiplayerGameplayReducer.sender(packet) || packet.players.count { it.isHost } != 1 ||
            packet.players.single { it.isHost }.id != packet.playerId || packet.currentTurnPlayerId !in ids ||
            !clock.valid() || clock.turnNumber != 1 || clock.playerId != packet.currentTurnPlayerId ||
            clock.startedAt != packet.startCommitDeadline + packet.clockOffsetMillis) return null
        val boards = automaticBoards(packet.boardSize, packet.seed, packet.players)
        return OngoingMatchData(packet.roomCode, packet.seed, packet.boardSize,
            isDynamicBoard = packet.isDynamicBoard, isHost = uid == packet.playerId,
            participants = packet.players, playerBoard = boards[uid],
            opponentBoard = boards[ids.first { it != uid }], allPlayerBoards = boards,
            currentTurnPlayerId = packet.currentTurnPlayerId,
            randomizedTurnOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(packet.players, packet.seed),
            startedAt = clock.startedAt, lastUpdatedAt = now, lastPlayerActivityAt = clock.startedAt,
            remainingTurnMillis = clock.initialBudgetMillis, turnClock = clock,
            is1v1Match = ids.size == 2, accountUid = uid, simplifiedRulesVersion = 1,
            clockOffsetMillis = packet.clockOffsetMillis, clockOffsetsByPlayer = packet.startClockOffsets,
            pendingStartEpoch = packet.requestId, lobbySessionId = sessionId)
    }

    fun stage(context: Context?, packet: RoomMessagePacket, uid: String, sessionId: String): Boolean =
        build(packet, uid, sessionId)?.let { OngoingMatchStore.saveOngoingMatch(context, it) } == true

    suspend fun resolve(context: Context?, data: OngoingMatchData): Resolution {
        if (data.pendingStartEpoch.isBlank()) return Resolution.Ready(data)
        val roomResult = RoomMetadataApi.read(data.roomCode)
        if (roomResult.isFailure) return Resolution.Unavailable
        val room = roomResult.getOrNull()
        if (room == null || room.sessionId != data.lobbySessionId || room.currentSeed != data.matchSeed ||
            room.status == "CLOSED" || data.pendingStartEpoch in room.cancelledStartIds) {
            OngoingMatchStore.clearScheduledStart(context, data.accountUid, data.matchSeed, data.pendingStartEpoch)
            return Resolution.Cancelled
        }
        if (OnlineRoomRegistry.hasPendingStartCancellation(data.roomCode, data.matchSeed, data.pendingStartEpoch)) return Resolution.Pending
        if (room.status != "PLAYING") {
            val now = System.currentTimeMillis()
            val inactiveExpired = (now - maxOf(data.lastPlayerActivityAt, data.lastUpdatedAt, room.startCommitDeadline, room.lastHeartbeat)) > 300_000L
            if (inactiveExpired) {
                OngoingMatchStore.clearScheduledStart(context, data.accountUid, data.matchSeed, data.pendingStartEpoch)
                return Resolution.Cancelled
            }
            return Resolution.Pending
        }
        val start = room.canonicalStartPacketJson?.let { runCatching { FastPacketCodec.decode(it) }.getOrNull() }
            ?: return Resolution.Unavailable
        if (start.requestId != data.pendingStartEpoch || !start.startCommitted || !start.startReleased || start.seed != data.matchSeed ||
            start.playerId != data.participants.singleOrNull { it.isHost }?.id ||
            MultiplayerGameplayReducer.sender(start) != start.playerId ||
            start.startAcknowledgedIds.toSet() != data.participants.map { it.id }.toSet() ||
            start.players.map { it.id }.toSet() != data.participants.map { it.id }.toSet()) return Resolution.Unavailable
        val ready = data.copy(pendingStartEpoch = "")
        return if (OngoingMatchStore.saveRecoveredMatch(context, ready)) Resolution.Ready(ready) else Resolution.Unavailable
    }
}
