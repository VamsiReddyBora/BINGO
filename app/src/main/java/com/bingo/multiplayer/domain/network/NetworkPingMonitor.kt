package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuous real-time network latency monitor.
 * Reads fresh subscribed EMQX echo RTT from the persistent connection.
 * An Internet ping or TCP handshake never establishes broker health.
 */
object NetworkPingMonitor {

    private val _pingMs = MutableStateFlow<Long>(0L) // Unknown until a probe succeeds.
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    @Volatile
    var lastSuccessfulProbeTimestamp: Long = 0L
        private set

    val isInternetFresh: Boolean
        get() = EmqxTransport.connection.isHealthy && _pingMs.value > 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var lastBrokerEcho: EmqxConnection.EchoSample? = null

    init {
        start()
    }

    fun start() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch {
            while (isActive) {
                val connection = EmqxTransport.connection
                recordBrokerEcho(connection.lastEchoSample.takeIf { connection.isHealthy })
                delay(1000L)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    internal suspend fun stopAndAwait() {
        val running = monitorJob
        stop()
        running?.join()
    }

    /** Sampling the same RTT again is not a new successful network round trip. */
    internal fun recordBrokerEcho(sample: EmqxConnection.EchoSample?, now: Long = System.currentTimeMillis()) {
        if (sample != null && sample !== lastBrokerEcho) {
            lastBrokerEcho = sample
            updateSmoothedPing(sample.rttMillis, sample.receivedAtMillis)
        } else updateSmoothedPing(0L, now)
    }

    fun recordExternalPing(rttMs: Long) {
        if (rttMs > 0L) {
            lastSuccessfulProbeTimestamp = System.currentTimeMillis()
            updateSmoothedPing(rttMs)
        }
    }

    private fun updateSmoothedPing(newRtt: Long, observedAt: Long = System.currentTimeMillis()) {
        if (newRtt <= 0L) {
            if (observedAt - lastSuccessfulProbeTimestamp >= 15000L) {
                _pingMs.value = 0L
            }
            return
        }
        lastSuccessfulProbeTimestamp = observedAt
        val cur = _pingMs.value
        val clamped = newRtt.coerceIn(1L, 9999L)
        _pingMs.value = if (cur <= 0L) {
            clamped
        } else {
            // Exponential moving average: 60% previous + 40% new
            ((cur * 0.60) + (clamped * 0.40)).toLong().coerceAtLeast(1L)
        }
    }

}
