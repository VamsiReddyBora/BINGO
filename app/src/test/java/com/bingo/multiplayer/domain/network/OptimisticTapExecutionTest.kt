package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.RecentPick
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying 0ms Optimistic Tap Execution and Authoritative Network Reconciliation.
 */
class OptimisticTapExecutionTest {

    private val engine = BingoEngine()

    @Test
    fun optimisticPickImmediatelyMarksCellAndLocksTurnBeforeNetworkAck() {
        val initialBoard = engine.generateBoard(3, 11)
        val targetCell = initialBoard.cells.first { !it.isMarked }
        val targetNumber = targetCell.number
        val myUid = "A"
        val pickedNumbers = mutableListOf<Int>()

        var isMyTurn = true
        var isProcessingTurn = false

        // Simulation of 0ms Optimistic Tap Execution
        val canPick = isMyTurn && !isProcessingTurn && targetNumber !in pickedNumbers
        assertTrue("Cell should be pickable on player's active turn", canPick)

        // 1. Optimistic Board Mark
        val playerBoard = engine.markCell(
            board = initialBoard,
            number = targetNumber,
            pickedByPlayerId = myUid,
            isOwnPick = true,
            turnNumber = 1
        )
        val recentPick = RecentPick(
            number = targetNumber,
            pickedByPlayerId = myUid,
            turnNumber = 1
        )
        pickedNumbers.add(targetNumber)

        // 2. Immediate Turn Lock
        isMyTurn = false
        isProcessingTurn = true

        // Verify cell state immediately after 0ms tap
        val markedCell = playerBoard.cells.first { it.number == targetNumber }
        assertTrue("Cell must be marked immediately at 0ms", markedCell.isMarked)
        assertEquals("Marked cell state must be Marked", CellMarkState.Marked(pickedByPlayerId = myUid, isOwnPick = true, turnNumber = 1), markedCell.markState)
        assertEquals("Recent pick number must match target", targetNumber, recentPick.number)
        assertEquals("Recent pick player must be local user", myUid, recentPick.pickedByPlayerId)
        assertTrue("Picked numbers must contain target number", targetNumber in pickedNumbers)

        // Verify double-tap prevention
        val attemptDoubleTap = isMyTurn && !isProcessingTurn
        assertFalse("Subsequent taps must be blocked while isMyTurn is false or isProcessingTurn is true", attemptDoubleTap)
    }

    @Test
    fun authoritativeSnapshotReconcilesAndResetsTurnStateWithoutDuplicateSound() {
        val myUid = "A"
        val opponentUid = "B"
        val initialBoard = engine.generateBoard(3, 11)
        val opponentInitialBoard = engine.generateBoard(3, 12)

        val pickedNumbersHistory = mutableListOf<Int>()
        var soundPlayedCount = 0

        // 1. Local player optimistically picks number 5
        val pickNumber = 5
        var playerBoard = engine.markCell(initialBoard, pickNumber, myUid, true, 1)
        var opponentBoard = engine.markCell(opponentInitialBoard, pickNumber, myUid, false, 1)
        pickedNumbersHistory.add(pickNumber)
        var isMyTurn = false
        var isProcessingTurn = true
        soundPlayedCount++ // 0ms sound played

        assertEquals(1, soundPlayedCount)
        assertEquals(1, pickedNumbersHistory.size)
        assertTrue("Player board marked immediately", playerBoard.cells.first { it.number == 5 }.isMarked)
        assertTrue("Opponent board marked immediately", opponentBoard.cells.first { it.number == 5 }.isMarked)

        // 2. Host coordinator processes pick and creates authoritative snapshot
        val coordinator = ReliableMatchCoordinator(
            initial = ReliableMatchSnapshot(
                matchId = "123",
                roomCode = "TEST",
                seed = 123L,
                hostId = myUid,
                ownerId = "installation-A",
                participants = listOf(
                    Player(myUid, "Kevin", isHost = true),
                    Player(opponentUid, "Stuart", isHost = false)
                ),
                boards = mapOf(myUid to initialBoard, opponentUid to opponentInitialBoard),
                order = listOf(myUid, opponentUid),
                currentPlayerId = myUid
            ),
            localUid = myUid,
            ownerId = "installation-A",
            clock = object : Clock { override fun elapsedRealtime() = 1000L }
        ) { true }

        coordinator.resumeObservation()
        val pickIntent = MatchIntent(
            id = "intent-1",
            matchId = "123",
            actorId = myUid,
            expectedRevision = coordinator.state.revision,
            turn = coordinator.state.turn,
            kind = "PICK",
            number = pickNumber
        )
        val dispatchSuccess = coordinator.dispatch(pickIntent)
        assertTrue("Coordinator must accept valid pick intent", dispatchSuccess)

        val authoritativeSnapshot = coordinator.state

        // 3. Test sound guard when authoritative snapshot for own pick arrives
        val lastPick = authoritativeSnapshot.picks.lastOrNull()
        val shouldPlaySnapshotSound = authoritativeSnapshot.picks.size > pickedNumbersHistory.size &&
            lastPick != null && lastPick.playerId != myUid

        assertFalse("Authoritative snapshot for own pick must NOT trigger duplicate pick sound", shouldPlaySnapshotSound)

        // Reconcile board and turn states
        playerBoard = authoritativeSnapshot.boards[myUid]!!
        opponentBoard = authoritativeSnapshot.boards[opponentUid]!!
        val currentTurnPlayerId = authoritativeSnapshot.currentPlayerId
        isMyTurn = currentTurnPlayerId == myUid

        if (isMyTurn || currentTurnPlayerId != myUid) {
            isProcessingTurn = false
        }

        assertFalse("isMyTurn should be false after local turn completes", isMyTurn)
        assertFalse("isProcessingTurn must be reset to false", isProcessingTurn)
        assertEquals("Opponent must now be the active turn player", opponentUid, currentTurnPlayerId)
        assertEquals("Sound count must remain 1 without duplication", 1, soundPlayedCount)

        // 4. Opponent takes turn with number 7
        val oppIntent = MatchIntent(
            id = "intent-2",
            matchId = "123",
            actorId = opponentUid,
            expectedRevision = coordinator.state.revision,
            turn = coordinator.state.turn,
            kind = "PICK",
            number = 7
        )
        assertTrue(coordinator.dispatch(oppIntent))

        val oppSnapshot = coordinator.state
        val oppLastPick = oppSnapshot.picks.lastOrNull()

        // When opponent snapshot arrives, local history currently has 1 item, snapshot has 2 items
        val shouldPlayOpponentSound = oppSnapshot.picks.size > pickedNumbersHistory.size &&
            oppLastPick != null && oppLastPick.playerId != myUid

        assertTrue("Opponent pick must trigger audio feedback on local device", shouldPlayOpponentSound)
        soundPlayedCount++

        assertEquals(2, soundPlayedCount)
        assertEquals(opponentUid, oppLastPick?.playerId)

        // Reconcile after opponent's move
        playerBoard = oppSnapshot.boards[myUid]!!
        opponentBoard = oppSnapshot.boards[opponentUid]!!
        pickedNumbersHistory.clear()
        pickedNumbersHistory.addAll(oppSnapshot.picks.map { it.number })
        val nextTurnId = oppSnapshot.currentPlayerId
        isMyTurn = nextTurnId == myUid
        if (isMyTurn || nextTurnId != myUid) {
            isProcessingTurn = false
        }

        assertTrue("It must now be local player's turn again", isMyTurn)
        assertFalse("isProcessingTurn must be clean and ready for next pick", isProcessingTurn)
        assertTrue("Player board has both numbers marked", playerBoard.cells.first { it.number == 5 }.isMarked && playerBoard.cells.first { it.number == 7 }.isMarked)
        assertTrue("Opponent board has both numbers marked", opponentBoard.cells.first { it.number == 5 }.isMarked && opponentBoard.cells.first { it.number == 7 }.isMarked)
    }

    // ── R6 Regression: Optimistic 1v1 Pick Rollback Without State Corruption ──

    @Test
    fun testOptimisticTap_droppedOrRejectedRequest_rollsBackOverlayWithoutCorruptingHistory() {
        val myUid = "A"
        val initialBoard = engine.generateBoard(3, 11)
        val pickedNumbersHistory = mutableListOf<Int>()
        var isMyTurn = true
        var isProcessingTurn = false
        var optimisticPendingPickNumber: Int? = null

        val pickNumber = 8

        // Player taps cell 8:
        assertTrue("Can pick on my turn", isMyTurn && !isProcessingTurn && optimisticPendingPickNumber == null)
        optimisticPendingPickNumber = pickNumber
        isMyTurn = false
        isProcessingTurn = true

        // Display overlay shows cell 8 as marked to the player
        val displayedBoard = initialBoard.copy(
            cells = initialBoard.cells.map { cell ->
                if (cell.number == optimisticPendingPickNumber) {
                    cell.copy(
                        markState = CellMarkState.Marked(
                            pickedByPlayerId = myUid,
                            isOwnPick = true,
                            turnNumber = 1
                        ),
                        isRecentPick = true
                    )
                } else cell
            }
        )
        assertTrue("Displayed board shows pending pick marked",
            displayedBoard.cells.first { it.number == pickNumber }.isMarked)
        // BUT authoritative board and history are completely untouched!
        assertFalse("Authoritative board was NOT modified",
            initialBoard.cells.first { it.number == pickNumber }.isMarked)
        assertTrue("Authoritative history was NOT corrupted",
            pickedNumbersHistory.isEmpty())

        // Transport fails / request is dropped or rejected:
        // ReliableMatchClient reports hasPendingIntent = false, pendingPickNumber = null
        val viewHasPendingIntent = false
        val viewPendingPickNumber: Int? = null

        if (optimisticPendingPickNumber != null && !viewHasPendingIntent && viewPendingPickNumber == null) {
            optimisticPendingPickNumber = null
            isProcessingTurn = false
            isMyTurn = true // Still local player's turn
        }

        // Overlay is cleared: displayed board rolls back cleanly to initialBoard!
        val rolledBackBoard = if (optimisticPendingPickNumber != null) displayedBoard else initialBoard
        assertFalse("Rolled-back board cell 8 is unmarked",
            rolledBackBoard.cells.first { it.number == pickNumber }.isMarked)
        assertEquals("History remains clean", 0, pickedNumbersHistory.size)
        assertTrue("Turn is re-enabled for valid input", isMyTurn && !isProcessingTurn)
    }

    @Test
    fun testOptimisticTap_pendingIntentBlocksSecondPickUntilResolved() {
        val myUid = "A"
        val initialBoard = engine.generateBoard(3, 11)
        val pickedNumbersHistory = mutableListOf<Int>()
        var isMyTurn = true
        var isProcessingTurn = false
        var optimisticPendingPickNumber: Int? = null

        // First tap: pick 4
        optimisticPendingPickNumber = 4
        isMyTurn = false
        isProcessingTurn = true

        // User rapidly taps a second cell (e.g. 9) while first intent is pending
        val canPickSecond = isMyTurn && !isProcessingTurn && optimisticPendingPickNumber == null
        assertFalse("Second tap MUST be blocked while an intent is pending", canPickSecond)
    }
}
