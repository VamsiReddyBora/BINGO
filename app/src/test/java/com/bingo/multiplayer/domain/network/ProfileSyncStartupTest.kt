package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileSyncStartupTest {
    private val google = UserProfile("google_fixture", "tester", "Tester", provider = AuthProvider.GOOGLE)

    @Test fun googleAppAccountWithAnonymousFirebaseSessionCanOpenPublicStorage() = runBlocking {
        val attemptedTargets = mutableListOf<String?>()
        val connected = openProfileStorageSession(google, null) { _, target ->
            attemptedTargets += target
            target == null // Existing Firebase session is anonymous, as in the reported account.
        }
        assertTrue(connected)
        assertEquals(listOf<String?>(null), attemptedTargets)
    }
    @Test fun profileObserverIsAttachedEvenWhenFirebaseSignInFails() = runBlocking {
        val events = mutableListOf<String>()
        assertFalse(beginProfileSynchronization({ events += "observe" }, { events += "auth-failed"; false }, { events += "upload" }))
        assertEquals(listOf("observe", "auth-failed"), events)
    }
    @Test fun cachedProfileCanReceiveAdminEditBeforeSessionRecovery() = runBlocking {
        var current = google
        val remote = mapOf<String, Any?>("adminRevision" to 2L, "adminChanges" to mapOf(
            "level" to mapOf("revision" to 2L, "value" to 100)))
        beginProfileSynchronization({ current = CloudAdminPolicy.from(remote).applyTo(current) }, { false }, {})
        assertEquals(100, current.level)
    }
    @Test fun failedGoogleFirebaseCredentialFallsBackToTheExistingPublicStorageSession() = runBlocking {
        val tokens = mutableListOf<String?>()
        assertTrue(openProfileStorageSession(google, "google-id-token") { token, target ->
            tokens += token
            token == null && target == null
        })
        assertEquals(listOf("google-id-token", null), tokens)
    }
    @Test fun guestBindingAndTheAccountUidArePreserved() = runBlocking {
        val guest = google.copy(uid = "guest_fixture", provider = AuthProvider.GUEST)
        var target: String? = null
        assertTrue(openProfileStorageSession(guest, null) { _, requested -> target = requested; true })
        assertEquals(guest.uid, target)
    }
    @Test fun normalStartupWatchesBeforeUploadingAndKeepsTheExistingOrder() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(beginProfileSynchronization({ events += "observe" }, { events += "session"; true }, { events += "upload" }))
        assertEquals(listOf("observe", "session", "upload"), events)
    }
    @Test fun aProfileUploadWithoutLocalApplicationProofDoesNotCountAsAnAppAcknowledgement() {
        val old = mapOf<String, Any?>("adminRevision" to 2L, "adminAppliedRevision" to 2L)
        assertTrue(profileAcknowledgementNeeded(old, 2L, 72))
        assertFalse(profileAcknowledgementNeeded(old, 0L, 72))
    }
    @Test fun acknowledgementDoesNotRegressOrClaimAnUnseenServerEdit() {
        val newer = mapOf<String, Any?>("adminRevision" to 3L, "adminAppliedRevision" to 3L, "adminAppliedBuild" to 72)
        assertFalse(profileAcknowledgementNeeded(newer, 2L, 72))
        assertFalse(profileAcknowledgementNeeded(newer, 3L, 72))
        assertTrue(profileAcknowledgementNeeded(newer + ("adminAppliedRevision" to 2L), 3L, 72))
    }
}
