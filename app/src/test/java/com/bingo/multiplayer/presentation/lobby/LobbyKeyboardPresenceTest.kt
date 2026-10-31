package com.bingo.multiplayer.presentation.lobby

import org.junit.Assert.*
import org.junit.Test

class LobbyKeyboardPresenceTest {
    @Test fun nativeWindowTracksKeyboardWhenComposeInsetsAreConsumedByDecorResize() {
        assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, 2328, 3f))
        assertTrue(LobbyKeyboardPresence.visible(false, true, 2400, 1500, 3f))
        assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, 2328, 3f))
    }

    @Test fun visibleWindowResizeIsFallbackWhenTheImeFlagIsMissingOrConsumed() {
        assertTrue(LobbyKeyboardPresence.visible(false, null, 2400, 1500, 3f))
        assertTrue(LobbyKeyboardPresence.visible(false, false, 2400, 1500, 3f))
        assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, 2328, 3f))
    }

    @Test fun staleComposeVisibilityCannotKeepCursorActiveAfterWindowKeyboardCloses() {
        assertFalse(LobbyKeyboardPresence.visible(true, false, 2400, 2328, 3f))
    }

    @Test fun navigationBarsStatusBarsAndTinyWindowChangesAreNotAKeyboard() {
        for (bottom in listOf(2400, 2328, 2300, 2200))
            assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, bottom, 3f))
    }

    @Test fun floatingKeyboardUsesNativeVisibilityWithoutAnyWindowResize() {
        assertTrue(LobbyKeyboardPresence.visible(false, true, 2400, 2328, 3f))
    }

    @Test fun unavailableNativeInsetsCanUseComposeAndInvalidGeometryDoesNotInventAKeyboard() {
        assertTrue(LobbyKeyboardPresence.visible(true, null, 0, 0, 3f))
        assertFalse(LobbyKeyboardPresence.visible(false, null, 0, 0, 3f))
        assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, -1, 3f))
        assertFalse(LobbyKeyboardPresence.visible(false, false, 2400, 2500, 3f))
    }
}
