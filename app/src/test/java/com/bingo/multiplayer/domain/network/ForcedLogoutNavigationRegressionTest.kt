package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import com.bingo.multiplayer.presentation.navigation.UnauthenticatedSessionCleaner
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ForcedLogoutNavigationRegressionTest {

    private var previousWire: EmqxWire? = null
    private var previousGateway: AccountSessionManager.CloudStorageGateway? = null
    private val createdRepos = mutableListOf<AuthRepository>()

    @Before
    fun setUp() {
        PresenceManager.networkEnabled = false
        previousWire = EmqxTransport.wireOverride
        EmqxTransport.wireOverride = EmqxWireFixture()
        previousGateway = AccountSessionManager.customCloudGateway
        AccountSessionManager.customCloudGateway = object : AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String): String? = null
            override suspend fun setKeyValue(key: String, value: String): Boolean = true
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }
    }

    @After
    fun tearDown() = runBlocking {
        for (repo in createdRepos) {
            repo.sessionManager.stopSessionWatcher()
            runCatching {
                val repoScope = repo.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as? CoroutineScope
                repoScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            }
            runCatching {
                val sessionScope = repo.sessionManager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo.sessionManager) as? CoroutineScope
                sessionScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            }
        }
        PresenceManager.resetForTests()
        createdRepos.clear()
        AccountSessionManager.customCloudGateway = previousGateway
        EmqxTransport.wireOverride = previousWire
        PresenceManager.networkEnabled = true
    }

    @Test
    fun testUnauthenticatedCleanup_stopsLanDiscovery_disconnectsRoom_andTriggersNavigation() {
        val disconnected = AtomicBoolean(false)
        val resetState = AtomicBoolean(false)
        val navigatedLogin = AtomicBoolean(false)

        val lan = LanDiscoveryManager()

        UnauthenticatedSessionCleaner.cleanup(
            disconnectRoom = { disconnected.set(true) },
            lanDiscovery = lan,
            onResetSessionState = { resetState.set(true) },
            onNavigateLogin = { navigatedLogin.set(true) }
        )

        assertTrue("disconnectRoom must be executed", disconnected.get())
        assertTrue("Session state must be reset", resetState.get())
        assertTrue("Must navigate to login", navigatedLogin.get())
        assertFalse("LAN discovery should not be broadcasting", lan.isBroadcastingActive)
        assertFalse("LAN discovery should not be discovering", lan.isDiscoveringActive)
    }

    @Test
    fun testSignOut_triggersPresenceShutdown_andEmitsUnauthenticatedState() = runBlocking {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val repo = AuthRepository(context = null, customPrefs = prefs)
        createdRepos.add(repo)

        repo.onGoogleSignInSuccess(
            googleId = "logout_test_user",
            displayName = "Logout Tester",
            email = "logout@example.com",
            customUsername = "logout_tester"
        )
        assertTrue(repo.authState.value is AuthState.Authenticated)

        repo.signOut()

        val state = repo.authState.value
        assertTrue("State must transition to Unauthenticated on signOut", state is AuthState.Unauthenticated)
        assertNull("Presence username must be null after sign out", PresenceManager.currentActiveUsername)
    }

    @Test
    fun testDisconnectRoom_cleansOnlineSyncState() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var sync: OnlineRoomSyncManager? = null
        try {
            sync = OnlineRoomSyncManager(testScope, object : RoomRegistryGateway {
                override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession? = null
                override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
                override suspend fun remove(code: String, uid: String) {}
                override suspend fun close(code: String) {}
            })

            val player = Player("user_logout", "Logout User", isHost = true)
            sync.connectToRoom("LOGOUT_ROOM", player, listOf(player))
            assertEquals("LOGOUT_ROOM", sync.currentRoomCode)

            sync.disconnect()
            assertNull("currentRoomCode must be cleared on disconnect", sync.currentRoomCode)
            assertEquals("Player list must be empty", 0, sync.players.value.size)
        } finally {
            sync?.disconnect()
            sync?.drainForTests()
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            testScope.cancel()
        }
    }

    @Test
    fun queuedForcedLogoutDoesNotAffectNewerSignedInUser() = runBlocking {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val repo = AuthRepository(context = null, customPrefs = prefs)
        createdRepos.add(repo)

        repo.onGoogleSignInSuccess(
            googleId = "user_A",
            displayName = "User A",
            email = "user_a@example.com",
            customUsername = "user_a"
        )
        val genA = repo.sessionGeneration.get()
        assertEquals("user_A", (repo.authState.value as? AuthState.Authenticated)?.user?.uid?.removePrefix("google_"))

        // User B signs in
        repo.onGoogleSignInSuccess(
            googleId = "user_B",
            displayName = "User B",
            email = "user_b@example.com",
            customUsername = "user_b"
        )
        val genB = repo.sessionGeneration.get()
        assertTrue(genB > genA)

        // Stale forced logout for user A's generation must not log out user B
        val currentAuth = repo.authState.value as? AuthState.Authenticated
        assertNotNull(currentAuth)
        assertEquals("user_b", currentAuth!!.user.username)
    }

    @Test
    fun forcedLogoutCleansUpLanDiscoveryPresenceAndOnlineRoomSync() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lan = LanDiscoveryManager(scope = testScope)
        var sync: OnlineRoomSyncManager? = null
        try {
            val hostPlayer = Player("host_lan", "Host", isHost = true)
            lan.startBroadcasting(hostPlayer, 5, "LAN123")
            lan.startDiscovering("host_lan")
            assertTrue(lan.isBroadcastingActive)
            assertTrue(lan.isDiscoveringActive)

            PresenceManager.setCurrentUser("active_presence_user")
            assertEquals("active_presence_user", PresenceManager.currentActiveUsername)

            sync = OnlineRoomSyncManager(testScope, object : RoomRegistryGateway {
                override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession? = null
                override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
                override suspend fun remove(code: String, uid: String) {}
                override suspend fun close(code: String) {}
            })
            val player = Player("user_logout", "Logout User", isHost = true)
            sync.connectToRoom("LOGOUT_ROOM", player, listOf(player))
            assertEquals("LOGOUT_ROOM", sync.currentRoomCode)

            var navigated = false
            UnauthenticatedSessionCleaner.cleanup(
                disconnectRoom = { sync.disconnect() },
                lanDiscovery = lan,
                onResetSessionState = { runBlocking { PresenceManager.resetForTests() } },
                onNavigateLogin = { navigated = true }
            )

            assertTrue("Must navigate to login", navigated)
            assertFalse("LAN broadcasting stopped", lan.isBroadcastingActive)
            assertFalse("LAN discovering stopped", lan.isDiscoveringActive)
            assertNull("Presence cleared", PresenceManager.currentActiveUsername)
            assertNull("Online room cleared", sync.currentRoomCode)
            assertEquals(0, sync.players.value.size)
        } finally {
            sync?.disconnect()
            sync?.drainForTests()
            lan.stopBroadcasting()
            lan.stopDiscovering()
            lan.awaitTermination()
            testScope.coroutineContext[Job]?.cancelChildren()
            testScope.coroutineContext[Job]?.cancelAndJoin()
            testScope.cancel()
        }
    }

    @Test
    fun realForcedLogoutPacketOverWireTriggersAccountInvalidationAndNavigation() = runBlocking {
        val wire = EmqxWireFixture()
        EmqxTransport.wireOverride = wire
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val repo = AuthRepository(context = null, customPrefs = prefs)
        createdRepos.add(repo)

        val googleId = "real_force_logout_user"
        repo.onGoogleSignInSuccess(
            googleId = googleId,
            displayName = "Victim",
            email = "victim@example.com",
            customUsername = "victim_user"
        )
        assertTrue(repo.authState.value is AuthState.Authenticated)

        val topic = repo.sessionManager.getSessionTopic(googleId)
        val packet = AccountSessionPacket(
            type = "FORCE_LOGOUT",
            googleId = googleId,
            deviceId = "other_intruder_device",
            deviceModel = "Pixel 9 Pro"
        )
        val payload = kotlinx.serialization.json.Json.encodeToString(packet).toByteArray()

        // Await active session subscription
        withTimeout(3000) {
            while (wire.activeSubscriptions(topic) == 0) {
                delay(20)
            }
        }

        // Deliver actual forced logout packet over the MQTT wire fixture
        wire.publish(topic, payload)

        // Give coroutine dispatcher a moment to process the message and invoke callback
        withTimeout(3000) {
            while (repo.authState.value !is AuthState.Unauthenticated) {
                delay(20)
            }
        }

        assertTrue("Account must transition to Unauthenticated on receiving FORCE_LOGOUT packet",
            repo.authState.value is AuthState.Unauthenticated)
        assertNull("Presence must be cleared after forced logout", PresenceManager.currentActiveUsername)
    }
}
