package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

class AppConvenienceRegressionTest {
    private fun start(seed: Long = 77L) = RoomMessagePacket("START_GAME", seed = seed, playerId = "k",
        startCommitDeadline = 10_000L, startClockOffsets = mapOf("k" to 0L, "s" to 5000L),
        turnClock = MultiplayerTurnClock.start(1, "k", 10_000L))

    @Test fun runningMatchUsesCalibratedTurnAndDoesNotApplyOffsetTwice() {
        val host = MatchClockSession().apply { begin(77L, "k", start().startClockOffsets) }
        val guest = MatchClockSession()
        val received = guest.ingress(start(), "s", 0L)
        guest.acceptStart(received)
        assertEquals(30, start().turnClock!!.remainingSeconds(10_000L))
        assertEquals(30, received.turnClock!!.remainingSeconds(15_000L))
        assertEquals(received, guest.ingress(received, "s", 77L))
        val timeoutAtHost = 40_000L
        assertEquals(5_000L, received.turnClock!!.remainingMillis(timeoutAtHost))
        val remote = RoomMessagePacket("GAME_SYNC", seed = 77L, playerId = "s", clockOffsetMillis = guest.offset,
            turnClock = received.turnClock!!.welcomeBack(15_000L), timestamp = 20_000L)
        val localized = host.ingress(remote, "k", 77L)
        assertEquals(15_000L, localized.timestamp)
        assertEquals(start().turnClock, localized.turnClock)
        assertEquals(localized, host.ingress(localized, "k", 77L))
    }

    @Test fun slowerPhoneAndThreePlayerStartUseTheSameRemainingBudget() {
        val packet = start().copy(startClockOffsets = mapOf("k" to 0L, "s" to -5000L, "b" to 3000L))
        for ((id, offset) in packet.startClockOffsets) {
            val session = MatchClockSession()
            val received = session.ingress(packet, id, 0L)
            session.acceptStart(received)
            assertEquals(30, received.turnClock!!.remainingSeconds(10_000L + offset))
            assertEquals(17, received.turnClock!!.remainingSeconds(23_000L + offset))
            assertEquals(3, LobbyStartSchedule.secondsUntil(LobbyStartSchedule.deadlineFor(packet, id), 7000L + offset))
        }
    }

    @Test fun coldRecoveryRetainsOffsetAndFrozenCountdown() {
        val session = MatchClockSession()
        val received = session.ingress(start(), "s", 0L)
        session.acceptStart(received)
        val data = session.checkpoint(OngoingMatchData("ABC123", 77L, 5,
            turnClock = received.turnClock!!.freeze(22_000L)))
        val restored = MatchClockSession().apply { restore(data) }
        assertEquals(5000L, restored.offset)
        assertEquals(start().startClockOffsets, restored.offsets)
        val peer = RoomMessagePacket("GAME_SYNC", seed = 77L, playerId = "k",
            turnClock = start().turnClock!!.freeze(17_000L), timestamp = 17_000L)
        val localized = restored.ingress(peer, "s", 77L)
        assertEquals(data.turnClock, localized.turnClock)
        assertEquals(23_000L, localized.turnClock!!.remainingMillis(999_999L))
    }

    @Test fun rematchUsesNewStartersClockAndIgnoredStartDoesNotReplaceCurrentOffset() {
        val session = MatchClockSession()
        session.acceptStart(session.ingress(start(), "s", 0L))
        session.ingress(start(999L).copy(startClockOffsets = mapOf("s" to 70_000L)), "s", 77L)
        assertEquals(77L, session.seed)
        assertEquals(5000L, session.offset)
        val next = session.rematchOffsets("s", listOf("k", "s"))
        assertEquals(mapOf("k" to -5000L, "s" to 0L), next)
        session.begin(88L, "s", next)
        assertEquals(0L, session.offset)
        assertEquals(30, MatchClockSession().ingress(start(88L).copy(type = "PLAY_AGAIN", startClockOffsets = next),
            "k", 77L).turnClock!!.remainingSeconds(5000L))
    }

    @Test fun absenceWelcomeBackSurvivesClockConversionAndFreezeResume() {
        val session = MatchClockSession()
        session.acceptStart(session.ingress(start(), "s", 0L))
        val packet = RoomMessagePacket("PLAYER_PRESENCE", seed = 77L, playerId = "k", timestamp = 13_000L,
            turnClock = MultiplayerTurnClock.start(2, "k", 10_000L, true).welcomeBack(12_000L).freeze(13_000L))
        val local = session.ingress(packet, "s", 77L)
        assertTrue(local.turnClock!!.welcomeBackGranted)
        assertEquals(29_000L, local.turnClock!!.resume(20_000L).remainingMillis(20_000L))
        assertEquals(local.turnClock, local.turnClock!!.welcomeBack(18_000L))
    }

    @Test fun nearbyOnlineTurnNeverGetsWelcomeBackTime() {
        val timer = TurnWelcomeBackTracker()
        timer.bind(77L, 1, "s", false)
        assertEquals(18, timer.onPresence(18, false))
        assertEquals(12, timer.onPresence(12, true))
        assertEquals(8, timer.onPresence(8, false))
        assertEquals(7, timer.onPresence(7, true))
    }

    @Test fun nearbyAbsenceTurnPromotesOnlyOnceAndSurvivesPauseRestart() {
        val timer = TurnWelcomeBackTracker()
        timer.bind(77L, 1, "s", true)
        assertEquals(5, timer.initialSeconds)
        assertEquals(30, timer.onPresence(2, true))
        timer.bind(77L, 1, "s", true) // same effect restarted after pause
        assertEquals(18, timer.onPresence(18, false))
        assertEquals(12, timer.onPresence(12, true))
        timer.bind(77L, 2, "s", true)
        assertEquals(30, timer.onPresence(4, true))
        timer.bind(88L, 2, "s", true)
        assertEquals(30, timer.onPresence(1, true))
    }

    @Test fun nearbyExpiredAbsenceTurnIsNeverRevived() {
        val timer = TurnWelcomeBackTracker()
        timer.bind(77L, 1, "s", true)
        assertEquals(0, timer.onPresence(0, true))
    }

    private fun invite(id: String = "invite", expires: Long = 121_000L) =
        GameInvite("kevin", "Kevin", roomCode = "ABC123", timestamp = 1000L, expiresAt = expires,
            eventId = id, recipientUid = "s", recipientUsername = "stuart")

    @Test fun openInvitationListExpiresWithoutReopeningTheTab() = runTest {
        val store = PendingInviteStore(this) { testScheduler.currentTime + 1000L }
        store.put(invite())
        assertEquals(1, store.invites.value.size)
        advanceTimeBy(119_999L); runCurrent()
        assertEquals(1, store.invites.value.size)
        advanceTimeBy(1); runCurrent()
        assertTrue(store.invites.value.isEmpty())
    }

    @Test fun handledInvitesDisappearButFailedJoinLeavesUnexpiredInviteAvailable() = runTest {
        val store = PendingInviteStore(this) { testScheduler.currentTime + 1000L }
        store.put(invite())
        // A failed attempt never calls remove; only success/explicit decline does.
        assertEquals(listOf(invite()), store.invites.value)
        store.remove("invite")
        assertTrue(store.invites.value.isEmpty())
        store.put(invite("other"))
        store.clear()
        assertTrue(store.invites.value.isEmpty())
    }

    @Test fun duplicateExpiryCannotRemoveAnotherInvitationAndRequestsAreNotInvites() = runTest {
        val store = PendingInviteStore(this) { testScheduler.currentTime + 1000L }
        store.put(invite(expires = 2000L)); runCurrent()
        store.put(invite(expires = 4000L))
        store.put(invite("request").copy(isPlayRequest = true))
        advanceTimeBy(1000L); runCurrent()
        assertEquals(1, store.invites.value.size)
        advanceTimeBy(2000L); runCurrent()
        assertTrue(store.invites.value.isEmpty())
    }
}
