package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.LineType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BingoEngineTest {

    private lateinit var engine: BingoEngine

    @Before
    fun setUp() {
        engine = BingoEngine()
    }

    @Test
    fun testGenerateBoard_dynamicSizes_createsValidCells() {
        val sizes = listOf(5, 6, 7, 8)
        for (size in sizes) {
            val board = engine.generateBoard(size = size, seed = 42L)
            assertEquals("Board size should match", size, board.size)
            assertEquals("Cell count must equal size * size", size * size, board.cells.size)
            assertEquals("Max number must equal size * size", size * size, board.maxNumber)

            // Numbers must be 1 to size * size without duplicates
            val numbers = board.cells.map { it.number }.sorted()
            val expected = (1..(size * size)).toList()
            assertEquals("Numbers must be unique from 1 to N^2", expected, numbers)

            // All cells initially unmarked
            assertTrue(board.cells.all { it.markState is CellMarkState.Unmarked })
            assertEquals(0, board.completedLinesCount)
            assertFalse(board.isBingo)
        }
    }

    @Test
    fun testMarkCell_horizontalRowCompletion() {
        val size = 5
        var board = engine.generateBoard(size = size)

        // Mark all numbers in row 2
        val row2Numbers = (0 until size).map { col -> board.getCell(2, col).number }

        for ((turn, num) in row2Numbers.withIndex()) {
            board = engine.markCell(
                board = board,
                number = num,
                pickedByPlayerId = "player_1",
                isOwnPick = true,
                turnNumber = turn + 1
            )
            assertTrue("Marked cell must be marked", board.findCellByNumber(num)!!.isMarked)
            assertTrue("Last marked cell must have isRecentPick = true", board.findCellByNumber(num)!!.isRecentPick)
        }

        // Verify row completion
        val rowLines = board.completedLines.filter { it.type == LineType.ROW }
        assertEquals(1, rowLines.size)
        assertEquals(2, rowLines.first().index)
        assertTrue(row2Numbers.all { num -> board.findCellByNumber(num)!!.isPartOfCompletedLine })
    }

    @Test
    fun testMarkCell_verticalColumnCompletion() {
        val size = 6
        var board = engine.generateBoard(size = size)

        // Mark all numbers in col 4
        val col4Numbers = (0 until size).map { row -> board.getCell(row, 4).number }

        for ((turn, num) in col4Numbers.withIndex()) {
            board = engine.markCell(
                board = board,
                number = num,
                pickedByPlayerId = "player_1",
                isOwnPick = true,
                turnNumber = turn + 1
            )
        }

        val colLines = board.completedLines.filter { it.type == LineType.COLUMN }
        assertEquals(1, colLines.size)
        assertEquals(4, colLines.first().index)
        assertTrue(col4Numbers.all { num -> board.findCellByNumber(num)!!.isPartOfCompletedLine })
    }

    @Test
    fun testMarkCell_mainDiagonalCompletion() {
        val size = 7
        var board = engine.generateBoard(size = size)

        // Main diagonal: (0,0), (1,1), ... (size-1, size-1)
        val diagNumbers = (0 until size).map { i -> board.getCell(i, i).number }

        for ((turn, num) in diagNumbers.withIndex()) {
            board = engine.markCell(
                board = board,
                number = num,
                pickedByPlayerId = "player_1",
                isOwnPick = true,
                turnNumber = turn + 1
            )
        }

        val mainDiagLines = board.completedLines.filter { it.type == LineType.MAIN_DIAGONAL }
        assertEquals(1, mainDiagLines.size)
        assertTrue(diagNumbers.all { num -> board.findCellByNumber(num)!!.isPartOfCompletedLine })
    }

    @Test
    fun testMarkCell_antiDiagonalCompletion() {
        val size = 8
        var board = engine.generateBoard(size = size)

        // Anti diagonal: (0, size-1), (1, size-2), ... (size-1, 0)
        val antiDiagNumbers = (0 until size).map { i -> board.getCell(i, size - 1 - i).number }

        for ((turn, num) in antiDiagNumbers.withIndex()) {
            board = engine.markCell(
                board = board,
                number = num,
                pickedByPlayerId = "player_1",
                isOwnPick = true,
                turnNumber = turn + 1
            )
        }

        val antiDiagLines = board.completedLines.filter { it.type == LineType.ANTI_DIAGONAL }
        assertEquals(1, antiDiagLines.size)
        assertTrue(antiDiagNumbers.all { num -> board.findCellByNumber(num)!!.isPartOfCompletedLine })
    }

    @Test
    fun testRecentPick_onlyOneCellHasRecentPickFlag() {
        val size = 5
        var board = engine.generateBoard(size = size)

        val pick1 = board.getCell(0, 0).number
        val pick2 = board.getCell(0, 1).number

        board = engine.markCell(board, pick1, "p1", true, 1)
        assertTrue(board.findCellByNumber(pick1)!!.isRecentPick)

        board = engine.markCell(board, pick2, "p2", false, 2)
        assertFalse("Previous pick should reset isRecentPick to false", board.findCellByNumber(pick1)!!.isRecentPick)
        assertTrue("Newest pick must have isRecentPick = true", board.findCellByNumber(pick2)!!.isRecentPick)

        val recentCount = board.cells.count { it.isRecentPick }
        assertEquals("Only exactly one cell can have isRecentPick", 1, recentCount)
    }

    @Test
    fun testMultipleLinesAndWinCondition() {
        val size = 5
        var board = engine.generateBoard(size = size)

        // Mark 5 complete lines (Rows 0, 1, 2, 3, 4)
        var turn = 1
        for (r in 0 until 5) {
            for (c in 0 until size) {
                board = engine.markCell(
                    board = board,
                    number = board.getCell(r, c).number,
                    pickedByPlayerId = "p1",
                    isOwnPick = true,
                    turnNumber = turn++
                )
            }
        }

        // 5 rows are completed
        assertTrue("Completed lines should be at least 5", board.completedLinesCount >= 5)
        assertTrue("Board must be marked as Bingo win", board.isBingo)
    }
}
