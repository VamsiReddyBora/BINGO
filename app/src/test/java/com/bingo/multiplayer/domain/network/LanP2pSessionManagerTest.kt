package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

class LanP2pSessionManagerTest {

    @Test
    fun testP2pGameFlow() = runBlocking {
        println("=== Starting P2P Network Flow Test ===")
        val hostManager = LanP2pSessionManager()
        val clientManager = LanP2pSessionManager()

        val hostPlayer = Player(id = "host1", displayName = "Alice", isHost = true)
        val clientPlayer = Player(id = "client1", displayName = "Bob", isHost = false)

        hostManager.connectAsHost(hostPlayer, port = 9001)
        println("Host started on port 9001")
        delay(500)

        clientManager.connectAsClient("127.0.0.1", clientPlayer, port = 9001)
        println("Client connecting to 127.0.0.1:9001")
        delay(1000)

        println("Host sending START_GAME...")
        hostManager.broadcastPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = hostPlayer.id,
                boardSize = 5,
                seed = 12345
            )
        )

        val clientReceivedStart = clientManager.incomingPackets.first { it.type == "START_GAME" }
        println("Client received: ${clientReceivedStart.type} with seed ${clientReceivedStart.seed}")
        assertEquals(12345, clientReceivedStart.seed)
        assertEquals("START_GAME", clientReceivedStart.type)

        println("Client sending PICK_NUMBER (15)...")
        clientManager.broadcastPacket(
            RoomMessagePacket(
                type = "PICK_NUMBER",
                playerId = clientPlayer.id,
                number = 15,
                pickedHistory = listOf(15)
            )
        )

        val hostReceivedPick = hostManager.incomingPackets.first { it.type == "PICK_NUMBER" }
        println("Host received: ${hostReceivedPick.type} with number ${hostReceivedPick.number}")
        assertEquals(15, hostReceivedPick.number)
        assertEquals("client1", hostReceivedPick.playerId)

        println("Host sending PLAY_AGAIN with new seed...")
        hostManager.broadcastPacket(
            RoomMessagePacket(
                type = "PLAY_AGAIN",
                playerId = hostPlayer.id,
                boardSize = 5,
                seed = 99999
            )
        )

        val clientReceivedPlayAgain = clientManager.incomingPackets.first { it.type == "PLAY_AGAIN" }
        println("Client received: ${clientReceivedPlayAgain.type} with seed ${clientReceivedPlayAgain.seed}")
        assertEquals(99999, clientReceivedPlayAgain.seed)

        hostManager.disconnect()
        clientManager.disconnect()
        println("=== P2P Network Flow Test Passed Successfully ===")
    }

    @Test
    fun testStandardHotspotQrParsing() {
        val qrRaw = "WIFI:T:WPA;S:realme 8 pro;P:secret123;;"
        val payload = QrCodeHelper.parseQrContent(qrRaw)
        assertNotNull(payload)
        assertEquals("realme 8 pro", payload?.ssid)
        assertEquals("secret123", payload?.password)
        assertEquals("realme 8 pro", payload?.hostName)
    }

    @Test
    fun testP2pClientConnectWithFallbackIp() = runBlocking {
        val hostManager = LanP2pSessionManager()
        val clientManager = LanP2pSessionManager()

        val hostPlayer = Player(id = "host2", displayName = "RealmeHost", isHost = true)
        val clientPlayer = Player(id = "client2", displayName = "JoinerBob", isHost = false)

        hostManager.connectAsHost(hostPlayer, port = 9002)
        delay(500)

        // Connect with empty hostIp and valid fallbackIp ("127.0.0.1")
        clientManager.connectAsClient("", clientPlayer, port = 9002, fallbackIp = "127.0.0.1")
        delay(1500)

        val clientPlayers = clientManager.players.value
        val hostPlayers = hostManager.players.value

        assertTrue(clientPlayers.any { it.id == "client2" })
        assertTrue(hostPlayers.any { it.id == "client2" })

        hostManager.disconnect()
        clientManager.disconnect()
    }

    @Test
    fun testLanDiscoveredGameCodecAndIsAlive() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val now = System.currentTimeMillis()
        val game = LanDiscoveredGame(
            hostId = "host_123",
            hostDisplayName = "Rahul",
            hostUsername = "rahul99",
            avatarUrl = "https://example.com/avatar.png",
            boardSize = 5,
            roomCode = "LAN_4821",
            hostIp = "192.168.1.45",
            ssid = "HomeWifi",
            isInLobby = false,
            broadcastTimestamp = now,
            lastSeenTimestamp = now
        )

        val encoded = json.encodeToString(LanDiscoveredGame.serializer(), game)
        val decoded = json.decodeFromString(LanDiscoveredGame.serializer(), encoded)

        assertEquals("host_123", decoded.hostId)
        assertEquals("Rahul", decoded.hostDisplayName)
        assertEquals("LAN_4821", decoded.roomCode)
        assertEquals("192.168.1.45", decoded.hostIp)
        assertTrue(decoded.isAlive)

        val staleGame = decoded.copy(lastSeenTimestamp = now - 5000L)
        assertFalse(staleGame.isAlive)
    }
}
