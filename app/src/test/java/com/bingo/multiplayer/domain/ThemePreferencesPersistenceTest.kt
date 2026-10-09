package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.model.UserSettings
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThemePreferencesPersistenceTest {

    private lateinit var themePrefs: AuthRepositoryTest.FakeSharedPreferences
    private lateinit var userProfilePrefs: AuthRepositoryTest.FakeSharedPreferences

    @Before
    fun setUp() {
        themePrefs = AuthRepositoryTest.FakeSharedPreferences()
        userProfilePrefs = AuthRepositoryTest.FakeSharedPreferences()
    }

    @Test
    fun testThemeDefaultsToLightWhenNoSavedPreferences() {
        ThemePreferences.initWithPrefs(themePrefs, userProfilePrefs)
        assertFalse("Theme should default to light when no saved preference exists", ThemePreferences.isDarkTheme.value)
    }

    @Test
    fun testThemeRemembersDarkSettingAcrossAppRestarts() {
        // User switches theme to AMOLED Pure Black
        ThemePreferences.setDarkTheme(themePrefs, userProfilePrefs, true)
        assertTrue("In-memory dark theme should be true after setting", ThemePreferences.isDarkTheme.value)
        assertTrue("Theme prefs should persist is_dark_theme = true", themePrefs.getBoolean("is_dark_theme", false))
        assertTrue("Profile prefs should persist settings_dark_theme = true", userProfilePrefs.getBoolean("settings_dark_theme", false))

        // Simulate app kill and recreation: reset in-memory state to false
        ThemePreferences.isDarkTheme.value = false
        assertFalse(ThemePreferences.isDarkTheme.value)

        // New app start: initWithPrefs runs (equivalent to init(context) in MainActivity.onCreate)
        ThemePreferences.initWithPrefs(themePrefs, userProfilePrefs)
        assertTrue("ThemePreferences must restore darkTheme = true on app startup", ThemePreferences.isDarkTheme.value)
    }

    @Test
    fun testThemeRemembersLightSettingWhenSwitchedBack() {
        ThemePreferences.setDarkTheme(themePrefs, userProfilePrefs, true)
        assertTrue(ThemePreferences.isDarkTheme.value)

        // Switch back to Light
        ThemePreferences.setDarkTheme(themePrefs, userProfilePrefs, false)
        assertFalse(ThemePreferences.isDarkTheme.value)
        assertFalse(themePrefs.getBoolean("is_dark_theme", true))
        assertFalse(userProfilePrefs.getBoolean("settings_dark_theme", true))

        // Simulate app restart
        ThemePreferences.initWithPrefs(themePrefs, userProfilePrefs)
        assertFalse(ThemePreferences.isDarkTheme.value)
    }

    @Test
    fun testAuthRepositorySettingsSyncsWithThemePreferences() {
        ThemePreferences.setDarkTheme(themePrefs, userProfilePrefs, true)

        val authRepo = AuthRepository(
            context = null,
            customPrefs = userProfilePrefs
        )

        val settings = authRepo.getSettings()
        assertTrue("AuthRepository settings must reflect active ThemePreferences darkTheme", settings.darkTheme)

        // Update settings via AuthRepository
        authRepo.updateSettings(settings.copy(darkTheme = false))
        assertFalse(userProfilePrefs.getBoolean("settings_dark_theme", true))
    }
}
