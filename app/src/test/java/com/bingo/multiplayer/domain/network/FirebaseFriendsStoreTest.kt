package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FirebaseFriendsStoreTest {
    private class Memory : FriendDocuments {
        val values = linkedMapOf<String, Map<String, Any?>>()
        var fail = false
        override suspend fun uid(username: String) = mapOf("kevin" to "k", "stuart" to "s", "bob" to "b")[username]
        override suspend fun list(path: String): List<Map<String, Any?>> {
            if (fail) throw IOException("Firebase unavailable")
            return values.filterKeys { it.startsWith("$path/") && it.removePrefix("$path/").contains('/').not() }.values.toList()
        }
        override suspend fun mergeAll(documents: Map<String, Map<String, Any?>>) {
            if (fail) throw IOException("Firebase unavailable")
            documents.forEach { (path, data) -> values[path] = values[path].orEmpty() + data }
        }
        override suspend fun transaction(block: (FriendDocuments.Transaction) -> Boolean): Boolean {
            val copy = values.toMutableMap(); var wrote = false
            val result = block(object : FriendDocuments.Transaction {
                override fun get(path: String): Map<String, Any?>? {
                    check(!wrote) { "Firestore requires all reads before writes" }; return copy[path]
                }
                override fun merge(path: String, data: Map<String, Any?>) {
                    wrote = true; copy[path] = copy[path].orEmpty() + data
                }
            })
            if (fail) throw IOException("Commit failed")
            values.clear(); values.putAll(copy)
            return result
        }
    }
    private val memory = Memory()
    private val store = FirebaseFriendsStore(memory)
    private val kevin = Friend("k", "kevin", "Kevin")
    private val stuart = UserProfile("s", "stuart", "Stuart")
    private fun request(id: String = "request-one", at: Long = System.currentTimeMillis()) =
        FriendRequest(id,"k","kevin","Kevin",toUsername="stuart",timestamp=at)

    @Test fun sendingAndFetchingUsesFirebaseDocumentsWithoutAnyKeyValueRead() = runBlocking {
        val req = request()
        assertTrue(store.addRequest("@Stuart",req))
        assertEquals(setOf("users/s/friendRequests/k"),memory.values.keys)
        assertEquals(listOf(req),store.fetchRequests("stuart"))
    }
    @Test fun resendingReplacesOnlyTheSameSenderAndOlderAcceptCannotResolveIt() = runBlocking {
        val old = request();val fresh = request("request-two")
        assertTrue(store.addRequest("stuart",old));assertTrue(store.addRequest("stuart",fresh))
        assertEquals(1,store.fetchRequests("stuart")!!.size)
        assertFalse(store.accept(old,stuart));assertEquals(listOf(fresh),store.fetchRequests("stuart"))
    }
    @Test fun acceptingAtomicallySavesBothFriendsAndResolvesTheRequest() = runBlocking {
        val req=request();assertTrue(store.addRequest("stuart",req));assertTrue(store.accept(req,stuart))
        assertEquals(listOf("k"),store.fetchFriends("stuart")!!.map { it.uid })
        assertEquals(listOf("s"),store.fetchFriends("kevin")!!.map { it.uid })
        assertTrue(store.fetchRequests("stuart")!!.isEmpty())
        assertTrue(store.accept(req,stuart))
    }
    @Test fun failedAcceptanceCommitLeavesBothListsAndRequestUntouched() = runBlocking {
        val req=request();assertTrue(store.addRequest("stuart",req));val before=memory.values.toMap()
        memory.fail=true;assertFalse(store.accept(req,stuart));assertEquals(before,memory.values)
        memory.fail=false;assertEquals(listOf(req),store.fetchRequests("stuart"))
    }
    @Test fun declinePersistsAndAnOldAcceptanceCannotRestoreTheRequest() = runBlocking {
        val req=request();assertTrue(store.addRequest("stuart",req));assertTrue(store.decline(req))
        assertTrue(store.fetchRequests("stuart")!!.isEmpty());assertFalse(store.accept(req,stuart))
        assertTrue(store.addRequest("stuart",request("fresh")));assertEquals("fresh",store.fetchRequests("stuart")!!.single().id)
    }
    @Test fun removalMarkerSurvivesStaleRosterReconciliation() = runBlocking {
        assertTrue(store.saveFriends("stuart",listOf(kevin)))
        assertTrue(store.removeFriend("stuart","kevin"))
        assertTrue(store.saveFriends("stuart",listOf(kevin)))
        assertTrue(store.fetchFriends("stuart")!!.isEmpty())
        assertEquals(true,memory.values["users/s/friends/k"]!!["removed"])
    }
    @Test fun oldPendingRequestCannotUndoALaterRemoval() = runBlocking {
        val req=request(at=1000L);assertTrue(store.addRequest("stuart",req))
        memory.values["users/s/friends/k"]=mapOf("uid" to "k","username" to "kevin","removed" to true,"removedAt" to 2000L)
        assertFalse(store.accept(req,stuart));assertEquals(listOf(req),store.fetchRequests("stuart"))
    }
    @Test fun freshExplicitAcceptanceRestoresTheExactRemovalGeneration() = runBlocking {
        memory.values["users/s/friends/k"]=mapOf("uid" to "k","username" to "kevin","removed" to true,"removedAt" to 1000L,"removalId" to "remove-one")
        val req=request(at=2000L);assertTrue(store.addRequest("stuart",req));assertTrue(store.accept(req,stuart))
        assertEquals("remove-one",memory.values["users/s/friends/k"]!!["restoredRemovalId"])
        assertEquals(false,memory.values["users/s/friends/k"]!!["removed"])
    }
    @Test fun handledAcceptanceCannotUndoRemovalAfterTheFriendshipWasAccepted() = runBlocking {
        val req=request();assertTrue(store.addRequest("stuart",req));assertTrue(store.accept(req,stuart))
        assertTrue(store.removeFriend("stuart","kevin"));assertFalse(store.accept(req,stuart))
        assertTrue(store.fetchFriends("stuart")!!.isEmpty())
    }
    @Test fun unrelatedFriendsAreNeverDeletedBySavingAnOlderSnapshot() = runBlocking {
        assertTrue(store.saveFriends("bob",listOf(kevin,Friend("s","stuart","Stuart"))))
        assertTrue(store.saveFriends("bob",listOf(kevin)))
        assertEquals(setOf("k","s"),store.fetchFriends("bob")!!.map { it.uid }.toSet())
    }
    @Test fun missingAccountOrUnavailableFirebaseReturnsFailureWithoutPartialMutation() = runBlocking {
        assertFalse(store.addRequest("unknown",request()));assertTrue(memory.values.isEmpty())
        memory.fail=true;assertFalse(store.addRequest("stuart",request()));assertTrue(memory.values.isEmpty())
        assertNull(store.fetchFriends("stuart"));assertNull(store.fetchRequests("stuart"))
    }
    @Test fun productionManagerPersistsBeforePublishingAnMqttNotice() = runBlocking {
        val wire=EmqxWireFixture();val previousWire=EmqxTransport.wireOverride
        val previousStore=FriendRequestManager.storeOverride
        EmqxTransport.wireOverride=wire;FriendRequestManager.storeOverride=store
        try {
            val req=request()
            assertTrue(FriendRequestManager.sendFriendRequest(req))
            FriendRequestManager.awaitActiveJobs()
            assertEquals(req.id,memory.values["users/s/friendRequests/k"]!!["id"])
            assertTrue(wire.published.contains("bingo/v3/friend_requests/stuart"))
        } finally {
            FriendRequestManager.awaitActiveJobs();FriendRequestManager.storeOverride=previousStore;EmqxTransport.wireOverride=previousWire
        }
    }
    @Test fun productionManagerDoesNotAnnounceAnUnsavedRequest() = runBlocking {
        val wire=EmqxWireFixture();val previousWire=EmqxTransport.wireOverride
        val previousStore=FriendRequestManager.storeOverride
        EmqxTransport.wireOverride=wire;FriendRequestManager.storeOverride=store;memory.fail=true
        try {
            assertFalse(FriendRequestManager.sendFriendRequest(request()))
            FriendRequestManager.awaitActiveJobs();assertTrue(wire.published.isEmpty());assertTrue(memory.values.isEmpty())
        } finally {
            FriendRequestManager.awaitActiveJobs();FriendRequestManager.storeOverride=previousStore;EmqxTransport.wireOverride=previousWire
        }
    }
    @Test fun legacyUsernameOnlyFriendIsPreservedAndCanStillBeRemoved() = runBlocking {
        val old=Friend("","old_friend","Old friend")
        assertTrue(store.saveFriends("bob",listOf(old)))
        assertEquals(listOf("old_friend"),store.fetchFriends("bob")!!.map { it.username })
        assertTrue(store.removeFriend("bob","old_friend"));assertTrue(store.fetchFriends("bob")!!.isEmpty())
    }

    @Test fun backgroundNotificationsResolveOwnUidWithoutConstructingAuthRepository() {
        val account=com.bingo.multiplayer.domain.notifications.NotificationAccount("s","stuart","generation","installation")
        assertEquals("s",socialAccountUid("stuart",account,null))
        assertNull(socialAccountUid("kevin",account,null))
        assertEquals("s",socialAccountUid("stuart",null,stuart))
    }

}
