package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.FriendCareerStats
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class CloudAdminPolicyTest {
    private val local = UserProfile("owner", "bob", "Bob", gamesPlayed = 40, gamesWon = 20,
        currentStreak = 4, bestStreak = 8, level = 5, xp = 2100, avatarBase64 = "old")
    private fun policy(revision: Long, vararg edits: Pair<String, Any?>) = CloudAdminPolicy.from(mapOf(
        "adminRevision" to revision,
        "adminChanges" to edits.associate { it.first to mapOf("revision" to revision, "value" to it.second) }
    ))

    @Test fun uploadingStaleLocalValuesAppliesTheExactAdminEditFirst() {
        val updated = policy(1, "level" to 100, "xp" to 40000, "gamesPlayed" to 2, "gamesWon" to 1).applyTo(local)
        assertEquals(100, updated.level); assertEquals(40000, updated.xp)
        assertEquals(2, updated.gamesPlayed); assertEquals(1, updated.gamesWon)
        assertEquals(local.uid, updated.uid); assertEquals(local.provider, updated.provider)
    }
    @Test fun changingTheNameDoesNotEraseGamesPlayedOffline() {
        val updated = policy(2, "displayName" to "New Bob").applyTo(local)
        assertEquals("New Bob", updated.displayName); assertEquals(40, updated.gamesPlayed)
    }
    @Test fun theSameAdminRevisionNeverReplaysOverSubsequentGameplayOrAPhotoChange() {
        val change = policy(1, "gamesPlayed" to 1, "avatarBase64" to null)
        val next = change.applyTo(local).copy(gamesPlayed = 2, avatarBase64 = "new-user-photo")
        assertEquals(next, change.applyTo(next))
    }
    @Test fun laterHistoryOnlyRevisionDoesNotReapplyAnEarlierProfileEdit() {
        val next = policy(1, "gamesPlayed" to 1).applyTo(local).copy(gamesPlayed = 2)
        val history = CloudAdminPolicy.from(mapOf("adminRevision" to 2L, "adminChanges" to mapOf(
            "gamesPlayed" to mapOf("revision" to 1L, "value" to 1))))
        assertEquals(2, history.applyTo(next).gamesPlayed)
        assertEquals(2L, history.applyTo(next).adminRevision)
    }
    @Test fun olderAndFutureDatedFieldRevisionsCannotChangeTheProfile() {
        val next = local.copy(adminRevision = 2)
        assertEquals(next, policy(1, "level" to 100).applyTo(next))
        val malformed = CloudAdminPolicy.from(mapOf("adminRevision" to 3L, "adminChanges" to mapOf(
            "level" to mapOf("revision" to 4L, "value" to 100))))
        assertEquals(5, malformed.applyTo(next).level)
    }
    @Test fun historyDeletionFiltersIdsAndCutoffWhileAllowingNewMatches() {
        val policy = CloudAdminPolicy.from(mapOf("adminRevision" to 3L,
            "adminDeletedMatches" to listOf("removed"), "adminClearMatchesBefore" to 200L))
        assertFalse(policy.permits(MatchRecord("removed", timestamp = 300)))
        assertFalse(policy.permits(MatchRecord("boundary", timestamp = 200)))
        assertTrue(policy.permits(MatchRecord("new", timestamp = 201)))
    }
    @Test fun photosCanBeReplacedOrExplicitlyClearedWithoutFallback() {
        val next = policy(1, "avatarUrl" to null, "avatarBase64" to "admin-photo").applyTo(local)
        assertEquals("admin-photo", next.avatarBase64)
        val cleared = policy(2, "avatarUrl" to null, "avatarBase64" to null).applyTo(next)
        assertNull(cleared.avatarBase64); assertNull(cleared.avatarUrl)
    }
    @Test fun oldAdminProjectionWithoutVersionedFieldsIsMigratedOnce() {
        val old = CloudAdminPolicy.from(mapOf("adminRevision" to 7L, "level" to 100, "xp" to 40000))
        assertEquals(100, old.applyTo(local).level)
        assertEquals(100, old.applyTo(old.applyTo(local)).level)
    }
    @Test fun anAdminResetAlsoUpdatesTheFriendsLeaderboardEvenWhenNumbersDecrease() {
        val friend = Friend("s", "stuart", "Stuart", avatarUrl = "old", gamesPlayed = 40, gamesWon = 30, level = 50)
        val entry = PlayerRegistryEntry("stuart", "s", "New Stuart", gamesPlayed = 1, gamesWon = 0,
            level = 1, avatarUrl = null, adminRevision = 1)
        val updated = FriendCareerStats.merge(friend, entry)
        assertEquals(1, updated.gamesPlayed); assertEquals(0, updated.gamesWon); assertEquals(1, updated.level)
        assertNull(updated.avatarUrl); assertEquals("New Stuart", updated.displayName)
        assertEquals(updated, FriendCareerStats.merge(updated, entry.copy(adminRevision = 0, level = 100, gamesPlayed = 50)))
    }
    @Test fun serverAdminPhotoCanOverrideAnUnacknowledgedLocalPhoto() {
        val state = ProfileAvatarState(ProfileAvatarSource { _, _ -> AutoCloseable {} })
        state.localChange("bob", "local")
        state.adminChange("bob", "owner")
        assertEquals("owner", ProfileAvatarState.resolve(state.records.value, "bob", "old"))
        state.adminChange("bob", null)
        assertNull(ProfileAvatarState.resolve(state.records.value, "bob", "old"))
    }
    @Test fun profileDialogUsesAdminStatisticsEvenWhenAnOldArgumentHasHigherValues() {
        val old = com.bingo.multiplayer.presentation.common.PlayerProfileData("s", "stuart", "Stuart", "old",
            gamesPlayed = 40, gamesWon = 30, level = 50)
        val new = PlayerRegistryEntry("stuart", "s", "New", gamesPlayed = 1, level = 1, adminRevision = 2)
        val updated = old.withRegistry(new)
        assertEquals(1, updated.level); assertEquals(1, updated.gamesPlayed); assertNull(updated.avatarUrl)
        assertEquals(updated, updated.withRegistry(new.copy(adminRevision = 0, level = 100, bestStreak = 0)))
    }
    @Test fun publicProjectionRepairsAnOldUploadUntilTheAdminRevisionIsAcknowledged() {
        val policy = policy(2, "gamesPlayed" to 1, "level" to 100, "avatarBase64" to "new", "avatarUrl" to null)
        val data = mapOf("gamesPlayed" to 50, "level" to 1, "avatarUrl" to "old", "adminAppliedRevision" to 0L)
        assertEquals(1, policy.publicProjection(data)["gamesPlayed"])
        assertEquals("new", policy.publicProjection(data)["avatarUrl"])
        assertEquals(50, policy.publicProjection(data + ("adminAppliedRevision" to 2L))["gamesPlayed"])
    }
}
