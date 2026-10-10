package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.BingoSoundEffects
import com.bingo.multiplayer.core.designsystem.SoundPreferences
import com.bingo.multiplayer.domain.model.UserSettings
import org.junit.Assert.*
import org.junit.Test

class SoundPreferencesTest {

    @Test
    fun testSoundPresets_exactlyTenPresetsWithOneDefault() {
        val presets = BingoSoundEffects.PRESETS
        assertEquals("Must have exactly 10 sound presets", 10, presets.size)

        val defaultPresets = presets.filter { it.isDefault }
        assertEquals("Must have exactly 1 default preset", 1, defaultPresets.size)
        assertEquals("Default preset must be classic_pop", "classic_pop", defaultPresets.first().id)

        // Verify all preset IDs are unique and non-empty
        val uniqueIds = presets.map { it.id }.toSet()
        assertEquals("All 10 preset IDs must be unique", 10, uniqueIds.size)

        presets.forEach { preset ->
            assertTrue("Preset ID cannot be blank", preset.id.isNotBlank())
            assertTrue("Preset name cannot be blank", preset.name.isNotBlank())
            assertTrue("Preset subtitle cannot be blank", preset.subtitle.isNotBlank())
        }
    }

    @Test
    fun testSoundPreferences_stateModification() {
        SoundPreferences.soundEnabled.value = true
        SoundPreferences.hapticsEnabled.value = true
        SoundPreferences.pickSoundPresetId.value = "classic_pop"

        assertTrue(SoundPreferences.soundEnabled.value)
        assertTrue(SoundPreferences.hapticsEnabled.value)
        assertEquals("classic_pop", SoundPreferences.pickSoundPresetId.value)

        // Toggle sound
        SoundPreferences.soundEnabled.value = false
        assertFalse(SoundPreferences.soundEnabled.value)

        // Toggle haptics
        SoundPreferences.hapticsEnabled.value = false
        assertFalse(SoundPreferences.hapticsEnabled.value)
        assertFalse(SoundPreferences.isHapticsAllowed())

        SoundPreferences.hapticsEnabled.value = true
        assertTrue(SoundPreferences.isHapticsAllowed())

        // Switch to arcade blip
        SoundPreferences.pickSoundPresetId.value = "arcade_blip"
        assertEquals("arcade_blip", SoundPreferences.pickSoundPresetId.value)

        // Switch to crystal chime
        SoundPreferences.pickSoundPresetId.value = "crystal_chime"
        assertEquals("crystal_chime", SoundPreferences.pickSoundPresetId.value)
    }

    @Test
    fun testUserSettings_holdsSoundAndHapticsConfiguration() {
        val settings = UserSettings(
            soundEnabled = false,
            hapticsEnabled = false,
            pickSoundPresetId = "marimba_note"
        )

        assertFalse(settings.soundEnabled)
        assertFalse(settings.hapticsEnabled)
        assertEquals("marimba_note", settings.pickSoundPresetId)
    }
}
