package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.*
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class InviteJoinAttemptTest {
    private var now = System.currentTimeMillis()
    private val account = NotificationAccount("stuart", "stuart", "generation", "device")
    private val prefs = FakeSharedPreferences()
    private val ledger = NotificationLedger(prefs) { now }
    private val attempt = InviteJoinAttempt { now }
    private val invite = GameInvite("kevin", "Kevin", roomCode = "RETRY1", senderUid = "kevin",
        recipientUid = account.uid, recipientUsername = account.username, timestamp = now)
    private val room = OnlineRoomSession(invite.roomCode, "kevin", "kevin", "Kevin")
    private var currentAccount = true
    private var finished = 0

    private suspend fun join(invitation: GameInvite? = invite, block: suspend () -> RoomJoinResult) =
        attempt.join(invitation, account, ledger, { currentAccount }, {
            finished++
            ledger.markHandled(it.toEvent())
        }, block)

    @Test fun failedAdmissionCanRetryTheSameInvitationThroughTheRealRoomRegistry() = runTest {
        val code = UUID.randomUUID().toString().take(6).uppercase()
        val host = Player("kevin", "Kevin", "kevin", isHost = true)
        val guest = Player(account.uid, "Stuart", account.username)
        var available = false
        var stored = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
        val invitation = invite.copy(roomCode = code, roomSessionId = stored.sessionId)
        val old = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> =
                if (available) Result.success(stored) else Result.failure(IOException("Temporary connection failure"))
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit {
                stored = next
                return RoomMetadataCommit.Applied(next)
            }
        }
        try {
            assertTrue(ledger.present(invitation.toEvent(), system = false).show)
            val failed = join(invitation) { OnlineRoomRegistry.joinRoom(code, guest, invitation) }
            assertFalse("The unavailable room cannot admit the guest", failed is RoomJoinResult.Success)
            assertFalse("A failed join must leave the original invitation usable", ledger.handled(account.uid, invitation.eventId))
            assertTrue("The UI can restore a previously presented invitation after failure",
                attempt.canRetry(invitation, account, NotificationLedger(prefs) { now }))
            assertEquals(0, finished)
            available = true
            assertTrue(join(invitation) { OnlineRoomRegistry.joinRoom(code, guest, invitation) } is RoomJoinResult.Success)
            assertTrue(ledger.handled(account.uid, invitation.eventId))
            assertEquals(setOf(host.id, guest.id), stored.players.map { it.id }.toSet())
            assertEquals(1, finished)
        } finally {
            RoomMetadataApi.transportOverride = old
            OnlineRoomRegistry.recentRoomCache.remove(code)
        }
        Unit
    }

    @Test fun invitationIsHandledOnlyAfterSuccessfulAdmission() = runTest {
        assertTrue(join {
            assertFalse("Accept must not consume the invite before the room replies", ledger.handled(account.uid, invite.eventId))
            assertEquals(0, finished)
            RoomJoinResult.Success(room)
        } is RoomJoinResult.Success)
        assertTrue(ledger.handled(account.uid, invite.eventId))
        assertEquals(1, finished)
    }

    @Test fun everyFailedAdmissionLeavesTheOriginalExpiryAndInvitationIntact() = runTest {
        val originalExpiry = invite.expiresAt
        for (failure in listOf(RoomJoinResult.NotFound("Offline"), RoomJoinResult.Error("Network"),
            RoomJoinResult.AlreadyFull("Full"), RoomJoinResult.AlreadyStarted("Started"), RoomJoinResult.Expired("Room expired"))) {
            assertEquals(failure, join { failure })
            assertFalse(ledger.handled(account.uid, invite.eventId))
            assertEquals(originalExpiry, invite.expiresAt)
        }
        assertEquals(0, finished)
    }

    @Test fun duplicateAcceptWhileJoiningDoesNotSendAnotherAdmission() = runTest {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<RoomJoinResult>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            join { calls++; started.complete(Unit); reply.await() }
        }
        started.await()
        assertNull(join { calls++; RoomJoinResult.Success(room) })
        assertEquals(1, calls)
        reply.complete(RoomJoinResult.Error("Connection lost"))
        first.await()
        assertFalse(ledger.handled(account.uid, invite.eventId))
        assertTrue(join { calls++; RoomJoinResult.Success(room) } is RoomJoinResult.Success)
        assertEquals(2, calls)
        assertEquals(1, finished)
    }

    @Test fun cancelledAdmissionLeavesInvitationUsableAndReleasesTheAttempt() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { join { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        assertFalse(ledger.handled(account.uid, invite.eventId))
        assertTrue(join { RoomJoinResult.Success(room) } is RoomJoinResult.Success)
    }

    @Test fun unexpectedAdmissionFailureDoesNotConsumeInvitationOrBlockRetry() = runTest {
        assertTrue(runCatching { join { throw IOException("Connection lost") } }.exceptionOrNull() is IOException)
        assertFalse(ledger.handled(account.uid, invite.eventId))
        assertTrue(join { RoomJoinResult.Success(room) } is RoomJoinResult.Success)
    }

    @Test fun expiredInvitationCannotStartAnotherAdmission() = runTest {
        now = invite.expiresAt
        var calls = 0
        assertTrue(join { calls++; RoomJoinResult.Success(room) } is RoomJoinResult.Error)
        assertEquals(0, calls)
        assertEquals(0, finished)
    }

    @Test fun notFoundRetryChecksOriginalExpiryBeforeSendingAgain() = runTest {
        var calls = 0
        assertTrue(join {
            calls++
            now = invite.expiresAt
            RoomJoinResult.NotFound("Temporary failure")
        } is RoomJoinResult.Error)
        assertEquals(1, calls)
    }

    @Test fun alreadyHandledInvitationCannotBeAcceptedAgain() = runTest {
        ledger.markHandled(invite.toEvent())
        var calls = 0
        assertTrue(join { calls++; RoomJoinResult.Success(room) } is RoomJoinResult.Error)
        assertEquals(0, calls)
        assertEquals(0, finished)
    }

    @Test fun accountChangeDuringAdmissionCannotConsumeOrNavigateForAnotherAccount() = runTest {
        assertNull(join { currentAccount = false; RoomJoinResult.Success(room) })
        assertFalse(ledger.handled(account.uid, invite.eventId))
        assertEquals(0, finished)
    }

    @Test fun successfulAdmissionStartedBeforeExpiryIsNotUndoneByALateReply() = runTest {
        assertTrue(join { now = invite.expiresAt + 1; RoomJoinResult.Success(room) } is RoomJoinResult.Success)
        assertEquals(1, finished)
    }

    @Test fun roomCodeAdmissionStillWorksWithoutAnInvitation() = runTest {
        assertTrue(join(null) { RoomJoinResult.Success(room) } is RoomJoinResult.Success)
        assertEquals(0, finished)
    }

    @Test fun aTemporaryMissingRoomRetriesOnceWithoutConsumingBeforeTheReply() = runTest {
        var calls = 0
        assertTrue(join {
            calls++
            assertFalse(ledger.handled(account.uid, invite.eventId))
            if (calls == 1) RoomJoinResult.NotFound("Try again") else RoomJoinResult.Success(room)
        } is RoomJoinResult.Success)
        assertEquals(2, calls)
        assertEquals(1, finished)
        assertFalse(attempt.canRetry(invite, account, ledger))
    }

    @Test fun accountChangeDuringTheRetryDelayPreventsAnotherAdmission() = runTest {
        var calls = 0
        assertNull(join { calls++; currentAccount = false; RoomJoinResult.NotFound("Offline") })
        assertEquals(1, calls)
        assertEquals(0, finished)
    }

    @Test fun restorationRejectsExpiredDeclinedAndOtherAccountInvitations() = runTest {
        assertTrue(attempt.canRetry(invite, account, ledger))
        assertFalse(attempt.canRetry(invite, null, ledger))
        assertFalse(attempt.canRetry(invite, account.copy(uid = "someone-else"), ledger))
        ledger.markHandled(invite.toEvent())
        assertFalse(attempt.canRetry(invite, account, ledger))
        now = invite.expiresAt
        assertFalse(attempt.canRetry(invite, account, ledger))
    }
}
