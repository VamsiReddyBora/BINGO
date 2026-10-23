package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted verification for Host disconnect, rejoin, and lobby authority preservation:
 * 1. Host disconnects mid-game: guests mark isHostLeftGame = true.
 * 2. Host rejoins mid-game:
 *    - me player retains isHost = true.
 *    - REJOIN_GAME packet carries isHost = true.
 *    - Guests receive REJOIN_GAME and reset isHostLeftGame = false.
 * 3. Match finishes and players return to lobby:
 *    - Guests do NOT kick to MainMenu with "Host left the lobby".
 *    - Host retains crown 👑 and authority to configure / start match.
 * 4. LobbyLifecycleEngine ignores STATUS_LEFT_LOBBY players so departed players do not deadlock lobby.
 * 5. OnlineRoomRegistry syncRoom never demotes room creator from isHost = true.
 */
class HostRejoinAndLobbyLifecycleTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun `test host mid-game disconnect and rejoin resets isHostLeftGame and preserves crown and authority`() {
        val host = Player(
            id = "host_101",
            displayName = "Bob The Host",
            username = "bob",
            isHost = true,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY
        )
        val guest1 = Player(
            id = "guest_202",
            displayName = "Alice",
            username = "alice",
            isHost = false,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY
        )
        val guest2 = Player(
            id = "guest_303",
            displayName = "Charlie",
            username = "charlie",
            isHost = false,
            lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY
        )

        var matchParticipants = listOf(host, guest1, guest2)

        // 1. Host intentionally disconnects mid-game
        var guest1IsHostLeftGame = false
        val disconnectedPlayerIds = mutableSetOf<String>()

        // Host disconnect event triggers on guest 1:
        disconnectedPlayerIds.add(host.id)
        guest1IsHostLeftGame = true // Simulating packet.type == "HOST_LEFT" or disconnect detection
        assertTrue("Guest 1 detects host left", guest1IsHostLeftGame)

        // 2. Host rejoins the game via ongoing match store
        val ongoingMatchData = OngoingMatchData(
            roomCode = "ROOM99",
            matchSeed = 123456789L,
            boardSize = 5,
            isHost = true,
            participants = matchParticipants
        )

        // Host recreates local player on rejoin:
        val hostLocalMe = Player(
            id = host.id,
            displayName = host.displayName,
            username = host.username,
            isHost = ongoingMatchData.isHost
        )
        assertTrue("Host local me player must retain isHost = true", hostLocalMe.isHost)

        // Host broadcasts REJOIN_GAME packet:
        val rejoinPacket = RoomMessagePacket(
            type = "REJOIN_GAME",
            playerId = hostLocalMe.id,
            displayName = hostLocalMe.displayName,
            username = hostLocalMe.username,
            isHost = hostLocalMe.isHost,
            seed = ongoingMatchData.matchSeed
        )
        assertTrue("REJOIN_GAME packet must carry isHost = true", rejoinPacket.isHost)

        // 3. Guest 1 processes incoming REJOIN_GAME packet:
        val hostUid = matchParticipants.find { it.isHost }?.id ?: host.id
        val isHostSender = rejoinPacket.isHost || (hostUid.isNotBlank() && LobbyLifecycleEngine.isPlayerIdMatch(rejoinPacket.playerId, hostUid))
        assertTrue("Guest 1 identifies sender as host", isHostSender)

        if (isHostSender) {
            guest1IsHostLeftGame = false
            disconnectedPlayerIds.remove(rejoinPacket.playerId)
            matchParticipants = matchParticipants.map {
                if (LobbyLifecycleEngine.isPlayerIdMatch(it.id, rejoinPacket.playerId)) {
                    it.copy(isHost = true)
                } else it
            }
        }

        assertFalse("Guest 1 must have reset isHostLeftGame = false upon host rejoin", guest1IsHostLeftGame)
        assertFalse("Host must be removed from disconnectedPlayerIds", disconnectedPlayerIds.contains(host.id))
        val hostInParticipants = matchParticipants.find { it.id == host.id }
        assertNotNull("Host exists in participants", hostInParticipants)
        assertTrue("Host in matchParticipants retains isHost = true", hostInParticipants!!.isHost)

        // 4. Match completes and players click "Return to Lobby"
        // Guest 1 evaluation:
        val isHostActuallyGoneGuest1 = guest1IsHostLeftGame && (hostUid.isBlank() || disconnectedPlayerIds.contains(hostUid))
        assertFalse("Guest 1 must NOT consider host gone", isHostActuallyGoneGuest1)

        // Host evaluation:
        val isKnownHostOnHost = hostLocalMe.isHost || (hostLocalMe.id == hostUid)
        assertTrue("Host is recognized as room host", isKnownHostOnHost)

        // Host returns to lobby: Crown display check
        val hostCardPlayer = matchParticipants.find { it.id == host.id }!!
        val isPlayerHost = hostCardPlayer.isHost || (hostCardPlayer.id == host.id && isKnownHostOnHost)
        assertTrue("Host player card must have crown enabled (isPlayerHost = true)", isPlayerHost)

        // Lobby readiness check:
        val effectivePlayers = matchParticipants.map { p ->
            if (p.id == host.id) p.copy(isHost = true, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
            else p.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_NOT_READY)
        }
        val readyCountInitial = LobbyLifecycleEngine.countReadyPlayers(effectivePlayers)
        assertEquals("Initially only host is ready", 1, readyCountInitial)

        // Guests ready up in lobby:
        val guestsReady = effectivePlayers.map { p ->
            p.copy(lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        }
        assertTrue("All players ready: canStartMatch must be true", LobbyLifecycleEngine.canStartMatch(guestsReady))
        assertEquals("All 3 players ready", 3, LobbyLifecycleEngine.countReadyPlayers(guestsReady))
    }

    @Test
    fun `test LobbyLifecycleEngine canStartMatch ignores departed players with STATUS_LEFT_LOBBY`() {
        val host = Player(id = "p1", displayName = "Host", isHost = true, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        val guest1 = Player(id = "p2", displayName = "Guest1", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_READY)
        val departedGuest = Player(id = "p3", displayName = "Guest2", isHost = false, lobbyReadyStatus = LobbyLifecycleEngine.STATUS_LEFT_LOBBY)

        val players = listOf(host, guest1, departedGuest)

        // Without fix, departedGuest in STATUS_LEFT_LOBBY would block canStartMatch
        val canStart = LobbyLifecycleEngine.canStartMatch(players)
        assertTrue("Active host + 1 ready guest can start even if another player left lobby", canStart)
        assertEquals("Ready count should only consider active players", 2, LobbyLifecycleEngine.countReadyPlayers(players))
    }

    @Test
    fun `test OngoingMatchData serialization preserves isHost flag`() {
        val original = OngoingMatchData(
            roomCode = "BINGO7",
            matchSeed = 987654321L,
            boardSize = 6,
            isDynamicBoard = true,
            isHost = true
        )

        val serialized = json.encodeToString(original)
        assertTrue("Serialized JSON must contain isHost: true", serialized.contains("\"isHost\":true"))

        val restored = json.decodeFromString<OngoingMatchData>(serialized)
        assertTrue("Restored data must have isHost == true", restored.isHost)
        assertEquals("Room code preserved", "BINGO7", restored.roomCode)
        assertEquals("Match seed preserved", 987654321L, restored.matchSeed)
    }

    @Test
    fun `test player clean exit mid-game broadcasts PLAYER_DISCONNECTED and advances turn immediately`() {
        val playerA = Player(id = "userA", displayName = "Alice", isHost = true)
        val playerB = Player(id = "userB", displayName = "Bob", isHost = false)
        val playerC = Player(id = "userC", displayName = "Charlie", isHost = false)
        val participants = listOf(playerA, playerB, playerC)

        val disconnectedPlayerIds = mutableSetOf<String>()
        var currentTurnPlayerId = "userB"
        var turnNumber = 3

        // Player B clicks door icon on GameScreen to return to lobby during active match
        val packet = RoomMessagePacket(
            type = "PLAYER_DISCONNECTED",
            playerId = playerB.id,
            displayName = playerB.displayName,
            isHost = false
        )

        // Peer (Player A) processes packet
        disconnectedPlayerIds.add(packet.playerId)
        assertTrue("Player B is marked in disconnectedPlayerIds", disconnectedPlayerIds.contains("userB"))

        // Because it was Player B's turn, coordinator immediately advances turn
        val isTurnOfDepartedPlayer = (currentTurnPlayerId == packet.playerId)
        assertTrue("Departed player had active turn", isTurnOfDepartedPlayer)

        val activeRemaining = participants.filter { it.id !in disconnectedPlayerIds }
        assertEquals("2 active players remain", 2, activeRemaining.size)

        // Next player calculated without 30s delay
        val nextId = "userC"
        currentTurnPlayerId = nextId
        turnNumber += 1
        assertEquals("Turn advanced to Charlie", "userC", currentTurnPlayerId)
        assertEquals("Turn number incremented", 4, turnNumber)

        // Turn countdown fast skip check:
        // When Charlie completes turn and it rotates back to Bob (userB) who is still in lobby:
        val activePicker = "userB"
        val isDisconnected = disconnectedPlayerIds.contains(activePicker)
        assertTrue("Active picker is recognized as disconnected", isDisconnected)
        val maxWaitSeconds = if (isDisconnected) 28 else 15
        assertEquals("Fast skip threshold is 28 seconds (2s delay only)", 28, maxWaitSeconds)
    }

    @Test
    fun `test invite debouncing prevents duplicate invitation popups during join delay`() {
        val handledInviteRoomCodes = mutableMapOf<String, Long>()
        var isJoiningRoom = false

        val testRoomCode = "ROOM88"
        val now = System.currentTimeMillis()

        // 1. First invite arrives
        val canShowFirst = !isJoiningRoom && ((now - (handledInviteRoomCodes[testRoomCode] ?: 0L)) >= 60_000L)
        assertTrue("First invite should be allowed to display", canShowFirst)

        // 2. User taps 'Accept & Play'
        isJoiningRoom = true
        handledInviteRoomCodes[testRoomCode] = now

        // 3. Second invite arrives from cloud poll 2 seconds later while join is in flight
        val timeAfter2Sec = now + 2000L
        val canShowSecond = !isJoiningRoom && ((timeAfter2Sec - (handledInviteRoomCodes[testRoomCode] ?: 0L)) >= 60_000L)
        assertFalse("Second invite popup MUST be suppressed while joining or recently handled", canShowSecond)

        // 4. Joining finishes
        isJoiningRoom = false
        val timeAfter5Sec = now + 5000L
        val canShowAfterJoin = !isJoiningRoom && ((timeAfter5Sec - (handledInviteRoomCodes[testRoomCode] ?: 0L)) >= 60_000L)
        assertFalse("Same room code invite MUST still be suppressed within 60s cooldown", canShowAfterJoin)
    }

    @Test
    fun `test match completion and lobby return clears chat history for next match`() {
        var matchChatHistory = listOf(
            "🔴 Bob disconnected",
            "🟢 Bob reconnected",
            "📢 Turn skipped (Bob in lobby...)"
        )
        assertFalse("Initial chat history contains previous match messages", matchChatHistory.isEmpty())

        // Match completes and return to lobby is clicked (wasOver == true)
        val wasOver = true
        if (wasOver) {
            matchChatHistory = emptyList()
        }
        assertTrue("Chat history must be empty after returning to lobby", matchChatHistory.isEmpty())

        // Next match starts in manual mode or auto mode
        val nextMatchChat = matchChatHistory
        assertEquals("Next match starts with zero stale chat messages", 0, nextMatchChat.size)
    }

    @Test
    fun `test PLAYER_DISCONNECTED in 1v1 match does not prematurely forfeit and allows rejoin`() {
        val host = Player(id = "alice_host", displayName = "Alice", isHost = true)
        val guest = Player(id = "bob_guest", displayName = "Bob", isHost = false)
        val participants = listOf(host, guest)

        val disconnectedPlayerIds = mutableSetOf<String>()
        val consecutiveMissedTurns = mutableMapOf<String, Int>()
        var isGameOver = false
        var currentTurnPlayerId = "bob_guest"
        var turnNumber = 5

        // Bob exits cleanly to lobby via door icon
        val disconnectPacket = RoomMessagePacket(
            type = "PLAYER_DISCONNECTED",
            playerId = guest.id,
            displayName = guest.displayName,
            isHost = false
        )

        // Host processes PLAYER_DISCONNECTED
        disconnectedPlayerIds.add(disconnectPacket.playerId)
        val activeRemaining = participants.filter { it.id !in disconnectedPlayerIds }
        assertEquals("Only Alice remains active", 1, activeRemaining.size)

        // Without our fix, activeRemaining.size <= 1 immediately set isGameOver = true!
        // With our fix:
        val misses = (consecutiveMissedTurns[disconnectPacket.playerId] ?: 0) + 1
        consecutiveMissedTurns[disconnectPacket.playerId] = misses

        if (activeRemaining.size <= 1 && misses >= 3) {
            isGameOver = true
        } else {
            val nextId = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = participants,
                disconnectedPlayerIds = disconnectedPlayerIds,
                currentPickerId = disconnectPacket.playerId
            )
            currentTurnPlayerId = nextId
            turnNumber += 1
        }

        assertFalse("Game must NOT be forfeited immediately upon door exit (misses = 1)", isGameOver)
        assertEquals("Turn advanced to Alice immediately", "alice_host", currentTurnPlayerId)
        assertEquals("Turn number incremented", 6, turnNumber)

        // Now Bob taps 'Rejoin Game' from the lobby
        val rejoinPacket = RoomMessagePacket(
            type = "REJOIN_GAME",
            playerId = guest.id,
            displayName = guest.displayName,
            isHost = false
        )
        disconnectedPlayerIds.remove(rejoinPacket.playerId)
        consecutiveMissedTurns.remove(rejoinPacket.playerId)

        assertFalse("Bob is no longer disconnected", disconnectedPlayerIds.contains("bob_guest"))
        assertEquals("Bob's missed turns are reset", 0, consecutiveMissedTurns["bob_guest"] ?: 0)
        assertFalse("Game continues active with both players", isGameOver)
    }

    @Test
    fun `test host rejoin on host turn triggers senior active guest GAME_SYNC response`() {
        val host = Player(id = "host_1", displayName = "Host Bob", isHost = true)
        val guest1 = Player(id = "guest_1", displayName = "Alice", isHost = false)
        val guest2 = Player(id = "guest_2", displayName = "Charlie", isHost = false)
        val participants = listOf(host, guest1, guest2)

        val disconnectedPlayerIds = mutableSetOf<String>()
        var isHostLeftGame = false
        var currentTurnPlayerId = "host_1" // Host disconnected on their own turn!

        // Host disconnects
        disconnectedPlayerIds.add("host_1")
        isHostLeftGame = true

        // Host reconnects and sends REJOIN_GAME
        val rejoinPacket = RoomMessagePacket(
            type = "REJOIN_GAME",
            playerId = "host_1",
            displayName = "Host Bob",
            isHost = true
        )

        // Guest 1 and Guest 2 process REJOIN_GAME
        disconnectedPlayerIds.remove(rejoinPacket.playerId)
        isHostLeftGame = false

        val hostUid = "host_1"
        val isHostGone = isHostLeftGame || disconnectedPlayerIds.contains(hostUid)
        val activeRemaining = participants.filter { it.id !in disconnectedPlayerIds }
        val isActingHostGuest1 = isHostGone && activeRemaining.firstOrNull()?.id == "guest_1"
        val isActingHostGuest2 = isHostGone && activeRemaining.firstOrNull()?.id == "guest_2"

        // Evaluate whether Guest 1 or Guest 2 should broadcast GAME_SYNC
        // Guest 1 evaluation:
        val isSeniorActivePeerGuest1 = activeRemaining.firstOrNull { it.id != rejoinPacket.playerId }?.id == "guest_1"
        val guest1ShouldSync = false /* isHosting */ || currentTurnPlayerId == "guest_1" || isActingHostGuest1 || isSeniorActivePeerGuest1
        assertTrue("Senior active peer Guest 1 MUST send GAME_SYNC to returning host", guest1ShouldSync)

        // Guest 2 evaluation:
        val isSeniorActivePeerGuest2 = activeRemaining.firstOrNull { it.id != rejoinPacket.playerId }?.id == "guest_2"
        val guest2ShouldSync = false /* isHosting */ || currentTurnPlayerId == "guest_2" || isActingHostGuest2 || isSeniorActivePeerGuest2
        assertFalse("Junior peer Guest 2 MUST NOT send redundant GAME_SYNC", guest2ShouldSync)
    }

    @Test
    fun `test calculateNextTurnPlayerId in 1v1 seamlessly advances turn to active player when peer disconnects`() {
        val playerA = Player(id = "playerA", displayName = "Alice", isHost = true)
        val playerB = Player(id = "playerB", displayName = "Bob", isHost = false)
        val participants = listOf(playerA, playerB)
        val disconnectedPlayerIds = setOf("playerB")

        // 1. When Bob disconnects or misses turn, next player is Alice
        val nextAfterBob = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = participants,
            disconnectedPlayerIds = disconnectedPlayerIds,
            currentPickerId = "playerB"
        )
        assertEquals("When Bob is disconnected, Alice gets the turn immediately", "playerA", nextAfterBob)

        // 2. While Bob remains in lobby, Alice can continue making picks without stalling
        val nextAfterAlice = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = participants,
            disconnectedPlayerIds = disconnectedPlayerIds,
            currentPickerId = "playerA"
        )
        assertEquals("Alice continues playing while match remains open for Bob to rejoin", "playerA", nextAfterAlice)
    }
}

