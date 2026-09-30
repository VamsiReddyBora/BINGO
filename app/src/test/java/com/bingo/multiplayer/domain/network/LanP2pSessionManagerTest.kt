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
}
