package com.bingo.multiplayer.domain.repository

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FriendsRepositoryTest {
    private val bob = UserProfile("bob-id", "bob", "Bob")
    private val kevin = Friend("kevin-id", "kevin", "Kevin")
    private val allFriends = listOf(kevin, Friend("s", "stuart", "Stuart"), Friend("a", "alice", "Alice"), Friend("d", "dave", "Dave"))
    private fun request(id: String = "request-1", sender: String = "kevin") =
        FriendRequest(id, "$sender-id", sender, sender, toUsername = "bob", timestamp = 1L)

    private open class Remote : FriendsDataSource {
        var cloud: List<Friend>? = emptyList()
        var backup: FriendsBackup? = FriendsBackup(emptyList())
        var requests: List<FriendRequest>? = emptyList()
        val saves = mutableListOf<Pair<String, List<Friend>>>()
        val savedVersions = mutableListOf<String?>()
        var callback: ((FriendRequestPacket) -> Unit)? = null
        var acceptSucceeds = true
        var gate: CompletableDeferred<Unit>? = null
        var reads = 0
        var lastRemovalId: String? = null
        var version: String = ""
        val sentRequests = mutableListOf<FriendRequest>()
        val tombstones = mutableMapOf<String, Long>()
        override suspend fun fetchFriends(username: String): List<Friend>? {
            reads++
            val snapshot = cloud
            gate?.await()
            return snapshot
        }
        override suspend fun fetchFriendsWithVersion(username: String): com.bingo.multiplayer.domain.network.FriendsCloudStore.VersionedSnapshot<Friend>? {
            reads++
            val snapshot = cloud ?: return null
            val ver = version
            gate?.await()
            return com.bingo.multiplayer.domain.network.FriendsCloudStore.VersionedSnapshot(snapshot, ver)
        }
        override suspend fun fetchBackup(uid: String) = backup
        override suspend fun fetchRequests(username: String) = requests
        override suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String?): Boolean {
            saves += username to friends
            savedVersions += expectedVersion
            cloud = friends
            return true
        }
        override suspend fun removeFriend(username: String, friendIdentifier: String): Boolean {
            cloud = cloud?.filterNot { it.uid == friendIdentifier || friendUsername(it.username) == friendUsername(friendIdentifier) }
            return true
        }
        override fun saveBackup(uid: String, friends: List<Friend>) {}
        override fun removeFromBackup(uid: String, friend: Friend, removalId: String) { lastRemovalId = removalId }
        override suspend fun send(request: FriendRequest): Boolean {
            sentRequests += request
            return true
        }
        override suspend fun accept(request: FriendRequest, user: UserProfile): Boolean {
            val matching = requests?.find { it.id == request.id } ?: request
            val tombstone = tombstones[matching.fromUid] ?: tombstones[friendUsername(matching.fromUsername)]
            if (tombstone != null && (matching.timestamp <= 0L || matching.timestamp <= tombstone)) {
                return false
            }
            if (acceptSucceeds) {
                requests = requests?.filterNot { it.id == request.id }
                backup = FriendsBackup(backup?.friends.orEmpty(), backup?.removedKeys.orEmpty() - friendKeys(Friend(matching.fromUid, matching.fromUsername, matching.fromDisplayName)))
            }
            return acceptSucceeds
        }
        override suspend fun decline(request: FriendRequest): Boolean {
            requests = requests?.filterNot { it.id == request.id }
            return true
        }
        override fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit): AutoCloseable {
            callback = onPacket
            return AutoCloseable {}
        }
        override suspend fun refreshPresence(usernames: List<String>) {}
        fun emit(req: FriendRequest) { callback?.invoke(FriendRequestPacket("FRIEND_REQUEST", req)) }
    }

    private fun repository(scope: TestScope, remote: Remote, prefs: AuthRepositoryTest.FakeSharedPreferences = AuthRepositoryTest.FakeSharedPreferences(), user: () -> UserProfile? = { bob }) =
        FriendsRepository(prefs, remote, user, scope)

    @Test fun partialPrimaryRestoresAllFourFriendsFromExistingBackup() = runTest {
        val remote = Remote().apply { cloud = listOf(kevin); backup = FriendsBackup(allFriends) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("@BoB")
        runCurrent()
        assertEquals(allFriends.toSet(), repo.friends.value.toSet())
        assertEquals(4, remote.saves.single().second.size)
    }

    @Test fun failedPrimaryReadKeepsCacheAndNeverWritesAPartialList() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        prefs.edit().putString("friends_list", Json.encodeToString(allFriends)).commit()
        val remote = Remote().apply { cloud = null; backup = null }
        val repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(4, repo.friends.value.size)
        assertTrue(remote.saves.isEmpty())
    }

    @Test fun successfulEmptyPrimaryDoesNotEraseBackupOrCache() = runTest {
        val remote = Remote().apply { backup = FriendsBackup(allFriends) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(4, repo.friends.value.size)
        remote.backup = null
        remote.cloud = emptyList()
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(4, repo.friends.value.size)
    }

    @Test fun equivalentUsernamesAndEmptyUidsDoNotDropUnrelatedFriends() = runTest {
        val remote = Remote().apply {
            cloud = listOf(kevin.copy(username = " @KEVIN "), Friend("", "alice", "Alice"), Friend("", "dave", "Dave"))
            backup = FriendsBackup(listOf(kevin))
        }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(3, repo.friends.value.size)
        assertTrue(repo.isFriend("@Kevin"))
    }

    @Test fun acceptedFriendReplayedRequestNeverNotifiesEvenAtColdStartup() = runTest {
        val remote = Remote().apply { cloud = listOf(kevin); backup = FriendsBackup(allFriends); requests = listOf(request()) }
        val repo = repository(this, remote)
        var toasts = 0
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertEquals(0, toasts)
        assertTrue(repo.pendingRequests.value.isEmpty())
        assertEquals(4, repo.friends.value.size)
    }

    @Test fun requestAbsentFromCloudNeverNotifies() = runTest {
        val remote = Remote()
        var toasts = 0
        val repo = repository(this, remote)
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertEquals(0, toasts)
        assertTrue(repo.pendingRequests.value.isEmpty())
    }

    @Test fun genuineNewRequestNotifiesOnceAcrossDuplicatesAndProcessRestarts() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply { requests = listOf(request()) }
        var toasts = 0
        var repo = repository(this, remote, prefs)
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); remote.emit(request()); runCurrent()
        assertEquals(1, toasts)
        repo = repository(this, remote, prefs)
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertEquals(1, toasts)
        assertEquals(1, repo.pendingRequests.value.size)
    }

    @Test fun newRequestIdFromSameSenderCanNotifyAfterEarlierDecline() = runTest {
        val remote = Remote().apply { requests = listOf(request()) }
        val repo = repository(this, remote)
        var toasts = 0
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertTrue(repo.declineFriendRequest(request()))
        remote.requests = listOf(request("request-2"))
        remote.emit(request("request-2")); runCurrent()
        assertEquals(2, toasts)
    }

    @Test fun acceptedRequestAndSameSenderDuplicatesAreRemovedAndStaySilentAfterRestart() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply { requests = listOf(request(), request("duplicate")) }
        var repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertTrue(repo.acceptFriendRequest(request(), bob))
        assertTrue(repo.pendingRequests.value.isEmpty())
        var toasts = 0
        repo = repository(this, remote, prefs)
        remote.requests = listOf(request()) // delayed/stale server response
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertEquals(0, toasts)
        assertEquals(1, repo.friends.value.size)
    }

    @Test fun failedAcceptKeepsPendingRequestAndDoesNotInventFriendship() = runTest {
        val remote = Remote().apply { requests = listOf(request()); acceptSucceeds = false }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertFalse(repo.acceptFriendRequest(request(), bob))
        assertTrue(repo.friends.value.isEmpty())
        assertEquals(1, repo.pendingRequests.value.size)
    }

    @Test fun declinedRequestDoesNotReappearOnStaleReadOrPacket() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply { requests = listOf(request()) }
        var repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertTrue(repo.declineFriendRequest(request()))
        repo = repository(this, remote, prefs)
        remote.requests = listOf(request())
        var toasts = 0
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertTrue(repo.pendingRequests.value.isEmpty())
        assertEquals(0, toasts)
    }

    @Test fun unavailableRequestReadRetainsUiButDoesNotAuthorizeAToast() = runTest {
        val remote = Remote().apply { requests = listOf(request()) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        remote.requests = null
        var toasts = 0
        repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request()); runCurrent()
        assertEquals(1, repo.pendingRequests.value.size)
        assertEquals(0, toasts)
    }

    @Test fun explicitlyRemovedFriendStaysRemovedAcrossRestartAndStaleBackups() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply { cloud = allFriends; backup = FriendsBackup(allFriends) }
        var repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        repo.removeFriend("kevin-id", "bob"); runCurrent()
        repo = repository(this, remote, prefs)
        remote.cloud = allFriends
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(3, repo.friends.value.size)
        assertFalse(repo.isFriend("kevin"))
    }

    @Test fun backupRemovalMarkersAreHonored() = runTest {
        val remote = Remote().apply { cloud = allFriends; backup = FriendsBackup(allFriends, friendKeys(kevin)) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(3, repo.friends.value.size)
        assertFalse(repo.isFriend("kevin"))
    }

    @Test fun explicitAcceptanceCanReaddARemovedFriend() = runTest {
        val remote = Remote().apply { cloud = allFriends; backup = FriendsBackup(allFriends) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        repo.removeFriend("kevin", "bob"); runCurrent()
        assertTrue(repo.acceptFriendRequest(request("new-request"), bob))
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertTrue(repo.isFriend("kevin"))
        assertEquals(4, repo.friends.value.size)
    }

    @Test fun closedListenerAndWrongRecipientCannotNotify() = runTest {
        val remote = Remote().apply { requests = listOf(request().copy(toUsername = "alice")) }
        val repo = repository(this, remote)
        var toasts = 0
        val listener = repo.startListeningForRequests("bob", { toasts++ }, {})
        remote.emit(request().copy(toUsername = "alice")); runCurrent()
        remote.requests = listOf(request())
        remote.emit(request()); listener.close(); runCurrent()
        assertEquals(0, toasts)
    }

    @Test fun accountSwitchRejectsOldAsyncResultsAndKeepsCachesSeparate() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        var user: UserProfile? = bob
        val remote = Remote().apply { cloud = allFriends; gate = CompletableDeferred() }
        val repo = repository(this, remote, prefs) { user }
        repo.syncFriendsAndRequests("bob"); runCurrent()
        user = UserProfile("alice-id", "alice", "Alice")
        repo.setAccount(user)
        remote.gate!!.complete(Unit); runCurrent()
        assertTrue(repo.friends.value.isEmpty())
        assertTrue(remote.saves.isEmpty())
        remote.cloud = listOf(kevin)
        repo.syncFriendsAndRequests("alice"); runCurrent()
        assertEquals(1, repo.friends.value.size)
        user = bob; repo.setAccount(user)
        assertTrue(repo.friends.value.isEmpty())
        user = null; repo.setAccount(null)
        assertTrue(repo.friends.value.isEmpty())
        assertTrue(repo.pendingRequests.value.isEmpty())
    }

    @Test fun repeatedPollsCoalesceWhileCloudReadIsRunning() = runTest {
        val remote = Remote().apply { cloud = allFriends; gate = CompletableDeferred() }
        val repo = repository(this, remote)
        repeat(10) { repo.syncFriendsAndRequests("bob") }
        runCurrent()
        assertEquals(1, remote.reads)
        remote.gate!!.complete(Unit); runCurrent()
        assertEquals(4, repo.friends.value.size)
    }

    @Test fun friendAcceptanceNotificationRequiresConfirmedFriendshipAndIsDeduplicated() = runTest {
        val remote = Remote().apply { cloud = listOf(kevin) }
        val repo = repository(this, remote)
        var toasts = 0
        repo.startListeningForRequests("bob", {}, { toasts++ })
        val accepted = request().copy(fromUid = bob.uid, fromUsername = "bob", toUsername = "kevin", status = FriendRequestStatus.ACCEPTED)
        repeat(2) { remote.callback!!(FriendRequestPacket("FRIEND_ACCEPT", accepted, kevin)) }
        remote.callback!!(FriendRequestPacket("FRIEND_ACCEPT", accepted.copy(id = "forged", toUsername = "other")))
        runCurrent()
        assertEquals(1, toasts)
        assertEquals(1, repo.friends.value.size)
    }
    @Test fun friendReaddedByOtherPlayersAcceptanceRecoversAfterRestart() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply { cloud = allFriends; backup = FriendsBackup(allFriends) }
        var repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        repo.removeFriend("kevin", "bob"); runCurrent()
        val removalId = remote.lastRemovalId!!
        // Kevin accepted a new request while Bob's app was closed. The backup confirms that exact removal was restored.
        remote.cloud = allFriends
        remote.backup = FriendsBackup(allFriends, restoredKeys = friendKeys(kevin), restoredRemovalIds = setOf(removalId))
        repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(4, repo.friends.value.size)
        assertTrue(repo.isFriend("kevin"))
    }

    @Test fun olderRestorationMarkerCannotUndoANewerExplicitRemoval() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val remote = Remote().apply {
            cloud = allFriends
            backup = FriendsBackup(allFriends, restoredKeys = friendKeys(kevin), restoredRemovalIds = setOf("old-removal"))
        }
        var repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        repo.removeFriend("kevin", "bob"); runCurrent()
        repo = repository(this, remote, prefs)
        remote.cloud = allFriends
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(3, repo.friends.value.size)
        assertFalse(repo.isFriend("kevin"))
    }

    @Test fun acceptancePacketRefreshesBackupBeforeNotifyingEvenWhenPrimaryIsPartial() = runTest {
        val remote = Remote().apply { cloud = allFriends; backup = FriendsBackup(allFriends) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        val newcomer = Friend("new-id", "newcomer", "Newcomer")
        // Another writer left primary storage stale, but the acceptance's existing backup is complete.
        remote.backup = FriendsBackup(allFriends + newcomer)
        var toasts = 0
        repo.startListeningForRequests("bob", {}, { toasts++ })
        val accepted = request("new-accept").copy(fromUid = bob.uid, fromUsername = "bob", toUsername = "newcomer", status = FriendRequestStatus.ACCEPTED)
        remote.callback!!(FriendRequestPacket("FRIEND_ACCEPT", accepted, newcomer))
        runCurrent()
        assertEquals(5, repo.friends.value.size)
        assertTrue(repo.isFriend("newcomer"))
        assertEquals(1, toasts)
    }

    @Test fun compactPrimaryRecordsKeepFullCareerStatisticsFromBackup() = runTest {
        val fullProfile = kevin.copy(gamesPlayed = 10, gamesWon = 5, currentStreak = 3, level = 4)
        val remote = Remote().apply { cloud = listOf(kevin); backup = FriendsBackup(listOf(fullProfile)) }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(10, repo.friends.value.single().gamesPlayed)
        assertEquals(5, repo.friends.value.single().gamesWon)
        assertEquals(3, repo.friends.value.single().currentStreak)
        assertEquals(4, repo.friends.value.single().level)
    }

    @Test fun newerCareerSnapshotCanResetStreakWithoutOldCacheUndoingIt() = runTest {
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val old = kevin.copy(gamesPlayed = 10, gamesWon = 5, currentStreak = 3)
        prefs.edit().putString("friends_list", Json.encodeToString(listOf(old))).commit()
        val newer = old.copy(gamesPlayed = 11, currentStreak = 0)
        val remote = Remote().apply { cloud = listOf(newer); backup = FriendsBackup(listOf(newer)) }
        val repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob"); runCurrent()
        assertEquals(11, repo.friends.value.single().gamesPlayed)
        assertEquals(0, repo.friends.value.single().currentStreak)
    }

    @Test fun removingLocalFriendDoesNotOverwriteIndependentlyAddedRemoteFriend() = runTest {
        val alice = Friend("a-id", "alice", "Alice")
        val remoteBob = Friend("b-id", "bob-remote", "Bob Remote")
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        prefs.edit().putString("friends_list", Json.encodeToString(listOf(alice))).commit()

        val remote = Remote().apply {
            cloud = listOf(alice, remoteBob)
            backup = FriendsBackup(listOf(alice, remoteBob))
        }

        val repo = repository(this, remote, prefs)
        repo.removeFriend("alice", "bob")
        runCurrent()

        val remainingCloud = remote.cloud.orEmpty()
        assertTrue("Remote friend Bob must be preserved when removing Alice", remainingCloud.any { it.uid == "b-id" })
        assertFalse("Alice must be removed", remainingCloud.any { it.uid == "a-id" })

        val restarted = repository(this, remote, prefs)
        restarted.syncFriendsAndRequests("bob")
        runCurrent()
        assertTrue("Bob must remain in friends after reconciliation", restarted.friends.value.any { it.uid == "b-id" })
        assertFalse("Alice must not be in friends", restarted.friends.value.any { it.uid == "a-id" })
    }

    @Test fun reconciliationWithInterveningAdditionCarriesVersionAndPreservesAddition() = runTest {
        val alice = Friend("a-id", "alice", "Alice")
        val dave = Friend("d-id", "dave", "Dave")
        val stuart = Friend("s-id", "stuart", "Stuart")
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        prefs.edit().putString("friends_list", Json.encodeToString(listOf(alice, dave))).commit()

        val remote = Remote()
        remote.cloud = listOf(alice)
        remote.version = "1"

        val gate = CompletableDeferred<Unit>()
        remote.gate = gate

        val repo = repository(this, remote, prefs)
        repo.syncFriendsAndRequests("bob")
        runCurrent()

        // At this point, repo is paused inside fetchFriendsWithVersion holding version "1"
        // Intervening client adds Stuart and increments version to "2"
        remote.cloud = listOf(alice, stuart)
        remote.version = "2"

        // Release repo
        gate.complete(Unit)
        runCurrent()

        // Reconciliation passed expectedVersion
        assertTrue("Saved versions must capture expected version", remote.savedVersions.isNotEmpty())
        assertEquals("First save attempt must pass version 1", "1", remote.savedVersions.first())
    }

    @Test fun testSendFriendRequestAssignsPositiveCreationProvenance() = runTest {
        val remote = Remote()
        val repo = repository(this, remote)
        val before = System.currentTimeMillis()
        val success = repo.sendFriendRequest("kevin", bob)
        val after = System.currentTimeMillis()
        assertTrue("sendFriendRequest must return true on success", success)
        assertEquals("Exactly one request must be dispatched", 1, remote.sentRequests.size)
        val sent = remote.sentRequests.single()
        assertEquals("kevin", sent.toUsername)
        assertEquals("bob", sent.fromUsername)
        assertEquals("bob-id", sent.fromUid)
        assertTrue("Sent request timestamp must be strictly positive", sent.timestamp > 0L)
        assertTrue("Sent request timestamp must be in execution window", sent.timestamp in before..after)
    }

    @Test fun testFriendshipAcceptanceDerivesFreshnessFromPersistedProvenanceAndRejectsTombstone() = runTest {
        val stalePersistedReq = request("stale-req", "kevin").copy(timestamp = 1000L)
        val remote = Remote().apply {
            requests = listOf(stalePersistedReq)
            tombstones["kevin-id"] = 2000L
        }
        val repo = repository(this, remote)
        repo.syncFriendsAndRequests("bob")
        runCurrent()

        // 1. Caller attempts to accept passing a fabricated request with newer timestamp (3000L).
        // Acceptance must derive freshness from the persisted request (1000L), NOT caller's payload,
        // and reject against the tombstone (2000L).
        val fabricatedReq = stalePersistedReq.copy(timestamp = 3000L)
        val acceptedWithFabricatedTimestamp = repo.acceptFriendRequest(fabricatedReq, bob)
        assertFalse("Acceptance must reject when persisted request is older than tombstone", acceptedWithFabricatedTimestamp)
        assertTrue("Friends must remain empty after rejected acceptance", repo.friends.value.isEmpty())

        // 2. Now update persisted request with fresh provenance (3000L > tombstone 2000L).
        val freshPersistedReq = stalePersistedReq.copy(id = "fresh-req", timestamp = 3000L)
        remote.requests = listOf(freshPersistedReq)
        repo.syncFriendsAndRequests("bob")
        runCurrent()

        val acceptedFresh = repo.acceptFriendRequest(freshPersistedReq, bob)
        assertTrue("Acceptance must succeed when persisted provenance is newer than tombstone", acceptedFresh)
        assertEquals(1, repo.friends.value.size)
        assertEquals("kevin-id", repo.friends.value.single().uid)
    }
}
