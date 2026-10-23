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

    // Controlled EMQX wire, actual production serialization/host writer/conflict coordinator.
    // Test method names and gameplay/state assertions are retained across transport migration.
    private inner class StoreFixture(initial: OnlineRoomSession) {
        val wire = EmqxWireFixture()
        val value: OnlineRoomSession get() = json.decodeFromString(wire.retained.getValue("bingo/emqx7/metadata/ABC123/state").toString(Charsets.UTF_8))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldOnce = AtomicBoolean(false)
        val loseNextWriteReply = AtomicBoolean(false)
        private val hostStore = MqttRoomMetadataTransport(wire, { host.id })
        private val guestStore = MqttRoomMetadataTransport(wire, { guest.id })
        init {
            val boundKey = AccountKeyStore.getPublicKeyBase64(host.id)
            AccountKeyStore.bindHostKey(host.id, boundKey)
            val unsigned = initial.copy(metadataProtocolVersion = 7, sessionId = "fixture-session", publisherKey = boundKey, publisherSignature = null)
            val authPayload = AccountKeyStore.computeAuthPayload("ABC123", unsigned)
            val sig = AccountKeyStore.sign(host.id, authPayload)
            val record = unsigned.copy(publisherSignature = sig)
            val payloadBytes = json.encodeToString(record).toByteArray()
            wire.retained["bingo/emqx7/metadata/ABC123/state"] = payloadBytes
            wire.retained["bingo/emqx7/metadata/ABC123/auth"] = payloadBytes
            wire.beforePublish = { topic, _ ->
                if (topic.endsWith("/command") && heldOnce.compareAndSet(false, true)) {
                    arrived.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Test did not release held guest proposal" }
                }
            }
            wire.afterPublish = { topic, _ ->
                if (topic.endsWith("/state") && loseNextWriteReply.compareAndSet(true, false))
                    throw java.io.IOException("Committed PUBACK lost in transit")
            }
            runBlocking { hostStore.read("ABC123") }
        }
        fun coordinator(actor: String) = RoomMetadataCoordinator(if (actor == "host") hostStore else guestStore)
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
        val wire = EmqxWireFixture().also { it.isHealthy = false }
        val coordinator = RoomMetadataCoordinator(MqttRoomMetadataTransport(wire, { host.id }))
        assertTrue(coordinator.mutate("ABC123") { it.copy(status = "CLOSED") } is MutationResult.Unavailable)
        assertTrue(wire.published.isEmpty())
    }

    @Test
    fun cancelledMetadataReadCancelsTheActualHttpCall() = runBlocking {
        // Historical name retained: cancellation now cancels the actual production MQTT read,
        // without closing the shared connection or allowing a detached write to run later.
        val arrived = CompletableDeferred<Unit>()
        var cancelled = false
        val wire = EmqxWireFixture()
        wire.beforeFence = {
            arrived.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val job = launch { transport.read("ABC123") }
        withTimeout(5000L) { arrived.await() }
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(wire.published.isEmpty())
    }
}
