package com.bingo.multiplayer.domain.network

import kotlinx.serialization.Serializable

/** One shared clock per turn. Recomposition and reconnect never create a new budget. */
@Serializable
data class MultiplayerTurnClock(
    val turnNumber: Int,
    val playerId: String,
    val startedAt: Long,
    val deadlineAt: Long,
    val initialBudgetMillis: Long,
    val welcomeBackGranted: Boolean = false,
    val frozenRemainingMillis: Long? = null,
    val changedAt: Long = startedAt
) {
    fun remainingMillis(now: Long): Long =
        (frozenRemainingMillis ?: (deadlineAt - now)).coerceIn(0L, ONLINE_MILLIS)

    fun remainingSeconds(now: Long): Int = ((remainingMillis(now) + 999L) / 1000L).toInt()

    fun welcomeBack(now: Long): MultiplayerTurnClock {
        if (initialBudgetMillis != ABSENCE_MILLIS || welcomeBackGranted || remainingMillis(now) == 0L) return this
        return copy(deadlineAt = now + ONLINE_MILLIS, welcomeBackGranted = true, changedAt = now,
            frozenRemainingMillis = if (frozenRemainingMillis != null) ONLINE_MILLIS else null)
    }

    fun freeze(now: Long): MultiplayerTurnClock = if (frozenRemainingMillis != null) this
        else copy(frozenRemainingMillis = remainingMillis(now), changedAt = now)

    fun resume(now: Long): MultiplayerTurnClock = frozenRemainingMillis?.let {
        copy(deadlineAt = now + it, frozenRemainingMillis = null, changedAt = now)
    } ?: this

    /** Snapshot arrival order must not select different clocks for the same turn. */
    fun merge(incoming: MultiplayerTurnClock): MultiplayerTurnClock {
        if (!incoming.valid() || incoming.turnNumber != turnNumber || incoming.playerId != playerId) return this
        if (welcomeBackGranted != incoming.welcomeBackGranted) return if (incoming.welcomeBackGranted) incoming else this
        return listOf(this, incoming).maxWith(compareBy<MultiplayerTurnClock> { it.changedAt }
            .thenBy { it.deadlineAt }.thenBy { it.frozenRemainingMillis ?: -1L })
    }

    fun valid(): Boolean = turnNumber > 0 && playerId.isNotBlank() && startedAt > 0L &&
        initialBudgetMillis in setOf(ONLINE_MILLIS, ABSENCE_MILLIS) && deadlineAt >= startedAt &&
        (frozenRemainingMillis == null || frozenRemainingMillis in 0L..ONLINE_MILLIS)

    companion object {
        const val ONLINE_MILLIS = 30_000L
        const val ABSENCE_MILLIS = 5_000L
        const val INACTIVITY_MILLIS = 5 * 60_000L

        fun start(turn: Int, playerId: String, now: Long, isAbsent: Boolean = false): MultiplayerTurnClock {
            val budget = if (isAbsent) ABSENCE_MILLIS else ONLINE_MILLIS
            return MultiplayerTurnClock(turn, playerId, now, now + budget, budget)
        }

        fun restore(turn: Int, playerId: String, now: Long, remaining: Long, isAbsent: Boolean = false): MultiplayerTurnClock =
            start(turn, playerId, now, isAbsent).copy(deadlineAt = now + remaining.coerceIn(0L, ONLINE_MILLIS))
    }
}
