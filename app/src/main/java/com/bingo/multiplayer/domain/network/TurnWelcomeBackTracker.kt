package com.bingo.multiplayer.domain.network

/** Nearby's relative countdown keeps its original budget across pause/recomposition. */
internal class TurnWelcomeBackTracker {
    private var key: Triple<Long, Int, String>? = null
    var initialSeconds: Int = 30
        private set
    private var granted = false

    fun bind(seed: Long, turn: Int, player: String, initiallyAbsent: Boolean) {
        val next = Triple(seed, turn, player)
        if (next == key) return
        key = next
        initialSeconds = if (initiallyAbsent) 5 else 30
        granted = false
    }

    fun onPresence(remaining: Int, online: Boolean): Int {
        if (initialSeconds != 5 || granted || !online || remaining <= 0) return remaining
        granted = true
        return 30
    }
}
