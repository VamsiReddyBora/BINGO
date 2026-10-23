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
    companion object {
        private val recentFriendAdds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArraySet<Friend>>()
        private val recentRequestAdds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArraySet<FriendRequest>>()
    }

    data class VersionedSnapshot<T>(val items: List<T>, val version: String)
    data class VersionedTombstones(val entries: Map<String, Long>, val version: String)

    @androidx.annotation.VisibleForTesting
    internal var beforeCompensationHook: (suspend () -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val rosterJson = Json { encodeDefaults = false }
    private fun cleanUsername(raw: String) = raw.trim().removePrefix("@").lowercase()

    suspend fun fetchFriends(username: String): List<Friend>? = readList("friends_${cleanUsername(username)}")

    suspend fun fetchFriendsWithVersion(username: String): VersionedSnapshot<Friend>? =
        readListWithVersion("friends_${cleanUsername(username)}")

    suspend fun fetchRequests(username: String): List<FriendRequest>? =
        readList<FriendRequest>("freq_${cleanUsername(username)}")?.filter { it.status == FriendRequestStatus.PENDING }

    suspend fun fetchRequestsWithVersion(username: String): VersionedSnapshot<FriendRequest>? {
        val snapshot = readListWithVersion<FriendRequest>("freq_${cleanUsername(username)}") ?: return null
        return VersionedSnapshot(snapshot.items.filter { it.status == FriendRequestStatus.PENDING }, snapshot.version)
    }

    suspend fun fetchTombstoneEntries(username: String): Map<String, Long> {
        val list = readList<String>("ftomb_${cleanUsername(username)}") ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (item in list) {
            val idx = item.lastIndexOf(':')
            if (idx > 0 && idx < item.length - 1) {
                val key = item.substring(0, idx).trim().removePrefix("@").lowercase()
                val ts = item.substring(idx + 1).toLongOrNull() ?: 0L
                result[key] = maxOf(result[key] ?: 0L, ts)
            } else {
                val key = item.trim().removePrefix("@").lowercase()
                result[key] = maxOf(result[key] ?: 0L, 0L)
            }
        }
        return result
    }

    suspend fun fetchTombstones(username: String): Set<String> =
        fetchTombstoneEntries(username).keys

    suspend fun fetchTombstoneEntriesWithVersion(username: String): VersionedTombstones {
        val cleanUser = cleanUsername(username)
        val versioned = readListWithVersion<String>("ftomb_$cleanUser") ?: VersionedSnapshot(emptyList(), "")
        val map = mutableMapOf<String, Long>()
        for (item in versioned.items) {
            val idx = item.lastIndexOf(':')
            if (idx > 0 && idx < item.length - 1) {
                val key = item.substring(0, idx).trim().removePrefix("@").lowercase()
                val ts = item.substring(idx + 1).toLongOrNull() ?: 0L
                map[key] = maxOf(map[key] ?: 0L, ts)
            } else if (item.isNotBlank()) {
                val key = item.trim().removePrefix("@").lowercase()
                map[key] = maxOf(map[key] ?: 0L, 0L)
            }
        }
        return VersionedTombstones(map, versioned.version)
    }

    suspend fun recordTombstone(username: String, friendId: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val cleanUser = cleanUsername(username)
        val cleanFriend = cleanUsername(friendId)
        for (attempt in 0..4) {
            val versioned = fetchTombstoneEntriesWithVersion(username)
            val current = versioned.entries.toMutableMap()
            current[cleanFriend] = timestamp
            val list = current.map { "${it.key}:${it.value}" }
            val ok = writeList("ftomb_$cleanUser", list, expectedVersion = versioned.version)
            if (ok) return true
            kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
        }
        return false
    }

    suspend fun removeTombstone(username: String, friendId: String, maxTombstoneTimestamp: Long? = null): Boolean {
        val cleanUser = cleanUsername(username)
        val cleanFriend = cleanUsername(friendId)
        for (attempt in 0..4) {
            val versioned = fetchTombstoneEntriesWithVersion(username)
            val current = versioned.entries.toMutableMap()
            val existingTime = current[cleanFriend] ?: current[friendId]
            if (existingTime == null) return true
            if (maxTombstoneTimestamp != null && existingTime > maxTombstoneTimestamp) {
                // A newer removal occurred after this acceptance was validated! Preserve newer tombstone.
                return false
            }
            val removed = current.remove(cleanFriend) != null || current.remove(friendId) != null
            if (!removed) return true
            val list = current.map { "${it.key}:${it.value}" }
            val ok = writeList("ftomb_$cleanUser", list, expectedVersion = versioned.version)
            if (ok) return true
            kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
        }
        return false
    }

    suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String? = null): Boolean {
        val userKey = cleanUsername(username)
        val safe = friends.map { friend ->
            friend.copy(avatarUrl = friend.avatarUrl?.takeIf { it.length <= 500 && !isLocalFilePath(it) })
        }
        var payload = rosterJson.encodeToString(safe)
        if (URLEncoder.encode(encode(payload), "UTF-8").length > 1024) {
            payload = rosterJson.encodeToString(safe.map {
                Friend(uid = it.uid, username = it.username, displayName = it.displayName)
            })
        }
        return writePayload("friends_${userKey}", payload, expected = expectedVersion)
    }

    suspend fun saveRequests(username: String, requests: List<FriendRequest>, expectedVersion: String? = null): Boolean =
        writeList("freq_${cleanUsername(username)}", requests.map { request ->
            request.copy(fromAvatarUrl = request.fromAvatarUrl?.takeIf { it.length <= 500 && !isLocalFilePath(it) })
        }, expectedVersion = expectedVersion)

    suspend fun addFriend(username: String, friend: Friend, requestTimestamp: Long = 0L): Boolean {
        val userKey = cleanUsername(username)
        val fKey = if (friend.uid.isNotBlank()) friend.uid else cleanUsername(friend.username)
        recentFriendAdds.computeIfAbsent(userKey) { java.util.concurrent.CopyOnWriteArraySet() }.add(friend)

        val friendToSave = if (friend.generation.isBlank()) {
            friend.copy(
                generation = java.util.UUID.randomUUID().toString(),
                addedAt = if (friend.addedAt > 0L) friend.addedAt else System.currentTimeMillis()
            )
        } else friend

        try {
            val targetKeys = setOfNotNull(
                fKey,
                cleanUsername(fKey),
                friendToSave.uid.takeIf { it.isNotBlank() },
                friendToSave.username.takeIf { it.isNotBlank() },
                cleanUsername(friendToSave.username).takeIf { it.isNotBlank() }
            )
            val tombstoneEntries = fetchTombstoneEntries(username)
            val matchingTombstoneTime = targetKeys.mapNotNull { tombstoneEntries[it] }.maxOrNull()
            if (matchingTombstoneTime != null) {
                // When a removal exists, require a demonstrably newer request timestamp before restoring
                if (requestTimestamp <= 0L || requestTimestamp <= matchingTombstoneTime) {
                    return false
                }
            }

            for (attempt in 0..4) {
                val versioned = fetchFriendsWithVersion(username) ?: return false
                val remote = versioned.items
                val currentVersion = versioned.version
                val currentTombstoneEntries = fetchTombstoneEntries(username)
                val currentTombstoneTime = targetKeys.mapNotNull { currentTombstoneEntries[it] }.maxOrNull()
                if (currentTombstoneTime != null && (requestTimestamp <= 0L || requestTimestamp <= currentTombstoneTime)) {
                    return false
                }
                val tombstones = currentTombstoneEntries.keys - targetKeys
                val filteredRemote = remote.filterNot { r ->
                    val rKey = if (r.uid.isNotBlank()) r.uid else cleanUsername(r.username)
                    tombstones.contains(rKey)
                }

                // Pre-flight check: if remote changed concurrently, merge fresh items so both survive
                var currentRemoteVersion = currentVersion
                val freshSnapshot = fetchFriendsWithVersion(username)
                val baseList = if (freshSnapshot != null && freshSnapshot.version != currentVersion) {
                    currentRemoteVersion = freshSnapshot.version
                    val freshFiltered = freshSnapshot.items.filterNot { r ->
                        val rKey = if (r.uid.isNotBlank()) r.uid else cleanUsername(r.username)
                        tombstones.contains(rKey)
                    }
                    (freshFiltered + filteredRemote).distinctBy { if (it.uid.isNotBlank()) it.uid else cleanUsername(it.username) }
                } else {
                    filteredRemote
                }

                val inFlight = (recentFriendAdds[userKey]?.toList() ?: emptyList()).filterNot { f ->
                    val inflightKey = if (f.uid.isNotBlank()) f.uid else cleanUsername(f.username)
                    tombstones.contains(inflightKey)
                }
                val nextList = (baseList + inFlight + friendToSave).distinctBy { if (it.uid.isNotBlank()) it.uid else cleanUsername(it.username) }
                val ok = saveFriends(username, nextList, expectedVersion = currentRemoteVersion)
                if (!ok) {
                    kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
                    continue
                }

                val versionedSnapshot = fetchFriendsWithVersion(username) ?: return false
                val readBack = versionedSnapshot.items
                val hasSelf = readBack.any { (it.uid.isNotBlank() && it.uid == friendToSave.uid) ||
                    (it.username.isNotBlank() && cleanUsername(it.username) == cleanUsername(friendToSave.username)) }
                if (hasSelf) {
                    val maxTombstone = currentTombstoneTime ?: matchingTombstoneTime ?: requestTimestamp
                    var retainedNewerTombstone = false
                    targetKeys.forEach { key ->
                        val okRemove = removeTombstone(username, key, maxTombstoneTimestamp = maxTombstone)
                        if (!okRemove) {
                            val latestEntries = fetchTombstoneEntries(username)
                            val latestTime = targetKeys.mapNotNull { latestEntries[it] }.maxOrNull()
                            if (latestTime != null && latestTime > maxTombstone) {
                                retainedNewerTombstone = true
                            }
                        }
                    }
                    if (retainedNewerTombstone) {
                        beforeCompensationHook?.invoke()
                        val latestEntries = fetchTombstoneEntries(username)
                        val stillHasNewerTombstone = targetKeys.any { k ->
                            val t = latestEntries[k]
                            t != null && t > maxTombstone
                        }
                        if (stillHasNewerTombstone) {
                            val currentRemote = fetchFriendsWithVersion(username)
                            val currentItem = currentRemote?.items?.find {
                                (it.uid.isNotBlank() && it.uid == friendToSave.uid) ||
                                (it.username.isNotBlank() && cleanUsername(it.username) == cleanUsername(friendToSave.username))
                            }
                            if (currentItem != null && (friendToSave.generation.isBlank() || currentItem.generation == friendToSave.generation)) {
                                val pruned = currentRemote.items.filterNot {
                                    targetKeys.contains(it.uid) || targetKeys.contains(cleanUsername(it.username))
                                }
                                saveFriends(username, pruned, expectedVersion = currentRemote.version)
                            }
                        }
                        return false
                    }
                    return true
                }
                kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
            }
            return false
        } finally {
            recentFriendAdds[userKey]?.remove(friendToSave)
            recentFriendAdds[userKey]?.remove(friend)
        }
    }

    suspend fun removeFriend(username: String, friendIdentifier: String): Boolean {
        val userKey = cleanUsername(username)
        val cleanId = cleanUsername(friendIdentifier)
        recentFriendAdds[userKey]?.removeIf { (it.uid.isNotBlank() && it.uid.equals(cleanId, true)) || cleanUsername(it.username).equals(cleanId, true) }
        recordTombstone(username, cleanId)

        for (attempt in 0..4) {
            val versioned = fetchFriendsWithVersion(username) ?: return false
            val remote = versioned.items
            val currentVersion = versioned.version
            val filtered = remote.filterNot { f ->
                (f.uid.isNotBlank() && f.uid.equals(cleanId, true)) ||
                (f.username.isNotBlank() && cleanUsername(f.username).equals(cleanId, true))
            }
            val ok = saveFriends(username, filtered, expectedVersion = currentVersion)
            if (!ok) {
                kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
                continue
            }
            val readBack = fetchFriends(username) ?: return false
            val stillPresent = readBack.any { f ->
                (f.uid.isNotBlank() && f.uid.equals(cleanId, true)) ||
                (f.username.isNotBlank() && cleanUsername(f.username).equals(cleanId, true))
            }
            if (!stillPresent) return true
        }
        return false
    }

    suspend fun addRequest(targetUsername: String, request: FriendRequest): Boolean {
        val userKey = cleanUsername(targetUsername)
        recentRequestAdds.computeIfAbsent(userKey) { java.util.concurrent.CopyOnWriteArraySet() }.add(request)
        try {
            for (attempt in 0..4) {
                val versioned = fetchRequestsWithVersion(targetUsername) ?: return false
                val remote = versioned.items
                val currentVersion = versioned.version
                val filtered = remote.filterNot {
                    it.id == request.id || cleanUsername(it.fromUsername) == cleanUsername(request.fromUsername)
                }
                val inFlight = recentRequestAdds[userKey]?.toList() ?: emptyList()
                val nextList = (filtered + inFlight + request).distinctBy {
                    if (it.id.isNotBlank()) it.id else cleanUsername(it.fromUsername)
                }
                val ok = saveRequests(targetUsername, nextList, expectedVersion = currentVersion)
                if (!ok) {
                    kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
                    continue
                }

                val readBack = fetchRequests(targetUsername) ?: return false
                val hasSelf = readBack.any {
                    it.id == request.id || cleanUsername(it.fromUsername) == cleanUsername(request.fromUsername)
                }
                if (hasSelf) return true
                kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
            }
            return false
        } finally {
            recentRequestAdds[userKey]?.remove(request)
        }
    }

    suspend fun removeRequest(username: String, requestId: String, fromUsername: String? = null): Boolean {
        val cleanFrom = fromUsername?.let { cleanUsername(it) }
        for (attempt in 0..4) {
            val versioned = fetchRequestsWithVersion(username) ?: return false
            val remote = versioned.items
            val currentVersion = versioned.version
            val filtered = remote.filterNot { r ->
                r.id == requestId || (!cleanFrom.isNullOrBlank() && cleanUsername(r.fromUsername) == cleanFrom)
            }
            val ok = saveRequests(username, filtered, expectedVersion = currentVersion)
            if (!ok) {
                kotlinx.coroutines.delay((50L * (attempt + 1)).coerceAtMost(250L))
                continue
            }
            val readBack = fetchRequests(username) ?: return false
            val stillPresent = readBack.any { r ->
                r.id == requestId || (!cleanFrom.isNullOrBlank() && cleanUsername(r.fromUsername) == cleanFrom)
            }
            if (!stillPresent) return true
        }
        return false
    }

    private suspend inline fun <reified T> readList(key: String): List<T>? = withContext(Dispatchers.IO) {
        readListWithVersion<T>(key)?.items
    }

    suspend inline fun <reified T> readListWithVersion(key: String): VersionedSnapshot<T>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/GetValue/$apiKey/$key").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string()?.trim()?.removeSurrounding("\"") ?: return@withContext null
                if (raw.isBlank() || raw == "null") return@withContext VersionedSnapshot(emptyList(), raw)
                val payload = if (raw.startsWith("[")) raw else decode(raw)
                val items = json.decodeFromString<List<T>>(payload)
                VersionedSnapshot(items, raw)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> writeList(key: String, list: List<T>, expectedVersion: String? = null): Boolean =
        writePayload(key, json.encodeToString(list), expected = expectedVersion)

    suspend fun writePayload(key: String, payload: String, expected: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val value = URLEncoder.encode(encode(payload), "UTF-8")
            val urlBuilder = StringBuilder("$baseUrl/UpdateValue/$apiKey/$key?value=$value")
            if (expected != null) {
                val encExp = URLEncoder.encode(expected, "UTF-8")
                urlBuilder.append("&expected=").append(encExp)
            }
            val reqBuilder = Request.Builder().url(urlBuilder.toString())
                .post("".toRequestBody(null))
            if (expected != null) {
                reqBuilder.header("If-Match", expected)
            }
            val request = reqBuilder.build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
