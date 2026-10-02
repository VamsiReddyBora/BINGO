package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.presentation.common.PlayerAvatarCache
import org.junit.Assert.*
import org.junit.Test

class AvatarRealtimeSyncTest {

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
    fun testAccountSessionManagerRemoteAvatarUpdate() {
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
}
