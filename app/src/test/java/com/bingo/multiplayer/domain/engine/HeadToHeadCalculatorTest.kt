package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.MatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadToHeadCalculatorTest {

    @Test
    fun `empty history returns empty stats`() {
        val stats = HeadToHeadCalculator.calculate(
            matchHistory = emptyList(),
            targetUid = "opponent_1",
            targetUsername = "opponent_user"
        )
        assertEquals(0, stats.totalMatches)
        assertEquals(0, stats.wins)
        assertEquals(0, stats.losses)
        assertEquals(0, stats.draws)
        assertEquals(0, stats.winRatePercentage)
        assertEquals(0, stats.streak)
        assertFalse(stats.hasHistory)
        assertTrue(stats.recentOutcomes.isEmpty())
    }

    @Test
    fun `matches against other opponents are excluded`() {
        val history = listOf(
            MatchRecord(
                id = "1",
                mode = "Online Match",
                opponentName = "OtherPlayer",
                opponentUid = "other_uid",
                didWin = true,
                boardSize = 5,
                timestamp = 1000L
            ),
            MatchRecord(
                id = "2",
                mode = "Online Match",
                opponentName = "TargetUser",
                opponentUid = "target_uid",
                didWin = true,
                boardSize = 5,
                timestamp = 2000L
            )
        )

        val stats = HeadToHeadCalculator.calculate(
            matchHistory = history,
            targetUid = "target_uid",
            targetUsername = "targetuser"
        )

        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.wins)
        assertEquals(0, stats.losses)
        assertEquals(100, stats.winRatePercentage)
        assertTrue(stats.hasHistory)
        assertEquals(listOf('W'), stats.recentOutcomes)
    }

    @Test
    fun `calculates wins losses and draws accurately with win rate`() {
        val history = listOf(
            MatchRecord(
                id = "1",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_uid",
                didWin = true,
                isDraw = false,
                boardSize = 5,
                timestamp = 1000L
            ),
            MatchRecord(
                id = "2",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_uid",
                didWin = false,
                isDraw = false,
                boardSize = 5,
                timestamp = 2000L
            ),
            MatchRecord(
                id = "3",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_uid",
                didWin = false,
                isDraw = true,
                boardSize = 5,
                timestamp = 3000L
            ),
            MatchRecord(
                id = "4",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_uid",
                didWin = true,
                isDraw = false,
                boardSize = 5,
                timestamp = 4000L
            )
        )

        val stats = HeadToHeadCalculator.calculate(
            matchHistory = history,
            targetUid = "rival_uid",
            targetUsername = "rival"
        )

        assertEquals(4, stats.totalMatches)
        assertEquals(2, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(1, stats.draws)
        // 2 wins out of 4 matches = 50% win rate
        assertEquals(50, stats.winRatePercentage)
    }

    @Test
    fun `calculates winning streak correctly`() {
        val history = listOf(
            MatchRecord(
                id = "1",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_1",
                didWin = false,
                timestamp = 1000L
            ),
            MatchRecord(
                id = "2",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_1",
                didWin = true,
                timestamp = 2000L
            ),
            MatchRecord(
                id = "3",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_1",
                didWin = true,
                timestamp = 3000L
            ),
            MatchRecord(
                id = "4",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_1",
                didWin = true,
                timestamp = 4000L
            )
        )

        val stats = HeadToHeadCalculator.calculate(
            matchHistory = history,
            targetUid = "rival_1",
            targetUsername = "rival"
        )

        // 3 consecutive wins at the end = streak +3
        assertEquals(3, stats.streak)
    }

    @Test
    fun `calculates losing streak correctly`() {
        val history = listOf(
            MatchRecord(
                id = "1",
                mode = "Online Match",
                opponentName = "Nemesis",
                opponentUid = "nem_1",
                didWin = true,
                timestamp = 1000L
            ),
            MatchRecord(
                id = "2",
                mode = "Online Match",
                opponentName = "Nemesis",
                opponentUid = "nem_1",
                didWin = false,
                isDraw = false,
                timestamp = 2000L
            ),
            MatchRecord(
                id = "3",
                mode = "Online Match",
                opponentName = "Nemesis",
                opponentUid = "nem_1",
                didWin = false,
                isDraw = false,
                timestamp = 3000L
            )
        )

        val stats = HeadToHeadCalculator.calculate(
            matchHistory = history,
            targetUid = "nem_1",
            targetUsername = "nemesis"
        )

        // 2 consecutive losses at the end = streak -2
        assertEquals(-2, stats.streak)
    }

    @Test
    fun `recent outcomes is capped to latest 5 matches in most-recent-first order`() {
        val history = (1..10).map { i ->
            MatchRecord(
                id = "$i",
                mode = "Online Match",
                opponentName = "Rival",
                opponentUid = "rival_uid",
                didWin = (i % 2 == 1),
                timestamp = i * 1000L
            )
        }

        val stats = HeadToHeadCalculator.calculate(
            matchHistory = history,
            targetUid = "rival_uid",
            targetUsername = "rival"
        )

        assertEquals(5, stats.recentOutcomes.size)
        // Matches 10 down to 6:
        // 10: didWin=false -> 'L'
        // 9: didWin=true -> 'W'
        // 8: didWin=false -> 'L'
        // 7: didWin=true -> 'W'
        // 6: didWin=false -> 'L'
        assertEquals(listOf('L', 'W', 'L', 'W', 'L'), stats.recentOutcomes)
    }
}
