package com.bingo.multiplayer.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class AppExitDiagnosticsTest {
    @Test fun crashReportKeepsUsefulFramesWithoutExceptionMessagesOrPayloads() {
        val error = IllegalStateException("secret MQTT password and private chat", IllegalArgumentException("another secret"))
        val text = AppExitDiagnostics.formatCrash(error, 1234)
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("IllegalArgumentException"))
        assertTrue(text.contains("AppExitDiagnosticsTest"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("private chat"))
        assertTrue(text.length <= 16_384)
    }
}
