package com.bingo.multiplayer.domain.repository

import android.content.Context
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.FirestoreSyncManager
import com.bingo.multiplayer.domain.network.FriendRequestManager
import com.bingo.multiplayer.domain.network.PresenceManager

internal data class FriendsBackup(
    val friends: List<Friend>,
    val removedKeys: Set<String> = emptySet(),
    val restoredKeys: Set<String> = emptySet(),
    val restoredRemovalIds: Set<String> = emptySet()
)

internal fun friendUsername(value: String) = value.trim().removePrefix("@").lowercase()

internal fun friendKeys(friend: Friend): Set<String> = buildSet {
    if (friend.uid.isNotBlank()) add("uid:${friend.uid}")
    if (friend.username.isNotBlank()) add("name:${friendUsername(friend.username)}")
}

/** Ports keep reconciliation tests on the production repository without contacting real accounts. */
internal interface FriendsDataSource {
    suspend fun fetchFriends(username: String): List<Friend>?
    suspend fun fetchFriendsWithVersion(username: String): com.bingo.multiplayer.domain.network.FriendsCloudStore.VersionedSnapshot<Friend>? =
        fetchFriends(username)?.let { com.bingo.multiplayer.domain.network.FriendsCloudStore.VersionedSnapshot(it, "") }
    suspend fun fetchBackup(uid: String): FriendsBackup?
    suspend fun fetchRequests(username: String): List<FriendRequest>?
    suspend fun outgoingStatuses(username: String, targets: Set<String>): Map<String, FriendRequestStatus?>? = null
    suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String? = null): Boolean
    suspend fun saveFriends(username: String, friends: List<Friend>): Boolean = saveFriends(username, friends, null)
    suspend fun removeFriend(username: String, friendIdentifier: String): Boolean
    fun saveBackup(uid: String, friends: List<Friend>)
    suspend fun saveBackupConfirmed(uid: String, friends: List<Friend>): Boolean {
        saveBackup(uid, friends)
        return true
    }
    fun removeFromBackup(uid: String, friend: Friend, removalId: String)
    suspend fun send(request: FriendRequest): Boolean
    suspend fun accept(request: FriendRequest, user: UserProfile): Boolean
    suspend fun decline(request: FriendRequest): Boolean
    fun watchFriends(uid: String, onRoster: (FriendsBackup) -> Unit,
        onProfile: (com.bingo.multiplayer.domain.network.PlayerRegistryEntry) -> Unit): AutoCloseable = AutoCloseable {}
    fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit): AutoCloseable
    suspend fun refreshPresence(usernames: List<String>)
}

internal class NetworkFriendsDataSource(context: Context) : FriendsDataSource {
    private val firestore = FirestoreSyncManager.getInstance(context)
    override suspend fun fetchFriends(username: String) = FriendRequestManager.fetchCloudFriends(username)
    override suspend fun fetchFriendsWithVersion(username: String) =
        FriendRequestManager.fetchCloudFriendsWithVersion(username)
    override suspend fun fetchBackup(uid: String) = firestore.fetchFriendsList(uid)
    override suspend fun fetchRequests(username: String) = FriendRequestManager.fetchRequestsForUserOrNull(username)
    override suspend fun outgoingStatuses(username: String, targets: Set<String>) =
        FriendRequestManager.outgoingStatuses(username, targets)
    override suspend fun saveFriends(username: String, friends: List<Friend>, expectedVersion: String?) =
        FriendRequestManager.saveCloudFriends(username, friends, expectedVersion)
    override suspend fun saveFriends(username: String, friends: List<Friend>) =
        saveFriends(username, friends, null)
    override fun saveBackup(uid: String, friends: List<Friend>) = firestore.syncFriendsList(uid, friends)
    override suspend fun saveBackupConfirmed(uid: String, friends: List<Friend>) = firestore.syncFriendsListConfirmed(uid, friends)
    override fun removeFromBackup(uid: String, friend: Friend, removalId: String) = firestore.markFriendRemoved(uid, friend, removalId)
    override suspend fun removeFriend(username: String, friendIdentifier: String): Boolean =
        FriendRequestManager.removeCloudFriend(username, friendIdentifier)
    override suspend fun send(request: FriendRequest) = FriendRequestManager.sendFriendRequest(request)
    override suspend fun accept(request: FriendRequest, user: UserProfile) =
        FriendRequestManager.acceptFriendRequest(request, user)
    override suspend fun decline(request: FriendRequest) = FriendRequestManager.declineFriendRequest(request)
    override fun watchFriends(uid: String, onRoster: (FriendsBackup) -> Unit,
        onProfile: (com.bingo.multiplayer.domain.network.PlayerRegistryEntry) -> Unit) = firestore.watchFriends(uid, onRoster, onProfile)
    override fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit) =
        FriendRequestManager.startRequestListener(username, onPacket)
    override suspend fun refreshPresence(usernames: List<String>) {
        PresenceManager.fetchCloudPresenceForUsers(usernames)
    }
}
