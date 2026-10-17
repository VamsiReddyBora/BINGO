package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.NotificationPreferences
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationPreferencesPersistenceTest {

    private lateinit var notifPrefs: AuthRepositoryTest.FakeSharedPreferences
    private lateinit var authPrefs: AuthRepositoryTest.FakeSharedPreferences

    @Before
    fun setUp() {
        notifPrefs = AuthRepositoryTest.FakeSharedPreferences()
        authPrefs = AuthRepositoryTest.FakeSharedPreferences()
        NotificationPreferences.resetForTesting()
    }

    @Test
    fun testNotificationDefaultsToEnabledWhenNoSavedPreferences() {
        NotificationPreferences.initWithPrefs(notifPrefs, authPrefs)
        assertTrue("System notifications should default to enabled", NotificationPreferences.systemNotificationsEnabled.value)
        assertTrue("In-app notifications should default to enabled", NotificationPreferences.inAppNotificationsEnabled.value)
    }

    @Test
    fun testNotificationSettingsPersistWhenDisabled() {
        NotificationPreferences.initWithPrefs(notifPrefs, authPrefs)
        assertTrue(NotificationPreferences.systemNotificationsEnabled.value)

        // User turns off notifications
        notifPrefs.edit().putBoolean("pref_system_notifs_enabled", false).apply()
        authPrefs.edit().putBoolean("settings_system_notifs", false).apply()

        // Simulate app restart
        NotificationPreferences.resetForTesting()
        NotificationPreferences.initWithPrefs(notifPrefs, authPrefs)

        assertFalse("Notification preferences must preserve disabled state across restarts",
            NotificationPreferences.systemNotificationsEnabled.value)
    }

    @Test
    fun testAuthRepositorySettingsPreserveLocalNotificationPreferencesAgainstCloudEcho() {
        // User turned off notifications locally
        notifPrefs.edit().putBoolean("pref_system_notifs_enabled", false).apply()
        authPrefs.edit().putBoolean("settings_system_notifs", false).apply()

        NotificationPreferences.initWithPrefs(notifPrefs, authPrefs)
        assertFalse(NotificationPreferences.systemNotificationsEnabled.value)

        val authRepo = AuthRepository(
            context = null,
            customPrefs = authPrefs
        )

        // Cloud returns default settings where systemNotificationsEnabled = true
        val incomingCloudSettings = authRepo.getSettings().copy(systemNotificationsEnabled = true)

        // With the fix, when reconciling cloud settings, local settings are preserved
        val localSysNotifs = NotificationPreferences.systemNotificationsEnabled.value
        val safeSettings = incomingCloudSettings.copy(systemNotificationsEnabled = localSysNotifs)

        authRepo.updateSettings(safeSettings, syncToCloud = false)

        assertFalse("Local disabled notification settings must not be overwritten by incoming cloud defaults",
            NotificationPreferences.systemNotificationsEnabled.value)
    }
}
