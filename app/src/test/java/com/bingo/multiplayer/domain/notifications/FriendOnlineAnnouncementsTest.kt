package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class FriendOnlineAnnouncementsTest {
    private val start = 1_800_000_000_000L
    private val hour = FriendOnlineAnnouncements.OFFLINE_INTERVAL

    @Test fun firstAppearanceCreatesOneDurableNotice() {
        val prefs = FakeSharedPreferences(); val store = FriendOnlineAnnouncements(prefs)
        val first = store.active("bob", start)!!
        assertEquals(first, FriendOnlineAnnouncements(prefs).active("bob", start + 1))
        assertTrue(store.queued("bob", first.id, "@Stuart"))
        assertEquals(setOf("stuart"), FriendOnlineAnnouncements(prefs).active("bob", start + 2)!!.queued)
    }
    @Test fun fiveMinuteAbsenceDoesNotProduceAnotherNotice() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start); store.offline("bob", start + 10_000)
        assertNull(store.active("bob", start + 310_000))
    }
    @Test fun anHourSinceTheLastAlertDoesNotQualifyAFiveMinuteAbsence() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start)
        for (minute in 1..55) store.active("bob", start + minute * 60_000L)
        store.offline("bob", start + 55 * 60_000L)
        assertNull(store.active("bob", start + hour))
    }
    @Test fun repeatedFiveMinuteReturnsForTwoHoursStaySilent() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start)
        for (minute in 5..120 step 5) {
            store.offline("bob", start + (minute - 4) * 60_000L)
            assertNull(store.active("bob", start + minute * 60_000L))
        }
    }
    @Test fun exactContinuousHourProducesANewNotice() {
        val prefs = FakeSharedPreferences(); val store = FriendOnlineAnnouncements(prefs)
        val first = store.active("bob", start)!!; store.offline("bob", start + 1_000)
        assertNull(FriendOnlineAnnouncements(prefs).active("other", 0))
        val returned = FriendOnlineAnnouncements(prefs).active("bob", start + 1_000 + hour)!!
        assertNotEquals(first.id, returned.id)
    }
    @Test fun returningJustBeforeAnHourRestartsTheInactivityPeriod() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start); store.offline("bob", start + 1_000)
        assertNull(store.active("bob", start + hour))
        store.offline("bob", start + hour + 1_000)
        assertNull(store.active("bob", start + hour + 301_000))
    }
    @Test fun processDeathWithoutBackgroundCallbackUsesTheLastCheckpoint() {
        val prefs = FakeSharedPreferences(); FriendOnlineAnnouncements(prefs).active("bob", start)
        assertNotNull(FriendOnlineAnnouncements(prefs).active("bob", start + hour + 15_000))
    }
    @Test fun shortProcessRestartDoesNotResetTheNotificationClock() {
        val prefs = FakeSharedPreferences(); FriendOnlineAnnouncements(prefs).active("bob", start)
        assertNull(FriendOnlineAnnouncements(prefs).active("bob", start + 300_000))
    }
    @Test fun anAbruptKillCannotTurnASlightlyShorterAbsenceIntoAnHour() {
        val prefs = FakeSharedPreferences(); FriendOnlineAnnouncements(prefs).active("bob", start)
        assertNull(FriendOnlineAnnouncements(prefs).active("bob", start + hour - 1))
    }
    @Test fun duplicateOfflineCallbacksDoNotPostponeTheHour() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start); store.offline("bob", start + 1_000)
        store.offline("bob", start + 300_000)
        assertNotNull(store.active("bob", start + hour + 1_000))
    }
    @Test fun accountsHaveIndependentAvailabilityAndRecipientHistory() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        val bob = store.active("bob", start)!!;store.queued("bob", bob.id, "stuart")
        val kevin = store.active("kevin", start + 1)!!
        assertNotEquals(bob.id, kevin.id); assertTrue(kevin.queued.isEmpty())
    }
    @Test fun failedQueueCanRetryTheSameIdWithoutExtendingItsLifetime() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        val first = store.active("bob", start)!!
        assertEquals(first, store.active("bob", start + 60_000))
        assertNull(store.active("bob", start + NotificationKind.FRIEND_ONLINE.lifetimeMillis))
        assertNull(store.active("bob", start + 300_000))
    }
    @Test fun lateQueueCompletionCannotConsumeANewerReturn() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        val first = store.active("bob", start)!!; store.offline("bob", start + 1)
        val second = store.active("bob", start + hour + 1)!!
        assertFalse(store.queued("bob", first.id, "stuart"))
        assertTrue(store.active("bob", second.createdAt + 1)!!.queued.isEmpty())
    }
    @Test fun clockMovingBackwardsDoesNotCreateANewAnnouncement() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        store.active("bob", start);store.offline("bob", start + 1_000)
        assertNull(store.active("bob", start - 60_000))
    }
    @Test fun concurrentLifecycleAndHeartbeatCallsReuseOneNotice() {
        val prefs = FakeSharedPreferences(); val pool = Executors.newFixedThreadPool(4)
        try {
            val ids = (1..20).map { pool.submit<String> { FriendOnlineAnnouncements(prefs).active("bob", start)!!.id } }.map { it.get() }
            assertEquals(1, ids.distinct().size)
        } finally { pool.shutdownNow() }
    }
}
