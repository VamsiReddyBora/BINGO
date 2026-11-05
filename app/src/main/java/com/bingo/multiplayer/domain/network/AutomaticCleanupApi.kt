package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.core.security.AdminControlAccess
import com.bingo.multiplayer.domain.model.UserProfile
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class AdminAccountReconnectRequired : IllegalStateException("Reconnect your admin Google account")

data class AutomaticCleanupState(val enabled: Boolean, val revision: Long, val configured: Boolean)

/** Shared scheduler setting. No cloud management credentials are shipped to Android. */
object AutomaticCleanupApi {
    private const val BASE_URL = "https://bingo-admin-control.vamsireddy2534.workers.dev"

    fun googleOptions(user: UserProfile): GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail().requestProfile().requestIdToken(NetworkConfig.NOTIFICATION_GOOGLE_CLIENT_ID)
        .apply { user.email?.takeIf { it.isNotBlank() }?.let { setAccountName(it) } }.build()

    internal fun requireMatchingAccount(user: UserProfile, googleId: String?) {
        check(!googleId.isNullOrBlank() && "google_$googleId" == user.uid) { "Choose the Google account used by this Bingo administrator" }
    }

    suspend fun fetch(context: Context, user: UserProfile, tokenOverride: String? = null) = request(context, user, null, tokenOverride)

    suspend fun update(context: Context, user: UserProfile, enabled: Boolean, expectedRevision: Long) = request(
        context, user, JSONObject().put("enabled", enabled).put("expectedRevision", expectedRevision)
    )

    private fun accountToken(context: Context, user: UserProfile): String {
        var googleFailure: Exception? = null
        try {
            val account = Tasks.await(GoogleSignIn.getClient(context, googleOptions(user)).silentSignIn(), 15, TimeUnit.SECONDS)
            requireMatchingAccount(user, account.id)
            account.idToken?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (error: Exception) { googleFailure = error }
        try {
            val current = FirebaseAuth.getInstance().currentUser ?: throw AdminAccountReconnectRequired()
            if (!current.providerData.any { it.providerId == "google.com" && "google_${it.uid}" == user.uid })
                throw AdminAccountReconnectRequired()
            return Tasks.await(current.getIdToken(true), 15, TimeUnit.SECONDS).token ?: throw AdminAccountReconnectRequired()
        } catch (_: Exception) {
            var cause: Throwable? = googleFailure
            while (cause?.cause != null) cause = cause.cause
            val status = (cause as? com.google.android.gms.common.api.ApiException)?.statusCode
            if (cause is java.util.concurrent.TimeoutException || status in listOf(7, 8, 15))
                throw java.io.IOException("Admin connection unavailable")
            throw AdminAccountReconnectRequired()
        }
    }

    private suspend fun request(context: Context, user: UserProfile, body: JSONObject?, tokenOverride: String? = null): AutomaticCleanupState {
        val json = requestAdminJson(context, user, "/client/admin/automatic-cleanup", body, tokenOverride)
        return AutomaticCleanupState(json.getBoolean("enabled"), json.getLong("revision"), json.getBoolean("configured"))
    }

    internal suspend fun requestAdminJson(context: Context, user: UserProfile, path: String,
        body: JSONObject? = null, tokenOverride: String? = null, maximumChars: Int = 16_384): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/client/admin/"))
        check(AdminControlAccess.canAccess(user)) { "Administrator access required" }
        val token = tokenOverride ?: accountToken(context, user)
        val connection = URL(BASE_URL + path).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = if (path.startsWith("/client/admin/latency-history")) 30_000 else 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use {
                val buffer = CharArray(maximumChars + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                String(buffer, 0, count)
            }.orEmpty()
            check(raw.length <= maximumChars) { "Cleanup service response too large" }
            val json = JSONObject(raw.ifEmpty { "{}" })
            if (code == 401 || code == 403) throw AdminAccountReconnectRequired()
            check(code in 200..299) { json.optString("error", "Cleanup service unavailable ($code)") }
            json
        } finally {
            connection.disconnect()
        }
    }
}
