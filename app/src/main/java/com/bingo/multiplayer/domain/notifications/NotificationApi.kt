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

class NotificationApiException(val status: Int, message: String) : Exception(message) {
    val isRoomMirrorPending get() = status == 403 && message?.contains("Only the active room host may send this invitation", ignoreCase = true) == true
    val retryable get() = status == 0 || status == 408 || status == 425 || status == 429 || status >= 500 || isRoomMirrorPending
}

object NotificationApi {
    private val sessions = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val base get() = NetworkConfig.FCM_RELAY_URL.removeSuffix("/send")
    private fun raw(path: String, body: JSONObject?, session: String? = null): JSONObject {
        val builder = Request.Builder().url(base + path)
        if (session != null) builder.header("Authorization", "Bearer $session")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType())) else builder.get()
        try {
            NetworkConfig.httpClient.newCall(builder.build()).execute().use { response ->
                val value = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) throw NotificationApiException(response.code, value.optString("error", "Notification service unavailable").take(200))
                return value
            }
        } catch (e: NotificationApiException) { throw e }
        catch (_: java.io.IOException) { throw NotificationApiException(0, "Notification service is unreachable") }
    }
    suspend fun session(context: Context, account: NotificationAccount): String = sessions.withLock {
        val store = NotificationAccountStore.get(context)
        store.session(account, System.currentTimeMillis())?.let { return@withLock it }
        if (NotificationRuntime.account(context) != account) throw NotificationApiException(401, "Account changed")
        // FCM can start the process without constructing AuthRepository or an Activity.
        val user = NotificationRuntime.profile(context)
        val deviceToken = BingoFcmManager.tokenSnapshot(context)
        val proof = NotificationIdentity.proof(context, account).put("uid", account.uid).put("username", account.username)
            .put("displayName", user?.displayName?.takeIf { it.isNotBlank() }?.take(80) ?: account.username).put("installationId", account.installationId)
            .put("generation", account.generation).put("sequence", account.sequence).put("token", deviceToken.first).put("tokenRevision", deviceToken.second)
        val result = withContext(Dispatchers.IO) { raw("/v2/session", proof) }
        val token = result.getString("session")
        if (!store.saveSession(account, token, result.getLong("expiresAt"), result.getString("registrationId"), result.getString("revokeSecret"))) {
            NotificationWork.revoke(context, result.getString("registrationId"), result.getString("revokeSecret"))
            throw NotificationApiException(401, "Account changed")
        }
        token
    }
    suspend fun call(context: Context, account: NotificationAccount, path: String, body: JSONObject? = null): JSONObject {
        val token = session(context, account)
        if (NotificationRuntime.account(context) != account) throw NotificationApiException(401, "Account changed")
        return withContext(Dispatchers.IO) {
            try { raw(path, body, token) }
            catch (e: NotificationApiException) {
                if (e.status == 401) NotificationAccountStore.get(context).forgetSession(account)
                throw e
            }
        }
    }
    suspend fun inbox(context: Context, account: NotificationAccount): List<NotificationEvent> {
        val result = call(context, account, "/v2/inbox")
        return json.decodeFromString(result.getJSONArray("events").toString())
    }
    suspend fun revoke(registration: String, secret: String) = withContext(Dispatchers.IO) {
        raw("/v2/revoke", JSONObject().put("registrationId", registration).put("revokeSecret", secret))
    }
}
