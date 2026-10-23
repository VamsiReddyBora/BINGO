package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class InviteAcceptanceRegressionTest {

    private val hostUid = "guest_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val host = Player(id = hostUid, displayName = "Host Player", username = "host_user", isHost = true)
    private val guest = Player(id = "guest_fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", displayName = "Guest Joiner", username = "guest_user", isHost = false)
    private val roomCode = "VAL123"
    private val validSessionId = UUID.randomUUID().toString()

    private fun testSession() = OnlineRoomSession(
        roomCode = roomCode,
        hostId = host.id,
        hostUsername = host.username,
        hostDisplayName = host.displayName,
        boardSize = 5,
        status = "WAITING",
        sessionId = validSessionId,
        players = listOf(host),
        revision = 1L,
        metadataProtocolVersion = 7
    )

    @org.junit.Before
    fun setUp() {
        AccountKeyStore.clearCachesForTests()
    }

    @org.junit.After
    fun tearDown() {
        AccountKeyStore.clearCachesForTests()
    }

    private class FailingCandidateStore(
        var room: OnlineRoomSession?
    ) : RoomMetadataTransport {
        var readCalls = 0

        override suspend fun read(code: String): Result<OnlineRoomSession?> {
            readCalls++
            return Result.success(room)
        }

        override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
            room = next
            return RoomMetadataCommit.Applied(next)
        }
    }

    @Test
    fun defect1_readCandidateExceptionDoesNotCauseNetworkError() = runBlocking {
        val wire = EmqxWireFixture()
        // Wire throws on readRetained
        wire.isHealthy = false
        val transport = MqttRoomMetadataTransport(wire, { guest.id })
        RoomMetadataApi.transportOverride = transport

        try {
            // Emulate what happens when readCandidate encounters an unhealthy wire/socket:
            // Must not crash or return unhandled "Network error while validating room ... Please check your connection."
            val result = OnlineRoomRegistry.validateAndJoinRoom(roomCode, guest)
            assertFalse("Must not return generic Network error catch-all: $result",
                result is RoomJoinResult.Error && result.message.contains("Network error while validating room"))
        } finally {
            RoomMetadataApi.transportOverride = null
            transport.close()
            OnlineRoomRegistry.recentRoomCache.remove(roomCode)
        }
    }

    @Test
    fun defect2_emptyExpectedSessionIdDoesNotRejectInviteAsOlderLobby() = runBlocking {
        val session = testSession()
        val store = FailingCandidateStore(session)
        val originalTransport = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = store
        OnlineRoomRegistry.rememberCanonicalRoom(session)

        try {
            // When invite contains empty roomSessionId = "", validateAndJoinRoom must not reject it as "This invitation belongs to an older lobby."
            val result = OnlineRoomRegistry.validateAndJoinRoom(roomCode, guest, expectedSessionId = "", expectedHostId = host.id)
            assertFalse("Must not reject empty expectedSessionId as older lobby: $result",
                result is RoomJoinResult.NotFound && result.message.contains("older lobby"))
            assertTrue("Expected success, but got: $result", result is RoomJoinResult.Success)
        } finally {
            RoomMetadataApi.transportOverride = originalTransport
            OnlineRoomRegistry.recentRoomCache.remove(roomCode)
        }
    }

    @Test
    fun test3_inviteWithPublicKeyBindsAuthorityCleanly() = runBlocking {
        val fakeKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "A".repeat(80)
        val invite = GameInvite(
            fromUsername = host.username,
            fromDisplayName = host.displayName,
            roomCode = roomCode,
            senderUid = host.id,
            roomSessionId = validSessionId,
            senderPublicKey = fakeKey
        )

        // Pre-caching logic triggered on invite acceptance:
        invite.senderPublicKey?.takeIf { it.isNotBlank() }?.let { pk ->
            invite.senderUid.takeIf { it.isNotBlank() }?.let { hostId ->
                AccountKeyStore.recordAuthoritativeKey(hostId, pk)
                AccountKeyStore.bindHostKey(hostId, pk, local = guest.id)
            }
        }

        assertEquals(fakeKey, AccountKeyStore.getAuthoritativeKey(host.id))
        assertTrue(AccountKeyStore.isHostKeyValid(host.id, fakeKey, local = guest.id))
    }

    @Test
    fun defect4_guestJoinProposalNotRejectedWithUntrustedActorKey() = runBlocking {
        val wire = EmqxWireFixture()
        val hostTransport = MqttRoomMetadataTransport(wire, { host.id })
        val guestTransport = MqttRoomMetadataTransport(wire, { guest.id })
        try {
            val session = testSession()
            val hostInit = hostTransport.commit(roomCode, null, session)
            assertTrue("Host initial commit must succeed: $hostInit", hostInit is RoomMetadataCommit.Applied)

            val guestCoordinator = RoomMetadataCoordinator(guestTransport)
            val joinResult = guestCoordinator.mutate(roomCode) { current ->
                current.copy(players = current.players + guest)
            }
            assertFalse("Guest join must not be rejected with Untrusted actor key: $joinResult",
                joinResult is MutationResult.Rejected && joinResult.reason == "Untrusted actor key")
            assertTrue("Guest join must be applied: $joinResult", joinResult is MutationResult.Applied)
        } finally {
            hostTransport.close()
            guestTransport.close()
        }
    }
}
