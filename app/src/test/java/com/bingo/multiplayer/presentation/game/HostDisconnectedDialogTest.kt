package com.bingo.multiplayer.presentation.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Tests the actual properties consumed by the production dialog; not an Android tap test. */
class HostDisconnectedDialogTest {
    @Test fun hostHoldDialogRejectsBackAndOutsideDismissal() {
        assertFalse(HostDisconnectedDialogSpec.properties.dismissOnBackPress)
        assertFalse(HostDisconnectedDialogSpec.properties.dismissOnClickOutside)
    }
    @Test fun interruptionAndPauseDialogsRejectBackAndOutsideDismissal() {
        assertFalse(MatchDecisionDialogSpec.properties.dismissOnBackPress)
        assertFalse(MatchDecisionDialogSpec.properties.dismissOnClickOutside)
    }
    @Test fun hostHoldDialogUsesTheAgreedBlockingMessage() {
        assertEquals("🔴 Host disconnected", HostDisconnectedDialogSpec.title)
        assertEquals("Your board and remaining time are preserved.", HostDisconnectedDialogSpec.message)
    }
}
