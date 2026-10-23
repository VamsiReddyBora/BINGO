package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player

/** The production lobby handshake. Exact epoch/roster matching applies on both peers.
 * Clock offsets come from the PREPARE/ACK round trip, not assumed phone clock equality. */
class LobbyStartBarrier {
    private var prepared: RoomMessagePacket? = null
    private val sent = linkedSetOf<Long>()
    private val offsets = linkedMapOf<String, Long>()
    private val acknowledged = linkedSetOf<String>()
    private val scheduledAcks = linkedSetOf<String>()
    private val scheduledAt = mutableMapOf<String, Long>()
    private val cancelled = linkedSetOf<String>()
    private var latestPreparationAt = 0L
    private var committed = false
    private var released = false
    private var lastCancelled: String? = null
    private fun key(p: RoomMessagePacket) = "${p.roomCode}/${p.seed}/${p.connectionGeneration}/${p.requestId}"
    private fun roster(players: List<Player>) = players.associate { it.id to it.readyVersion }

    @Synchronized fun begin(packet: RoomMessagePacket) {
        prepared = packet
        committed = false
        released = false
        lastCancelled = null
        sent.clear(); offsets.clear(); acknowledged.clear(); scheduledAcks.clear(); scheduledAt.clear()
        acknowledged.add(packet.playerId); offsets[packet.playerId] = 0L
    }
    @Synchronized fun preparation(now: Long): RoomMessagePacket? = prepared?.copy(timestamp = now)?.also {
        sent.add(now)
        while (sent.size > 20) sent.remove(sent.first())
    }
    @Synchronized fun acceptPreparation(packet: RoomMessagePacket, localId: String, hostId: String,
        live: List<Player>, healthy: Boolean, now: Long): RoomMessagePacket? {
        if (!healthy || packet.type != "PREPARE_START" || packet.playerId != hostId ||
            MultiplayerGameplayReducer.sender(packet) != hostId || packet.seed == 0L ||
            packet.connectionGeneration.isBlank() || packet.requestId.isBlank() || key(packet) in cancelled ||
            packet.players.size < 2 || packet.players.map { it.id }.toSet() != live.map { it.id }.toSet() ||
            packet.players.none { it.id == localId } || live.any { it.id != hostId && it.lobbyReadyStatus != "READY" } ||
            packet.players.any { p -> live.none { it.id == p.id && it.readyVersion == p.readyVersion } }) return null
        if (prepared != null && key(prepared!!) != key(packet) && packet.timestamp <= latestPreparationAt) return null
        prepared = packet
        latestPreparationAt = maxOf(latestPreparationAt, packet.timestamp)
        return RoomMessagePacket("START_GAME_ACK", playerId = localId, senderPlayerId = localId,
            roomCode = packet.roomCode, seed = packet.seed, targetPlayerId = hostId,
            connectionGeneration = packet.connectionGeneration, requestId = packet.requestId,
            pingTimestamp = packet.timestamp, timestamp = now)
    }
    @Synchronized fun acknowledge(packet: RoomMessagePacket, now: Long): Boolean {
        val p = prepared ?: return false
        if (packet.type != "START_GAME_ACK" || packet.roomCode != p.roomCode || packet.seed != p.seed ||
            packet.targetPlayerId != p.playerId || packet.connectionGeneration != p.connectionGeneration ||
            packet.requestId != p.requestId || MultiplayerGameplayReducer.sender(packet) != packet.playerId ||
            packet.playerId == p.playerId || p.players.none { it.id == packet.playerId } ||
            packet.pingTimestamp !in sent || now - packet.pingTimestamp !in 0L..1500L) return false
        if (acknowledged.add(packet.playerId)) offsets[packet.playerId] = packet.timestamp - (packet.pingTimestamp + now) / 2L
        return true
    }
    @get:Synchronized val complete: Boolean get() = prepared?.players?.map { it.id }?.toSet() == acknowledged.toSet()
    @Synchronized fun schedule(packet: RoomMessagePacket): RoomMessagePacket? {
        val p = prepared ?: return null
        if (!complete || packet.seed != p.seed || roster(packet.players) != roster(p.players)) return null
        scheduledAcks.add(p.playerId)
        return packet.copy(connectionGeneration = p.connectionGeneration, requestId = p.requestId,
            startAcknowledgedIds = acknowledged.toList(), startClockOffsets = offsets.toMap())
    }
    @Synchronized fun acknowledgeScheduled(packet: RoomMessagePacket, now: Long = System.nanoTime()): Boolean {
        val p = prepared ?: return false
        if (packet.type != "START_GAME_ACK" || packet.heartbeatAction != (if (released) "RELEASED" else if (committed) "COMMITTED" else "SCHEDULED") || key(packet) != key(p) ||
            packet.targetPlayerId != p.playerId || packet.playerId == p.playerId ||
            MultiplayerGameplayReducer.sender(packet) != packet.playerId || p.players.none { it.id == packet.playerId }) return false
        scheduledAcks.add(packet.playerId)
        scheduledAt[packet.playerId] = now
        return true
    }
    @get:Synchronized val scheduledComplete: Boolean get() = prepared?.players?.map { it.id }?.toSet() == scheduledAcks.toSet()
    @Synchronized fun scheduledFresh(now: Long = System.nanoTime()): Boolean {
        val p = prepared ?: return false
        return scheduledComplete && p.players.filter { it.id != p.playerId }.all {
            now - (scheduledAt[it.id] ?: Long.MIN_VALUE / 2) in 0L..500_000_000L
        }
    }
    @Synchronized fun commitSchedule(): Boolean {
        val p = prepared ?: return false
        if (!scheduledComplete) return false
        committed = true
        scheduledAcks.clear(); scheduledAcks.add(p.playerId); scheduledAt.clear()
        return true
    }
    @Synchronized fun releaseSchedule(): Boolean {
        val p = prepared ?: return false
        if (!committed || !scheduledComplete) return false
        released = true
        scheduledAcks.clear(); scheduledAcks.add(p.playerId); scheduledAt.clear()
        return true
    }
    @Synchronized fun observeCommit(packet: RoomMessagePacket): Boolean {
        val p = prepared ?: return false
        if (!packet.startCommitted || key(packet) != key(p) || key(packet) in cancelled ||
            packet.playerId != p.playerId || MultiplayerGameplayReducer.sender(packet) != p.playerId ||
            packet.startAcknowledgedIds.toSet() != p.players.map { it.id }.toSet()) return false
        committed = true
        if (packet.startReleased) released = true
        return true
    }
    @get:Synchronized val canEnter: Boolean get() = committed && released && prepared?.let { key(it) !in cancelled } == true
    @Synchronized fun acceptsSchedule(packet: RoomMessagePacket, localId: String, hostId: String, live: List<Player>): Boolean {
        val p = prepared ?: return false
        return packet.type == "START_GAME" && key(packet) == key(p) && key(packet) !in cancelled &&
            packet.playerId == hostId && MultiplayerGameplayReducer.sender(packet) == hostId &&
            packet.startAcknowledgedIds.toSet() == p.players.map { it.id }.toSet() &&
            packet.startClockOffsets.keys == p.players.map { it.id }.toSet() &&
            localId in packet.startAcknowledgedIds && roster(packet.players) == roster(p.players) &&
            rosterStillPrepared(live)
    }
    @Synchronized fun rosterStillPrepared(live: List<Player>): Boolean {
        val p = prepared ?: return false
        return live.map { it.id }.toSet() == p.players.map { it.id }.toSet() && live.all { current ->
            val initial = p.players.single { it.id == current.id }
            (current.readyVersion == initial.readyVersion ||
                (committed && current.lobbyReadyStatus == "IN_GAME" && current.readyVersion >= initial.readyVersion)) &&
                (current.id == p.playerId || current.lobbyReadyStatus in setOf("READY", "IN_GAME"))
        }
    }
    @Synchronized fun cancel(packet: RoomMessagePacket): Boolean {
        cancelled.add(key(packet))
        while (cancelled.size > 64) cancelled.remove(cancelled.first())
        val matches = prepared?.let { key(it) } == key(packet)
        if (matches) { prepared = null; lastCancelled = key(packet) }
        return matches
    }
    @Synchronized fun isCurrentEpoch(packet: RoomMessagePacket): Boolean =
        prepared?.let { key(it) == key(packet) } ?: (lastCancelled == key(packet))
    @Synchronized fun cancelled(packet: RoomMessagePacket): Boolean = key(packet) in cancelled
}
