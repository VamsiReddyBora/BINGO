package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.domain.model.InGameChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MatchPresentationPolicyTest {
    @Test fun heartbeatSnapshotsNeverReplayPickFeedback() {
        val tracker = PickFeedbackTracker(null)
        val pick = RecentPick(7, "B", 2, 100)
        assertTrue(tracker.observe(pick, false))
        repeat(100) { assertFalse(tracker.observe(pick.copy(timestamp = 200L + it), false)) }
        assertTrue(tracker.observe(RecentPick(9, "B", 4), false))
        assertFalse(tracker.observe(RecentPick(9, "B", 4), false))
    }
    @Test fun restoredAndReconciledPicksAreSilentAndNextLivePickStillVibrates() {
        val restored = RecentPick(7, "B", 2)
        val tracker = PickFeedbackTracker(restored)
        assertFalse(tracker.observe(restored.copy(timestamp = 999), false))
        val caughtUp = RecentPick(9, "B", 4)
        assertFalse(tracker.observe(caughtUp, true))
        assertFalse(tracker.observe(caughtUp, false))
        assertTrue(tracker.observe(RecentPick(10, "B", 6), false))
    }
    @Test fun decisionDeadlineIsExactlyFifteenSecondsAndDoesNotResetOnHeartbeats() {
        val prompt = InterruptionPromptState.open(InterruptionPromptState(), "first", 1, 1000, false)
        assertEquals(15, prompt.remainingSeconds(1000))
        assertEquals(1, prompt.remainingSeconds(15_999))
        assertEquals(0, prompt.remainingSeconds(16_000))
        assertEquals(prompt, InterruptionPromptState.open(prompt, "first", 1, 15_000, false))
        val restored = Json.decodeFromString<InterruptionPromptState>(Json.encodeToString(prompt))
        assertEquals(0, restored.remainingSeconds(99_000))
    }
    @Test fun aDecisionSurvivesOldAsyncWritesAndReopenAttempts() {
        val original = InterruptionPromptState.open(InterruptionPromptState(), "first", 1, 1000, false)
        val decided = original.decide()
        assertEquals(decided, InterruptionPromptState.open(decided, "first", 1, 99_000, false))
        val merged = InterruptionPromptState.merge(decided, original.copy(deadlineMillis = 90_000))
        assertTrue(merged.decided)
        assertEquals(16_000L, merged.deadlineMillis)
        val next = InterruptionPromptState.open(merged, "second", 2, 99_000, false)
        assertFalse(next.decided)
        assertEquals(15, next.remainingSeconds(99_000))
        assertEquals(next, InterruptionPromptState.merge(next, original))
    }
    @Test fun alreadyPausedMatchDoesNotStackAnotherDecisionOverThePauseDialog() {
        assertTrue(InterruptionPromptState.open(InterruptionPromptState(), "outage", 1, 1000, true).decided)
    }
    @Test fun localStatusInsertionPreservesAuthoritativePlayerChatOrderWhenPhoneClocksDiffer() {
        val accepted = listOf(
            InGameChatMessage(1, "First accepted", true, timestamp = 2000),
            InGameChatMessage(2, "Second accepted", false, timestamp = 1000))
        val notice = MatchSystemMessages.notice(1, "A", "status", "Synchronizing…", 1500)
        val merged = MatchSystemMessages.merge(accepted, listOf(notice, notice))
        assertEquals(accepted, merged.filterNot { it.isSystemMessage })
        assertEquals(3, merged.size)
        assertEquals(merged, MatchSystemMessages.merge(merged, listOf(notice)))
    }

    @Test fun statusMessagesStayCenteredUniqueAndSeparateFromUserChat() {
        val first = MatchSystemMessages.notice(1, "A", "disconnect:1", "Stuart disconnected (warning 1)", 1000)
        val replay = MatchSystemMessages.notice(1, "A", "disconnect:1", "same event", 2000)
        assertEquals(first.id, replay.id)
        assertTrue(first.isSystemMessage)
        assertNull(first.senderName)
        assertEquals(listOf(first), MatchSystemMessages.merge(listOf(first), listOf(replay)))
        assertNotEquals(first.id, MatchSystemMessages.notice(2, "A", "disconnect:1", "other match", 1000).id)
    }
}
