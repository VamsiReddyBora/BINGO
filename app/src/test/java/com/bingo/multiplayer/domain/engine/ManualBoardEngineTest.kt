package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.CellMarkState
import org.junit.Assert.*
import org.junit.Test

class ManualBoardEngineTest {

    @Test
    fun testCreateEmptyGrid() {
        val grid5 = ManualBoardEngine.createEmptyGrid(5)
        assertEquals(25, grid5.size)
        assertTrue(grid5.all { it == null })

        val grid6 = ManualBoardEngine.createEmptyGrid(6)
        assertEquals(36, grid6.size)
        assertTrue(grid6.all { it == null })
    }

    @Test
    fun testPlaceNextNumber_sequentialAndValidation() {
        var grid = ManualBoardEngine.createEmptyGrid(5)
        var nextNum = 1

        // Place 1 at index 0
        val step1 = ManualBoardEngine.placeNextNumber(grid, index = 0, nextNumber = nextNum, size = 5)
        assertNotNull(step1)
        grid = step1!!.first
        nextNum = step1.second
        assertEquals(1, grid[0])
        assertEquals(2, nextNum)

        // Place 2 at index 12 (center)
        val step2 = ManualBoardEngine.placeNextNumber(grid, index = 12, nextNumber = nextNum, size = 5)
        assertNotNull(step2)
        grid = step2!!.first
        nextNum = step2.second
        assertEquals(2, grid[12])
        assertEquals(3, nextNum)

        // Attempting to place on an already occupied cell (index 0) must return null
        val occupiedAttempt = ManualBoardEngine.placeNextNumber(grid, index = 0, nextNumber = nextNum, size = 5)
        assertNull(occupiedAttempt)

        // Out of bounds index must return null
        val oobAttempt = ManualBoardEngine.placeNextNumber(grid, index = 25, nextNumber = nextNum, size = 5)
        assertNull(oobAttempt)

        val negativeIndexAttempt = ManualBoardEngine.placeNextNumber(grid, index = -1, nextNumber = nextNum, size = 5)
        assertNull(negativeIndexAttempt)

        // Attempting to place number exceeding N^2 (26 on 5x5) must return null
        val overflowAttempt = ManualBoardEngine.placeNextNumber(grid, index = 1, nextNumber = 26, size = 5)
        assertNull(overflowAttempt)
    }

    @Test
    fun testUndoLastNumber() {
        var grid = ManualBoardEngine.createEmptyGrid(5)

        // Undo on empty board returns null
        val undoEmpty = ManualBoardEngine.undoLastNumber(grid, 1)
        assertNull(undoEmpty)

        // Place numbers 1, 2, 3
        var nextNum = 1
        grid = ManualBoardEngine.placeNextNumber(grid, 0, nextNum++, 5)!!.first
        grid = ManualBoardEngine.placeNextNumber(grid, 1, nextNum++, 5)!!.first
        grid = ManualBoardEngine.placeNextNumber(grid, 2, nextNum++, 5)!!.first
        assertEquals(4, nextNum)

        // Undo 3
        val undo1 = ManualBoardEngine.undoLastNumber(grid, nextNum)
        assertNotNull(undo1)
        grid = undo1!!.first
        nextNum = undo1.second
        assertEquals(3, nextNum)
        assertNull(grid[2])
        assertEquals(2, grid[1])
        assertEquals(1, grid[0])

        // Undo 2
        val undo2 = ManualBoardEngine.undoLastNumber(grid, nextNum)
        assertNotNull(undo2)
        grid = undo2!!.first
        nextNum = undo2.second
        assertEquals(2, nextNum)
        assertNull(grid[1])
        assertEquals(1, grid[0])

        // Undo 1
        val undo3 = ManualBoardEngine.undoLastNumber(grid, nextNum)
        assertNotNull(undo3)
        grid = undo3!!.first
        nextNum = undo3.second
        assertEquals(1, nextNum)
        assertNull(grid[0])
        assertTrue(grid.all { it == null })
    }

    @Test
    fun testClearAll() {
        val (clearedGrid, nextNum) = ManualBoardEngine.clearAll(5)
        assertEquals(25, clearedGrid.size)
        assertTrue(clearedGrid.all { it == null })
        assertEquals(1, nextNum)
    }

    @Test
    fun testAutoFillRemaining() {
        var grid = ManualBoardEngine.createEmptyGrid(5)
        // Manually place 1, 2, 3 in specific cells
        grid = ManualBoardEngine.placeNextNumber(grid, 4, 1, 5)!!.first
        grid = ManualBoardEngine.placeNextNumber(grid, 12, 2, 5)!!.first
        grid = ManualBoardEngine.placeNextNumber(grid, 24, 3, 5)!!.first

        val (filledGrid, finalNextNum) = ManualBoardEngine.autoFillRemaining(grid, 5)
        assertEquals(26, finalNextNum)
        assertEquals(25, filledGrid.size)
        assertTrue(filledGrid.all { it != null })

        // Manual placements preserved
        assertEquals(1, filledGrid[4])
        assertEquals(2, filledGrid[12])
        assertEquals(3, filledGrid[24])

        // All numbers 1..25 present without duplicates
        val numbers = filledGrid.map { it!! }.sorted()
        assertEquals((1..25).toList(), numbers)
        assertTrue(ManualBoardEngine.isBoardComplete(filledGrid, 5))
    }

    @Test
    fun testIsBoardComplete() {
        val emptyGrid = ManualBoardEngine.createEmptyGrid(5)
        assertFalse(ManualBoardEngine.isBoardComplete(emptyGrid, 5))

        // Partially filled grid
        val partialGrid = emptyGrid.toMutableList()
        (0 until 24).forEach { partialGrid[it] = it + 1 }
        assertFalse(ManualBoardEngine.isBoardComplete(partialGrid, 5))

        // Fully filled with duplicates (missing 25, duplicate 1)
        val duplicateGrid = partialGrid.toMutableList()
        duplicateGrid[24] = 1
        assertFalse(ManualBoardEngine.isBoardComplete(duplicateGrid, 5))

        // Fully filled correctly 1..25
        val validGrid = partialGrid.toMutableList()
        validGrid[24] = 25
        assertTrue(ManualBoardEngine.isBoardComplete(validGrid, 5))
    }

    @Test
    fun testBuildBoard() {
        val (grid, _) = ManualBoardEngine.clearAll(5)
        val (completeGrid, _) = ManualBoardEngine.autoFillRemaining(grid, 5)

        val board = ManualBoardEngine.buildBoard(completeGrid, 5)
        assertEquals(5, board.size)
        assertEquals(25, board.cells.size)
        assertEquals(25, board.maxNumber)
        assertFalse(board.isBingo)
        assertEquals(0, board.completedLinesCount)
        assertTrue(board.cells.all { it.markState is CellMarkState.Unmarked })

        // Verify cells map correctly to rows and cols
        for (r in 0 until 5) {
            for (c in 0 until 5) {
                val cell = board.cells[r * 5 + c]
                assertEquals(r, cell.row)
                assertEquals(c, cell.col)
                assertEquals(completeGrid[r * 5 + c], cell.number)
            }
        }
    }

    @Test
    fun testDetermineRandomFirstTurn_deterministicAndBalanced() {
        val playerA = "player_alice"
        val playerB = "player_bob"
        val players = listOf(playerA, playerB)

        // Consistency across calls with same seed
        val seed = 987654321L
        val pick1 = ManualBoardEngine.determineRandomFirstTurn(seed, players)
        val pick2 = ManualBoardEngine.determineRandomFirstTurn(seed, players)
        assertEquals("Same seed must produce identical first player choice", pick1, pick2)

        // Balance across 1000 random seeds (approx 50/50)
        var countA = 0
        var countB = 0
        for (i in 0 until 1000) {
            val choice = ManualBoardEngine.determineRandomFirstTurn(i.toLong(), players)
            if (choice == playerA) countA++ else countB++
        }
        assertTrue("Count A should be near 500 (was $countA)", countA in 400..600)
        assertTrue("Count B should be near 500 (was $countB)", countB in 400..600)
    }
}
