package com.bingo.multiplayer.domain.network

/** Presentation evidence is scoped to a match and connection. Unknown initial presence, startup
 * collection, retained records and a sync request alone do not establish an absence episode. */
class MatchReturnTracker {
    private var match = 0L
    private val online = mutableMapOf<String, String>()
    private val absent = mutableSetOf<String>()
    private val returned = mutableSetOf<String>()
    fun bind(seed: Long) {
        if (match == seed) return
        match = seed; online.clear(); absent.clear(); returned.clear()
    }
    fun observe(seed: Long, player: String, connection: String, status: String, live: Boolean = true): Boolean {
        bind(seed)
        if (seed == 0L || player.isBlank() || !live) return false
        return when (status) {
            "AWAY", "OFFLINE" -> {
                if (online.containsKey(player) && (connection.isBlank() || online[player] == connection)) {
                    absent.add(player); returned.remove(player)
                }
                false
            }
            "ONLINE" -> {
                // A generic gameplay packet has no connection identity. It must not erase
                // the session established by a validated presence callback.
                if (connection.isNotBlank() || !online.containsKey(player)) online[player] = connection
                if (absent.remove(player)) { returned.add(player); true } else false
            }
            else -> false
        }
    }
    /** Legacy REJOIN uses a previously observed absence, once, never mere first contact. */
    fun rejoin(seed: Long, player: String): Boolean {
        bind(seed)
        if (seed == 0L || !absent.remove(player)) return false
        returned.add(player)
        return true
    }
}
