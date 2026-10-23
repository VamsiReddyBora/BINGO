package com.bingo.multiplayer.domain.notifications

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.bingo.multiplayer.domain.network.toInvite
import org.junit.Assert.*
import org.junit.Test

class OfflineInvitationPushTest {
    private val account = NotificationAccount("s", "stuart", "generation", "installation")
    private val now = 100_000L
    private fun invite() = NotificationEvent("game_invite:test", NotificationKind.GAME_INVITE,
        "k", "kevin", "Kevin", "s", "stuart", now, now + 120_000L,
        roomCode = "ABC123", roomSessionId = "room-session", generation = "generation", installationId = "installation")
    private fun data(event: NotificationEvent) = mapOf("version" to "2", "event" to Json.encodeToString(event))

    @Test fun offlineInvitationEntersTheRealFcmIngressWithItsOriginalRoomAndLifetime() {
        val expected = invite()
        assertEquals(expected, NotificationPushIngress.event(data(expected), account, now) { _, _ -> false })
    }
    @Test fun recipientBindingAndExpiryStillRejectObsoletePushes() {
        for (event in listOf(invite().copy(recipientUid = "other"), invite().copy(generation = "old"),
            invite().copy(installationId = "other"), invite().copy(expiresAt = now - 1))) {
            assertNull(NotificationPushIngress.event(data(event), account, now) { _, _ -> false })
        }
        assertNull(NotificationPushIngress.event(data(invite()), null, now) { _, _ -> false })
        assertNull(NotificationPushIngress.event(data(invite()), account, now) { _, _ -> true })
    }
    @Test fun notificationConversionPreservesRoomSessionAndEventIdentityForAccept() {
        val event = NotificationPushIngress.event(data(invite()), account, now) { _, _ -> false }
        assertNotNull(event)
        val converted = event!!.toInvite()
        assertEquals(event.id, converted.eventId)
        assertEquals(event.roomSessionId, converted.roomSessionId)
        assertEquals(event.roomCode, converted.roomCode)
        assertEquals(event.expiresAt, converted.expiresAt)
    }
    @Test fun theRealPushDispatcherPresentsAnInviteBeforeSchedulingAnInboxFetch() {
        val stages = mutableListOf<String>()
        assertTrue(NotificationPushIngress.dispatch(data(invite()), account, now, { _, _ -> false },
            { stages += "present:${it.roomCode}" }, { stages += "sync:${it.id}" }))
        assertEquals(listOf("present:ABC123", "sync:game_invite:test"), stages)
    }
    @Test fun inboxNetworkFailureCannotPreventTheInitialInvitationPresentation() {
        var shown = false
        try {
            NotificationPushIngress.dispatch(data(invite()), account, now, { _, _ -> false },
                { shown = true }, { throw java.io.IOException("synthetic offline inbox") })
            fail("Expected the synthetic sync failure")
        } catch (_: java.io.IOException) { assertTrue(shown) }
    }
    @Test fun expiredOrWrongAccountPushCannotEnterTheImmediatePresentationPath() {
        for (event in listOf(invite().copy(generation = "old"), invite().copy(expiresAt = now))) {
            assertFalse(NotificationPushIngress.dispatch(data(event), account, now, { _, _ -> false },
                { fail("Rejected push was presented") }, { fail("Rejected push was synchronized") }))
        }
    }
    @Test fun friendRequestsContinueToUseCanonicalInboxValidation() {
        val request = invite().copy(kind = NotificationKind.FRIEND_REQUEST, requestId = "request")
        var synchronized = false
        NotificationPushIngress.dispatch(data(request), account, now, { _, _ -> false },
            { fail("Friend request skipped its inbox validation") }, { synchronized = true })
        assertTrue(synchronized)
    }
}
