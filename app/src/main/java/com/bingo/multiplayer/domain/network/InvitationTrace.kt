package com.bingo.multiplayer.domain.network

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/** Bounded local diagnostics; no credentials, payloads or account identifiers are logged.
 * Device monotonic times are comparable only on the same device. PUBACK is not recipient/UI proof. */
object InvitationTrace {
    data class Sample(val event: String, val stage: String, val elapsedNanos: Long, val wallMillis: Long)
    private val samples = CopyOnWriteArrayList<Sample>()
    fun mark(event: String, stage: String) {
        val sample = Sample(event, stage, System.nanoTime(), System.currentTimeMillis())
        samples.add(sample)
        while (samples.size > 256) samples.removeAt(0)
        runCatching { Log.d("InvitationTiming", "event=$event stage=$stage wall=${sample.wallMillis} mono=${sample.elapsedNanos}") }
    }
    fun snapshot(event: String): List<Sample> = samples.filter { it.event == event }
}
