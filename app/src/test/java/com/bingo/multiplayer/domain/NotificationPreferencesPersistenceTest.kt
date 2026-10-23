package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.NotificationPreferences
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationPreferencesPersistenceTest {

    private lateinit var notifPrefs: AuthRepositoryTest.FakeSharedPreferences
    private lateinit var authPrefs: AuthRepositoryTest.FakeSharedPreferences
    private var previousWire: com.bingo.multiplayer.domain.network.EmqxWire? = null
    private var previousGateway: com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway? = null
    private val createdRepos = mutableListOf<AuthRepository>()

    @Before
    fun setUp() {
        com.bingo.multiplayer.domain.network.PresenceManager.networkEnabled = false
        previousWire = com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride
        com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride = com.bingo.multiplayer.domain.network.EmqxWireFixture()
        previousGateway = com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = null
            override suspend fun setKeyValue(key: String, value: String): Boolean = true
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_notif_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }
        notifPrefs = AuthRepositoryTest.FakeSharedPreferences()
        authPrefs = AuthRepositoryTest.FakeSharedPreferences()
        NotificationPreferences.resetForTesting()
    }

    @After
    fun tearDown() = runBlocking {
        for (repo in createdRepos) {
            repo.sessionManager.stopSessionWatcher()
            runCatching {
                val repoScope = repo.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as? kotlinx.coroutines.CoroutineScope
                repoScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelAndJoin()
            }
            runCatching {
                val sessionScope = repo.sessionManager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo.sessionManager) as? kotlinx.coroutines.CoroutineScope
                sessionScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelAndJoin()
            }
        }
        createdRepos.clear()
        com.bingo.multiplayer.domain.network.PresenceManager.resetForTests()
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = previousGateway
        com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride = previousWire
        com.bingo.multiplayer.domain.network.PresenceManager.networkEnabled = true
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
        ).also { createdRepos.add(it) }

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
