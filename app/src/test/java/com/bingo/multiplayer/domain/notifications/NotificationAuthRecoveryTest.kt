package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationAuthRecoveryTest {
    private val account = NotificationAccount("google_12345", "bob", "generation", "installation")

    @Test fun approvalRequiredKeepsGoogleCodeAndDoesNotBlindlyRetry() {
        val error = NotificationAuthRecovery.googleFailure(4)
        assertEquals(401, error.status)
        assertEquals(4, error.googleStatus)
        assertEquals(NotificationPhase.GOOGLE_SIGN_IN, error.phase)
        assertTrue(error.needsGoogleApproval)
        assertFalse(NotificationAuthRecovery.shouldRetry(error, 0, 200, 100))
    }
    @Test fun developerConfigurationErrorIsNotMislabelledAsExpiredAuthorization() {
        val error = NotificationAuthRecovery.googleFailure(10)
        assertEquals(NotificationFailure.GOOGLE_CONFIGURATION, error.failure)
        assertEquals(10, error.googleStatus)
        assertFalse(error.needsGoogleApproval)
        assertFalse(NotificationAuthRecovery.shouldRetry(error, 0, 200, 100))
    }
    @Test fun transientGoogleErrorsRemainRetryableAndKeepTheirCode() {
        for (code in listOf(7, 8, 15, 17, 12502)) {
            val error = NotificationAuthRecovery.googleFailure(code)
            assertEquals(code, error.googleStatus)
            assertFalse(error.needsGoogleApproval)
            assertTrue(NotificationAuthRecovery.shouldRetry(error, 0, 200, 100))
        }
    }
    @Test fun cancellationDoesNotRequestAnotherAutomaticSignIn() {
        for (code in listOf(16, 12501)) {
            val error = NotificationAuthRecovery.googleFailure(code)
            assertEquals(NotificationFailure.GOOGLE_CANCELLED, error.failure)
            assertFalse(error.needsGoogleApproval)
            assertFalse(NotificationAuthRecovery.shouldRetry(error, 0, 200, 100))
        }
    }
    @Test fun rejectedRelaySessionRenewsOnceAndReplaysTheSameOperation() = runBlocking {
        var sessions = 0; val requests = mutableListOf<String>(); val discarded = mutableListOf<String>()
        val result = NotificationAuthRecovery.call({ true }, { if (++sessions == 1) "old" else "new" }, { discarded += it }) { token ->
            requests += token
            if (token == "old") throw NotificationApiException(401, "Expired", NotificationPhase.TOKEN_REGISTER)
            "registered"
        }
        assertEquals("registered", result)
        assertEquals(listOf("old", "new"), requests)
        assertEquals(listOf("old"), discarded)
    }
    @Test fun aSecondRejectionStopsWithoutAnAuthenticationLoop() = runBlocking {
        var requests = 0; val discarded = mutableListOf<String>()
        try {
            NotificationAuthRecovery.call<Unit>({ true }, { "session" }, { discarded += it }) {
                requests++; throw NotificationApiException(401, "Rejected", NotificationPhase.INBOX_SYNC)
            }
            fail("Expected the final rejection")
        } catch (error: NotificationApiException) { assertEquals(401, error.status) }
        assertEquals(2, requests); assertEquals(2, discarded.size)
    }
    @Test fun aFreshGoogleProofFailureIsNotRepeatedAsARelayRetry() = runBlocking {
        var sessions = 0
        try {
            NotificationAuthRecovery.call<Unit>({ true }, { sessions++; throw NotificationAuthRecovery.googleFailure(4) }, { fail("No relay token exists") }) { fail("No HTTP request should run") }
            fail("Approval is required")
        } catch (error: NotificationApiException) { assertEquals(4, error.googleStatus) }
        assertEquals(1, sessions)
    }
    @Test fun accountSwitchWhileTheRejectedRequestRunsStopsRenewal() = runBlocking {
        var current = true; var sessions = 0
        try {
            NotificationAuthRecovery.call<Unit>({ current }, { sessions++; "old" }, {}) {
                current = false; throw NotificationApiException(401, "Expired", NotificationPhase.INBOX_SYNC)
            }
            fail("Account changed")
        } catch (error: NotificationApiException) { assertEquals(NotificationFailure.ACCOUNT_CHANGED, error.failure) }
        assertEquals(1, sessions)
    }
    @Test fun oldRejectionCannotEraseAReplacementSessionForTheSameAccount() {
        val store = NotificationAccountStore(FakeSharedPreferences())
        val a = store.bind("google_12345", "bob")!!
        store.saveSession(a, "old", 1_000_000, "reg", "secret")
        store.saveSession(a, "new", 1_000_000, "reg", "secret-new")
        assertFalse(store.forgetSession(a, "old"))
        assertEquals("new", store.session(a, 100))
        assertTrue(store.forgetSession(a, "new"))
        assertNull(store.session(a, 100))
    }
    @Test fun reconnectAllowsOnlyTheOriginalGoogleIdentityAndAccountGeneration() {
        NotificationAuthRecovery.verifyGoogleAccount(account, account, "12345")
        for ((current, id) in listOf(account to "another", account.copy(generation = "new") to "12345", null to "12345")) {
            try { NotificationAuthRecovery.verifyGoogleAccount(account, current, id); fail("Unexpected account accepted") }
            catch (_: NotificationApiException) { }
        }
    }
    @Test fun cancellationPropagatesWithoutRetrying() = runBlocking {
        var requests = 0
        try {
            NotificationAuthRecovery.call<Unit>({ true }, { "token" }, { fail("Cancelled operations must not erase credentials") }) {
                requests++; throw CancellationException()
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }
    @Test fun recipientFriendAuthorizationRetryIsBoundedByTheOriginalExpiry() {
        val error = NotificationApiException(403, "Recipient has not authorized friend alerts", NotificationPhase.EVENT_SEND, failure = NotificationFailure.FRIEND_NOT_AUTHORIZED)
        assertFalse(error.needsGoogleApproval)
        assertTrue(NotificationAuthRecovery.shouldRetry(error, 0, 200, 100))
        assertFalse(NotificationAuthRecovery.shouldRetry(error, 0, 200, 200))
        assertFalse(NotificationAuthRecovery.shouldRetry(error, 8, 200, 100))
    }
    @Test fun aRejectedFcmTokenIsRotatedAndRegisteredBeforeSuccess() = runBlocking {
        val operations = mutableListOf<String>()
        NotificationAuthRecovery.registerToken({ true }, { force ->
            operations += "fetch:$force"
            (if (force) "new" else "old") to (if (force) 2L else 1L)
        }, { token -> operations += "register:${token.first}"; token.first == "new" })
        assertEquals(listOf("fetch:false", "register:old", "fetch:true", "register:new"), operations)
    }
    @Test fun repeatedTokenRejectionCannotBeReportedAsRegistered() = runBlocking {
        var posts = 0
        try {
            NotificationAuthRecovery.registerToken({ true }, { "token" to 1L }, { posts++; false })
            fail("Registration must wait for a valid token")
        } catch (error: NotificationApiException) {
            assertEquals(NotificationPhase.TOKEN_REGISTER, error.phase)
            assertTrue(error.retryable)
        }
        assertEquals(2, posts)
    }
    @Test fun accountSwitchDuringTokenFetchPreventsRegistrationForEitherAccount() = runBlocking {
        var current = true
        try {
            NotificationAuthRecovery.registerToken({ current }, { current = false; "old-account-token" to 1L }, { fail("Account changed"); true })
            fail("Account changed")
        } catch (error: NotificationApiException) { assertEquals(NotificationFailure.ACCOUNT_CHANGED, error.failure) }
    }
    @Test fun accountSwitchWhileRegisteringCannotReportRegistrationSuccess() = runBlocking {
        var current = true
        try {
            NotificationAuthRecovery.registerToken({ current }, { "old-account-token" to 1L }, { current = false; true })
            fail("Account changed")
        } catch (error: NotificationApiException) { assertEquals(NotificationFailure.ACCOUNT_CHANGED, error.failure) }
    }
}
