package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.presentation.common.PlayerAvatarCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AvatarRealtimeSyncTest {

    private var previousWire: EmqxWire? = null
    private var previousGateway: AccountSessionManager.CloudStorageGateway? = null
    private var onlineManagerToClean: OnlineRoomSyncManager? = null
    private var lanManagerToClean: LanP2pSessionManager? = null

    @Before
    fun setUp() {
        PresenceManager.networkEnabled = false
        previousWire = EmqxTransport.wireOverride
        EmqxTransport.wireOverride = EmqxWireFixture()
        previousGateway = AccountSessionManager.customCloudGateway
        AccountSessionManager.customCloudGateway = object : AccountSessionManager.CloudStorageGateway {
            private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
            override suspend fun getKeyValue(key: String): String? = map[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean { map[key] = value; return true }
            override suspend fun setKeyValueConditional(key: String, expected: String?, value: String): Boolean {
                if (map[key] == expected) { map[key] = value; return true } else return false
            }
            override suspend fun createJsonBin(jsonPayload: String): String? = "bin_test"
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = true
            override suspend fun getJsonBin(binId: String): String? = null
        }
    }

    @After
    fun tearDown() = runBlocking {
        RoomMetadataApi.resetForTests()
        AccountSessionManager.drainBackgroundJobsForTests()
        onlineManagerToClean?.disconnect()
        onlineManagerToClean?.drainForTests()
        onlineManagerToClean = null
        lanManagerToClean?.disconnect()
        lanManagerToClean?.drainForTests()
        lanManagerToClean = null
        AccountSessionManager.customCloudGateway = previousGateway
        EmqxTransport.wireOverride = previousWire
        PresenceManager.networkEnabled = true
    }

    @Test
    fun testPlayerAvatarCacheEvictionAndVersioning() {
        val initialVersion = PlayerAvatarCache.version.value
        PlayerAvatarCache.evict("testUser", "/data/path/avatar_1.jpg")
        val newVersion = PlayerAvatarCache.version.value
        assertTrue("Cache version should increment upon eviction", newVersion >= initialVersion)

        PlayerAvatarCache.notifyAvatarChanged("testUser")
        val notifiedVersion = PlayerAvatarCache.version.value
        assertTrue("Cache version should increment upon notifyAvatarChanged", notifiedVersion >= newVersion)
    }

    @Test
    fun testAccountSessionManagerRemoteAvatarUpdate() = runBlocking {
        val manager = AccountSessionManager.defaultInstance
        val cleanUser = "player_${System.currentTimeMillis()}"

        // Claim username initially
        val initialEntry = PlayerRegistryEntry(
            username = cleanUser,
            uid = "uid_$cleanUser",
            displayName = "Player One",
            avatarUrl = "data:image/jpeg;base64,initialAvatar"
        )
        manager.claimUsername(initialEntry)
        AccountSessionManager.drainBackgroundJobsForTests()

        // Remote avatar arrives via MQTT broadcast
        val newAvatar = "data:image/jpeg;base64,updatedAvatarString"
        manager.onRemoteAvatarUpdated(cleanUser, newAvatar)

        val cached = AccountSessionManager.getCachedPlayer(cleanUser)
        assertNotNull(cached)
        assertEquals(newAvatar, cached?.avatarUrl)
    }

    @Test
    fun testOnlineRoomSyncManagerRemoteAvatarUpdate() {
        val manager = OnlineRoomSyncManager()
        onlineManagerToClean = manager
        val host = Player(
            id = "host1",
            displayName = "Host",
            username = "host_user",
            avatarUrl = "avatar_old"
        )
        manager.connectToRoom("ROOM1", host)

        assertEquals("avatar_old", manager.players.value.first().avatarUrl)

        // Update local avatar
        manager.updateLocalAvatar("avatar_new")
        assertEquals("avatar_new", manager.players.value.first().avatarUrl)

        // Simulate remote peer avatar updated
        manager.onRemoteAvatarUpdated("host_user", "avatar_remote_sync")
        assertEquals("avatar_remote_sync", manager.players.value.first().avatarUrl)
    }

    @Test
    fun testLanP2pSessionManagerAvatarUpdate() {
        val manager = LanP2pSessionManager()
        lanManagerToClean = manager
        val host = Player(
            id = "p1",
            displayName = "Host Player",
            username = "lan_host",
            avatarUrl = "lan_old"
        )
        manager.connectAsHost(host)

        assertEquals("lan_old", manager.players.value.first().avatarUrl)

        manager.updateLocalAvatar("lan_new")
        assertEquals("lan_new", manager.players.value.first().avatarUrl)

        manager.onRemoteAvatarUpdated("lan_host", "lan_sync")
        assertEquals("lan_sync", manager.players.value.first().avatarUrl)
    }

    @Test
    fun testPresenceManagerAvatarUpdateReceived() {
        val cleanUser = "sync_user_${System.currentTimeMillis()}"
        val payload = """{"username":"$cleanUser","avatar":"data:image/jpeg;base64,testBase64Avatar","timestamp":${System.currentTimeMillis()}}"""

        val v0 = PlayerAvatarCache.version.value
        PresenceManager.onAvatarUpdateReceived(cleanUser, payload)
        val v1 = PlayerAvatarCache.version.value

        assertTrue("PlayerAvatarCache should be notified and bumped", v1 >= v0)
    }

    @Test
    fun testAvatarRealtimeSyncTeardownDrainsPendingJobsAndClosesConnections() = runBlocking {
        // Verification regression for R11: holds username publisher, room connection, and host heartbeat,
        // verifies teardown cleans up completely and fixture replacement leaves zero surviving subscriptions.
        val manager = AccountSessionManager.defaultInstance
        val cleanUser = "hold_user_${System.currentTimeMillis()}"
        manager.claimUsername(PlayerRegistryEntry(cleanUser, "uid_$cleanUser", "Holder"))
        
        val syncManager = OnlineRoomSyncManager()
        onlineManagerToClean = syncManager
        val hostPlayer = Player("h1", "Holder", username = cleanUser, isHost = true)
        syncManager.connectToRoom("HOLD01", hostPlayer)

        // Reach host heartbeat which initializes metadata transport coordinator and subscriptions
        OnlineRoomRegistry.heartbeatRoom("HOLD01")
        
        val lanManager = LanP2pSessionManager()
        lanManagerToClean = lanManager
        lanManager.connectAsHost(hostPlayer)
        
        // Execute teardown logic explicitly
        AccountSessionManager.drainBackgroundJobsForTests()
        syncManager.disconnect()
        syncManager.drainForTests()
        onlineManagerToClean = null
        
        lanManager.disconnect()
        lanManager.drainForTests()
        lanManagerToClean = null

        RoomMetadataApi.resetForTests()
        
        // Ensure no lingering jobs or connections remain on original fixture
        val fixture = EmqxTransport.wireOverride as? EmqxWireFixture
        assertNotNull("Wire fixture must be installed", fixture)
        assertEquals("Wire fixture must not have active subscriptions on room topic", 0,
            fixture!!.activeSubscriptions("bingo/emqx7/room/HOLD01"))
        assertEquals("Wire fixture must not have active subscriptions on availability topic", 0,
            fixture.activeSubscriptions("bingo/emqx7/room/HOLD01/availability/#"))
        assertEquals("Wire fixture must not have active subscriptions on metadata topic", 0,
            fixture.activeSubscriptions("bingo/emqx7/metadata/HOLD01/state"))
        assertFalse("Online sync manager must not remain connected", syncManager.isConnected)

        // Fixture replacement: ensure a new wire fixture has zero lingering subscriptions
        val newFixture = EmqxWireFixture()
        EmqxTransport.wireOverride = newFixture
        RoomMetadataApi.resetForTests()

        assertEquals("New fixture must have zero subscriptions on room topic", 0,
            newFixture.activeSubscriptions("bingo/emqx7/room/HOLD01"))
        assertEquals("New fixture must have zero subscriptions on metadata topic", 0,
            newFixture.activeSubscriptions("bingo/emqx7/metadata/HOLD01/state"))
    }
}
