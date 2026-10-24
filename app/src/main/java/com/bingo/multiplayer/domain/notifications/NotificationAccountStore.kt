package com.bingo.multiplayer.domain.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** All instances share a lock: a token refresh cannot commit credentials after an account switch. */
class NotificationAccountStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    fun account(): NotificationAccount? = synchronized(guard) { runCatching {
        prefs.getString("account", null)?.let { json.decodeFromString<NotificationAccount>(it) }
    }.getOrNull() }
    fun bind(uid: String?, username: String?): NotificationAccount? = synchronized(guard) {
        val old = account()
        val name = notificationUsername(username.orEmpty())
        if (uid.isNullOrBlank() || name.isBlank()) {
            check(prefs.edit().remove("account").remove("session").remove("sessionExpiry").commit())
            return@synchronized null
        }
        if (old?.uid == uid && old.username == name) {
            check(prefs.edit().putString("account", json.encodeToString(old)).commit())
            return@synchronized old
        }
        val installation = prefs.getString("installation", null) ?: UUID.randomUUID().toString()
        val sequence = prefs.getLong("sequence", 0) + 1
        val next = NotificationAccount(uid, name, UUID.randomUUID().toString(), installation, sequence)
        check(prefs.edit().putString("account", json.encodeToString(next)).putString("installation", installation)
            .putLong("sequence", sequence).remove("session").remove("sessionExpiry").commit())
        next
    }
    fun session(account: NotificationAccount, now: Long): String? = synchronized(guard) {
        if (account() == account && prefs.getLong("sessionExpiry", 0) > now + 60_000L) prefs.getString("session", null) else null
    }
    fun saveSession(account: NotificationAccount, token: String, expires: Long, registration: String, revoke: String): Boolean = synchronized(guard) {
        if (account() != account) return@synchronized false
        prefs.edit().putString("session", token).putLong("sessionExpiry", expires)
            .putString("registration", registration).putString("revoke", revoke).commit()
    }
    fun revocation(): Pair<String, String>? = synchronized(guard) {
        val registration = prefs.getString("registration", null) ?: return@synchronized null
        val revoke = prefs.getString("revoke", null) ?: return@synchronized null
        registration to revoke
    }
    fun forgetSession(account: NotificationAccount, rejectedToken: String? = null) = synchronized(guard) {
        if (account() == account && (rejectedToken == null || prefs.getString("session", null) == rejectedToken))
            prefs.edit().remove("session").remove("sessionExpiry").commit() else false
    }
    companion object {
        private val guard = Any()
        fun get(context: Context) = NotificationAccountStore(context.getSharedPreferences("bingo_notification_account_v2", Context.MODE_PRIVATE))
    }
}
