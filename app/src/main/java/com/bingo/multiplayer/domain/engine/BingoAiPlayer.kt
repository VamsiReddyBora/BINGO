package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random

enum class AiDifficulty {
    EASY,
    HARD
}

class BingoAiPlayer(
    private val minDelayMs: Long = 700L,
    private val maxDelayMs: Long = 1300L
) {

    suspend fun decideNextMove(
        aiBoard: Board,
        opponentBoard: Board? = null,
        difficulty: AiDifficulty = AiDifficulty.HARD
    ): Int {
        val delayTime = if (maxDelayMs > minDelayMs) {
            Random.nextLong(minDelayMs, maxDelayMs)
        } else {
            minDelayMs
        }
        if (delayTime > 0L) {
            delay(delayTime)
        }

        val unmarkedNumbers = aiBoard.cells
            .filter { !it.isMarked }
            .map { it.number }

        require(unmarkedNumbers.isNotEmpty()) { "No valid moves left" }

        return when (difficulty) {
            AiDifficulty.EASY -> unmarkedNumbers.random()
            AiDifficulty.HARD -> getBestHeuristicPick(aiBoard, unmarkedNumbers, opponentBoard)
        }
    }

    private fun getBestHeuristicPick(
        aiBoard: Board,
        candidateNumbers: List<Int>,
        opponentBoard: Board?
    ): Int {
        var highestScore = Double.NEGATIVE_INFINITY
        var bestPick = candidateNumbers.first()

        for (number in candidateNumbers) {
            val aiCell = aiBoard.findCellByNumber(number) ?: continue
            val offensiveScore = evaluateCellScore(aiBoard, aiCell)

            val defensiveScore = if (opponentBoard != null) {
                opponentBoard.findCellByNumber(number)?.let { oppCell ->
                    evaluateCellScore(opponentBoard, oppCell) * 0.8
                } ?: 0.0
            } else 0.0

            val totalScore = offensiveScore + defensiveScore

            if (totalScore > highestScore) {
                highestScore = totalScore
                bestPick = number
            }
        }

        return bestPick
    }

    private fun evaluateCellScore(board: Board, cell: Cell): Double {
        val size = board.size
        var score = 0.0

        // Row score
        val rowCells = (0 until size).map { c -> board.getCell(cell.row, c) }
        score += calculateLineWeight(rowCells, size)

        // Column score
        val colCells = (0 until size).map { r -> board.getCell(r, cell.col) }
        score += calculateLineWeight(colCells, size)

        // Main diagonal
        if (cell.row == cell.col) {
            val mainDiagCells = (0 until size).map { i -> board.getCell(i, i) }
            score += calculateLineWeight(mainDiagCells, size) * 1.2
        }

        // Anti diagonal
        if (cell.row + cell.col == size - 1) {
            val antiDiagCells = (0 until size).map { i -> board.getCell(i, size - 1 - i) }
            score += calculateLineWeight(antiDiagCells, size) * 1.2
        }

        return score
    }

    private fun calculateLineWeight(line: List<Cell>, size: Int): Double {
        val markedCount = line.count { it.isMarked }
        return when (markedCount) {
            size - 1 -> 10000.0 // Instant completion priority
            size - 2 -> 500.0   // Setup move
            else -> 10.0.pow(markedCount.toDouble())
        }
    }
}
