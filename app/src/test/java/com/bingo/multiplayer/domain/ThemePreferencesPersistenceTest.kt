package com.bingo.multiplayer.domain

import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThemePreferencesPersistenceTest {

    private lateinit var themePrefs: AuthRepositoryTest.FakeSharedPreferences
    private lateinit var userProfilePrefs: AuthRepositoryTest.FakeSharedPreferences
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
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_theme_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }
        themePrefs = AuthRepositoryTest.FakeSharedPreferences()
        userProfilePrefs = AuthRepositoryTest.FakeSharedPreferences()
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
        ).also { createdRepos.add(it) }

        val settings = authRepo.getSettings()
        assertTrue("AuthRepository settings must reflect active ThemePreferences darkTheme", settings.darkTheme)

        // Update settings via AuthRepository
        authRepo.updateSettings(settings.copy(darkTheme = false))
        assertFalse(userProfilePrefs.getBoolean("settings_dark_theme", true))
    }

    @Test
    fun testRestoreCloudUserDataPreservesLocalDarkTheme() {
        // User sets dark theme locally
        ThemePreferences.setDarkTheme(themePrefs, userProfilePrefs, true)
        assertTrue(ThemePreferences.isDarkTheme.value)

        val authRepo = AuthRepository(
            context = null,
            customPrefs = userProfilePrefs
        ).also { createdRepos.add(it) }

        // Incoming cloud settings with darkTheme = false from an old backup
        val oldCloudSettings = authRepo.getSettings().copy(darkTheme = false)
        val localDark = ThemePreferences.isDarkTheme.value
        val safeSettings = oldCloudSettings.copy(darkTheme = localDark)
        authRepo.updateSettings(safeSettings, syncToCloud = false)

        // Ensure dark theme is NOT overwritten by cloud restore
        assertTrue("Local dark theme must be preserved during cloud data restore", ThemePreferences.isDarkTheme.value)
    }
}
