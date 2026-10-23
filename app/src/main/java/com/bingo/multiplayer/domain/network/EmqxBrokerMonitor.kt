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
 * Monitors the real-time reachability of the primary EMQX MQTT broker.
 * When the EMQX broker is turned off or unreachable, marks [isBrokerDown] as true.
 * It stays true and keeps the alert scrolling continuously until the primary server
 * is turned back on and a subscribed MQTT echo succeeds.
 */
object EmqxBrokerMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _isBrokerDown = MutableStateFlow(true)
    val isBrokerDown: StateFlow<Boolean> = _isBrokerDown.asStateFlow()

    internal var healthyOverride: (() -> Boolean)? = null
    private fun brokerHealthy() = healthyOverride?.invoke() ?: EmqxTransport.connection.isHealthy
    init { start() }

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            launch {
                EmqxTransport.connection.healthy.collect { isHealthy ->
                    if (healthyOverride == null) {
                        _isBrokerDown.value = !isHealthy
                    }
                }
            }
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

    /** Await any in-flight probe when a caller needs a fully stopped monitor. */
    internal suspend fun stopAndAwait() {
        val running = monitorJob
        stop()
        running?.join()
    }

    fun recordPrimaryBrokerDown() {
        _isBrokerDown.value = true
    }

    fun recordPrimaryBrokerUp() {
        _isBrokerDown.value = !brokerHealthy()
    }

    // Backwards-compatible aliases
    fun recordBrokerFailure() = recordPrimaryBrokerDown()
    fun recordBrokerSuccess() = recordPrimaryBrokerUp()

    /**
     * Directly probes whether the primary EMQX server is currently down.
     * Returns true if down, false if healthy.
     */
    private fun probeIsPrimaryBrokerDown(): Boolean = !brokerHealthy()
}
