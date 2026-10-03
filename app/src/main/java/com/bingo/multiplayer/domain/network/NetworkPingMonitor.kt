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
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Continuous real-time network latency monitor.
 * Probes low-latency socket handshakes and HTTP fast-checks every 1.5 - 2.0s
 * to provide authentic, live ping (RTT in ms) to the UI.
 */
object NetworkPingMonitor {

    private val _pingMs = MutableStateFlow<Long>(32L) // Plausible initial ping
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    init {
        start()
    }

    fun start() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch {
            while (isActive) {
                val measuredRtt = measureRealtimePing()
                updateSmoothedPing(measuredRtt)
                delay(1000L)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun recordExternalPing(rttMs: Long) {
        if (rttMs > 0L) {
            updateSmoothedPing(rttMs)
        }
    }

    private fun updateSmoothedPing(newRtt: Long) {
        val cur = _pingMs.value
        val clamped = newRtt.coerceIn(1L, 9999L)
        _pingMs.value = if (cur <= 0L) {
            clamped
        } else {
            // Exponential moving average: 60% previous + 40% new
            ((cur * 0.60) + (clamped * 0.40)).toLong().coerceAtLeast(1L)
        }
    }

    private fun measureRealtimePing(): Long {
        // Fast probe 1: TCP handshake to high-availability Google DNS (port 53)
        val socketStart = System.nanoTime()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("8.8.8.8", 53), 1200)
            val elapsedMs = (System.nanoTime() - socketStart) / 1_000_000L
            return elapsedMs.coerceAtLeast(1L)
        } catch (_: Exception) {
            // Socket connect failed; try HTTP generate_204 fallback
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }

        // Fast probe 2: HTTP HEAD to connectivity check endpoint
        val httpStart = System.nanoTime()
        try {
            val request = Request.Builder()
                .url("https://connectivitycheck.gstatic.com/generate_204")
                .head()
                .build()

            NetworkConfig.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val elapsedMs = (System.nanoTime() - httpStart) / 1_000_000L
                    return elapsedMs.coerceAtLeast(1L)
                }
            }
        } catch (_: Exception) {
            // Complete network unreachable / timeout
            return 999L
        }

        return 999L
    }
}
