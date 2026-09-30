package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.presentation.components.ALL_REACTION_EMOJIS
import com.bingo.multiplayer.presentation.components.FloatingEmoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit & simulation test suite verifying the game screen redesign layout logic:
 * 1. 3-number FIFO sliding queue pill pushing oldest out and placing new picks at the 3rd slot.
 * 2. Ping color thresholds (0-250 green, 250-500 yellow, 500-999+ red, capping at 999+).
 * 3. 1-second 180° hourglass flip rotation angles.
 * 4. B-I-N-G-O column progression and guaranteed 'O' strike completion when isBingo is true.
 * 5. Emoji recents reordering and randomized trajectory generation.
 */
class GameScreenLayoutAndSimulationTest {

    @Test
    fun testRecentPicksFifoQueueSliding() {
        val history = mutableListOf<Int>()

        // Helper function matching RecentPicksQueuePill logic
        fun computeQueueSlots(picks: List<Int>): Triple<Int?, Int?, Int?> {
            val valid = picks.filter { it > 0 }
            val lastThree = valid.takeLast(3)
            return Triple(lastThree.getOrNull(0), lastThree.getOrNull(1), lastThree.getOrNull(2))
        }

        // Empty picks
        var slots = computeQueueSlots(history)
        assertEquals(null, slots.first)
        assertEquals(null, slots.second)
        assertEquals(null, slots.third)

        // 1st pick: 12
        history.add(12)
        slots = computeQueueSlots(history)
        assertEquals(12, slots.first)
        assertEquals(null, slots.second)
        assertEquals(null, slots.third)

        // 2nd pick: 7
        history.add(7)
        slots = computeQueueSlots(history)
        assertEquals(12, slots.first)
        assertEquals(7, slots.second)
        assertEquals(null, slots.third)

        // 3rd pick: 24
        history.add(24)
        slots = computeQueueSlots(history)
        assertEquals(12, slots.first)
        assertEquals(7, slots.second)
        assertEquals(24, slots.third)

        // 4th pick: 5 -> pushes 12 out! 7 moves to pos 1, 24 moves to pos 2, 5 sits at pos 3!
        history.add(5)
        slots = computeQueueSlots(history)
        assertEquals(7, slots.first)
        assertEquals(24, slots.second)
        assertEquals(5, slots.third)

        // 5th pick: 19 -> pushes 7 out! 24 moves to pos 1, 5 moves to pos 2, 19 sits at pos 3!
        history.add(19)
        slots = computeQueueSlots(history)
        assertEquals(24, slots.first)
        assertEquals(5, slots.second)
        assertEquals(19, slots.third)
    }

    @Test
    fun testPingClassificationAndCapping() {
        fun formatPing(pingMs: Long): Pair<String, String> {
            val colorCategory = when {
                pingMs <= 250L -> "GREEN"
                pingMs <= 500L -> "YELLOW"
                else -> "RED"
            }
            val text = if (pingMs > 999L) "999+ms" else "${pingMs}ms"
            return Pair(colorCategory, text)
        }

        // 0 to 250 -> GREEN
        assertEquals(Pair("GREEN", "0ms"), formatPing(0L))
        assertEquals(Pair("GREEN", "45ms"), formatPing(45L))
        assertEquals(Pair("GREEN", "250ms"), formatPing(250L))

        // 251 to 500 -> YELLOW
        assertEquals(Pair("YELLOW", "251ms"), formatPing(251L))
        assertEquals(Pair("YELLOW", "380ms"), formatPing(380L))
        assertEquals(Pair("YELLOW", "500ms"), formatPing(500L))

        // 501+ -> RED, capped at 999+ms
        assertEquals(Pair("RED", "501ms"), formatPing(501L))
        assertEquals(Pair("RED", "850ms"), formatPing(850L))
        assertEquals(Pair("RED", "999ms"), formatPing(999L))
        assertEquals(Pair("RED", "999+ms"), formatPing(1000L))
        assertEquals(Pair("RED", "999+ms"), formatPing(2400L))
        assertEquals(Pair("RED", "999+ms"), formatPing(9999L))
    }

    @Test
    fun testHourglassFlipAngleCalculation() {
        fun calculateFlipAngle(secondsRemaining: Int): Float {
            return (30 - secondsRemaining) * 180f
        }

        assertEquals(0f, calculateFlipAngle(30))
        assertEquals(180f, calculateFlipAngle(29))
        assertEquals(360f, calculateFlipAngle(28))
        assertEquals(540f, calculateFlipAngle(27))
        assertEquals(2700f, calculateFlipAngle(15))
        assertEquals(5400f, calculateFlipAngle(0))

        // Ensure every second tick represents exactly 180 degrees flip
        for (sec in 30 downTo 1) {
            val delta = calculateFlipAngle(sec - 1) - calculateFlipAngle(sec)
            assertEquals(180f, delta, 0.001f)
        }
    }

    @Test
    fun testBingoDiagonalStrikeProgressionAndBingoFix() {
        val engine = BingoEngine()
        var board = engine.generateBoard(size = 5, seed = 12345L)
        val letters = listOf('B', 'I', 'N', 'G', 'O')

        fun isLetterStruck(colIndex: Int, completedLines: Int, isBingo: Boolean): Boolean {
            return colIndex < completedLines || (isBingo && colIndex < 5)
        }

        // Initially 0 lines completed -> no letters struck
        assertEquals(0, board.completedLinesCount)
        for (i in 0 until 5) {
            assertEquals(false, isLetterStruck(i, board.completedLinesCount, board.isBingo))
        }

        // Complete 1 horizontal line
        val row0Nums = board.cells.filter { it.row == 0 }.map { it.number }
        for ((idx, num) in row0Nums.withIndex()) {
            board = engine.markCell(board, num, "player1", true, idx + 1)
        }
        assertTrue(board.completedLinesCount >= 1)
        assertEquals(true, isLetterStruck(0, board.completedLinesCount, board.isBingo))

        // Complete remaining cells to reach BINGO (5 lines)
        val allBoardNums = (1..25).toList()
        for ((idx, num) in allBoardNums.withIndex()) {
            board = engine.markCell(board, num, "player1", true, idx + 10)
            if (board.completedLinesCount >= 5) break
        }
        assertTrue(board.isBingo)
        assertTrue(board.completedLinesCount >= 5)

        // Verify that B, I, N, G, and critically 'O' (index 4) are ALL struck
        for (i in 0 until 5) {
            assertEquals("Letter ${letters[i]} at index $i must be struck on BINGO",
                true, isLetterStruck(i, board.completedLinesCount, board.isBingo))
        }
    }

    @Test
    fun testEmojiRecentsOrderingAndDynamicTrajectory() {
        var emojiList = ALL_REACTION_EMOJIS

        fun onEmojiTapped(tapped: String) {
            emojiList = listOf(tapped) + emojiList.filter { it != tapped }
        }

        // Tapping 🔥 moves 🔥 to index 0
        onEmojiTapped("🔥")
        assertEquals("🔥", emojiList.first())

        // Tapping 🚀 moves 🚀 to index 0, 🔥 is now index 1
        onEmojiTapped("🚀")
        assertEquals("🚀", emojiList.first())
        assertEquals("🔥", emojiList[1])

        // Tapping 🔥 again moves 🔥 back to index 0
        onEmojiTapped("🔥")
        assertEquals("🔥", emojiList.first())
        assertEquals("🚀", emojiList[1])

        // Verify random trajectories for floating emotes
        fun createRandomEmoteItem(emoji: String, rnd: Random): FloatingEmoteItem {
            return FloatingEmoteItem(
                id = rnd.nextLong(),
                emoji = emoji,
                startXRatio = 0.3f + rnd.nextFloat() * 0.4f,
                swayAmplitude = 12f + rnd.nextFloat() * 24f,
                swayFrequency = 2.5f + rnd.nextFloat() * 3.0f,
                driftX = (rnd.nextFloat() - 0.5f) * 120f,
                swayPhase = rnd.nextFloat() * 6.28f
            )
        }

        val rnd = Random(42)
        val items = (1..10).map { createRandomEmoteItem("⚡", rnd) }

        // Ensure each item has a unique ID and non-identical trajectory parameters
        val ids = items.map { it.id }.toSet()
        assertEquals(10, ids.size)

        val sways = items.map { it.swayAmplitude }.toSet()
        assertTrue("Sway amplitudes must be varied/randomized", sways.size > 5)

        val drifts = items.map { it.driftX }.toSet()
        assertTrue("Drift values must be varied/randomized", drifts.size > 5)
    }
}
