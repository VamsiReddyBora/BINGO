package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.internal.wire.MqttConnack
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPingReq
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.Vector
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MqttClientShutdownTest {
    /**
     * Real Paho 1.2.5 disconnect worker and queue. Only the socket/connection-loss
     * scheduling is controlled: loss completes while the worker is quiescing,
     * and close releases its queue before the worker wakes (the recorded crash).
     * This is fault injection, not a physical-phone reproduction.
     */
    private class QuiesceRaceClient : MqttAsyncClient(
        "tcp://127.0.0.1:1883", "shutdown-race-${UUID.randomUUID()}", MemoryPersistence()
    ) {
        val failures = CopyOnWriteArrayList<Throwable>()
        val closeCompleted = CountDownLatch(1)
        private val state = comms.clientState
        private val queueLock = field(state, "queueLock")!!
        var worker: Thread? = null
        var asynchronousDisconnects = 0
        var synchronousDisconnects = 0

        init {
            // These are the structures Paho normally initializes on connect.
            setField(state, "pendingMessages", Vector<Any>())
            @Suppress("UNCHECKED_CAST")
            (field(state, "pendingFlows") as Vector<Any>).add(MqttPingReq())
            comms.connectComplete(MqttConnack(0x20.toByte(), byteArrayOf(0, 0)), null)
        }

        override fun disconnect(): IMqttToken {
            asynchronousDisconnects++
            val token = super.disconnect(350L)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val thread = Thread.getAllStackTraces().keys.firstOrNull { it.name == "MQTT Disc: $clientId" }
                if (thread != null && thread.state == Thread.State.TIMED_WAITING) {
                    worker = thread
                    thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failures.add(error) }
                    return token
                }
                Thread.sleep(1L)
            }
            error("Real Paho disconnect worker did not enter quiesce")
        }

        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) {
            synchronousDisconnects++
            super.disconnectForcibly(quiesceTimeout, disconnectTimeout)
        }

        override fun close(force: Boolean) {
            try {
                if (worker != null) {
                    synchronized(queueLock) {
                        // Simulate the completed connection-loss transition while
                        // the actual worker is still in its quiesce wait.
                        setField(comms, "conState", 3.toByte())
                        super.close(force)
                    }
                } else super.close(force)
            } finally { closeCompleted.countDown() }
        }

        fun assertSafe() {
            assertTrue("Client was not closed", closeCompleted.await(3, TimeUnit.SECONDS))
            worker?.join(2000L)
            assertTrue("Paho background worker crashed: $failures", failures.isEmpty())
            assertEquals("No asynchronous disconnect worker may outlive cleanup", 0, asynchronousDisconnects)
            assertEquals(1, synchronousDisconnects)
        }
    }

    private class RecordingClient : MqttAsyncClient(
        "tcp://127.0.0.1:1883", "shutdown-record-${UUID.randomUUID()}", MemoryPersistence()
    ) {
        val calls = CopyOnWriteArrayList<String>()
        var failDisconnect = false
        override fun isConnected() = true
        override fun disconnect(): IMqttToken {
            calls.add("async")
            return CompletedMqttToken(clientId)
        }
        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) {
            calls.add("sync:$quiesceTimeout:$disconnectTimeout")
            if (failDisconnect) throw MqttException(MqttException.REASON_CODE_CONNECTION_LOST.toInt())
        }
        override fun close(force: Boolean) { calls.add("close"); super.close(force) }
    }

    @Test fun quiesceQueueCannotCrashAfterConnectionLossDuringShutdown() {
        val client = QuiesceRaceClient()
        MqttClientShutdown.close(client, quiesceMillis = 50L, disconnectMillis = 50L)
        client.assertSafe()
    }

    @Test fun asynchronousCallerUsesTheSameSafeShutdown() = runBlocking {
        val client = QuiesceRaceClient()
        MqttClientShutdown.closeAsync(client).join()
        client.assertSafe()
    }

    @Test fun duplicateConcurrentCleanupRetiresOneClientOnlyOnce() {
        val client = RecordingClient()
        val start = CountDownLatch(1)
        val threads = List(3) { Thread { start.await(); MqttClientShutdown.close(client) }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(3000L); assertFalse(it.isAlive) }
        assertEquals(listOf("sync:1000:1000", "close"), client.calls)
    }

    @Test fun disconnectFailureStillReleasesClientWithoutEscaping() {
        val client = RecordingClient().apply { failDisconnect = true }
        MqttClientShutdown.close(client, quiesceMillis = 20L, disconnectMillis = 50L)
        assertEquals(listOf("sync:20:50", "close"), client.calls)
    }

    @Test fun absentClientNeedsNoCleanup() { MqttClientShutdown.close(null) }

    companion object {
        private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(target)
        private fun setField(target: Any, name: String, value: Any) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }
}
