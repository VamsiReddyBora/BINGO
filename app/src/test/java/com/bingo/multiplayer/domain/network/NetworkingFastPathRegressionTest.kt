package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Proves that networking fast paths use the persistent EMQX socket for:
 * 1. Username searches (instant readRetained instead of ephemeral TLS connect)
 * 2. Username claims (instant retained publish)
 * 3. Avatar update broadcasts (instant retained publish)
 * 4. Friend request & acceptance dispatch (instant publish)
 *
 * Verifies that all operations succeed and wire packets arrive on target topics.
 */
class NetworkingFastPathRegressionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var wireFixture: EmqxWireFixture

    @Before
    fun setUp() {
        wireFixture = EmqxWireFixture()
        EmqxTransport.wireOverride = wireFixture
    }

    @After
    fun tearDown() = runBlocking {
        AccountSessionManager.defaultInstance.awaitAllBackgroundJobs()
        FriendRequestManager.awaitActiveJobs()
        EmqxTransport.wireOverride = null
    }

    @Test
    fun searchPlayerByUsernameMqttRetrievesFromRetainedTopicViaPersistentSocket() = runBlocking {
        val entry = PlayerRegistryEntry(
            username = "alice_wonder",
            uid = "uid-alice-123",
            displayName = "Alice In Wonderland",
            avatarUrl = "https://example.com/alice.png",
            level = 7,
            gamesPlayed = 42,
            gamesWon = 25
        )
        val payload = json.encodeToString(entry).toByteArray(StandardCharsets.UTF_8)
        wireFixture.retained["bingo/v3/registry/alice_wonder"] = payload

        val result = AccountSessionManager.defaultInstance.searchPlayerByUsernameMqtt("alice_wonder", 2000L)

        assertNotNull("Search should find player via persistent EMQX socket retained lookup", result)
        assertEquals("alice_wonder", result?.username)
        assertEquals("uid-alice-123", result?.uid)
        assertEquals("Alice In Wonderland", result?.displayName)
        assertEquals("https://example.com/alice.png", result?.avatarUrl)
        assertEquals(7, result?.level)
    }

    @Test
    fun claimUsernameMqttPublishesRetainedViaPersistentSocket() = runBlocking {
        val entry = PlayerRegistryEntry(
            username = "bob_builder",
            uid = "uid-bob-456",
            displayName = "Bob The Builder",
            avatarUrl = null,
            level = 3
        )

        AccountSessionManager.defaultInstance.claimUsernameMqtt(entry, "google_bob_id_999")

        var foundRegistry = false
        var foundGoogle = false
        for (i in 1..20) {
            delay(50L)
            if (wireFixture.retained.containsKey("bingo/v3/registry/bob_builder")) foundRegistry = true
            if (wireFixture.retained.containsKey("bingo/v3/google_account/google_bob_id_999")) foundGoogle = true
            if (foundRegistry && foundGoogle) break
        }

        assertTrue("Claim should publish retained registry entry via persistent socket", foundRegistry)
        assertTrue("Claim should publish retained Google account mapping via persistent socket", foundGoogle)

        val regBytes = wireFixture.retained["bingo/v3/registry/bob_builder"]
        assertNotNull(regBytes)
        val decoded = json.decodeFromString<PlayerRegistryEntry>(String(regBytes!!, StandardCharsets.UTF_8))
        assertEquals("bob_builder", decoded.username)
        assertEquals("Bob The Builder", decoded.displayName)
    }

    @Test
    fun broadcastAvatarUpdatePublishesRetainedViaPersistentSocket() = runBlocking {
        val avatarB64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        AccountSessionManager.defaultInstance.broadcastAvatarUpdate("charlie", avatarB64)

        var found = false
        for (i in 1..20) {
            delay(50L)
            if (wireFixture.retained.containsKey("bingo/v3/avatar_update/charlie")) {
                found = true
                break
            }
        }

        assertTrue("Avatar update should be published retained via persistent socket", found)
        val payload = String(wireFixture.retained["bingo/v3/avatar_update/charlie"]!!, StandardCharsets.UTF_8)
        assertTrue("Avatar payload must contain avatar string", payload.contains("data:image/png;base64"))
    }

    @Test
    fun publishMqttPacketDispatchesFriendRequestViaPersistentSocket() = runBlocking {
        val req = FriendRequest(
            id = "freq-101",
            fromUid = "uid-charlie",
            fromUsername = "charlie",
            fromDisplayName = "Charlie Chaplin",
            toUsername = "david",
            timestamp = System.currentTimeMillis()
        )
        val packet = FriendRequestPacket(type = "FRIEND_REQUEST", request = req)

        FriendRequestManager.publishMqttPacket("david", packet)

        var found = false
        for (i in 1..20) {
            delay(50L)
            if (wireFixture.published.contains("bingo/v3/friend_requests/david")) {
                found = true
                break
            }
        }

        assertTrue("Friend request packet should be published via persistent socket", found)
        assertNull("Friend request packets must be non-retained", wireFixture.retained["bingo/v3/friend_requests/david"])
    }

    @Test
    fun publishMqttPacketDispatchesFriendAcceptanceViaPersistentSocket() = runBlocking {
        val req = FriendRequest(
            id = "freq-102",
            fromUid = "uid-david",
            fromUsername = "david",
            fromDisplayName = "David Copperfield",
            toUsername = "alice",
            status = FriendRequestStatus.ACCEPTED,
            timestamp = System.currentTimeMillis()
        )
        val packet = FriendRequestPacket(type = "FRIEND_ACCEPT", request = req)

        FriendRequestManager.publishMqttPacket("david", packet)

        var found = false
        for (i in 1..20) {
            delay(50L)
            if (wireFixture.published.contains("bingo/v3/friend_requests/david")) {
                found = true
                break
            }
        }

        assertTrue("Friend accept packet should be published via persistent socket", found)
        assertNull("Friend accept packets must be non-retained", wireFixture.retained["bingo/v3/friend_requests/david"])
    }
}
