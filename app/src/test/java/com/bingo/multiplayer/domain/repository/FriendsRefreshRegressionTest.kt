package com.bingo.multiplayer.domain.repository

import com.bingo.multiplayer.domain.model.*
import com.bingo.multiplayer.domain.network.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class FriendsRefreshRegressionTest {
    private class Documents : FriendDocuments {
        val rows = linkedMapOf<String, Map<String, Any?>>()
        var unavailable = false
        override suspend fun uid(username: String) = mapOf("kevin" to "k", "stuart" to "s", "bob" to "b")[username]
        override suspend fun list(path: String): List<Map<String, Any?>> {
            check(!unavailable)
            return rows.filterKeys { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }.values.toList()
        }
        override suspend fun mergeAll(documents: Map<String, Map<String, Any?>>) {
            check(!unavailable)
            documents.forEach { (p, d) -> rows[p] = rows[p].orEmpty() + d }
        }
        override suspend fun transaction(block: (FriendDocuments.Transaction) -> Boolean): Boolean {
            check(!unavailable)
            return block(object : FriendDocuments.Transaction {
                override fun get(path: String) = rows[path]
                override fun merge(path: String, data: Map<String, Any?>) { rows[path] = rows[path].orEmpty() + data }
            })
        }
    }
    private class Source(val store: FirebaseFriendsStore) : FriendsDataSource {
        var liveRoster: ((FriendsBackup) -> Unit)? = null
        var liveProfile: ((PlayerRegistryEntry) -> Unit)? = null
        var closed = false
        override fun watchFriends(uid: String, onRoster: (FriendsBackup) -> Unit, onProfile: (PlayerRegistryEntry) -> Unit): AutoCloseable {
            liveRoster = onRoster; liveProfile = onProfile
            return AutoCloseable { closed = true }
        }
        val backups = mutableListOf<List<Friend>>()
        var backupSucceeds = true
        var backupGate: CompletableDeferred<Unit>? = null
        override suspend fun fetchFriends(username: String) = store.fetchFriends(username)
        override suspend fun fetchBackup(uid: String) = FriendsBackup(emptyList())
        override suspend fun fetchRequests(username: String) = store.fetchRequests(username)
        override suspend fun outgoingStatuses(username: String, targets: Set<String>) = store.outgoingStatuses(username, targets)
        override suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String?) = store.saveFriends(username, friends)
        override suspend fun removeFriend(username: String, friendIdentifier: String) = store.removeFriend(username, friendIdentifier)
        override fun saveBackup(uid: String, friends: List<Friend>) { backups += friends }
        override suspend fun saveBackupConfirmed(uid: String, friends: List<Friend>): Boolean {
            backups += friends
            backupGate?.await()
            return backupSucceeds
        }
        override fun removeFromBackup(uid: String, friend: Friend, removalId: String) {}
        override suspend fun send(request: FriendRequest) = store.addRequest(request.toUsername, request)
        override suspend fun accept(request: FriendRequest, user: UserProfile) = store.accept(request, user)
        override suspend fun decline(request: FriendRequest) = store.decline(request)
        override fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit) = AutoCloseable {}
        override suspend fun refreshPresence(usernames: List<String>) {}
    }
    private val kevin = UserProfile("k", "kevin", "Kevin")

    @Test fun liveAdminFriendAddRemoveRestoreAndProfileResetReachTheSameRepositoryList() = runTest {
        val source = Source(FirebaseFriendsStore(Documents()))
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), source, { kevin }, this)
        val listener = repo.startListeningForRequests("kevin", {}, {})
        val stuart = Friend("s", "stuart", "Stuart", gamesPlayed=50, gamesWon=40, level=50)
        source.liveRoster!!(FriendsBackup(listOf(stuart)))
        assertTrue(repo.isFriend("stuart"))
        source.liveProfile!!(PlayerRegistryEntry("stuart", "s", "New Stuart", gamesPlayed=1, gamesWon=0, level=1, adminRevision=2))
        assertEquals(1, repo.friends.value.single().level)
        assertEquals(1, repo.friends.value.single().gamesPlayed)
        source.liveRoster!!(FriendsBackup(listOf(stuart)))
        assertEquals("An older roster card must not restore pre-reset statistics", 1, repo.friends.value.single().level)
        source.liveRoster!!(FriendsBackup(emptyList(), removedKeys=friendKeys(stuart)))
        assertFalse(repo.isFriend("stuart"))
        source.liveRoster!!(FriendsBackup(listOf(stuart), restoredKeys=friendKeys(stuart)))
        assertTrue(repo.isFriend("stuart"))
        listener.close(); assertTrue(source.closed)
        source.liveRoster!!(FriendsBackup(emptyList(), removedKeys=friendKeys(stuart)))
        assertTrue("Detached listeners cannot change the roster", repo.isFriend("stuart"))
    }

    @Test fun declinedOutgoingRequestClearsAfterReconciliationWithoutAnMqttPacket() = runTest {
        val store = FirebaseFriendsStore(Documents())
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), Source(store), { kevin }, this)
        assertTrue(repo.sendFriendRequest("stuart", kevin))
        assertTrue(store.decline(store.fetchRequests("stuart")!!.single()))
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertFalse(repo.hasSentRequest("stuart"))
    }

    @Test fun restartRetainsPendingAndLaterDiscoversAcceptanceWithoutMqtt() = runTest {
        val store = FirebaseFriendsStore(Documents())
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        val original = FriendsRepository(prefs, Source(store), { kevin }, this)
        assertTrue(original.sendFriendRequest("stuart", kevin))
        val restarted = FriendsRepository(prefs, Source(store), { kevin }, this)
        assertTrue(restarted.hasSentRequest("stuart"))
        val req = store.fetchRequests("stuart")!!.single()
        assertTrue(store.accept(req, UserProfile("s", "stuart", "Stuart")))
        restarted.syncFriendsAndRequests("kevin"); runCurrent()
        assertFalse(restarted.hasSentRequest("stuart"))
        assertTrue(restarted.isFriend("stuart"))
    }

    @Test fun unchangedFriendsAreBackedUpOnceRatherThanEveryPoll() = runTest {
        val store = FirebaseFriendsStore(Documents())
        store.saveFriends("kevin", (1..4).map { Friend("f$it", "friend$it", "Friend $it") })
        val source = Source(store)
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), source, { kevin }, this)
        repeat(20) { repo.syncFriendsAndRequests("kevin"); runCurrent() }
        assertEquals(1, source.backups.size)
        assertEquals(4, source.backups.single().size)
    }

    @Test fun failedBackupRetriesUntilSuccessfulThenStops() = runTest {
        val store = FirebaseFriendsStore(Documents())
        store.saveFriends("kevin", listOf(Friend("s", "stuart", "Stuart")))
        val source = Source(store).apply { backupSucceeds = false }
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), source, { kevin }, this)
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        source.backupSucceeds = true
        repeat(3) { repo.syncFriendsAndRequests("kevin"); runCurrent() }
        assertEquals(2, source.backups.size)
    }

    @Test fun failedFirebaseReadPreservesPendingAndAccountSwitchKeepsItScoped() = runTest {
        val docs = Documents(); val store = FirebaseFriendsStore(docs)
        val prefs = AuthRepositoryTest.FakeSharedPreferences()
        var user: UserProfile? = kevin
        val repo = FriendsRepository(prefs, Source(store), { user }, this)
        assertTrue(repo.sendFriendRequest("stuart", kevin))
        docs.unavailable = true
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertTrue(repo.hasSentRequest("stuart"))
        user = UserProfile("b", "bob", "Bob"); repo.setAccount(user)
        assertFalse(repo.hasSentRequest("stuart"))
        user = kevin; repo.setAccount(user)
        assertTrue(repo.hasSentRequest("stuart"))
    }

    @Test fun openProfileObservesSendAcceptanceRemovalAndAccountSwitch() = runTest {
        val store = FirebaseFriendsStore(Documents())
        var user: UserProfile? = kevin
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), Source(store), { user }, this)
        val statuses = mutableListOf<FriendshipStatus>()
        backgroundScope.launch { repo.observeFriendship("s", "@Stuart").collect { statuses += it } }
        runCurrent()
        assertEquals(FriendshipStatus.NONE, statuses.last())
        assertTrue(repo.sendFriendRequest("stuart", kevin)); runCurrent()
        assertEquals(FriendshipStatus.PENDING, statuses.last())
        assertTrue(store.accept(store.fetchRequests("stuart")!!.single(), UserProfile("s", "stuart", "Stuart")))
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertEquals(FriendshipStatus.FRIEND, statuses.last())
        repo.removeFriend("stuart"); runCurrent()
        assertEquals(FriendshipStatus.NONE, statuses.last())
        user = UserProfile("b", "bob", "Bob"); repo.setAccount(user); runCurrent()
        assertEquals(FriendshipStatus.NONE, statuses.last())
    }

    @Test fun oneChangedCardWritesOnlyThatCardAndPresenceNeverTriggersBackup() = runTest {
        val store = FirebaseFriendsStore(Documents())
        val stuart = Friend("s", "stuart", "Stuart")
        store.saveFriends("kevin", listOf(stuart, Friend("b", "bob", "Bob")))
        val source = Source(store)
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), source, { kevin }, this)
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        store.saveFriends("kevin", listOf(stuart.copy(gamesPlayed = 1)))
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertEquals(2, source.backups.size)
        assertEquals(listOf("s"), source.backups.last().map { it.uid })
        assertTrue(source.backups.flatten().all { !it.isOnline && it.lastSeenTimestamp == 0L })
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertEquals(2, source.backups.size)
    }

    @Test fun failedUnknownRecipientDoesNotBlockLaterDeclineReconciliation() = runTest {
        val store = FirebaseFriendsStore(Documents())
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), Source(store), { kevin }, this)
        assertFalse(repo.sendFriendRequest("unknown_user", kevin))
        assertTrue(repo.sendFriendRequest("stuart", kevin))
        assertTrue(store.decline(store.fetchRequests("stuart")!!.single()))
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertFalse(repo.hasSentRequest("stuart"))
    }

    @Test fun unacknowledgedBackupCannotHoldTheSocialMutexIndefinitely() = runTest {
        val store = FirebaseFriendsStore(Documents())
        store.saveFriends("kevin", listOf(Friend("b", "bob", "Bob")))
        val source = Source(store).apply { backupGate = CompletableDeferred() }
        val repo = FriendsRepository(AuthRepositoryTest.FakeSharedPreferences(), source, { kevin }, this)
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        advanceTimeBy(8001L); runCurrent()
        source.backupGate = null
        assertTrue(repo.sendFriendRequest("stuart", kevin))
        repo.syncFriendsAndRequests("kevin"); runCurrent()
        assertEquals(2, source.backups.size)
    }
}
