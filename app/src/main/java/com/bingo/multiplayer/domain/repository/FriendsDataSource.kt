package com.bingo.multiplayer.domain.repository

import android.content.Context
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
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
    suspend fun fetchBackup(uid: String): FriendsBackup?
    suspend fun fetchRequests(username: String): List<FriendRequest>?
    suspend fun saveFriends(username: String, friends: List<Friend>): Boolean
    fun saveBackup(uid: String, friends: List<Friend>)
    fun removeFromBackup(uid: String, friend: Friend, removalId: String)
    suspend fun send(request: FriendRequest): Boolean
    suspend fun accept(request: FriendRequest, user: UserProfile): Boolean
    suspend fun decline(request: FriendRequest): Boolean
    fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit): AutoCloseable
    suspend fun refreshPresence(usernames: List<String>)
}

internal class NetworkFriendsDataSource(context: Context) : FriendsDataSource {
    private val firestore = FirestoreSyncManager.getInstance(context)
    override suspend fun fetchFriends(username: String) = FriendRequestManager.fetchCloudFriends(username)
    override suspend fun fetchBackup(uid: String) = firestore.fetchFriendsList(uid)
    override suspend fun fetchRequests(username: String) = FriendRequestManager.fetchRequestsForUserOrNull(username)
    override suspend fun saveFriends(username: String, friends: List<Friend>) =
        FriendRequestManager.saveCloudFriends(username, friends)
    override fun saveBackup(uid: String, friends: List<Friend>) = firestore.syncFriendsList(uid, friends)
    override fun removeFromBackup(uid: String, friend: Friend, removalId: String) = firestore.markFriendRemoved(uid, friend, removalId)
    override suspend fun send(request: FriendRequest) = FriendRequestManager.sendFriendRequest(request)
    override suspend fun accept(request: FriendRequest, user: UserProfile) =
        FriendRequestManager.acceptFriendRequest(request, user)
    override suspend fun decline(request: FriendRequest) = FriendRequestManager.declineFriendRequest(request)
    override fun listen(username: String, onPacket: (FriendRequestPacket) -> Unit) =
        FriendRequestManager.startRequestListener(username, onPacket)
    override suspend fun refreshPresence(usernames: List<String>) {
        PresenceManager.fetchCloudPresenceForUsers(usernames)
    }
}
