package com.bingo.multiplayer.domain.network

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled wire only. All metadata protocol, authority, conflict and invitation logic is production code. */
internal class EmqxWireFixture : EmqxWire {
    override var isHealthy = true
    val retained = ConcurrentHashMap<String, ByteArray>()
    val published = CopyOnWriteArrayList<String>()
    private val callbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray, Boolean) -> Unit>>()
    var beforePublish: suspend (String, ByteArray) -> Unit = { _, _ -> }
    var beforeFence: suspend () -> Unit = {}
    var afterPublish: suspend (String, ByteArray) -> Unit = { _, _ -> }
    private fun matches(filter: String, topic: String): Boolean {
        val a = filter.split('/'); val b = topic.split('/')
        return a.size == b.size && a.indices.all { a[it] == "+" || a[it] == b[it] }
    }
    fun activeSubscriptions(topic: String): Int = callbacks[topic]?.size ?: 0
    override suspend fun subscribe(topic: String, handler: (ByteArray, Boolean) -> Unit): AutoCloseable {
        if (!isHealthy) throw IOException("Controlled EMQX outage")
        val list = callbacks.computeIfAbsent(topic) { CopyOnWriteArrayList() }
        list.add(handler)
        retained.forEach { (key, value) -> if (matches(topic, key)) handler(value, true) }
        return AutoCloseable { list.remove(handler) }
    }
    override suspend fun publish(topic: String, payload: ByteArray, retained: Boolean) {
        if (!isHealthy) throw IOException("Controlled EMQX outage")
        beforePublish(topic, payload)
        published.add(topic)
        if (retained) { if (payload.isEmpty()) this.retained.remove(topic) else this.retained[topic] = payload }
        callbacks.forEach { (filter, handlers) -> if (matches(filter, topic)) handlers.forEach { it(payload, false) } }
        afterPublish(topic, payload)
    }
    override suspend fun readRetained(topic: String): ByteArray? {
        if (!isHealthy) throw IOException("Controlled EMQX outage")
        return retained[topic]?.copyOf()
    }
    override suspend fun confirmRetained(topic: String, payload: ByteArray): Boolean = isHealthy && (
        if (payload.isEmpty()) retained[topic] == null || retained[topic]?.isEmpty() == true
        else retained[topic]?.contentEquals(payload) == true
    )
    override suspend fun fence() { beforeFence(); if (!isHealthy) throw IOException("Controlled EMQX outage") }
}
