package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InvitationDeliveryRegressionTest {
    private val host = Player(id = "invite-host", displayName = "Kevin", username = "kevin", isHost = true)
    private fun waiting() = OnlineRoomSession("INV123", host.id, host.username, host.displayName,
        players = listOf(host), revision = 1L, metadataProtocolVersion = 3)

    private class Store(var room: OnlineRoomSession?) : RoomMetadataTransport {
        var reads = 0
        override suspend fun read(code: String): Result<OnlineRoomSession?> { reads++; return Result.success(room) }
        override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
            room = next
            return RoomMetadataCommit.Applied(next)
        }
    }

    private suspend fun withStore(store: Store, block: suspend () -> Unit) {
        val originalTransport = RoomMetadataApi.transportOverride
        val originalPublisher = OnlineRoomRegistry.roomMetaPublisherOverride
        RoomMetadataApi.transportOverride = store
        OnlineRoomRegistry.roomMetaPublisherOverride = { false }
        try { block() } finally {
            RoomMetadataApi.transportOverride = originalTransport
            OnlineRoomRegistry.roomMetaPublisherOverride = originalPublisher
            OnlineRoomRegistry.recentRoomCache.remove("INV123")
            OnlineRoomRegistry.terminallyClosedRooms.remove("INV123")
            OnlineRoomRegistry.roomStatusMap.remove("INV123")
        }
    }

    @Test
    fun canonicalInvitationIsReadyWhenRedundantMqttMetadataPublicationFails() = runBlocking {
        val store = Store(waiting())
        withStore(store) {
            assertEquals(InvitationReadinessResult.Ready,
                OnlineRoomRegistry.ensureInvitationReadinessDetailed("INV123", host, allowCreation = false))
            assertEquals(1, store.reads)
        }
    }

    @Test
    fun committedCanonicalRoomCreationSucceedsWhenMqttMetadataPublicationFails() = runBlocking {
        val store = Store(null)
        withStore(store) {
            assertTrue(OnlineRoomRegistry.createRoom("INV123", host))
            assertEquals("WAITING", store.room?.status)
            assertEquals(host.id, store.room?.hostId)
            assertEquals(3, store.room?.metadataProtocolVersion)
        }
    }

    @Test
    fun canonicalInvitationReachesBothDeliveryChannelsWithoutMetadataRepublish() = runBlocking {
        val store = Store(waiting())
        var peers = 0
        var registered = 0
        withStore(store) {
            val result = GameInviteManager.dispatchInviteAttempt(
                readiness = { OnlineRoomRegistry.ensureInvitationReadinessDetailed("INV123", host, allowCreation = false) },
                isCurrent = { true }, publishPeer = { peers++ }, registerEvent = { registered++ })
            assertEquals(InviteDeliveryAttempt.REGISTERED, result)
            assertEquals(1, peers)
            assertEquals(1, registered)
        }
    }

    @Test
    fun queuedRetrySendsPeerAndRegistersEventAfterActualReadinessRecovers() = runBlocking {
        val store = Store(waiting())
        var peers = 0
        var registered = 0
        var available = false
        val originalTransport = RoomMetadataApi.transportOverride
        val originalPublisher = OnlineRoomRegistry.roomMetaPublisherOverride
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport by store {
            override suspend fun read(code: String) = if (available) store.read(code) else Result.failure(IOException("Controlled outage"))
        }
        OnlineRoomRegistry.roomMetaPublisherOverride = { false }
        try {
            suspend fun attempt() = GameInviteManager.dispatchInviteAttempt(
                readiness = { OnlineRoomRegistry.ensureInvitationReadinessDetailed("INV123", host, allowCreation = false) },
                isCurrent = { true }, publishPeer = { peers++ }, registerEvent = { registered++ })
            assertEquals(InviteDeliveryAttempt.RETRY, attempt())
            assertEquals(0, peers)
            assertEquals(0, registered)
            available = true
            assertEquals(InviteDeliveryAttempt.REGISTERED, attempt())
            assertEquals(1, peers)
            assertEquals(1, registered)
        } finally {
            RoomMetadataApi.transportOverride = originalTransport
            OnlineRoomRegistry.roomMetaPublisherOverride = originalPublisher
            OnlineRoomRegistry.recentRoomCache.remove("INV123")
        }
    }

    @Test
    fun closedPlayingAndForeignHostRoomsNeverDispatchInvitations() = runBlocking {
        for (room in listOf(waiting().copy(status = "CLOSED", isTerminalClosed = true),
                waiting().copy(status = "PLAYING", currentSeed = 12L), waiting().copy(hostId = "other-host"))) {
            withStore(Store(room)) {
                val result = GameInviteManager.dispatchInviteAttempt(
                    readiness = { OnlineRoomRegistry.ensureInvitationReadinessDetailed("INV123", host, allowCreation = false) },
                    isCurrent = { true }, publishPeer = { fail("Invalid room cannot send a peer invite") },
                    registerEvent = { fail("Invalid room cannot send push") })
                assertEquals(InviteDeliveryAttempt.REJECTED, result)
            }
        }
    }

    @Test
    fun coldRetryNeverRecreatesMissingRoom() = runBlocking {
        val store = Store(null)
        withStore(store) {
            val result = GameInviteManager.dispatchInviteAttempt(
                readiness = { OnlineRoomRegistry.ensureInvitationReadinessDetailed("INV123", host, allowCreation = false) },
                isCurrent = { true }, publishPeer = { fail("Missing room cannot send invite") },
                registerEvent = { fail("Missing room cannot register invite") })
            assertEquals(InviteDeliveryAttempt.REJECTED, result)
            assertNull(store.room)
        }
    }

    @Test
    fun peerPublishFailureDoesNotSuppressAuthenticatedInboxDelivery() = runBlocking {
        var registered = 0
        assertEquals(InviteDeliveryAttempt.REGISTERED, GameInviteManager.dispatchInviteAttempt(
            readiness = { InvitationReadinessResult.Ready }, isCurrent = { true },
            publishPeer = { throw IOException("MQTT unavailable") }, registerEvent = { registered++ }))
        assertEquals(1, registered)
    }

    @Test
    fun transientReadinessDefersPeerAndPushAndMirrorPendingDefersOnlyPush() = runBlocking {
        var peers = 0
        var registered = 0
        for (readiness in listOf(InvitationReadinessResult.TransientUnavailable("offline"),
                InvitationReadinessResult.MirrorPending(waiting()))) {
            assertEquals(InviteDeliveryAttempt.RETRY, GameInviteManager.dispatchInviteAttempt(
                readiness = { readiness }, isCurrent = { true },
                publishPeer = { peers++ }, registerEvent = { registered++ }))
        }
        assertEquals(1, peers)
        assertEquals(0, registered)
    }

    @Test
    fun accountSwitchOrExpiryDuringReadinessSuppressesBothChannels() = runBlocking {
        var current = true
        assertEquals(InviteDeliveryAttempt.REJECTED, GameInviteManager.dispatchInviteAttempt(
            readiness = { current = false; InvitationReadinessResult.Ready }, isCurrent = { current },
            publishPeer = { fail("Expired account or invite cannot publish") },
            registerEvent = { fail("Expired account or invite cannot register") }))
    }

    @Test
    fun registrationFailureIsNotReportedAsSent() = runBlocking {
        try {
            GameInviteManager.dispatchInviteAttempt(
                readiness = { InvitationReadinessResult.Ready }, isCurrent = { true },
                publishPeer = {}, registerEvent = { throw IOException("Event service unavailable") })
            fail("The caller must retain retry work when registration fails")
        } catch (_: IOException) { /* UI queues the original payload; worker retains retry intent. */ }
    }
}
