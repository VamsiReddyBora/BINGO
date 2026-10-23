package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Active rooms answer lookup and mutation requests directly over MQTT. Knowing the room code
 * is sufficient for lobby admission; the host still validates roster and gameplay transitions. */
internal class MqttRoomMetadataTransport(
    private val connection: EmqxWire,
    private val actor: () -> String,
    private val observe: (OnlineRoomSession) -> Unit = {},
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMs: Long = 9000L,
    private val discoveryTimeoutMs: Long = 2000L
) : RoomMetadataTransport, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rooms = ConcurrentHashMap<String, OnlineRoomSession>()
    private val subscriptions = ConcurrentHashMap<String, AutoCloseable>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val retiredWriters = ConcurrentHashMap.newKeySet<String>()
    private fun writerKey(code: String, seed: Long, uid: String) = "$code/$seed/$uid"
    private fun isRetired(room: OnlineRoomSession, uid: String) = writerKey(room.roomCode, room.currentSeed, uid) in retiredWriters
    fun retireLocalWriter(code: String, seed: Long, uid: String) {
        retiredWriters.add(writerKey(code.trim().uppercase(), seed, uid))
    }
    suspend fun drainRetiredWriter(code: String) { locks[code.trim().uppercase()]?.withLock { } }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
    private val watching = Mutex()
    private val responseTopic = "bingo/emqx7/metadata/reply/${UUID.randomUUID()}"
    private var responses: AutoCloseable? = null
    private data class Execution(val proposal: Proposal, val reply: CompletableDeferred<Reply>, val createdAt: Long)
    private val executions = ConcurrentHashMap<String, Execution>()
    @Volatile internal var lastReplyError: String? = null

    // Optional legacy fields remain decodable so an old cached packet cannot break lookup.
    @Serializable internal data class Proposal(
        val id: String,
        val actor: String,
        val reply: String,
        val sentAt: Long,
        val base: OnlineRoomSession?,
        val next: OnlineRoomSession,
        val challenge: String = "",
        val actorKey: String? = null,
        val actorSig: String? = null
    )
    @Serializable private data class Lookup(val id: String, val reply: String, val kind: String = "lookup")
    @Serializable internal data class Reply(
        val id: String,
        val status: String,
        val room: OnlineRoomSession? = null,
        val reason: String = "",
        val hostKey: String? = null,
        val hostSig: String? = null
    )
    private fun writer(room: OnlineRoomSession): String =
        OnlineRoomSyncManager.activeInstance?.metadataWriterFor(room) ?: room.hostId
    private fun state(code: String) = "bingo/emqx7/metadata/$code/state"
    private fun auth(code: String) = "bingo/emqx7/metadata/$code/auth"
    private fun commands(code: String) = "bingo/emqx7/metadata/$code/command"
    private fun committed(code: String) = "bingo/emqx7/metadata/$code/committed"
    private fun validRecord(code: String, room: OnlineRoomSession): Boolean =
        room.roomCode == code && room.metadataProtocolVersion == 7 &&
            room.sessionId.isNotBlank() && room.hostId.isNotBlank()

    private fun remember(code: String, bytes: ByteArray): OnlineRoomSession? {
        val room = runCatching { json.decodeFromString<OnlineRoomSession>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { validRecord(code, it) } ?: return null
        return rooms.compute(code) { _, old ->
            if (old != null) {
                val local = actor()
                // The current host owns its accepted roster, even if an obsolete cached packet arrives.
                if (local.isNotBlank() && writer(old) == local && !isRetired(old, local) && room != old) return@compute old
                if (old.hostId != room.hostId && OnlineRoomSyncManager.activeInstance?.isPromotedSuccessor(room, old.hostId) != true) return@compute old
                if (old.createdAt > room.createdAt ||
                    (old.sessionId == room.sessionId && (old.revision > room.revision || old.isTerminalClosed))) return@compute old
                if (old.sessionId == room.sessionId && old.status == "PLAYING" && room.status == "WAITING" &&
                    !(room.revision > old.revision && room.currentSeed == 0L && old.currentSeed != 0L)) return@compute old
                if (old.sessionId == room.sessionId && old.status == "PLAYING" && room.status == "PLAYING" &&
                    old.currentSeed == room.currentSeed && room.players.any { p -> old.players.none { it.id == p.id } }) return@compute old
            }
            room.also(observe)
        }
    }

    private suspend fun sendReply(topic: String, reply: Reply) {
        val bytes = json.encodeToString(reply).toByteArray()
        for (attempt in 0..2) {
            try {
                connection.publish(topic, bytes)
                lastReplyError = null
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastReplyError = "${e.javaClass.simpleName}: ${e.message}"
                if (attempt < 2) delay(50L * (attempt + 1))
            }
        }
    }

    private suspend fun watch(code: String) = watching.withLock {
        val localActor = actor()
        val activeCode = OnlineRoomSyncManager.activeInstance?.currentRoomCode
        subscriptions.entries.toList().forEach { (previousCode, handle) ->
            val previous = rooms[previousCode]
            if (previousCode != code && previousCode != activeCode &&
                (previous == null || previous.isTerminalClosed || previous.status == "CLOSED" || writer(previous) != localActor)) {
                if (subscriptions.remove(previousCode, handle)) handle.close()
            }
        }
        if (responses == null) responses = connection.subscribe(responseTopic) { bytes, _ ->
            runCatching { json.decodeFromString<Reply>(bytes.toString(Charsets.UTF_8)) }.getOrNull()?.let { pending[it.id]?.complete(it) }
        }
        if (subscriptions.containsKey(code)) return@withLock
        val opened = mutableListOf<AutoCloseable>()
        try {
        opened += connection.subscribe(state(code)) { bytes, retained ->
            // Waiting-room updates are live messages, not a broker-storage transaction.
            val waiting = runCatching { json.decodeFromString<OnlineRoomSession>(bytes.toString(Charsets.UTF_8)).status == "WAITING" }.getOrDefault(false)
            if (retained || waiting) remember(code, bytes)
        }
        opened += connection.subscribe(committed(code)) { bytes, retained ->
            if (!retained) scope.launch {
                val room = runCatching { json.decodeFromString<OnlineRoomSession>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                if (room?.status == "WAITING" || runCatching { connection.confirmRetained(state(code), bytes) }.getOrDefault(false)) remember(code, bytes)
            }
        }
        opened += connection.subscribe(commands(code)) { bytes, retained ->
            if (!retained) scope.launch {
                val envelope = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return@launch
                val local = actor()
                val current = rooms[code] ?: return@launch
                if (local.isBlank() || writer(current) != local || isRetired(current, local)) return@launch
                val replyTopic = envelope["reply"]?.jsonPrimitive?.content ?: return@launch
                if (!replyTopic.startsWith("bingo/emqx7/metadata/reply/")) return@launch
                val reply = if (envelope["kind"]?.jsonPrimitive?.content == "lookup") {
                    val id = envelope["id"]?.jsonPrimitive?.content ?: return@launch
                    Reply(id, "snapshot", current)
                } else {
                    val proposal = runCatching { json.decodeFromString<Proposal>(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return@launch
                    answerProposal(code, proposal, local)
                }
                sendReply(replyTopic, reply)
            }
        }
        connection.fence()
        subscriptions[code] = AutoCloseable { opened.forEach { it.close() } }
        } catch (e: Exception) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
    }

    private suspend fun answerProposal(code: String, proposal: Proposal, local: String): Reply {
        if (proposal.actor == local) return Reply(proposal.id, "rejected", reason = "Host mutations must originate locally")
        if (proposal.id.isBlank() || proposal.actor.isBlank()) return Reply(proposal.id, "rejected", reason = "Missing identity")
        val now = System.nanoTime()
        executions.entries.removeIf { it.value.reply.isCompleted && now - it.value.createdAt > 30_000_000_000L }
        val execution = Execution(proposal, CompletableDeferred(), now)
        val previous = executions.putIfAbsent(proposal.id, execution)
        if (previous != null) return if (previous.proposal == proposal) previous.reply.await()
            else Reply(proposal.id, "rejected", reason = "Request identity reused")
        try {
            val reply = when (val result = apply(code, proposal.actor, proposal.base, proposal.next)) {
                is RoomMetadataCommit.Applied -> Reply(proposal.id, "applied", result.room)
                is RoomMetadataCommit.Conflict -> Reply(proposal.id, "conflict", result.room)
                is RoomMetadataCommit.Rejected -> Reply(proposal.id, "rejected", reason = result.reason)
                is RoomMetadataCommit.Unavailable -> Reply(proposal.id, "unavailable", reason = result.error.message.orEmpty())
            }
            execution.reply.complete(reply)
            return reply
        } catch (e: CancellationException) {
            execution.reply.cancel(e)
            executions.remove(proposal.id, execution)
            throw e
        } catch (e: Exception) {
            val reply = Reply(proposal.id, "unavailable", reason = e.message.orEmpty())
            execution.reply.complete(reply)
            return reply
        }
    }

    private suspend fun liveSnapshot(code: String): OnlineRoomSession? {
        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<Reply>()
        pending[id] = waiter
        return try {
            connection.publish(commands(code), json.encodeToString(Lookup(id, responseTopic)).toByteArray())
            val reply = withTimeoutOrNull(discoveryTimeoutMs) { waiter.await() }
            reply?.room?.takeIf { reply.status == "snapshot" && validRecord(code, it) }
        } finally { pending.remove(id) }
    }

    suspend fun readCandidate(code: String): OnlineRoomSession? {
        val bytes = connection.readRetained(state(code)) ?: return null
        return runCatching { json.decodeFromString<OnlineRoomSession>(bytes.toString(Charsets.UTF_8)) }
            .getOrNull()?.takeIf { validRecord(code, it) }
    }

    override suspend fun read(code: String): Result<OnlineRoomSession?> = try {
        watch(code)
        val local = actor()
        val owned = rooms[code]
        if (owned != null && writer(owned) == local && !isRetired(owned, local)) {
            Result.success(owned)
        } else {
            val live = liveSnapshot(code)
            if (live != null) {
                Result.success(remember(code, json.encodeToString(live).toByteArray()))
            } else {
                // Compatibility/cold recovery only. An active host never waits for these readbacks.
                val a = connection.readRetained(state(code))?.let { runCatching { json.decodeFromString<OnlineRoomSession>(it.toString(Charsets.UTF_8)) }.getOrNull() }?.takeIf { validRecord(code, it) }
                val b = connection.readRetained(auth(code))?.let { runCatching { json.decodeFromString<OnlineRoomSession>(it.toString(Charsets.UTF_8)) }.getOrNull() }?.takeIf { validRecord(code, it) }
                val effective = if (a != null && b != null && a.sessionId == b.sessionId &&
                    ((a.status == "CLOSED" || a.isTerminalClosed) != (b.status == "CLOSED" || b.isTerminalClosed))) {
                    val closed = if (a.status == "CLOSED" || a.isTerminalClosed) a else b
                    val open = if (closed === a) b else a
                    if (closed.revision <= open.revision) throw IOException("Inconsistent replica closure between state and auth")
                    closed
                } else listOfNotNull(a, b).maxWithOrNull(compareBy<OnlineRoomSession> { it.createdAt }.thenBy { it.revision })
                if (effective == null) Result.success(null)
                else Result.success(remember(code, json.encodeToString(effective).toByteArray()))
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
        return try {
        watch(code)
        val uid = actor().takeIf { it.isNotBlank() } ?: return RoomMetadataCommit.Rejected("Sign in before updating a room")
        if (base != null && isRetired(base, uid)) return RoomMetadataCommit.Rejected("Writer intentionally exited this match")
        if ((base?.let(::writer) ?: next.hostId) == uid) {
            apply(code, uid, base, next)
        } else {
            val id = UUID.randomUUID().toString()
            val waiter = CompletableDeferred<Reply>()
            pending[id] = waiter
            try {
                val proposal = Proposal(id, uid, responseTopic, wallNow(), base, next)
                val bytes = json.encodeToString(proposal).toByteArray()
                val reply = withTimeoutOrNull(requestTimeoutMs) {
                    repeat(3) {
                        connection.publish(commands(code), bytes)
                        val response = withTimeoutOrNull((requestTimeoutMs / 3).coerceAtLeast(1L)) { waiter.await() }
                        if (response != null) return@withTimeoutOrNull response
                    }
                    null
                } ?: return RoomMetadataCommit.Unavailable(IOException("No response from the room host"))
                if (actor() != uid) return RoomMetadataCommit.Rejected("Account changed")
                when (reply.status) {
                    "applied" -> {
                        val room = reply.room
                        if (room == null || !validRecord(code, room) || room.hostId != (base?.hostId ?: next.hostId) ||
                            (base != null && (room.sessionId != base.sessionId || room.revision <= base.revision))) {
                            RoomMetadataCommit.Unavailable(IOException("The room changed while joining"))
                        } else {
                            remember(code, json.encodeToString(room).toByteArray())
                            RoomMetadataCommit.Applied(room)
                        }
                    }
                    "conflict" -> RoomMetadataCommit.Conflict(reply.room?.takeIf { validRecord(code, it) })
                    "rejected" -> RoomMetadataCommit.Rejected(reply.reason)
                    else -> RoomMetadataCommit.Unavailable(IOException(reply.reason.ifBlank { "No response from the room host" }))
                }
            } finally { pending.remove(id) }
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { RoomMetadataCommit.Unavailable(e) }
    }

    private suspend fun apply(code: String, uid: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit =
        locks.computeIfAbsent(code) { Mutex() }.withLock {
            val current = rooms[code]
            if (current?.revision != base?.revision || current?.sessionId != base?.sessionId || current?.currentSeed != base?.currentSeed) return@withLock RoomMetadataCommit.Conflict(current)
            val currentWriter = current?.let(::writer) ?: next.hostId
            if (actor() != currentWriter || (current != null && isRetired(current, currentWriter))) return@withLock RoomMetadataCommit.Rejected("Gameplay metadata authority changed")
            if (current != null && currentWriter != current.hostId && next.hostId != currentWriter) return@withLock RoomMetadataCommit.Conflict(current)
            val reason = MqttRoomAuthority.reject(uid, current?.copy(hostId = currentWriter), next)
            if (reason != null) return@withLock RoomMetadataCommit.Rejected(reason)
            val accepted = next.copy(lastHeartbeat = if (current != null && uid != current.hostId) current.lastHeartbeat else next.lastHeartbeat,
                metadataProtocolVersion = 7, revision = (current?.revision ?: 0L) + 1L,
                sessionId = current?.sessionId ?: next.sessionId.ifBlank { UUID.randomUUID().toString() },
                publisherKey = null, publisherSignature = null)
            try {
                val payload = json.encodeToString(accepted).toByteArray()
                if (accepted.status == "WAITING" && !accepted.isTerminalClosed) {
                    // A single retained advertisement is a cache, not a storage transaction.
                    // If the broker cannot retain it, live delivery and host lookup still work.
                    try { connection.publish(state(code), payload, retained = true) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { connection.publish(state(code), payload) }
                    rooms[code] = accepted
                    observe(accepted)
                    scope.launch { runCatching { connection.publish(committed(code), payload) } }
                } else {
                    // Preserve existing running-match/terminal recovery storage in this targeted lobby fix.
                    for (topic in listOf(state(code), auth(code))) {
                        try { connection.publish(topic, payload, retained = true) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (!connection.confirmRetained(topic, payload)) return@withLock RoomMetadataCommit.Unavailable(e)
                        }
                        if (!connection.confirmRetained(topic, payload)) return@withLock RoomMetadataCommit.Unavailable(IOException("Room recovery snapshot unavailable"))
                    }
                    rooms[code] = accepted
                    observe(accepted)
                    scope.launch { runCatching { connection.publish(committed(code), payload) } }
                }
                RoomMetadataCommit.Applied(accepted)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { RoomMetadataCommit.Unavailable(e) }
        }

    override fun close() {
        val job = scope.coroutineContext[Job]
        job?.cancel()
        runCatching { runBlocking { withTimeoutOrNull(2000L) { job?.join() } } }
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
        responses?.close()
        responses = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        executions.values.forEach { it.reply.cancel() }
        executions.clear()
        rooms.clear()
        retiredWriters.clear()
    }
}

internal object AccountKeyStore {
    private val keyPairs = ConcurrentHashMap<String, java.security.KeyPair>()
    private val hostBindings = ConcurrentHashMap<String, String>()
    private val keyFactory by lazy { java.security.KeyFactory.getInstance("RSA") }
    private val keyGen by lazy {
        java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
    }
    internal var keyDirOverride: java.io.File? = null
    internal val userKeyDirs = ConcurrentHashMap<String, java.io.File>()

    fun keyDirFor(uid: String? = null): java.io.File =
        (if (!uid.isNullOrBlank()) userKeyDirs[uid] else null) ?: keyDirOverride ?: run {
            val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "bingo_keystore")
            dir.mkdirs()
            dir
        }

    private fun remoteHostsFileFor(local: String? = null): java.io.File =
        java.io.File(keyDirFor(local), "remote_hosts.dat")

    internal val authoritativeKeys = ConcurrentHashMap<String, String>()

    fun recordAuthoritativeKey(uid: String, key: String) {
        if (uid.isNotBlank() && key.isNotBlank()) {
            authoritativeKeys[uid] = key
        }
    }

    fun getAuthoritativeKey(uid: String): String? = authoritativeKeys[uid]

    fun clearCachesForTests() {
        keyPairs.clear()
        hostBindings.clear()
        authoritativeKeys.clear()
        accountKeyResolver = null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun computeAuthPayload(code: String, record: OnlineRoomSession): String {
        val clean = record.copy(publisherKey = null, publisherSignature = null)
        return "$code|" + json.encodeToString(clean)
    }

    @Synchronized
    private fun loadRemoteBindings(local: String? = null) {
        val file = remoteHostsFileFor(local)
        if (file.exists()) {
            runCatching {
                file.forEachLine { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0 && idx < line.length - 1) {
                        val uid = line.substring(0, idx)
                        val key = line.substring(idx + 1)
                        if (uid.isNotBlank() && key.isNotBlank()) {
                            if (!local.isNullOrBlank()) {
                                hostBindings.putIfAbsent("$local:$uid", key)
                            }
                            hostBindings.putIfAbsent(uid, key)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun persistRemoteBinding(uid: String, key: String, local: String? = null) {
        runCatching {
            val dir = keyDirFor(local)
            dir.mkdirs()
            val file = remoteHostsFileFor(local)
            val existingLines = if (file.exists()) file.readLines() else emptyList()
            if (existingLines.none { it.startsWith("$uid:") }) {
                file.appendText("$uid:$key\n")
            }
        }
    }

    fun getEstablishedPublicKey(uid: String, local: String? = null): String? {
        val cacheKey = if (!local.isNullOrBlank()) "$local:$uid" else uid
        val bound = if (!local.isNullOrBlank() && userKeyDirs.containsKey(local)) {
            hostBindings[cacheKey]
        } else {
            hostBindings[cacheKey] ?: hostBindings[uid]
        }
        if (bound != null) return bound
        loadRemoteBindings(local)
        return if (!local.isNullOrBlank() && userKeyDirs.containsKey(local)) {
            hostBindings[cacheKey]
        } else {
            hostBindings[cacheKey] ?: hostBindings[uid]
        }
    }

    fun bindHostKey(uid: String, key: String, local: String? = null): Boolean {
        if (uid.isBlank() || key.isBlank()) return false
        val cacheKey = if (!local.isNullOrBlank()) "$local:$uid" else uid
        val existing = if (!local.isNullOrBlank() && userKeyDirs.containsKey(local)) {
            hostBindings[cacheKey] ?: getEstablishedPublicKey(uid, local)
        } else {
            hostBindings[cacheKey] ?: hostBindings[uid] ?: getEstablishedPublicKey(uid, local)
        }
        if (existing != null && existing != key) {
            return false
        }
        hostBindings[cacheKey] = key
        hostBindings[uid] = key
        persistRemoteBinding(uid, key, local)
        return true
    }

    fun isHostKeyValid(uid: String, key: String, local: String? = null): Boolean {
        if (uid.isBlank() || key.isBlank()) return false
        if (!local.isNullOrBlank() && local == uid) {
            return getPublicKeyBase64(uid) == key
        }
        val cacheKey = if (!local.isNullOrBlank()) "$local:$uid" else uid
        val bound = if (!local.isNullOrBlank() && userKeyDirs.containsKey(local)) {
            hostBindings[cacheKey]
        } else {
            hostBindings[cacheKey] ?: hostBindings[uid]
        }
        if (bound != null) {
            return bound == key
        }
        val established = getEstablishedPublicKey(uid, local)
        if (established != null) {
            return established == key
        }
        return false
    }

    sealed interface AccountKeyEvidence {
        data class Verified(val publicKey: String) : AccountKeyEvidence
        object Absent : AccountKeyEvidence
        data class Unavailable(val cause: Throwable) : AccountKeyEvidence
    }

    var accountKeyResolver: ((uid: String) -> AccountKeyEvidence)? = null

    fun guestUidForPublicKey(publicKeyBase64: String): String {
        val pubBytes = publicKeyBase64.decodeBase64()?.toByteArray() ?: ByteArray(0)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pubBytes).joinToString("") { "%02x".format(it) }
        return "guest_$digest"
    }

    fun registerKeyPair(uid: String, keyPair: java.security.KeyPair) {
        keyPairs[uid] = keyPair
        val safeUid = uid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = java.io.File(keyDirFor(uid), "key_$safeUid.dat")
        val privBase64 = keyPair.private.encoded.toByteString().base64()
        val pubBase64 = keyPair.public.encoded.toByteString().base64()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("$privBase64\n$pubBase64")
        }
        hostBindings["$uid:$uid"] = pubBase64
        hostBindings[uid] = pubBase64
    }

    fun createGuestKeyPair(dir: java.io.File? = null): Pair<String, String> {
        val ecGen = try {
            java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
        } catch (_: Exception) { keyGen }
        val kp = ecGen.generateKeyPair()
        val pubBase64 = kp.public.encoded.toByteString().base64()
        val guestUid = guestUidForPublicKey(pubBase64)
        if (dir != null) {
            userKeyDirs[guestUid] = dir
        }
        registerKeyPair(guestUid, kp)
        return Pair(guestUid, pubBase64)
    }

    fun decodeBase64Safe(str: String): ByteArray? {
        val standard = str.decodeBase64()?.toByteArray()
        if (standard != null) return standard
        val normalized = str.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return padded.decodeBase64()?.toByteArray()
    }

    fun verifyGuestIdentity(uid: String, publicKeyBase64: String): Boolean {
        if (!uid.startsWith("guest_")) return false
        val hex = uid.removePrefix("guest_")
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        val pubBytes = decodeBase64Safe(publicKeyBase64) ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pubBytes).joinToString("") { "%02x".format(it) }
        return hex.equals(digest, ignoreCase = true)
    }

    fun getAccountKeyEvidence(uid: String): AccountKeyEvidence {
        if (uid.isBlank()) return AccountKeyEvidence.Absent
        val resolved = accountKeyResolver?.invoke(uid)
        if (resolved != null) return resolved
        val cached = authoritativeKeys[uid]
        if (cached != null) return AccountKeyEvidence.Verified(cached)
        return AccountKeyEvidence.Absent
    }

    @Synchronized
    fun getOrCreateKey(uid: String): java.security.KeyPair {
        keyPairs[uid]?.let { return it }
        val guestMatch = runCatching {
            val nUid = com.bingo.multiplayer.domain.notifications.NotificationIdentity.guestUid()
            if (uid == nUid) com.bingo.multiplayer.domain.notifications.NotificationIdentity.getKeyPair() else null
        }.getOrNull()
        if (guestMatch != null) {
            keyPairs[uid] = guestMatch
            val pubBase64 = guestMatch.public.encoded.toByteString().base64()
            hostBindings["$uid:$uid"] = pubBase64
            hostBindings[uid] = pubBase64
            return guestMatch
        }

        val safeUid = uid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = java.io.File(keyDirFor(uid), "key_$safeUid.dat")
        if (file.exists()) {
            try {
                val lines = file.readLines()
                if (lines.size >= 2) {
                    val privBytes = lines[0].decodeBase64()?.toByteArray()
                    val pubBytes = lines[1].decodeBase64()?.toByteArray()
                    if (privBytes != null && pubBytes != null) {
                        val privKey = try {
                            keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                        } catch (_: Exception) {
                            java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                        }
                        val pubKey = try {
                            keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
                        } catch (_: Exception) {
                            java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
                        }
                        val kp = java.security.KeyPair(pubKey, privKey)
                        keyPairs[uid] = kp
                        hostBindings["$uid:$uid"] = lines[1]
                        hostBindings[uid] = lines[1]
                        return kp
                    }
                }
            } catch (_: Exception) {}
        }
        val kp = if (uid.startsWith("guest_")) {
            val ecGen = try {
                java.security.KeyPairGenerator.getInstance("EC").apply {
                    initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                }
            } catch (_: Exception) { keyGen }
            ecGen.generateKeyPair()
        } else {
            keyGen.generateKeyPair()
        }
        keyPairs[uid] = kp
        try {
            val privBase64 = kp.private.encoded.toByteString().base64()
            val pubBase64 = kp.public.encoded.toByteString().base64()
            file.writeText("$privBase64\n$pubBase64")
            hostBindings["$uid:$uid"] = pubBase64
            hostBindings[uid] = pubBase64
        } catch (_: Exception) {}
        return kp
    }

    fun getAccountPublicKey(uid: String): String? {
        when (val ev = getAccountKeyEvidence(uid)) {
            is AccountKeyEvidence.Verified -> return ev.publicKey
            else -> return null
        }
    }

    fun sign(uid: String, data: String): String {
        val kp = getOrCreateKey(uid)
        val algorithm = if (kp.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val sig = java.security.Signature.getInstance(algorithm)
        sig.initSign(kp.private)
        sig.update(data.toByteArray(Charsets.UTF_8))
        return sig.sign().toByteString().base64()
    }

    fun verify(publicKeyBase64: String, data: String, signatureBase64: String): Boolean {
        return try {
            val pubBytes = publicKeyBase64.decodeBase64()?.toByteArray() ?: return false
            val pubKey = try {
                keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
            } catch (_: Exception) {
                java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
            }
            val algorithm = if (pubKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val sig = java.security.Signature.getInstance(algorithm)
            sig.initVerify(pubKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            val sigBytes = signatureBase64.decodeBase64()?.toByteArray() ?: return false
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun getPublicKeyBase64(uid: String): String {
        val kp = getOrCreateKey(uid)
        return kp.public.encoded.toByteString().base64()
    }

    fun resetForTests() {
        keyPairs.clear()
        hostBindings.clear()
        userKeyDirs.clear()
        accountKeyResolver = null
        try { keyDirFor().deleteRecursively(); keyDirFor().mkdirs() } catch (_: Exception) {}
    }
}

internal object MqttRoomAuthority {
    fun reject(actor: String, base: OnlineRoomSession?, next: OnlineRoomSession): String? {
        if (actor.isBlank() || next.roomCode.isBlank()) return "Missing identity"
        if (base == null) return if (next.hostId == actor && next.status == "WAITING" &&
            next.players.size == 1 && next.players.single().id == actor) null else "Only the host can create a lobby"
        if (base.isTerminalClosed || base.status == "CLOSED") return "Room is closed"
        if (next.sessionId != base.sessionId || next.createdAt != base.createdAt) return "Obsolete session"
        if (actor == base.hostId) {
            if (next.status == "PLAYING" && base.status == "PLAYING" && next.currentSeed == base.currentSeed &&
                next.players.any { p -> base.players.none { it.id == p.id } }) return "Cannot add a running participant"
            return null
        }
        // A guest can change only its own presence/readiness, join a waiting lobby, or leave.
        // Compare every other field exactly; host time/roster cannot be supplied by a guest.
        val oldSelf = base.players.firstOrNull { it.id == actor }
        val newSelf = next.players.firstOrNull { it.id == actor }
        if (newSelf?.isHost == true || (oldSelf == null && (base.status != "WAITING" || base.players.size >= 8))) return "Join rejected"
        if (newSelf != null && oldSelf != null && newSelf.readyVersion < oldSelf.readyVersion) return "Stale readiness"
        if (next.players.map { it.id }.distinct().size != next.players.size) return "Duplicate roster"
        val normalized = next.copy(players = base.players, lastHeartbeat = base.lastHeartbeat,
            revision = base.revision, metadataProtocolVersion = base.metadataProtocolVersion)
        if (normalized != base) return "Guest cannot change host metadata"
        val others = next.players.filter { it.id != actor }
        if (others != base.players.filter { it.id != actor })
            return "Guest cannot change other participants"
        return null
    }
}
