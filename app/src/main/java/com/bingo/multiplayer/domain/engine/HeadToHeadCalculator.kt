package com.bingo.multiplayer.domain.engine

import androidx.annotation.Keep
import com.bingo.multiplayer.domain.model.MatchRecord
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class HeadToHeadStats(
    val totalMatches: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val winRatePercentage: Int = 0,
    val streak: Int = 0, // > 0 = W streak, < 0 = L streak, 0 = no streak
    val recentOutcomes: List<Char> = emptyList(), // 'W', 'L', 'D' (most recent first)
    val hasHistory: Boolean = false
) {
    val streakDisplay: String
        get() = when {
            streak > 1 -> "$streak Wins in a row 🔥"
            streak == 1 -> "1 Win"
            streak < -1 -> "${-streak} Losses in a row ❄️"
            streak == -1 -> "1 Loss"
            else -> "Even"
        }
}

object HeadToHeadCalculator {

    /**
     * Calculates mutual battle records between the current user and a target opponent.
     * Robust matching supports opponentUid, @username, and display names.
     */
    fun calculate(
        matchHistory: List<MatchRecord>,
        targetUid: String,
        targetUsername: String,
        targetDisplayName: String = ""
    ): HeadToHeadStats {
        val cleanUid = targetUid.trim()
        val cleanUser = targetUsername.trim().lowercase().removePrefix("@")
        val cleanDisplay = targetDisplayName.trim().lowercase()

        // Filter matches matching this opponent
        val mutualMatches = matchHistory.filter { record ->
            val rUid = record.opponentUid.trim()
            val rOpp = record.opponentName.trim().lowercase().removePrefix("@")
            val rTitle = record.matchTitle.trim().lowercase()

            when {
                cleanUid.isNotBlank() && rUid.isNotBlank() -> rUid == cleanUid
                cleanUser.isNotBlank() && (rOpp == cleanUser || containsName(rTitle, cleanUser)) -> true
                cleanDisplay.isNotBlank() && (rOpp == cleanDisplay || containsName(rTitle, cleanDisplay)) -> true
                else -> false
            }
        }.sortedByDescending { it.timestamp } // most recent first

        if (mutualMatches.isEmpty()) {
            return HeadToHeadStats(hasHistory = false)
        }

        var wins = 0
        var losses = 0
        var draws = 0

        mutualMatches.forEach { match ->
            when {
                match.isDraw -> draws++
                match.didWin -> wins++
                else -> losses++
            }
        }

        val total = mutualMatches.size
        val winRate = if (total > 0) ((wins.toFloat() / total) * 100).toInt() else 0

        // Calculate current streak from most recent matches
        var streak = 0
        var streakType: Boolean? = null // true = win, false = loss

        for (match in mutualMatches) {
            if (match.isDraw) break // draw breaks a streak
            if (streakType == null) {
                streakType = match.didWin
                streak = if (match.didWin) 1 else -1
            } else if (match.didWin == streakType) {
                if (streakType) streak++ else streak--
            } else {
                break
            }
        }

        // Recent outcomes (up to 5 most recent)
        val recentOutcomes = mutualMatches.take(5).map { match ->
            when {
                match.isDraw -> 'D'
                match.didWin -> 'W'
                else -> 'L'
            }
        }

        return HeadToHeadStats(
            totalMatches = total,
            wins = wins,
            losses = losses,
            draws = draws,
            winRatePercentage = winRate,
            streak = streak,
            recentOutcomes = recentOutcomes,
            hasHistory = true
        )
    }

    private fun containsName(title: String, name: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])").containsMatchIn(title)
}
