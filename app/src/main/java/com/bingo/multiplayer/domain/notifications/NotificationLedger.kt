package com.bingo.multiplayer.domain.notifications

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One durable ledger shared by FCM, MQTT, polling, tray actions and in-app presentation. */
class NotificationLedger(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Entry(val uid: String, val id: String, val expiresAt: Long,
        val system: Boolean = false, val inApp: Boolean = false, val handled: Boolean = false, val alerted: Boolean = false)
    data class Presentation(val show: Boolean, val alert: Boolean)
    private val json = Json { ignoreUnknownKeys = true }
    private fun entries(): List<Entry>? = runCatching {
        json.decodeFromString<List<Entry>>(prefs.getString("events", "[]") ?: "[]").filter { it.expiresAt > now() }
    }.getOrNull()
    private fun save(items: List<Entry>, onlineKey: String? = null): Boolean {
        val editor = prefs.edit().putString("events", json.encodeToString(items))
        if (onlineKey != null) editor.putLong(onlineKey, now())
        return editor.commit()
    }

    fun handled(uid: String, id: String): Boolean = synchronized(guard) { entries()?.any { it.uid == uid && it.id == id && it.handled } ?: true }

    fun presented(uid: String, id: String): Boolean = synchronized(guard) {
        entries()?.any { it.uid == uid && it.id == id && (it.system || it.inApp || it.handled) } ?: true
    }

    fun present(event: NotificationEvent, system: Boolean): Presentation = synchronized(guard) {
        val list = entries()?.toMutableList() ?: return@synchronized Presentation(false, false)
        val old = list.firstOrNull { it.uid == event.recipientUid && it.id == event.id }
            ?: Entry(event.recipientUid, event.id, event.expiresAt)
        if (old.handled || (event.kind != NotificationKind.GAME_INVITE && old.alerted) || (if (system) old.system else old.inApp)) return@synchronized Presentation(false, false)
        if (list.size >= 1024 && list.none { it.uid == old.uid && it.id == old.id }) return@synchronized Presentation(false, false)
        val onlineKey = if (event.kind == NotificationKind.FRIEND_ONLINE) "online:${event.recipientUid}:${event.senderUid}" else null
        if (onlineKey != null && prefs.contains(onlineKey) && now() - prefs.getLong(onlineKey, 0) < 120_000L) return@synchronized Presentation(false, false)
        val next = old.copy(system = old.system || system, inApp = old.inApp || !system, alerted = true)
        list.removeAll { it.uid == old.uid && it.id == old.id }; list.add(next)
        return@synchronized if (save(list, onlineKey)) Presentation(true, !old.alerted) else Presentation(false, false)
    }

    fun markHandled(event: NotificationEvent): Boolean = synchronized(guard) {
        val list = entries()?.toMutableList() ?: return@synchronized false
        list.removeAll { it.uid == event.recipientUid && it.id == event.id }
        list.add(Entry(event.recipientUid, event.id, event.expiresAt, handled = true, alerted = true))
        return@synchronized save(list)
    }

    /** Release only the failed presentation so permission denial cannot consume an alert. */
    fun presentationFailed(event: NotificationEvent, system: Boolean, previouslyAlerted: Boolean) = synchronized(guard) {
        val items = entries() ?: return@synchronized false
        save(items.map { if (it.uid == event.recipientUid && it.id == event.id)
            it.copy(system = if (system) false else it.system, inApp = if (system) it.inApp else false,
                alerted = previouslyAlerted) else it })
    }
    companion object { private val guard = Any() }
}
