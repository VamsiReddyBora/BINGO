package com.bingo.multiplayer.domain

import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.network.PlayerPresence
import com.bingo.multiplayer.domain.network.PresenceManager
import com.bingo.multiplayer.domain.network.RoomMessagePacket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PresenceAndFriendRequestTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun testPresenceDisplayFormatting_noGreenDotsOrSymbols() {
        val now = System.currentTimeMillis()

        // 1. Online status — within 15 seconds
        val onlineStatus = PresenceManager.getDisplayStatus("test_user_online", now)
        assertEquals("online", onlineStatus)
        assertFalse("Must not contain green dot or circle", onlineStatus.contains("●") || onlineStatus.contains("🟢") || onlineStatus.contains("•"))

        // 2. 5 minutes ago — should show "last seen 5m ago"
        val fiveMinutesAgo = now - (5 * 60 * 1000L)
        val fiveMinStatus = PresenceManager.getDisplayStatus("unknown_user", fiveMinutesAgo)
        assertTrue("Expected 'last seen 5m ago' but got: $fiveMinStatus", fiveMinStatus.startsWith("last seen 5m"))
        assertFalse("Must not contain green dot", fiveMinStatus.contains("●") || fiveMinStatus.contains("🟢") || fiveMinStatus.contains("•"))

        // 3. 2 hours ago — should show "last seen 2h ago"
        val twoHoursAgo = now - (2 * 3600 * 1000L)
        val twoHoursStatus = PresenceManager.getDisplayStatus("unknown_user", twoHoursAgo)
        assertTrue("Expected 'last seen 2h ago' but got: $twoHoursStatus", twoHoursStatus.startsWith("last seen 2h"))
        assertFalse("Must not contain green dot", twoHoursStatus.contains("●") || twoHoursStatus.contains("🟢"))

        // 4. Over 24 hours ago — show "offline"
        val twoDaysAgo = now - (2 * 86400 * 1000L)
        val offlineStatus = PresenceManager.getDisplayStatus("unknown_user", twoDaysAgo)
        assertEquals("offline", offlineStatus)
        assertFalse("Must not contain dots", offlineStatus.contains("●") || offlineStatus.contains("🟢"))
    }

    @Test
    fun testPresenceDisplayFormatting_justNow() {
        val now = System.currentTimeMillis()

        // 5 seconds ago — should show "online" (within 15s window)
        val fiveSecsAgo = now - 5_000L
        val onlineStatus = PresenceManager.getDisplayStatus("unknown_user", fiveSecsAgo)
        assertEquals("online", onlineStatus)

        // 30 seconds ago — should show "last seen just now" (between 15s and 60s)
        val thirtySecsAgo = now - 30_000L
        val justNowStatus = PresenceManager.getDisplayStatus("unknown_user", thirtySecsAgo)
        assertEquals("last seen just now", justNowStatus)
    }

    @Test
    fun testFriendRequestSerialization() {
        val request = FriendRequest(
            id = "req_12345",
            fromUid = "uid_player_a",
            fromUsername = "playera",
            fromDisplayName = "Player A",
            fromAvatarUrl = "https://example.com/avatar.png",
            toUsername = "playerb",
            status = FriendRequestStatus.PENDING,
            timestamp = 1700000000000L
        )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<FriendRequest>(serialized)

        assertEquals("req_12345", deserialized.id)
        assertEquals("playera", deserialized.fromUsername)
        assertEquals("playerb", deserialized.toUsername)
        assertEquals(FriendRequestStatus.PENDING, deserialized.status)

        // Packet wrapping
        val packet = FriendRequestPacket(
            type = "FRIEND_REQUEST",
            request = request
        )
        val packetJson = json.encodeToString(packet)
        val packetDeserialized = json.decodeFromString<FriendRequestPacket>(packetJson)

        assertEquals("FRIEND_REQUEST", packetDeserialized.type)
        assertEquals("playera", packetDeserialized.request.fromUsername)
    }

    @Test
    fun testRoomMessagePacket_pingPongSurrenderPlayAgain() {
        val pingPacket = RoomMessagePacket(
            type = "PING",
            playerId = "player_1",
            pingTimestamp = 12345678L
        )
        val pongPacket = RoomMessagePacket(
            type = "PONG",
            playerId = "player_2",
            pingTimestamp = 12345678L
        )
        val surrenderPacket = RoomMessagePacket(
            type = "SURRENDER",
            playerId = "player_1",
            displayName = "Player One"
        )
        val playAgainReqPacket = RoomMessagePacket(
            type = "PLAY_AGAIN_REQUEST",
            playerId = "player_2",
            displayName = "Player Two"
        )

        val pingJson = json.encodeToString(pingPacket)
        val pongJson = json.encodeToString(pongPacket)
        val surrenderJson = json.encodeToString(surrenderPacket)
        val playAgainJson = json.encodeToString(playAgainReqPacket)

        assertEquals("PING", json.decodeFromString<RoomMessagePacket>(pingJson).type)
        assertEquals(12345678L, json.decodeFromString<RoomMessagePacket>(pongJson).pingTimestamp)
        assertEquals("SURRENDER", json.decodeFromString<RoomMessagePacket>(surrenderJson).type)
        assertEquals("Player Two", json.decodeFromString<RoomMessagePacket>(playAgainJson).displayName)
    }

    @Test
    fun testFastPacketCodec_microPayloads() {
        // 1. Pick Number micro payload
        val pickPacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 17,
            playerId = "u_alice",
            turnNumber = 4,
            currentTurnPlayerId = "u_bob",
            pickedHistory = listOf(3, 11, 17)
        )
        val encodedPick = com.bingo.multiplayer.domain.network.FastPacketCodec.encode(pickPacket)
        assertTrue("Must be micro-encoded starting with P|", encodedPick.startsWith("P|17|u_alice|4|u_bob|3,11,17"))
        assertTrue("Must be ultra compact (< 40 bytes)", encodedPick.length < 40)

        val decodedPick = com.bingo.multiplayer.domain.network.FastPacketCodec.decode(encodedPick)
        assertEquals("PICK_NUMBER", decodedPick.type)
        assertEquals(17, decodedPick.number)
        assertEquals("u_alice", decodedPick.playerId)
        assertEquals(4, decodedPick.turnNumber)
        assertEquals("u_bob", decodedPick.currentTurnPlayerId)
        assertEquals(listOf(3, 11, 17), decodedPick.pickedHistory)

        // 2. Ping micro payload
        val ping = RoomMessagePacket(type = "PING", playerId = "u1", pingTimestamp = 99999L)
        val encodedPing = com.bingo.multiplayer.domain.network.FastPacketCodec.encode(ping)
        assertEquals("G|u1|99999", encodedPing)
        val decodedPing = com.bingo.multiplayer.domain.network.FastPacketCodec.decode(encodedPing)
        assertEquals("PING", decodedPing.type)
        assertEquals("u1", decodedPing.playerId)
        assertEquals(99999L, decodedPing.pingTimestamp)

        // 3. Pong micro payload
        val pong = RoomMessagePacket(type = "PONG", playerId = "u2", pingTimestamp = 99999L)
        val encodedPong = com.bingo.multiplayer.domain.network.FastPacketCodec.encode(pong)
        assertEquals("O|u2|99999", encodedPong)
        val decodedPong = com.bingo.multiplayer.domain.network.FastPacketCodec.decode(encodedPong)
        assertEquals("PONG", decodedPong.type)
        assertEquals("u2", decodedPong.playerId)
        assertEquals(99999L, decodedPong.pingTimestamp)

        // 4. Heartbeat micro payload
        val hb = RoomMessagePacket(type = "HEARTBEAT", playerId = "u1", displayName = "Alice", isHost = true, timestamp = 1234567L)
        val encodedHb = com.bingo.multiplayer.domain.network.FastPacketCodec.encode(hb)
        assertEquals("H|u1|Alice|1|1234567", encodedHb)
        val decodedHb = com.bingo.multiplayer.domain.network.FastPacketCodec.decode(encodedHb)
        assertEquals("HEARTBEAT", decodedHb.type)
        assertEquals("u1", decodedHb.playerId)
        assertEquals("Alice", decodedHb.displayName)
        assertTrue(decodedHb.isHost)
        assertEquals(1234567L, decodedHb.timestamp)

        // 5. Backward compatibility with standard JSON
        val jsonPayload = "{\"type\":\"PICK_NUMBER\",\"number\":25,\"playerId\":\"legacy_user\",\"turnNumber\":1}"
        val decodedLegacy = com.bingo.multiplayer.domain.network.FastPacketCodec.decode(jsonPayload)
        assertEquals("PICK_NUMBER", decodedLegacy.type)
        assertEquals(25, decodedLegacy.number)
        assertEquals("legacy_user", decodedLegacy.playerId)
    }

    @Test
    fun testGameInvite_serializationAndLifecycle() {
        val invite = com.bingo.multiplayer.domain.network.GameInvite(
            fromUsername = "alice",
            fromDisplayName = "Alice In Wonderland",
            fromAvatarUrl = null,
            roomCode = "ROOM42",
            timestamp = System.currentTimeMillis()
        )

        // 1. Serialization
        val jsonStr = json.encodeToString(listOf(invite))
        val decodedList = json.decodeFromString<List<com.bingo.multiplayer.domain.network.GameInvite>>(jsonStr)
        assertEquals(1, decodedList.size)
        assertEquals("alice", decodedList[0].fromUsername)
        assertEquals("ROOM42", decodedList[0].roomCode)

        // 2. Expiry filtering (> 15 mins = 900_000 ms)
        val now = System.currentTimeMillis()
        val expiredInvite = invite.copy(timestamp = now - 1_000_000L)
        val freshInvite = invite.copy(timestamp = now - 100_000L)
        val allInvites = listOf(expiredInvite, freshInvite)
        val active = allInvites.filter { (now - it.timestamp) < 900_000L }
        assertEquals(1, active.size)
        assertEquals(freshInvite.timestamp, active[0].timestamp)

        // 3. Deduplication on roomCode or fromUsername
        val existing = mutableListOf(freshInvite)
        val duplicateRoom = invite.copy(fromUsername = "charlie", roomCode = "room42", timestamp = now)
        existing.removeAll { it.roomCode.equals(duplicateRoom.roomCode, ignoreCase = true) || it.fromUsername.equals(duplicateRoom.fromUsername, ignoreCase = true) }
        existing.add(0, duplicateRoom)
        assertEquals(1, existing.size)
        assertEquals("charlie", existing[0].fromUsername)
    }

    @Test
    fun testOnlineRoomSession_lifecycleAndValidation() {
        val host = com.bingo.multiplayer.domain.model.Player(
            id = "host_uid_1",
            displayName = "Host Player",
            username = "hostplayer",
            isHost = true,
            avatarUrl = "https://example.com/avatar.png",
            gamesPlayed = 10,
            gamesWon = 5,
            currentStreak = 2,
            level = 3
        )
        val joiner = com.bingo.multiplayer.domain.model.Player(
            id = "joiner_uid_2",
            displayName = "Joiner Player",
            username = "joinerplayer",
            isHost = false,
            avatarUrl = "https://example.com/avatar2.png",
            gamesPlayed = 8,
            gamesWon = 4,
            currentStreak = 1,
            level = 2
        )

        val session = com.bingo.multiplayer.domain.network.OnlineRoomSession(
            roomCode = "ROOM88",
            hostId = host.id,
            hostUsername = host.username,
            hostDisplayName = host.displayName,
            hostAvatarUrl = host.avatarUrl,
            status = "WAITING",
            boardSize = 5,
            createdAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            players = listOf(host)
        )

        // 1. Serialization & Deserialization
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<com.bingo.multiplayer.domain.network.OnlineRoomSession>(jsonStr)
        assertEquals("ROOM88", decoded.roomCode)
        assertEquals("host_uid_1", decoded.hostId)
        assertEquals("WAITING", decoded.status)
        assertEquals(1, decoded.players.size)
        assertEquals("Host Player", decoded.players[0].displayName)

        // 2. Joining room & capacity validation (max 2 players)
        val roomWithJoiner = session.copy(players = session.players + joiner)
        assertEquals(2, roomWithJoiner.players.size)

        val thirdPlayer = joiner.copy(id = "third_player_3", username = "third")
        val isFull = roomWithJoiner.players.size >= 2 && roomWithJoiner.players.none { it.id == thirdPlayer.id }
        assertTrue("Room with 2 distinct players should be full for a third player", isFull)

        // Rejoining should be allowed if same player ID
        val isJoinerAlreadyInRoom = roomWithJoiner.players.any { it.id == joiner.id }
        assertTrue("Rejoining by same player should be recognized", isJoinerAlreadyInRoom)

        // 3. Heartbeat expiry check (> 5 mins = 300_000ms)
        val now = System.currentTimeMillis()
        val expiredSession = session.copy(lastHeartbeat = now - 350_000L)
        val freshSession = session.copy(lastHeartbeat = now - 60_000L)
        assertTrue("Room with heartbeat > 5 mins ago must be expired", (now - expiredSession.lastHeartbeat) > 300_000L)
        assertFalse("Room with heartbeat 1 min ago must be active", (now - freshSession.lastHeartbeat) > 300_000L)

        // 4. Status validation
        val playingSession = session.copy(status = "PLAYING")
        assertEquals("PLAYING", playingSession.status)
        val closedSession = session.copy(status = "CLOSED")
        assertEquals("CLOSED", closedSession.status)
    }

    @Test
    fun testPlayerOnlineStatus_lifecycleBased() {
        val now = System.currentTimeMillis()

        // Within 15s = ONLINE (app is in foreground, heartbeat is live)
        val playerOnline10s = com.bingo.multiplayer.domain.model.Player(
            id = "p1",
            displayName = "Player One",
            lastSeenTimestamp = now - 10_000L
        )
        assertEquals("Player within 10s must be ONLINE", com.bingo.multiplayer.domain.model.PlayerOnlineStatus.ONLINE, playerOnline10s.onlineStatus)
        assertEquals("Player within 10s lastSeenDisplay must be 'online'", "online", playerOnline10s.lastSeenDisplay)

        // 3 minutes ago = LAST_SEEN_RECENTLY (app went to background)
        val playerRecentlyOffline3m = com.bingo.multiplayer.domain.model.Player(
            id = "p2",
            displayName = "Player Two",
            lastSeenTimestamp = now - 180_000L
        )
        assertEquals("Player within 3m must be LAST_SEEN_RECENTLY", com.bingo.multiplayer.domain.model.PlayerOnlineStatus.LAST_SEEN_RECENTLY, playerRecentlyOffline3m.onlineStatus)
        assertTrue("Player within 3m must show 'last seen 3m ago'", playerRecentlyOffline3m.lastSeenDisplay.contains("last seen 3m"))

        // 6 minutes ago = still LAST_SEEN_RECENTLY (within 24h)
        val playerOffline6m = com.bingo.multiplayer.domain.model.Player(
            id = "p3",
            displayName = "Player Three",
            lastSeenTimestamp = now - 360_000L
        )
        assertEquals("Player within 6m must be LAST_SEEN_RECENTLY (within 24h)", com.bingo.multiplayer.domain.model.PlayerOnlineStatus.LAST_SEEN_RECENTLY, playerOffline6m.onlineStatus)
        assertTrue("Player within 6m must show minutes", playerOffline6m.lastSeenDisplay.contains("last seen 6m"))

        // Over 24 hours = OFFLINE
        val playerOffline25h = com.bingo.multiplayer.domain.model.Player(
            id = "p4",
            displayName = "Player Four",
            lastSeenTimestamp = now - (25 * 3600 * 1000L)
        )
        assertEquals("Player over 24h must be OFFLINE", com.bingo.multiplayer.domain.model.PlayerOnlineStatus.OFFLINE, playerOffline25h.onlineStatus)
        assertEquals("Player over 24h must show 'offline'", "offline", playerOffline25h.lastSeenDisplay)
    }

    @Test
    fun testPlayerPresence_isOnline_15secondWindow() {
        val now = System.currentTimeMillis()

        // Within 15s and status ONLINE = isOnline true
        val onlinePresence = PlayerPresence("user1", "ONLINE", now - 5_000L)
        assertTrue("Presence within 5s with ONLINE status should be online", onlinePresence.isOnline)

        // Within 15s but status OFFLINE = isOnline false
        val offlinePresence = PlayerPresence("user2", "OFFLINE", now - 5_000L)
        assertFalse("Presence with OFFLINE status should not be online", offlinePresence.isOnline)

        // Status ONLINE but timestamp > 15s = isOnline false (stale retained message)
        val stalePresence = PlayerPresence("user3", "ONLINE", now - 20_000L)
        assertFalse("Presence with ONLINE status but > 15s old should not be online", stalePresence.isOnline)
    }

    @Test
    fun testIsLocalFilePath_correctlyDifferentiatesBase64AndLocalPaths() {
        // Base64 JPEG starts with /9j/ - MUST NOT be treated as a local file path!
        val jpegBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/4gHYSUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABh" + ("A".repeat(800))
        assertFalse("Base64 JPEG starting with /9j/ must NOT be a local file path", com.bingo.multiplayer.domain.network.isLocalFilePath(jpegBase64))

        // Data URI
        val dataUri = "data:image/jpeg;base64,/9j/4AAQSkZJRg..."
        assertFalse("data:image must NOT be a local file path", com.bingo.multiplayer.domain.network.isLocalFilePath(dataUri))

        // HTTP URL
        val httpUrl = "https://example.com/avatar.jpg"
        assertFalse("HTTP URL must NOT be a local file path", com.bingo.multiplayer.domain.network.isLocalFilePath(httpUrl))

        // Real local Android path
        val localPath = "/data/user/0/com.bingo.multiplayer/files/avatar_test.jpg"
        assertTrue("Android internal storage path should be detected as local path", com.bingo.multiplayer.domain.network.isLocalFilePath(localPath))

        // Non-path short string
        val randomString = "hello_world"
        assertFalse("Random string must NOT be a local file path", com.bingo.multiplayer.domain.network.isLocalFilePath(randomString))
    }

    @Test
    fun testOnlineRoomSession_compactPayloadUnderLimit() {
        val players = (1..8).map { i ->
            com.bingo.multiplayer.domain.model.Player(
                id = "user_id_$i",
                displayName = "Player $i",
                username = "player$i",
                isHost = (i == 1),
                avatarUrl = null,
                score = 0,
                completedLinesCount = 0,
                gamesPlayed = 0,
                gamesWon = 0,
                currentStreak = 0,
                level = 1,
                lastSeenTimestamp = System.currentTimeMillis(),
                lobbyReadyStatus = if (i == 1) "READY" else "NOT_READY"
            )
        }

        val session = com.bingo.multiplayer.domain.network.OnlineRoomSession(
            roomCode = "ABCDEF",
            hostId = "user_id_1",
            hostUsername = "player1",
            hostDisplayName = "Player 1",
            hostAvatarUrl = null,
            status = "WAITING",
            boardSize = 5,
            players = players
        )

        val jsonStr = json.encodeToString(session)
        val encoded = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.encodeBase64Url(jsonStr)

        // 8 players compressed must be well under KeyValue's 1024-byte query param limit
        assertTrue("Encoded payload for 8 players must be < 700 chars (actual: ${encoded.length})", encoded.length < 700)

        // Verify lossless roundtrip decompression
        val decoded = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.decodeBase64Url(encoded)
        assertEquals("Decompressed session JSON must match original", jsonStr, decoded)

        // Verify backward compatibility with uncompressed base64
        val plainBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(jsonStr.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).trim()
        val decodedPlain = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.decodeBase64Url(plainBase64)
        assertEquals("Plain base64 must decode correctly", jsonStr, decodedPlain)
    }
}

