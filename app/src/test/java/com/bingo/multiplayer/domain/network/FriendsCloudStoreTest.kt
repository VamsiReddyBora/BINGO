package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestStatus
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
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (request.method == "POST") postStatus else status)
                    .message("fixture").body(body.toResponseBody()).build()
            }.build(), "https://friends.test", "test-key",
            { Base64.getUrlEncoder().encodeToString(it.toByteArray()) },
            { String(Base64.getUrlDecoder().decode(it)) }
        )
        fun savedFriends(): List<Friend> {
            val encoded = calls.last().url.queryParameter("value")!!
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

}
