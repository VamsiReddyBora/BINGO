package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.notifications.NotificationAccount
import com.bingo.multiplayer.domain.notifications.NotificationPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EndToEndInviteAndLobbyFlowTest {

    private val hostUid = "host_123"
    private val hostUsername = "host_user"
    private val guestUid = "guest_456"
    private val guestUsername = "guest_user"

    private val hostPlayer = Player(id = hostUid, displayName = "Host Player", username = hostUsername, isHost = true)
    private val guestPlayer = Player(id = guestUid, displayName = "Guest Player", username = guestUsername, isHost = false)
    private val roomCode = "FLOW99"

    @Test
    fun testEndToEndInviteAcceptAndLobbyEntry() = runBlocking {
        // 1. Host creates room
        val store = object : RoomMetadataTransport {
            var room: OnlineRoomSession? = null
            override suspend fun read(code: String) = Result.success(room)
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
                room = next
                return RoomMetadataCommit.Applied(next)
            }
        }
        val origTransport = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = store

        try {
            val created = OnlineRoomRegistry.createRoom(roomCode, hostPlayer)
            assertTrue("Host room creation should succeed", created)
            val session = OnlineRoomRegistry.getRoomCachedOnly(roomCode)
            assertNotNull("Room must be in cache", session)
            assertEquals("WAITING", session?.status)
            assertEquals(hostUid, session?.hostId)

            // 2. Invitee receives and accepts invite
            // Test that validateAndJoinRoom succeeds and admits guest
            val joinResult = OnlineRoomRegistry.validateAndJoinRoom(roomCode, guestPlayer)
            assertTrue("validateAndJoinRoom must succeed, got $joinResult", joinResult is RoomJoinResult.Success)
            val joinedSession = (joinResult as RoomJoinResult.Success).room
            assertTrue("Guest must be in joined room players", joinedSession.players.any { it.id == guestUid })

            // 3. OnlineRoomSyncManager lobby presence
            // Test that host sync manager receives JOIN packet from guest without dropping it
            val hostSync = OnlineRoomSyncManager()
            hostSync.connectToRoom(roomCode, hostPlayer, initialPlayers = listOf(hostPlayer))

            // Guest sends JOIN packet
            val joinPacket = RoomMessagePacket(
                type = "JOIN",
                playerId = guestUid,
                displayName = guestPlayer.displayName,
                username = guestUsername,
                isHost = false,
                roomCode = roomCode,
                timestamp = System.currentTimeMillis()
            )

            hostSync.handleIncomingPacket(joinPacket)

            // Verify that host's player list now contains guest
            val currentHostPlayers = hostSync.players.value
            assertTrue("Host player registry must contain guest after JOIN packet",
                currentHostPlayers.any { it.id == guestUid })
            assertEquals("Host must see 2 players in lobby", 2, currentHostPlayers.size)

            hostSync.disconnect()
        } finally {
            RoomMetadataApi.transportOverride = origTransport
            OnlineRoomRegistry.recentRoomCache.remove(roomCode)
        }
    }

    @Test
    fun timedOutAdmissionDoesNotInventLocalLobbyMembership() = runBlocking {
        // If host proposal times out (returns Unavailable), validateAndJoinRoom must fallback
        // to direct verified admission rather than failing with "Room host unavailable"
        val store = object : RoomMetadataTransport {
            var room: OnlineRoomSession? = OnlineRoomSession(
                roomCode = roomCode,
                hostId = hostUid,
                hostUsername = hostUsername,
                hostDisplayName = "Host Player",
                boardSize = 5,
                status = "WAITING",
                players = listOf(hostPlayer),
                revision = 1L
            )
            override suspend fun read(code: String) = Result.success(room)
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
                // Simulate host peer proposal timeout / unavailable
                return RoomMetadataCommit.Unavailable(java.io.IOException("Room host unavailable"))
            }
        }

        val origTransport = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = store

        try {
            OnlineRoomRegistry.rememberCanonicalRoom(store.room!!)
            val result = OnlineRoomRegistry.validateAndJoinRoom(roomCode, guestPlayer)
            assertTrue("A missing host acknowledgement must be reported honestly: $result", result is RoomJoinResult.Error)
            assertFalse(store.room!!.players.any { it.id == guestUid })
        } finally {
            RoomMetadataApi.transportOverride = origTransport
            OnlineRoomRegistry.recentRoomCache.remove(roomCode)
        }
    }
}
