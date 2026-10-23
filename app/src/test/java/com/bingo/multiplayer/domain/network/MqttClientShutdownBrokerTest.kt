package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Paho sockets and MQTT acknowledgments; no EMQX credentials or Internet required. */
class MqttClientShutdownBrokerTest {
    private class Broker : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val uri = "tcp://127.0.0.1:${server.localPort}"
        val published = CountDownLatch(1)
        val acknowledge = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        @Volatile var payload = ""
        @Volatile private var socket: Socket? = null
        @Volatile private var stopping = false
        private val worker = Thread({
            try {
                server.accept().use { peer ->
                    socket = peer
                    peer.soTimeout = 5000
                    val input = DataInputStream(peer.getInputStream())
                    val output = peer.getOutputStream()
                    while (!stopping) {
                        val header = input.read()
                        if (header < 0) break
                        var length = 0
                        var multiplier = 1
                        do {
                            val digit = input.readUnsignedByte()
                            length += (digit and 127) * multiplier
                            multiplier *= 128
                            check(length <= 65536 && multiplier <= 128 * 128 * 128 * 128)
                        } while (digit and 128 != 0)
                        val body = ByteArray(length)
                        input.readFully(body)
                        when (header ushr 4) {
                            1 -> { output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush() }
                            3 -> {
                                val topicSize = (body[0].toInt() and 255) * 256 + (body[1].toInt() and 255)
                                val packetId = 2 + topicSize
                                payload = body.copyOfRange(packetId + 2, body.size).toString(Charsets.UTF_8)
                                published.countDown()
                                check(acknowledge.await(3, TimeUnit.SECONDS))
                                if (!stopping) {
                                    output.write(byteArrayOf(0x40, 2, body[packetId], body[packetId + 1]))
                                    output.flush()
                                }
                            }
                            14 -> { disconnected.countDown(); break }
                        }
                    }
                }
            } catch (error: Exception) { if (!stopping) failures.add(error) }
        }, "shutdown-test-broker").apply { isDaemon = true; start() }

        fun dropConnection() {
            stopping = true
            socket?.close()
            acknowledge.countDown()
        }

        override fun close() {
            dropConnection()
            server.close()
            worker.join(2000L)
            assertFalse("Loopback broker did not stop", worker.isAlive)
            assertTrue("Loopback broker failed: $failures", failures.isEmpty())
        }
    }

    private fun connect(broker: Broker) = MqttAsyncClient(
        broker.uri, "safe-close-${UUID.randomUUID()}", MemoryPersistence()
    ).apply {
        connect(MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false
            connectionTimeout = 2
        }).waitForCompletion(3000L)
    }

    @Test fun pendingQosOneMessageFinishesBeforeNormalDisconnectPacket() = runBlocking {
        Broker().use { broker ->
            val client = connect(broker)
            try {
                val sent = client.publish("test/shutdown", MqttMessage("keep-this-message".toByteArray()).apply { qos = 1 })
                assertTrue(broker.published.await(2, TimeUnit.SECONDS))
                val cleanup = MqttClientShutdown.closeAsync(client)
                assertFalse("Shutdown discarded the pending publish", broker.disconnected.await(50, TimeUnit.MILLISECONDS))
                broker.acknowledge.countDown()
                withTimeout(4000L) { cleanup.join() }
                assertTrue("Normal MQTT DISCONNECT was not sent", broker.disconnected.await(2, TimeUnit.SECONDS))
                assertTrue(sent.isComplete)
                assertEquals("keep-this-message", broker.payload)
                assertFalse(client.isConnected)
            } finally { MqttClientShutdown.close(client) }
        }
    }

    @Test fun socketLossDuringPendingPublishClosesWithoutAnOrphanDisconnectWorker() {
        Broker().use { broker ->
            val client = connect(broker)
            try {
                client.publish("test/shutdown", MqttMessage(byteArrayOf(1)).apply { qos = 1 })
                assertTrue(broker.published.await(2, TimeUnit.SECONDS))
                broker.dropConnection()
                MqttClientShutdown.close(client, quiesceMillis = 50L, disconnectMillis = 50L)
                assertFalse(client.isConnected)
                assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "MQTT Disc: ${client.clientId}" })
            } finally { MqttClientShutdown.close(client) }
        }
    }
}
