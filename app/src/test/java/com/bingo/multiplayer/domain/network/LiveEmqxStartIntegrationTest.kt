package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real EMQX wire and the same handshake/schedule components called by RootNavGraph.
 * This measures native schedule handlers, not Compose rendering or phone process scheduling. */
class LiveEmqxStartIntegrationTest {
    @Test fun initialConnectTimeoutRetriesAndSameConnectionRecoversItsSubscriptions() = runBlocking {
        assumeTrue(System.getenv("BINGO_LIVE_MQTT") == "1")
        val attempts = AtomicInteger()
        val connection = EmqxConnection("qa-retry-${UUID.randomUUID()}") { if (attempts.incrementAndGet() == 1) 1L else 5000L }
        val topic = "bingo/emqx7/qa_retry/${UUID.randomUUID()}"
        var handle: AutoCloseable? = null
        try {
            val healthy = connection.awaitHealthy(15_000L)
            assertTrue("An operation deadline must not cancel the persistent reconnect loop: ${connection.diagnostics}", healthy)
            assertTrue(attempts.get() >= 2)
            val received = CompletableDeferred<Unit>()
            handle = connection.subscribe(topic) { data, _ -> if (data.toString(Charsets.UTF_8) == "after-reconnect") received.complete(Unit) }
            connection.interruptSocket()
            assertFalse(connection.isHealthy)
            try { connection.publish(topic, "must-not-queue".toByteArray()); fail("A disconnected action must fail immediately") }
            catch (_: java.io.IOException) {}
            val start = System.nanoTime()
            assertTrue(connection.awaitHealthy(15_000L))
            connection.publish(topic, "after-reconnect".toByteArray())
            withTimeout(5000L) { received.await() }
            println("PASS native initial timeout retries; reconnect restores subscriptions; recovery_ms=${(System.nanoTime() - start) / 1_000_000L}")
        } finally { handle?.close(); connection.close() }
    }

    @Test fun acknowledgedNativeLobbySchedulesThreeTwoOneAcrossRealEmqxWithClockSkew() = runBlocking {
        assumeTrue(System.getenv("BINGO_LIVE_MQTT") == "1")
        val suffix = UUID.randomUUID().toString()
        val host = Player("qa-h-$suffix", "QA host", isHost = true, lobbyReadyStatus = "READY", readyVersion = 1L)
        val guest = Player("qa-g-$suffix", "QA guest", lobbyReadyStatus = "READY", readyVersion = 2L)
        val roster = listOf(host, guest)
        val hc = EmqxConnection("qa-start-h-$suffix"); val gc = EmqxConnection("qa-start-g-$suffix")
        val hb = LobbyStartBarrier(); val gb = LobbyStartBarrier()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val ht = "bingo/emqx7/qa_start/$suffix/h"; val gt = "bingo/emqx7/qa_start/$suffix/g"
        val guestSchedule = CompletableDeferred<RoomMessagePacket>()
        val hostTicks = CopyOnWriteArrayList<Int>(); val guestTicks = CopyOnWriteArrayList<Int>()
        var hsub: AutoCloseable? = null; var gsub: AutoCloseable? = null
        try {
            val hostHealthy = hc.awaitHealthy(12_000)
            assertTrue("Start host EMQX health: ${hc.diagnostics}", hostHealthy)
            val guestHealthy = gc.awaitHealthy(12_000)
            assertTrue("Start guest EMQX health: ${gc.diagnostics}", guestHealthy)
            hsub = hc.subscribe(gt) { bytes, _ ->
                val packet = json.decodeFromString<RoomMessagePacket>(bytes.toString(Charsets.UTF_8))
                if (packet.heartbeatAction in setOf("SCHEDULED", "COMMITTED", "RELEASED")) hb.acknowledgeScheduled(packet)
                else hb.acknowledge(packet, System.currentTimeMillis())
            }
            gsub = gc.subscribe(ht) { bytes, _ -> scope.launch {
                val packet = json.decodeFromString<RoomMessagePacket>(bytes.toString(Charsets.UTF_8))
                val ack = if (packet.type == "PREPARE_START") gb.acceptPreparation(packet, guest.id, host.id, roster, gc.isHealthy, System.currentTimeMillis() + 60_000L)
                else if (gb.acceptsSchedule(packet, guest.id, host.id, roster)) {
                    if (packet.startCommitted) gb.observeCommit(packet)
                    guestSchedule.complete(packet)
                    RoomMessagePacket("START_GAME_ACK", playerId = guest.id, senderPlayerId = guest.id, targetPlayerId = host.id,
                        roomCode = packet.roomCode, seed = packet.seed, requestId = packet.requestId,
                        connectionGeneration = packet.connectionGeneration, heartbeatAction = if (packet.startReleased) "RELEASED" else if (packet.startCommitted) "COMMITTED" else "SCHEDULED")
                } else null
                if (ack != null) gc.publish(gt, json.encodeToString(ack).toByteArray())
            } }
            val prepare = RoomMessagePacket("PREPARE_START", playerId = host.id, senderPlayerId = host.id,
                roomCode = "OWNED_$suffix", seed = System.currentTimeMillis(), players = roster,
                connectionGeneration = "epoch-$suffix", requestId = "prepare-$suffix")
            hb.begin(prepare)
            hc.publish(ht, json.encodeToString(hb.preparation(System.currentTimeMillis())!!).toByteArray())
            withTimeout(5000L) { while (!hb.complete) delay(10L) }
            val start = hb.schedule(prepare.copy(type = "START_GAME", startCommitDeadline = System.currentTimeMillis() + 3300L))!!
            hc.publish(ht, json.encodeToString(start).toByteArray())
            val guestStart = withTimeout(5000L) { guestSchedule.await() }
            val guestRun = async {
                val ok = LobbyStartSchedule.awaitStart(guestStart, guest.id, { gc.isHealthy }, { guestTicks.add(it) },
                    wallNow = { System.currentTimeMillis() + 60_000L })
                assertTrue(ok && gb.canEnter)
                System.nanoTime()
            }
            withTimeout(2000L) { while (!hb.scheduledComplete) delay(10L) }
            assertTrue(hb.commitSchedule())
            val committed = start.copy(startCommitted = true)
            hc.publish(ht, json.encodeToString(committed).toByteArray())
            withTimeout(1000L) { while (!hb.scheduledComplete) delay(10L) }
            assertTrue(hb.releaseSchedule())
            hc.publish(ht, json.encodeToString(committed.copy(startReleased = true)).toByteArray())
            withTimeout(1000L) { while (!hb.scheduledComplete) delay(10L) }
            val hostRun = async {
                assertTrue(LobbyStartSchedule.awaitStart(start, host.id, { hc.isHealthy && hb.canEnter }, { hostTicks.add(it) }, minimumRemainingMillis = 0L))
                System.nanoTime()
            }
            val skew = kotlin.math.abs(hostRun.await() - guestRun.await()) / 1_000_000L
            assertEquals(listOf(3, 2, 1), hostTicks.toList()); assertEquals(listOf(3, 2, 1), guestTicks.toList())
            assertTrue("Native entry times should agree within observed network timing", skew < 300L)
            println("PASS real EMQX acknowledged roster; host=$hostTicks guest=$guestTicks; native_entry_delta_ms=$skew; phone_UI_not_measured=true")
        } finally { hsub?.close(); gsub?.close(); scope.cancel(); hc.close(); gc.close() }
    }

    @Test fun stalledZombieSocketWithoutEchoMustForciblyReconnectAndRestoreHealth() = runBlocking {
        assumeTrue(System.getenv("BINGO_LIVE_MQTT") == "1")
        val connection = EmqxConnection("qa-zombie-${UUID.randomUUID()}")
        try {
            assertTrue("Initial connection must become healthy", connection.awaitHealthy(15_000L))
            val initialClient = connection.client
            assertNotNull("Initial client must exist", initialClient)

            // Simulate silent socket stall: lastEcho expired (>6s) while client.isConnected is still true
            connection.simulateZombieStall()
            assertFalse("Stalled socket must be marked unhealthy", connection.isHealthy)

            // A self-healing connection must detect the zombie socket, tear down the stalled client,
            // create a fresh client, and restore health.
            val recovered = connection.awaitHealthy(8_000L)
            assertTrue("Stalled zombie socket must recover health within deadline", recovered)
            assertNotSame("Stalled client must be torn down and replaced with a fresh client", initialClient, connection.client)
        } finally {
            connection.close()
        }
    }
}
