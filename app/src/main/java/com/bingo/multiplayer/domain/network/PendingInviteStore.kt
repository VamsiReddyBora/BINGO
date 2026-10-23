package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** In-memory invitation presentation only. Failed joins do not consume an invitation. */
internal class PendingInviteStore(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val expiryJobs = mutableMapOf<String, Job>()
    private val _invites = MutableStateFlow<List<GameInvite>>(emptyList())
    val invites = _invites.asStateFlow()

    @Synchronized fun put(invite: GameInvite) {
        if (invite.isPlayRequest || invite.expiresAt <= now()) return
        _invites.value = (listOf(invite) + _invites.value.filterNot { it.eventId == invite.eventId })
            .filter { it.expiresAt > now() }.sortedByDescending { it.timestamp }
        expiryJobs.remove(invite.eventId)?.cancel()
        expiryJobs[invite.eventId] = scope.launch {
            delay((invite.expiresAt - now()).coerceAtLeast(0L))
            expire(invite)
        }
    }

    @Synchronized private fun expire(invite: GameInvite) {
        val current = _invites.value.firstOrNull { it.eventId == invite.eventId } ?: return
        if (current.expiresAt != invite.expiresAt) return
        if (now() < invite.expiresAt) {
            // A device clock adjustment must not leave an expired row indefinitely.
            put(current)
            return
        }
        _invites.value = _invites.value.filterNot { it.eventId == invite.eventId && it.expiresAt == invite.expiresAt }
        expiryJobs.remove(invite.eventId)
    }

    @Synchronized fun remove(id: String) {
        expiryJobs.remove(id)?.cancel()
        _invites.value = _invites.value.filterNot { it.eventId == id }
    }

    @Synchronized fun clear() {
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        _invites.value = emptyList()
    }
}
