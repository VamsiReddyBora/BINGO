package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class LegacyProfileRecoveryTest {
    private val local = UserProfile("google_fixture", "fresh_name", "Fresh", provider = AuthProvider.GOOGLE)
    private val data: Map<String, Any?> = mapOf("uid" to local.uid, "provider" to "GOOGLE", "username" to "original_name",
        "displayName" to "Original", "gamesPlayed" to 35L, "gamesWon" to 1L, "currentStreak" to 0L,
        "bestStreak" to 1L, "level" to 3L, "xp" to 1200L, "avatarBase64" to "photo",
        "legacyMigration" to mapOf("schema" to 1L, "id" to "legacy-v1-fixture", "source" to "keyvalue-extendsclass"))
    @Test fun normalProfilesNeverEnterTheMigrationPath() { assertSame(local, recoverLegacyProfile(local, data - "legacyMigration")) }
    @Test fun returningAccountGetsOriginalNameStatsAndPhotoBeforeUpload() {
        val restored = recoverLegacyProfile(local, data)
        assertEquals("original_name", restored.username); assertEquals(35, restored.gamesPlayed)
        assertEquals(1200, restored.xp); assertEquals("photo", restored.avatarBase64)
        assertEquals("legacy-v1-fixture", restored.legacyMigrationId)
    }
    @Test fun repeatingTheSnapshotPreservesNewGames() {
        val restored = recoverLegacyProfile(local, data).copy(gamesPlayed = 36, gamesWon = 2)
        assertSame(restored, recoverLegacyProfile(restored, data))
    }
    @Test fun newerOfflineProgressIsNotReducedByTheOldBackup() {
        val newer = local.copy(gamesPlayed = 40, gamesWon = 3, xp = 1600, currentStreak = 2, bestStreak = 3, level = 5)
        val restored = recoverLegacyProfile(newer, data)
        assertEquals(40, restored.gamesPlayed); assertEquals(1600, restored.xp)
        assertEquals(2, restored.currentStreak); assertEquals(5, restored.level)
    }
    @Test fun administratorReductionsRemainAuthoritative() {
        val edited = local.copy(adminRevision = 2, gamesPlayed = 2, gamesWon = 1)
        assertSame(edited, recoverLegacyProfile(edited, data))
        val first = recoverLegacyProfile(local, data)
        val policy = CloudAdminPolicy.from(mapOf("adminRevision" to 1L, "adminChanges" to mapOf(
            "gamesPlayed" to mapOf("revision" to 1L, "value" to 2))))
        assertEquals(2, policy.applyTo(first).gamesPlayed)
    }
    @Test fun anotherAccountOrProviderCannotSupplyAnImportedProfile() {
        assertSame(local, recoverLegacyProfile(local, data + ("uid" to "other")))
        assertSame(local, recoverLegacyProfile(local, data + ("provider" to "GUEST")))
    }
    @Test fun invalidMigrationOrIncompleteStatisticsDoNotCreateARecovery() {
        assertSame(local, recoverLegacyProfile(local, data - "xp"))
        assertSame(local, recoverLegacyProfile(local, data + ("gamesWon" to 100L)))
        assertSame(local, recoverLegacyProfile(local, data + ("legacyMigration" to mapOf("schema" to 2))))
    }
    @Test fun originalGuestIdentityCanRecoverWithoutBecomingGoogle() {
        val guest = local.copy(uid = "guest_fixture", provider = AuthProvider.GUEST)
        assertEquals("guest_fixture", recoverLegacyProfile(guest, data + mapOf("uid" to guest.uid, "provider" to "GUEST")).uid)
        assertEquals(AuthProvider.GUEST, recoverLegacyProfile(guest, data + mapOf("uid" to guest.uid, "provider" to "GUEST")).provider)
    }
}
