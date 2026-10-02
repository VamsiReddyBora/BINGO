package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import kotlinx.coroutines.delay
import kotlin.random.Random

enum class AiDifficulty {
    EASY,
    HARD
}

/**
 * High-performance, strategic AI engine for Bingo.
 *
 * Implements:
 * 1. Immediate Win / Line-Completion Greedy Execution.
 * 2. Opponent Denial & Anti-Suicide Defense (Master mode).
 * 3. Multi-Line Intersection Synergy Optimization (evaluating 2x, 3x, and 4x crossing lines).
 * 4. Target Winning Set Focus (concentrating picks on the subset of lines closest to completing 5 lines).
 */
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
            AiDifficulty.EASY -> getChallengingMove(aiBoard, unmarkedNumbers)
            AiDifficulty.HARD -> getMasterMove(aiBoard, unmarkedNumbers, opponentBoard)
        }
    }

    /**
     * Master mode: Maximum Performance & Logic.
     * Deterministic optimization combining aggressive offensive multi-line synergy
     * with strict defensive opponent denial and win blocking.
     */
    private fun getMasterMove(
        aiBoard: Board,
        candidateNumbers: List<Int>,
        opponentBoard: Board?
    ): Int {
        val scoredMoves = candidateNumbers.map { number ->
            val score = evaluateMove(aiBoard, number, opponentBoard, isMaster = true)
            number to score
        }.sortedByDescending { it.second }

        return scoredMoves.first().first
    }

    /**
     * Easy mode: Now tuned to a Challenging, high-performing level.
     * Plays with full offensive line-completion and intersection synergy,
     * without defensive opponent tracking, with slight stochastic top-choice variance.
     */
    private fun getChallengingMove(
        aiBoard: Board,
        candidateNumbers: List<Int>
    ): Int {
        val scoredMoves = candidateNumbers.map { number ->
            val score = evaluateMove(aiBoard, number, opponentBoard = null, isMaster = false)
            number to score
        }.sortedByDescending { it.second }

        // If the top move is an immediate Bingo win or completion, play it immediately
        val topMove = scoredMoves.first()
        if (topMove.second >= 100_000.0 || scoredMoves.size <= 1) {
            return topMove.first
        }

        // 85% pick optimal move, 15% pick 2nd best move if within competitive range
        val secondMove = scoredMoves.getOrNull(1)
        return if (secondMove != null && secondMove.second >= topMove.second * 0.75 && Random.nextFloat() < 0.15f) {
            secondMove.first
        } else {
            topMove.first
        }
    }

    /**
     * Evaluates the comprehensive strategic score for marking [number].
     */
    private fun evaluateMove(
        aiBoard: Board,
        number: Int,
        opponentBoard: Board?,
        isMaster: Boolean
    ): Double {
        val aiCell = aiBoard.findCellByNumber(number) ?: return Double.NEGATIVE_INFINITY
        val size = aiBoard.size
        val targetLines = aiBoard.targetLines

        // ── 1. Offensive Analysis on AI Board ──
        val aiLinesThroughCell = getLinesThroughCell(aiBoard, aiCell)
        var newlyCompletedLinesAi = 0

        for (line in aiLinesThroughCell) {
            val markedCount = line.count { it.isMarked }
            if (markedCount == size - 1) {
                newlyCompletedLinesAi++
            }
        }

        val aiCurrentCompleted = aiBoard.completedLinesCount
        val aiWillHaveCompleted = aiCurrentCompleted + newlyCompletedLinesAi

        // Immediate Bingo Win Check (+100,000,000)
        if (aiWillHaveCompleted >= targetLines) {
            return 100_000_000.0 + (newlyCompletedLinesAi * 10_000.0)
        }

        // ── 2. Defensive Analysis on Opponent Board ──
        var defensiveScore = 0.0

        if (isMaster && opponentBoard != null) {
            val oppCell = opponentBoard.findCellByNumber(number)
            if (oppCell != null) {
                val oppLinesThroughCell = getLinesThroughCell(opponentBoard, oppCell)
                var newlyCompletedLinesOpp = 0

                for (line in oppLinesThroughCell) {
                    val markedCount = line.count { it.isMarked }
                    if (markedCount == opponentBoard.size - 1) {
                        newlyCompletedLinesOpp++
                    }
                }

                val oppCurrentCompleted = opponentBoard.completedLinesCount
                val oppWillHaveCompleted = oppCurrentCompleted + newlyCompletedLinesOpp

                // ANTI-SUICIDE RULE: Never give opponent an instant Bingo win unless AI also wins on this pick
                if (oppWillHaveCompleted >= opponentBoard.targetLines) {
                    defensiveScore -= 50_000_000.0
                } else if (newlyCompletedLinesOpp > 0) {
                    // Penalty for completing an opponent line
                    defensiveScore -= (newlyCompletedLinesOpp * 250_000.0)
                }

                // Opponent Starvation: Subtract progress given to opponent
                val oppAdvancementScore = evaluateCellOffensiveWeight(opponentBoard, oppCell)
                defensiveScore -= (oppAdvancementScore * 0.6)
            }
        }

        // ── 3. Offensive Multi-Line Synergy & Target Focus ──
        val offensiveScore = evaluateCellOffensiveWeight(aiBoard, aiCell)

        // Bonus for newly completed lines
        val completionBonus = when (newlyCompletedLinesAi) {
            1 -> 150_000.0
            2 -> 450_000.0
            3 -> 1_000_000.0
            else -> 0.0
        }

        return offensiveScore + completionBonus + defensiveScore
    }

    /**
     * Calculates the offensive weight of a cell based on incomplete line proximity and synergy.
     */
    private fun evaluateCellOffensiveWeight(board: Board, cell: Cell): Double {
        val size = board.size
        val linesThroughCell = getLinesThroughCell(board, cell)

        // Filter to incomplete lines (completed lines give 0 advancement)
        val activeLines = linesThroughCell.filter { line ->
            line.any { !it.isMarked }
        }

        if (activeLines.isEmpty()) {
            return 0.0
        }

        // Proximity weights: exponential scaling as line gets closer to completion
        var totalLineWeight = 0.0
        for (line in activeLines) {
            val markedCount = line.count { it.isMarked }
            val weight = when (markedCount) {
                size - 1 -> 40_000.0 // 1 away from complete
                size - 2 -> 3_500.0  // 2 away from complete
                size - 3 -> 450.0    // 3 away from complete
                size - 4 -> 60.0     // 4 away
                else -> 15.0
            }
            totalLineWeight += weight
        }

        // Multi-line intersection synergy multiplier:
        // Cells intersecting 2, 3, or 4 active lines compound progress exponentially
        val synergyMultiplier = 1.0 + (0.45 * (activeLines.size - 1))

        // Center cell strategic bonus in odd-sized boards (intersects 4 lines)
        val centerIndex = size / 2
        val isCenter = (cell.row == centerIndex && cell.col == centerIndex)
        val centerBonus = if (isCenter && size % 2 == 1) 250.0 else 0.0

        return (totalLineWeight * synergyMultiplier) + centerBonus
    }

    /**
     * Retrieves all rows, columns, and diagonals that pass through [cell].
     */
    private fun getLinesThroughCell(board: Board, cell: Cell): List<List<Cell>> {
        val size = board.size
        val lines = mutableListOf<List<Cell>>()

        // 1. Row
        val rowCells = (0 until size).map { c -> board.getCell(cell.row, c) }
        lines.add(rowCells)

        // 2. Column
        val colCells = (0 until size).map { r -> board.getCell(r, cell.col) }
        lines.add(colCells)

        // 3. Main Diagonal
        if (cell.row == cell.col) {
            val mainDiagCells = (0 until size).map { i -> board.getCell(i, i) }
            lines.add(mainDiagCells)
        }

        // 4. Anti-Diagonal
        if (cell.row + cell.col == size - 1) {
            val antiDiagCells = (0 until size).map { i -> board.getCell(i, size - 1 - i) }
            lines.add(antiDiagCells)
        }

        return lines
    }
}
