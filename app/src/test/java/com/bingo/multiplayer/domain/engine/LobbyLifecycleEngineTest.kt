package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.RoomMessagePacket
import org.junit.Assert.*
import org.junit.Test

class LobbyLifecycleEngineTest {

    @Test
    fun testMonotonicVersionStrictlyIncreases() {
        var v = 100L
        for (i in 1..1000) {
            val next = LobbyLifecycleEngine.nextVersion(v)
            assertTrue("Expected $next > $v", next > v)
            v = next
        }
    }

    @Test
    fun testLocalToggleReadyTransitionsAndVersion() {
        val player = Player(
            id = "p1",
            displayName = "Player1",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 100L
        )

        // Toggle to Ready
        val readyPlayer = LobbyLifecycleEngine.onLocalToggleReady(player, isReady = true)
        assertEquals(LobbyLifecycleEngine.STATUS_READY, readyPlayer.lobbyReadyStatus)
        assertTrue(readyPlayer.readyVersion > player.readyVersion)

        // Toggle back to Not Ready
        val unreadyPlayer = LobbyLifecycleEngine.onLocalToggleReady(readyPlayer, isReady = false)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, unreadyPlayer.lobbyReadyStatus)
        assertTrue(unreadyPlayer.readyVersion > readyPlayer.readyVersion)
    }

    @Test
    fun testRapidToggling1000TimesMaintainsMonotonicity() {
        var player = Player(
            id = "p1",
            displayName = "RapidPlayer",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 1L
        )

        for (i in 1..1000) {
            val shouldBeReady = (i % 2 == 1)
            player = LobbyLifecycleEngine.onLocalToggleReady(player, isReady = shouldBeReady)
            val expectedStatus = if (shouldBeReady) LobbyLifecycleEngine.STATUS_READY else LobbyLifecycleEngine.STATUS_NOT_READY
            assertEquals(expectedStatus, player.lobbyReadyStatus)
        }
        assertTrue(player.readyVersion >= 1001L)
    }

    @Test
    fun testReconcileReadyStatusHigherVersionWins() {
        val (status, ver) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            currentVersion = 100L,
            incomingStatus = LobbyLifecycleEngine.STATUS_READY,
            incomingVersion = 200L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_READY, status)
        assertEquals(200L, ver)
    }

    @Test
    fun testReconcileReadyStatusStaleLowerVersionIgnored() {
        val (status, ver) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_READY,
            currentVersion = 300L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 150L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_READY, status)
        assertEquals(300L, ver)
    }

    @Test
    fun testReconcileReadyStatusActiveOverridesLeftLobbyOnEqualVersion() {
        val (status, _) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY,
            currentVersion = 500L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 500L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, status)
    }

    @Test
    fun testPlayerLeaveAndRejoinLifecycle() {
        val p2 = Player(id = "p2", displayName = "Player2", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY, readyVersion = 100L)

        // Player 2 leaves lobby
        val leftPlayer = LobbyLifecycleEngine.onPlayerLeave(p2)
        assertEquals(LobbyLifecycleEngine.STATUS_LEFT_LOBBY, leftPlayer.lobbyReadyStatus)
        assertTrue(leftPlayer.readyVersion > p2.readyVersion)

        // Player 2 re-enters lobby
        val rejoinedPlayer = LobbyLifecycleEngine.onPlayerJoinSession(
            player = Player(id = "p2", displayName = "Player2", isHost = false),
            existingPlayerInSession = leftPlayer
        )
        // Must be NOT_READY and have version higher than leftPlayer's version
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, rejoinedPlayer.lobbyReadyStatus)
        assertTrue(rejoinedPlayer.readyVersion > leftPlayer.readyVersion)

        // Reconciling with cloud that had leftPlayer must now yield NOT_READY because rejoinedPlayer has higher version
        val (reconciledStatus, reconciledVer) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = leftPlayer.lobbyReadyStatus,
            currentVersion = leftPlayer.readyVersion,
            incomingStatus = rejoinedPlayer.lobbyReadyStatus,
            incomingVersion = rejoinedPlayer.readyVersion
        )
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, reconciledStatus)
        assertEquals(rejoinedPlayer.readyVersion, reconciledVer)
    }

    @Test
    fun testRemoteReadyStatusPacketRejectsStalePackets() {
        val existing = Player(
            id = "p2",
            displayName = "Player2",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY,
            readyVersion = 500L
        )

        // Stale packet with version 400
        val stalePacket = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "p2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 400L
        )
        val result = LobbyLifecycleEngine.onRemoteReadyStatusPacket(stalePacket, existing)
        assertNull("Stale packet must be discarded", result)

        // Newer packet with version 600
        val newerPacket = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "p2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 600L
        )
        val updated = LobbyLifecycleEngine.onRemoteReadyStatusPacket(newerPacket, existing)
        assertNotNull(updated)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, updated?.lobbyReadyStatus)
        assertEquals(600L, updated?.readyVersion)
    }

    @Test
    fun testCanStartMatchValidation() {
        val host = Player(id = "host", displayName = "Host", isHost = true, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        val p2 = Player(id = "p2", displayName = "P2", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY)

        // Only host: cannot start
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host)))

        // Host + unready P2: cannot start
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host, p2)))
        assertEquals(1, LobbyLifecycleEngine.countReadyPlayers(listOf(host, p2)))

        // P2 ready: can start
        val readyP2 = p2.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        assertTrue(LobbyLifecycleEngine.canStartMatch(listOf(host, readyP2)))
        assertEquals(2, LobbyLifecycleEngine.countReadyPlayers(listOf(host, readyP2)))

        // P2 left lobby: cannot start
        val leftP2 = p2.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY)
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host, leftP2)))
    }

    @Test
    fun testIsPlayerLeft() {
        val p = Player(
            id = "p2",
            displayName = "P2",
            isHost = false,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        assertTrue(LobbyLifecycleEngine.isPlayerLeft(p, isMe = false, rawPresenceStatus = "online"))

        val activePlayer = p.copy(
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        assertFalse(LobbyLifecycleEngine.isPlayerLeft(activePlayer, isMe = false, rawPresenceStatus = "online"))

        val timedOutPlayer = p.copy(
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            lastSeenTimestamp = System.currentTimeMillis() - 70_000L
        )
        assertTrue(LobbyLifecycleEngine.isPlayerLeft(timedOutPlayer, isMe = false, rawPresenceStatus = "offline"))
        // If it's me, should not mark left just due to presence delay
        assertFalse(LobbyLifecycleEngine.isPlayerLeft(timedOutPlayer, isMe = true, rawPresenceStatus = "offline"))
    }

    @Test
    fun testLocalStatusChangeInGameAndReviewingBoard() {
        val player = Player(
            id = "p1",
            displayName = "Player1",
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY,
            readyVersion = 50L
        )

        // Game starts -> player enters STATUS_IN_GAME
        val inGamePlayer = LobbyLifecycleEngine.onLocalStatusChange(player, LobbyLifecycleEngine.STATUS_IN_GAME)
        assertEquals(LobbyLifecycleEngine.STATUS_IN_GAME, inGamePlayer.lobbyReadyStatus)
        assertTrue(inGamePlayer.readyVersion > player.readyVersion)

        // While player is reviewing board (still in STATUS_IN_GAME), getPlayerLobbyStatus must return IN_GAME (⌛)
        val status = LobbyLifecycleEngine.getPlayerLobbyStatus(inGamePlayer, isMe = false, rawPresenceStatus = "online")
        assertEquals(LobbyLifecycleEngine.PlayerLobbyStatus.IN_GAME, status)

        // Host cannot start match while player is still reviewing board
        val host = Player(id = "host", displayName = "Host", isHost = true, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        assertFalse(LobbyLifecycleEngine.canStartMatch(listOf(host, inGamePlayer)))
        assertEquals(1, LobbyLifecycleEngine.countReadyPlayers(listOf(host, inGamePlayer)))

        // Player returns to lobby -> transitions to NOT_READY (⏸️) with advanced version
        val returnedPlayer = LobbyLifecycleEngine.onLocalStatusChange(inGamePlayer, LobbyLifecycleEngine.STATUS_NOT_READY)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, returnedPlayer.lobbyReadyStatus)
        assertTrue(returnedPlayer.readyVersion > inGamePlayer.readyVersion)

        // Remote reconciliation must accept the new NOT_READY state over previous IN_GAME
        val (reconciledStatus, reconciledVer) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = inGamePlayer.lobbyReadyStatus,
            currentVersion = inGamePlayer.readyVersion,
            incomingStatus = returnedPlayer.lobbyReadyStatus,
            incomingVersion = returnedPlayer.readyVersion
        )
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, reconciledStatus)
        assertEquals(returnedPlayer.readyVersion, reconciledVer)

        // Now in lobby, status displays NOT_READY (⏸️)
        val postReturnStatus = LobbyLifecycleEngine.getPlayerLobbyStatus(returnedPlayer, isMe = false, rawPresenceStatus = "online")
        assertEquals(LobbyLifecycleEngine.PlayerLobbyStatus.NOT_READY, postReturnStatus)
    }

    @Test
    fun testReconcileReadyStatusInGameTiesWinsOverDefault() {
        val (status, _) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_IN_GAME,
            currentVersion = 500L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 500L
        )
        assertEquals(LobbyLifecycleEngine.STATUS_IN_GAME, status)
    }

    @Test
    fun testShouldStartNewMatchRejectsDuplicateWhileActive() {
        val activeSeed = 123456789L

        // While guest is in game and game is not over, duplicate start packet with same seed must be rejected
        val duplicateAllowed = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = activeSeed,
            currentMatchSeed = activeSeed,
            isGameOver = false,
            isCurrentlyInGame = true
        )
        assertFalse("Duplicate START_GAME/PLAY_AGAIN packet during active match must be rejected", duplicateAllowed)

        // Rogue packet with seed = 0 must be rejected
        val rogueZeroSeedAllowed = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = 0L,
            currentMatchSeed = activeSeed,
            isGameOver = false,
            isCurrentlyInGame = true
        )
        assertFalse("Rogue start packet with seed = 0 must be rejected", rogueZeroSeedAllowed)

        // When game is over (e.g. Play Again), new seed must be accepted
        val newSeed = 987654321L
        val playAgainAllowed = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = newSeed,
            currentMatchSeed = activeSeed,
            isGameOver = true,
            isCurrentlyInGame = true
        )
        assertTrue("New match with fresh seed after game over must be accepted", playAgainAllowed)

        // Starting from lobby (isCurrentlyInGame = false) must be accepted
        val lobbyStartAllowed = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = newSeed,
            currentMatchSeed = 0L,
            isGameOver = false,
            isCurrentlyInGame = false
        )
        assertTrue("Initial match start from lobby must be accepted", lobbyStartAllowed)

        // Completed seed returning to lobby must be strictly rejected
        val completedSeedRejected = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = activeSeed,
            currentMatchSeed = 0L,
            isGameOver = false,
            isCurrentlyInGame = false,
            isCompletedSeed = true
        )
        assertFalse("Completed seed must be rejected when returning to lobby", completedSeedRejected)

        // Same seed when game concluded must be strictly rejected
        val sameSeedGameOverRejected = LobbyLifecycleEngine.shouldStartNewMatch(
            isHost = false,
            incomingSeed = activeSeed,
            currentMatchSeed = activeSeed,
            isGameOver = true,
            isCurrentlyInGame = false,
            isCompletedSeed = false
        )
        assertFalse("Same seed must be rejected if match already concluded", sameSeedGameOverRejected)

        // Host always triggers match initiation
        assertTrue(
            "Host always starts",
            LobbyLifecycleEngine.shouldStartNewMatch(
                isHost = true,
                incomingSeed = null,
                currentMatchSeed = 0L,
                isGameOver = false,
                isCurrentlyInGame = false
            )
        )
    }

    @Test
    fun testIsPacketForActiveMatchRejectsStaleMovesAcrossConsecutiveMatches() {
        val match1Seed = 111111L
        val match2Seed = 222222L

        // Moves belonging to active match must be accepted
        assertTrue(LobbyLifecycleEngine.isPacketForActiveMatch(match1Seed, match1Seed))
        assertTrue(LobbyLifecycleEngine.isPacketForActiveMatch(match2Seed, match2Seed))

        // Stale moves delayed over network from match 1 arriving during match 2 must be rejected
        assertFalse(
            "Stale packet from match 1 arriving in match 2 must be discarded",
            LobbyLifecycleEngine.isPacketForActiveMatch(match1Seed, match2Seed)
        )

        // Backward compatibility: unseeded legacy packets (0L or null) accepted
        assertTrue(LobbyLifecycleEngine.isPacketForActiveMatch(0L, match2Seed))
        assertTrue(LobbyLifecycleEngine.isPacketForActiveMatch(null, match2Seed))
    }

    @Test
    fun testResolveBoardSizeWhenDynamicBoardDisabled() {
        // When dynamic board is unchecked/disabled, board size is ALWAYS 5x5 regardless of player count
        for (playerCount in 0..10) {
            val size = LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = false, playerCount = playerCount)
            assertEquals("Unchecked dynamic board must strictly return 5x5 for playerCount $playerCount", 5, size)
        }
    }

    @Test
    fun testResolveBoardSizeWhenDynamicBoardEnabled() {
        // When dynamic board is checked/enabled, board size scales dynamically:
        // 2 players -> 5x5
        // 3 players -> 6x6
        // 4 players -> 7x7
        // 5+ players -> 8x8 (capped at 8)
        assertEquals(5, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 1)) // min coerce
        assertEquals(5, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 2))
        assertEquals(6, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 3))
        assertEquals(7, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 4))
        assertEquals(8, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 5))
        assertEquals(8, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 6))
        assertEquals(8, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 8))
        assertEquals(8, LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard = true, playerCount = 12))
    }

    @Test
    fun testIsPlayerMeIdentification() {
        val player = Player(
            id = "u_alice",
            displayName = "Alice",
            username = "alice",
            isHost = false
        )

        // Exact UID match
        assertTrue(LobbyLifecycleEngine.isPlayerMe(player, myUid = "u_alice"))
        // Prefix stripped UID match
        assertTrue(LobbyLifecycleEngine.isPlayerMe(player, myUid = "alice"))
        // Username match
        assertTrue(LobbyLifecycleEngine.isPlayerMe(player, myUid = "other_uid", myUsername = "@Alice"))
        // Display name match for non-host
        assertTrue(LobbyLifecycleEngine.isPlayerMe(player, myUid = "diff", myUsername = "", myDisplayName = "Alice", isHost = false))
        // Negative test: should not match Bob
        assertFalse(LobbyLifecycleEngine.isPlayerMe(player, myUid = "bob", myUsername = "bob", myDisplayName = "Bob", isHost = false))
    }

    @Test
    fun testResolvePlayerBoardSeedGuaranteesUniqueSeedsAcrossAllPlayers() {
        val baseMatchSeed = 9876543210123L
        val host = Player(id = "p_host", displayName = "Host", username = "host_user", isHost = true)
        val guest1 = Player(id = "p_guest1", displayName = "Guest 1", username = "player_bob", isHost = false)
        val guest2 = Player(id = "p_guest2", displayName = "Guest 2", username = "player_charlie", isHost = false)
        val guest3 = Player(id = "p_guest3", displayName = "Guest 3", username = "player_diana", isHost = false)
        val guest4 = Player(id = "p_guest4", displayName = "Guest 4", username = "player_evan", isHost = false)

        val players = listOf(host, guest1, guest2, guest3, guest4)
        val seeds = players.mapIndexed { index, player ->
            LobbyLifecycleEngine.resolvePlayerBoardSeed(baseMatchSeed, player, index)
        }

        // 1. All seeds must be non-zero
        assertTrue(seeds.all { it != 0L })

        // 2. All seeds must be completely distinct (no collisions)
        assertEquals("Every player must receive a unique board seed", seeds.toSet().size, seeds.size)

        // 3. Test board generation: boards generated with these seeds must have distinct layouts
        val engine = BingoEngine()
        val boards = seeds.map { engine.generateBoard(size = 5, seed = it) }
        val layouts = boards.map { b -> b.cells.map { it.number } }
        assertEquals("Every player must receive a distinct board layout", layouts.toSet().size, boards.size)
    }

    @Test
    fun testResolvePlayerBoardSeedPreventsCollisionsEvenUnderAsymmetricLobbyViews() {
        val baseMatchSeed = 555555555L
        val host = Player(id = "h1", displayName = "Host", username = "host", isHost = true)
        val bob = Player(id = "b1", displayName = "Bob", username = "bob", isHost = false)
        val charlie = Player(id = "c1", displayName = "Charlie", username = "charlie", isHost = false)

        // In a network glitch scenario where Bob's phone saw [Host, Bob] (Bob at index 1),
        // and Charlie's phone also saw [Host, Charlie] (Charlie at index 1):
        val bobSeedAtIdx1 = LobbyLifecycleEngine.resolvePlayerBoardSeed(baseMatchSeed, bob, 1)
        val charlieSeedAtIdx1 = LobbyLifecycleEngine.resolvePlayerBoardSeed(baseMatchSeed, charlie, 1)
        val hostSeedAtIdx0 = LobbyLifecycleEngine.resolvePlayerBoardSeed(baseMatchSeed, host, 0)

        assertNotEquals("Host and Bob must never share a seed", hostSeedAtIdx0, bobSeedAtIdx1)
        assertNotEquals("Host and Charlie must never share a seed", hostSeedAtIdx0, charlieSeedAtIdx1)
        assertNotEquals(
            "Bob and Charlie must NEVER share a seed even if both evaluate at index 1",
            bobSeedAtIdx1,
            charlieSeedAtIdx1
        )

        val engine = BingoEngine()
        val bobBoard = engine.generateBoard(size = 5, seed = bobSeedAtIdx1)
        val charlieBoard = engine.generateBoard(size = 5, seed = charlieSeedAtIdx1)
        assertNotEquals(
            "Bob and Charlie must receive different boards",
            bobBoard.cells.map { it.number },
            charlieBoard.cells.map { it.number }
        )
    }

    @Test
    fun testJoinPacketDoesNotDemoteAlreadyReadyPlayer() {
        val readyPlayer = Player(
            id = "p2",
            displayName = "Player 2",
            username = "player2",
            isHost = false,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY,
            readyVersion = 200L
        )

        // Delayed or retried JOIN packet arrives with default NOT_READY status and version 100
        val delayedJoinPacket = RoomMessagePacket(
            type = "JOIN",
            playerId = "p2",
            displayName = "Player 2",
            username = "player2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 100L
        )

        val updated = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(delayedJoinPacket, readyPlayer)
        assertEquals(
            "Player already marked READY must NOT be demoted by delayed/retry JOIN packet",
            LobbyLifecycleEngine.STATUS_READY,
            updated.lobbyReadyStatus
        )
        assertTrue(updated.readyVersion >= readyPlayer.readyVersion)
    }

    @Test
    fun testClockSkewDoesNotBlockReadyStatusTransition() {
        // Host has clock 500L, Guest phone has clock 400L (100ms behind)
        val guestJoinPacket = RoomMessagePacket(
            type = "JOIN",
            playerId = "p2",
            displayName = "Player 2",
            username = "player2",
            readyStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            readyVersion = 400L
        )

        // Host processes JOIN packet from guest
        val initialPlayerOnHost = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(guestJoinPacket, existing = null)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, initialPlayerOnHost.lobbyReadyStatus)
        assertEquals("Host must use sender's version counter, not host's inflated clock", 400L, initialPlayerOnHost.readyVersion)

        // Guest now taps "I'm ready" on guest phone (clock reaches 401L)
        val readyPacket = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "p2",
            displayName = "Player 2",
            username = "player2",
            readyStatus = LobbyLifecycleEngine.STATUS_READY,
            readyVersion = 401L
        )

        // Host processes guest's READY_STATUS packet
        val readyPlayerOnHost = LobbyLifecycleEngine.onRemoteReadyStatusPacket(readyPacket, initialPlayerOnHost)
        assertNotNull("Guest ready packet must NOT be discarded", readyPlayerOnHost)
        assertEquals(LobbyLifecycleEngine.STATUS_READY, readyPlayerOnHost?.lobbyReadyStatus)
        assertEquals(401L, readyPlayerOnHost?.readyVersion)
    }

    @Test
    fun testVersionTieGivesReadyPrecedenceOverNotReady() {
        val (status, ver) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_READY,
            currentVersion = 1000L,
            incomingStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            incomingVersion = 1000L
        )
        assertEquals("Affirmative READY must take precedence over default NOT_READY on version tie", LobbyLifecycleEngine.STATUS_READY, status)
        assertEquals(1000L, ver)

        // Reverse order test: incoming is READY, current is NOT_READY on equal version
        val (reverseStatus, reverseVer) = LobbyLifecycleEngine.reconcileReadyStatus(
            currentStatus = LobbyLifecycleEngine.STATUS_NOT_READY,
            currentVersion = 1000L,
            incomingStatus = LobbyLifecycleEngine.STATUS_READY,
            incomingVersion = 1000L
        )
        assertEquals("Affirmative READY must take precedence over default NOT_READY on version tie", LobbyLifecycleEngine.STATUS_READY, reverseStatus)
        assertEquals(1000L, reverseVer)
    }
}


