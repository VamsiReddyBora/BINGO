package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.Clock
import com.bingo.multiplayer.domain.engine.SystemMonotonicClock
import java.util.UUID

/** Match controls and fresh presence are deliberately separate from broker/room presence hints. */
class MultiplayerLifecyclePacketGate(private val clock: Clock = SystemMonotonicClock) {
    companion object {
        const val PROBE_INTERVAL_MS = 2_500L
        const val PROBE_EXPIRY_MS = 5_000L
        val scopedTypes = MultiplayerGameplayReducer.gameplayTypes + setOf(
            "GAME_SYNC", "GAME_SYNC_ACK", "GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND",
            "HOST_HOLD", "HOST_RESUMED", "PLAYER_ELIMINATED", "PLAYER_DISCONNECTED",
            "HOST_LEFT", "REJOIN_GAME", "SYNC_REQUEST", "BOARD_READY", "GO_TO_LOBBY",
            "MULTIPLAYER_PRESENCE_REQUEST", "MULTIPLAYER_PRESENCE_RESPONSE",
            "KICK_PLAYER", "SETTINGS_UPDATE", "CHAT_MESSAGE"
        )
        private val departureTypes = setOf("HOST_LEFT", "PLAYER_DISCONNECTED", "REJOIN_GAME")
    }
    private data class Probe(val createdAt: Long, val responders: MutableSet<String> = mutableSetOf())
    private var scope = ""
    private val probes = mutableMapOf<String, Probe>()
    private val peerInstances = mutableMapOf<String, String>()
    private val retiredInstances = mutableSetOf<String>()
    private val controlSequences = mutableMapOf<String, Long>()

    private fun configure(context: MultiplayerGameplayContext, room: String, generation: String) {
        val next = "${context.seed}/${context.localId}/${context.isActive}/$room/$generation"
        if (scope != next) {
            scope = next; probes.clear(); peerInstances.clear(); retiredInstances.clear(); controlSequences.clear()
        }
    }

    fun accepts(packet: RoomMessagePacket, context: MultiplayerGameplayContext, room: String, generation: String): Boolean {
        configure(context, room, generation)
        if (packet.roomCode.isNotBlank() && packet.roomCode != room) return false
        // Old game controls must not close a later lobby. Unscoped room controls keep their lobby behavior.
        if (!context.isActive) return packet.type !in scopedTypes || packet.seed == 0L
        // Nearby socket closure events are local transport evidence, with the
        // existing LAN departure policy. MQTT Last Will is never this exception.
        if (context.allowPeerTimeouts && !packet.transportOnly && packet.seed == 0L && packet.matchId.isBlank()) {
            if (packet.type == "LEAVE") return context.participantId(packet.playerId) != null &&
                MultiplayerGameplayReducer.sender(packet) == packet.playerId
            if (packet.type == "HOST_LEFT") return packet.playerId.isBlank() ||
                (packet.playerId == context.hostId && MultiplayerGameplayReducer.sender(packet) == context.hostId)
        }
        if (packet.transportOnly || packet.type in setOf("LEAVE", "LOBBY_EXPIRED", "LOBBY_EXTEND")) return false
        if (packet.type !in scopedTypes) return true
        if (!MultiplayerGameplayReducer.belongsToMatch(packet, context)) return false
        if (packet.senderInstanceId in retiredInstances) return false
        if (packet.type in setOf("HOST_LEFT", "GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND", "PLAYER_ELIMINATED", "HOST_HOLD", "HOST_RESUMED") && (packet.playerId != context.hostId ||
                MultiplayerGameplayReducer.sender(packet) != context.hostId)) return false
        if (packet.type in departureTypes) {
            if (MultiplayerGameplayReducer.sender(packet) != packet.playerId) return false
            if (packet.lifecycleSequence > 0L) {
                val key = "${packet.playerId}/${packet.senderInstanceId}"
                if (packet.lifecycleSequence <= (controlSequences[key] ?: 0L)) return false
                controlSequences[key] = packet.lifecycleSequence
            }
        }
        return true
    }

    fun beginProbe(context: MultiplayerGameplayContext, room: String, generation: String): RoomMessagePacket {
        configure(context, room, generation)
        val now = clock.elapsedRealtime()
        probes.entries.removeAll { now - it.value.createdAt >= PROBE_EXPIRY_MS }
        val id = UUID.randomUUID().toString()
        probes[id] = Probe(now)
        return RoomMessagePacket(type = "MULTIPLAYER_PRESENCE_REQUEST", playerId = context.localId,
            seed = context.seed, matchId = context.seed.toString(), roomCode = room,
            connectionGeneration = generation, requestId = id)
    }

    fun respond(packet: RoomMessagePacket, context: MultiplayerGameplayContext, room: String): RoomMessagePacket? {
        if (packet.type != "MULTIPLAYER_PRESENCE_REQUEST" || !MultiplayerGameplayReducer.belongsToMatch(packet, context) ||
            packet.roomCode != room || packet.requestId.isBlank() || packet.connectionGeneration.isBlank() ||
            (packet.targetPlayerId.isNotBlank() && packet.targetPlayerId != context.localId) ||
            packet.playerId == context.localId) return null
        return RoomMessagePacket(type = "MULTIPLAYER_PRESENCE_RESPONSE", playerId = context.localId,
            targetPlayerId = packet.playerId, seed = context.seed, matchId = context.seed.toString(),
            roomCode = room, connectionGeneration = packet.connectionGeneration, requestId = packet.requestId)
    }

    /** Receiver nonce + local connection generation + monotonic age, independent of phone clock differences. */
    fun acceptResponse(packet: RoomMessagePacket, context: MultiplayerGameplayContext, room: String, generation: String): Boolean {
        configure(context, room, generation)
        if (packet.type != "MULTIPLAYER_PRESENCE_RESPONSE" || !MultiplayerGameplayReducer.belongsToMatch(packet, context) ||
            packet.roomCode != room || packet.connectionGeneration != generation || packet.targetPlayerId != context.localId ||
            packet.playerId == context.localId || packet.senderInstanceId.isBlank() ||
            MultiplayerGameplayReducer.sender(packet) != packet.playerId) return false
        val probe = probes[packet.requestId] ?: return false
        if (clock.elapsedRealtime() - probe.createdAt >= PROBE_EXPIRY_MS || packet.playerId in probe.responders ||
            packet.senderInstanceId in retiredInstances) return false
        probe.responders.add(packet.playerId)
        peerInstances.put(packet.playerId, packet.senderInstanceId)?.takeIf { it != packet.senderInstanceId }?.let(retiredInstances::add)
        val key = "${packet.playerId}/${packet.senderInstanceId}"
        controlSequences[key] = maxOf(controlSequences[key] ?: 0L, packet.lifecycleSequence)
        return true
    }
}
