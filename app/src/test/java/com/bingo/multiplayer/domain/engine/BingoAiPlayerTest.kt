package com.bingo.multiplayer.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BingoAiPlayerTest {

    private lateinit var aiPlayer: BingoAiPlayer
    private lateinit var engine: BingoEngine

    @Before
    fun setUp() {
        // 0ms delay for lightning fast deterministic unit tests
        aiPlayer = BingoAiPlayer(minDelayMs = 0L, maxDelayMs = 0L)
        engine = BingoEngine()
    }

    @Test
    fun testDecideNextMove_easyBot_picksUnmarkedNumber() = runBlocking {
        val board = engine.generateBoard(size = 5, seed = 123L)

        // Mark 10 cells
        var currentBoard = board
        for (i in 1..10) {
            currentBoard = engine.markCell(currentBoard, i, "p1", true, i)
        }

        val aiMove = aiPlayer.decideNextMove(
            aiBoard = currentBoard,
            difficulty = AiDifficulty.EASY
        )

        val cell = currentBoard.findCellByNumber(aiMove)
        assertNotNull("Chosen move must exist on board", cell)
        assertFalse("AI must never choose an already marked cell", cell!!.isMarked)
        assertTrue("AI move must be > 10 in this scenario", aiMove > 10)
    }

    @Test
    fun testDecideNextMove_hardBot_picksWinningNumberGreedily() = runBlocking {
        val size = 5
        var aiBoard = engine.generateBoard(size = size, seed = 999L)

        // Mark 4 out of 5 cells in row 0 for AI
        val winningNumber = aiBoard.getCell(0, 4).number
        for (col in 0 until 4) {
            val num = aiBoard.getCell(0, col).number
            aiBoard = engine.markCell(aiBoard, num, "ai", true, col + 1)
        }

        // Hard bot should detect instant completion priority and choose winningNumber
        val pick = aiPlayer.decideNextMove(
            aiBoard = aiBoard,
            opponentBoard = null,
            difficulty = AiDifficulty.HARD
        )

        assertEquals("Hard AI must greedily pick the winning line completion number", winningNumber, pick)
    }

    @Test
    fun testDecideNextMove_hardBot_defendsAgainstOpponentWin() = runBlocking {
        val size = 5
        var aiBoard = engine.generateBoard(size = size, seed = 111L)
        var opponentBoard = engine.generateBoard(size = size, seed = 222L)

        // Opponent has 4 out of 5 in row 2
        val oppThreatCell = opponentBoard.getCell(2, 4)
        val oppThreatNumber = oppThreatCell.number

        for (col in 0 until 4) {
            val num = opponentBoard.getCell(2, col).number
            opponentBoard = engine.markCell(opponentBoard, num, "opp", true, col + 1)
        }

        // Verify the threat number is also on AI's board and unmarked
        val aiCell = aiBoard.findCellByNumber(oppThreatNumber)
        assertNotNull(aiCell)
        assertFalse(aiCell!!.isMarked)

        val pick = aiPlayer.decideNextMove(
            aiBoard = aiBoard,
            opponentBoard = opponentBoard,
            difficulty = AiDifficulty.HARD
        )

        assertFalse("Move must be unmarked", aiBoard.findCellByNumber(pick)!!.isMarked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDecideNextMove_fullBoard_throwsException(): Unit = runBlocking {
        val size = 5
        var board = engine.generateBoard(size = size)

        // Mark all 25 numbers
        for (num in 1..(size * size)) {
            board = engine.markCell(board, num, "p1", true, num)
        }

        aiPlayer.decideNextMove(aiBoard = board, difficulty = AiDifficulty.HARD)
    }

    @Test
    fun testDecideNextMove_easyBot_prioritizesLineCompletion() = runBlocking {
        val size = 5
        var aiBoard = engine.generateBoard(size = size, seed = 444L)

        // Mark 4 out of 5 cells in row 1 for AI
        val winningNumber = aiBoard.getCell(1, 4).number
        for (col in 0 until 4) {
            val num = aiBoard.getCell(1, col).number
            aiBoard = engine.markCell(aiBoard, num, "ai", true, col + 1)
        }

        // Easy bot should prioritize completing the line instead of a random number
        val pick = aiPlayer.decideNextMove(
            aiBoard = aiBoard,
            opponentBoard = null,
            difficulty = AiDifficulty.EASY
        )

        assertEquals("Easy AI must prioritize completing the line", winningNumber, pick)
    }

    @Test
    fun testDecideNextMove_masterBot_avoidsOpponentInstantWin() = runBlocking {
        val size = 5
        var aiBoard = engine.generateBoard(size = size, seed = 555L)
        var opponentBoard = engine.generateBoard(size = size, seed = 777L).copy(targetLines = 1)

        // Opponent has 4 out of 5 in row 0, needing only 1 more for instant Bingo
        val oppFatalNum = opponentBoard.getCell(0, 4).number
        for (col in 0 until 4) {
            val num = opponentBoard.getCell(0, col).number
            opponentBoard = engine.markCell(opponentBoard, num, "opp", true, 1)
            aiBoard = engine.markCell(aiBoard, num, "opp", false, 1)
        }

        assertEquals(0, opponentBoard.completedLinesCount)
        assertFalse(opponentBoard.findCellByNumber(oppFatalNum)!!.isMarked)
        assertFalse(aiBoard.findCellByNumber(oppFatalNum)!!.isMarked)

        // 21 candidate numbers remain. AI must NOT pick oppFatalNum!
        val pick = aiPlayer.decideNextMove(
            aiBoard = aiBoard,
            opponentBoard = opponentBoard,
            difficulty = AiDifficulty.HARD
        )

        assertNotEquals("Master AI must avoid calling the fatal number that gives opponent instant Bingo", oppFatalNum, pick)
    }

    @Test
    fun testDecideNextMove_dynamicSizes_neverSelectsOutOfRangeNumber() = runBlocking {
        val sizes = listOf(5, 6, 7, 8)
        for (size in sizes) {
            val board = engine.generateBoard(size = size, seed = size.toLong())
            val pick = aiPlayer.decideNextMove(board, difficulty = AiDifficulty.HARD)

            assertTrue("Pick must be >= 1", pick >= 1)
            assertTrue("Pick must be <= size^2", pick <= size * size)
            assertFalse("Picked cell must not be marked", board.findCellByNumber(pick)!!.isMarked)
        }
    }
}
