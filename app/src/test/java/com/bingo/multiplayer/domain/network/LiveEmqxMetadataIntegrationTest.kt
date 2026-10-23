package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class LiveEmqxMetadataIntegrationTest {
    @Test fun actualHostWriterAdmitsGuestAndColdReaderSeesTerminalClosure() = runBlocking {
        assumeTrue("Opt in with BINGO_LIVE_MQTT=1", System.getenv("BINGO_LIVE_MQTT") == "1")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val code = "OWNED_META_$suffix"
        val host = Player("qa_host_$suffix", "QA host", "qa_host_$suffix", isHost = true)
        val guest = Player("qa_guest_$suffix", "QA guest", "qa_guest_$suffix")
        val hc = EmqxConnection("qa-meta-host-$suffix")
        val gc = EmqxConnection("qa-meta-guest-$suffix")
        val cc = EmqxConnection("qa-meta-cold-$suffix")
        val hs = MqttRoomMetadataTransport(hc, { host.id })
        val gs = MqttRoomMetadataTransport(gc, { guest.id })
        val cold = MqttRoomMetadataTransport(cc, { guest.id })
        var failure: Throwable? = null
        try {
            val hostHealthy = hc.awaitHealthy(12_000)
            assertTrue("Host EMQX health: ${hc.diagnostics}", hostHealthy)
            val guestHealthy = gc.awaitHealthy(12_000)
            assertTrue("Guest EMQX health: ${gc.diagnostics}", guestHealthy)
            val h = RoomMetadataCoordinator(hs); val g = RoomMetadataCoordinator(gs)
            val created = h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it }
            assertTrue("Host creation must receive broker confirmation: $created ${hc.diagnostics}", created is MutationResult.Applied)
            val joined = g.mutate(code) { it.copy(players = it.players + guest) }
            assertTrue("Guest admission must be committed by the live host writer: $joined ${gc.diagnostics}", joined is MutationResult.Applied)
            assertEquals(2, hs.read(code).getOrThrow()!!.players.size)
            val ready = g.mutate(code) { r -> r.copy(players = r.players.map { if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY", readyVersion = 1L) else it }) }
            assertTrue(ready is MutationResult.Applied)
            assertTrue(h.mutate(code, expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 99L) } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(hostId = guest.id) } is MutationResult.Rejected)
            assertTrue(h.mutate(code, expectedSeed = 99L) { it.copy(status = "CLOSED") } is MutationResult.Applied)
            val stored = cold.read(code).getOrThrow()!!
            assertEquals("CLOSED", stored.status); assertTrue(stored.isTerminalClosed)
            assertEquals(99L, stored.currentSeed); assertEquals(2, stored.players.size)
            println("PASS actual native EMQX host writer -> guest proposal/ACK -> readiness -> start -> cold retained CLOSED")
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            hs.close(); gs.close(); cold.close()
            try {
                check(hc.awaitHealthy(10_000L)) { "Owned metadata cleanup connection unavailable: ${hc.diagnostics}" }
                hc.publish("bingo/emqx7/metadata/$code/state", ByteArray(0), retained = true)
                hc.publish("bingo/emqx7/metadata/$code/auth", ByteArray(0), retained = true)
                val stateClearConfirmed = hc.confirmRetained("bingo/emqx7/metadata/$code/state", ByteArray(0))
                val authClearConfirmed = hc.confirmRetained("bingo/emqx7/metadata/$code/auth", ByteArray(0))
                assertTrue("Retained state cleanup must be confirmed absent", stateClearConfirmed)
                assertTrue("Retained auth cleanup must be confirmed absent", authClearConfirmed)
                val verifier = MqttRoomMetadataTransport(hc, { "cleanup-verifier" })
                try {
                    val clearedSession = verifier.read(code).getOrNull()
                    assertNull("Metadata records must be absent after retained cleanup", clearedSession)
                } finally {
                    verifier.close()
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            } finally { hc.close(); gc.close(); cc.close() }
        }
    }
}
