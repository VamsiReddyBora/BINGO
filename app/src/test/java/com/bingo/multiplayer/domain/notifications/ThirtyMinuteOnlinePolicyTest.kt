package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ThirtyMinuteOnlinePolicyTest {
    private val start = 1_800_000_000_000L
    @Test fun fiveMinutesAndJustUnderThirtyDoNotAnnounceAnotherReturn() {
        for (absence in listOf(300_000L, 1_799_999L)) {
            val store = FriendOnlineAnnouncements(FakeSharedPreferences())
            store.active("bob", start); store.offline("bob", start + 1_000)
            assertNull(store.active("bob", start + 1_000 + absence))
        }
    }
    @Test fun exactlyThirtyMinutesCreatesANewAnnouncement() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        val old = store.active("bob", start)!!; store.offline("bob", start + 1_000)
        val next = store.active("bob", start + 1_801_000)!!
        assertNotEquals(old.id, next.id)
    }
    @Test fun receiverSuppressesFiveAndTwentyNineMinutesButAllowsThirty() {
        val prefs = FakeSharedPreferences()
        fun event(id: String, at: Long) = NotificationEvent(id, NotificationKind.FRIEND_ONLINE,
            "bob", "bob", "Bob", "stuart", "stuart", at, at + 120_000)
        assertTrue(NotificationLedger(prefs) { start }.present(event("first", start), true).show)
        assertFalse(NotificationLedger(prefs) { start + 300_000 }.present(event("five", start + 300_000), true).show)
        assertFalse(NotificationLedger(prefs) { start + 1_799_999 }.present(event("early", start + 1_799_999), true).show)
        assertTrue(NotificationLedger(prefs) { start + 1_800_000 }.present(event("next", start + 1_800_000), true).show)
    }
}
