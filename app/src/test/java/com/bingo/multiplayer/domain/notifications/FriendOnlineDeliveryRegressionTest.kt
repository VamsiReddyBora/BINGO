package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class FriendOnlineDeliveryRegressionTest {
    private val start = 1_800_000_000_000L
    private fun online(time: Long, id: String) = NotificationEvent(id, NotificationKind.FRIEND_ONLINE,
        "bob-id", "bob", "Bob", "stuart-id", "stuart", time, time + NotificationKind.FRIEND_ONLINE.lifetimeMillis)

    @Test fun friendOnlineSurvivesThirtySecondsOfBackgroundScheduling() {
        assertTrue(NotificationPolicy.fresh(online(start, "return-one"), start + 30_000))
    }

    @Test fun anotherOnlineEventAfterFiveMinutesDoesNotAlertAgain() {
        val prefs = FakeSharedPreferences()
        assertTrue(NotificationLedger(prefs) { start }.present(online(start, "return-one"), true).show)
        // Historical name retained: a new notice stays silent until the configured interval ends.
        val beforeBoundary = start + FriendOnlineAnnouncements.OFFLINE_INTERVAL - 1
        assertFalse(NotificationLedger(prefs) { beforeBoundary }
            .present(online(beforeBoundary, "return-two"), false).show)
    }
    @Test fun aFailedAndroidPostCanRetryTheSameOnlineEvent() {
        val ledger = NotificationLedger(FakeSharedPreferences()) { start }
        val event = online(start, "retry-after-post-failure")
        assertTrue(ledger.present(event, true).show)
        ledger.presentationFailed(event, true, false)
        assertTrue(ledger.present(event, true).show)
    }
    @Test fun aDelayedFirstDeliveryDoesNotSuppressALaterEligibleReturn() {
        val prefs = FakeSharedPreferences()
        assertTrue(NotificationLedger(prefs) { start + 30_000 }.present(online(start, "first"), true).show)
        val next = start + FriendOnlineAnnouncements.OFFLINE_INTERVAL
        assertTrue(NotificationLedger(prefs) { next }.present(online(next, "second"), false).show)
    }
    @Test fun aStaleOnlineCacheEntryIsNotProofThatTheSenderWentOffline() {
        val event = online(start, "active-friend")
        assertTrue(NotificationPolicy.friendOnlineStillRelevant(event, start + 1_000, "ONLINE"))
        assertFalse(NotificationPolicy.friendOnlineStillRelevant(event, start + 1_000, "OFFLINE"))
        assertTrue(NotificationPolicy.friendOnlineStillRelevant(event, start - 1_000, "OFFLINE"))
    }
}
