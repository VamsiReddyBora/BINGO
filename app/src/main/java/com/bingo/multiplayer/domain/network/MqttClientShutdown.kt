package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import java.util.WeakHashMap

/** Shared teardown for clients that are permanently retired, never for temporary absence. */
internal object MqttClientShutdown {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retired = WeakHashMap<MqttAsyncClient, Boolean>()

    /**
     * Call on an IO worker. Paho 1.2.5's asynchronous disconnect leaves a
     * DisconnectBG thread quiescing after a concurrent connection loss/close can
     * release its queues. Its uncaught NPE terminates the whole Android process.
     * The synchronous API drains work and sends the same MQTT DISCONNECT packet,
     * but finishes quiescing on this caller before close can release the queues.
     * A failed socket still reaches close, and duplicate cleanup is serialized.
     */
    fun close(client: MqttAsyncClient?, quiesceMillis: Long = 1000L, disconnectMillis: Long = 1000L) {
        if (client == null) return
        synchronized(client) {
            if (synchronized(retired) { retired.put(client, true) } == true) return
            try {
                // Both waits are bounded; zero disconnect timeout would mean an
                // infinite wait in Paho. Never start an asynchronous disconnect.
                client.disconnectForcibly(quiesceMillis.coerceAtLeast(0L), disconnectMillis.coerceAtLeast(1L))
            } catch (_: Exception) {
                // A lost/already closed socket must not prevent final cleanup.
            } finally {
                try { client.close(true) } catch (_: Exception) { }
            }
        }
    }

    fun closeAsync(client: MqttAsyncClient?) = scope.launch { close(client) }
}
