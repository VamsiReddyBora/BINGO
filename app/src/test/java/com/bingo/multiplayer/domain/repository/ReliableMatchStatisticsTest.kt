package com.bingo.multiplayer.domain.repository

import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.AuthState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ReliableMatchStatisticsTest {

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
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }
    }

    private fun createTestRepo(prefs: SharedPreferences): AuthRepository {
        val repo = AuthRepository(customPrefs = prefs)
        createdRepos.add(repo)
        return repo
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
        com.bingo.multiplayer.domain.network.PresenceManager.resetForTests()
        createdRepos.clear()
        com.bingo.multiplayer.domain.network.AccountSessionManager.customCloudGateway = previousGateway
        com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride = previousWire
        com.bingo.multiplayer.domain.network.PresenceManager.networkEnabled = true
    }

    @Test fun resultHistoryAndCareerAreAtomicAndIdempotentAcrossRepositoryRestart() {
        val backing = AuthRepositoryTest.FakeSharedPreferences()
        backing.edit().putString("uid","google_statistics").putString("display_name","Kevin").putString("username","statistics_test").commit()
        var fail = false
        val prefs = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val delegate = backing.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit" && fail) {
                        // Model Android's memory update despite a failed disk commit.
                        method.invoke(delegate, *(args ?: emptyArray()))
                        false
                    }
                    else {
                        val result = method.invoke(delegate, *(args ?: emptyArray()))
                        if (method.returnType == SharedPreferences.Editor::class.java) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        val repository = createTestRepo(prefs)
        assertFalse(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="wrong-account",expectedAccountUid="another-account"))
        assertEquals(0,backing.getInt("games_played",0))
        fail = true
        assertFalse(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(0,backing.getInt("games_played",0)); assertTrue(repository.getMatchHistory().isEmpty())
        fail = false
        assertTrue(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(1,backing.getInt("games_played",0)); assertEquals(1,repository.getMatchHistory().size)
        // Verify R03: UID-scoped match history and career counters are also atomically persisted:
        assertEquals(1, backing.getInt("google_statistics_games_played", 0))
        assertEquals(1, backing.getInt("google_statistics_games_won", 0))
        assertEquals(1, repository.getMatchHistory("google_statistics").size)

        val restarted = createTestRepo(prefs)
        assertTrue(restarted.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(1,backing.getInt("games_played",0)); assertEquals(1,restarted.getMatchHistory().size)
        assertEquals(1,(restarted.authState.value as AuthState.Authenticated).user.gamesWon)
        assertEquals(1, restarted.getMatchHistory("google_statistics").size)
    }

    @Test fun testScopedXpAndLevelSurviveAccountSwitchAndRelogin() {
        val backing = AuthRepositoryTest.FakeSharedPreferences()
        val repo = createTestRepo(backing)

        // 1. User A logs in and records match with XP and level
        repo.onGoogleSignInSuccess(
            googleId = "user_A_xp",
            displayName = "User A XP",
            email = "user_a_xp@example.com",
            customUsername = "user_a_xp"
        )
        assertTrue(repo.recordMatch("Match", "Opp", true, 5, stableResultId = "m1"))
        val userAProfile = (repo.authState.value as AuthState.Authenticated).user
        assertTrue("User A should have gained XP", userAProfile.xp > 0)
        val userAXp = userAProfile.xp
        val userALevel = userAProfile.level

        // 2. Switch to User B (brand new)
        repo.onGoogleSignInSuccess(
            googleId = "user_B_clean",
            displayName = "User B Clean",
            email = "user_b_clean@example.com",
            customUsername = "user_b_clean"
        )
        val userBProfile = (repo.authState.value as AuthState.Authenticated).user
        assertEquals("User B must start at level 1 with 0 XP", 0, userBProfile.xp)
        assertEquals(1, userBProfile.level)

        // 3. User A logs back in: scoped XP and level must be preserved
        repo.onGoogleSignInSuccess(
            googleId = "user_A_xp",
            displayName = "User A XP",
            email = "user_a_xp@example.com",
            customUsername = "user_a_xp"
        )
        val restoredUserA = (repo.authState.value as AuthState.Authenticated).user
        assertEquals("User A XP must survive account switch and relogin", userAXp, restoredUserA.xp)
        assertEquals("User A level must survive account switch and relogin", userALevel, restoredUserA.level)
    }
}
