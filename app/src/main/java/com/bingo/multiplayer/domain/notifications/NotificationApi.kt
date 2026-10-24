package com.bingo.multiplayer.domain.notifications

import android.content.Context
import com.bingo.multiplayer.domain.network.BingoFcmManager
import com.bingo.multiplayer.domain.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NotificationApiException(val status: Int, message: String,
    val phase: NotificationPhase = NotificationPhase.UNKNOWN, val googleStatus: Int? = null,
    val failure: NotificationFailure = NotificationFailure.UNKNOWN) : Exception(message) {
    val isRoomMirrorPending get() = status == 403 && message?.contains("Only the active room host may send this invitation", ignoreCase = true) == true
    val retryable get() = status == 0 || status == 408 || status == 425 || status == 429 || status >= 500 || isRoomMirrorPending
    val needsGoogleApproval get() = failure in setOf(NotificationFailure.GOOGLE_APPROVAL_REQUIRED, NotificationFailure.GOOGLE_ACCOUNT_MISMATCH, NotificationFailure.TOKEN_MISSING) ||
        (status == 401 && phase != NotificationPhase.ACCOUNT_CHECK && failure != NotificationFailure.GOOGLE_CANCELLED)
}

object NotificationApi {
    private val sessions = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val base get() = NetworkConfig.FCM_RELAY_URL.removeSuffix("/send")
    private fun phase(path: String) = when (path) {
        "/v2/session" -> NotificationPhase.SESSION_CREATE
        "/v2/register" -> NotificationPhase.TOKEN_REGISTER
        "/v2/friends" -> NotificationPhase.FRIENDS_SYNC
        "/v2/events" -> NotificationPhase.EVENT_SEND
        "/v2/ack" -> NotificationPhase.EVENT_ACK
        "/v2/inbox" -> NotificationPhase.INBOX_SYNC
        "/v2/revoke" -> NotificationPhase.REVOKE
        else -> NotificationPhase.UNKNOWN
    }
    private fun raw(path: String, body: JSONObject?, session: String? = null): JSONObject {
        val builder = Request.Builder().url(base + path)
        if (session != null) builder.header("Authorization", "Bearer $session")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType())) else builder.get()
        try {
            NetworkConfig.httpClient.newCall(builder.build()).execute().use { response ->
                val value = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) {
                    val message = value.optString("error", "Notification service unavailable").take(200)
                    throw NotificationApiException(response.code, message, phase(path), failure =
                        if (response.code == 403 && message.contains("Recipient has not authorized friend alerts")) NotificationFailure.FRIEND_NOT_AUTHORIZED else NotificationFailure.UNKNOWN)
                }
                return value
            }
        } catch (e: NotificationApiException) { throw e }
        catch (_: java.io.IOException) { throw NotificationApiException(0, "Notification service is unreachable", phase(path)) }
    }
    suspend fun session(context: Context, account: NotificationAccount): String = sessions.withLock {
        val store = NotificationAccountStore.get(context)
        store.session(account, System.currentTimeMillis())?.let { return@withLock it }
        if (NotificationRuntime.account(context) != account) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        createSession(context, account, NotificationIdentity.proof(context, account))
    }
    private suspend fun createSession(context: Context, account: NotificationAccount, identity: JSONObject): String {
        if (NotificationRuntime.account(context) != account) throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        val store = NotificationAccountStore.get(context)
        // FCM can start the process without constructing AuthRepository or an Activity.
        val user = NotificationRuntime.profile(context)
        val deviceToken = BingoFcmManager.tokenSnapshot(context)
        val proof = identity.put("uid", account.uid).put("username", account.username)
            .put("displayName", user?.displayName?.takeIf { it.isNotBlank() }?.take(80) ?: account.username).put("installationId", account.installationId)
            .put("generation", account.generation).put("sequence", account.sequence).put("token", deviceToken.first).put("tokenRevision", deviceToken.second)
        val result = withContext(Dispatchers.IO) { raw("/v2/session", proof) }
        val token = result.getString("session")
        if (!store.saveSession(account, token, result.getLong("expiresAt"), result.getString("registrationId"), result.getString("revokeSecret"))) {
            NotificationWork.revoke(context, result.getString("registrationId"), result.getString("revokeSecret"))
            throw NotificationApiException(401, "Account changed", NotificationPhase.ACCOUNT_CHECK, failure = NotificationFailure.ACCOUNT_CHANGED)
        }
        return token
    }
    suspend fun call(context: Context, account: NotificationAccount, path: String, body: JSONObject? = null): JSONObject {
        return NotificationAuthRecovery.call(
            isCurrent = { NotificationRuntime.account(context) == account },
            session = { session(context, account) },
            discard = { NotificationAccountStore.get(context).forgetSession(account, it) },
            request = { token -> withContext(Dispatchers.IO) { raw(path, body, token) } })
    }
    suspend fun reconnect(context: Context, account: NotificationAccount, googleId: String?, idToken: String) = sessions.withLock {
        NotificationAuthRecovery.verifyGoogleAccount(account, NotificationRuntime.account(context), googleId)
        if (idToken.isBlank()) throw NotificationApiException(401, "Google returned no notification ID token", NotificationPhase.GOOGLE_SIGN_IN, failure = NotificationFailure.TOKEN_MISSING)
        createSession(context, account, JSONObject().put("provider", "google").put("idToken", idToken))
    }
    suspend fun inbox(context: Context, account: NotificationAccount): List<NotificationEvent> {
        val result = call(context, account, "/v2/inbox")
        return json.decodeFromString(result.getJSONArray("events").toString())
    }
    suspend fun revoke(registration: String, secret: String) = withContext(Dispatchers.IO) {
        raw("/v2/revoke", JSONObject().put("registrationId", registration).put("revokeSecret", secret))
    }
}
