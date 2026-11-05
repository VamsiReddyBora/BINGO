package com.bingo.multiplayer.presentation.admin

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AdminLatencyControlsTest {
    @Test fun graphColorsMatchExactPingBoundaries() {
        assertEquals(androidx.compose.ui.graphics.Color.White, adminLatencyColor(250f))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFEAB308), adminLatencyColor(251f))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFEAB308), adminLatencyColor(500f))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFDC2626), adminLatencyColor(501f))
    }
    @Test fun lightThemeTraceColorsContrastWithTheGraphBackground() {
        val background = androidx.compose.ui.graphics.Color(0xFFE2E8F0).luminance()
        for (ping in listOf(100f, 250f, 251f, 500f, 501f, 1500f)) {
            val trace = adminLatencyColor(ping, isDark = false)
            assertNotEquals(androidx.compose.ui.graphics.Color.White, trace)
            assertTrue("Light theme trace must contrast by at least 3:1", (background + 0.05f) / (trace.luminance() + 0.05f) >= 3f)
        }
        assertEquals(adminLatencyColor(250f, false), adminLatencyColor(100f, false))
        assertEquals(adminLatencyColor(251f, false), adminLatencyColor(500f, false))
        assertNotEquals(adminLatencyColor(500f, false), adminLatencyColor(501f, false))
    }

}
