package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class UniversalProfileAvatarTest {
    private class Source : ProfileAvatarSource {
        val callbacks = mutableListOf<(ProfileAvatar) -> Unit>()
        var starts = 0
        var stops = 0
        override fun watch(username: String, changed: (ProfileAvatar) -> Unit): AutoCloseable {
            starts++; callbacks += changed
            return AutoCloseable { stops++ }
        }
    }
    @Test fun everyWindowPrefersTheSameLatestPhotoOverItsStaleArgument() {
        val source = Source(); val state = ProfileAvatarState(source)
        val lobby = state.observe("@STUART"); val profile = state.observe("stuart")
        source.callbacks.single()(ProfileAvatar("new-photo"))
        for (old in listOf("old-lobby-photo", "old-search-photo", null))
            assertEquals("new-photo", ProfileAvatarState.resolve(state.records.value, "stuart", old))
        assertEquals(1, source.starts)
        lobby.close(); assertEquals(0, source.stops)
        profile.close(); assertEquals(1, source.stops)
    }
    @Test fun removalCannotResurrectAStalePhotoFromAnotherWindow() {
        val source = Source(); val state = ProfileAvatarState(source)
        val handle = state.observe("stuart")
        source.callbacks.single()(ProfileAvatar("first"))
        source.callbacks.single()(ProfileAvatar(null))
        assertNull(ProfileAvatarState.resolve(state.records.value, "@Stuart", "old-photo"))
        handle.close()
    }
    @Test fun localChangeIgnoresOldCachedSnapshotUntilFirebaseAcknowledgesIt() {
        val source = Source(); val state = ProfileAvatarState(source)
        val handle = state.observe("stuart")
        state.localChange("stuart", "new")
        source.callbacks.single()(ProfileAvatar("old"))
        assertEquals("new", state.records.value["stuart"]!!.source)
        source.callbacks.single()(ProfileAvatar("new"))
        source.callbacks.single()(ProfileAvatar("later"))
        assertEquals("later", state.records.value["stuart"]!!.source)
        state.localChange("stuart", null)
        source.callbacks.single()(ProfileAvatar("later"))
        assertNull(state.records.value["stuart"]!!.source)
        source.callbacks.single()(ProfileAvatar(null))
        handle.close()
    }
    @Test fun detachedWatchCannotOverwriteAReopenedWindow() {
        val source = Source(); val state = ProfileAvatarState(source)
        val first = state.observe("stuart"); first.close(); first.close()
        val second = state.observe("stuart")
        source.callbacks[1](ProfileAvatar("current"))
        source.callbacks[0](ProfileAvatar("stale"))
        assertEquals("current", state.records.value["stuart"]!!.source)
        assertEquals(1, source.stops)
        second.close(); assertEquals(2, source.stops)
    }
    @Test fun firstLookupCanUseSuppliedPhotoUntilPublicFirebaseProfileArrives() {
        val source = Source(); val state = ProfileAvatarState(source)
        assertEquals("supplied", ProfileAvatarState.resolve(state.records.value, "stranger", "supplied"))
        val handle = state.observe("stranger")
        source.callbacks.single()(ProfileAvatar("public-photo"))
        assertEquals("public-photo", ProfileAvatarState.resolve(state.records.value, "stranger", null))
        handle.close()
    }
    @Test fun observerFailureKeepsTheExistingPhotoUsable() {
        val state = ProfileAvatarState(ProfileAvatarSource { _, _ -> error("offline") })
        val handle = state.observe("stuart")
        assertEquals("cached-photo", ProfileAvatarState.resolve(state.records.value, "stuart", "cached-photo"))
        handle.close()
    }
    @Test fun lateProfileBackupUsesTheCurrentPhotoAndNeverPublishesAnotherAccountsLocalPath() {
        val previous = UserProfile("s", "stuart", "Stuart", avatarBase64 = "old-photo")
        val current = previous.copy(avatarBase64 = "new-photo")
        assertEquals("new-photo", publicProfileAvatar(previous, current))
        assertNull(publicProfileAvatar(previous, current.copy(avatarBase64 = null, avatarUrl = null)))
        assertEquals("old-photo", publicProfileAvatar(previous, current.copy(uid = "other")))
        assertNull(publicProfileAvatar(previous.copy(avatarBase64 = null, avatarUrl = "/data/user/0/app/files/photo.jpg"), null))
    }
    @Test fun freshOfflinePresenceStillAllowsAnInviteWhileBusyStillMeansRequest() {
        assertTrue(FriendPlayAvailability.UNKNOWN.canContact)
        assertTrue(FriendPlayAvailability.AVAILABLE.canContact)
        assertTrue(FriendPlayAvailability.BUSY.canContact)
        assertFalse(FriendPlayAvailability.IN_LOBBY.canContact)
        val now = System.currentTimeMillis()
        val invite = GameInvite("kevin", "Kevin", roomCode = "ABC123", timestamp = now, expiresAt = now + 90_000L)
        val payload = BingoFcmManager.invitePayload(invite.copy(recipientUsername = "@Stuart", roomSessionId = "session"))
        assertEquals(invite.eventId, payload.getString("id"))
        assertEquals("stuart", payload.getString("toUsername"))
        assertEquals("session", payload.getString("roomSessionId"))
        assertEquals(invite.expiresAt, payload.getLong("expiresAt"))
    }
}
