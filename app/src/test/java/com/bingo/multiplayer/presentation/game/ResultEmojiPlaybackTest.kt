package com.bingo.multiplayer.presentation.game

import org.junit.Assert.*
import org.junit.Test

class ResultEmojiPlaybackTest {
    private fun decide(
        consumed: Boolean = false, over: Boolean = true, result: StampResultType = StampResultType.WON,
        exiting: Boolean = false, preferred: Boolean = true, visible: Boolean = true, flag: Boolean = true
    ) = ResultEmojiPlayback.decide(consumed, over, result, exiting, preferred, visible, flag)

    @Test fun aFreshWinAndAFreshLossGetTheirOwnEffect() {
        assertEquals(StampResultType.WON, decide().launch)
        assertEquals(StampResultType.LOST, decide(result = StampResultType.LOST).launch)
    }

    @Test fun codeFlagAlwaysOverridesAnEnabledPlayerToggle() {
        assertNull(decide(flag = false).launch)
        assertNull(decide(result = StampResultType.LOST, flag = false).launch)
        assertTrue(decide(flag = false).consumed)
    }

    @Test fun disablingThePreferenceConsumesTheResultWithoutPlayingLater() {
        val disabled = decide(preferred = false)
        assertNull(disabled.launch)
        assertNull(decide(consumed = disabled.consumed, preferred = true).launch)
    }

    @Test fun reviewRecompositionAndCompletedResultRestorationDoNotReplay() {
        val first = decide()
        assertTrue(first.consumed)
        assertNull(decide(consumed = first.consumed).launch)
        // A saved result initializes the UI's consumed state to isGameOver=true.
        assertNull(decide(consumed = true, result = StampResultType.LOST).launch)
    }

    @Test fun leavingAndBackgroundCompletionCannotStartAnEffect() {
        assertNull(decide(exiting = true).launch)
        val hidden = decide(visible = false)
        assertNull(hidden.launch)
        assertNull(decide(consumed = hidden.consumed, visible = true).launch)
    }

    @Test fun runnerAndOfflineResultsKeepTheirExistingStampWithoutAnEmojiBurst() {
        for (result in listOf(StampResultType.RUNNER, StampResultType.OFFLINE)) assertNull(decide(result = result).launch)
    }

    @Test fun aNewActiveMatchArmsOnlyItsNextFinish() {
        val playing = decide(consumed = true, over = false)
        assertFalse(playing.consumed)
        assertNull(playing.launch)
        assertEquals(StampResultType.LOST, decide(consumed = playing.consumed, result = StampResultType.LOST).launch)
    }
}
