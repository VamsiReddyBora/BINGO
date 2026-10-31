package com.bingo.multiplayer.presentation.lobby

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bingo.multiplayer.domain.network.LobbyMessageSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** State used by the real inline field; drafts stay local until the user submits. */
internal class LobbyInlineMessageDraft {
    var editing by mutableStateOf(false)
        private set
    var text by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var keyboardWasVisible = false
    private var generation = 0

    fun open() {
        if (sending) return
        if (!editing) text = ""
        editing = true
        error = null
    }

    fun change(value: String) {
        if (!editing || sending) return
        text = value.replace('\n', ' ').replace('\r', ' ').take(LobbyMessageSession.MAX_TEXT)
        error = null
    }

    /** A hidden keyboard at mount or during backgrounding is not a user submission. */
    fun keyboardChanged(visible: Boolean, foreground: Boolean, windowFocused: Boolean = true,
        inputFocused: Boolean = true): Boolean {
        if (!editing || sending || !foreground) {
            keyboardWasVisible = false
            return false
        }
        // Another text field's IME, or an opening animation before our field takes focus,
        // must not arm an immediate empty submission and close the new editor.
        if (visible) { if (inputFocused) keyboardWasVisible = true; return false }
        // An IME window may momentarily take focus during dismissal. Keep the close edge
        // until focus returns; only an actual lifecycle background event discards it.
        if (!windowFocused) return false
        return keyboardWasVisible.also { keyboardWasVisible = false }
    }

    /** LaunchedEffect cancels this wait if the IME reappears during its opening animation. */
    suspend fun settledKeyboardChanged(visible: Boolean, foreground: Boolean, windowFocused: Boolean,
        inputFocused: Boolean): Boolean {
        if (!visible && foreground && windowFocused) delay(200L)
        return keyboardChanged(visible, foreground, windowFocused, inputFocused)
    }

    fun closeForMatchStart() {
        generation++
        editing = false
        text = ""
        error = null
        sending = false
        keyboardWasVisible = false
    }

    /** Enter and the resulting IME-close event may both arrive; publish at most once. */
    suspend fun submit(send: suspend (String) -> Boolean): Boolean {
        if (!editing || sending) return false
        keyboardWasVisible = false
        val value = LobbyMessageSession.cleanText(text)
        if (value == null) { editing = false; text = ""; return true }
        val started = generation
        sending = true
        error = null
        try {
            val sent = send(value)
            if (started != generation) return false
            if (sent) { editing = false; text = "" }
            else error = "Not sent. Tap here to retry."
            return sent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (started == generation) error = "Not sent. Tap here to retry."
            return false
        } finally {
            if (started == generation) sending = false
        }
    }
}
