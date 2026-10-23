package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope

/** Both production lobby callbacks run this one scheduled countdown. Phone wall-clock offsets
 * are estimated by the acknowledged preparation exchange; elapsed time after ingress is monotonic. */
object LobbyStartSchedule {
    fun deadlineFor(packet: RoomMessagePacket, localId: String): Long =
        packet.startCommitDeadline + (packet.startClockOffsets[localId] ?: 0L)

    suspend fun awaitHostCountdown(packet: RoomMessagePacket, localId: String, valid: () -> Boolean,
        display: (Int) -> Unit, wallNow: () -> Long = System::currentTimeMillis,
        monotonicNow: () -> Long = System::nanoTime): Boolean =
        awaitCountdown(packet, localId, valid, display, wallNow, monotonicNow)

    suspend fun awaitGuestCountdown(packet: RoomMessagePacket, localId: String, valid: () -> Boolean,
        display: (Int) -> Unit, wallNow: () -> Long = System::currentTimeMillis,
        monotonicNow: () -> Long = System::nanoTime): Boolean =
        awaitCountdown(packet, localId, valid, display, wallNow, monotonicNow)

    private suspend fun awaitCountdown(packet: RoomMessagePacket, localId: String, valid: () -> Boolean,
        display: (Int) -> Unit, wallNow: () -> Long, monotonicNow: () -> Long): Boolean {
        val timed = if (packet.startCommitDeadline > 0L) packet else packet.copy(
            startCommitDeadline = wallNow() + 3300L - (packet.startClockOffsets[localId] ?: 0L))
        if (!valid()) return false
        // A slow metadata commit must not start a second, independent host countdown.
        if (deadlineFor(timed, localId) <= wallNow()) { display(0); return valid() }
        return awaitStart(timed, localId, valid, display, wallNow, monotonicNow, minimumRemainingMillis = 0L)
    }

    fun secondsUntil(deadline: Long, now: Long): Int = ((deadline - now + 999L) / 1000L).toInt().coerceIn(0, 3)

    fun canArm(packet: RoomMessagePacket, localId: String, now: Long = System.currentTimeMillis(),
        minimumRemainingMillis: Long = 2500L): Boolean {
        val remaining = packet.startCommitDeadline + (packet.startClockOffsets[localId] ?: 0L) - now
        return packet.startCommitDeadline > 0L && remaining >= minimumRemainingMillis && remaining <= 6000L
    }

    /** Host and guest share the same deadline; final persistence must succeed before entry. */
    suspend fun awaitHostStart(packet: RoomMessagePacket, localId: String,
        finalize: suspend () -> Boolean, valid: () -> Boolean, display: (Int) -> Unit,
        wallNow: () -> Long = System::currentTimeMillis,
        monotonicNow: () -> Long = System::nanoTime): Boolean {
        return coroutineScope {
            var finalResult: Boolean? = null
            val finalization = async { finalize().also { finalResult = it } }
            try {
                // Display starts on ingress, just as on the guest. Persistence and final ACKs
                // run during the countdown; neither may grant an extension or skip a number.
                val counted = awaitStart(packet, localId, { valid() && finalResult != false },
                    display, wallNow, monotonicNow)
                counted && finalization.isCompleted && finalization.await()
            } finally { finalization.cancelAndJoin() }
        }
    }

    suspend fun awaitStart(packet: RoomMessagePacket, localId: String, valid: () -> Boolean,
        display: (Int) -> Unit, wallNow: () -> Long = System::currentTimeMillis,
        monotonicNow: () -> Long = System::nanoTime, minimumRemainingMillis: Long = 2500L): Boolean {
        val deadline = packet.startCommitDeadline + (packet.startClockOffsets[localId] ?: 0L)
        val sampledWallNow = wallNow()
        val remaining = deadline - sampledWallNow
        // A live start cannot silently skip its countdown because a write/packet arrived late.
        if (!canArm(packet, localId, sampledWallNow, minimumRemainingMillis) || !valid()) return false
        val end = monotonicNow() + remaining * 1_000_000L
        var previous = -1
        while (true) {
            if (!valid()) return false
            val left = ((end - monotonicNow()) / 1_000_000L).coerceAtLeast(0L)
            if (left == 0L) return valid()
            val seconds = secondsUntil(left, 0L)
            if (seconds != previous) { display(seconds); previous = seconds }
            delay(minOf(left, 40L))
        }
    }
}

/** Uses the existing preparation/ACK exchange only; it never controls lobby admission. */
internal class LobbyClockCalibration {
    private var seed = 0L
    private var host = ""
    private var ids = emptySet<String>()
    private val sent = linkedMapOf<Long, Long>()
    private val samples = mutableMapOf<String, Pair<Long, Long>>()
    fun begin(matchSeed: Long, hostId: String, rosterIds: Set<String>) {
        seed = matchSeed; host = hostId; ids = rosterIds; sent.clear(); samples.clear()
    }
    fun prepare(packet: RoomMessagePacket, now: Long = System.currentTimeMillis(),
        monotonic: Long = System.nanoTime()): RoomMessagePacket {
        sent[now] = monotonic
        while (sent.size > 20) sent.remove(sent.keys.first())
        return packet.copy(timestamp = now)
    }
    fun acknowledge(packet: RoomMessagePacket, nowMonotonic: Long = System.nanoTime()) {
        if (packet.seed != seed || packet.playerId !in ids || packet.playerId == host || packet.timestamp <= 0L) return
        val started = sent[packet.pingTimestamp] ?: return
        val rttMillis = (nowMonotonic - started) / 1_000_000L
        if (rttMillis !in 0L..1500L) return
        val offset = packet.timestamp - (packet.pingTimestamp + rttMillis / 2L)
        if (samples[packet.playerId]?.first?.let { it <= rttMillis } == true) return
        samples[packet.playerId] = rttMillis to offset
    }
    fun offsets(): Map<String, Long> = ids.associateWith { if (it == host) 0L else samples[it]?.second ?: 0L }
}
