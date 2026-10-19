package com.bingo.multiplayer.domain.repository

import android.content.Context
import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Reconciles account-scoped friendships without treating a directory or a failed read as deletion. */
class FriendsRepository internal constructor(
    private val prefs: SharedPreferences,
    private val remote: FriendsDataSource,
    private val currentUser: () -> UserProfile?,
    private val scope: CoroutineScope,
    private val context: Context? = null
) {
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        NetworkFriendsDataSource(context),
        { AuthRepository.activeInstance?.getPersistedUserSync() },
        scope,
        context
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val syncMutex = Mutex()
    private data class Account(val uid: String, val username: String, val generation: Long)
    private var account: Account? = null
    private var generation = 0L
    private var removedKeys = emptySet<String>()
    private var removalIds = emptyMap<String, String>()
    private var handledRequests = emptySet<String>()
    private var notifiedRequests = emptySet<String>()
    private var backupCache: FriendsBackup? = null
    private var backupReadAtNanos = 0L

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()
    private val _pendingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val pendingRequests: StateFlow<List<FriendRequest>> = _pendingRequests.asStateFlow()
    private val _sentRequestUsernames = MutableStateFlow<Set<String>>(emptySet())
    val sentRequestUsernames: StateFlow<Set<String>> = _sentRequestUsernames.asStateFlow()

    init {
        activeInstance = this
        setAccount(currentUser())
    }

    /** Also called on sign-out, so another account never sees this account's friends. */
    @Synchronized
    fun setAccount(user: UserProfile?) {
        val uid = user?.uid?.takeIf { it.isNotBlank() }
        val name = user?.username?.let(::friendUsername).orEmpty()
        if (account?.uid == uid && account?.username.orEmpty() == name) return
        generation++
        account = if (uid != null && name.isNotBlank()) Account(uid, name, generation) else null
        _friends.value = emptyList()
        _pendingRequests.value = emptyList()
        _sentRequestUsernames.value = emptySet()
        removedKeys = emptySet()
        removalIds = emptyMap()
        handledRequests = emptySet()
        notifiedRequests = emptySet()
        backupCache = null
        backupReadAtNanos = 0L
        val active = account ?: return

        // Bind the legacy, device-wide cache to the account that owned it during upgrade, once.
        val legacyOwner = prefs.getString("legacy_friends_owner", null)
        if (legacyOwner == null) {
            val editor = prefs.edit().putString("legacy_friends_owner", active.uid)
            if (!prefs.contains(key(active, KEY_FRIENDS))) {
                prefs.getString(KEY_FRIENDS, null)?.let { editor.putString(key(active, KEY_FRIENDS), it) }
            }
            editor.commit()
        }
        _friends.value = runCatching {
            json.decodeFromString<List<Friend>>(prefs.getString(key(active, KEY_FRIENDS), "[]") ?: "[]")
        }.getOrDefault(emptyList())
        removedKeys = prefs.getStringSet(key(active, "removed"), emptySet())?.toSet().orEmpty()
        removalIds = runCatching {
            json.decodeFromString<Map<String, String>>(prefs.getString(key(active, "removal_ids"), "{}") ?: "{}")
        }.getOrDefault(emptyMap())
        handledRequests = prefs.getStringSet(key(active, "handled"), emptySet())?.toSet().orEmpty()
        notifiedRequests = prefs.getStringSet(key(active, "notified"), emptySet())?.toSet().orEmpty()
        _friends.value = mergeFriends(_friends.value).filterNot(::wasRemoved)
    }

    @Synchronized
    private fun activeAccount(): Account? {
        setAccount(currentUser())
        return account
    }

    @Synchronized
    private fun isCurrent(expected: Account): Boolean {
        val user = currentUser()
        return account == expected && user?.uid == expected.uid && friendUsername(user.username) == expected.username
    }

    private fun key(active: Account, suffix: String) = "${active.uid}:$suffix"
    private fun wasRemoved(friend: Friend) = friendKeys(friend).any { it in removedKeys }
    private fun sameFriend(a: Friend, b: Friend) = friendKeys(a).intersect(friendKeys(b)).isNotEmpty()
    private fun fromRequest(req: FriendRequest) = Friend(req.fromUid, req.fromUsername, req.fromDisplayName, req.fromAvatarUrl)

    private fun mergeFriends(vararg lists: List<Friend>): List<Friend> {
        val merged = mutableListOf<Friend>()
        lists.forEach { list -> list.forEach friendLoop@ { friend ->
            if (friendKeys(friend).isEmpty()) return@friendLoop
            val index = merged.indexOfFirst { sameFriend(it, friend) }
            if (index < 0) merged += friend
            else {
                val previous = merged[index]
                merged[index] = friend.copy(
                    uid = friend.uid.ifBlank { previous.uid },
                    username = friend.username.ifBlank { previous.username },
                    avatarUrl = friend.avatarUrl ?: previous.avatarUrl,
                    gamesPlayed = maxOf(friend.gamesPlayed, previous.gamesPlayed),
                    gamesWon = maxOf(friend.gamesWon, previous.gamesWon),
                    currentStreak = if (friend.gamesPlayed >= previous.gamesPlayed && friend.gamesWon >= previous.gamesWon)
                        friend.currentStreak else previous.currentStreak,
                    level = maxOf(friend.level, previous.level)
                )
            }
        } }
        return merged
    }

    @Synchronized
    private fun persistFriends(active: Account) {
        if (!isCurrent(active)) return
        prefs.edit().putString(key(active, KEY_FRIENDS), json.encodeToString(_friends.value))
            .putStringSet(key(active, "removed"), removedKeys)
            .putString(key(active, "removal_ids"), json.encodeToString(removalIds)).apply()
        context?.let { com.bingo.multiplayer.domain.notifications.NotificationRuntime.syncFriends(it, _friends.value.toList()) }
    }

    fun syncFriendsAndRequests(username: String) {
        val active = activeAccount() ?: return
        if (friendUsername(username) != active.username) return
        scope.launch {
            // The UI polls every two seconds. Never build an unbounded queue of network jobs.
            if (!syncMutex.tryLock()) return@launch
            try { reconcile(active) }
            finally { syncMutex.unlock() }
        }
    }

    private suspend fun reconcile(active: Account, forceBackup: Boolean = false): List<FriendRequest>? {
        if (!isCurrent(active)) return null
        val cachedBackup = synchronized(this) { backupCache }
        val refreshBackup = forceBackup || cachedBackup == null || System.nanoTime() - backupReadAtNanos >= 30_000_000_000L
        val (cloud, backup, requests) = coroutineScope {
            val friendsJob = async { withTimeoutOrNull(8_000L) { remote.fetchFriends(active.username) } }
            val backupJob = async { if (refreshBackup) withTimeoutOrNull(8_000L) { remote.fetchBackup(active.uid) } else cachedBackup }
            val requestsJob = async { withTimeoutOrNull(8_000L) { remote.fetchRequests(active.username) } }
            Triple(friendsJob.await(), backupJob.await(), requestsJob.await())
        }
        val merged = synchronized(this) {
            if (!isCurrent(active)) return null
            if (refreshBackup && backup != null) {
                backupCache = backup
                backupReadAtNanos = System.nanoTime()
            }
            val restored = removedKeys.filter { key ->
                val ownRemovalId = removalIds[key]
                if (ownRemovalId != null) ownRemovalId in backup?.restoredRemovalIds.orEmpty()
                else key in backup?.restoredKeys.orEmpty()
            }.toSet()
            removalIds = removalIds.filterKeys { it !in restored }
            removedKeys = (removedKeys - restored) + backup?.removedKeys.orEmpty()
            // KeyValue may contain a partial list. The existing per-friend backup and cache survive it.
            _friends.value = mergeFriends(backup?.friends.orEmpty(), cloud.orEmpty(), _friends.value)
                .filterNot(::wasRemoved)
            if (requests != null) {
                _pendingRequests.value = requests.filter { validPending(it, active) }
                    .distinctBy { friendUsername(it.fromUsername) }
            } else _pendingRequests.value = _pendingRequests.value.filter { validPending(it, active) }
            persistFriends(active)
            _friends.value
        }
        if (!isCurrent(active)) return null
        remote.saveBackup(active.uid, merged)
        // An unavailable/malformed read is never permission to replace the full cloud list.
        if (cloud != null && mergeFriends(cloud).map(::friendKeys).toSet() != merged.map(::friendKeys).toSet()) {
            remote.saveFriends(active.username, merged)
        }
        if (isCurrent(active) && merged.isNotEmpty()) remote.refreshPresence(merged.map { it.username }.filter { it.isNotBlank() })
        return requests
    }

    private fun validPending(req: FriendRequest, active: Account): Boolean =
        req.id.isNotBlank() && req.status == FriendRequestStatus.PENDING &&
            friendUsername(req.toUsername) == active.username &&
            friendUsername(req.fromUsername).isNotBlank() && friendUsername(req.fromUsername) != active.username &&
            req.id !in handledRequests && !_friends.value.any { sameFriend(it, fromRequest(req)) }

    @Synchronized
    fun notificationFriends(uid: String): List<Friend>? {
        val active = activeAccount() ?: return null
        return if (active.uid == uid) _friends.value.toList() else null
    }

    fun isFriend(uidOrUsername: String): Boolean {
        activeAccount()
        return _friends.value.any { it.uid == uidOrUsername || friendUsername(it.username) == friendUsername(uidOrUsername) }
    }

    fun hasSentRequest(username: String): Boolean {
        activeAccount()
        return friendUsername(username) in _sentRequestUsernames.value
    }

    suspend fun sendFriendRequest(targetUsername: String, currentUser: UserProfile): Boolean {
        val active = activeAccount() ?: return false
        val target = friendUsername(targetUsername)
        if (active.uid != currentUser.uid || target.isBlank() || target == active.username || isFriend(target)) return false
        val avatar = currentUser.avatarBase64?.takeIf { it.isNotBlank() }
            ?: currentUser.avatarUrl?.takeIf { !com.bingo.multiplayer.domain.network.isLocalFilePath(it) }
        val request = FriendRequest(UUID.randomUUID().toString(), currentUser.uid, active.username,
            currentUser.displayName, avatar, target)
        val success = remote.send(request)
        synchronized(this) {
            if (!isCurrent(active)) return false
            if (success) _sentRequestUsernames.value = _sentRequestUsernames.value + target
        }
        return success
    }

    suspend fun acceptFriendRequest(request: FriendRequest, currentUser: UserProfile): Boolean = syncMutex.withLock {
        val active = activeAccount() ?: return@withLock false
        if (currentUser.uid != active.uid || friendUsername(request.toUsername) != active.username) return@withLock false
        val avatar = currentUser.avatarBase64?.takeIf { it.isNotBlank() }
            ?: currentUser.avatarUrl?.takeIf { !com.bingo.multiplayer.domain.network.isLocalFilePath(it) }
        if (!remote.accept(request, currentUser.copy(avatarUrl = avatar))) return@withLock false
        synchronized(this) {
            if (!isCurrent(active)) return@withLock false
            markHandled(active, request.id)
            context?.let { com.bingo.multiplayer.domain.notifications.NotificationRuntime.finishFriendRequest(it, request) }
            val friend = fromRequest(request)
            removedKeys = removedKeys - friendKeys(friend)
            removalIds = removalIds.filterKeys { it !in friendKeys(friend) }
            backupCache = null
            _friends.value = mergeFriends(_friends.value, listOf(friend))
            _pendingRequests.value = _pendingRequests.value.filterNot { it.id == request.id || sameFriend(fromRequest(it), friend) }
            persistFriends(active)
            remote.saveBackup(active.uid, _friends.value)
        }
        true
    }

    suspend fun declineFriendRequest(request: FriendRequest): Boolean = syncMutex.withLock {
        val active = activeAccount() ?: return@withLock false
        if (friendUsername(request.toUsername) != active.username || !remote.decline(request)) return@withLock false
        synchronized(this) {
            if (!isCurrent(active)) return@withLock false
            markHandled(active, request.id)
            context?.let { com.bingo.multiplayer.domain.notifications.NotificationRuntime.finishFriendRequest(it, request) }
            _pendingRequests.value = _pendingRequests.value.filterNot { it.id == request.id }
        }
        true
    }

    private fun markHandled(active: Account, id: String) {
        handledRequests = handledRequests + id
        prefs.edit().putStringSet(key(active, "handled"), handledRequests).commit()
    }

    fun startListeningForRequests(
        myUsername: String,
        onNewRequest: (FriendRequest) -> Unit,
        onFriendAccepted: (Friend) -> Unit
    ): AutoCloseable {
        val active = activeAccount() ?: return AutoCloseable {}
        if (active.username != friendUsername(myUsername)) return AutoCloseable {}
        val closed = AtomicBoolean(false)
        val listener = remote.listen(active.username) { packet ->
            scope.launch {
                syncMutex.withLock {
                    if (closed.get() || !isCurrent(active)) return@withLock
                    // Restore friendships before considering a packet received during cold startup.
                    val verifiedRequests = reconcile(active, forceBackup = true)
                    if (closed.get() || !isCurrent(active)) return@withLock
                    synchronized(this@FriendsRepository) {
                        val req = packet.request
                        when (packet.type) {
                            "FRIEND_REQUEST" -> {
                                // MQTT is a notification, not evidence that a request is still pending.
                                if (!validPending(req, active) || verifiedRequests?.none { it.id == req.id } != false) return@synchronized
                                val event = "request:${req.id}"
                                if (event in notifiedRequests) return@synchronized
                                val notified = notifiedRequests + event
                                if (!prefs.edit().putStringSet(key(active, "notified"), notified).commit()) return@synchronized
                                notifiedRequests = notified
                                onNewRequest(req)
                            }
                            "FRIEND_ACCEPT" -> {
                                if (friendUsername(req.fromUsername) != active.username || req.status != FriendRequestStatus.ACCEPTED) return@synchronized
                                // Reconciliation must confirm this friendship; delayed accepts cannot re-add a removed friend.
                                val friend = _friends.value.firstOrNull { friendUsername(it.username) == friendUsername(req.toUsername) }
                                    ?: return@synchronized
                                _sentRequestUsernames.value = _sentRequestUsernames.value - friendUsername(req.toUsername)
                                val event = "accepted:${req.id}"
                                if (event in notifiedRequests) return@synchronized
                                val notified = notifiedRequests + event
                                if (!prefs.edit().putStringSet(key(active, "notified"), notified).commit()) return@synchronized
                                notifiedRequests = notified
                                onFriendAccepted(friend)
                            }
                        }
                    }
                }
            }
        }
        return AutoCloseable { closed.set(true); listener.close() }
    }

    fun removeFriend(uidOrUsername: String, currentUsername: String? = null) {
        val active = activeAccount() ?: return
        if (currentUsername != null && friendUsername(currentUsername) != active.username) return
        synchronized(this) {
            val found = _friends.value.firstOrNull { it.uid == uidOrUsername || friendUsername(it.username) == friendUsername(uidOrUsername) } ?: return
            val removalId = UUID.randomUUID().toString()
            removedKeys = removedKeys + friendKeys(found)
            removalIds = removalIds + friendKeys(found).associateWith { removalId }
            backupCache = null
            _friends.value = _friends.value.filterNot(::wasRemoved)
            persistFriends(active)
            remote.removeFromBackup(active.uid, found, removalId)
        }
        scope.launch {
            syncMutex.withLock {
                if (isCurrent(active)) remote.saveFriends(active.username, _friends.value)
            }
        }
    }

    suspend fun refreshFriendsStats(friendsList: List<Friend> = _friends.value) = withContext(Dispatchers.IO) {
        val active = activeAccount() ?: return@withContext
        val ctx = context ?: return@withContext
        val targets = _friends.value.ifEmpty { friendsList }
        if (targets.isEmpty()) return@withContext
        val firestoreProfiles = com.bingo.multiplayer.domain.network.FirestoreSyncManager
            .getInstance(ctx).fetchPlayerProfiles(targets.map { it.uid })
        val permits = Semaphore(4)
        val entries = coroutineScope {
            targets.map { friend ->
                async {
                    permits.withPermit {
                        firestoreProfiles[friend.uid] ?: withTimeoutOrNull(3500L) {
                            com.bingo.multiplayer.domain.network.AccountSessionManager.searchPlayerByUsername(
                                friend.username, timeoutMs = 2500L, forceRefresh = true
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (!isCurrent(active)) return@withContext
        entries.forEach { entry ->
            com.bingo.multiplayer.domain.network.AccountSessionManager.cachePlayer(entry)
            updateFriendCareerStats(entry)
        }
    }

    fun updateFriendCareerStats(entry: com.bingo.multiplayer.domain.network.PlayerRegistryEntry) {
        val active = activeAccount() ?: return
        synchronized(this) {
            _friends.value = _friends.value.map { com.bingo.multiplayer.domain.engine.FriendCareerStats.merge(it, entry) }
            persistFriends(active)
        }
    }

    @Synchronized
    fun onRemoteAvatarUpdated(username: String, newAvatarUrl: String?) {
        val active = activeAccount() ?: return
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        val current = _friends.value
        val idx = current.indexOfFirst { it.username.trim().lowercase().removePrefix("@") == clean }
        if (idx != -1) {
            val updatedList = current.toMutableList()
            val safeAvatar = newAvatarUrl?.takeIf { !com.bingo.multiplayer.presentation.common.isLocalFilePath(it) }
            updatedList[idx] = updatedList[idx].copy(avatarUrl = safeAvatar)
            _friends.value = updatedList
            persistFriends(active)
        }
    }

    companion object {
        @Volatile var activeInstance: FriendsRepository? = null
        private const val PREFS_NAME = "bingo_friends_prefs"
        private const val KEY_FRIENDS = "friends_list"
    }
}
