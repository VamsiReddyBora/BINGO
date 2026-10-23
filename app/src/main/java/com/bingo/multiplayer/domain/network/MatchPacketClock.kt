package com.bingo.multiplayer.domain.network

/** Packet times are expressed in the sender's calibrated clock domain. Budgets and identities
 * never change. Both the running handler and cold recovery use this idempotent conversion. */
object MatchPacketClock {
    fun localize(packet: RoomMessagePacket, localOffset: Long): RoomMessagePacket {
        val delta = localOffset - packet.clockOffsetMillis
        if (delta == 0L) return packet
        fun at(value: Long) = if (value > 0L) value + delta else value
        return packet.copy(clockOffsetMillis = localOffset, timestamp = at(packet.timestamp),
            turnClock = clock(packet.turnClock, delta), closedTurnClock = clock(packet.closedTurnClock, delta),
            lastPlayerActivityAt = at(packet.lastPlayerActivityAt),
            playerPresences = packet.playerPresences.mapValues { (_, p) -> p.copy(observedAt = at(p.observedAt)) },
            chatMessages = packet.chatMessages.map { it.copy(timestamp = at(it.timestamp)) })
    }

    private fun clock(value: MultiplayerTurnClock?, delta: Long): MultiplayerTurnClock? = value?.copy(
        startedAt = value.startedAt + delta, deadlineAt = value.deadlineAt + delta, changedAt = value.changedAt + delta)

    fun ingress(packet: RoomMessagePacket, localOffset: Long, localId: String, currentSeed: Long): RoomMessagePacket {
        // Preparation timestamps calibrate clocks, and must reach the midpoint calculation raw.
        if (packet.type in setOf("PREPARE_START", "START_GAME_ACK", "CANCEL_START")) return packet
        if (packet.type in setOf("START_GAME", "PLAY_AGAIN")) {
            val offset = packet.startClockOffsets[localId] ?: if (packet.seed == currentSeed) localOffset else 0L
            val delta = offset - packet.clockOffsetMillis
            // Countdown deadlines remain in the initial host domain: LobbyStartSchedule adds
            // the offset exactly once. Only the initial gameplay clocks enter the local domain.
            return packet.copy(clockOffsetMillis = offset, turnClock = clock(packet.turnClock, delta),
                closedTurnClock = clock(packet.closedTurnClock, delta))
        }
        return localize(packet, localOffset)
    }
}

/** Clock state used by the running screen as well as its durable checkpoint. */
class MatchClockSession {
    var seed: Long = 0L
        private set
    var offset: Long = 0L
        private set
    var offsets: Map<String, Long> = emptyMap()
        private set

    fun begin(matchSeed: Long, localId: String, calibrated: Map<String, Long> = emptyMap()) {
        seed = matchSeed
        offsets = calibrated.toMap()
        offset = offsets[localId] ?: 0L
    }

    fun ingress(packet: RoomMessagePacket, localId: String, currentSeed: Long): RoomMessagePacket {
        val start = packet.type in setOf("START_GAME", "PLAY_AGAIN")
        val calibrated = packet.startClockOffsets.ifEmpty { if (packet.seed == seed) offsets else emptyMap() }
        return MatchPacketClock.ingress(
            if (start) packet.copy(startClockOffsets = calibrated) else packet, offset, localId, currentSeed)
    }

    /** Called only after the screen has accepted the new start/rematch. Ignored starts
     * can be converted for validation without replacing the active match's clock. */
    fun acceptStart(packet: RoomMessagePacket) {
        seed = packet.seed
        offset = packet.clockOffsetMillis
        offsets = packet.startClockOffsets
    }

    fun restore(data: OngoingMatchData) {
        seed = data.matchSeed
        offset = data.clockOffsetMillis
        offsets = data.clockOffsetsByPlayer
    }

    fun checkpoint(data: OngoingMatchData): OngoingMatchData =
        data.copy(clockOffsetMillis = offset, clockOffsetsByPlayer = offsets)

    /** A rematch uses its starter's clock as the new origin, including a former guest. */
    fun rematchOffsets(localId: String, ids: List<String>): Map<String, Long> {
        val origin = offsets[localId] ?: offset
        return ids.associateWith { if (it == localId) 0L else (offsets[it] ?: origin) - origin }
    }
}
