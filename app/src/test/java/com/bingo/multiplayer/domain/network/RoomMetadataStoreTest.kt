package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RoomMetadataStoreTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val host = Player(id = "host", displayName = "Kevin", isHost = true)
    private val guest = Player(id = "guest", displayName = "Stuart")
    private fun room() = OnlineRoomSession("ABC123", host.id, "kevin", "Kevin",
        players = listOf(host, guest), revision = 1L, metadataProtocolVersion = 3)

    // The fixture supplies atomic storage behavior; Android production HTTP/serialization,
    // conflict handling and transforms run unchanged. Worker tests execute the real server CAS.
    private inner class StoreFixture(initial: OnlineRoomSession) {
        var value = initial
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldOnce = AtomicBoolean(false)
        val loseNextWriteReply = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            if (request.method == "POST" && request.header("Authorization") == "Bearer guest" && heldOnce.compareAndSet(false, true)) {
                arrived.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release held write" }
            }
            val result = synchronized(this) {
                var status = 200
                if (body != null) {
                    val input = json.parseToJsonElement(body).jsonObject
                    val expectedRevision = input.getValue("expectedRevision").jsonPrimitive.long
                    val expectedSeed = input.getValue("expectedSeed").jsonPrimitive.contentOrNull
                    if (expectedRevision != value.revision || expectedSeed != value.currentSeed.toString()) status = 409
                    else value = json.decodeFromString<OnlineRoomSession>(input.getValue("payload").jsonPrimitive.content)
                }
                status to buildJsonObject {
                    put("payload", json.encodeToString(value)); put("revision", value.revision)
                    put("closed", value.status == "CLOSED")
                }.toString()
            }
            if (request.method == "POST" && loseNextWriteReply.compareAndSet(true, false))
                throw java.io.IOException("Committed response lost in transit")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(result.first)
                .message("Controlled room storage").body(result.second.toResponseBody()).build()
        }.build()
        fun coordinator(actor: String) = RoomMetadataCoordinator(HttpRoomMetadataTransport(client, "https://rooms.invalid") { actor })
    }

    @Test
    fun delayedIndependentReadinessWritePreservesCompleteCommittedStart() = runBlocking {
        val fixture = StoreFixture(room())
        val guestWrite = async(Dispatchers.IO) { fixture.coordinator("guest").mutate("ABC123") { current ->
            current.copy(players = current.players.map { if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY") else it })
        } }
        assertTrue(fixture.arrived.await(5, TimeUnit.SECONDS))
        try {
            val clock = MultiplayerTurnClock.start(1, guest.id, 10000L)
            val canonical = "{\"seed\":9223372036854775806,\"type\":\"START_GAME\"}"
            val started = fixture.coordinator("host").mutate("ABC123") { it.copy(status = "PLAYING",
                currentSeed = Long.MAX_VALUE - 1, currentTurnPlayerId = guest.id,
                turnClock = clock, canonicalStartPacketJson = canonical, startCommitDeadline = 10000L) }
            assertTrue(started is MutationResult.Applied)
            fixture.release.countDown()
            assertTrue(guestWrite.await() is MutationResult.Applied)
            assertEquals("PLAYING", fixture.value.status)
            assertEquals(Long.MAX_VALUE - 1, fixture.value.currentSeed)
            assertEquals(clock, fixture.value.turnClock)
            assertEquals(canonical, fixture.value.canonicalStartPacketJson)
            assertEquals(guest.id, fixture.value.currentTurnPlayerId)
            assertEquals(10000L, fixture.value.startCommitDeadline)
            assertEquals(listOf(host.id, guest.id), fixture.value.players.map { it.id })
            assertEquals("READY", fixture.value.players.last().lobbyReadyStatus)
        } finally { fixture.release.countDown(); guestWrite.cancelAndJoin() }
    }

    @Test
    fun delayedIndependentJoinCannotReopenClosedRoom() = runBlocking {
        val fixture = StoreFixture(room())
        val join = async(Dispatchers.IO) { fixture.coordinator("guest").mutate("ABC123") { it.copy(lastHeartbeat = 123L) } }
        assertTrue(fixture.arrived.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(fixture.coordinator("host").mutate("ABC123") { it.copy(status = "CLOSED") } is MutationResult.Applied)
            fixture.release.countDown()
            assertTrue(join.await() is MutationResult.Rejected)
            assertEquals("CLOSED", fixture.value.status)
            assertTrue(fixture.value.isTerminalClosed)
        } finally { fixture.release.countDown(); join.cancelAndJoin() }
    }

    @Test
    fun obsoleteGenerationCloseCannotMutateCurrentRematch() = runBlocking {
        val fixture = StoreFixture(room().copy(status = "PLAYING", currentSeed = 200L))
        val result = fixture.coordinator("host").mutate("ABC123", expectedSeed = 100L) { it.copy(status = "CLOSED") }
        assertTrue(result is MutationResult.Rejected)
        assertEquals("PLAYING", fixture.value.status)
        assertEquals(200L, fixture.value.currentSeed)
        assertEquals(1L, fixture.value.revision)
    }

    @Test
    fun lostCommitReplyIsReconciledWithoutStartingAnotherMatch() = runBlocking {
        val fixture = StoreFixture(room())
        fixture.loseNextWriteReply.set(true)
        val clock = MultiplayerTurnClock.start(1, guest.id, 20000L)
        val canonical = "{\"type\":\"START_GAME\",\"seed\":123}"
        val result = fixture.coordinator("host").mutate("ABC123", expectedSeed = 0L) {
            it.copy(status = "PLAYING", currentSeed = 123L, turnClock = clock, canonicalStartPacketJson = canonical)
        }
        assertTrue(result is MutationResult.Applied)
        assertEquals(2L, fixture.value.revision)
        assertEquals(123L, fixture.value.currentSeed)
        assertEquals(clock, (result as MutationResult.Applied).session.turnClock)
        assertEquals(canonical, result.session.canonicalStartPacketJson)
    }

    @Test
    fun unavailableStorageNeverPublishesAnUnconditionalWrite() = runBlocking {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(methods) { methods += chain.request().method }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503)
                .message("Unavailable").body("{\"error\":\"Unavailable\"}".toResponseBody()).build()
        }.build()
        val coordinator = RoomMetadataCoordinator(HttpRoomMetadataTransport(client, "https://rooms.invalid") { "host" })
        assertTrue(coordinator.mutate("ABC123") { it.copy(status = "CLOSED") } is MutationResult.Unavailable)
        assertEquals(listOf("GET"), methods)
    }

    @Test
    fun cancelledMetadataReadCancelsTheActualHttpCall() = runBlocking {
        val arrived = CountDownLatch(1)
        val callRef = AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            callRef.set(chain.call()); arrived.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw java.io.IOException("Cancelled by production continuation")
        }.build()
        val transport = HttpRoomMetadataTransport(client, "https://rooms.invalid") { "host" }
        val job = launch(Dispatchers.IO) { transport.read("ABC123") }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(callRef.get().isCanceled())
    }
}
