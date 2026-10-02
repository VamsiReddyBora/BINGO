package com.bingo.multiplayer.domain.repository

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class AuthRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockAuthProvider: MockFirebaseAuthProvider
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockAuthProvider = MockFirebaseAuthProvider()
        repository = AuthRepository(
            context = null,
            firebaseAuthProvider = mockAuthProvider,
            customPrefs = fakePrefs
        )
    }

    @Test
    fun testUnauthenticated_onLaunchWithNoPersistedUser() = runBlocking {
        val state = repository.verifyPersistedSession()
        assertTrue("State should be Unauthenticated", state is AuthState.Unauthenticated)
    }

    @Test
    fun testLoginWithFirebase_googleSuccess_persistsProfileAndToken() = runBlocking {
        mockAuthProvider.customGoogleResult = FirebaseUserResult(
            uid = "google_user_123",
            displayName = "Alice Wonder",
            email = "alice@example.com",
            token = "jwt_token_google_abc",
            provider = AuthProvider.GOOGLE
        )

        val result = repository.loginWithFirebase(AuthProvider.GOOGLE, "id_token_xyz")
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("google_user_123", profile.uid)
        assertEquals("Alice Wonder", profile.displayName)
        assertEquals("alice@example.com", profile.email)
        assertEquals(AuthProvider.GOOGLE, profile.provider)

        // Verify state is Authenticated
        val state = repository.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertEquals(profile, (state as AuthState.Authenticated).user)

        // Verify token was persisted
        assertEquals("jwt_token_google_abc", repository.getAuthToken())

        // Verify session persistence across repo instances
        val repo2 = AuthRepository(context = null, firebaseAuthProvider = mockAuthProvider, customPrefs = fakePrefs)
        val persistedState = repo2.verifyPersistedSession()
        assertTrue(persistedState is AuthState.Authenticated)
        assertEquals("google_user_123", (persistedState as AuthState.Authenticated).user.uid)
    }

    @Test
    fun testLoginWithFirebase_playGamesSuccess() = runBlocking {
        mockAuthProvider.customPlayGamesResult = FirebaseUserResult(
            uid = "pg_gamer_456",
            displayName = "ChampionPlayer",
            email = null,
            token = "pg_token_secret",
            provider = AuthProvider.PLAY_GAMES
        )

        val result = repository.loginWithFirebase(AuthProvider.PLAY_GAMES, "pg_auth_code")
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("pg_gamer_456", profile.uid)
        assertEquals("ChampionPlayer", profile.displayName)
        assertEquals(AuthProvider.PLAY_GAMES, profile.provider)
        assertEquals("pg_token_secret", repository.getAuthToken())
    }

    @Test
    fun testTokenRefresh_success() = runBlocking {
        // First log in
        mockAuthProvider.customGoogleResult = FirebaseUserResult(
            uid = "u1",
            displayName = "User",
            token = "old_token",
            provider = AuthProvider.GOOGLE
        )
        repository.loginWithFirebase(AuthProvider.GOOGLE, "token")

        mockAuthProvider.refreshSuccessToken = "new_shiny_token"
        val refreshResult = repository.refreshToken()

        assertTrue(refreshResult.isSuccess)
        assertEquals("new_shiny_token", refreshResult.getOrThrow())
        assertEquals("new_shiny_token", repository.getAuthToken())
    }

    @Test
    fun testTokenRefresh_expired_signsOutUser() = runBlocking {
        mockAuthProvider.customGoogleResult = FirebaseUserResult(
            uid = "u1",
            displayName = "User",
            token = "old_token",
            provider = AuthProvider.GOOGLE
        )
        repository.loginWithFirebase(AuthProvider.GOOGLE, "token")

        // Refresh returns null (session expired / revoked)
        mockAuthProvider.refreshSuccessToken = null
        val refreshResult = repository.refreshToken()

        assertTrue(refreshResult.isFailure)
        assertNull(repository.getAuthToken())
        assertTrue(repository.authState.value is AuthState.Unauthenticated)
    }

    @Test
    fun testFetchUserProfile_flow() = runBlocking {
        mockAuthProvider.customUserProfile = UserProfile(
            uid = "u100",
            displayName = "Fetched Pro Player",
            provider = AuthProvider.GOOGLE,
            gamesPlayed = 42,
            gamesWon = 30
        )

        val result = repository.fetchUserProfile("u100")
        assertTrue(result.isSuccess)
        val fetched = result.getOrThrow()

        assertEquals("Fetched Pro Player", fetched.displayName)
        assertEquals(42, fetched.gamesPlayed)
        assertEquals(30, fetched.gamesWon)
        assertEquals(fetched, (repository.authState.value as AuthState.Authenticated).user)
    }

    @Test
    fun testSignOut_clearsEverything() = runBlocking {
        mockAuthProvider.customGoogleResult = FirebaseUserResult(
            uid = "u1",
            displayName = "User",
            token = "token123",
            provider = AuthProvider.GOOGLE
        )
        repository.loginWithFirebase(AuthProvider.GOOGLE, "tok")
        assertTrue(repository.authState.value is AuthState.Authenticated)

        repository.signOut()
        assertEquals(AuthState.Unauthenticated, repository.authState.value)
        assertNull(repository.getAuthToken())

        val repo2 = AuthRepository(context = null, firebaseAuthProvider = mockAuthProvider, customPrefs = fakePrefs)
        assertEquals(AuthState.Unauthenticated, repo2.verifyPersistedSession())
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
        kotlinx.coroutines.delay(3000L)

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
            firebaseAuthProvider = mockAuthProvider,
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

    class MockFirebaseAuthProvider : FirebaseAuthProvider {
        var customGoogleResult: FirebaseUserResult? = null
        var customPlayGamesResult: FirebaseUserResult? = null
        var customUserProfile: UserProfile? = null
        var refreshSuccessToken: String? = "refreshed_mock_token"

        override suspend fun signInWithGoogle(idToken: String): FirebaseUserResult {
            return customGoogleResult ?: FirebaseUserResult("g_$idToken", "Google User", token = "tok_g")
        }

        override suspend fun signInWithPlayGames(authCode: String): FirebaseUserResult {
            return customPlayGamesResult ?: FirebaseUserResult("pg_$authCode", "PG User", token = "tok_pg", provider = AuthProvider.PLAY_GAMES)
        }

        override suspend fun signInAnonymously(): FirebaseUserResult {
            return FirebaseUserResult("guest_1", "Guest", token = "tok_anon", provider = AuthProvider.GUEST)
        }

        override suspend fun refreshToken(currentToken: String?): String? {
            return refreshSuccessToken
        }

        override suspend fun fetchUserProfile(uid: String): UserProfile? {
            return customUserProfile ?: UserProfile(uid = uid, displayName = "Mock User")
        }
    }

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

