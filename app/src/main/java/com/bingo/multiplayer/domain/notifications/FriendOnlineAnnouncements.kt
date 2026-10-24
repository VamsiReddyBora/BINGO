package com.bingo.multiplayer.domain.notifications

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Small account-scoped availability history; MQTT remains the live presence transport. */
class FriendOnlineAnnouncements(private val prefs: SharedPreferences) {
    data class Announcement(val id: String, val createdAt: Long, val queued: Set<String>)
    @Serializable private data class State(val lastActiveAt: Long = 0, val offlineSince: Long = 0,
        val noticeId: String = "", val noticeAt: Long = 0, val queued: Set<String> = emptySet())
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(uid: String) = NotificationPolicy.eventId("online-history", uid)
    private fun read(uid: String): State? = prefs.getString(key(uid), null)?.let {
        runCatching { json.decodeFromString<State>(it) }.getOrElse { State() }
    }
    private fun save(uid: String, state: State) = prefs.edit().putString(key(uid), json.encodeToString(state)).commit()

    fun active(uid: String, now: Long): Announcement? = synchronized(guard) {
        if (uid.isBlank() || now <= 0) return@synchronized null
        val previous = read(uid)
        val since = previous?.let { if (it.offlineSince > 0) it.offlineSince else it.lastActiveAt } ?: 0
        // After a hard process kill, the last checkpoint can predate the actual exit.
        // Wait one checkpoint extra rather than overstate the continuous absence.
        val interval = OFFLINE_INTERVAL + if (previous?.offlineSince == 0L) CHECKPOINT_INTERVAL else 0L
        val eligible = previous == null || (since > 0 && now >= since && now - since >= interval)
        val next = if (eligible) State(now, noticeId = UUID.randomUUID().toString(), noticeAt = now)
            else (previous ?: State()).copy(lastActiveAt = now, offlineSince = 0)
        // Persist transitions immediately; checkpoint ongoing activity at most once per 15 seconds.
        if (eligible || previous == null || previous.offlineSince != 0L ||
            now < previous.lastActiveAt || now - previous.lastActiveAt >= CHECKPOINT_INTERVAL) {
            if (!save(uid, next)) return@synchronized null
        }
        next.takeIf { it.noticeId.isNotBlank() && now >= it.noticeAt &&
            now - it.noticeAt < NotificationKind.FRIEND_ONLINE.lifetimeMillis }
            ?.let { Announcement(it.noticeId, it.noticeAt, it.queued) }
    }

    fun offline(uid: String, now: Long) = synchronized(guard) {
        val previous = read(uid) ?: return@synchronized
        if (previous.offlineSince == 0L && now > 0) save(uid, previous.copy(offlineSince = now))
    }

    fun queued(uid: String, noticeId: String, target: String): Boolean = synchronized(guard) {
        val previous = read(uid) ?: return@synchronized false
        if (previous.noticeId != noticeId) return@synchronized false
        save(uid, previous.copy(queued = previous.queued + notificationUsername(target)))
    }

    companion object {
        const val OFFLINE_INTERVAL = NotificationOnlineConfig.OFFLINE_INTERVAL_MS
        private const val CHECKPOINT_INTERVAL = 15_000L
        private val guard = Any()
    }
}
