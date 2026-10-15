package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** A failed/unreadable GET is distinct from a successful empty list. */
internal class FriendsCloudStore(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val encode: (String) -> String,
    private val decode: (String) -> String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val rosterJson = Json { encodeDefaults = false }
    private fun cleanUsername(raw: String) = raw.trim().removePrefix("@").lowercase()

    suspend fun fetchFriends(username: String): List<Friend>? = readList("friends_${cleanUsername(username)}")
    suspend fun fetchRequests(username: String): List<FriendRequest>? =
        readList<FriendRequest>("freq_${cleanUsername(username)}")?.filter { it.status == FriendRequestStatus.PENDING }

    suspend fun saveFriends(username: String, friends: List<Friend>): Boolean {
        val safe = friends.map { friend ->
            friend.copy(avatarUrl = friend.avatarUrl?.takeIf { it.length <= 500 && !isLocalFilePath(it) })
        }
        var payload = rosterJson.encodeToString(safe)
        if (URLEncoder.encode(encode(payload), "UTF-8").length > 1024) {
            // The existing backup retains full profiles. Fit the roster in KeyValue's query limit
            // without truncating friends or changing the legacy base64/JSON format.
            payload = rosterJson.encodeToString(safe.map {
                Friend(uid = it.uid, username = it.username, displayName = it.displayName)
            })
        }
        return writePayload("friends_${cleanUsername(username)}", payload)
    }

    suspend fun saveRequests(username: String, requests: List<FriendRequest>): Boolean =
        writeList("freq_${cleanUsername(username)}", requests.map { request ->
            request.copy(fromAvatarUrl = request.fromAvatarUrl?.takeIf { it.length <= 500 && !isLocalFilePath(it) })
        })

    suspend fun addFriend(username: String, friend: Friend): Boolean {
        val current = fetchFriends(username)?.toMutableList() ?: return false
        current.removeAll { (it.uid.isNotBlank() && it.uid == friend.uid) ||
            (it.username.isNotBlank() && cleanUsername(it.username) == cleanUsername(friend.username)) }
        current.add(0, friend)
        return saveFriends(username, current)
    }

    private suspend inline fun <reified T> readList(key: String): List<T>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/GetValue/$apiKey/$key").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string()?.trim()?.removeSurrounding("\"") ?: return@withContext null
                if (raw.isBlank() || raw == "null") return@withContext emptyList()
                val payload = if (raw.startsWith("[")) raw else decode(raw)
                json.decodeFromString<List<T>>(payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> writeList(key: String, list: List<T>): Boolean =
        writePayload(key, json.encodeToString(list))

    private suspend fun writePayload(key: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val value = URLEncoder.encode(encode(payload), "UTF-8")
            val request = Request.Builder().url("$baseUrl/UpdateValue/$apiKey/$key?value=$value")
                .post("".toRequestBody(null)).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
