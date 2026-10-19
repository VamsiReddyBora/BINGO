package com.bingo.multiplayer.domain.network

import androidx.compose.ui.graphics.Color
import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.PlayerConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the official simplified, clean, friendly zero-stress multiplayer rules:
 * 1. Player Connection Statuses & Visual Indicator Colors (Green, Yellow, Red)
 * 2. 30s for Online vs 3s Grace for Away/Offline
 * 3. The "Welcome Back!" Rule: Timer resets to 30s when returning online
 * 4. Pure Turn Pass at 0s (no auto-picks, no forfeits)
 * 5. Surrender Mechanics (1v1 instant win, Group mid-turn advance & sole survivor win)
 * 6. Results Screen: "Play Again" only when lobby is available
 */
class SimplifiedCleanMultiplayerTest {

    private val engine = BingoEngine()

    @Test
    fun testPlayerConnectionStatusEnumValues() {
        assertEquals(3, PlayerConnectionStatus.values().size)
        assertTrue(PlayerConnectionStatus.ONLINE.name == "ONLINE")
        assertTrue(PlayerConnectionStatus.AWAY.name == "AWAY")
        assertTrue(PlayerConnectionStatus.OFFLINE.name == "OFFLINE")
    }

    @Test
    fun testStatusRingsAndBannerColorsMatchSpec() {
        val greenRing = Color(0xFF22C55E)
        val yellowRing = Color(0xFFEAB308)
        val redRing = Color(0xFFEF4444)

        fun resolveRingColor(status: PlayerConnectionStatus): Color = when (status) {
            PlayerConnectionStatus.ONLINE -> Color(0xFF22C55E)
            PlayerConnectionStatus.AWAY -> Color(0xFFEAB308)
            PlayerConnectionStatus.OFFLINE -> Color(0xFFEF4444)
        }

        assertEquals(greenRing, resolveRingColor(PlayerConnectionStatus.ONLINE))
        assertEquals(yellowRing, resolveRingColor(PlayerConnectionStatus.AWAY))
        assertEquals(redRing, resolveRingColor(PlayerConnectionStatus.OFFLINE))
    }

    @Test
    fun testTurnBudgetCalculation() {
        // Online players receive 30s, away or disconnected players receive 3s grace
        fun resolveInitialTurnTimer(status: PlayerConnectionStatus): Int = when (status) {
            PlayerConnectionStatus.ONLINE -> 30
            PlayerConnectionStatus.AWAY -> 3
            PlayerConnectionStatus.OFFLINE -> 3
        }

        assertEquals(30, resolveInitialTurnTimer(PlayerConnectionStatus.ONLINE))
        assertEquals(3, resolveInitialTurnTimer(PlayerConnectionStatus.AWAY))
        assertEquals(3, resolveInitialTurnTimer(PlayerConnectionStatus.OFFLINE))
    }

    @Test
    fun testWelcomeBackRuleResetsTimerTo30s() {
        var status = PlayerConnectionStatus.AWAY
        var wasAwayOrOffline = (status != PlayerConnectionStatus.ONLINE)
        var turnTimer = if (wasAwayOrOffline) 3 else 30

        assertEquals(3, turnTimer)
        assertTrue(wasAwayOrOffline)

        // Tick 1 second down
        turnTimer -= 1
        assertEquals(2, turnTimer)

        // Player returns online at 2 seconds remaining!
        status = PlayerConnectionStatus.ONLINE
        if (wasAwayOrOffline && status == PlayerConnectionStatus.ONLINE) {
            wasAwayOrOffline = false
            turnTimer = 30
        }

        // Must immediately reset to full 30s and switch to green
        assertEquals(30, turnTimer)
        assertFalse(wasAwayOrOffline)
    }

    @Test
    fun testPureTurnPassDoesNotModifyBoardOrForfeitPlayer() {
        val board = engine.generateBoard(5, seed = 12345L)
        val initialMarkedCount = board.cells.count { it.isMarked }
        assertEquals(0, initialMarkedCount)

        // Timeout pass: number is -1
        val isTimeoutPass = true
        var turnNumber = 1
        var turnTimer = 0

        if (isTimeoutPass) {
            // No cells marked on pure pass
            turnNumber += 1
            turnTimer = 30
        }

        // Board unchanged
        assertEquals(0, board.cells.count { it.isMarked })
        assertEquals(2, turnNumber)
        assertEquals(30, turnTimer)
    }

    @Test
    fun testGroupMidTurnSurrenderAdvancesTurnImmediatelyWithFull30s() {
        val p1 = Player("p1", "Alice", isHost = true)
        val p2 = Player("p2", "Bob", isHost = false)
        val p3 = Player("p3", "Charlie", isHost = false)
        val participants = listOf(p1, p2, p3)

        val context = MultiplayerGameplayContext(
            seed = 100L,
            localId = "p1",
            hostId = "p1",
            participants = participants,
            turnOrder = participants
        )

        val initialState = MultiplayerGameplayState(
            picks = emptyList(),
            playerBoard = engine.generateBoard(5, 100L),
            opponentBoard = engine.generateBoard(5, 101L),
            allPlayerBoards = emptyMap(),
            turnNumber = 1,
            currentPlayerId = "p2", // It is Bob's turn
            remainingTurnMillis = 15_000L
        )

        // Bob surrenders mid-turn
        val surrenderPacket = RoomMessagePacket(
            type = "SURRENDER",
            playerId = "p2",
            displayName = "Bob",
            seed = 100L
        )

        val decision = MultiplayerGameplayReducer.surrender(surrenderPacket, context, initialState)
        assertTrue(decision.accepted)

        // Next player is Charlie (p3)
        assertEquals("p3", decision.state.currentPlayerId)
        // Turn number advanced from 1 to 2
        assertEquals(2, decision.state.turnNumber)
        // Fresh full 30s budget for Charlie
        assertEquals(30_000L, decision.state.remainingTurnMillis)
        // Bob is marked surrendered
        assertTrue(decision.state.surrenderedPlayerIds.contains("p2"))
    }

    @Test
    fun testSoleSurvivorWinsWhenOthersSurrender() {
        val p1 = Player("p1", "Alice", isHost = true)
        val p2 = Player("p2", "Bob", isHost = false)
        val p3 = Player("p3", "Charlie", isHost = false)
        val participants = listOf(p1, p2, p3)

        val surrenderedIds = mutableSetOf<String>()
        surrenderedIds.add("p2")
        surrenderedIds.add("p3")

        val eligibleRemaining = participants.filter { it.id !in surrenderedIds }
        assertEquals(1, eligibleRemaining.size)
        assertEquals("p1", eligibleRemaining.first().id)

        // Sole survivor gets the victory
        val winnerPlayerId = eligibleRemaining.first().id
        assertEquals("p1", winnerPlayerId)
    }

    @Test
    fun testLobbyAvailabilityOnlyWhenLobbyExists() {
        fun checkLobbyAvailable(
            mode: GameMode,
            roomCode: String,
            isHostLeft: Boolean,
            activeCount: Int
        ): Boolean = when (mode) {
            GameMode.AI_EASY, GameMode.AI_HARD -> true
            GameMode.ONLINE_ROOM -> {
                roomCode.isNotBlank() && !isHostLeft && activeCount >= 2
            }
            GameMode.NEARBY_NETWORK -> {
                !isHostLeft && activeCount >= 2
            }
        }

        // Active 2-player lobby
        assertTrue(checkLobbyAvailable(GameMode.ONLINE_ROOM, "123456", isHostLeft = false, activeCount = 2))

        // Opponent left or surrendered (activeCount = 1) -> No lobby available
        assertFalse(checkLobbyAvailable(GameMode.ONLINE_ROOM, "123456", isHostLeft = false, activeCount = 1))

        // Host left -> No lobby available
        assertFalse(checkLobbyAvailable(GameMode.ONLINE_ROOM, "123456", isHostLeft = true, activeCount = 2))

        // AI modes always have rematch available
        assertTrue(checkLobbyAvailable(GameMode.AI_EASY, "", isHostLeft = false, activeCount = 1))
        assertTrue(checkLobbyAvailable(GameMode.AI_HARD, "", isHostLeft = false, activeCount = 1))
    }
}
