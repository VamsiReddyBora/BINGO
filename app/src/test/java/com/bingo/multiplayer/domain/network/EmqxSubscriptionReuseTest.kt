package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The real production connection runs against a bounded in-memory MQTT server. */
class EmqxSubscriptionReuseTest {
    private class BoundedClient(id: String) : MqttAsyncClient("tcp://127.0.0.1:1883", id, MemoryPersistence()) {
        val filters = ConcurrentHashMap.newKeySet<String>()
        private var callback: MqttCallback? = null
        @Volatile private var online = false
        private fun token(qos: Int = 1): IMqttToken = Proxy.newProxyInstance(
            IMqttToken::class.java.classLoader, arrayOf(IMqttToken::class.java)
        ) { _, method, _ -> when (method.name) {
            "getGrantedQos" -> intArrayOf(qos)
            "isComplete" -> true
            "getMessageId" -> 1
            else -> null
        } } as IMqttToken
        override fun setCallback(callback: MqttCallback?) { this.callback = callback }
        override fun isConnected() = online
        override fun connect(options: MqttConnectOptions?, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
            online = true
            val t = token(); listener?.onSuccess(t); return t
        }
        override fun subscribe(topic: String, qos: Int, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
            val granted = if (topic in filters || filters.size < 10) qos else 128
            if (granted != 128) filters.add(topic)
            val t = token(granted); listener?.onSuccess(t); return t
        }
        override fun unsubscribe(topic: String, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
            filters.remove(topic)
            val t = token(); listener?.onSuccess(t); return t
        }
        override fun publish(topic: String, message: MqttMessage?, userContext: Any?, listener: IMqttActionListener?): IMqttDeliveryToken {
            if (filters.any { filter ->
                val a = filter.split('/'); val b = topic.split('/')
                a.size == b.size && a.indices.all { a[it] == "+" || a[it] == b[it] }
            }) callback?.messageArrived(topic, message!!)
            val t = Proxy.newProxyInstance(IMqttDeliveryToken::class.java.classLoader,
                arrayOf(IMqttDeliveryToken::class.java)) { _, method, _ ->
                when (method.name) { "isComplete" -> true; "getMessageId" -> 1; else -> null }
            } as IMqttDeliveryToken
            listener?.onSuccess(t); return t
        }
        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) { online = false; filters.clear() }
        override fun close(force: Boolean) { online = false; super.close(force) }
    }

    @Test fun roomMetadataSharesOneBrokerSlotAndKeepsExactCallbacksUntilLastSubscriberLeaves() = runBlocking {
        val client = BoundedClient("bounded-${UUID.randomUUID()}")
        val connection = EmqxConnection("bounded-test-${UUID.randomUUID()}", initialClientProvider = { _, _ -> client })
        val handles = mutableListOf<AutoCloseable>()
        try {
            assertTrue(connection.awaitHealthy(8000L))
            repeat(7) { handles += connection.subscribe("owned/other/$it") { _, _ -> } }
            handles += connection.subscribe("bingo/emqx7/metadata/reply/owned") { _, _ -> }
            val state = "bingo/emqx7/metadata/ABC123/state"
            val command = "bingo/emqx7/metadata/ABC123/command"
            val received = CopyOnWriteArrayList<String>()
            val record = connection.subscribe(state) { _, _ -> received.add("state") }
            handles += record
            handles += connection.subscribe(command) { _, _ -> received.add("command") }
            handles += connection.subscribe("bingo/emqx7/metadata/ABC123/committed") { _, _ -> received.add("committed") }
            assertEquals(10, client.filters.size)
            assertTrue(client.filters.contains("bingo/emqx7/metadata/ABC123/+"))
            connection.publish(state, "snapshot".toByteArray())
            connection.publish(command, "join".toByteArray())
            assertEquals(listOf("state", "command"), received.toList())
            record.close()
            delay(50L)
            connection.publish(command, "ready".toByteArray())
            assertEquals(listOf("state", "command", "command"), received.toList())
            assertTrue(connection.isHealthy)
            handles.forEach { it.close() }; handles.clear()
            withTimeout(3000L) { while (client.filters.any { it.contains("metadata/") }) delay(10L) }
            assertFalse(client.filters.contains("bingo/emqx7/metadata/ABC123/+"))
        } finally { handles.forEach { it.close() }; connection.close() }
    }
}
