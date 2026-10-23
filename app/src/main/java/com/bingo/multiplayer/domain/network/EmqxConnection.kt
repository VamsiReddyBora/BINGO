package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One persistent EMQX connection. Health requires SUBACK and a fresh round trip on this socket.
 * No HTTP, alternate broker, action queue, or per-action connection is used. */
interface EmqxWire {
    val isHealthy: Boolean
    suspend fun subscribe(topic: String, handler: (ByteArray, Boolean) -> Unit): AutoCloseable
    suspend fun publish(topic: String, payload: ByteArray, retained: Boolean = false)
    suspend fun fence()
    suspend fun readRetained(topic: String): ByteArray?
    suspend fun confirmRetained(topic: String, payload: ByteArray): Boolean
}

class EmqxConnection(private val identity: String = UUID.randomUUID().toString(),
    initialClientProvider: ((url: String, clientId: String) -> MqttAsyncClient)? = null,
    private val connectBudgetMillis: () -> Long = { 5000L }) : AutoCloseable, EmqxWire {

    companion object {
        const val ECHO_EXPIRY_NANOS = 15_000_000_000L // 15s aligned with NetworkStatusMapper FRESHNESS_WINDOW_MS
        const val OPERATION_TIMEOUT_MS = 6000L
        const val CONNECT_TIMEOUT_SECONDS = 10
        const val KEEPALIVE_INTERVAL_SECONDS = 20
        const val MAX_INFLIGHT_MESSAGES = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray, Boolean) -> Unit>>()
    private val subscribed = ConcurrentHashMap.newKeySet<String>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    internal var client: MqttAsyncClient? = null
    // Tests must inject before init starts IO, rather than racing the first real connection.
    internal var clientProvider: ((url: String, clientId: String) -> MqttAsyncClient)? = initialClientProvider
    @Volatile private var closed = false
    @Volatile var lastFailure: String = "none"
        private set
    @Volatile private var lastEcho = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var receivedCount = 0
    internal val diagnostics: String get() = "connected=${client?.isConnected} subscriptions=${subscribed.size} handlers=${handlers.size} probes=${probes.size} received=$receivedCount failure=$lastFailure"
    private val probes = ConcurrentHashMap<String, Long>()
    private val echoTopic = "bingo/emqx7/probe/$identity"
    private val _rtt = MutableStateFlow(0L)
    val rtt: StateFlow<Long> = _rtt
    private val _healthy = MutableStateFlow(false)
    val healthy: StateFlow<Boolean> = _healthy
    internal data class EchoSample(val rttMillis: Long, val receivedAtMillis: Long)
    @Volatile internal var lastEchoSample: EchoSample? = null
        private set
    internal val isTransportUsable: Boolean get() = !closed && client?.isConnected == true
    override val isHealthy: Boolean get() = isTransportUsable &&
        lastEcho != 0L && System.nanoTime() - lastEcho <= ECHO_EXPIRY_NANOS &&
        subscribed.containsAll(handlers.filterValues { it.isNotEmpty() }.keys.map(::subscriptionFilter).filterNot { it in refreshing })

    // Keep exact local callbacks, but share one broker subscription for a room's metadata.
    // The broker limits subscriptions per connection; state, commands and notifications
    // must not consume separate slots on every lobby lookup.
    private fun subscriptionFilter(topic: String): String {
        val prefix = "bingo/emqx7/metadata/"
        if (!topic.startsWith(prefix)) return topic
        val parts = topic.removePrefix(prefix).split('/')
        return if (parts.size == 2 && parts[0] != "reply" &&
            parts[1] in setOf("state", "auth", "command", "committed")) "$prefix${parts[0]}/+" else topic
    }

    init {
        handlers[echoTopic] = CopyOnWriteArrayList<(ByteArray, Boolean) -> Unit>().apply { add { bytes, _ ->
            probes.remove(bytes.toString(Charsets.UTF_8))?.let { sent ->
                lastEcho = System.nanoTime()
                val sample = EchoSample(((lastEcho - sent) / 1_000_000L).coerceAtLeast(1L), System.currentTimeMillis())
                lastEchoSample = sample
                _rtt.value = sample.rttMillis
                _healthy.value = isHealthy
            }
        } }
        scope.launch {
            var consecutiveFailures = 0
            while (isActive && !closed) {
                try {
                    val c = client
                    val now = System.nanoTime()
                    val isZombie = c == null || !c.isConnected ||
                        (lastEcho != 0L && now - lastEcho > ECHO_EXPIRY_NANOS) ||
                        (lastEcho == 0L && connectedAt != 0L && now - connectedAt > ECHO_EXPIRY_NANOS) ||
                        consecutiveFailures >= 2

                    if (isZombie) {
                        connect(force = true)
                    } else {
                        connect(force = false)
                    }

                    for (topic in handlers.filterValues { it.isNotEmpty() }.keys) {
                        ensureSubscription(topic)
                    }
                    if (subscribed.contains(echoTopic)) {
                        if (probes.size < 2) {
                            val nonce = UUID.randomUUID().toString()
                            probes[nonce] = System.nanoTime()
                            publishRaw(echoTopic, nonce.toByteArray(), false)
                        }
                    }
                    consecutiveFailures = 0
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    consecutiveFailures++
                    lastFailure = e.javaClass.simpleName + if (e is MqttException) ":${e.reasonCode}" else ""
                    if (consecutiveFailures >= 2) {
                        invalidate()
                    }
                }
                probes.entries.removeIf { System.nanoTime() - it.value > ECHO_EXPIRY_NANOS }
                _healthy.value = isHealthy
                if (!isHealthy) _rtt.value = 0L
                delay(1500L)
            }
        }
    }
    private fun matches(filter: String, topic: String): Boolean {
        val a = filter.split('/'); val b = topic.split('/')
        return a.size == b.size && a.indices.all { a[it] == "+" || a[it] == b[it] }
    }
    private fun invalidate() {
        lastEcho = 0L; lastEchoSample = null; probes.clear()
        _healthy.value = false; _rtt.value = 0L
    }
    private suspend fun connect(force: Boolean = false) = gate.withLock {
        if (closed) return@withLock
        val current = client
        if (!force && current?.isConnected == true && (lastEcho == 0L || System.nanoTime() - lastEcho <= ECHO_EXPIRY_NANOS)) {
            return@withLock
        }
        subscribed.clear(); invalidate()
        current?.let {
            runCatching { it.disconnectForcibly(0L, 50L) }
            runCatching { it.close(true) }
        }
        client = null
        val next = clientProvider?.invoke(NetworkConfig.BROKER_URL, "emqx7_${identity.take(32)}")
            ?: MqttAsyncClient(NetworkConfig.BROKER_URL, "emqx7_${identity.take(32)}", MemoryPersistence())
        client = next
        next.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                if (client === next) { subscribed.clear(); invalidate() }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (client !== next) return
                receivedCount++
                handlers.forEach { (filter, callbacks) ->
                    if (matches(filter, topic)) callbacks.forEach { handler -> runCatching { handler(message.payload, message.isRetained) } }
                }
            }
        })
        try {
            awaitOperation(connectBudgetMillis()) { listener -> next.connect(MqttConnectOptions().apply {
                isCleanSession = true; connectionTimeout = CONNECT_TIMEOUT_SECONDS; keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS; maxInflight = MAX_INFLIGHT_MESSAGES
                NetworkConfig.applyMqttOptions(this)
            }, null, listener) }
            connectedAt = System.nanoTime()
        } catch (e: Exception) {
            runCatching { next.disconnectForcibly(0L, 50L) }
            runCatching { next.close(true) }
            if (client === next) client = null
            throw e
        }
    }
    private suspend fun ensureSubscription(requestedTopic: String, refresh: Boolean = false) = gate.withLock {
        val topic = subscriptionFilter(requestedTopic)
        if ((!refresh && topic in subscribed) || closed) return@withLock
        val c = client?.takeIf { it.isConnected } ?: throw IOException("EMQX disconnected")
        if (refresh && topic in subscribed) {
            refreshing.add(topic)
            try {
                runCatching {
                    awaitOperation(OPERATION_TIMEOUT_MS) { listener -> c.unsubscribe(topic, null, listener) }
                }
                subscribed.remove(topic)
            } catch (e: Throwable) {
                refreshing.remove(topic)
                throw e
            }
        }
        try {
            val token = awaitOperation(OPERATION_TIMEOUT_MS) { listener -> c.subscribe(topic, 1, null, listener) }
            if (token.grantedQos?.any { it == 128 } == true) throw IOException("EMQX subscription rejected")
            subscribed.add(topic)
        } finally {
            refreshing.remove(topic)
        }
    }
    internal fun hasSubscription(topic: String): Boolean = subscriptionFilter(topic) in subscribed
    suspend fun awaitHealthy(timeoutMs: Long = 8000L): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!isHealthy) delay(20L)
        true
    } ?: false

    override suspend fun subscribe(topic: String, handler: (ByteArray, Boolean) -> Unit): AutoCloseable {
        if (!isTransportUsable) {
            runCatching { connect(force = false) }
        }
        if (!awaitHealthy(3000L) && client?.isConnected != true) throw IOException("EMQX unavailable")
        val list = handlers.computeIfAbsent(topic) { CopyOnWriteArrayList() }
        list.add(handler)
        try {
            ensureSubscription(topic)
        } catch (e: Exception) {
            list.remove(handler)
            if (list.isEmpty()) handlers.remove(topic, list)
            throw e
        }
        return AutoCloseable {
            list.remove(handler)
            if (list.isEmpty() && topic != echoTopic) scope.launch {
                gate.withLock {
                    if (list.isEmpty() && handlers.remove(topic, list)) {
                        val filter = subscriptionFilter(topic)
                        if (handlers.filterValues { it.isNotEmpty() }.keys.none { subscriptionFilter(it) == filter }) {
                            subscribed.remove(filter)
                            runCatching { client?.takeIf { it.isConnected }?.let { c ->
                                awaitOperation(OPERATION_TIMEOUT_MS) { listener -> c.unsubscribe(filter, null, listener) }
                            } }
                        }
                    }
                }
            }
        }
    }
    private suspend fun publishRaw(topic: String, payload: ByteArray, retained: Boolean) {
        val c = client?.takeIf { it.isConnected } ?: throw IOException("EMQX disconnected")
        awaitOperation(OPERATION_TIMEOUT_MS) { listener -> c.publish(topic,
            MqttMessage(payload).apply { qos = 1; isRetained = retained }, null, listener) }
    }
    override suspend fun publish(topic: String, payload: ByteArray, retained: Boolean) {
        if (!isTransportUsable) {
            runCatching { connect(force = false) }
        }
        if (!isTransportUsable) throw IOException("EMQX unavailable")
        try { publishRaw(topic, payload, retained) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { invalidate(); throw e }
    }
    /** MQTT ordering fence on this subscription connection, including retained delivery. */
    override suspend fun fence() {
        val nonce = UUID.randomUUID().toString()
        val reply = CompletableDeferred<Unit>()
        val handle = subscribe(echoTopic) { bytes, _ -> if (bytes.toString(Charsets.UTF_8) == nonce) reply.complete(Unit) }
        try {
            publish(echoTopic, nonce.toByteArray())
            if (withTimeoutOrNull(OPERATION_TIMEOUT_MS) { reply.await(); true } != true) throw IOException("EMQX echo timed out")
        }
        finally { handle.close() }
    }
    /** Refresh the retained snapshot over the existing socket. The nonce fence follows
     * SUBACK on that same connection, so missing storage is distinguishable from a live echo. */
    override suspend fun readRetained(topic: String): ByteArray? {
        val retained = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val handle = subscribe(topic) { bytes, _ ->
            retained.set(bytes.copyOf())
        }
        return try {
            ensureSubscription(topic, refresh = true)
            fence()
            retained.get()
        } finally { handle.close() }
    }
    /** PUBACK alone is not retained-storage proof; require an exact retained readback. */
    override suspend fun confirmRetained(topic: String, payload: ByteArray): Boolean {
        val read = readRetained(topic)
        return if (payload.isEmpty()) {
            read == null || read.isEmpty()
        } else {
            read?.contentEquals(payload) == true
        }
    }

    private suspend fun awaitOperation(timeoutMs: Long, start: (IMqttActionListener) -> IMqttToken): IMqttToken = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(token))
                    }
                    override fun onFailure(token: IMqttToken?, exception: Throwable?) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(exception ?: IOException("EMQX operation failed")))
                    }
                }
                try { start(listener) }
                catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            }
        }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("EMQX operation timed out", e)
    }
    internal fun interruptSocket() {
        invalidate(); subscribed.clear(); refreshing.clear()
        client?.let { runCatching { it.disconnectForcibly(0L, 50L) } }
    }
    internal fun simulateZombieStall() {
        lastEcho = 1L
        _healthy.value = false
        _rtt.value = 0L
        probes.clear()
    }
    override fun close() {
        closed = true; scope.cancel(); invalidate(); handlers.clear(); subscribed.clear(); refreshing.clear()
        client?.let { runCatching { it.disconnectForcibly(0L, 50L) }; runCatching { it.close(true) } }; client = null
    }
}

object EmqxTransport {
    @Volatile internal var wireOverride: EmqxWire? = null
    val connection: EmqxConnection by lazy { EmqxConnection() }
    val wire: EmqxWire get() = wireOverride ?: connection
}
