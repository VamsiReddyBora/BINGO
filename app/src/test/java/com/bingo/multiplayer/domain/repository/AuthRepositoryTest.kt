package com.bingo.multiplayer.domain.repository

import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.AuthState
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AuthRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: AuthRepository
    private val createdRepos = mutableListOf<AuthRepository>()
    private var previousGateway: com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway? = null
    private var previousWire: com.bingo.multiplayer.domain.network.EmqxWire? = null

    @Before
    fun setUp() {
        com.bingo.multiplayer.domain.network.PresenceManager.networkEnabled = false
        previousWire = com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride
        com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride = com.bingo.multiplayer.domain.network.EmqxWireFixture()
        previousGateway = com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway
        val memoryStore = ConcurrentHashMap<String, String>()
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = memoryStore[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                memoryStore[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? {
                val binId = "bin_" + java.util.UUID.randomUUID().toString()
                memoryStore[binId] = jsonPayload
                return binId
            }
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean {
                memoryStore[binId] = jsonPayload
                return true
            }
            override suspend fun getJsonBin(binId: String): String? = memoryStore[binId]
        }
        fakePrefs = FakeSharedPreferences()
        repository = createTestRepo(fakePrefs)
    }

    private fun createTestRepo(prefs: FakeSharedPreferences): AuthRepository {
        val repo = AuthRepository(
            context = null,
            customPrefs = prefs
        )
        createdRepos.add(repo)
        return repo
    }

    @org.junit.After
    fun tearDown() = runBlocking {
        for (repo in createdRepos) {
            repo.sessionManager.stopSessionWatcher()
            runCatching {
                val scope = repo.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as? kotlinx.coroutines.CoroutineScope
                scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelAndJoin()
            }
            runCatching {
                val sessionScope = repo.sessionManager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo.sessionManager) as? kotlinx.coroutines.CoroutineScope
                sessionScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelAndJoin()
            }
        }
        com.bingo.multiplayer.domain.network.PresenceManager.resetForTests()
        createdRepos.clear()
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = previousGateway
        com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride = previousWire
        com.bingo.multiplayer.domain.network.PresenceManager.networkEnabled = true
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

        val repo2 = createTestRepo(fakePrefs)
        val state = repo2.verifyPersistedSession() as AuthState.Authenticated
        assertEquals(profile, state.user)
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
        val repo2 = createTestRepo(fakePrefs)
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
        val freshRepository = createTestRepo(freshEmptyPrefs)

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

    @Test
    fun testSameAccountGoogleSignIn_preservesSavedBestStreak() = runBlocking {
        val googleId = "gid_streak_preservation_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Streak Champion",
            email = "champ@example.com",
            customUsername = "streak_champ"
        )
        // Accumulate streaks: win 3 matches
        repository.recordGameFinished(didWin = true)
        repository.recordGameFinished(didWin = true)
        repository.recordGameFinished(didWin = true)
        val profileBeforeSignOut = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(3, profileBeforeSignOut.currentStreak)
        assertEquals(3, profileBeforeSignOut.bestStreak)

        // Sign out
        repository.signOut()
        assertEquals(AuthState.Unauthenticated, repository.authState.value)

        // Sign in again with same Google account (without cloud data, using local fallback)
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Streak Champion",
            email = "champ@example.com",
            customUsername = "streak_champ"
        )
        val profileAfterReSignIn = (repository.authState.value as AuthState.Authenticated).user
        assertEquals("bestStreak must be preserved upon same-account Google sign-in", 3, profileAfterReSignIn.bestStreak)
    }

    @Test
    fun testSaveProfileAvatar_rejectsInvalidPhotoBytesAndCleansUp() = runBlocking {
        val googleId = "avatar_user_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Avatar User",
            email = "avatar@example.com",
            customUsername = "avatar_user"
        )
        // Corrupt / invalid image bytes (not a valid PNG/JPEG/WEBP)
        val corruptBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        var createdFile: java.io.File? = null
        AuthRepository.contentStreamProvider = { _ ->
            java.io.ByteArrayInputStream(corruptBytes)
        }
        AuthRepository.bitmapDecoderOverride = { path ->
            createdFile = java.io.File(path)
            assertTrue("Temporary file must exist during decoding attempt", createdFile!!.exists())
            // Corrupt bytes decode to null!
            null
        }
        try {
            val result = repository.saveProfileAvatar(null)
            assertFalse("saveProfileAvatar must return false for corrupted bitmap bytes", result)
            assertNotNull(createdFile)
            assertFalse("Temporary file must be deleted upon decode failure", createdFile!!.exists())
            val user = (repository.authState.value as AuthState.Authenticated).user
            assertTrue("Avatar path must remain null or unchanged on decode failure", user.avatarUrl.isNullOrBlank())
        } finally {
            AuthRepository.contentStreamProvider = null
            AuthRepository.bitmapDecoderOverride = null
        }
    }

    @Test
    fun testRestoreCloudUserData_rejectsMismatchedGoogleIdBackup() = runBlocking {
        val requestedGoogleId = "user_alpha"
        val foreignGoogleId = "user_beta"

        // Put foreign user's backup into cloud store under requestedGoogleId
        val foreignBackup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(
                uid = "google_$foreignGoogleId",
                username = "foreign_beta",
                displayName = "Foreign Beta",
                provider = AuthProvider.GOOGLE
            ),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        repository.sessionManager.backupUserDataSync(requestedGoogleId, foreignBackup)

        // Attempt restore on clean repository for requestedGoogleId
        val freshPrefs = FakeSharedPreferences()
        val freshRepo = createTestRepo(freshPrefs)
        val restored = freshRepo.restoreCloudUserData(requestedGoogleId)

        assertFalse("restoreCloudUserData must reject backup with mismatched UID", restored)
        val state = freshRepo.authState.value
        assertTrue("State must remain unauthenticated when backup is rejected", state is AuthState.Unauthenticated)
    }

    @Test
    fun testUpdateUsername_failsClosedWhenCloudClaimFails() = runBlocking {
        val googleId = "claim_user_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Claim Tester",
            email = "claim@example.com",
            customUsername = "original_name"
        )
        assertEquals("original_name", (repository.authState.value as AuthState.Authenticated).user.username)

        // Set up gateway to fail on claim
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = null
            override suspend fun setKeyValue(key: String, value: String): Boolean = false // Claim fails!
            override suspend fun createJsonBin(jsonPayload: String): String? = null
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = false
            override suspend fun getJsonBin(binId: String): String? = null
        }

        val result = repository.updateUsername("new_stolen_name")
        assertFalse("updateUsername must return false when cloud claim fails", result)
        val currentProfile = (repository.authState.value as AuthState.Authenticated).user
        assertEquals("Username must remain original when cloud claim fails", "original_name", currentProfile.username)
    }

    @Test
    fun testRecordMatch_persistsIntoUidScopedStorageAndPreservesAcrossRelogin() = runBlocking {
        val googleId = "match_storage_user_${System.currentTimeMillis()}"
        val targetUid = "google_$googleId"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Match Winner",
            email = "winner@example.com",
            customUsername = "match_winner"
        )
        val initialUser = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(0, initialUser.gamesPlayed)
        assertEquals(0, initialUser.gamesWon)
        assertEquals(0, initialUser.currentStreak)

        // Seed 1 existing match in UID-scoped storage
        val initialRecord = com.bingo.multiplayer.domain.model.MatchRecord(
            id = "seed_match_1",
            mode = "MULTIPLAYER_ONLINE",
            opponentName = "Opponent A",
            didWin = true,
            boardSize = 5,
            timestamp = System.currentTimeMillis() - 100000L
        )
        repository.persistMatchHistory(listOf(initialRecord), targetUid)
        assertEquals(1, repository.getMatchHistory(targetUid).size)

        // Record a stable match result
        val ok1 = repository.recordMatch(
            mode = "MULTIPLAYER_ONLINE",
            opponentName = "Opponent B",
            didWin = true,
            boardSize = 5,
            stableResultId = "stable_res_100",
            expectedAccountUid = targetUid
        )
        assertTrue("recordMatch stable result must succeed", ok1)

        // Verify history contains both records in UID-scoped storage
        val historyAfterStable = repository.getMatchHistory(targetUid)
        assertEquals(2, historyAfterStable.size)
        assertEquals("stable_res_100", historyAfterStable[0].id)
        assertEquals("seed_match_1", historyAfterStable[1].id)

        // Verify profile career and streak
        val profileAfterStable = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(1, profileAfterStable.gamesPlayed)
        assertEquals(1, profileAfterStable.gamesWon)
        assertEquals(1, profileAfterStable.currentStreak)

        // Duplicate result idempotency check
        val duplicateResult = repository.recordMatch(
            mode = "MULTIPLAYER_ONLINE",
            opponentName = "Opponent B",
            didWin = true,
            boardSize = 5,
            stableResultId = "stable_res_100",
            expectedAccountUid = targetUid
        )
        assertTrue("Duplicate result must return true idempotently", duplicateResult)
        assertEquals("Match history must not duplicate receipt", 2, repository.getMatchHistory(targetUid).size)
        assertEquals("Career counters must not increment on duplicate receipt", 1, (repository.authState.value as AuthState.Authenticated).user.gamesPlayed)

        // Record an ordinary non-stable match
        val ok2 = repository.recordMatch(
            mode = "MULTIPLAYER_ONLINE",
            opponentName = "Opponent C",
            didWin = true,
            boardSize = 5,
            expectedAccountUid = targetUid
        )
        assertTrue("Ordinary recordMatch must succeed", ok2)
        val historyAfterOrdinary = repository.getMatchHistory(targetUid)
        assertEquals(3, historyAfterOrdinary.size)

        val profileAfterOrdinary = (repository.authState.value as AuthState.Authenticated).user
        assertEquals(2, profileAfterOrdinary.gamesPlayed)
        assertEquals(2, profileAfterOrdinary.gamesWon)
        assertEquals(2, profileAfterOrdinary.currentStreak)

        // Sign out (which wipes global keys: match_history, KEY_GAMES_PLAYED, etc.)
        repository.signOut()
        assertTrue(repository.authState.value is AuthState.Unauthenticated)

        // Re-login with same Google account: UID-scoped counters and history MUST be restored!
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Match Winner",
            email = "winner@example.com",
            customUsername = "match_winner"
        )
        val reloginUser = (repository.authState.value as AuthState.Authenticated).user
        assertEquals("Games played must be preserved from UID-scoped storage across re-login", 2, reloginUser.gamesPlayed)
        assertEquals("Games won must be preserved from UID-scoped storage across re-login", 2, reloginUser.gamesWon)
        assertEquals("Current streak must be preserved from UID-scoped storage across re-login", 2, reloginUser.currentStreak)
        assertEquals("Best streak must be preserved from UID-scoped storage across re-login", 2, reloginUser.bestStreak)

        val reloginHistory = repository.getMatchHistory(targetUid)
        assertEquals("UID-scoped match history must be fully preserved across re-login", 3, reloginHistory.size)
    }

    @Test
    fun testAccountSwitch_generationFencePreventsStaleDataOverwrite() = runBlocking {
        val userA = "user_a_${System.currentTimeMillis()}"
        val userB = "user_b_${System.currentTimeMillis()}"

        repository.onGoogleSignInSuccess(
            googleId = userA,
            displayName = "User Alpha",
            email = "alpha@example.com",
            customUsername = "alpha_player"
        )
        assertEquals("alpha_player", (repository.authState.value as AuthState.Authenticated).user.username)

        // User B logs in (increments session generation)
        repository.onGoogleSignInSuccess(
            googleId = userB,
            displayName = "User Beta",
            email = "beta@example.com",
            customUsername = "beta_player"
        )
        assertEquals("beta_player", (repository.authState.value as AuthState.Authenticated).user.username)

        // An async restore from User A attempts to write settings/history with stale generation
        val staleBackup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(
                uid = "google_$userA",
                username = "alpha_player",
                displayName = "User Alpha Stale",
                provider = AuthProvider.GOOGLE
            ),
            settings = repository.getSettings().copy(darkTheme = false),
            matchHistory = listOf(
                com.bingo.multiplayer.domain.model.MatchRecord("stale_1", "ONLINE", "Opp", true, 5, 1000L)
            )
        )
        repository.sessionManager.backupUserDataSync(userA, staleBackup)

        // Attempt restore of User A while User B is active
        val restoredA = repository.restoreCloudUserData(userA)
        assertFalse("Stale restore of User A must be rejected when User B is active", restoredA)

        // Ensure active user is still Beta
        val activeUser = (repository.authState.value as AuthState.Authenticated).user
        assertEquals("Active user must remain User Beta", "beta_player", activeUser.username)
        assertEquals("google_$userB", activeUser.uid)
    }

    @Test
    fun testDelayedSettingsCallbackDoesNotMutateNewSession() = runBlocking {
        val userA = "settings_user_a"
        val userB = "settings_user_b"

        repository.onGoogleSignInSuccess(
            googleId = userA,
            displayName = "User Alpha",
            email = "alpha@example.com",
            customUsername = "alpha_user"
        )
        val genA = repository.sessionGeneration.get()
        repository.updateSettings(repository.getSettings().copy(preferredBoardSize = 3))
        assertEquals(3, repository.getSettings().preferredBoardSize)

        // Switch to User B (who has preferredBoardSize = 5)
        repository.onGoogleSignInSuccess(
            googleId = userB,
            displayName = "User Beta",
            email = "beta@example.com",
            customUsername = "beta_user"
        )
        val genB = repository.sessionGeneration.get()
        assertTrue(genB > genA)
        repository.updateSettings(repository.getSettings().copy(preferredBoardSize = 5))
        assertEquals(5, repository.getSettings().preferredBoardSize)

        // Delayed settings update from User A with stale generation
        val delayedSettings = repository.getSettings().copy(preferredBoardSize = 3)
        repository.updateSettings(delayedSettings, expectedGen = genA)
        assertEquals("User B's board size must remain 5 after delayed callback", 5, repository.getSettings().preferredBoardSize)
    }

    @Test
    fun testPersistMatchHistory_heldMonitorDuringAccountSwitch_doesNotMutateNewUser() = runBlocking {
        val userA = "history_user_a"
        val userB = "history_user_b"

        repository.onGoogleSignInSuccess(
            googleId = userA,
            displayName = "User A",
            email = "user_a@example.com",
            customUsername = "user_a"
        )
        val uidA = "google_$userA"
        val genA = repository.sessionGeneration.get()

        // User A gets match history callback prepared
        val matchA = com.bingo.multiplayer.domain.model.MatchRecord(
            id = "match_a_1",
            mode = "ONLINE",
            opponentName = "Opponent",
            didWin = true,
            boardSize = 5,
            timestamp = 1000L
        )

        // Switch to User B
        repository.onGoogleSignInSuccess(
            googleId = userB,
            displayName = "User B",
            email = "user_b@example.com",
            customUsername = "user_b"
        )
        val uidB = "google_$userB"
        val genB = repository.sessionGeneration.get()
        assertTrue(genB > genA)

        // Stale history callback from User A attempts to persist with uidA and genA
        repository.persistMatchHistory(listOf(matchA), uid = uidA, expectedGeneration = genA)

        // User B's history must remain completely empty!
        val historyB = repository.getMatchHistory(uidB)
        assertTrue("User B's history must remain empty", historyB.isEmpty())
        val globalHistory = repository.getMatchHistory()
        assertTrue("Global history must not contain User A's matches while User B is active", globalHistory.none { it.id == "match_a_1" })
    }

    @Test
    fun testNativeBackupFailsWhenDiscoveryPointersFail() = runBlocking {
        val googleId = "backup_ptr_fail_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Backup Ptr Tester",
            email = "bptr@example.com",
            customUsername = "bptr_user"
        )

        // Mock gateway where JSON bin creation succeeds, but pointer key writes fail
        val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = (repository.authState.value as AuthState.Authenticated).user,
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = null
            override suspend fun setKeyValue(key: String, value: String): Boolean = false // Pointer write fails!
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_created_ok"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }

        val success = repository.sessionManager.backupUserDataSync(googleId, backup)
        assertFalse("backupUserDataSync must fail when pointer writes fail", success)
    }

    @Test
    fun testFailedUsernameAliasReleaseIsRetriedDurably() = runBlocking {
        val googleId = "retry_alias_user_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Alias Retry",
            email = "alias_retry@example.com",
            customUsername = "first_name"
        )

        val testBackupJson = kotlinx.serialization.json.Json.encodeToString(
            com.bingo.multiplayer.domain.model.CloudUserDataBackup.serializer(),
            com.bingo.multiplayer.domain.model.CloudUserDataBackup(
                profile = com.bingo.multiplayer.domain.model.UserProfile(
                    uid = "google_$googleId",
                    displayName = "Alias Retry",
                    username = "first_name"
                )
            )
        )

        // Mock gateway: allow claims, but fail release
        var releaseAttempted = false
        var releaseShouldSucceed = false
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                if (key.startsWith("user_first_name") && value.isEmpty()) {
                    releaseAttempted = true
                    if (!releaseShouldSucceed) return false
                }
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_alias_retry"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = testBackupJson
        }

        // Populate initial alias in store
        store["user_first_name"] = "bin_alias_retry"

        // Rename from first_name to second_name: release of first_name will fail
        val renamed = repository.updateUsername("second_name")
        assertTrue(renamed)
        assertTrue(releaseAttempted)

        // Now allow release and call retryPendingAliasReleases()
        releaseShouldSucceed = true
        releaseAttempted = false
        repository.retryPendingAliasReleases()
        kotlinx.coroutines.delay(250L)
        assertTrue(releaseAttempted)
        assertEquals("", store["user_first_name"])
    }

    @Test
    fun testUpdateUsername_failsWhenAliasWriteFails() = runBlocking {
        val googleId = "alias_fail_user_${System.currentTimeMillis()}"
        repository.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Alias Tester",
            email = "alias@example.com",
            customUsername = "original_name"
        )

        // Mock gateway where reg_ write succeeds, but user_ alias write fails
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = if (key.startsWith("user_")) null else "bin_existing"
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                if (key.startsWith("user_")) return false
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }

        val result = repository.updateUsername("new_candidate_name")
        assertFalse("updateUsername must fail when ownership alias write fails", result)
        assertEquals("original_name", (repository.authState.value as AuthState.Authenticated).user.username)
    }

    @Test
    fun testReleaseUsernameAlias_onlyReleasesWhenOwnedBySameUid() = runBlocking {
        val user1 = "google_user_1"
        val user2 = "google_user_2"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val bins = java.util.concurrent.ConcurrentHashMap<String, String>()

        val backupUser1 = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = user1, username = "shared_name", displayName = "One", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        val backupJson1 = kotlinx.serialization.json.Json.encodeToString(backupUser1)
        bins["bin_u1"] = backupJson1
        store["user_shared_name"] = "bin_u1"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_${System.currentTimeMillis()}"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = bins[binId]
        }

        // User 2 attempts to release alias owned by User 1
        val releasedByUnauthorized = repository.sessionManager.releaseUsernameAlias("shared_name", user2)
        assertFalse("Unauthorized UID must not be allowed to release alias", releasedByUnauthorized)
        assertEquals("Alias must remain assigned to bin_u1", "bin_u1", store["user_shared_name"])

        // User 1 releases their own alias
        val releasedByOwner = repository.sessionManager.releaseUsernameAlias("shared_name", user1)
        assertTrue("Legitimate owner must be allowed to release their alias", releasedByOwner)
        assertEquals("Alias must be cleared", "", store["user_shared_name"])
    }

    @Test
    fun releaseUsernameAliasWithCompetingReplacementClaimDoesNotClearReplacementOwnership() = runBlocking {
        val user1 = "google_user_1"
        val user2 = "google_user_2"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val bins = java.util.concurrent.ConcurrentHashMap<String, String>()

        val backupUser1 = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = user1, username = "foo", displayName = "User One", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        val backupUser2 = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = user2, username = "foo", displayName = "User Two", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        bins["bin_u1"] = kotlinx.serialization.json.Json.encodeToString(backupUser1)
        bins["bin_u2"] = kotlinx.serialization.json.Json.encodeToString(backupUser2)
        store["user_foo"] = "bin_u1"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun setKeyValueConditional(key: String, expected: String?, value: String): Boolean {
                // Simulate race: User 2 claimed foo in between, so current is bin_u2, not expected bin_u1
                store[key] = "bin_u2"
                return false
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_${System.currentTimeMillis()}"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = bins[binId]
        }

        // User 1 releases alias "foo" after changing username
        val releaseResult = repository.sessionManager.releaseUsernameAlias("foo", user1)

        // Release must acknowledge (return true) because new owner differs from releasing UID
        assertTrue("Release must be acknowledged when competing claim has replaced alias", releaseResult)
        // CRITICAL: Replacement claim by User 2 must NOT be cleared with an empty string!
        assertEquals("Competing replacement account's claim must be preserved", "bin_u2", store["user_foo"])
    }

    @Test
    fun testCompetingUsernameClaimFailsClosedWhenConcurrentWriteWins() = runBlocking {
        repository.onGuestLogin("Concurrent Player", "orig_name")
        val clean = "contested_name"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun setKeyValueConditional(key: String, expected: String?, value: String): Boolean {
                // Simulate race: another client wrote to key before this write
                if (key == "user_$clean") {
                    store[key] = "competitor_bin_999"
                    return false
                }
                return setKeyValue(key, value)
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "my_bin_123"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }

        val result = repository.updateUsername(clean)
        assertFalse("updateUsername must fail when conditional claim fails due to race", result)
        assertEquals("orig_name", (repository.authState.value as AuthState.Authenticated).user.username)
        assertEquals("competitor_bin_999", store["user_$clean"])
    }

    @Test
    fun testRetryPendingAliasReleases_resumedOnRepositoryCreation() = runBlocking {
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val bins = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pendingUser = "google_pending_1"
        val backupUser = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = pendingUser, username = "old_alias", displayName = "Pending", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )
        bins["bin_pending"] = kotlinx.serialization.json.Json.encodeToString(backupUser)
        store["user_old_alias"] = "bin_pending"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_dummy"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = bins[binId]
        }

        // Put an unreleased alias in prefs
        fakePrefs.edit().putStringSet("pending_alias_releases", mutableSetOf("old_alias:$pendingUser")).commit()

        // Create a new AuthRepository instance: init must call retryPendingAliasReleases()
        val repo2 = createTestRepo(fakePrefs)
        // Wait for async release
        for (i in 0..20) {
            val pending = fakePrefs.getStringSet("pending_alias_releases", null)
            if (pending.isNullOrEmpty()) break
            kotlinx.coroutines.delay(50)
        }
        val remaining = fakePrefs.getStringSet("pending_alias_releases", null)
        assertTrue("Pending alias releases must be cleared on restart", remaining.isNullOrEmpty())
        assertEquals("Alias must be cleared in cloud store", "", store["user_old_alias"])
    }

    @Test
    fun testAvatarDecodingBoundsWideAndTallBitmaps() = runBlocking {
        repository.onGuestLogin("Avatar Test", "avatar_user")

        // 1. Production sampling bounds calculations
        // Wide image: 800 x 200
        val wideSample = AuthRepository.computeSampleSize(800, 200, 160)
        assertEquals("Sample size for 800x200 must be 4", 4, wideSample)
        val (wideTargetW, wideTargetH) = AuthRepository.computeTargetDimensions(800, 200, 160)
        assertEquals("Target width for 800x200 must be scaled to 160", 160, wideTargetW)
        assertEquals("Target height for 800x200 must be scaled to 40", 40, wideTargetH)

        // Tall image: 200 x 800
        val tallSample = AuthRepository.computeSampleSize(200, 800, 160)
        assertEquals("Sample size for 200x800 must be 4", 4, tallSample)
        val (tallTargetW, tallTargetH) = AuthRepository.computeTargetDimensions(200, 800, 160)
        assertEquals("Target width for 200x800 must be scaled to 40", 40, tallTargetW)
        assertEquals("Target height for 200x800 must be scaled to 160", 160, tallTargetH)

        // Square image: 640 x 640
        val squareSample = AuthRepository.computeSampleSize(640, 640, 160)
        assertEquals("Sample size for 640x640 must be 2", 2, squareSample)
        val (sqTargetW, sqTargetH) = AuthRepository.computeTargetDimensions(640, 640, 160)
        assertEquals(160, sqTargetW)
        assertEquals(160, sqTargetH)

        // Small image below bounds: 120 x 80
        val smallSample = AuthRepository.computeSampleSize(120, 80, 160)
        assertEquals("Sample size for small image must be 1", 1, smallSample)
        val (smallTargetW, smallTargetH) = AuthRepository.computeTargetDimensions(120, 80, 160)
        assertEquals(120, smallTargetW)
        assertEquals(80, smallTargetH)

        // 2. Production saveProfileAvatar flow with wide bitmap
        var interceptedScalerW = 0
        var interceptedScalerH = 0
        val ctor = android.graphics.Bitmap::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        val dummyBmp = ctor.newInstance()

        AuthRepository.contentStreamProvider = { java.io.ByteArrayInputStream("fake_image_bytes".toByteArray()) }
        AuthRepository.bitmapDecoderOverride = { dummyBmp }
        AuthRepository.bitmapDimensionsOverride = { Pair(800, 200) }
        AuthRepository.bitmapScalerOverride = { bmp, w, h ->
            interceptedScalerW = w
            interceptedScalerH = h
            bmp
        }

        try {
            val wideOk = repository.saveProfileAvatar(android.net.TestUri("content://media/wide.jpg"))
            assertTrue("saveProfileAvatar must succeed for wide bitmap", wideOk)
            assertEquals("Scaler must receive 160 for wide width", 160, interceptedScalerW)
            assertEquals("Scaler must receive 40 for wide height", 40, interceptedScalerH)

            val userAfterWide = (repository.authState.value as AuthState.Authenticated).user
            assertNotNull("avatarUrl must be populated", userAfterWide.avatarUrl)
            assertNotNull("Avatar must be cached by path",
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.get(userAfterWide.avatarUrl!!))

            // 3. Production saveProfileAvatar flow with tall bitmap
            interceptedScalerW = 0
            interceptedScalerH = 0
            AuthRepository.bitmapDimensionsOverride = { Pair(200, 800) }
            val tallOk = repository.saveProfileAvatar(android.net.TestUri("content://media/tall.jpg"))
            assertTrue("saveProfileAvatar must succeed for tall bitmap", tallOk)
            assertEquals("Scaler must receive 40 for tall width", 40, interceptedScalerW)
            assertEquals("Scaler must receive 160 for tall height", 160, interceptedScalerH)

            val userAfterTall = (repository.authState.value as AuthState.Authenticated).user
            assertNotNull("avatarUrl must be populated", userAfterTall.avatarUrl)

            // 4. Test failure paths
            AuthRepository.contentStreamProvider = null
            assertFalse("Null uri must fail cleanly", repository.saveProfileAvatar(null))

            AuthRepository.contentStreamProvider = { null }
            assertFalse("Null stream must fail cleanly", repository.saveProfileAvatar(android.net.TestUri("content://media/missing.jpg")))

            AuthRepository.contentStreamProvider = { java.io.ByteArrayInputStream(ByteArray(10)) }
            AuthRepository.bitmapDecoderOverride = { null }
            assertFalse("Corrupted/unsupported image decoding must fail cleanly", repository.saveProfileAvatar(android.net.TestUri("content://media/corrupt.jpg")))
        } finally {
            AuthRepository.contentStreamProvider = null
            AuthRepository.bitmapDecoderOverride = null
            AuthRepository.bitmapDimensionsOverride = null
            AuthRepository.bitmapScalerOverride = null
        }
    }

    @Test
    fun testBackupUserDataSyncFailsClosedWhenExistingBinReadFails() = runBlocking {
        val user = "google_victim_bin"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val bins = java.util.concurrent.ConcurrentHashMap<String, String>()
        store["gid_$user"] = "existing_bin_123"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "new_bin_456"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean {
                bins[binId] = jsonPayload
                return true
            }
            override suspend fun getJsonBin(binId: String): String? = null // Simulated HTTP 503 outage
        }

        val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = user, username = "victim", displayName = "Victim", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )

        val result = repository.sessionManager.backupUserDataSync(user, backup)
        assertFalse("backupUserDataSync must fail closed when existing bin read fails (503)", result)
        assertNull("Existing bin must not be overwritten without owner confirmation", bins["existing_bin_123"])
    }

    @Test
    fun testBackupUserDataSyncFailsClosedWhenOccupiedAliasOwnerReadFails() = runBlocking {
        val attacker = "google_attacker"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val bins = java.util.concurrent.ConcurrentHashMap<String, String>()
        store["user_target_alias"] = "existing_victim_bin"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "attacker_bin"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean {
                bins[binId] = jsonPayload
                return true
            }
            override suspend fun getJsonBin(binId: String): String? {
                if (binId == "existing_victim_bin") return null // 503 outage on existing owner read
                return bins[binId]
            }
        }

        val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = com.bingo.multiplayer.domain.model.UserProfile(uid = attacker, username = "target_alias", displayName = "Attacker", provider = AuthProvider.GOOGLE),
            settings = repository.getSettings(),
            matchHistory = emptyList()
        )

        val result = repository.sessionManager.backupUserDataSync(attacker, backup)
        assertFalse("backupUserDataSync must fail closed when alias owner read fails", result)
        assertEquals("Alias must not be stolen during owner read outage", "existing_victim_bin", store["user_target_alias"])
    }

    @Test
    fun testReleaseUsernameAliasReturnsFalseOnHttp503AndRetainsPendingRelease() = runBlocking {
        val user = "google_user_retry"
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        store["user_alias_503"] = "bin_503"

        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = object : com.bingo.multiplayer.domain.network.AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = store[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = null
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = false
            override suspend fun getJsonBin(binId: String): String? = null // Simulated HTTP 503 outage
        }

        val released = repository.sessionManager.releaseUsernameAlias("alias_503", user)
        assertFalse("releaseUsernameAlias must return false on 503 read failure", released)
        assertEquals("Alias mapping must remain untouched during 503", "bin_503", store["user_alias_503"])
    }

    @Test
    fun testGoogleProfilePublicationsPreservePublicKeyAcrossRestartRenameAvatarAndCareer() = runBlocking {
        val googleId = "gid_auth_preservation_99"
        repository.onGoogleSignInSuccess(googleId, "Kevin QA", "kevin@example.com", customUsername = "kevin_pres", idToken = "test_token_$googleId")
        repository.sessionManager.drainBackgroundJobs()

        repository.sessionManager.googleProfileCache.clear()
        val entry1 = repository.sessionManager.lookupGoogleProfileSync(googleId)
        assertNotNull("Initial Google login must publish non-null publicKey", entry1?.publicKey)
        val expectedKey = entry1!!.publicKey

        // 1. Rename username
        assertTrue("updateUsername must succeed", repository.updateUsername("kevin_renamed"))
        repository.sessionManager.drainBackgroundJobs()
        repository.sessionManager.googleProfileCache.clear()
        val entry2 = repository.sessionManager.lookupGoogleProfileSync(googleId)
        assertNotNull("publicKey must not be erased after updateUsername", entry2?.publicKey)
        assertEquals("publicKey must match original key after updateUsername", expectedKey, entry2?.publicKey)
        assertEquals("username must match updated username", "kevin_renamed", entry2?.username)

        // 2. Avatar remove
        assertTrue("removeProfileAvatar must succeed", repository.removeProfileAvatar())
        repository.sessionManager.drainBackgroundJobs()
        repository.sessionManager.googleProfileCache.clear()
        val entry3 = repository.sessionManager.lookupGoogleProfileSync(googleId)
        assertNotNull("publicKey must not be erased after removeProfileAvatar", entry3?.publicKey)
        assertEquals("publicKey must match original key after removeProfileAvatar", expectedKey, entry3?.publicKey)
        assertNull("avatarUrl must be null after removal", entry3?.avatarUrl)

        // 3. Career match result
        repository.recordGameFinished(didWin = true)
        repository.sessionManager.drainBackgroundJobs()
        repository.sessionManager.googleProfileCache.clear()
        val entry4 = repository.sessionManager.lookupGoogleProfileSync(googleId)
        assertNotNull("publicKey must not be erased after recordGameFinished", entry4?.publicKey)
        assertEquals("publicKey must match original key after recordGameFinished", expectedKey, entry4?.publicKey)
        assertTrue("gamesPlayed must increment", (entry4?.gamesPlayed ?: 0) >= 1)

        // 4. Session restore on startup via separate repository instance
        repository.sessionManager.googleProfileCache.clear()
        val repoRestart = createTestRepo(fakePrefs)
        val verifiedState = repoRestart.verifyPersistedSession()
        assertTrue("verifyPersistedSession must succeed", verifiedState is AuthState.Authenticated)
        repoRestart.sessionManager.drainBackgroundJobs()
        repoRestart.sessionManager.googleProfileCache.clear()
        val entry5 = repoRestart.sessionManager.lookupGoogleProfileSync(googleId)
        assertNotNull("publicKey must not be erased after verifyPersistedSession", entry5?.publicKey)
        assertEquals("publicKey must match original key after verifyPersistedSession", expectedKey, entry5?.publicKey)
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
