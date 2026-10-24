package com.bingo.multiplayer.domain.notifications

import android.content.Context
import android.content.Intent
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.network.NetworkConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/** Repairs notification credentials only; the player account and gameplay are untouched. */
object NotificationGoogleAuth {
    fun failure(code: Int) = NotificationAuthRecovery.googleFailure(code)

    fun options(context: Context): GoogleSignInOptions {
        if (NetworkConfig.NOTIFICATION_GOOGLE_CLIENT_ID.isBlank())
            throw NotificationApiException(400, "Google notification sign-in is not configured", NotificationPhase.GOOGLE_SIGN_IN, failure = NotificationFailure.GOOGLE_CONFIGURATION)
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile()
            .requestIdToken(NetworkConfig.NOTIFICATION_GOOGLE_CLIENT_ID)
            .apply { NotificationRuntime.profile(context)?.email?.takeIf { it.isNotBlank() }?.let { setAccountName(it) } }
            .build()
    }

    fun intent(context: Context, expected: NotificationAccount): Intent {
        if (NotificationRuntime.account(context) != expected || NotificationRuntime.profile(context)?.provider != AuthProvider.GOOGLE)
            throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        return GoogleSignIn.getClient(context, options(context)).signInIntent
    }

    suspend fun reconnect(context: Context, expected: NotificationAccount, googleId: String?, idToken: String?) {
        NotificationAuthRecovery.verifyGoogleAccount(expected, NotificationRuntime.account(context), googleId)
        NotificationApi.reconnect(context, expected, googleId, idToken.orEmpty())
        if (NotificationRuntime.account(context) != expected) return
        if (!NotificationWork.register(context, expected, "reconnect:${System.currentTimeMillis()}"))
            throw NotificationApiException(503, "Notification registration could not be queued", NotificationPhase.ENQUEUE)
        NotificationDiagnostics.record(context, null, "GOOGLE_RECONNECTED")
    }
}
