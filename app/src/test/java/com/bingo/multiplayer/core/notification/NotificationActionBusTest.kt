package com.bingo.multiplayer.core.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NotificationActionBusTest {

    @Before
    fun setup() {
        NotificationActionBus.clear()
    }

    @Test
    fun testPostAndConsumeSendInviteAction() {
        assertNull(NotificationActionBus.pendingAction.value)

        val sendInvite = NotificationAction.SendInvite(
            friendUsername = "alice",
            friendDisplayName = "Alice"
        )
        NotificationActionBus.postAction(sendInvite)

        assertEquals(sendInvite, NotificationActionBus.pendingAction.value)

        val consumed = NotificationActionBus.consumeAction()
        assertEquals(sendInvite, consumed)
        assertNull(NotificationActionBus.pendingAction.value)
    }

    @Test
    fun testPostAndConsumeAcceptInviteAction() {
        val acceptInvite = NotificationAction.AcceptInvite(
            roomCode = "ROOM42",
            hostUsername = "bob"
        )
        NotificationActionBus.postAction(acceptInvite)

        assertEquals(acceptInvite, NotificationActionBus.pendingAction.value)

        val consumed = NotificationActionBus.consumeAction()
        assertEquals(acceptInvite, consumed)
        assertNull(NotificationActionBus.pendingAction.value)
    }

    @Test
    fun testPostAndConsumeInviteDeclinedAction() {
        val declined = NotificationAction.InviteDeclined(roomCode = "XYZ789")
        NotificationActionBus.postAction(declined)

        assertEquals(declined, NotificationActionBus.pendingAction.value)

        val consumed = NotificationActionBus.consumeAction()
        assertEquals(declined, consumed)
        assertNull(NotificationActionBus.pendingAction.value)
    }

    @Test
    fun testClear() {
        NotificationActionBus.postAction(NotificationAction.InviteDeclined("ROOM1"))
        NotificationActionBus.clear()
        assertNull(NotificationActionBus.pendingAction.value)
    }
}
