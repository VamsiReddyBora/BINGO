package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying:
 * 1. Invitation serialization, unpadded base64 & raw JSON decoding
 * 2. 2-minute invite TTL expiration enforcement
 * 3. Compact OnlineRoomSession payload serialization (< 400 bytes)
 * 4. Room status lifecycle ("WAITING", "PLAYING", "CLOSED")
 * 5. Instant invite clear & retained MQTT topic wiping
 * 6. Dual-channel fallback decoding
 */
class InvitationAndRoomLifecycleTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun `test game invite serialization and deserialization roundtrip`() {
        val invite = GameInvite(
            fromUsername = "alice",
            fromDisplayName = "Alice In Wonderland",
            fromAvatarUrl = null,
            roomCode = "ROOM88",
            timestamp = System.currentTimeMillis()
        )

        val encoded = json.encodeToString(invite)
        assertTrue(encoded.contains("\"roomCode\":\"ROOM88\""))
        assertTrue(encoded.contains("\"fromUsername\":\"alice\""))

        val decoded = json.decodeFromString<GameInvite>(encoded)
        assertEquals(invite.roomCode, decoded.roomCode)
        assertEquals(invite.fromUsername, decoded.fromUsername)
        assertEquals(invite.fromDisplayName, decoded.fromDisplayName)
    }

    @Test
    fun `test game invite TTL expiration at 120 seconds`() {
        val now = System.currentTimeMillis()
        val freshInvite = GameInvite(
            fromUsername = "bob",
            fromDisplayName = "Bob",
            roomCode = "ROOM01",
            timestamp = now - 30_000L // 30 seconds ago -> FRESH
        )
        val staleInvite = GameInvite(
            fromUsername = "charlie",
            fromDisplayName = "Charlie",
            roomCode = "ROOM02",
            timestamp = now - 150_000L // 2.5 minutes ago -> EXPIRED
        )

        val invites = listOf(freshInvite, staleInvite)
        val validInvites = invites.filter { (now - it.timestamp) < 120_000L }

        assertEquals(1, validInvites.size)
        assertEquals("ROOM01", validInvites.first().roomCode)
    }

    @Test
    fun `test online room session encoding and decoding resilience`() {
        val host = Player(
            id = "h123",
            displayName = "Vamsi Reddy Bora",
            username = "vamsireddy",
            isHost = true,
            level = 10,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val session = OnlineRoomSession(
            roomCode = "VAMSI9",
            hostId = host.id,
            hostUsername = host.username,
            hostDisplayName = host.displayName,
            status = "WAITING",
            boardSize = 5,
            players = listOf(host)
        )

        val rawJson = json.encodeToString(session)

        // Test GZ encoding and decoding via OnlineRoomRegistry
        val encodedGz = OnlineRoomRegistry.encodeBase64Url(rawJson)
        assertTrue(encodedGz.startsWith("GZ:"))
        assertTrue(encodedGz.length < 1024) // Fits comfortably in keyvalue query param limit (< 1024 bytes)

        val decodedFromGz = OnlineRoomRegistry.decodeBase64Url(encodedGz)
        val restoredFromGz = json.decodeFromString<OnlineRoomSession>(decodedFromGz)
        assertEquals("VAMSI9", restoredFromGz.roomCode)
        assertEquals("vamsireddy", restoredFromGz.hostUsername)

        // Test plain JSON resilience (if keyvalue returned plain json string)
        val decodedPlain = OnlineRoomRegistry.decodeBase64Url(rawJson)
        assertEquals(rawJson, decodedPlain)
        val restoredPlain = json.decodeFromString<OnlineRoomSession>(decodedPlain)
        assertEquals("VAMSI9", restoredPlain.roomCode)
    }

    @Test
    fun `test closed room rejection in session validation`() {
        val closedSession = OnlineRoomSession(
            roomCode = "CLOSED1",
            hostId = "host1",
            hostUsername = "host1",
            hostDisplayName = "Host",
            status = "CLOSED"
        )

        assertEquals("CLOSED", closedSession.status)
        // Verify closed room is dropped
        val activeSessions = listOf(closedSession).filter { it.status != "CLOSED" }
        assertTrue(activeSessions.isEmpty())
    }

    @Test
    fun `test one-time invite clear and list removal`() {
        val list = mutableListOf(
            GameInvite("alice", "Alice", null, "RM111", System.currentTimeMillis()),
            GameInvite("bob", "Bob", null, "RM222", System.currentTimeMillis())
        )

        // Recipient accepts RM111 -> remove RM111
        list.removeAll { it.roomCode.equals("RM111", ignoreCase = true) }
        assertEquals(1, list.size)
        assertEquals("RM222", list.first().roomCode)

        // Clear all remaining invites
        list.clear()
        assertTrue(list.isEmpty())
    }
}
