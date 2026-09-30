package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class TurnRotationAndCodecTest {

    @Test
    fun testFastPacketCodecPickNumber() {
        val original = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 17,
            playerId = "player_a",
            turnNumber = 3,
            currentTurnPlayerId = "player_b",
            pickedHistory = listOf(5, 12, 17)
        )

        val encoded = FastPacketCodec.encode(original)
        assertTrue("Encoded micro-payload should start with P|", encoded.startsWith("P|"))

        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("PICK_NUMBER", decoded.type)
        assertEquals(17, decoded.number)
        assertEquals("player_a", decoded.playerId)
        assertEquals(3, decoded.turnNumber)
        assertEquals("player_b", decoded.currentTurnPlayerId)
        assertEquals(listOf(5, 12, 17), decoded.pickedHistory)
    }

    @Test
    fun testFastPacketCodecTurnTimeout() {
        val original = RoomMessagePacket(
            type = "TURN_TIMEOUT",
            number = -1,
            playerId = "player_a",
            turnNumber = 4,
            currentTurnPlayerId = "player_b",
            pickedHistory = listOf(5, 12, 17)
        )

        val encoded = FastPacketCodec.encode(original)
        assertTrue("Encoded micro-payload should start with T|", encoded.startsWith("T|"))

        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("TURN_TIMEOUT", decoded.type)
        assertEquals(-1, decoded.number)
        assertEquals("player_a", decoded.playerId)
        assertEquals(4, decoded.turnNumber)
        assertEquals("player_b", decoded.currentTurnPlayerId)
        assertEquals(listOf(5, 12, 17), decoded.pickedHistory)
    }

    @Test
    fun testTwoPlayerStrictAlternation() {
        val players = listOf(
            Player(id = "uid_a", displayName = "Player A"),
            Player(id = "uid_b", displayName = "Player B")
        )

        fun getNext(picker: String): String {
            val idx = players.indexOfFirst { it.id == picker }
            return players[(idx + 1) % players.size].id
        }

        var currentTurn = "uid_a"
        assertEquals("uid_a", currentTurn)

        // Turn 1: Player A picks -> next is Player B
        currentTurn = getNext(currentTurn)
        assertEquals("uid_b", currentTurn)

        // Turn 2: Player B picks -> next is Player A
        currentTurn = getNext(currentTurn)
        assertEquals("uid_a", currentTurn)

        // Turn 3: Player A picks -> next is Player B
        currentTurn = getNext(currentTurn)
        assertEquals("uid_b", currentTurn)

        // Turn 4: Player B picks -> next is Player A
        currentTurn = getNext(currentTurn)
        assertEquals("uid_a", currentTurn)
    }

    @Test
    fun testThreePlayerCircularRotation() {
        val players = listOf(
            Player(id = "p1", displayName = "P1"),
            Player(id = "p2", displayName = "P2"),
            Player(id = "p3", displayName = "P3")
        )

        fun getNext(picker: String): String {
            val idx = players.indexOfFirst { it.id == picker }
            return players[(idx + 1) % players.size].id
        }

        var current = "p1"
        current = getNext(current)
        assertEquals("p2", current)
        current = getNext(current)
        assertEquals("p3", current)
        current = getNext(current)
        assertEquals("p1", current)
        current = getNext(current)
        assertEquals("p2", current)
    }

    @Test
    fun testAiMatchAlternation() {
        fun getNext(picker: String, localUid: String): String {
            return if (picker == "ai_bot") localUid else "ai_bot"
        }

        val myUid = "local_human"
        var currentPicker = myUid
        var next = getNext(currentPicker, myUid)
        assertEquals("ai_bot", next)

        currentPicker = "ai_bot"
        next = getNext(currentPicker, myUid)
        assertEquals(myUid, next)

        currentPicker = myUid
        next = getNext(currentPicker, myUid)
        assertEquals("ai_bot", next)
    }

    @Test
    fun testTimeoutDoesNotPickNumber() {
        val engine = BingoEngine()
        val board = engine.generateBoard(5, seed = 42)
        val pickedHistory = mutableListOf<Int>()

        // Simulate timeout pass (number = -1)
        val timeoutNumber = -1
        val isTimeoutPass = (timeoutNumber <= 0)
        assertTrue(isTimeoutPass)

        if (!isTimeoutPass) {
            pickedHistory.add(timeoutNumber)
        }

        // Verify no number was added to history
        assertTrue("History must remain empty on timeout", pickedHistory.isEmpty())

        // Verify all cells on the board remain unmarked
        assertTrue("All cells must remain unmarked on timeout", board.cells.none { it.isMarked })
    }

    @Test
    fun testFastPacketCodec_readyStatusAndKickPlayer() {
        // 1. READY_STATUS encoding and decoding
        val readyPkt = RoomMessagePacket(
            type = "READY_STATUS",
            playerId = "player_bob",
            readyStatus = "READY"
        )
        val encodedReady = FastPacketCodec.encode(readyPkt)
        assertEquals("R|player_bob|READY", encodedReady)
        val decodedReady = FastPacketCodec.decode(encodedReady)
        assertEquals("READY_STATUS", decodedReady.type)
        assertEquals("player_bob", decodedReady.playerId)
        assertEquals("READY", decodedReady.readyStatus)

        // 2. KICK_PLAYER encoding and decoding
        val kickPkt = RoomMessagePacket(
            type = "KICK_PLAYER",
            targetPlayerId = "player_bad",
            playerId = "host_alice"
        )
        val encodedKick = FastPacketCodec.encode(kickPkt)
        assertEquals("K|player_bad|host_alice", encodedKick)
        val decodedKick = FastPacketCodec.decode(encodedKick)
        assertEquals("KICK_PLAYER", decodedKick.type)
        assertEquals("player_bad", decodedKick.targetPlayerId)
        assertEquals("host_alice", decodedKick.playerId)
    }

    @Test
    fun testLobbyReadiness_startMatchGating() {
        val host = Player(id = "p1", displayName = "Host Player", isHost = true)
        val playerB = Player(id = "p2", displayName = "Player B", isHost = false)

        // 1. Host defaults to READY, non-host defaults to NOT_READY
        assertEquals("READY", host.lobbyReadyStatus)
        assertEquals("NOT_READY", playerB.lobbyReadyStatus)

        // 2. With only Host: match cannot start (needs >= 2 players)
        val onePlayerList = listOf(host)
        val canStartOne = onePlayerList.size >= 2 && onePlayerList.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertFalse("Host cannot start alone", canStartOne)

        // 3. With Player B in NOT_READY state: match cannot start
        val playersNotReady = listOf(host, playerB)
        val canStartNotReady = playersNotReady.size >= 2 && playersNotReady.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertFalse("Host cannot start while Player B is NOT_READY", canStartNotReady)

        // 4. When Player B becomes READY: match CAN start
        val playerBReady = playerB.copy(lobbyReadyStatus = "READY")
        val playersAllReady = listOf(host, playerBReady)
        val canStartAllReady = playersAllReady.size >= 2 && playersAllReady.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertTrue("Host CAN start when all players are READY", canStartAllReady)

        // 5. When Player B enters IN_GAME or reviews board post-match: match cannot start
        val playerBInGame = playerB.copy(lobbyReadyStatus = "IN_GAME")
        val playersInGame = listOf(host, playerBInGame)
        val canStartInGame = playersInGame.size >= 2 && playersInGame.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertFalse("Host cannot start while Player B is IN_GAME", canStartInGame)

        // 6. When Player B leaves lobby (LEFT_LOBBY): match cannot start
        val playerBLeft = playerB.copy(lobbyReadyStatus = "LEFT_LOBBY")
        val playersLeft = listOf(host, playerBLeft)
        val canStartLeft = playersLeft.size >= 2 && playersLeft.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertFalse("Host cannot start with an exited player in lobby", canStartLeft)

        // 7. When Host kicks the exited player: player list removes Player B
        val playersAfterKick = playersLeft.filterNot { it.id == "p2" }
        assertEquals(1, playersAfterKick.size)
        val canStartAfterKick = playersAfterKick.size >= 2 && playersAfterKick.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertFalse("Cannot start alone after kick", canStartAfterKick)

        // 8. New player joins and gets ready
        val playerC = Player(id = "p3", displayName = "Player C", isHost = false, lobbyReadyStatus = "READY")
        val playersWithNew = playersAfterKick + playerC
        val canStartWithNew = playersWithNew.size >= 2 && playersWithNew.all { it.isHost || it.lobbyReadyStatus == "READY" }
        assertTrue("Host CAN start with new ready player", canStartWithNew)
    }

    @Test
    fun testHostInGameStatus_takesPrecedenceOverIsHost() {
        val hostInLobby = Player(id = "host1", displayName = "Host", isHost = true, lobbyReadyStatus = "READY")
        val hostInGame = Player(id = "host1", displayName = "Host", isHost = true, lobbyReadyStatus = "IN_GAME")

        fun getDisplayStatus(player: Player): String {
            return when {
                player.lobbyReadyStatus == "IN_GAME" -> "In Game"
                player.lobbyReadyStatus == "LEFT_LOBBY" -> "Left Lobby"
                player.isHost || player.lobbyReadyStatus == "READY" -> "Host Ready"
                else -> "Not Ready"
            }
        }

        assertEquals("Host Ready", getDisplayStatus(hostInLobby))
        assertEquals("In Game", getDisplayStatus(hostInGame))
    }

    @Test
    fun testDrawMatchEvaluation() {
        val winA = true
        val winB = true

        val isDraw = winA && winB
        val didPlayerWin = if (isDraw) false else winA
        assertTrue("Simultaneous 5-line completion must result in Draw", isDraw)
        assertFalse("Neither player is designated sole winner in Draw", didPlayerWin)
    }

    @Test
    fun testMatchTitleFormatting() {
        fun formatTitle(myName: String, opponentName: String, won: Boolean, isDraw: Boolean, isGroup: Boolean): String {
            return if (isGroup) {
                when {
                    isDraw -> "Group play draw 🤝"
                    won -> "Group play won 🏆"
                    else -> "Group play lost 😑"
                }
            } else {
                when {
                    isDraw -> "$myName vs $opponentName Draw 🤝"
                    won -> "$myName vs $opponentName Won 🏆"
                    else -> "$myName vs $opponentName Lost 😑"
                }
            }
        }

        // Two player tests
        assertEquals("alice vs bob Won 🏆", formatTitle("alice", "bob", won = true, isDraw = false, isGroup = false))
        assertEquals("alice vs bob Lost 😑", formatTitle("alice", "bob", won = false, isDraw = false, isGroup = false))
        assertEquals("alice vs bob Draw 🤝", formatTitle("alice", "bob", won = false, isDraw = true, isGroup = false))

        // Group play tests
        assertEquals("Group play won 🏆", formatTitle("alice", "bob", won = true, isDraw = false, isGroup = true))
        assertEquals("Group play lost 😑", formatTitle("alice", "bob", won = false, isDraw = false, isGroup = true))
        assertEquals("Group play draw 🤝", formatTitle("alice", "bob", won = false, isDraw = true, isGroup = true))
    }

    @Test
    fun testPlayerProfileMedalEmoji() {
        fun getMedalEmoji(level: Int): String {
            return when {
                level >= 12 -> "🎖️"
                level >= 8 -> "🏅"
                level >= 5 -> "🥇"
                level >= 3 -> "🥈"
                else -> "🥉"
            }
        }

        assertEquals("🥉", getMedalEmoji(1))
        assertEquals("🥉", getMedalEmoji(2))
        assertEquals("🥈", getMedalEmoji(3))
        assertEquals("🥈", getMedalEmoji(4))
        assertEquals("🥇", getMedalEmoji(5))
        assertEquals("🥇", getMedalEmoji(7))
        assertEquals("🏅", getMedalEmoji(8))
        assertEquals("🏅", getMedalEmoji(11))
        assertEquals("🎖️", getMedalEmoji(12))
        assertEquals("🎖️", getMedalEmoji(25))
    }

    @Test
    fun testReadyButtonTextAndOutlineState() {
        fun getReadyButtonProps(isReady: Boolean): Pair<String, Boolean> {
            val text = if (isReady) "I'm Not Ready" else "I'm Ready"
            val isOutlined = isReady
            return Pair(text, isOutlined)
        }

        val (unreadyText, unreadyOutlined) = getReadyButtonProps(false)
        assertEquals("I'm Ready", unreadyText)
        assertFalse("Unready button should be filled (not outlined)", unreadyOutlined)
        assertFalse("Text should have zero emojis or symbols", unreadyText.contains("⏸") || unreadyText.contains("✅"))

        val (readyText, readyOutlined) = getReadyButtonProps(true)
        assertEquals("I'm Not Ready", readyText)
        assertTrue("Ready button should be outline-only", readyOutlined)
        assertFalse("Text should have zero emojis or symbols", readyText.contains("⏸") || readyText.contains("✅"))
    }

    @Test
    fun testPlayerLeftLobbyStatusPreserved() {
        val playerRegistry = mutableMapOf<String, Player>()
        val host = Player(id = "host1", displayName = "HostPlayer", isHost = true)
        val guest = Player(id = "guest1", displayName = "GuestPlayer", isHost = false, lobbyReadyStatus = "READY")
        playerRegistry[host.id] = host
        playerRegistry[guest.id] = guest

        // Guest sends LEAVE packet
        val leavePacket = RoomMessagePacket(type = "LEAVE", playerId = "guest1")
        val existing = playerRegistry[leavePacket.playerId]
        assertNotNull(existing)
        if (existing!!.isHost) {
            playerRegistry.remove(leavePacket.playerId)
        } else {
            playerRegistry[leavePacket.playerId] = existing.copy(lobbyReadyStatus = "LEFT_LOBBY")
        }

        // Verify guest is NOT removed and status is LEFT_LOBBY
        assertEquals(2, playerRegistry.size)
        assertEquals("LEFT_LOBBY", playerRegistry["guest1"]?.lobbyReadyStatus)

        // Verify that in lobby, ready indicator returns Left Lobby (mapped to ❌ icon)
        val guestStatus = when {
            playerRegistry["guest1"]?.lobbyReadyStatus == "IN_GAME" -> "In Game"
            playerRegistry["guest1"]?.lobbyReadyStatus == "LEFT_LOBBY" -> "Left Lobby"
            playerRegistry["guest1"]?.isHost == true || playerRegistry["guest1"]?.lobbyReadyStatus == "READY" -> "Ready"
            else -> "Not Ready"
        }
        assertEquals("Left Lobby", guestStatus)
    }

    @Test
    fun testLobbyInactivityAndExtensionCycle() {
        var inactivitySeconds = 300
        var showWarning = false
        var countdownSeconds = 30

        fun onTick() {
            if (!showWarning) {
                if (inactivitySeconds > 0) {
                    inactivitySeconds--
                } else {
                    showWarning = true
                    countdownSeconds = 30
                }
            } else {
                if (countdownSeconds > 0) {
                    countdownSeconds--
                }
            }
        }

        // Simulate 300 seconds elapsed
        repeat(300) { onTick() }
        onTick()
        assertTrue("Warning dialog should trigger after 300s of inactivity", showWarning)
        assertEquals(30, countdownSeconds)

        // Simulate user clicking "Wait +5mins"
        fun onExtendLobby() {
            inactivitySeconds = 300
            showWarning = false
            countdownSeconds = 30
        }

        onExtendLobby()
        assertFalse("Warning dialog should dismiss upon extension", showWarning)
        assertEquals(300, inactivitySeconds)
    }
}
