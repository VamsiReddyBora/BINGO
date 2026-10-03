package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType
import kotlin.random.Random

class BingoEngine {

    /**
     * Generates a randomized N x N board containing numbers 1 to N^2.
     */
    fun generateBoard(size: Int, seed: Long? = null): Board {
        val totalNumbers = size * size
        val numbers = (1..totalNumbers).toMutableList()
        if (seed != null) {
            numbers.shuffle(Random(seed))
        } else {
            numbers.shuffle()
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
     * Marks the cell containing [number] and evaluates winning rows/columns/diagonals.
     */
    fun markCell(
        board: Board,
        number: Int,
        pickedByPlayerId: String,
        isOwnPick: Boolean,
        turnNumber: Int
    ): Board {
        // 1. Mark target cell and reset recent pick flag on others
        val updatedCells = board.cells.map { cell ->
            if (cell.number == number) {
                val newMarkState = if (cell.isMarked) {
                    cell.markState
                } else {
                    CellMarkState.Marked(
                        pickedByPlayerId = pickedByPlayerId,
                        isOwnPick = isOwnPick,
                        turnNumber = turnNumber
                    )
                }
                cell.copy(
                    markState = newMarkState,
                    isRecentPick = true
                )
            } else {
                cell.copy(isRecentPick = false)
            }
        }.toMutableList()

        // Temporary board for line checks
        val tempBoard = board.copy(cells = updatedCells)
        val (completedLines, winningIndices) = detectCompletedLines(tempBoard)

        // 2. Mark winning line cells
        val finalCells = updatedCells.mapIndexed { index, cell ->
            cell.copy(isPartOfCompletedLine = index in winningIndices)
        }

        return board.copy(
            cells = finalCells,
            completedLines = completedLines
        )
    }

    /**
     * Scans rows, columns, and diagonals for full completion.
     */
    private fun detectCompletedLines(board: Board): Pair<Set<LineCoordinate>, Set<Int>> {
        val size = board.size
        val lines = mutableSetOf<LineCoordinate>()
        val winningIndices = mutableSetOf<Int>()

        // Check Rows
        for (r in 0 until size) {
            val isRowComplete = (0 until size).all { c ->
                board.getCell(r, c).isMarked
            }
            if (isRowComplete) {
                lines.add(LineCoordinate(LineType.ROW, r))
                for (c in 0 until size) {
                    winningIndices.add(r * size + c)
                }
            }
        }

        // Check Columns
        for (c in 0 until size) {
            val isColComplete = (0 until size).all { r ->
                board.getCell(r, c).isMarked
            }
            if (isColComplete) {
                lines.add(LineCoordinate(LineType.COLUMN, c))
                for (r in 0 until size) {
                    winningIndices.add(r * size + c)
                }
            }
        }

        // Main Diagonal (top-left to bottom-right)
        val isMainDiagComplete = (0 until size).all { i ->
            board.getCell(i, i).isMarked
        }
        if (isMainDiagComplete) {
            lines.add(LineCoordinate(LineType.MAIN_DIAGONAL, 0))
            for (i in 0 until size) {
                winningIndices.add(i * size + i)
            }
        }

        // Anti Diagonal (top-right to bottom-left)
        val isAntiDiagComplete = (0 until size).all { i ->
            board.getCell(i, size - 1 - i).isMarked
        }
        if (isAntiDiagComplete) {
            lines.add(LineCoordinate(LineType.ANTI_DIAGONAL, 0))
            for (i in 0 until size) {
                winningIndices.add(i * size + (size - 1 - i))
            }
        }

        return Pair(lines, winningIndices)
    }

    /**
     * Computes a deterministic 64-bit FNV-1a hash of the board's state.
     * Evaluates all marked cell coordinates, numbers, and completed lines.
     * Guaranteed identical across all devices if and only if the marked cells and lines match.
     */
    fun computeBoardHash(board: Board): Long {
        var h = -3750763034362895579L // FNV offset basis
        val prime = 1099511628211L    // FNV prime
        val size = board.size

        h = (h xor size.toLong()) * prime

        for (r in 0 until size) {
            for (c in 0 until size) {
                val cell = board.getCell(r, c)
                val cellVal = (r * size + c + 1).toLong() * 10007L + cell.number.toLong()
                h = (h xor cellVal) * prime
                if (cell.isMarked) {
                    h = (h xor (cellVal * 31L + 1L)) * prime
                }
            }
        }

        board.completedLines.sortedWith(compareBy({ it.type.name }, { it.index })).forEach { line ->
            val lineVal = line.type.ordinal.toLong() * 997L + line.index.toLong() * 31L
            h = (h xor lineVal) * prime
        }

        return h
    }
}
