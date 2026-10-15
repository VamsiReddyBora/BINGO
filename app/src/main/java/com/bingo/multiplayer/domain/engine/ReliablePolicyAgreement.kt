package com.bingo.multiplayer.domain.engine

import java.util.concurrent.ConcurrentHashMap

/** Explicit lobby advertisement; the fresh per-match HELLO/SYNC exchange is still mandatory. */
class ReliablePolicyAgreement {
    companion object { const val VERSION = 3 }
    enum class Status { WAITING, COMPATIBLE, INCOMPATIBLE, INVALID_PARTICIPANTS }
    private val advertised = ConcurrentHashMap<String, Int>()
    fun observe(uid: String, explicitVersion: Int) { if (uid.isNotBlank()) advertised[uid] = explicitVersion }
    fun status(participants: List<String>, localUid: String): Status {
        if (participants.size != 2 || participants.any { it.isBlank() } ||
            participants.distinct().size != 2 || localUid !in participants) return Status.INVALID_PARTICIPANTS
        val version = advertised[participants.single { it != localUid }] ?: return Status.WAITING
        return when (version) {
            VERSION -> Status.COMPATIBLE
            0 -> Status.WAITING
            else -> Status.INCOMPATIBLE
        }
    }
    fun canStart(participants: List<String>, localUid: String): Boolean = status(participants, localUid) == Status.COMPATIBLE
    fun clear() = advertised.clear()
}
