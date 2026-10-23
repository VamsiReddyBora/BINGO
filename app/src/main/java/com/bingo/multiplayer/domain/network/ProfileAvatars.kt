package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.notificationUsername
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A missing record means unknown. A record containing null is an explicit photo removal. */
data class ProfileAvatar(val source: String?)

internal fun interface ProfileAvatarSource {
    fun watch(username: String, changed: (ProfileAvatar) -> Unit): AutoCloseable
}

/** One live Firebase watch per visible username, shared by every avatar on the screen. */
internal class ProfileAvatarState(private val source: ProfileAvatarSource) {
    private class Watch(val generation: Long, var references: Int = 1, var handle: AutoCloseable? = null)
    private var generation = 0L
    private val watches = mutableMapOf<String, Watch>()
    private val pendingLocal = mutableMapOf<String, ProfileAvatar>()
    private val _records = MutableStateFlow<Map<String, ProfileAvatar>>(emptyMap())
    val records = _records.asStateFlow()

    @Synchronized fun observe(username: String): AutoCloseable {
        val name = notificationUsername(username)
        if (name.isBlank()) return AutoCloseable {}
        val watch = watches[name]?.also { it.references++ } ?: Watch(++generation).also { next ->
            watches[name] = next
            try {
                next.handle = source.watch(name) { value -> received(name, next.generation, value) }
            } catch (_: Exception) { /* A supplied/cached photo remains usable while Firebase is unavailable. */ }
        }
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (closed) return@synchronized
                closed = true
                if (watches[name] !== watch) return@synchronized
                if (--watch.references == 0) {
                    watches.remove(name)
                    watch.handle?.close()
                }
            }
        }
    }

    @Synchronized private fun received(name: String, token: Long, value: ProfileAvatar) {
        if (watches[name]?.generation != token) return
        // An initial cached snapshot must not roll back a photo just changed on this phone.
        pendingLocal[name]?.let { if (it != value) return }
        pendingLocal.remove(name)
        _records.value = _records.value + (name to value)
    }

    @Synchronized fun localChange(username: String, avatar: String?) {
        val name = notificationUsername(username)
        if (name.isBlank()) return
        val value = ProfileAvatar(avatar?.takeIf { it.isNotBlank() })
        pendingLocal[name] = value
        _records.value = _records.value + (name to value)
    }

    companion object {
        fun resolve(records: Map<String, ProfileAvatar>, username: String?, supplied: String?): String? {
            val name = username?.let(::notificationUsername)
            return if (name != null && records.containsKey(name)) records.getValue(name).source
                else supplied?.takeIf { it.isNotBlank() }
        }
    }
}

/** The SDK connection is opened only when a view observes a username, never by a photo change alone. */
internal object ProfileAvatars {
    val state = ProfileAvatarState(ProfileAvatarSource { name, changed ->
        FirestoreSyncManager.getInstance().watchAvatar(name, changed)
    })
}
