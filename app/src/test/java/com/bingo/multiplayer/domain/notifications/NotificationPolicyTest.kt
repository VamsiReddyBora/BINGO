package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotificationPolicyTest {
    private val now = 1_800_000_000_000L
    private val account = NotificationAccount("bob-id", "bob", "generation-one", "installation")
    private fun event(kind: NotificationKind = NotificationKind.GAME_INVITE) = NotificationEvent("event-one", kind,
        "kevin-id", "kevin", "Kevin", "bob-id", "bob", now, now + kind.lifetimeMillis,
        roomCode = "ABC123", requestId = "request", installationId = "installation", generation = "generation-one")

    @Test fun everyKindAcceptsFreshEvents() { NotificationKind.entries.forEach { assertTrue(NotificationPolicy.eligible(event(it), account, now)) } }
    @Test fun exactExpiryRejectsAllKinds() { NotificationKind.entries.forEach { assertFalse(NotificationPolicy.eligible(event(it), account, now + it.lifetimeMillis)) } }
    @Test fun finalMillisecondBeforeExpiryIsAccepted() { NotificationKind.entries.forEach { assertTrue(NotificationPolicy.fresh(event(it), now + it.lifetimeMillis - 1)) } }
    @Test fun ancientEventCannotBeMadeFreshByPostingNotificationNow() { assertFalse(NotificationPolicy.eligible(event(), account, now + 20 * 60_000)) }
    @Test fun missingAndMalformedTimesAreRejected() { listOf(0L, -1L, Long.MIN_VALUE).forEach { assertFalse(NotificationPolicy.fresh(event().copy(createdAt = it), now)) } }
    @Test fun unreasonableFutureTimeIsRejected() { assertFalse(NotificationPolicy.fresh(event().copy(createdAt = now+5001, expiresAt = now+125001), now)) }
    @Test fun boundedClockSkewIsAccepted() { assertTrue(NotificationPolicy.fresh(event().copy(createdAt = now+5000, expiresAt = now+125000), now)) }
    @Test fun ttlCannotExceedKindPolicy() { assertFalse(NotificationPolicy.fresh(event().copy(expiresAt = now+120001), now)) }
    @Test fun invalidAndEmptyExpiryIsRejected() { listOf(now, now-1).forEach { assertFalse(NotificationPolicy.fresh(event().copy(expiresAt = it), now)) } }
    @Test fun loggedOutAccountNeverReceivesEvents() { assertFalse(NotificationPolicy.eligible(event(), null, now)) }
    @Test fun wrongUidAndWrongUsernameAreRejectedIndependently() {
        assertFalse(NotificationPolicy.eligible(event().copy(recipientUid="stuart"),account,now))
        assertFalse(NotificationPolicy.eligible(event().copy(recipientUsername="stuart"),account,now))
    }
    @Test fun oldAccountPushCannotCrossGenerationEvenWhenUserSignsInAgain() { assertFalse(NotificationPolicy.eligible(event(),account.copy(generation="new-generation"),now,push=true)) }
    @Test fun otherInstallationPushCannotBeAccepted() { assertFalse(NotificationPolicy.eligible(event(),account.copy(installationId="other-device"),now,push=true)) }
    @Test fun authenticatedInboxDoesNotRequirePushInstallationBinding() { assertTrue(NotificationPolicy.eligible(event().copy(installationId="",generation=""),account,now)) }
    @Test fun selfNotificationsAndMissingSenderAreRejected() {
        assertFalse(NotificationPolicy.eligible(event().copy(senderUid=account.uid),account,now))
        assertFalse(NotificationPolicy.eligible(event().copy(senderUsername="@Bob"),account,now))
        assertFalse(NotificationPolicy.eligible(event().copy(senderUid=""),account,now))
    }
    @Test fun malformedRoomsAndMissingRequestIdsAreRejected() {
        listOf("", "aBC123", "ABC12", "ABC1234", "ABC/23").forEach { assertFalse(NotificationPolicy.eligible(event().copy(roomCode=it),account,now)) }
        assertFalse(NotificationPolicy.eligible(event(NotificationKind.FRIEND_REQUEST).copy(requestId=""),account,now))
    }
    @Test fun allActiveMatchModesAndPausedHostWaitMustBlockInvitationJoin() {
        for(sameRoom in listOf(true,false)) for(auth in listOf(true,false)) assertFalse(NotificationPolicy.canJoinInvite(true,sameRoom,auth))
        assertFalse(NotificationPolicy.canJoinInvite(false,true,true));assertFalse(NotificationPolicy.canJoinInvite(false,false,false))
        assertTrue(NotificationPolicy.canJoinInvite(false,false,true))
    }
    @Test fun stableEventIdentityHandlesDelimiterAmbiguityAndNewInvitations() {
        assertEquals(NotificationPolicy.eventId("invite","a","bc"),NotificationPolicy.eventId("invite","a","bc"))
        assertNotEquals(NotificationPolicy.eventId("invite","a","bc"),NotificationPolicy.eventId("invite","ab","c"))
        assertNotEquals(NotificationPolicy.eventId("invite","ABC123","1"),NotificationPolicy.eventId("invite","ABC123","2"))
    }
    @Test fun transportsShareOneDurablePresentationLedger() {
        val prefs=FakeSharedPreferences();val a=NotificationLedger(prefs){now};val b=NotificationLedger(prefs){now}
        assertEquals(NotificationLedger.Presentation(true,true),a.present(event(),true))
        assertEquals(NotificationLedger.Presentation(false,false),b.present(event(),true))
        assertEquals(NotificationLedger.Presentation(true,false),b.present(event(),false))
        assertEquals(NotificationLedger.Presentation(false,false),a.present(event(),false))
    }
    @Test fun declineSurvivesProcessRecreationAndLateTransportCopies() {
        val prefs=FakeSharedPreferences();NotificationLedger(prefs){now}.markHandled(event())
        val recreated=NotificationLedger(prefs){now}
        assertTrue(recreated.handled(account.uid,event().id))
        assertFalse(recreated.present(event(),true).show);assertFalse(recreated.present(event(),false).show)
    }
    @Test fun handledInvitesDoNotResurfaceAfterSixtySeconds() {
        val prefs=FakeSharedPreferences();NotificationLedger(prefs){now}.markHandled(event())
        assertTrue(NotificationLedger(prefs){now+61000}.handled(account.uid,event().id))
    }
    @Test fun handlingAnOldInviteDoesNotRemoveANewerInvite() {
        val prefs=FakeSharedPreferences();val ledger=NotificationLedger(prefs){now}
        ledger.markHandled(event());assertTrue(ledger.present(event().copy(id="new-invite"),true).show)
    }
    @Test fun eventLedgerIsAccountScoped() {
        val prefs=FakeSharedPreferences();val ledger=NotificationLedger(prefs){now}
        ledger.markHandled(event());assertFalse(ledger.handled("stuart-id",event().id))
    }
    @Test fun failedSystemPostingDoesNotConsumeFuturePresentation() {
        val ledger=NotificationLedger(FakeSharedPreferences()){now}
        assertTrue(ledger.present(event(),true).show);ledger.presentationFailed(event(),true,false)
        assertEquals(NotificationLedger.Presentation(true,true),ledger.present(event(),true))
    }
    @Test fun concurrentWorkerAndUiClaimsAcrossDifferentInstancesAlertOnce() {
        val prefs=FakeSharedPreferences();val executor=Executors.newFixedThreadPool(8)
        val claims=(1..64).map{executor.submit<NotificationLedger.Presentation>{NotificationLedger(prefs){now}.present(event(),true)}}.map{it.get(5,TimeUnit.SECONDS)}
        executor.shutdownNow();assertEquals(1,claims.count{it.show});assertEquals(1,claims.count{it.alert})
    }
    @Test fun generationPersistsOnColdStartButChangesOnLogoutLogin() {
        val prefs=FakeSharedPreferences();val a=NotificationAccountStore(prefs);val first=a.bind("bob-id","@BoB")!!
        assertEquals(first,NotificationAccountStore(prefs).bind("bob-id","bob"));a.bind(null,null)
        val second=NotificationAccountStore(prefs).bind("bob-id","bob")!!
        assertNotEquals(first.generation,second.generation);assertEquals(first.installationId,second.installationId);assertTrue(second.sequence>first.sequence)
    }
    @Test fun delayedTokenSyncCannotWriteCredentialsForAnotherAccount() {
        val prefs=FakeSharedPreferences();val a=NotificationAccountStore(prefs);val b=NotificationAccountStore(prefs)
        val old=a.bind("bob-id","bob")!!;val current=b.bind("stuart-id","stuart")!!
        assertFalse(a.saveSession(old,"old-token",now+86400000,"old-registration","old-revoke"))
        assertTrue(b.saveSession(current,"new-token",now+86400000,"new-registration","new-revoke"))
        a.forgetSession(old);assertEquals("new-token",b.session(current,now));assertNull(a.session(old,now))
    }
    @Test fun registrationExpiryAndRevocationArePreservedForOfflineLogout() {
        val store=NotificationAccountStore(FakeSharedPreferences());val a=store.bind("bob-id","bob")!!
        store.saveSession(a,"session",now+120000,"registration","revocation")
        assertEquals("session",store.session(a,now));assertNull(store.session(a,now+60001))
        store.bind(null,null);assertEquals("registration" to "revocation",store.revocation());assertNull(store.session(a,now))
    }
    @Test fun socialAlertsDoNotReplayAcrossForegroundAndBackground() {
        for (kind in listOf(NotificationKind.FRIEND_REQUEST, NotificationKind.FRIEND_ACCEPTED, NotificationKind.FRIEND_ONLINE)) {
            val ledger = NotificationLedger(FakeSharedPreferences()) { now }
            assertTrue(ledger.present(event(kind), true).show)
            assertFalse(ledger.present(event(kind), false).show)
        }
    }
    @Test fun repeatedAppSwitchesDoNotSpamOnlineAlerts() {
        val prefs = FakeSharedPreferences()
        assertTrue(NotificationLedger(prefs) { now }.present(event(NotificationKind.FRIEND_ONLINE), true).show)
        assertFalse(NotificationLedger(prefs) { now + 20000 }.present(event(NotificationKind.FRIEND_ONLINE).copy(id = "another-session", createdAt = now+20000, expiresAt = now+35000), false).show)
        assertTrue(NotificationLedger(prefs) { now + 120000 }.present(event(NotificationKind.FRIEND_ONLINE).copy(id = "later-session", createdAt = now+120000, expiresAt = now+135000), false).show)
    }
    @Test fun corruptLedgerFailsClosedWithoutResurrectingHandledEvents() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("events", "corrupt-json").commit()
        val ledger = NotificationLedger(prefs) { now }
        assertTrue(ledger.handled(account.uid, event().id)); assertFalse(ledger.present(event(), true).show)
        assertFalse(ledger.markHandled(event()))
    }

}
