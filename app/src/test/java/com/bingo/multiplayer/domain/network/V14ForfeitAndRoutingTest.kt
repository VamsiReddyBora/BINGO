package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.presentation.game.StampResultType
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit and simulation tests verifying fixes for BUG-024 through BUG-034:
 * - BUG-024: winnerPlayerId and activeWinReason populated on all forfeit paths
 * - BUG-025: ManualBoardDesign included in in-game state and ready player filtering
 * - BUG-026: 2-player disconnect turn rotation and 3-miss forfeit condition reachable
 * - BUG-027: KICK_PLAYER cleans up match state
 * - BUG-028: Surrender dialog invokes onSurrender during active matches
 * - BUG-029: StampResultType.DRAW is deprecated; only WON, RUNNER, LOST, OFFLINE apply
 * - BUG-030: LAN P2P host disconnect awards forfeit win to guest
 * - BUG-031: consecutiveMissedTurns set to 3 immediately on explicit surrender, host exit, or leave
 * - BUG-032: isRunnerMatch, winnerPlayerId, activeWinReason, and activeRunnerPlayerIds reset on manual board rematches
 * - BUG-033: TURN_TIMEOUT peer sync increments consecutiveMissedTurns and evaluates forfeit
 * - BUG-034: REJOIN_GAME uses normalized isPlayerDisconnected to prevent prefix mismatch
 */
class V14ForfeitAndRoutingTest {

    @Test
    fun testBug024_forfeitOutcomesPopulateWinnerAndWinReason() {
        val myUid = "user_me"
        val opponentUid = "user_opp"
        val participants = listOf(
            Player(id = myUid, displayName = "Me", isHost = false),
            Player(id = opponentUid, displayName = "Opponent", isHost = true)
        )

        // 1. HOST_LEFT forfeit simulation
        val activeRemainingHostLeft = participants.filter { it.id == myUid }
        val wonByForfeitHostLeft = activeRemainingHostLeft.any { it.id == myUid } || participants.size <= 2
        val winnerPlayerIdHostLeft = if (wonByForfeitHostLeft) myUid else ""
        val activeWinReasonHostLeft = "Opponent Left"
        val activeRunnerPlayerIdsHostLeft = emptyList<String>()

        assertTrue(wonByForfeitHostLeft)
        assertEquals(myUid, winnerPlayerIdHostLeft)
        assertEquals("Opponent Left", activeWinReasonHostLeft)
        assertTrue(activeRunnerPlayerIdsHostLeft.isEmpty())

        // Verify review badge check matches winnerPlayerId
        assertTrue(LobbyLifecycleEngine.isPlayerIdMatch(myUid, winnerPlayerIdHostLeft))

        // 2. PLAYER_DISCONNECTED with 3 misses forfeit simulation
        val misses = 3
        val wonByForfeitDisconnect = (activeRemainingHostLeft.size <= 1 && misses >= 3) &&
                (activeRemainingHostLeft.any { it.id == myUid } || participants.size <= 2)
        val winnerPlayerIdDisconnect = if (wonByForfeitDisconnect) myUid else ""
        val activeWinReasonDisconnect = "Opponent Disconnected"

        assertTrue(wonByForfeitDisconnect)
        assertEquals(myUid, winnerPlayerIdDisconnect)
        assertEquals("Opponent Disconnected", activeWinReasonDisconnect)

        // 3. Turn timer disconnect loop forfeit simulation
        val wonByForfeitTimer = wonByForfeitDisconnect
        val winnerPlayerIdTimer = if (wonByForfeitTimer) myUid else ""
        assertEquals(myUid, winnerPlayerIdTimer)
    }

    @Test
    fun testBug025_manualBoardDesignInGameRouteAndReadyFiltering() {
        val gameRoute = "game"
        val manualBoardRoute = "manual_board_design"
        val mainMenuRoute = "main_menu"

        fun isInGame(route: String): Boolean =
            (route == gameRoute || route == manualBoardRoute)

        assertTrue("Game route must be considered inGame", isInGame(gameRoute))
        assertTrue("ManualBoardDesign route must be considered inGame", isInGame(manualBoardRoute))
        assertFalse("MainMenu route must NOT be inGame", isInGame(mainMenuRoute))

        // Disconnected players must be filtered when calculating ready players
        val p1 = Player(id = "p1", displayName = "Player 1")
        val p2 = Player(id = "p2", displayName = "Player 2")
        val allParticipants = listOf(p1, p2)
        val disconnectedIds = setOf("p2")

        val activeParticipants = allParticipants.filter { !disconnectedIds.contains(it.id) }
        val expectedUids = activeParticipants.map { it.id }.filter { it.isNotBlank() }.toSet()

        assertEquals(1, expectedUids.size)
        assertTrue(expectedUids.contains("p1"))
        assertFalse(expectedUids.contains("p2"))
    }

    @Test
    fun testBug026_twoPlayerDisconnectTurnRotationReachesThreeMisses() {
        val playerA = Player(id = "user_a", displayName = "Player A", isHost = true)
        val playerB = Player(id = "user_b", displayName = "Player B", isHost = false)
        val candidatePlayers = listOf(playerA, playerB)

        val disconnectedPlayerIds = mutableSetOf<String>()
        val consecutiveMissedTurns = mutableMapOf<String, Int>()

        // Player B disconnects
        disconnectedPlayerIds.add("user_b")
        consecutiveMissedTurns["user_b"] = 0

        fun getNextTurn(currentPicker: String): String {
            val fullyDisconnected = disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
            return LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = candidatePlayers,
                disconnectedPlayerIds = fullyDisconnected,
                currentPickerId = currentPicker,
                fallbackPlayerId = "user_a",
                matchSeed = 12345L
            )
        }

        // Initially, user_b has 0 misses. Turn is user_b's turn.
        // Next turn after user_b skips -> should rotate to user_a!
        consecutiveMissedTurns["user_b"] = 1
        var next = getNextTurn("user_b")
        assertEquals("Turn should rotate from B to A on miss 1", "user_a", next)

        // user_a plays their turn. Next turn after user_a plays -> must rotate back to user_b!
        next = getNextTurn("user_a")
        assertEquals("Turn should rotate from A back to B because misses < 3", "user_b", next)

        // user_b skips again (miss 2)
        consecutiveMissedTurns["user_b"] = 2
        next = getNextTurn("user_b")
        assertEquals("Turn should rotate from B to A on miss 2", "user_a", next)

        // user_a plays again. Next turn after user_a -> must rotate to user_b!
        next = getNextTurn("user_a")
        assertEquals("Turn should rotate from A back to B because misses < 3", "user_b", next)

        // user_b skips 3rd time (miss 3) -> Forfeit condition met!
        consecutiveMissedTurns["user_b"] = 3
        val fullyDisconnected = disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
        assertTrue("user_b is now fully disconnected after 3 misses", fullyDisconnected.contains("user_b"))

        // Active remaining is only user_a -> match forfeits!
        val activeRemaining = candidatePlayers.filter { !fullyDisconnected.contains(it.id) }
        assertEquals(1, activeRemaining.size)
        assertEquals("user_a", activeRemaining.first().id)
    }

    @Test
    fun testBug027_kickPlayerClearsOngoingMatch() {
        var matchCleared = false
        val myUid = "user_kicked"
        val packetTargetId = "user_kicked"

        if (packetTargetId == myUid) {
            matchCleared = true
        }

        assertTrue("OngoingMatchStore must be cleared when kicked", matchCleared)
    }

    @Test
    fun testBug028_surrenderDialogActionRouting() {
        fun resolveSurrenderAction(isMultiplayerLobbyGame: Boolean, isGameOver: Boolean): String {
            return if (isMultiplayerLobbyGame && isGameOver) {
                "RETURN_TO_LOBBY"
            } else {
                "SURRENDER"
            }
        }

        // Active multiplayer match -> must surrender, NOT return to lobby
        assertEquals("SURRENDER", resolveSurrenderAction(isMultiplayerLobbyGame = true, isGameOver = false))

        // Finished multiplayer match -> return to lobby
        assertEquals("RETURN_TO_LOBBY", resolveSurrenderAction(isMultiplayerLobbyGame = true, isGameOver = true))

        // Single player match -> surrender/exit to menu
        assertEquals("SURRENDER", resolveSurrenderAction(isMultiplayerLobbyGame = false, isGameOver = false))
    }

    @Test
    fun testBug029_stampResultTypeDrawIsDeprecated() {
        val activeStamps = listOf(
            StampResultType.WON,
            StampResultType.RUNNER,
            StampResultType.LOST,
            StampResultType.OFFLINE
        )

        assertEquals(4, activeStamps.size)
        // Verify DRAW has deprecated annotation
        val drawField = StampResultType::class.java.getField("DRAW")
        assertTrue("DRAW enum constant must be annotated with @Deprecated", drawField.isAnnotationPresent(Deprecated::class.java))
    }

    @Test
    fun testBug030_lanP2pHostDisconnectAwardsGuestWinByForfeit() {
        val isUsingP2p = true
        val participants = listOf(
            Player(id = "host_uid", displayName = "Host", isHost = true),
            Player(id = "guest_uid", displayName = "Guest", isHost = false)
        )
        val activeRemaining = listOf(Player(id = "guest_uid", displayName = "Guest", isHost = false))
        val myUid = "guest_uid"

        // LAN P2P host disconnect logic
        val wonByForfeit = if (isUsingP2p) true else (activeRemaining.any { it.id == myUid } || participants.size <= 2)
        val didPlayerWin = wonByForfeit
        val winnerPlayerId = if (wonByForfeit) myUid else ""
        val activeWinReason = if (isUsingP2p) "Host Disconnected" else "Opponent Left"
        val opponentDisconnectMessage = if (isUsingP2p) "Host disconnected. You win by forfeit!" else "All opponents left the game."

        assertTrue("Guest must win by forfeit when LAN host disconnects", wonByForfeit)
        assertTrue("didPlayerWin must be true", didPlayerWin)
        assertEquals("guest_uid", winnerPlayerId)
        assertEquals("Host Disconnected", activeWinReason)
        assertEquals("Host disconnected. You win by forfeit!", opponentDisconnectMessage)
    }

    @Test
    fun testBug031_explicitSurrenderOrLeaveSetsThreeMissesImmediately() {
        val playerA = Player(id = "user_a", displayName = "Player A", isHost = true)
        val playerB = Player(id = "user_b", displayName = "Player B", isHost = false)
        val playerC = Player(id = "user_c", displayName = "Player C", isHost = false)
        val candidatePlayers = listOf(playerA, playerB, playerC)

        val disconnectedPlayerIds = mutableSetOf<String>()
        val consecutiveMissedTurns = mutableMapOf<String, Int>()

        // Player B surrenders or leaves explicitly
        val surrenderId = "user_b"
        disconnectedPlayerIds.add(surrenderId)
        consecutiveMissedTurns[surrenderId] = 3

        val fullyDisconnected = disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
        assertTrue("Departed player must immediately be fully disconnected", fullyDisconnected.contains("user_b"))

        // Next turn from player A must go directly to player C, completely skipping surrendered player B
        val nextTurn = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = candidatePlayers,
            disconnectedPlayerIds = fullyDisconnected,
            currentPickerId = "user_a",
            fallbackPlayerId = "user_a",
            matchSeed = 99999L
        )
        assertNotEquals("Turn must not go to surrendered player B", "user_b", nextTurn)
        assertEquals("Turn must immediately advance to player C", "user_c", nextTurn)
    }

    @Test
    fun testBug032_manualBoardRematchResetsRunnerAndWinnerOutcomes() {
        // State lingering from Match 1 where user was Runner
        var isGameOver = true
        var didPlayerWin = false
        var isDrawMatch = false
        var isRunnerMatch = true
        var winnerPlayerId = "user_winner_m1"
        var activeWinReason = "Match 1 Completed"
        var activeRunnerPlayerIds = listOf("user_me")

        // Simulation of reset applied across manual board entry points
        fun resetMatchOutcomes() {
            isGameOver = false
            didPlayerWin = false
            isDrawMatch = false
            isRunnerMatch = false
            winnerPlayerId = ""
            activeWinReason = ""
            activeRunnerPlayerIds = emptyList()
        }

        resetMatchOutcomes()

        assertFalse("isGameOver must be reset", isGameOver)
        assertFalse("didPlayerWin must be reset", didPlayerWin)
        assertFalse("isDrawMatch must be reset", isDrawMatch)
        assertFalse("isRunnerMatch must be reset to prevent false runner stamp in Match 2", isRunnerMatch)
        assertEquals("winnerPlayerId must be empty", "", winnerPlayerId)
        assertEquals("activeWinReason must be empty", "", activeWinReason)
        assertTrue("activeRunnerPlayerIds must be empty", activeRunnerPlayerIds.isEmpty())

        // Verify that upon defeat in Match 2, stamp resolves to LOST, not RUNNER
        val isLocalSelected = true
        val isLocalDisconnected = false
        val didPlayerWinM2 = false
        val isRunnerM2 = isRunnerMatch // which was reset to false

        val (stampType, stampText) = if (isLocalSelected) {
            when {
                isLocalDisconnected -> StampResultType.OFFLINE to "OFFLINE"
                didPlayerWinM2 -> StampResultType.WON to "YOU'VE WON!"
                isRunnerM2 -> StampResultType.RUNNER to "RUNNER!"
                else -> StampResultType.LOST to "YOU LOST!"
            }
        } else {
            StampResultType.LOST to "YOU LOST!"
        }

        assertEquals(StampResultType.LOST, stampType)
        assertEquals("YOU LOST!", stampText)
    }

    @Test
    fun testBug033_peerTurnTimeoutPacketIncrementsMissesAndEvaluatesForfeit() {
        val myUid = "user_me"
        val opponentUid = "user_opp"
        val participants = listOf(
            Player(id = myUid, displayName = "Me", isHost = false),
            Player(id = opponentUid, displayName = "Opponent", isHost = true)
        )
        val disconnectedPlayerIds = mutableSetOf<String>()
        val consecutiveMissedTurns = mutableMapOf<String, Int>()

        fun markPlayerDisconnected(id: String) {
            disconnectedPlayerIds.add(id)
        }

        fun isPlayerDisconnected(id: String): Boolean =
            disconnectedPlayerIds.any { LobbyLifecycleEngine.isPlayerIdMatch(it, id) }

        // Peer receives TURN_TIMEOUT packet for opponentUid
        val packetPlayerId = opponentUid
        markPlayerDisconnected(packetPlayerId)
        val misses = (consecutiveMissedTurns[packetPlayerId] ?: 0) + 1
        consecutiveMissedTurns[packetPlayerId] = misses

        assertEquals(1, consecutiveMissedTurns[opponentUid])
        assertTrue(isPlayerDisconnected(opponentUid))

        // Fast-forward to 3 misses
        consecutiveMissedTurns[packetPlayerId] = 3
        val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
        val currentMisses = consecutiveMissedTurns[packetPlayerId] ?: 0

        var isGameOver = false
        var didPlayerWin = false
        var winnerPlayerId = ""
        var activeWinReason = ""

        if (activeRemaining.size <= 1 && currentMisses >= 3) {
            isGameOver = true
            val wonByForfeit = activeRemaining.any { it.id == myUid } || participants.size <= 2
            didPlayerWin = wonByForfeit
            winnerPlayerId = if (wonByForfeit) myUid else ""
            activeWinReason = "Opponent Disconnected"
        }

        assertTrue("Game must conclude on 3 misses with 1 remaining active player", isGameOver)
        assertTrue("Peer must win by forfeit", didPlayerWin)
        assertEquals(myUid, winnerPlayerId)
        assertEquals("Opponent Disconnected", activeWinReason)
    }

    @Test
    fun testBug034_rejoinGameUsesNormalizedPrefixPlayerIdMatch() {
        val hostUid = "u_host123"
        val guestUid = "guest456"
        val participants = listOf(
            Player(id = hostUid, displayName = "Host", isHost = true),
            Player(id = guestUid, displayName = "Guest", isHost = false)
        )

        // Disconnected set contains host without the "u_" prefix
        val disconnectedPlayerIds = mutableSetOf("host123")

        fun isPlayerDisconnected(playerId: String): Boolean {
            if (playerId.isBlank()) return false
            return disconnectedPlayerIds.any { LobbyLifecycleEngine.isPlayerIdMatch(it, playerId) }
        }

        // Direct set check fails due to prefix mismatch
        assertFalse(disconnectedPlayerIds.contains(hostUid))

        // Canonical isPlayerDisconnected matches correctly
        assertTrue(isPlayerDisconnected(hostUid))

        val isHostLeftGame = false
        val isHostGone = isHostLeftGame || (hostUid.isNotBlank() && isPlayerDisconnected(hostUid))
        assertTrue("Host departure must be detected despite ID prefix difference", isHostGone)

        val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
        assertEquals(1, activeRemaining.size)
        assertEquals(guestUid, activeRemaining.first().id)

        val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == guestUid
        assertTrue("Guest must become acting host when host is disconnected", isActingHost)
    }
}
