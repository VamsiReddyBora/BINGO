package com.bingo.multiplayer.domain.engine

/** Counts actual marked lines for scorecards, independently of an awarded match outcome. */
object MatchScorecardStats {
    fun completedLines(size: Int, boardNumbers: List<Int>, markedNumbers: Collection<Int>): Int? {
        if (size !in 5..10 || boardNumbers.size != size * size) return null
        val marked = markedNumbers.toSet()
        fun selected(row: Int, col: Int) = boardNumbers[row * size + col] in marked
        val rows = (0 until size).count { row -> (0 until size).all { selected(row, it) } }
        val columns = (0 until size).count { col -> (0 until size).all { selected(it, col) } }
        val main = if ((0 until size).all { selected(it, it) }) 1 else 0
        val anti = if ((0 until size).all { selected(it, size - 1 - it) }) 1 else 0
        return rows + columns + main + anti
    }
}
