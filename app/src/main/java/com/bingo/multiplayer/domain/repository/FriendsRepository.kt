package com.bingo.multiplayer.domain.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestPacket
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.FriendRequestManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Manages the user's in-app friends list and pending PUBG-style friend requests.
 * Persists friends locally in SharedPreferences and syncs with cloud key-value storage.
 */
class FriendsRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val pendingRequests: StateFlow<List<FriendRequest>> = _pendingRequests.asStateFlow()

    private val _sentRequestUsernames = MutableStateFlow<Set<String>>(emptySet())
    val sentRequestUsernames: StateFlow<Set<String>> = _sentRequestUsernames.asStateFlow()

    init {
        activeInstance = this
        loadFriends()
    }

    /**
     * Synchronizes friends and pending friend requests from cloud.
     */
    fun syncFriendsAndRequests(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return

        scope.launch {
            try {
                // 1. Sync cloud friends
                val cloudFriends = FriendRequestManager.fetchCloudFriends(clean)
                if (cloudFriends.isNotEmpty()) {
                    val currentMap = _friends.value.associateBy { it.uid.ifBlank { it.username.lowercase() } }.toMutableMap()
                    cloudFriends.forEach { cf ->
                        val key = cf.uid.ifBlank { cf.username.lowercase() }
                        currentMap[key] = cf
                    }
                    val merged = currentMap.values.toList()
                    _friends.value = merged
                    persistFriends(merged)
                    val friendUsernames = merged.map { it.username }.filter { it.isNotBlank() }
                    if (friendUsernames.isNotEmpty()) {
                        com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresenceForUsers(friendUsernames)
                    }
                } else {
                    val currentFriendUsernames = _friends.value.map { it.username }.filter { it.isNotBlank() }
                    if (currentFriendUsernames.isNotEmpty()) {
                        com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresenceForUsers(currentFriendUsernames)
                    }
                }

                // 2. Fetch pending requests
                val requests = FriendRequestManager.fetchRequestsForUser(clean)
                _pendingRequests.value = requests
            } catch (e: Exception) {
                Log.w("FriendsRepository", "Error syncing friends & requests: ${e.message}")
            }
        }
    }

    /**
     * Checks if a user is already a friend.
     */
    fun isFriend(uidOrUsername: String): Boolean {
        val clean = uidOrUsername.removePrefix("@").trim().lowercase()
        return _friends.value.any {
            it.uid == uidOrUsername || it.username.trim().lowercase().removePrefix("@") == clean
        }
    }

    /**
     * Checks if a friend request was sent to this username.
     */
    fun hasSentRequest(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        return _sentRequestUsernames.value.contains(clean)
    }

    /**
     * Sends a PUBG-style friend request to a player.
     * Does NOT add to friends list yet.
     */
    suspend fun sendFriendRequest(
        targetUsername: String,
        targetDisplayName: String,
        targetUid: String,
        currentUser: UserProfile
    ): Boolean {
        val cleanTarget = targetUsername.trim().lowercase().removePrefix("@")
        if (cleanTarget.isBlank() || isFriend(cleanTarget)) return false

        val safeAvatarUrl = currentUser.avatarUrl?.takeIf { !com.bingo.multiplayer.domain.network.isLocalFilePath(it) && it.length < 300 }
        val request = FriendRequest(
            id = UUID.randomUUID().toString(),
            fromUid = currentUser.uid,
            fromUsername = currentUser.username.ifBlank { currentUser.playerId },
            fromDisplayName = currentUser.displayName,
            fromAvatarUrl = safeAvatarUrl,
            toUsername = cleanTarget,
            status = FriendRequestStatus.PENDING,
            timestamp = System.currentTimeMillis()
        )

        val success = FriendRequestManager.sendFriendRequest(request)
        if (success) {
            _sentRequestUsernames.value = _sentRequestUsernames.value + cleanTarget
        }
        return success
    }

    /**
     * Accepts a pending friend request.
     * Adds the sender to friends list, removes request, and updates cloud storage.
     */
    suspend fun acceptFriendRequest(
        request: FriendRequest,
        currentUser: UserProfile
    ): Boolean {
        val safeAvatarUrl = currentUser.avatarUrl?.takeIf { !com.bingo.multiplayer.domain.network.isLocalFilePath(it) && it.length < 300 }
        val success = FriendRequestManager.acceptFriendRequest(request, currentUser.copy(avatarUrl = safeAvatarUrl))
        if (success) {
            // Add sender to local friends list
            val newFriend = Friend(
                uid = request.fromUid,
                username = request.fromUsername,
                displayName = request.fromDisplayName,
                avatarUrl = request.fromAvatarUrl,
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            addLocalFriend(newFriend)

            // Remove from pending
            _pendingRequests.value = _pendingRequests.value.filter { it.id != request.id }
        }
        return success
    }

    /**
     * Declines / Ignores a pending friend request.
     */
    suspend fun declineFriendRequest(request: FriendRequest): Boolean {
        val success = FriendRequestManager.declineFriendRequest(request)
        if (success) {
            _pendingRequests.value = _pendingRequests.value.filter { it.id != request.id }
        }
        return success
    }

    /**
     * Helper to add a friend locally and persist.
     */
    fun addLocalFriend(friend: Friend) {
        val current = _friends.value.toMutableList()
        current.removeAll {
            it.uid == friend.uid ||
            (it.username.isNotBlank() && it.username.equals(friend.username, ignoreCase = true))
        }
        current.add(0, friend)
        _friends.value = current
        persistFriends(current)
    }

    /**
     * Subscribes to real-time incoming friend requests and acceptances.
     */
    fun startListeningForRequests(
        myUsername: String,
        currentUser: UserProfile?,
        onNewRequest: (FriendRequest) -> Unit,
        onFriendAccepted: (Friend) -> Unit
    ): AutoCloseable {
        val clean = myUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return AutoCloseable {}

        return FriendRequestManager.startRequestListener(clean) { packet ->
            when (packet.type) {
                "FRIEND_REQUEST" -> {
                    val req = packet.request
                    // Add to pending requests if not already in list
                    val currentReqs = _pendingRequests.value.toMutableList()
                    currentReqs.removeAll { it.id == req.id || it.fromUsername.equals(req.fromUsername, ignoreCase = true) }
                    currentReqs.add(0, req)
                    _pendingRequests.value = currentReqs

                    onNewRequest(req)
                }

                "FRIEND_ACCEPT" -> {
                    // The other player accepted our request!
                    val req = packet.request
                    val acceptor = packet.acceptorFriend
                    val friendName = acceptor?.displayName?.ifBlank { req.toUsername } ?: req.toUsername
                    val friendAvatar = acceptor?.avatarUrl
                    val newFriend = Friend(
                        uid = acceptor?.uid ?: "uid_${req.toUsername}",
                        username = req.toUsername,
                        displayName = friendName,
                        avatarUrl = friendAvatar,
                        isOnline = true,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    addLocalFriend(newFriend)

                    // Remove from sent set
                    _sentRequestUsernames.value = _sentRequestUsernames.value - req.toUsername.lowercase()

                    // Sync friends from cloud in background
                    syncFriendsAndRequests(clean)

                    onFriendAccepted(newFriend)
                }
            }
        }
    }

    /**
     * Remove a friend by UID or username.
     */
    fun removeFriend(uidOrUsername: String, currentUsername: String? = null) {
        val clean = uidOrUsername.removePrefix("@").trim().lowercase()
        val current = _friends.value.toMutableList()
        current.removeAll { it.uid == uidOrUsername || it.username.lowercase() == clean }
        _friends.value = current
        persistFriends(current)

        if (currentUsername != null) {
            scope.launch {
                FriendRequestManager.saveCloudFriends(currentUsername, current)
            }
        }
    }

    /**
     * Get friends count.
     */
    fun friendsCount(): Int = _friends.value.size

    private fun loadFriends() {
        val stored = prefs.getString(KEY_FRIENDS, null)
        if (stored != null) {
            try {
                _friends.value = json.decodeFromString<List<Friend>>(stored)
            } catch (_: Exception) {
                _friends.value = emptyList()
            }
        }
    }

    private fun persistFriends(list: List<Friend>) {
        prefs.edit()
            .putString(KEY_FRIENDS, json.encodeToString(list))
            .apply()
    }

    fun onRemoteAvatarUpdated(username: String, newAvatarUrl: String?) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        val current = _friends.value
        val idx = current.indexOfFirst { it.username.trim().lowercase().removePrefix("@") == clean }
        if (idx != -1) {
            val updatedList = current.toMutableList()
            val safeAvatar = newAvatarUrl?.takeIf { !com.bingo.multiplayer.presentation.common.isLocalFilePath(it) }
            updatedList[idx] = updatedList[idx].copy(avatarUrl = safeAvatar)
            _friends.value = updatedList
            persistFriends(updatedList)
        }
    }

    companion object {
        @Volatile var activeInstance: FriendsRepository? = null
        private const val PREFS_NAME = "bingo_friends_prefs"
        private const val KEY_FRIENDS = "friends_list"
    }
}
