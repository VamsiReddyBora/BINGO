package com.bingo.multiplayer.domain.repository

import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.AuthState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AuthRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        repository = AuthRepository(
            context = null,
            customPrefs = fakePrefs
        )
    }

    @org.junit.After
    fun tearDown() {
        repository.sessionManager.stopSessionWatcher()
    }

    @Test
    fun testUnauthenticated_onLaunchWithNoPersistedUser() = runBlocking {
        val state = repository.verifyPersistedSession()
        assertTrue("State should be Unauthenticated", state is AuthState.Unauthenticated)
    }

    @Test
    fun testGoogleSignIn_persistsProfileAndSession() = runBlocking {
        val id = "cleanup_${System.nanoTime()}"
        repository.onGoogleSignInSuccess(id, "Alice Wonder", "alice@example.com", customUsername = id)
        val profile = (repository.authState.value as AuthState.Authenticated).user
        assertEquals("google_$id", profile.uid)
        assertEquals("Alice Wonder", profile.displayName)
        assertEquals(id, profile.username)
        assertEquals("alice@example.com", profile.email)
        assertEquals(AuthProvider.GOOGLE, profile.provider)

        val repo2 = AuthRepository(context = null, customPrefs = fakePrefs)
        val state = repo2.verifyPersistedSession() as AuthState.Authenticated
        assertEquals(profile, state.user)
        repo2.sessionManager.stopSessionWatcher()
    }

    @Test
    fun testLegacyPlayGamesProfile_remainsReadable() {
        fakePrefs.edit().putString("uid", "pg_gamer_456")
            .putString("display_name", "ChampionPlayer")
            .putString("provider", AuthProvider.PLAY_GAMES.name).apply()
        val profile = repository.getPersistedUserSync()!!
        assertEquals("pg_gamer_456", profile.uid)
        assertEquals("ChampionPlayer", profile.displayName)
        assertEquals(AuthProvider.PLAY_GAMES, profile.provider)
    }

    @Test
    fun testPersistedProfile_retainsCareerStatistics() {
        fakePrefs.edit().putString("uid", "google_stats")
            .putString("display_name", "Pro Player")
            .putInt("games_played", 42).putInt("games_won", 30)
            .putInt("streak", 0).putInt("best_streak", 8).apply()
        val profile = repository.getPersistedUserSync()!!
        assertEquals(42, profile.gamesPlayed)
        assertEquals(30, profile.gamesWon)
        assertEquals(0, profile.currentStreak)
        assertEquals(8, profile.bestStreak)
    }

    @Test
    fun testSignOut_clearsPersistedSession() = runBlocking {
        val id = "cleanup_${System.nanoTime()}"
        repository.onGoogleSignInSuccess(id, "User", "user@example.com", customUsername = id)
        assertTrue(repository.authState.value is AuthState.Authenticated)
        repository.signOut()
        assertEquals(AuthState.Unauthenticated, repository.authState.value)
        assertFalse(fakePrefs.contains("auth_token"))
        assertNull(repository.getPersistedUserSync())
        val repo2 = AuthRepository(context = null, customPrefs = fakePrefs)
        assertEquals(AuthState.Unauthenticated, repo2.verifyPersistedSession())
        repo2.sessionManager.stopSessionWatcher()
    }

    @Test
    fun testFirstTimeGoogleUserRegistration_andCustomNickname() {
        val googleId = "google_user_999"
        assertFalse("New google user should not be registered yet", repository.isGoogleUserRegistered(googleId))

        // Set custom nickname and mark registered
        val customNickname = "Sherlock"
        repository.setSavedGoogleDisplayName(googleId, customNickname)
        repository.setGoogleUserRegistered(googleId, true)

        assertTrue(repository.isGoogleUserRegistered(googleId))
        assertEquals("Sherlock", repository.getSavedGoogleDisplayName(googleId))

        // Sign in should persist this custom nickname
        repository.onGoogleSignInSuccess(googleId, customNickname, "sherlock@example.com")
        val state = repository.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertEquals("Sherlock", (state as AuthState.Authenticated).user.displayName)
    }

    @Test
    fun testDeviceId_isGeneratedAndPersisted() {
        val id1 = repository.deviceId
        assertNotNull(id1)
        assertTrue(id1.startsWith("dev_"))

        // Should return the same deviceId on subsequent queries
        val id2 = repository.deviceId
        assertEquals(id1, id2)
    }

    @Test
    fun testCloudBackupAndRestore_survivesAppUninstallAndWipe() = runBlocking {
        val kvStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val binStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val fakeGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = kvStore[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                kvStore[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? {
                val id = "bin_${java.util.UUID.randomUUID()}"
                binStore[id] = jsonPayload
                return id
            }
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean {
                binStore[binId] = jsonPayload
                return true
            }
            override suspend fun getJsonBin(binId: String): String? = binStore[binId]
        }
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = fakeGateway
        try {
            val googleId = "test_user_gid_${System.currentTimeMillis()}"
            fakePrefs.edit().putString("avatar_base64", "fake_base64_avatar_string").apply()
            repository.onGoogleSignInSuccess(
                googleId = googleId,
                displayName = "Persistent Hero",
                email = "hero@example.com",
                customUsername = "super_hero"
            )
            // Simulate playing games and winning
            repository.recordGameFinished(didWin = true)
            repository.recordGameFinished(didWin = true)

            val currentProfile = (repository.authState.value as AuthState.Authenticated).user
            assertEquals("super_hero", currentProfile.username)
            assertEquals(2, currentProfile.gamesPlayed)
            assertEquals(2, currentProfile.gamesWon)

            // Wait for in-flight async backups from recordGameFinished to settle
            kotlinx.coroutines.delay(1000L)

        // Force synchronous backup
        val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = currentProfile.copy(avatarBase64 = "fake_base64_avatar_string"),
            settings = repository.getSettings(),
            matchHistory = repository.getMatchHistory()
        )
        val backupSuccess = repository.sessionManager.backupUserDataSync(googleId, backup)
        assertTrue("Cloud backup must succeed", backupSuccess)

        // Simulate APP UNINSTALL:
        // Wipes all SharedPreferences and creates a fresh repository on a new empty device!
        val freshEmptyPrefs = FakeSharedPreferences()
        val freshRepository = AuthRepository(
            context = null,
            customPrefs = freshEmptyPrefs
        )

        // Verify fresh state has no user
        assertFalse(freshRepository.isGoogleUserRegistered(googleId))
        assertNull(freshRepository.getSavedGoogleDisplayName(googleId))
        val emptyState = freshRepository.verifyPersistedSession()
        assertTrue(emptyState is AuthState.Unauthenticated)

        // Now simulate user logging in again after reinstall:
        val restored = freshRepository.restoreCloudUserData(googleId)
        assertTrue("Restore must succeed from cloud", restored)

        // Verify all data is restored!
        val restoredState = freshRepository.authState.value
        assertTrue(restoredState is AuthState.Authenticated)
        val restoredUser = (restoredState as AuthState.Authenticated).user
        assertEquals("super_hero", restoredUser.username)
        assertEquals("Persistent Hero", restoredUser.displayName)
        assertEquals(2, restoredUser.gamesPlayed)
        assertEquals(2, restoredUser.gamesWon)
        assertEquals("fake_base64_avatar_string", restoredUser.avatarBase64)
        assertTrue(freshRepository.isGoogleUserRegistered(googleId))
        assertEquals("Persistent Hero", freshRepository.getSavedGoogleDisplayName(googleId))
        assertEquals("super_hero", freshRepository.getSavedGoogleUsername(googleId))
        freshRepository.sessionManager.stopSessionWatcher()
        } finally {
            com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = null
        }
    }

    @Test
    fun testDualWinStreakTracking_activeAndBestStreak() = runBlocking {
        repository.onGoogleSignInSuccess(
            googleId = "streak_tester",
            displayName = "Streak Master",
            email = "streak@example.com",
            customUsername = "streak_master"
        )

        val initialUser = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(0, initialUser.currentStreak)
        assertEquals(0, initialUser.bestStreak)
        assertEquals(0, initialUser.activeStreak)

        // Win 1st match: streak = 1, best = 1
        repository.recordGameFinished(didWin = true)
        val s1 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(1, s1.currentStreak)
        assertEquals(1, s1.bestStreak)
        assertEquals(1, s1.activeStreak)

        // Win 2nd match: streak = 2, best = 2
        repository.recordGameFinished(didWin = true)
        val s2 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(2, s2.currentStreak)
        assertEquals(2, s2.bestStreak)

        // Win 3rd match: streak = 3, best = 3
        repository.recordGameFinished(didWin = true)
        val s3 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(3, s3.currentStreak)
        assertEquals(3, s3.bestStreak)

        // Loss on 4th match: active streak resets to 0, best streak remains 3!
        repository.recordGameFinished(didWin = false)
        val s4 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(0, s4.currentStreak)
        assertEquals(0, s4.activeStreak)
        assertEquals(3, s4.bestStreak)

        // Win again: active streak = 1, best streak remains 3!
        repository.recordGameFinished(didWin = true)
        val s5 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(1, s5.currentStreak)
        assertEquals(3, s5.bestStreak)

        // Win up to 4 consecutive wins: active streak = 4, best streak becomes 4!
        repository.recordGameFinished(didWin = true) // 2
        repository.recordGameFinished(didWin = true) // 3
        repository.recordGameFinished(didWin = true) // 4
        val s6 = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(4, s6.currentStreak)
        assertEquals(4, s6.bestStreak)
    }

    // ── Test Doubles & In-Memory SharedPreferences ──

    class FakeSharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)

        class FakeEditor(private val data: ConcurrentHashMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removed.forEach { data.remove(it) }
                temp.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }
}
