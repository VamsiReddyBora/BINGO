package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Firebase persists social state; MQTT carries only the live notification. */
internal interface FriendDocuments {
    interface Transaction {
        fun get(path: String): Map<String, Any?>?
        fun merge(path: String, data: Map<String, Any?>)
    }
    suspend fun uid(username: String): String?
    suspend fun list(path: String): List<Map<String, Any?>>
    suspend fun get(path: String): Map<String, Any?>? {
        var result: Map<String, Any?>? = null
        transaction { result = it.get(path); true }
        return result
    }
    suspend fun mergeAll(documents: Map<String, Map<String, Any?>>)
    suspend fun transaction(block: (Transaction) -> Boolean): Boolean
}

internal class FirebaseFriendsStore(private val documents: FriendDocuments) {
    private fun clean(value: String) = value.trim().removePrefix("@").lowercase()
    private suspend fun uid(username: String) = documents.uid(clean(username))?.takeIf { it.isNotBlank() }
    private fun friendPath(owner: String, other: String) = "users/$owner/friends/$other"
    private fun requestPath(owner: String, sender: String) = "users/$owner/friendRequests/$sender"
    private suspend fun <T> read(block: suspend () -> T): T? = try { withTimeoutOrNull(8_000L) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    private suspend fun write(block: suspend () -> Boolean): Boolean = read(block) ?: false

    suspend fun fetchFriends(username: String): List<Friend>? = read {
        val owner = uid(username) ?: return@read null
        documents.list("users/$owner/friends").filter { it["removed"] != true }.mapNotNull(::friend)
    }
    suspend fun fetchFriendsWithVersion(username: String): FriendsCloudStore.VersionedSnapshot<Friend>? =
        fetchFriends(username)?.let { FriendsCloudStore.VersionedSnapshot(it, "") }
    suspend fun fetchRequests(username: String): List<FriendRequest>? = read {
        val owner = uid(username) ?: return@read null
        documents.list("users/$owner/friendRequests").mapNotNull(::request)
            .filter { it.status == FriendRequestStatus.PENDING && clean(it.toUsername) == clean(username) }
    }
    suspend fun outgoingStatuses(username: String, targets: Set<String>): Map<String, FriendRequestStatus?>? = read {
        val sender = uid(username) ?: return@read null
        targets.associateWith { target ->
            val owner = uid(target) ?: error("Recipient lookup unavailable")
            documents.get(requestPath(owner, sender))?.let(::request)?.takeIf {
                it.fromUid == sender && clean(it.toUsername) == clean(target)
            }?.status
        }
    }
    suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String? = null): Boolean = write {
        val owner = uid(username) ?: return@write false
        // Merge individual cards. Reconciliation never clears removal markers or deletes absent cards.
        documents.mergeAll(friends.filter { it.uid.isNotBlank() || clean(it.username).isNotBlank() }.associate {
            friendPath(owner, it.uid.ifBlank { clean(it.username) }) to card(it)
        })
        true
    }
    suspend fun addRequest(username: String, value: FriendRequest): Boolean = write {
        val target = uid(username) ?: return@write false
        if (value.fromUid.isBlank() || value.id.isBlank() || target == value.fromUid || value.timestamp <= 0L ||
            value.status != FriendRequestStatus.PENDING || clean(value.toUsername) != clean(username)) return@write false
        documents.mergeAll(mapOf(requestPath(target, value.fromUid) to requestData(value, target)))
        true
    }
    suspend fun accept(value: FriendRequest, profile: UserProfile): Boolean = write {
        if (clean(profile.username) != clean(value.toUsername) || profile.uid == value.fromUid) return@write false
        val target = profile.uid
        val incoming = requestPath(target, value.fromUid)
        val mine = friendPath(target, value.fromUid)
        val theirs = friendPath(value.fromUid, target)
        documents.transaction { tx ->
            val raw = tx.get(incoming) ?: return@transaction false
            val stored = request(raw) ?: return@transaction false
            if (stored.id != value.id || stored.fromUid != value.fromUid || raw["toUid"] != target ||
                clean(stored.fromUsername) != clean(value.fromUsername) || clean(stored.toUsername) != clean(profile.username)) return@transaction false
            val oldMine = tx.get(mine)
            val oldTheirs = tx.get(theirs)
            if (stored.status == FriendRequestStatus.ACCEPTED) {
                return@transaction oldMine != null && oldTheirs != null && oldMine["removed"] != true && oldTheirs["removed"] != true
            }
            if (stored.status != FriendRequestStatus.PENDING) return@transaction false
            if (listOf(oldMine, oldTheirs).any { it?.get("removed") == true &&
                    stored.timestamp <= ((it["removedAt"] as? Number)?.toLong() ?: 0L) }) return@transaction false
            val sender = Friend(stored.fromUid, stored.fromUsername, stored.fromDisplayName, stored.fromAvatarUrl)
            val acceptor = Friend(profile.uid, profile.username, profile.displayName,
                profile.avatarBase64?.takeIf { it.isNotBlank() } ?: profile.avatarUrl)
            tx.merge(mine, card(sender) + mapOf("removed" to false, "restoredRemovalId" to oldMine?.get("removalId")))
            tx.merge(theirs, card(acceptor) + mapOf("removed" to false, "restoredRemovalId" to oldTheirs?.get("removalId")))
            tx.merge(incoming, mapOf("status" to FriendRequestStatus.ACCEPTED.name))
            true
        }
    }
    suspend fun decline(value: FriendRequest): Boolean = write {
        val target = uid(value.toUsername) ?: return@write false
        val path = requestPath(target, value.fromUid)
        documents.transaction { tx ->
            val stored = tx.get(path)?.let(::request) ?: return@transaction false
            if (stored.id != value.id || stored.fromUid != value.fromUid || stored.status == FriendRequestStatus.ACCEPTED) return@transaction false
            if (stored.status == FriendRequestStatus.PENDING) tx.merge(path, mapOf("status" to FriendRequestStatus.DECLINED.name))
            true
        }
    }
    suspend fun removeFriend(username: String, identifier: String): Boolean = write {
        val owner = uid(username) ?: return@write false
        val existing = documents.list("users/$owner/friends").firstOrNull {
            it["uid"] == identifier || clean(it["username"] as? String ?: "") == clean(identifier)
        } ?: return@write true
        val other = (existing["uid"] as? String)?.takeIf { it.isNotBlank() }
            ?: clean(existing["username"] as? String ?: "").takeIf { it.isNotBlank() } ?: return@write false
        documents.mergeAll(mapOf(friendPath(owner, other) to mapOf("removed" to true,
            "removedAt" to System.currentTimeMillis(), "removalId" to java.util.UUID.randomUUID().toString())))
        true
    }
    private fun card(f: Friend): Map<String, Any?> = mapOf("uid" to f.uid, "username" to clean(f.username),
        "displayName" to f.displayName.take(50), "avatarUrl" to f.avatarUrl?.takeIf { !isLocalFilePath(it) && it.length <= 200000 },
        "gamesPlayed" to f.gamesPlayed, "gamesWon" to f.gamesWon, "currentStreak" to f.currentStreak, "level" to f.level)
    private fun friend(raw: Map<String, Any?>): Friend? {
        val id = raw["uid"] as? String ?: return null
        val name = raw["username"] as? String ?: return null
        return Friend(id, name, raw["displayName"] as? String ?: name, raw["avatarUrl"] as? String,
            gamesPlayed = (raw["gamesPlayed"] as? Number)?.toInt() ?: 0,
            gamesWon = (raw["gamesWon"] as? Number)?.toInt() ?: 0,
            currentStreak = (raw["currentStreak"] as? Number)?.toInt() ?: 0,
            level = (raw["level"] as? Number)?.toInt() ?: 1)
    }
    private fun requestData(r: FriendRequest, target: String): Map<String, Any?> = mapOf(
        "id" to r.id, "fromUid" to r.fromUid, "fromUsername" to clean(r.fromUsername),
        "fromDisplayName" to r.fromDisplayName.take(80), "fromAvatarUrl" to r.fromAvatarUrl?.takeIf { !isLocalFilePath(it) && it.length <= 200000 },
        "toUid" to target, "toUsername" to clean(r.toUsername), "status" to r.status.name, "timestamp" to r.timestamp)
    private fun request(raw: Map<String, Any?>): FriendRequest? {
        val id = raw["id"] as? String ?: return null
        val from = raw["fromUid"] as? String ?: return null
        val sender = raw["fromUsername"] as? String ?: return null
        val target = raw["toUsername"] as? String ?: return null
        val state = runCatching { FriendRequestStatus.valueOf(raw["status"] as? String ?: "") }.getOrNull() ?: return null
        return FriendRequest(id, from, sender, raw["fromDisplayName"] as? String ?: sender,
            raw["fromAvatarUrl"] as? String, target, state, (raw["timestamp"] as? Number)?.toLong() ?: 0L)
    }
}
