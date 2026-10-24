package com.bingo.multiplayer.domain.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationPresentationTest {
    private fun event(kind: NotificationKind) = NotificationEvent("event", kind, "sender", "bob", "BOB",
        "receiver", "stuart", 1_800_000_000_000L, 1_800_000_120_000L, roomCode = "ABC123")

    @Test fun onlineSystemNotificationContainsExactlyOneOnlineMessage() {
        val text = NotificationPresentation.systemText(event(NotificationKind.FRIEND_ONLINE))
        assertEquals("🟢 BOB is online", text.title)
        assertNull("No repeated collapsed/expanded body", text.body)
    }
    @Test fun foregroundOnlineMessageKeepsTheSameSingleLine() {
        assertEquals("🟢 BOB is online", NotificationPresentation.message(event(NotificationKind.FRIEND_ONLINE)))
    }
    @Test fun matchInvitationTitleAndBodyArePreserved() {
        val text = NotificationPresentation.systemText(event(NotificationKind.GAME_INVITE))
        assertEquals("🎮 Match invite from BOB", text.title)
        assertEquals("BOB invited you to room #ABC123", text.body)
    }
    @Test fun friendRequestAndAcceptanceKeepTheirExistingContent() {
        assertEquals(NotificationText("👥 New friend request", "👥 @bob sent you a friend request!"),
            NotificationPresentation.systemText(event(NotificationKind.FRIEND_REQUEST)))
        assertEquals(NotificationText("🎉 Friend request accepted", "🎉 @bob accepted your friend request! You are now friends."),
            NotificationPresentation.systemText(event(NotificationKind.FRIEND_ACCEPTED)))
    }
}
