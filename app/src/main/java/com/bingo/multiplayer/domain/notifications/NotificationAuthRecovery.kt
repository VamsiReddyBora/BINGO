package com.bingo.multiplayer.domain.notifications

enum class NotificationPhase { UNKNOWN, GOOGLE_SIGN_IN, ACCOUNT_CHECK, SESSION_CREATE, TOKEN_REGISTER, FRIENDS_SYNC, EVENT_SEND, EVENT_ACK, INBOX_SYNC, REVOKE, ENQUEUE }
enum class NotificationFailure { UNKNOWN, GOOGLE_APPROVAL_REQUIRED, GOOGLE_CONFIGURATION, GOOGLE_CANCELLED, ACCOUNT_CHANGED, GOOGLE_ACCOUNT_MISMATCH, TOKEN_MISSING, FRIEND_NOT_AUTHORIZED }

/** The same bounded credential recovery is used by the real HTTP notification client. */
object NotificationAuthRecovery {
    fun shouldRetry(error: NotificationApiException, attempt: Int, expiry: Long, now: Long): Boolean =
        !error.needsGoogleApproval && error.failure != NotificationFailure.ACCOUNT_CHANGED &&
            (error.retryable || error.status == 403) && attempt < 8 && expiry > now

    fun googleFailure(code: Int): NotificationApiException = when (code) {
        7, 8, 15, 17, 12502 -> NotificationApiException(503, "Google notification sign-in is temporarily unavailable", NotificationPhase.GOOGLE_SIGN_IN, code)
        10 -> NotificationApiException(400, "Google notification sign-in configuration needs correction", NotificationPhase.GOOGLE_SIGN_IN, code, NotificationFailure.GOOGLE_CONFIGURATION)
        16, 12501 -> NotificationApiException(401, "Notification reconnection was cancelled", NotificationPhase.GOOGLE_SIGN_IN, code, NotificationFailure.GOOGLE_CANCELLED)
        else -> NotificationApiException(401, "Reconnect Google to enable background notifications", NotificationPhase.GOOGLE_SIGN_IN, code, NotificationFailure.GOOGLE_APPROVAL_REQUIRED)
    }

    fun verifyGoogleAccount(expected: NotificationAccount, current: NotificationAccount?, googleId: String?) {
        if (current != expected) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        if (googleId.isNullOrBlank() || "google_$googleId" != expected.uid)
            throw NotificationApiException(401, "Choose the Google account already used by this player", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.GOOGLE_ACCOUNT_MISMATCH)
    }

    suspend fun registerToken(isCurrent: () -> Boolean,
                              fetch: suspend (Boolean) -> Pair<String, Long>,
                              post: suspend (Pair<String, Long>) -> Boolean) {
        for (refresh in listOf(false, true)) {
            if (!isCurrent()) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
            val token = fetch(refresh)
            if (!isCurrent()) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
            if (token.first.isBlank()) throw NotificationApiException(503, "FCM token is temporarily unavailable", NotificationPhase.TOKEN_REGISTER)
            if (post(token)) {
                if (!isCurrent()) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
                return
            }
        }
        throw NotificationApiException(503, "FCM token refresh is still pending", NotificationPhase.TOKEN_REGISTER)
    }

    suspend fun <T> call(isCurrent: () -> Boolean, session: suspend () -> String,
                         discard: (String) -> Unit, request: suspend (String) -> T): T {
        fun checkAccount() {
            if (!isCurrent()) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        }
        checkAccount()
        val original = session()
        checkAccount()
        try { return request(original) }
        catch (error: NotificationApiException) {
            // Only a rejected HTTP session is refreshed here. Google approval needs foreground UI.
            if (error.status != 401 || error.phase in setOf(NotificationPhase.GOOGLE_SIGN_IN, NotificationPhase.ACCOUNT_CHECK, NotificationPhase.SESSION_CREATE)) throw error
            discard(original)
            checkAccount()
            val renewed = session()
            checkAccount()
            try { return request(renewed) }
            catch (second: NotificationApiException) {
                if (second.status == 401) discard(renewed)
                throw second
            }
        }
    }
}
