package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.CloudUserDataBackup
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdminControlClientTest {
    private val player = UserProfile(uid = "guest_admin_fixture", username = "admin_fixture", displayName = "Fixture", gamesPlayed = 10, gamesWon = 5)

    @Before fun setup() { AdminControlClient.resetForTests() }
    @After fun cleanup() { AdminControlClient.resetForTests(); AccountSessionManager.customCloudGateway = null }

    @Test fun resetAppliesOnceAndNewGamesAccumulate() {
        val policy = AdminPlayerPolicy(username = player.username, uid = player.uid, revision = 1,
            fields = mapOf("gamesPlayed" to AdminField(JsonPrimitive(0), 1), "gamesWon" to AdminField(JsonPrimitive(0), 1)))
        val reset = policy.apply(player)!!
        assertEquals(0, reset.gamesPlayed)
        assertEquals(1L, reset.adminRevision)
        val next = policy.apply(reset.copy(gamesPlayed = 1, gamesWon = 1))!!
        assertEquals(1, next.gamesPlayed)
        assertEquals(1, next.gamesWon)
    }

    @Test fun laterPhotoEditDoesNotRepeatAnAcknowledgedStatisticsReset() {
        val policy = AdminPlayerPolicy(revision = 2, fields = mapOf("gamesPlayed" to AdminField(JsonPrimitive(0), 1), "avatarUrl" to AdminField(JsonNull, 2)))
        val result = policy.apply(player.copy(adminRevision = 1, avatarUrl = "https://example.test/old.jpg"))!!
        assertEquals(10, result.gamesPlayed)
        assertNull(result.avatarUrl)
    }

    @Test fun deletedAccountAndMismatchedIdentityCannotApplyOrBackUp() = runBlocking {
        assertNull(AdminPlayerPolicy(uid = "someone_else").apply(player))
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, uid = player.uid, revision = 1, deleted = true))
        assertTrue(AdminControlClient.removed(player.username))
        assertNull(AdminControlClient.apply(player))
        assertNull(AdminControlClient.prepareBackup(CloudUserDataBackup(player)))
    }

    @Test fun explicitNullPhotoCannotFallBackToOldGoogleOrLocalPhoto() {
        val policy = AdminPlayerPolicy(revision = 1, fields = mapOf("avatarUrl" to AdminField(JsonNull, 1), "avatarBase64" to AdminField(JsonNull, 1)))
        val result = policy.apply(player.copy(avatarUrl = "/data/user/old.jpg", avatarBase64 = "OLD"))!!
        assertNull(result.avatarUrl)
        assertNull(result.avatarBase64)
    }

    @Test fun webDataUriIsConvertedToNativeBase64WithoutChangingBytes() {
        val policy = AdminPlayerPolicy(revision = 1, fields = mapOf("avatarBase64" to AdminField(JsonPrimitive("data:image/jpeg;base64,/9j/fixture"), 1)))
        assertEquals("/9j/fixture", policy.apply(player)!!.avatarBase64)
    }

    @Test fun coldJsonBackupsWithoutAnAdminRevisionStayCompatible() {
        val old = Json.decodeFromString<UserProfile>("""{"uid":"guest_admin_fixture","username":"admin_fixture","displayName":"Fixture"}""")
        assertEquals(0L, old.adminRevision)
        assertEquals(old, AdminControlClient.apply(old))
    }

    @Test fun delayedOlderPolicyCannotReplaceANewerDeletion() {
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, revision = 2, deleted = true))
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, revision = 1))
        assertNull(AdminControlClient.apply(player))
        assertEquals(2L, AdminControlClient.cached(player.username)!!.revision)
    }

    @Test fun deletedMatchesStayFilteredAndFutureMatchesRemain() = runBlocking {
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, deletedMatches = setOf("removed"), clearMatchesBefore = 100))
        val records = listOf(MatchRecord(id = "old", timestamp = 99), MatchRecord(id = "removed", timestamp = 500), MatchRecord(id = "new", timestamp = 501))
        assertEquals(listOf("new"), AdminControlClient.prepareBackup(CloudUserDataBackup(player, matchHistory = records))!!.matchHistory.map { it.id })
    }

    @Test fun productionBackupWritesCorrectedProfileAndKeepsHistoryFiltered() = runBlocking {
        val bins = mutableMapOf<String, String>()
        val keys = mutableMapOf<String, String>()
        AccountSessionManager.customCloudGateway = object : AccountSessionManager.CloudStorageGateway {
            override suspend fun getKeyValue(key: String) = keys[key]
            override suspend fun setKeyValue(key: String, value: String): Boolean { keys[key] = value; return true }
            override suspend fun createJsonBin(jsonPayload: String): String { bins["fixture_bin"] = jsonPayload; return "fixture_bin" }
            override suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean { bins[binId] = jsonPayload; return true }
            override suspend fun getJsonBin(binId: String) = bins[binId]
        }
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, uid = player.uid, revision = 1,
            fields = mapOf("gamesPlayed" to AdminField(JsonPrimitive(0), 1), "gamesWon" to AdminField(JsonPrimitive(0), 1)), deletedMatches = setOf("removed")))
        val backup = CloudUserDataBackup(player, matchHistory = listOf(MatchRecord(id = "removed")))
        val manager = AccountSessionManager()
        assertTrue(manager.backupUserDataSync(player.uid, backup))
        val saved = Json.decodeFromString<CloudUserDataBackup>(bins.getValue("fixture_bin"))
        assertEquals(0, saved.profile.gamesPlayed)
        assertEquals(1L, saved.profile.adminRevision)
        assertTrue(saved.matchHistory.isEmpty())
        assertTrue(manager.backupUserDataSync(player.uid, saved.copy(profile = saved.profile.copy(gamesPlayed = 1, gamesWon = 1), lastBackupTimestamp = saved.lastBackupTimestamp + 1)))
        assertEquals(1, Json.decodeFromString<CloudUserDataBackup>(bins.getValue("fixture_bin")).profile.gamesPlayed)
    }

    @Test fun productionBackupDoesNotWriteDeletedAccount() = runBlocking {
        AdminControlClient.accept(AdminPlayerPolicy(username = player.username, uid = player.uid, revision = 5, deleted = true))
        assertFalse(AccountSessionManager().backupUserDataSync(player.uid, CloudUserDataBackup(player)))
    }
}
