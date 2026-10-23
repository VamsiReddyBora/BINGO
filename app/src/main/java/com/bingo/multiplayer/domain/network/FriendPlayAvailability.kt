package com.bingo.multiplayer.domain.network

/** Fresh presence selects Invite or Request. Offline/unknown friends can receive an
 * invitation through FCM; somebody already in a lobby remains unavailable. */
enum class FriendPlayAvailability {
    AVAILABLE, BUSY, IN_LOBBY, UNKNOWN;
    val canContact: Boolean get() = this != IN_LOBBY
    companion object {
        fun of(presence: PlayerPresence?, now: Long = System.currentTimeMillis()): FriendPlayAvailability {
            if (presence == null || presence.timestamp <= 0L || presence.timestamp > now + 30_000L ||
                now - presence.timestamp >= 15_000L) return UNKNOWN
            return when (presence.status.uppercase()) {
                "PLAYING" -> BUSY
                "IN_LOBBY" -> IN_LOBBY
                "ONLINE" -> if (presence.currentRoomCode.isBlank()) AVAILABLE else IN_LOBBY
                else -> UNKNOWN
            }
        }
    }
}
