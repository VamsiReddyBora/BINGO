package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Optional live smoke test. It never joins or changes a real user's room. */
class ReadLiveRoomProbe {
    @Test fun probeOwnTemporaryRoom() = runBlocking {
        assumeTrue("Opt in with BINGO_LIVE_MQTT=1", System.getenv("BINGO_LIVE_MQTT") == "1")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val code = "OWNED_PROBE_$suffix"
        val host = Player("google_qa_host_$suffix", "QA host", "qa_host_$suffix", isHost = true)
        val guest = Player("guest_qa_installation_$suffix", "QA guest", "qa_guest_$suffix")
        val hc = EmqxConnection("qa-probe-host-$suffix")
        val gc = EmqxConnection("qa-probe-guest-$suffix")
        val hs = MqttRoomMetadataTransport(hc, { host.id })
        val gs = MqttRoomMetadataTransport(gc, { guest.id })
        try {
            assertTrue(hc.awaitHealthy(12_000L) && gc.awaitHealthy(12_000L))
            val room = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
            assertTrue(hs.commit(code, null, room) is RoomMetadataCommit.Applied)
            assertEquals(host.id, gs.read(code).getOrThrow()!!.hostId)
            assertTrue(RoomMetadataCoordinator(gs).mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            assertEquals(1, hs.read(code).getOrThrow()!!.players.count { it.id == guest.id })
        } finally {
            hs.close(); gs.close()
            try {
                check(hc.awaitHealthy(10_000L))
                for (leaf in listOf("state", "auth")) {
                    val topic = "bingo/emqx7/metadata/$code/$leaf"
                    hc.publish(topic, ByteArray(0), retained = true)
                    check(hc.confirmRetained(topic, ByteArray(0)))
                }
            } finally { hc.close(); gc.close() }
        }
    }
}
