package com.bingo.multiplayer.domain.network

import android.util.Log
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Monitors the real-time reachability of the primary EMQX MQTT broker.
 * When the EMQX broker is turned off or unreachable, marks [isBrokerDown] as true.
 * It stays true and keeps the alert scrolling continuously until the primary server
 * is turned back on and responds to socket handshakes.
 */
object EmqxBrokerMonitor {
    private const val TAG = "EmqxBrokerMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _isBrokerDown = MutableStateFlow(false)
    val isBrokerDown: StateFlow<Boolean> = _isBrokerDown.asStateFlow()

    private val primaryHost: String
    private val primaryPort: Int

    init {
        val uri = try {
            val raw = NetworkConfig.BROKER_URL.replace("ssl://", "http://").replace("tcp://", "http://")
            URI(raw)
        } catch (_: Exception) {
            null
        }
        primaryHost = uri?.host ?: "p0812f88.ala.asia-southeast1.emqxsl.com"
        primaryPort = uri?.port?.takeIf { it > 0 } ?: 8883

        start()
    }

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            // Immediate check on startup
            val initialDown = probeIsPrimaryBrokerDown()
            _isBrokerDown.value = initialDown

            while (isActive) {
                delay(3000L)
                val isDown = probeIsPrimaryBrokerDown()
                _isBrokerDown.value = isDown
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun recordPrimaryBrokerDown() {
        _isBrokerDown.value = true
    }

    fun recordPrimaryBrokerUp() {
        _isBrokerDown.value = false
    }

    // Backwards-compatible aliases
    fun recordBrokerFailure() = recordPrimaryBrokerDown()
    fun recordBrokerSuccess() = recordPrimaryBrokerUp()

    /**
     * Directly probes whether the primary EMQX server is currently down.
     * Returns true if down, false if healthy.
     */
    private fun probeIsPrimaryBrokerDown(): Boolean {
        // If PresenceManager is actively connected to the primary EMQX broker:
        if (PresenceManager.isPrimaryBrokerConnected) {
            return false
        }

        // Fast probe directly to the primary EMQX server port (8883)
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(primaryHost, primaryPort), 2500)
            false // Primary broker port reached successfully
        } catch (e: Exception) {
            Log.d(TAG, "EMQX primary broker probe failed: ${e.message}")
            true // Primary server is down / turned off / unreachable
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
