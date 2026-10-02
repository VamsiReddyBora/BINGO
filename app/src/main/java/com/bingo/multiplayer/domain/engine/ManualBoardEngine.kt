package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import kotlin.random.Random

/**
 * ManualBoardEngine:
 * Fully isolated domain engine responsible for manual board design,
 * sequential cell filling (1 to N^2), undo, clear, auto-fill, and board construction.
 *
 * Invariants:
 * 1. Monotonic sequential numbering: numbers are placed strictly from 1 up to N^2.
 * 2. Uniqueness: each number from 1 to N^2 appears exactly once.
 * 3. Deterministic first-turn selection: random first-turn assignment is seeded for identical choice on both devices.
 */
object ManualBoardEngine {

    /**
     * Creates an empty grid with [size] * [size] null cells.
     */
    fun createEmptyGrid(size: Int): List<Int?> {
        val totalCells = size * size
        return List(totalCells) { null }
    }

    /**
     * Places [nextNumber] at [index] if that cell is currently empty.
     * Returns the updated grid and the incremented next number, or null if cell is already occupied or invalid.
     */
    fun placeNextNumber(grid: List<Int?>, index: Int, nextNumber: Int, size: Int): Pair<List<Int?>, Int>? {
        val maxNumber = size * size
        if (index !in grid.indices || nextNumber > maxNumber) return null
        if (grid[index] != null) return null

        val updated = grid.toMutableList()
        updated[index] = nextNumber
        return Pair(updated, nextNumber + 1)
    }

    /**
     * Undoes the most recently placed number ([currentNextNumber] - 1).
     * Returns the updated grid and decremented next number, or null if no numbers have been placed.
     */
    fun undoLastNumber(grid: List<Int?>, currentNextNumber: Int): Pair<List<Int?>, Int>? {
        if (currentNextNumber <= 1) return null
        val targetNumber = currentNextNumber - 1
        val index = grid.indexOfFirst { it == targetNumber }
        if (index == -1) return null

        val updated = grid.toMutableList()
        updated[index] = null
        return Pair(updated, targetNumber)
    }

    /**
     * Clears all placed numbers, returning an empty grid and resetting next number to 1.
     */
    fun clearAll(size: Int): Pair<List<Int?>, Int> {
        return Pair(createEmptyGrid(size), 1)
    }

    /**
     * Automatically fills all remaining empty cells with random unused numbers from [nextNumber] to N^2.
     */
    fun autoFillRemaining(grid: List<Int?>, size: Int): Pair<List<Int?>, Int> {
        val maxNumber = size * size
        val usedNumbers = grid.filterNotNull().toSet()
        val remainingNumbers = (1..maxNumber).filter { it !in usedNumbers }.shuffled().toMutableList()

        val updated = grid.toMutableList()
        for (i in updated.indices) {
            if (updated[i] == null && remainingNumbers.isNotEmpty()) {
                updated[i] = remainingNumbers.removeAt(0)
            }
        }
        return Pair(updated, maxNumber + 1)
    }

    /**
     * Verifies if every cell is filled and contains all numbers from 1 to N^2.
     */
    fun isBoardComplete(grid: List<Int?>, size: Int): Boolean {
        val totalCells = size * size
        if (grid.size != totalCells) return false
        val filled = grid.filterNotNull()
        if (filled.size != totalCells) return false
        val set = filled.toSet()
        return set.size == totalCells && (1..totalCells).all { it in set }
    }

    /**
     * Constructs a valid [Board] from a completed manual grid.
     */
    fun buildBoard(grid: List<Int?>, size: Int): Board {
        val total = size * size
        val numbers = if (isBoardComplete(grid, size)) {
            grid.map { it!! }
        } else {
            // Fallback safety: fill missing sequentially
            val used = grid.filterNotNull().toSet()
            val remaining = (1..total).filter { it !in used }.toMutableList()
            grid.map { it ?: remaining.removeAt(0) }
        }

        val cells = mutableListOf<Cell>()
        for (r in 0 until size) {
            for (c in 0 until size) {
                val num = numbers[r * size + c]
                cells.add(
                    Cell(
                        row = r,
                        col = c,
                        number = num,
                        markState = CellMarkState.Unmarked,
                        isPartOfCompletedLine = false,
                        isRecentPick = false
                    )
                )
            }
        }
        return Board(size = size, cells = cells, targetLines = size)
    }

    /**
     * Deterministically selects the first player to pick using the shared match [seed].
     * Guarantees both clients select the exact same starting player with 50/50 probability.
     */
    fun determineRandomFirstTurn(seed: Long, playerIds: List<String>): String {
        if (playerIds.isEmpty()) return ""
        val index = Random(seed).nextInt(playerIds.size)
        return playerIds[index]
    }
}
