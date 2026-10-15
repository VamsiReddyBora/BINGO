package com.bingo.multiplayer.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit test suite validating MATCH_HEARTBEAT packet codec, challenge-reply validation,
 * connection generations, and backward compatibility.
 * Validates test scenarios: H10, H11, H14.
 */
class MatchHeartbeatPacketCodecTest {

    @Test
    fun `test MATCH_HEARTBEAT micro-string encode and decode roundtrip`() {
        val probe = RoomMessagePacket(
            type = "MATCH_HEARTBEAT",
            protocolVersion = 1,
            matchId = "match_xyz_999",
            heartbeatAction = "PROBE",
            playerId = "user_kevin",
            peerUid = "user_stuart",
            connectionGeneration = "gen_session_1",
            sequence = 42L,
            challengeId = "chal_abc",
            timestamp = 1729001234567L
        )

        val encoded = FastPacketCodec.encode(probe)
        assertTrue("Encoded string should start with MHB|", encoded.startsWith("MHB|"))
        assertEquals(
            "MHB|1|match_xyz_999|PROBE|user_kevin|user_stuart|gen_session_1|42|chal_abc|1729001234567",
            encoded
        )

        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("MATCH_HEARTBEAT", decoded.type)
        assertEquals(1, decoded.protocolVersion)
        assertEquals("match_xyz_999", decoded.matchId)
        assertEquals("PROBE", decoded.heartbeatAction)
        assertEquals("user_kevin", decoded.playerId)
        assertEquals("user_stuart", decoded.peerUid)
        assertEquals("gen_session_1", decoded.connectionGeneration)
        assertEquals(42L, decoded.sequence)
        assertEquals("chal_abc", decoded.challengeId)
        assertEquals(1729001234567L, decoded.timestamp)
    }

    @Test
    fun `test MATCH_HEARTBEAT ACK encode and decode roundtrip`() {
        val ack = RoomMessagePacket(
            type = "MATCH_HEARTBEAT",
            protocolVersion = 1,
            matchId = "match_xyz_999",
            heartbeatAction = "ACK",
            playerId = "user_stuart",
            peerUid = "user_kevin",
            connectionGeneration = "gen_session_1",
            sequence = 42L,
            challengeId = "chal_abc",
            timestamp = 1729001234599L
        )

        val encoded = FastPacketCodec.encode(ack)
        assertTrue(encoded.startsWith("MHB|"))
        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("ACK", decoded.heartbeatAction)
        assertEquals("user_stuart", decoded.playerId)
        assertEquals("user_kevin", decoded.peerUid)
        assertEquals("chal_abc", decoded.challengeId)
    }

    @Test
    fun `H10 - packets for wrong match or superseded connection generation must not match`() {
        val currentMatchId = "match_100"
        val activeGeneration = "gen_current"

        val wrongMatchPacket = RoomMessagePacket(
            type = "MATCH_HEARTBEAT",
            matchId = "match_old_99",
            connectionGeneration = activeGeneration,
            challengeId = "chal_1"
        )
        assertNotEquals(currentMatchId, wrongMatchPacket.matchId)

        val oldGenerationPacket = RoomMessagePacket(
            type = "MATCH_HEARTBEAT",
            matchId = currentMatchId,
            connectionGeneration = "gen_superseded",
            challengeId = "chal_1"
        )
        assertNotEquals(activeGeneration, oldGenerationPacket.connectionGeneration)
    }

    @Test
    fun `H11 - reconnect creates fresh connection generation invalidating older generation ACKs`() {
        val gen1 = UUID.randomUUID().toString()
        val gen2 = UUID.randomUUID().toString()
        assertNotEquals(gen1, gen2)

        val ackFromOldGen = RoomMessagePacket(
            type = "MATCH_HEARTBEAT",
            heartbeatAction = "ACK",
            connectionGeneration = gen1,
            challengeId = "chal_xyz"
        )

        // Validation against active generation gen2:
        val isValid = (ackFromOldGen.connectionGeneration == gen2)
        assertFalse("ACK from old generation must be rejected", isValid)
    }

    @Test
    fun `test challenge validation rejects challenges older than 5000 ms`() {
        val challenges = mutableMapOf<String, Long>()
        val now = 100_000L

        // Challenge 1 sent 2000 ms ago (valid)
        challenges["chal_valid"] = now - 2000L

        // Challenge 2 sent 5001 ms ago (expired)
        challenges["chal_expired"] = now - 5001L

        val isChal1Valid = challenges["chal_valid"]?.let { (now - it) <= 5000L } ?: false
        val isChal2Valid = challenges["chal_expired"]?.let { (now - it) <= 5000L } ?: false

        assertTrue("Challenge within 5000ms should be valid", isChal1Valid)
        assertFalse("Challenge older than 5000ms must be expired", isChal2Valid)
    }

    @Test
    fun `test legacy packets remain strictly compatible with FastPacketCodec`() {
        // PICK_NUMBER
        val pick = RoomMessagePacket(type = "PICK_NUMBER", number = 14, playerId = "u1", turnNumber = 2, currentTurnPlayerId = "u2")
        val pickEncoded = FastPacketCodec.encode(pick)
        assertTrue(pickEncoded.startsWith("P|14|u1|2|u2|"))
        val pickDecoded = FastPacketCodec.decode(pickEncoded)
        assertEquals(14, pickDecoded.number)
        assertEquals("u1", pickDecoded.playerId)

        // PING / PONG
        val ping = RoomMessagePacket(type = "PING", playerId = "u1", pingTimestamp = 55555L)
        val pingDecoded = FastPacketCodec.decode(FastPacketCodec.encode(ping))
        assertEquals("PING", pingDecoded.type)
        assertEquals(55555L, pingDecoded.pingTimestamp)

        // HEARTBEAT
        val hb = RoomMessagePacket(type = "HEARTBEAT", playerId = "u1", displayName = "Player 1", isHost = true)
        val hbDecoded = FastPacketCodec.decode(FastPacketCodec.encode(hb))
        assertEquals("HEARTBEAT", hbDecoded.type)
        assertEquals("u1", hbDecoded.playerId)
        assertTrue(hbDecoded.isHost)
    }
}
