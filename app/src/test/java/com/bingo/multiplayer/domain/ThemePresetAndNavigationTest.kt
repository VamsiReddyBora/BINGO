package com.bingo.multiplayer.domain

import androidx.compose.ui.graphics.Color
import com.bingo.multiplayer.core.designsystem.AmoledDarkColors
import com.bingo.multiplayer.core.designsystem.CleanLightColors
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ThemePresetAndNavigationTest {

    @Test
    fun testDefaultAppPaletteIsMatteSlate() {
        assertEquals("matte_slate", ThemePreferences.accentColorId.value)
        assertEquals("matte_slate", ThemePreferences.PALETTES.first().id)
        val palette = ThemePreferences.getPalette("matte_slate")
        assertNotNull(palette)
        assertEquals("Matte Slate", palette.name)
    }

    @Test
    fun testLightModePresets() {
        // Light theme presets:
        // my pick: #7E22CE
        // last pick: #D9B13D
        // opponent pick: #C2410C
        // line completion: #64748B
        assertEquals("#7E22CE", ThemePreferences.DEFAULT_MY_PICK_HEX)
        assertEquals("#D9B13D", ThemePreferences.DEFAULT_RECENT_PICK_LIGHT_HEX)
        assertEquals("#C2410C", ThemePreferences.DEFAULT_OPPONENT_PICK_HEX)
        assertEquals("#64748B", ThemePreferences.DEFAULT_COMPLETED_LINE_HEX)

        assertEquals("#D9B13D", ThemePreferences.getDefaultRecentPickHex(isDark = false))

        assertEquals(Color(0xFF7E22CE), CleanLightColors.cellPlayerPickBg)
        assertEquals(Color(0xFFD9B13D), CleanLightColors.recentPickBg)
        assertEquals(Color(0xFFC2410C), CleanLightColors.cellOpponentPickBg)
        assertEquals(Color(0xFF64748B), CleanLightColors.completedLineBg)
    }

    @Test
    fun testDarkModePresets() {
        // Dark theme presets:
        // my pick: #7E22CE
        // last pick: #FFFFFF
        // opponent pick: #C2410C
        // line completion: #64748B
        assertEquals("#7E22CE", ThemePreferences.DEFAULT_MY_PICK_HEX)
        assertEquals("#FFFFFF", ThemePreferences.DEFAULT_RECENT_PICK_DARK_HEX)
        assertEquals("#C2410C", ThemePreferences.DEFAULT_OPPONENT_PICK_HEX)
        assertEquals("#64748B", ThemePreferences.DEFAULT_COMPLETED_LINE_HEX)

        assertEquals("#FFFFFF", ThemePreferences.getDefaultRecentPickHex(isDark = true))

        assertEquals(Color(0xFF7E22CE), AmoledDarkColors.cellPlayerPickBg)
        assertEquals(Color(0xFFFFFFFF), AmoledDarkColors.recentPickBg)
        assertEquals(Color(0xFFC2410C), AmoledDarkColors.cellOpponentPickBg)
        assertEquals(Color(0xFF64748B), AmoledDarkColors.completedLineBg)
    }
}
