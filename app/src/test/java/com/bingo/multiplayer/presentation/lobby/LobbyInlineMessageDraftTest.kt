package com.bingo.multiplayer.presentation.lobby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LobbyInlineMessageDraftTest {
    @Test fun opensEmptyInsteadOfEditingOrBroadcastingThePreviousMessage() {
        val draft = LobbyInlineMessageDraft()
        draft.open(); assertTrue(draft.editing); assertEquals("", draft.text)
        draft.change("Typing here"); assertEquals("Typing here", draft.text)
        draft.open(); assertEquals("Typing here", draft.text) // Retap chat keeps the unsent text.
    }

    @Test fun enterAndKeyboardDismissalPublishExactlyOnceWhileAwaitingAcknowledgment() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("  Hello Kevin  ")
        assertFalse(draft.keyboardChanged(true, true))
        val ack = CompletableDeferred<Boolean>(); val sent = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            draft.submit { sent += it; ack.await() }
        }
        assertTrue(draft.sending)
        assertFalse(draft.keyboardChanged(false, true))
        assertFalse(draft.submit { sent += it; true })
        ack.complete(true); assertTrue(first.await())
        assertEquals(listOf("Hello Kevin"), sent)
        assertFalse(draft.editing); assertEquals("", draft.text)
        assertFalse(draft.submit { fail("Already submitted"); true })
        draft.open(); assertEquals("", draft.text)
    }

    @Test fun keyboardDismissalIsArmedOnlyAfterTheUserActuallyOpensTheKeyboard() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Ready soon")
        assertFalse(draft.keyboardChanged(false, true))
        assertFalse(draft.keyboardChanged(true, true))
        assertTrue(draft.keyboardChanged(false, true))
        var sent = ""; assertTrue(draft.submit { sent = it; true })
        assertEquals("Ready soon", sent)
        assertFalse(draft.keyboardChanged(false, true))
    }

    @Test fun backgroundingAndReturningDoNotSendAnUnfinishedDraft() {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Still typing")
        draft.keyboardChanged(true, true)
        assertFalse(draft.keyboardChanged(false, false))
        assertFalse(draft.keyboardChanged(false, true))
        assertEquals("Still typing", draft.text); assertTrue(draft.editing)
        draft.keyboardChanged(true, true)
        assertTrue(draft.keyboardChanged(false, true))
    }

    @Test fun dismissingAnEmptyFieldDoesNotSendOrDeleteTheExistingMessage() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("  ")
        var calls = 0; assertTrue(draft.submit { calls++; true })
        assertEquals(0, calls); assertFalse(draft.editing)
    }

    @Test fun failedSendRetainsTheTextForRetryAndNeverClaimsSuccess() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Keep this draft")
        assertFalse(draft.submit { false }); assertFalse(draft.sending)
        assertTrue(draft.editing); assertEquals("Keep this draft", draft.text); assertNotNull(draft.error)
        draft.open(); assertEquals("Keep this draft", draft.text)
        assertFalse(draft.submit { throw IOException("Offline") })
        assertTrue(draft.editing); assertNotNull(draft.error)
        assertFalse(draft.keyboardChanged(false, true))
        assertTrue(draft.submit { true }); assertFalse(draft.editing); assertNull(draft.error)
    }

    @Test fun matchStartDiscardsDraftAndIgnoresLateSendCompletion() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Wait")
        val ack = CompletableDeferred<Boolean>()
        val sending = async(start = CoroutineStart.UNDISPATCHED) { draft.submit { ack.await() } }
        draft.closeForMatchStart(); ack.complete(true)
        assertFalse(sending.await()); assertFalse(draft.editing); assertFalse(draft.sending)
        assertEquals("", draft.text)
        assertFalse(draft.keyboardChanged(false, true))
    }

    @Test fun inputKeepsExistingLengthLimitAndSingleLinePresentation() {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Hello\nKevin\r!")
        assertEquals("Hello Kevin !", draft.text)
        draft.change("x".repeat(501)); assertEquals(500, draft.text.length)
    }

    @Test fun temporaryImeWindowFocusLossDefersDismissalWithoutDiscardingIt() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Send on keyboard close")
        assertFalse(draft.keyboardChanged(true, true, true))
        assertFalse(draft.keyboardChanged(false, true, false))
        assertTrue(draft.keyboardChanged(false, true, true))
        var sent = ""; assertTrue(draft.submit { sent = it; true })
        assertEquals("Send on keyboard close", sent); assertFalse(draft.editing)
    }

    @Test fun emptyDismissalAfterTransientImeFocusLossClosesWithoutASecondBackOrPacket() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open()
        draft.keyboardChanged(true, true, true)
        assertFalse(draft.keyboardChanged(false, true, false))
        assertTrue(draft.keyboardChanged(false, true, true))
        assertTrue(draft.submit { fail("Empty input cannot publish"); true })
        assertFalse(draft.editing)
    }

    @Test fun realBackgroundingDiscardsAnUnfocusedPendingKeyboardDismissal() {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Unfinished")
        draft.keyboardChanged(true, true, true)
        assertFalse(draft.keyboardChanged(false, true, false))
        assertFalse(draft.keyboardChanged(false, false, false))
        assertFalse(draft.keyboardChanged(false, true, true))
        assertTrue(draft.editing); assertEquals("Unfinished", draft.text)
    }

    @Test fun consumedComposeInsetsStillSubmitOnNativeKeyboardCloseWithoutAnyBackAction() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("No extra Back")
        val sent = mutableListOf<String>()
        suspend fun sample(nativeIme: Boolean, bottom: Int) {
            val visible = LobbyKeyboardPresence.visible(false, nativeIme, 2400, bottom, 3f)
            if (draft.keyboardChanged(visible, true)) draft.submit { sent += it; true }
        }
        sample(false, 2328); assertTrue(draft.editing)
        sample(true, 1500); sample(true, 1500)
        sample(false, 2328); sample(false, 2328)
        assertEquals(listOf("No extra Back"), sent); assertFalse(draft.editing)
    }

    @Test fun nativeKeyboardClosureAlsoRemovesEmptyInputWithoutPublishingAnything() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("  ")
        assertFalse(draft.keyboardChanged(LobbyKeyboardPresence.visible(false, true, 2400, 1500, 3f), true))
        assertTrue(draft.keyboardChanged(LobbyKeyboardPresence.visible(false, false, 2400, 2328, 3f), true))
        assertTrue(draft.submit { fail("Empty bubble must not send"); true })
        assertFalse(draft.editing)
    }
    @Test fun keyboardFromAnotherFieldCannotCloseTheNewChatBeforeItTakesFocus() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open()
        assertFalse(draft.keyboardChanged(true, true, true, inputFocused = false))
        assertFalse(draft.keyboardChanged(false, true, true, inputFocused = false))
        assertTrue(draft.editing)
        // Now the actual inline input acquires focus and opens its own keyboard.
        assertFalse(draft.keyboardChanged(true, true, true, inputFocused = true))
        draft.change("Hello from the inline field")
        assertTrue(draft.keyboardChanged(false, true, true, inputFocused = true))
        var sent = ""; assertTrue(draft.submit { sent = it; true })
        assertEquals("Hello from the inline field", sent)
    }

    @Test fun unrelatedImeDoesNotSubmitRetainedDraftWhenChatIsReopened() {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Keep typing")
        assertFalse(draft.keyboardChanged(true, true, true, inputFocused = false))
        assertFalse(draft.keyboardChanged(false, true, true, inputFocused = true))
        assertEquals("Keep typing", draft.text); assertTrue(draft.editing)
    }

    @Test fun losingTextFocusDuringRealKeyboardDismissalStillSubmitsOnce() = runBlocking {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Send when closed")
        assertFalse(draft.keyboardChanged(true, true, true, inputFocused = true))
        assertFalse(draft.keyboardChanged(false, true, false, inputFocused = false))
        assertTrue(draft.keyboardChanged(false, true, true, inputFocused = false))
        var calls = 0; assertTrue(draft.submit { calls++; true }); assertEquals(1, calls)
        assertFalse(draft.keyboardChanged(false, true, true, inputFocused = false))
    }

    @Test fun temporaryHiddenFrameWhileKeyboardOpensDoesNotCloseTheChat() = runTest {
        val draft = LobbyInlineMessageDraft(); draft.open()
        assertFalse(draft.settledKeyboardChanged(true, true, true, true))
        val temporaryHidden = async { draft.settledKeyboardChanged(false, true, true, true) }
        runCurrent(); advanceTimeBy(100L)
        assertFalse("A single hidden animation frame must not submit", temporaryHidden.isCompleted)
        temporaryHidden.cancel(); temporaryHidden.join() // Actual LaunchedEffect restarts when IME becomes visible.
        assertFalse(draft.settledKeyboardChanged(true, true, true, true))
        assertTrue(draft.editing)
        draft.change("Now the keyboard is open")
        val realClose = async { draft.settledKeyboardChanged(false, true, true, true) }
        runCurrent(); advanceTimeBy(200L); runCurrent()
        assertTrue(realClose.await())
        var sent = ""; assertTrue(draft.submit { sent = it; true })
        assertEquals("Now the keyboard is open", sent)
    }

    @Test fun persistentKeyboardCloseStillSubmitsEmptyInputWithoutAnExtraBack() = runTest {
        val draft = LobbyInlineMessageDraft(); draft.open()
        draft.settledKeyboardChanged(true, true, true, true)
        val close = async { draft.settledKeyboardChanged(false, true, true, false) }
        runCurrent(); advanceTimeBy(200L); runCurrent(); assertTrue(close.await())
        assertTrue(draft.submit { fail("Blank input cannot publish"); true })
        assertFalse(draft.editing)
    }

    @Test fun backgroundingCancelsPendingKeyboardCloseAndRetainsTheDraft() = runTest {
        val draft = LobbyInlineMessageDraft(); draft.open(); draft.change("Unfinished")
        draft.settledKeyboardChanged(true, true, true, true)
        val close = async { draft.settledKeyboardChanged(false, true, true, true) }
        runCurrent(); advanceTimeBy(100L)
        assertFalse(close.isCompleted)
        close.cancel(); close.join()
        assertFalse(draft.settledKeyboardChanged(false, false, false, false))
        assertFalse(draft.settledKeyboardChanged(false, true, true, false))
        assertTrue(draft.editing); assertEquals("Unfinished", draft.text)
    }

}
