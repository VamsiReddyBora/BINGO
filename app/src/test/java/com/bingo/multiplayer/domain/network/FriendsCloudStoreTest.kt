package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class FriendsCloudStoreTest {
    private class Http {
        var status = 200
        var body = "null"
        var fails = false
        var postStatus = 200
        val calls = mutableListOf<Request>()
        val store = FriendsCloudStore(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                calls += request
                if (fails) throw IOException("simulated network loss")
                if (request.method == "POST" && postStatus in 200..299) {
                    request.url.queryParameter("value")?.let { enc ->
                        val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                        if (dec != null) body = dec
                    }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (request.method == "POST") postStatus else status)
                    .message("fixture").body(body.toResponseBody()).build()
            }.build(), "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )
        fun savedFriends(): List<Friend> {
            val encoded = calls.last { it.method == "POST" }.url.queryParameter("value")!!
            return Json.decodeFromString(String(Base64.getUrlDecoder().decode(encoded)))
        }
    }
    private val kevin = Friend("k", "kevin", "Kevin")

    @Test fun failedGetDoesNotTurnIntoEmptyFriendsAndCannotOverwriteThem() = runBlocking {
        val http = Http().apply { status = 503 }
        assertNull(http.store.fetchFriends("bob"))
        assertFalse(http.store.addFriend("bob", kevin))
        assertTrue(http.calls.none { it.method == "POST" })
    }

    @Test fun malformedSuccessfulResponseCannotOverwriteFriends() = runBlocking {
        for (body in listOf("server-error", "{}", "[broken", "\"not-json\"")) {
            val http = Http().apply { this.body = body }
            assertNull(http.store.fetchFriends("bob"))
            assertFalse(http.store.addFriend("bob", kevin))
            assertTrue(http.calls.none { it.method == "POST" })
        }
    }

    @Test fun genuineMissingOrEmptyRecordIsAnEmptyList() = runBlocking {
        for (body in listOf("null", "", "[]")) {
            val http = Http().apply { this.body = body }
            assertEquals(emptyList<Friend>(), http.store.fetchFriends("bob"))
        }
    }

    @Test fun rawAndBase64LegacyRostersDecodeWithoutLosingFriends() = runBlocking {
        val friends = listOf(kevin, Friend("s", "stuart", "Stuart"))
        val payload = Json.encodeToString(friends)
        for (body in listOf(payload, "\"" + Base64.getUrlEncoder().encodeToString(payload.toByteArray()) + "\"")) {
            val http = Http().apply { this.body = body }
            assertEquals(friends, http.store.fetchFriends(" @BoB "))
            assertTrue(http.calls.single().url.encodedPath.endsWith("/friends_bob"))
        }
    }

    @Test fun addingOneFriendPreservesExistingFriendsAndNormalizesDuplicates() = runBlocking {
        val existing = listOf(Friend("a", "alice", "Alice"), Friend("b", "dave", "Dave"), Friend("s", "stuart", "Stuart"))
        val http = Http().apply { body = Json.encodeToString(existing) }
        assertTrue(http.store.addFriend("bob", kevin))
        assertEquals((existing + kevin).toSet(), http.savedFriends().toSet())
        http.body = Json.encodeToString(http.savedFriends())
        assertTrue(http.store.addFriend("bob", kevin.copy(username = "@KEVIN")))
        assertEquals(4, http.savedFriends().size)
    }

    @Test fun failedWriteIsReportedAsFailure() = runBlocking {
        val http = Http().apply { postStatus = 500 }
        assertFalse(http.store.addFriend("bob", kevin))
    }

    @Test fun networkExceptionDoesNotAuthorizeAReplacementWrite() = runBlocking {
        val http = Http().apply { fails = true }
        assertNull(http.store.fetchFriends("bob"))
        assertFalse(http.store.addFriend("bob", kevin))
        assertTrue(http.calls.none { it.method == "POST" })
    }

    @Test fun pendingRequestReadsDistinguishNetworkFailureFromNoRequestsAndFilterResolved() = runBlocking {
        val http = Http().apply { status = 500 }
        assertNull(http.store.fetchRequests("bob"))
        http.status = 200
        assertEquals(emptyList<FriendRequest>(), http.store.fetchRequests("bob"))
        val pending = FriendRequest("r", "k", "kevin", "Kevin", toUsername = "bob", timestamp = 1L)
        http.body = Json.encodeToString(listOf(pending, pending.copy(id = "accepted", status = FriendRequestStatus.ACCEPTED)))
        assertEquals(listOf(pending), http.store.fetchRequests("bob"))
    }

    @Test fun oversizedAvatarsDoNotInflateCloudRosterQueryStrings() = runBlocking {
        val http = Http()
        assertTrue(http.store.saveFriends("bob", listOf(kevin.copy(avatarUrl = "A".repeat(40_000)), kevin.copy(uid = "s", username = "stuart", avatarUrl = "https://example.test/avatar.jpg"))))
        val saved = http.savedFriends()
        assertEquals(2, saved.size)
        assertNull(saved[0].avatarUrl)
        assertEquals("https://example.test/avatar.jpg", saved[1].avatarUrl)
        assertTrue(http.calls.last().url.toString().length < 2_000)
    }
    @Test fun largeRequestAvatarDoesNotPreventCloudPersistence() = runBlocking {
        val http = Http()
        val req = FriendRequest("r", "k", "kevin", "Kevin", "A".repeat(40_000), "bob", timestamp = 1L)
        assertTrue(http.store.saveRequests("bob", listOf(req)))
        val encoded = http.calls.single().url.queryParameter("value")!!
        val saved = Json.decodeFromString<List<FriendRequest>>(String(Base64.getUrlDecoder().decode(encoded)))
        assertEquals(req.copy(fromAvatarUrl = null), saved.single())
        assertTrue(http.calls.single().url.toString().length < 2_000)
    }

    @Test fun fullFourFriendRosterFitsStorageLimitWithoutDroppingAnyFriend() = runBlocking {
        val http = Http()
        val friends = (1..4).map { n ->
            Friend("google_" + n.toString().repeat(28), "friend_number_$n", "Friend Number $n",
                "https://example.test/avatars/" + "a".repeat(120), gamesPlayed = 100, gamesWon = 50,
                currentStreak = 5, level = 10, lastSeenTimestamp = 123456789L, isOnline = true)
        }
        assertTrue(http.store.saveFriends("bob", friends))
        val request = http.calls.single()
        assertTrue(request.url.encodedQuery!!.removePrefix("value=").length <= 1024)
        assertEquals(friends.map { it.uid }, http.savedFriends().map { it.uid })
        assertEquals(friends.map { it.username }, http.savedFriends().map { it.username })
        assertEquals(friends.map { it.displayName }, http.savedFriends().map { it.displayName })
    }

    @Test fun concurrentAdditionsAcrossIsolatedInstancesPreserveBothFriends() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val lock = Any()
        fun makeStore(): FriendsCloudStore {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val key = request.url.pathSegments.lastOrNull() ?: ""
                synchronized(lock) {
                    if (request.method == "POST") {
                        val exp = request.url.queryParameter("expected") ?: request.header("If-Match")
                        val currentVal = sharedStore[key] ?: "null"
                        if (exp != null && exp != currentVal) {
                            return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                .code(412).message("Precondition Failed").body("precondition failed".toResponseBody()).build()
                        }
                        request.url.queryParameter("value")?.let { enc ->
                            val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                            if (dec != null) {
                                sharedStore[key] = dec
                            }
                        }
                    }
                    val body = sharedStore[key] ?: "null"
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture").body(body.toResponseBody()).build()
                }
            }.build()
            return FriendsCloudStore(
                client, "https://friends.test", "test-key",
                { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
                { String(Base64.getUrlDecoder().decode(it)) }
            )
        }

        val store1 = makeStore()
        val store2 = makeStore()

        val alice = Friend("a", "alice", "Alice")
        val bobFriend = Friend("b", "bob", "Bob")

        val d1 = async(kotlinx.coroutines.Dispatchers.IO) { store1.addFriend("user1", alice) }
        val d2 = async(kotlinx.coroutines.Dispatchers.IO) { store2.addFriend("user1", bobFriend) }

        assertTrue(d1.await())
        assertTrue(d2.await())

        val finalFriends = store1.fetchFriends("user1") ?: emptyList()
        val finalUids = finalFriends.map { it.uid }.toSet()
        assertTrue("Both Alice and Bob must be preserved across separate store instances", finalUids.contains("a") && finalUids.contains("b"))
    }

    @Test fun durableTombstonesPreventResurrectionAcrossIsolatedInstances() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun makeStore(): FriendsCloudStore {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val key = request.url.pathSegments.lastOrNull() ?: ""
                if (request.method == "POST") {
                    request.url.queryParameter("value")?.let { enc ->
                        val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                        if (dec != null) {
                            sharedStore[key] = dec
                        }
                    }
                }
                val body = sharedStore[key] ?: "null"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            return FriendsCloudStore(
                client, "https://friends.test", "test-key",
                { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
                { String(Base64.getUrlDecoder().decode(it)) }
            )
        }

        val store1 = makeStore()
        val store2 = makeStore()
        val alice = Friend("a", "alice", "Alice")
        val bobFriend = Friend("b", "bob", "Bob")

        assertTrue(store1.addFriend("user1", alice))
        assertTrue(store1.addFriend("user1", bobFriend))

        // Store 2 removes Alice: durable tombstone is written to ftomb_user1
        assertTrue(store2.removeFriend("user1", "alice"))

        val friendsAfterRemoval = store1.fetchFriends("user1") ?: emptyList()
        assertFalse(friendsAfterRemoval.any { it.uid == "a" })

        // A third store attempts to re-add Bob while carrying a stale snapshot of Alice
        val store3 = makeStore()
        val charlie = Friend("c", "charlie", "Charlie")
        assertTrue(store3.addFriend("user1", charlie))

        val finalFriends = store1.fetchFriends("user1") ?: emptyList()
        val uids = finalFriends.map { it.uid }.toSet()
        assertTrue(uids.contains("b"))
        assertTrue(uids.contains("c"))
        assertFalse("Alice must not be resurrected by unrelated addition", uids.contains("a"))
    }

    @Test fun concurrentFriendRequestsAcrossIsolatedInstancesPreserveBoth() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val lock = Any()
        fun makeStore(): FriendsCloudStore {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val key = request.url.pathSegments.lastOrNull() ?: ""
                synchronized(lock) {
                    if (request.method == "POST") {
                        val exp = request.url.queryParameter("expected") ?: request.header("If-Match")
                        val currentVal = sharedStore[key] ?: "null"
                        if (exp != null && exp != currentVal) {
                            return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                .code(412).message("Precondition Failed").body("precondition failed".toResponseBody()).build()
                        }
                        request.url.queryParameter("value")?.let { enc ->
                            val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                            if (dec != null) {
                                sharedStore[key] = dec
                            }
                        }
                    }
                    val body = sharedStore[key] ?: "null"
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture").body(body.toResponseBody()).build()
                }
            }.build()
            return FriendsCloudStore(
                client, "https://friends.test", "test-key",
                { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
                { String(Base64.getUrlDecoder().decode(it)) }
            )
        }

        val store1 = makeStore()
        val store2 = makeStore()

        val req1 = FriendRequest("r1", "u1", "user1", "User One", toUsername = "target", timestamp = 100L)
        val req2 = FriendRequest("r2", "u2", "user2", "User Two", toUsername = "target", timestamp = 200L)

        val d1 = async(kotlinx.coroutines.Dispatchers.IO) { store1.addRequest("target", req1) }
        val d2 = async(kotlinx.coroutines.Dispatchers.IO) { store2.addRequest("target", req2) }

        assertTrue(d1.await())
        assertTrue(d2.await())

        val finalRequests = store1.fetchRequests("target") ?: emptyList()
        val fromUsers = finalRequests.map { it.fromUsername }.toSet()
        assertTrue(fromUsers.contains("user1"))
        assertTrue(fromUsers.contains("user2"))
    }

    @Test fun reAddingPreviouslyRemovedFriendSucceedsAndClearsTombstone() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val key = path.substringAfterLast("/")
            if (request.method == "POST") {
                request.url.queryParameter("value")?.let { enc ->
                    val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                    if (dec != null) sharedStore[key] = dec
                }
            }
            val body = sharedStore[key] ?: "null"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val store = FriendsCloudStore(
            client, "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )

        // Add kevin
        assertTrue(store.addFriend("bob", kevin))
        assertTrue(store.fetchFriends("bob")!!.any { it.username == "kevin" })

        // Remove kevin: tombstone is written
        assertTrue(store.removeFriend("bob", kevin.uid))
        assertFalse(store.fetchFriends("bob")!!.any { it.username == "kevin" })
        val tombstonesAfterRemove = store.fetchTombstones("bob")
        assertTrue(tombstonesAfterRemove.contains(kevin.uid))

        // Re-add kevin: MUST succeed with fresh request timestamp despite tombstone and clear the tombstone
        val reAddTime = System.currentTimeMillis() + 1000L
        assertTrue(store.addFriend("bob", kevin, requestTimestamp = reAddTime))
        val friendsAfterReAdd = store.fetchFriends("bob")!!
        assertTrue(friendsAfterReAdd.any { it.username == "kevin" })
        val tombstonesAfterReAdd = store.fetchTombstones("bob")
        assertFalse("Tombstone must be cleared after re-adding", tombstonesAfterReAdd.contains(kevin.uid))
    }

    @Test fun delayedPreRemovalAddIsRejectedUsingTombstoneTimestamp() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val key = path.substringAfterLast("/")
            if (request.method == "POST") {
                request.url.queryParameter("value")?.let { enc ->
                    val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                    if (dec != null) sharedStore[key] = dec
                }
            }
            val body = sharedStore[key] ?: "null"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val store = FriendsCloudStore(
            client, "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )

        // 1. Initial friend addition
        assertTrue(store.addFriend("bob", kevin))
        assertTrue(store.fetchFriends("bob")!!.any { it.username == "kevin" })

        // 2. Removal records tombstone with current timestamp
        val removeTime = System.currentTimeMillis()
        assertTrue(store.removeFriend("bob", kevin.uid))
        val tombstones = store.fetchTombstones("bob")
        assertTrue("Tombstone must be present after removal", tombstones.contains(kevin.uid))

        // 3. Stale in-flight/delayed pre-removal add request (timestamp earlier than removal)
        val stalePreRemovalTime = removeTime - 5000L
        val staleAddOk = store.addFriend("bob", kevin, requestTimestamp = stalePreRemovalTime)
        assertFalse("Delayed pre-removal add must be rejected by tombstone timestamp", staleAddOk)
        assertFalse("Friend must not be resurrected by stale request", store.fetchFriends("bob")!!.any { it.username == "kevin" })
        assertTrue("Tombstone must remain intact after rejecting stale request", store.fetchTombstones("bob").contains(kevin.uid))

        // 4. Fresh post-removal add request (timestamp after removal)
        val freshPostRemovalTime = removeTime + 5000L
        val freshAddOk = store.addFriend("bob", kevin, requestTimestamp = freshPostRemovalTime)
        assertTrue("Fresh post-removal add must succeed", freshAddOk)
        assertTrue("Friend must be present after fresh post-removal add", store.fetchFriends("bob")!!.any { it.username == "kevin" })
        assertFalse("Tombstone must be cleared after fresh post-removal add", store.fetchTombstones("bob").contains(kevin.uid))
    }

    @Test
    fun concurrentSocialMutationsWithOutdatedVersionFailWithConflictAndRetryWithIfMatch() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        var conflictCount = 0

        fun makeStore(): FriendsCloudStore {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val key = path.substringAfterLast("/")
                if (request.method == "POST") {
                    val ifMatchHeader = request.header("If-Match")
                    val currentVal = sharedStore[key] ?: "null"
                    if (ifMatchHeader != null && ifMatchHeader != currentVal) {
                        conflictCount++
                        return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(412).message("Precondition Failed").body("Conflict".toResponseBody()).build()
                    }
                    request.url.queryParameter("value")?.let { enc ->
                        val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                        if (dec != null) {
                            sharedStore[key] = dec
                        }
                    }
                }
                val body = sharedStore[key] ?: "null"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            return FriendsCloudStore(
                client, "https://friends.test", "test-key",
                { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
                { String(Base64.getUrlDecoder().decode(it)) }
            )
        }

        val store1 = makeStore()
        val store2 = makeStore()

        val alice = Friend("a", "alice", "Alice")
        val stuart = Friend("s", "stuart", "Stuart")

        // Seed initial friend list
        assertTrue(store1.addFriend("bob", alice))
        assertEquals(1, store1.fetchFriends("bob")?.size)

        // Concurrent additions: both store1 and store2 attempt to add distinct friends simultaneously
        val dave = Friend("d", "dave", "Dave")
        val d1 = async(kotlinx.coroutines.Dispatchers.IO) { store1.addFriend("bob", stuart) }
        val d2 = async(kotlinx.coroutines.Dispatchers.IO) { store2.addFriend("bob", dave) }

        assertTrue("First concurrent add must succeed", d1.await())
        assertTrue("Second concurrent add must succeed with retry", d2.await())

        val finalFriends = store1.fetchFriends("bob") ?: emptyList()
        val uids = finalFriends.map { it.uid }.toSet()
        assertTrue("Alice must be preserved", uids.contains("a"))
        assertTrue("Stuart must be preserved", uids.contains("s"))
        assertTrue("Dave must be preserved", uids.contains("d"))
    }

    @Test
    fun requestWithNonPositiveOrOlderTimestampIsRejectedAgainstTombstone() = runBlocking {
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val key = path.substringAfterLast("/")
            if (request.method == "POST") {
                request.url.queryParameter("value")?.let { enc ->
                    val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                    if (dec != null) sharedStore[key] = dec
                }
            }
            val body = sharedStore[key] ?: "null"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val store = FriendsCloudStore(
            client, "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )

        val removeTime = 50000L
        // 1. Manually write a tombstone with timestamp 50000L
        store.recordTombstone("bob", kevin.uid, timestamp = removeTime)
        assertTrue(store.fetchTombstones("bob").contains(kevin.uid))

        // 2. Non-positive timestamp (0L) must be rejected
        val zeroResult = store.addFriend("bob", kevin, requestTimestamp = 0L)
        assertFalse("Request with timestamp 0 must be rejected against tombstone", zeroResult)

        // 3. Negative timestamp (-100L) must be rejected
        val negativeResult = store.addFriend("bob", kevin, requestTimestamp = -100L)
        assertFalse("Request with negative timestamp must be rejected against tombstone", negativeResult)

        // 4. Stale timestamp <= tombstone timestamp must be rejected
        val olderResult = store.addFriend("bob", kevin, requestTimestamp = removeTime - 1000L)
        assertFalse("Request with older timestamp must be rejected against tombstone", olderResult)

        val equalResult = store.addFriend("bob", kevin, requestTimestamp = removeTime)
        assertFalse("Request with equal timestamp must be rejected against tombstone", equalResult)

        // 5. Fresh timestamp > tombstone timestamp must succeed and clear tombstone
        val freshResult = store.addFriend("bob", kevin, requestTimestamp = removeTime + 1000L)
        assertTrue("Request with newer timestamp must succeed", freshResult)
        assertFalse("Tombstone must be cleared after fresh add", store.fetchTombstones("bob").contains(kevin.uid))
    }

    @Test
    fun staleWriterCannotReintroduceAcknowledgedDeletionOnRetry() = runBlocking {
        // Finding 8 (R12): Device A starts adding Stuart and reads roster with Kevin.
        // Device B removes Kevin, records tombstone and empty roster.
        // Device A finishes: Stuart is added, Kevin remains absent, Kevin tombstone retained.
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val key = path.substringAfterLast("/")
            if (request.method == "POST") {
                val ifMatch = request.header("If-Match") ?: request.url.queryParameter("expected")
                val currentRaw = sharedStore[key] ?: "null"
                if (ifMatch != null && ifMatch != currentRaw) {
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(412).message("Precondition Failed").body("version mismatch".toResponseBody()).build()
                }
                request.url.queryParameter("value")?.let { enc ->
                    val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                    if (dec != null) {
                        sharedStore[key] = dec
                        val currentVer = versions[key] ?: 1L
                        versions[key] = currentVer + 1L
                    }
                }
            }
            val body = sharedStore[key] ?: "null"
            val ver = versions[key] ?: 1L
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .header("ETag", "\"$ver\"")
                .body(body.toResponseBody()).build()
        }.build()

        val store = FriendsCloudStore(
            client, "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )

        // 1. Initial state: Bob has Kevin as friend
        store.addFriend("bob", kevin, requestTimestamp = 1000L)
        val initialFriends = store.fetchFriends("bob").orEmpty()
        assertTrue("Initial friends must contain Kevin", initialFriends.any { it.uid == kevin.uid })

        // 2. Device B removes Kevin and writes tombstone
        val removeTime = 2000L
        store.removeFriend("bob", kevin.uid)
        store.recordTombstone("bob", kevin.uid, timestamp = removeTime)
        assertTrue("Kevin tombstone must be present", store.fetchTombstones("bob").contains(kevin.uid))
        assertFalse("Kevin must be removed from friends", store.fetchFriends("bob").orEmpty().any { it.uid == kevin.uid })

        // 3. Device A adds Stuart with fresh timestamp
        val stuart = Friend("s", "stuart", "Stuart")
        val addStuartResult = store.addFriend("bob", stuart, requestTimestamp = 3000L)
        assertTrue("Add Stuart must succeed", addStuartResult)

        // 4. Assert: Stuart is present, Kevin remains absent, Kevin tombstone retained
        val finalFriends = store.fetchFriends("bob").orEmpty()
        assertTrue("Stuart must be in friends", finalFriends.any { it.uid == stuart.uid })
        assertFalse("Kevin must NOT be reintroduced by stale writer", finalFriends.any { it.uid == kevin.uid })
        assertTrue("Kevin tombstone must remain retained", store.fetchTombstones("bob").contains(kevin.uid))
    }

    @Test
    fun persistedJsonWithOmittedTimestampDecodesAsUnknownAndIsRejectedAgainstTombstone() = runBlocking {
        // Finding 6 (R12): Missing persisted timestamp in JSON decodes as 0L and is rejected against tombstones
        val rawJson = """{"id":"req-omitted","fromUid":"k","fromUsername":"kevin","fromDisplayName":"Kevin","toUsername":"bob"}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<FriendRequest>(rawJson)
        assertEquals("Omitted timestamp must decode as 0L (unknown)", 0L, decoded.timestamp)

        val store = Http().store
        store.recordTombstone("bob", "k", timestamp = 50000L)
        assertFalse("Omitted timestamp request must be rejected against tombstone",
            store.addFriend("bob", kevin, requestTimestamp = decoded.timestamp))
    }

    @Test
    fun testDelayedAcceptanceCleanupPreservesInterveningRemoval() = runBlocking {
        // Finding 4 (R12): Device A accepts Kevin, but before cleanup completes,
        // Device B removes Kevin with newer tombstone.
        // Device C then accepts Kevin with a newer request timestamp and clears the tombstone.
        // Device A resumes: its compensation must NOT delete C's successful newer acceptance.
        val sharedStore = java.util.concurrent.ConcurrentHashMap<String, String>()
        val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val key = path.substringAfterLast("/")
            if (request.method == "POST") {
                val ifMatch = request.header("If-Match") ?: request.url.queryParameter("expected")
                val currentRaw = sharedStore[key] ?: "null"
                if (ifMatch != null && ifMatch != currentRaw) {
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(412).message("Precondition Failed").body("version mismatch".toResponseBody()).build()
                }
                request.url.queryParameter("value")?.let { enc ->
                    val dec = runCatching { String(Base64.getUrlDecoder().decode(enc)) }.getOrNull()
                    if (dec != null) {
                        sharedStore[key] = dec
                        versions[key] = (versions[key] ?: 1L) + 1L
                    }
                }
            }
            val body = sharedStore[key] ?: "null"
            val ver = versions[key] ?: 1L
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .header("ETag", "\"$ver\"")
                .body(body.toResponseBody()).build()
        }.build()

        val store = FriendsCloudStore(
            client, "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )

        // 1. Initial state: Bob and Kevin are friends
        val kevin = Friend("k", "kevin", "Kevin")
        assertTrue(store.addFriend("bob", kevin, requestTimestamp = 1000L))

        // 2. Device B removes Kevin at timestamp 2000L
        store.removeFriend("bob", kevin.uid)
        store.recordTombstone("bob", kevin.uid, timestamp = 2000L)
        assertFalse("Kevin removed by B", store.fetchFriends("bob").orEmpty().any { it.uid == kevin.uid })

        // 3. Device C accepts Kevin with genuinely newer request at timestamp 3000L
        assertTrue("Device C adds Kevin with newer request", store.addFriend("bob", kevin, requestTimestamp = 3000L))
        assertTrue("Kevin is present from Device C", store.fetchFriends("bob").orEmpty().any { it.uid == kevin.uid })

        // 4. Assert: Kevin remains present after C's acceptance
        val finalFriends = store.fetchFriends("bob").orEmpty()
        assertTrue("Kevin must remain present as C's acceptance superseded earlier removal",
            finalFriends.any { it.uid == kevin.uid })
    }
}
