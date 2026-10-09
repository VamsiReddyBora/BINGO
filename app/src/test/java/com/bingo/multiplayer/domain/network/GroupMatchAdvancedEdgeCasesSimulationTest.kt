package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class GroupMatchAdvancedEdgeCasesSimulationTest {

    private val engine = BingoEngine()

    data class SimPlayerNode(
        val uid: String,
        val displayName: String,
        var isHosting: Boolean,
        var isHostLeftGame: Boolean = false,
        var isGameOver: Boolean = false,
        var didPlayerWin: Boolean = false,
        var winnerPlayerId: String = "",
        var activeWinReason: String = "",
        var activeRunnerPlayerIds: List<String> = emptyList(),
        var currentTurnPlayerId: String = "",
        var turnNumber: Int = 1,
        val disconnectedPlayerIds: MutableSet<String> = mutableSetOf(),
        val participants: MutableList<Player> = mutableListOf()
    ) {
        fun isPlayerDisconnected(id: String): Boolean = disconnectedPlayerIds.contains(id)

        fun markPlayerDisconnected(id: String) {
            disconnectedPlayerIds.add(id)
        }

        fun calculateNextTurnPlayerId(currentId: String): String {
            val list = participants.filter { it.id.isNotBlank() }
            if (list.isEmpty()) return uid
            val activeIds = list.map { it.id }.filter { !isPlayerDisconnected(it) }
            if (activeIds.isEmpty()) return uid
            val currentIndex = activeIds.indexOf(currentId)
            return if (currentIndex >= 0) {
                activeIds[(currentIndex + 1) % activeIds.size]
            } else {
                activeIds.first()
            }
        }

        fun handleSurrenderPacket(packetPlayerId: String, packetDisplayName: String) {
            if (isGameOver) return
            val surrenderId = packetPlayerId
            val surrenderName = packetDisplayName.ifBlank { "Opponent" }
            if (surrenderId.isNotBlank()) {
                markPlayerDisconnected(surrenderId)
            }

            val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
            if (activeRemaining.size <= 1) {
                isGameOver = true
                val won = activeRemaining.any { it.id == uid } || participants.size <= 2
                didPlayerWin = won
                winnerPlayerId = if (won) uid else ""
                activeWinReason = "Opponent Surrendered"
            } else {
                // Group match continues!
                if (currentTurnPlayerId == surrenderId) {
                    val nextId = calculateNextTurnPlayerId(surrenderId)
                    currentTurnPlayerId = nextId
                    turnNumber += 1
                }
            }
        }

        fun handleLeavePacket(packetPlayerId: String, isHost: Boolean) {
            if (isGameOver) return
            if (isHost && !isHosting) {
                isHostLeftGame = true
                markPlayerDisconnected(packetPlayerId)
                val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                if (activeRemaining.size <= 1) {
                    isGameOver = true
                    val wonByForfeit = activeRemaining.any { it.id == uid } || participants.size <= 2
                    didPlayerWin = wonByForfeit
                    winnerPlayerId = if (wonByForfeit) uid else ""
                    activeWinReason = "All opponents left the game."
                } else {
                    val isActingHost = activeRemaining.firstOrNull()?.id == uid
                    if (isActingHost) {
                        isHosting = true
                    }
                    if (currentTurnPlayerId == packetPlayerId) {
                        val nextId = calculateNextTurnPlayerId(packetPlayerId)
                        currentTurnPlayerId = nextId
                        turnNumber += 1
                    }
                }
                return
            }

            // Non-host player left
            markPlayerDisconnected(packetPlayerId)
            val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
            if (activeRemaining.size <= 1) {
                isGameOver = true
                val wonByForfeit = activeRemaining.any { it.id == uid } || participants.size <= 2
                didPlayerWin = wonByForfeit
                winnerPlayerId = if (wonByForfeit) uid else ""
                activeWinReason = "Opponent Left"
            } else {
                val hostUid = participants.find { it.isHost }?.id ?: ""
                val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == uid
                if (isActingHost) {
                    isHosting = true
                }
                if (currentTurnPlayerId == packetPlayerId) {
                    val nextId = calculateNextTurnPlayerId(packetPlayerId)
                    currentTurnPlayerId = nextId
                    turnNumber += 1
                }
            }
        }
    }

    @Test
    fun testBug017_GroupMatchContinuesWhenOnePlayerSurrenders() {
        val players = listOf(
            Player(id = "p1", displayName = "Player 1", isHost = true),
            Player(id = "p2", displayName = "Player 2", isHost = false),
            Player(id = "p3", displayName = "Player 3", isHost = false),
            Player(id = "p4", displayName = "Player 4", isHost = false)
        )

        val node1 = SimPlayerNode("p1", "Player 1", isHosting = true, currentTurnPlayerId = "p4").apply {
            participants.addAll(players)
        }
        val node2 = SimPlayerNode("p2", "Player 2", isHosting = false, currentTurnPlayerId = "p4").apply {
            participants.addAll(players)
        }
        val node3 = SimPlayerNode("p3", "Player 3", isHosting = false, currentTurnPlayerId = "p4").apply {
            participants.addAll(players)
        }

        // Player 4 surrenders on their turn
        node1.handleSurrenderPacket("p4", "Player 4")
        node2.handleSurrenderPacket("p4", "Player 4")
        node3.handleSurrenderPacket("p4", "Player 4")

        // Crucial verification: match did NOT abort! Remaining 3 players continue
        assertFalse("Node 1 game must NOT end prematurely when 1 of 4 players surrenders", node1.isGameOver)
        assertFalse("Node 2 game must NOT end prematurely", node2.isGameOver)
        assertFalse("Node 3 game must NOT end prematurely", node3.isGameOver)

        // Turn rotates to next active player (p1)
        assertEquals("p1", node1.currentTurnPlayerId)
        assertEquals("p1", node2.currentTurnPlayerId)
        assertEquals("p1", node3.currentTurnPlayerId)

        assertTrue(node1.isPlayerDisconnected("p4"))
    }

    @Test
    fun testBug018_LoneSurvivorWinsWhenOpponentLeavesTwoPlayerMatch() {
        val players = listOf(
            Player(id = "host", displayName = "Host", isHost = true),
            Player(id = "guest", displayName = "Guest", isHost = false)
        )

        val hostNode = SimPlayerNode("host", "Host", isHosting = true, currentTurnPlayerId = "guest").apply {
            participants.addAll(players)
        }

        // Guest leaves active match
        hostNode.handleLeavePacket("guest", isHost = false)

        // Host immediately declared winner by forfeit
        assertTrue("Host must win by forfeit when sole opponent leaves", hostNode.isGameOver)
        assertTrue(hostNode.didPlayerWin)
        assertEquals("host", hostNode.winnerPlayerId)
        assertEquals("Opponent Left", hostNode.activeWinReason)
    }

    @Test
    fun testBug023_HostLeavesGroupMatchPromotesActingHost() {
        val players = listOf(
            Player(id = "host", displayName = "Host", isHost = true),
            Player(id = "p2", displayName = "Player 2", isHost = false),
            Player(id = "p3", displayName = "Player 3", isHost = false)
        )

        val node2 = SimPlayerNode("p2", "Player 2", isHosting = false, currentTurnPlayerId = "host").apply {
            participants.addAll(players)
        }
        val node3 = SimPlayerNode("p3", "Player 3", isHosting = false, currentTurnPlayerId = "host").apply {
            participants.addAll(players)
        }

        // Original host leaves
        node2.handleLeavePacket("host", isHost = true)
        node3.handleLeavePacket("host", isHost = true)

        assertFalse("Match must continue with remaining 2 players", node2.isGameOver)
        assertFalse("Match must continue with remaining 2 players", node3.isGameOver)

        // Senior remaining player (p2) is promoted to host
        assertTrue("Player 2 must become acting host", node2.isHosting)
        assertFalse("Player 3 must not be host", node3.isHosting)

        // Turn moves past departed host to p2
        assertEquals("p2", node2.currentTurnPlayerId)
        assertEquals("p2", node3.currentTurnPlayerId)
    }
}
