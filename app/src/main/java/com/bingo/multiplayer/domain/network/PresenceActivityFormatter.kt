package com.bingo.multiplayer.domain.network

/** Presentation only: activity details never affect a game's turns or outcomes. */
object PresenceActivityFormatter {
    fun activeLabel(presence: PlayerPresence, nowMs: Long): String? {
        if (presence.timestamp <= 0L || nowMs - presence.timestamp >= 15_000L) return null
        val detail = presence.activityDetail.trim()
        return when (presence.status.uppercase()) {
            "ONLINE" -> "online"
            "PLAYING" -> if (detail.isEmpty()) "playing" else "playing ($detail)"
            "IN_LOBBY" -> if (detail.isEmpty()) "in-lobby" else "in-lobby ($detail)"
            else -> null
        }
    }
}
