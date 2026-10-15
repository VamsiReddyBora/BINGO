package com.bingo.multiplayer.domain.engine

import java.util.concurrent.ConcurrentHashMap

/** Explicit lobby advertisement; the fresh per-match HELLO/SYNC exchange is still mandatory. */
class ReliablePolicyAgreement {
    companion object { const val VERSION = 3 }
    private val advertised = ConcurrentHashMap<String, Int>()
    fun observe(uid: String, explicitVersion: Int) { if (uid.isNotBlank()) advertised[uid] = explicitVersion }
    fun canStart(participants: List<String>, localUid: String): Boolean =
        participants.size == 2 && participants.distinct().size == 2 && localUid in participants &&
            participants.filter { it != localUid }.all { advertised[it] == VERSION }
    fun clear() = advertised.clear()
}
