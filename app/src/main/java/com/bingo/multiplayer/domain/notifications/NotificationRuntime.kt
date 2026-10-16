package com.bingo.multiplayer.domain.notifications

import android.content.Context
import android.widget.Toast
import com.bingo.multiplayer.core.designsystem.NotificationPreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.FriendRequestStatus
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.*
import com.bingo.multiplayer.domain.repository.FriendsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** All presentation enters here. Transports are hints; inbox data comes from an authenticated server. */
object NotificationRuntime {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var appContext: Context? = null
    @Volatile private var storageUnavailable = false
    @Volatile var matchActive: Boolean = false
        private set
    private val pendingInvites = linkedMapOf<String, NotificationEvent>()
    private var visibleInvite: String? = null
    private var presentationAccount: NotificationAccount? = null
    private var activeToast: Toast? = null
    private val refreshMutex = Mutex()

    fun profile(context: Context): UserProfile? {
        val p = context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
        val uid = p.getString("uid", null)?.takeIf { it.isNotBlank() } ?: return null
        val username = p.getString("username", null)?.takeIf { it.isNotBlank() } ?: return null
        return UserProfile(uid, username, p.getString("display_name", username) ?: username,
            email = p.getString("email", null), provider = runCatching { AuthProvider.valueOf(p.getString("provider", "GOOGLE")!!) }.getOrDefault(AuthProvider.GOOGLE))
    }
    fun init(context: Context) {
        appContext = context.applicationContext
        NotificationPreferences.init(context)
        val user = profile(context)
        bind(context, user?.uid, user?.username)
    }
    fun context(): Context? = appContext
    fun account(context: Context): NotificationAccount? {
        if (storageUnavailable) return null
        val account = NotificationAccountStore.get(context).account() ?: return null
        val user = profile(context) ?: return null
        return account.takeIf { it.uid == user.uid && it.username == notificationUsername(user.username) }
    }
    fun ledger(context: Context) = NotificationLedger(context.getSharedPreferences("bingo_notification_ledger_v2", Context.MODE_PRIVATE))

    /** Serialize the final presentation with account changes, including cancellation at logout. */
    @Synchronized fun withAccount(context: Context, expected: NotificationAccount?, action: () -> Unit): Boolean {
        if (account(context) != expected) return false
        action()
        return true
    }
    // Main-thread state. A delayed cleanup must never clear a newer account's presentation.
    private fun preparePresentation(context: Context) {
        val current = account(context)
        if (presentationAccount == current) return
        activeToast?.cancel()
        activeToast = null
        pendingInvites.clear()
        visibleInvite = null
        GameInviteManager.clearForegroundInvite()
        presentationAccount = current
    }

    @Synchronized fun bind(context: Context, uid: String?, username: String?) {
        appContext = context.applicationContext
        val store = NotificationAccountStore.get(context)
        val old = store.account()
        val next = try { store.bind(uid, username).also { storageUnavailable = false } }
        catch (_: RuntimeException) {
            storageUnavailable = true
            BingoNotificationManager.cancelAccountNotifications(context)
            NotificationDeliveryStatus.failed(NotificationApiException(503, "Notification storage unavailable"))
            return
        }
        if (old != next) {
            store.revocation()?.let { NotificationWork.revoke(context, it.first, it.second) }
            if (old != null) NotificationWork.cancelAccount(context, old)
            BingoNotificationManager.cancelAccountNotifications(context)
            GameInviteManager.clearForegroundInvite()
            BingoNotificationManager.clearPendingActions()
            BroadcastMessageManager.onAccountChanged()
            NotificationDeliveryStatus.clear()
            matchActive = false
            scope.launch { preparePresentation(context) }
        }
        if (next != null) NotificationWork.register(context, next)
    }
    fun updateMatchActive(context: Context, active: Boolean) {
        val expected = account(context)
        matchActive = active
        if (active) {
            BingoNotificationManager.cancelInvites(context)
            scope.launch {
                if (account(context) != expected || !matchActive) return@launch
                preparePresentation(context)
                GameInviteManager.clearForegroundInvite(); visibleInvite = null
            }
        } else scope.launch { showNextInvite(context) }
    }
    fun isBusy(context: Context): Boolean = matchActive || account(context)?.let {
        OngoingMatchStore.getOngoingMatch(context, it.uid) != null
    } == true

    fun friends(context: Context, account: NotificationAccount): List<Friend> {
        val repo = FriendsRepository.activeInstance
        repo?.notificationFriends(account.uid)?.let { return it }
        val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
        val removed = prefs.getStringSet("${account.uid}:removed", emptySet()).orEmpty()
        val raw = prefs.getString("${account.uid}:friends_list", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Friend>>(raw) }.getOrDefault(emptyList()).filter { f ->
            "uid:${f.uid}" !in removed && "name:${notificationUsername(f.username)}" !in removed
        }
    }
    fun isFriend(context: Context, username: String): Boolean {
        val a = account(context) ?: return false
        return friends(context, a).any { notificationUsername(it.username) == notificationUsername(username) }
    }
    @Synchronized fun friendSnapshot(context: Context, account: NotificationAccount): Pair<List<String>, Long>? {
        val prefs = context.getSharedPreferences("bingo_notification_account_v2", Context.MODE_PRIVATE)
        if (prefs.getString("friendGeneration", null) != account.generation) return null
        val values = runCatching { json.decodeFromString<List<String>>(prefs.getString("friendUids", "[]")!!) }.getOrDefault(emptyList())
        return values to prefs.getLong("friendRevision", 1)
    }
    @Synchronized fun syncFriends(context: Context, snapshot: List<Friend>) {
        val account = account(context) ?: return
        val uids = snapshot.map { it.uid }.distinct().sorted()
        val hash = NotificationPolicy.eventId("friends", account.generation, *uids.toTypedArray())
        val prefs = context.getSharedPreferences("bingo_notification_account_v2", Context.MODE_PRIVATE)
        if (prefs.getString("friendHash", null) == hash) return
        val revision = prefs.getLong("friendRevision", 1) + 1
        if (!prefs.edit().putLong("friendRevision", revision).putString("friendUids", json.encodeToString(uids))
            .putString("friendGeneration", account.generation).commit()) return
        if (NotificationWork.register(context, account, hash)) prefs.edit().putString("friendHash", hash).commit()
    }
    suspend fun refresh(context: Context, expected: NotificationAccount? = account(context)) = refreshMutex.withLock {
        val target = expected ?: return@withLock
        val events = NotificationApi.inbox(context, target).sortedWith(compareBy<NotificationEvent> { NotificationPolicy.priority(it.kind) }.thenByDescending { it.createdAt })
        var requestsRead = false
        var requests: List<com.bingo.multiplayer.domain.model.FriendRequest>? = null
        var friendsRead = false
        var remoteFriends: List<Friend>? = null
        suspend fun pending(): List<com.bingo.multiplayer.domain.model.FriendRequest>? {
            if (!requestsRead) { requests = FriendRequestManager.fetchRequestsForUserOrNull(target.username); requestsRead = true }
            return requests
        }
        suspend fun roster(): List<Friend>? {
            if (!friendsRead) { remoteFriends = FriendRequestManager.fetchCloudFriends(target.username); friendsRead = true }
            return remoteFriends
        }
        for (event in events) {
            if (account(context) != target) return@withLock
            if (ledger(context).handled(target.uid, event.id)) { NotificationWork.acknowledge(context, target, event); continue }
            if (event.kind != NotificationKind.GAME_INVITE && ledger(context).presented(target.uid, event.id)) continue
            receive(context, event, ::pending, ::roster)
        }
        withContext(Dispatchers.Main.immediate) { showNextInvite(context) }
    }
    private suspend fun receive(context: Context, event: NotificationEvent,
        pendingRequests: suspend () -> List<com.bingo.multiplayer.domain.model.FriendRequest>?,
        remoteRoster: suspend () -> List<Friend>?) {
        val current = account(context) ?: return
        if (!NotificationPolicy.eligible(event, current, System.currentTimeMillis()) || ledger(context).handled(current.uid, event.id)) return
        when (event.kind) {
            NotificationKind.FRIEND_ONLINE -> {
                if (!isFriend(context, event.senderUsername)) return
                val known = PresenceManager.presenceFlow.value[notificationUsername(event.senderUsername)]
                if (known != null && known.timestamp > event.createdAt && !known.isOnline) return
            }
            NotificationKind.FRIEND_REQUEST -> {
                if (isFriend(context, event.senderUsername)) return
                val pending = pendingRequests()
                    ?: throw NotificationApiException(0, "Request validation is temporarily unavailable")
                if (pending.none { it.id == event.requestId && it.status == FriendRequestStatus.PENDING &&
                        it.fromUid == event.senderUid && notificationUsername(it.toUsername) == current.username }) return
            }
            NotificationKind.FRIEND_ACCEPTED -> {
                if (!isFriend(context, event.senderUsername)) {
                    val prefs = context.getSharedPreferences("bingo_friends_prefs", Context.MODE_PRIVATE)
                    val removed = prefs.getStringSet("${current.uid}:removed", emptySet()).orEmpty()
                    if ("uid:${event.senderUid}" in removed || "name:${notificationUsername(event.senderUsername)}" in removed) return
                    val remote = remoteRoster()
                        ?: throw NotificationApiException(0, "Friendship validation is temporarily unavailable")
                    if (remote.none { it.uid == event.senderUid && notificationUsername(it.username) == notificationUsername(event.senderUsername) }) return
                }
            }
            NotificationKind.GAME_INVITE -> if (isBusy(context)) return
        }
        withContext(Dispatchers.Main.immediate) {
            // Background validation may read the synchronized friends repository. Do that before
            // taking the account/presentation lock; repository persistence also updates this runtime.
            if (!AppLifecycleObserver.isAppInForeground.value) {
                if (account(context) == current) BingoNotificationManager.showEvent(context, event)
                return@withContext
            }
            withAccount(context, current) {
                if (!NotificationPolicy.eligible(event, current, System.currentTimeMillis())) return@withAccount
                preparePresentation(context)
                if (event.kind == NotificationKind.GAME_INVITE) {
                    pendingInvites[event.id] = event
                    showNextInvite(context)
                } else {
                    val enabled = if (event.kind == NotificationKind.FRIEND_ONLINE) NotificationPreferences.canShowInAppPlayerOnline(context)
                        else NotificationPreferences.inAppNotificationsEnabled.value
                    if (enabled) {
                        val claim = ledger(context).present(event, system = false)
                        if (claim.show) {
                            activeToast?.cancel()
                            activeToast = Toast.makeText(context.applicationContext, eventText(event), Toast.LENGTH_LONG).also { it.show() }
                        }
                    }
                }
            }
        }
    }
    private fun showNextInvite(context: Context) {
        val expected = account(context) ?: return
        withAccount(context, expected) {
            preparePresentation(context)
            if (!AppLifecycleObserver.isAppInForeground.value || isBusy(context) || visibleInvite != null || !NotificationPreferences.canShowInAppInvite(context)) return@withAccount
            val account = expected
            pendingInvites.entries.removeAll { !NotificationPolicy.eligible(it.value, account, System.currentTimeMillis()) || ledger(context).handled(account.uid, it.key) }
            val event = pendingInvites.values.firstOrNull() ?: return@withAccount
            val claim = ledger(context).present(event, system = false)
            // Restore an unanswered popup after process recreation, silently.
            visibleInvite = event.id
            BingoNotificationManager.cancelEvent(context, event)
            if (claim.alert) com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playVibration(context, durationMs = 200L)
            GameInviteManager.stageInvite(event.toInvite())
        }
    }
    fun finishInvite(context: Context, invite: GameInvite): Boolean {
        val a = account(context) ?: return false
        val event = invite.toEvent()
        if (event.recipientUid != a.uid || notificationUsername(event.recipientUsername) != a.username) return false
        if (!ledger(context).markHandled(event)) return false
        BingoNotificationManager.cancelEvent(context, event)
        NotificationWork.acknowledge(context, a, event)
        scope.launch {
            if (account(context) != a) return@launch
            preparePresentation(context)
            pendingInvites.remove(event.id)
            if (visibleInvite == event.id) {
                visibleInvite = null
                GameInviteManager.clearForegroundInvite()
            }
            showNextInvite(context)
        }
        return true
    }
    fun finishFriendRequest(context: Context, request: com.bingo.multiplayer.domain.model.FriendRequest) {
        val account = account(context) ?: return
        if (notificationUsername(request.toUsername) != account.username) return
        val event = NotificationEvent(NotificationPolicy.eventId("friend_request", request.id), NotificationKind.FRIEND_REQUEST,
            request.fromUid, request.fromUsername, request.fromDisplayName, account.uid, account.username,
            request.timestamp, request.timestamp + NotificationKind.FRIEND_REQUEST.lifetimeMillis, requestId = request.id)
        if (ledger(context).markHandled(event)) {
            BingoNotificationManager.cancelEvent(context, event)
            NotificationWork.acknowledge(context, account, event)
        }
    }
    fun onForeground(context: Context) {
        account(context)?.let { NotificationWork.sync(context, it) }
        scope.launch { showNextInvite(context) }
    }
    fun eventText(event: NotificationEvent): String = when (event.kind) {
        NotificationKind.GAME_INVITE -> "${event.senderDisplayName} invited you to room #${event.roomCode}"
        NotificationKind.FRIEND_REQUEST -> "👥 @${event.senderUsername} sent you a friend request!"
        NotificationKind.FRIEND_ACCEPTED -> "🎉 @${event.senderUsername} accepted your friend request! You are now friends."
        NotificationKind.FRIEND_ONLINE -> "🟢 ${event.senderDisplayName} is online"
    }
}
