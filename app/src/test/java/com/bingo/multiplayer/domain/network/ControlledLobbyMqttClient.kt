package com.bingo.multiplayer.domain.network

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled broker only; all connection, admission and recovery logic is production code. */
internal class ControlledLobbyMqttClient(id: String, val limit: Int = 10) : MqttAsyncClient("tcp://127.0.0.1:1883", id, MemoryPersistence()) {
    val filters = ConcurrentHashMap.newKeySet<String>()
    val rejected = CopyOnWriteArrayList<String>()
    var mute = false
    @Volatile var rejectNextMatchSubscription = false
    @Volatile var dropNextMarker = false
    val markerPublications = java.util.concurrent.atomic.AtomicInteger()
    var beforeUnsubscribe: (String) -> Unit = {}
    var brokerCallback: MqttCallback? = null
    @Volatile var online = false
    private fun token(qos: IntArray = intArrayOf(1), delivery: Boolean = false): IMqttToken {
        val kind = if (delivery) IMqttDeliveryToken::class.java else IMqttToken::class.java
        return Proxy.newProxyInstance(kind.classLoader, arrayOf(kind)) { _, method, _ -> when(method.name) {
            "getGrantedQos" -> qos
            "isComplete" -> true
            "getMessageId" -> 1
            else -> null
        } } as IMqttToken
    }
    override fun isConnected() = online
    override fun setCallback(next: MqttCallback?) { brokerCallback = next }
    override fun connect(options: MqttConnectOptions?, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
        online = true
        val t = token(); listener?.onSuccess(t)
        (brokerCallback as? MqttCallbackExtended)?.connectComplete(false, "ssl://owned-test-server")
        return t
    }
    private fun grant(topics: Array<out String>): IMqttToken = token(topics.map { topic ->
        if (topic.startsWith("bingo/emqx7/match/") && topic.endsWith("/#") && rejectNextMatchSubscription) {
            rejectNextMatchSubscription = false; rejected.add(topic); 128
        } else if (topic in filters || filters.size < limit) { filters.add(topic); 1 }
        else { rejected.add(topic); 128 }
    }.toIntArray())
    override fun subscribe(topic: String, qos: Int): IMqttToken = grant(arrayOf(topic))
    override fun subscribe(topics: Array<out String>, qos: IntArray): IMqttToken = grant(topics)
    override fun subscribe(topic: String, qos: Int, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
        val t = grant(arrayOf(topic)); listener?.onSuccess(t); return t
    }
    override fun unsubscribe(topic: String, userContext: Any?, listener: IMqttActionListener?): IMqttToken {
        beforeUnsubscribe(topic); filters.remove(topic); val t = token(); listener?.onSuccess(t); return t
    }
    override fun unsubscribe(topic: String): IMqttToken {
        beforeUnsubscribe(topic); filters.remove(topic); return token()
    }
    private fun emit(topic: String, message: MqttMessage?) : IMqttDeliveryToken {
        if (topic.startsWith("bingo/emqx7/match/") && topic.contains("/probe/")) {
            markerPublications.incrementAndGet()
            if (dropNextMarker) { dropNextMarker = false; return token(delivery=true) as IMqttDeliveryToken }
        }
        if (!mute && message != null && filters.any { filter ->
            val a = filter.split('/'); val b = topic.split('/')
            var found = true
            for (i in a.indices) {
                if (a[i] == "#") break
                if (i >= b.size || (a[i] != "+" && a[i] != b[i])) { found=false; break }
                if (i == a.lastIndex && a.size != b.size) found=false
            }
            found
        }) brokerCallback?.messageArrived(topic, message)
        return token(delivery=true) as IMqttDeliveryToken
    }
    override fun publish(topic: String, message: MqttMessage?): IMqttDeliveryToken = emit(topic,message)
    override fun publish(topic: String, message: MqttMessage?, userContext: Any?, listener: IMqttActionListener?): IMqttDeliveryToken {
        val t=emit(topic,message); listener?.onSuccess(t); return t
    }
    override fun disconnect(): IMqttToken { online=false; filters.clear(); return token() }
    override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) { online=false; filters.clear() }
    override fun close(force: Boolean) { online=false; super.close(force) }
}

