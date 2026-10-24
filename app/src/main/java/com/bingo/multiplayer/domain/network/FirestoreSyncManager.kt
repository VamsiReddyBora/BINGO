package com.bingo.multiplayer.domain.network

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.model.UserSettings
import com.bingo.multiplayer.domain.repository.FriendsBackup
import com.bingo.multiplayer.domain.repository.friendKeys
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Enterprise-grade Firebase Firestore Synchronization Manager.
 * Orchestrates real-time bidirectional syncing of User Profiles, App Settings,
 * Board Customization Colors, Match History, and Friends roster.
 *
 * Equipped with native offline persistence (LocalCacheSettings) and graceful fallback.
 */
@Keep
class FirestoreSyncManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val firestore: FirebaseFirestore by lazy {
        val db = FirebaseFirestore.getInstance()
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            db.firestoreSettings = settings
        } catch (e: Exception) {
            Log.w(TAG, "Firestore cache settings already configured or exception: ${e.message}")
        }
        db
    }

    private var profileListenerRegistration: ListenerRegistration? = null
    private var profileListenerEpoch = 0L
    private var acknowledgementRetry: kotlinx.coroutines.Job? = null
    private var acknowledgementJob: kotlinx.coroutines.Job? = null
    private var accountRemovalListenerRegistration: ListenerRegistration? = null
    private var syncRetryJob: kotlinx.coroutines.Job? = null
    private var privateSettingsListenerRegistration: ListenerRegistration? = null
    private var privateAccountListenerRegistration: ListenerRegistration? = null

    // ── 1. USER PROFILE & THEME SETTINGS SYNC ──

    internal fun friendDocuments(): FriendDocuments = FirestoreFriendDocuments(firestore)

    private fun publicAvatarFor(user: UserProfile): String? = publicProfileAvatar(
        user, AuthRepository.activeInstance?.getPersistedUserSync())

    internal fun watchAvatar(username: String, changed: (ProfileAvatar) -> Unit): AutoCloseable {
        // Watch the canonical username index so a duplicate/old UID cannot supply the photo.
        val lock = Any()
        var closed = false
        var watchedUid: String? = null
        var profile: ListenerRegistration? = null
        val index = firestore.collection(COLLECTION_USERNAMES).document(username)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                synchronized(lock) {
                    if (closed) return@addSnapshotListener
                    val uid = snapshot.getString("uid")
                    if (uid == watchedUid) return@addSnapshotListener
                    profile?.remove(); profile = null; watchedUid = uid
                    if (uid.isNullOrBlank()) {
                        if (!snapshot.metadata.isFromCache) changed(ProfileAvatar(null))
                    } else profile = firestore.collection(COLLECTION_USERS).document(uid)
                        .addSnapshotListener { document, failure ->
                            synchronized(lock) {
                                if (!closed && watchedUid == uid && failure == null && document != null) {
                                    if (document.exists()) {
                                        val data = CloudAdminPolicy.from(document.data.orEmpty()).publicProjection(document.data.orEmpty())
                                        if (data.containsKey("avatarUrl")) changed(ProfileAvatar((data["avatarUrl"] as? String)
                                            ?.takeIf { it.isNotBlank() && !isLocalFilePath(it) }))
                                    } else if (!document.metadata.isFromCache) changed(ProfileAvatar(null))
                                }
                            }
                        }
                }
            }
        return AutoCloseable { synchronized(lock) { closed = true; index.remove(); profile?.remove() } }
    }

    /** Public lookup uses the same canonical UID as the admin directory. */
    internal suspend fun findPlayerByUsername(username: String): PlayerRegistryEntry? =
        kotlinx.coroutines.withTimeoutOrNull(2500L) {
            try {
                val uid = firestore.collection(COLLECTION_USERNAMES).document(username).get().await().getString("uid")
                    ?: firestore.collection(COLLECTION_USERS).whereEqualTo("username", username).limit(1)
                        .get().await().documents.firstOrNull()?.id
                    ?: return@withTimeoutOrNull null
                fetchPlayerProfile(uid)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    /**
     * Uploads the full user profile and settings to Firestore: `users/{uid}`.
     * Also claims the username record in `usernames/{username}`.
     */
    fun syncUserProfile(user: UserProfile, settings: UserSettings) {
        if (user.uid.isBlank()) return
        val repository = AuthRepository.activeInstance
        val generation = repository?.sessionGeneration?.get()
        scope.launch {
            try {
                val userDocRef = firestore.collection(COLLECTION_USERS).document(user.uid)
                // Public profile projection contains only permitted non-sensitive fields.
                // Explicitly delete email and settings from public doc to migrate existing mixed records.
                val publicPayload = hashMapOf<String, Any?>(
                    "uid" to user.uid,
                    "username" to user.username.trim().lowercase().removePrefix("@"),
                    "displayName" to user.displayName,
                    "avatarUrl" to publicAvatarFor(user),
                    "provider" to user.provider.name,
                    "gamesPlayed" to user.gamesPlayed,
                    "gamesWon" to user.gamesWon,
                    "currentStreak" to user.currentStreak,
                    "bestStreak" to user.bestStreak,
                    "level" to user.level,
                    "xp" to user.xp,
                    "lastSeenTimestamp" to FieldValue.serverTimestamp(),
                    "appVersion" to user.appVersion,
                    "appVersionCode" to user.appVersionCode,
                    "email" to FieldValue.delete(),
                    "settings" to FieldValue.delete()
                )
                val myKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(user.uid)
                com.bingo.multiplayer.domain.network.AccountKeyStore.recordAuthoritativeKey(user.uid, myKey)
                val currentFirebaseUid = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
                if (!currentFirebaseUid.isNullOrBlank()) {
                    publicPayload["firebaseUid"] = currentFirebaseUid
                    if (user.uid.startsWith("guest_")) {
                        runCatching {
                            val (pubKey, sig) = com.bingo.multiplayer.domain.notifications.NotificationIdentity.signGuestBinding(currentFirebaseUid)
                            publicPayload["installationPublicKey"] = pubKey
                            publicPayload["installationSignature"] = sig
                            val rawPub = com.bingo.multiplayer.domain.notifications.NotificationIdentity.rawPublicKey()
                            publicPayload["installationPublicKeyBlob"] = com.google.firebase.firestore.Blob.fromBytes(rawPub)
                        }
                    } else if (myKey.isNotBlank()) {
                        publicPayload["installationPublicKey"] = myKey
                    }
                }

                val adminSnapshot = firestore.runTransaction { transaction ->
                    val remote = transaction.get(userDocRef).data.orEmpty()
                    val latest = repository?.getPersistedUserSync()
                    if (repository != null && (repository.sessionGeneration.get() != generation || latest?.uid != user.uid))
                        return@runTransaction null
                    val effective = CloudAdminPolicy.from(remote).applyTo(latest ?: user)
                    // Read the admin policy and write its resulting projection atomically. Firebase
                    // retries this transaction if the administrator edits during an upload.
                    val guarded = publicPayload + mapOf(
                        "username" to effective.username.trim().lowercase().removePrefix("@"),
                        "displayName" to effective.displayName,
                        "avatarUrl" to (effective.avatarBase64?.takeIf { it.isNotBlank() }
                            ?: effective.avatarUrl?.takeIf { !isLocalFilePath(it) }),
                        "avatarBase64" to effective.avatarBase64,
                        "gamesPlayed" to effective.gamesPlayed, "gamesWon" to effective.gamesWon,
                        "currentStreak" to effective.currentStreak, "bestStreak" to effective.bestStreak,
                        "level" to effective.level, "xp" to effective.xp
                    )
                    transaction.set(userDocRef, guarded, SetOptions.merge())
                    remote
                }.await() ?: return@launch
                if (repository != null && generation != null)
                    repository.applyCloudAdminSnapshot(adminSnapshot, user.uid, generation)
                if (repository != null && (repository.sessionGeneration.get() != generation || repository.getPersistedUserSync()?.uid != user.uid)) return@launch

                // Claim / index username if present
                if (user.username.isNotBlank()) {
                    val cleanUsername = user.username.trim().lowercase().removePrefix("@")
                    firestore.collection(COLLECTION_USERNAMES)
                        .document(cleanUsername)
                        .set(
                            mapOf(
                                "uid" to user.uid,
                                "username" to cleanUsername,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        ).await()
                }

                // Save private settings in owner-only /private/settings subcollection
                val settingsPayload = hashMapOf<String, Any?>(
                    "soundEnabled" to settings.soundEnabled,
                    "hapticsEnabled" to settings.hapticsEnabled,
                    "pickSoundPresetId" to settings.pickSoundPresetId,
                    "preferredBoardSize" to settings.preferredBoardSize,
                    "darkTheme" to settings.darkTheme,
                    "accentColorId" to settings.accentColorId,
                    "customAccentHex" to settings.customAccentHex,
                    "customMyPickHex" to settings.customMyPickHex,
                    "customOpponentPickHex" to settings.customOpponentPickHex,
                    "customRecentPickHex" to settings.customRecentPickHex,
                    "customCompletedLineHex" to settings.customCompletedLineHex,
                    "cellBorderEnabled" to settings.cellBorderEnabled,
                    "cellBorderColorHex" to settings.cellBorderColorHex,
                    "isLiquidMetalTheme" to settings.isLiquidMetalTheme,
                    "systemNotificationsEnabled" to settings.systemNotificationsEnabled,
                    "playerOnlineNotificationsEnabled" to settings.playerOnlineNotificationsEnabled,
                    "playerInvitesNotificationsEnabled" to settings.playerInvitesNotificationsEnabled,
                    "inAppNotificationsEnabled" to settings.inAppNotificationsEnabled,
                    "inAppPlayerOnlineEnabled" to settings.inAppPlayerOnlineEnabled,
                    "inAppPlayerInvitesEnabled" to settings.inAppPlayerInvitesEnabled
                )
                userDocRef.collection("private").document("settings")
                    .set(settingsPayload, SetOptions.merge()).await()

                if (!user.email.isNullOrBlank()) {
                    userDocRef.collection("private").document("account")
                        .set(mapOf("email" to user.email), SetOptions.merge()).await()
                }

                Log.d(TAG, "Successfully synced user profile and private settings to Firestore for uid: ${user.uid}")

            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync user profile to Firestore: ${e.message}")
                retryAfterNetworkFailure(user.uid, e)
            }
        }
    }

    /** Transactions need connectivity; retain local changes and coalesce one retry while offline. */
    private fun retryAfterNetworkFailure(uid: String, error: Exception) {
        val code = (error as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
        if (code !in setOf(com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
                com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED)) return
        val repository = AuthRepository.activeInstance ?: return
        val generation = repository.sessionGeneration.get()
        synchronized(this) {
            if (syncRetryJob?.isActive == true) return
            syncRetryJob = scope.launch {
                kotlinx.coroutines.delay(10_000L)
                synchronized(this@FirestoreSyncManager) { syncRetryJob = null }
                val current = repository.getPersistedUserSync()
                if (current?.uid != uid || repository.sessionGeneration.get() != generation) return@launch
                syncUserProfile(current, repository.getSettings())
                syncAllMatches(uid, repository.getMatchHistory(uid))
            }
        }
    }

    private fun parseSettings(settingsMap: Map<String, Any?>): UserSettings {
        return UserSettings(
            soundEnabled = settingsMap["soundEnabled"] as? Boolean ?: true,
            hapticsEnabled = settingsMap["hapticsEnabled"] as? Boolean ?: true,
            pickSoundPresetId = settingsMap["pickSoundPresetId"] as? String ?: "classic_pop",
            preferredBoardSize = (settingsMap["preferredBoardSize"] as? Long)?.toInt() ?: 5,
            darkTheme = settingsMap["darkTheme"] as? Boolean ?: com.bingo.multiplayer.core.designsystem.ThemePreferences.isDarkTheme.value,
            accentColorId = settingsMap["accentColorId"] as? String ?: "matte_slate",
            customAccentHex = settingsMap["customAccentHex"] as? String ?: "#64748B",
            customMyPickHex = settingsMap["customMyPickHex"] as? String,
            customOpponentPickHex = settingsMap["customOpponentPickHex"] as? String,
            customRecentPickHex = settingsMap["customRecentPickHex"] as? String,
            customCompletedLineHex = settingsMap["customCompletedLineHex"] as? String,
            cellBorderEnabled = settingsMap["cellBorderEnabled"] as? Boolean ?: false,
            cellBorderColorHex = settingsMap["cellBorderColorHex"] as? String ?: "#FFFFFF",
            isLiquidMetalTheme = settingsMap["isLiquidMetalTheme"] as? Boolean ?: false,
            systemNotificationsEnabled = settingsMap["systemNotificationsEnabled"] as? Boolean ?: true,
            playerOnlineNotificationsEnabled = settingsMap["playerOnlineNotificationsEnabled"] as? Boolean ?: true,
            playerInvitesNotificationsEnabled = settingsMap["playerInvitesNotificationsEnabled"] as? Boolean ?: true,
            inAppNotificationsEnabled = settingsMap["inAppNotificationsEnabled"] as? Boolean ?: true,
            inAppPlayerOnlineEnabled = settingsMap["inAppPlayerOnlineEnabled"] as? Boolean ?: true,
            inAppPlayerInvitesEnabled = settingsMap["inAppPlayerInvitesEnabled"] as? Boolean ?: true
        )
    }

    /**
     * Subscribes to real-time changes on the user's Firestore document and private settings.
     * When remote changes occur (e.g. from another device or cloud admin),
     * automatically invokes the callback to update local state without emitting defaults on empty schemas.
     */
    fun startRealtimeProfileListener(
        uid: String,
        fallbackSettings: UserSettings = UserSettings(),
        onAdminUpdate: (Map<String, Any?>) -> Unit = {},
        onAccountRemoved: () -> Unit = {},
        onProfileError: (Exception) -> Unit = {},
        onProfileUpdated: (UserProfile, UserSettings) -> Unit
    ): Boolean {
        if (uid.isBlank()) return false
        stopRealtimeProfileListener()
        val epoch = profileListenerEpoch
        try {
            var currentProfile: UserProfile? = null
            var currentSettings: UserSettings? = fallbackSettings
            var privateEmail: String? = null

            val userDocRef = firestore.collection(COLLECTION_USERS).document(uid)
            accountRemovalListenerRegistration = firestore.collection("adminTombstones").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error == null && snapshot?.exists() == true) onAccountRemoved()
                }
            val settingsDocRef = userDocRef.collection("private").document("settings")
            val accountDocRef = userDocRef.collection("private").document("account")

            fun notifyIfReady() {
                val p = currentProfile ?: return
                val s = currentSettings ?: return
                val effectiveProfile = if (p.email.isNullOrBlank() && !privateEmail.isNullOrBlank()) {
                    p.copy(email = privateEmail)
                } else p
                onProfileUpdated(effectiveProfile, s)
            }

            profileListenerRegistration = userDocRef.addSnapshotListener { snapshot, error ->
                if (epoch != profileListenerEpoch) return@addSnapshotListener
                if (error != null) {
                    Log.w(TAG, "Profile snapshot listener error: ${error.message}")
                    onProfileError(error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) {
                        // Local write confirmation pending; ignore to prevent echo feedback loop
                        return@addSnapshotListener
                    }
                    try {
                        onAdminUpdate(snapshot.data.orEmpty())
                        val username = snapshot.getString("username") ?: ""
                        val displayName = snapshot.getString("displayName") ?: "Player"
                        val email = snapshot.getString("email")
                        val avatarUrl = snapshot.getString("avatarUrl")
                        val providerStr = snapshot.getString("provider") ?: AuthProvider.GOOGLE.name
                        val provider = runCatching { AuthProvider.valueOf(providerStr) }.getOrDefault(AuthProvider.GOOGLE)
                        val gamesPlayed = snapshot.getLong("gamesPlayed")?.toInt() ?: 0
                        val gamesWon = snapshot.getLong("gamesWon")?.toInt() ?: 0
                        val currentStreak = snapshot.getLong("currentStreak")?.toInt() ?: 0
                        val bestStreak = snapshot.getLong("bestStreak")?.toInt() ?: currentStreak
                        val level = snapshot.getLong("level")?.toInt() ?: 1
                        val xp = snapshot.getLong("xp")?.toInt() ?: 0

                        currentProfile = UserProfile(
                            uid = uid,
                            username = username,
                            displayName = displayName,
                            email = email,
                            avatarUrl = avatarUrl,
                            provider = provider,
                            gamesPlayed = gamesPlayed,
                            gamesWon = gamesWon,
                            currentStreak = currentStreak,
                            bestStreak = bestStreak,
                            level = level,
                            xp = xp
                        )

                        @Suppress("UNCHECKED_CAST")
                        val settingsMap = snapshot.get("settings") as? Map<String, Any?>
                        if (settingsMap != null) {
                            currentSettings = parseSettings(settingsMap)
                        }

                        notifyIfReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Firestore profile snapshot: ${e.message}")
                    }
                }
            }

            privateSettingsListenerRegistration = settingsDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Private settings snapshot listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    val data = snapshot.data
                    if (data != null) {
                        currentSettings = parseSettings(data)
                        notifyIfReady()
                    }
                }
            }

            privateAccountListenerRegistration = accountDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    privateEmail = snapshot.getString("email")
                    notifyIfReady()
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Firestore profile listener: ${e.message}")
            stopRealtimeProfileListener()
            return false
        }
    }

    fun stopRealtimeProfileListener() {
        profileListenerEpoch++
        acknowledgementJob?.cancel()
        acknowledgementJob = null
        acknowledgementRetry?.cancel()
        acknowledgementRetry = null
        accountRemovalListenerRegistration?.remove()
        accountRemovalListenerRegistration = null
        profileListenerRegistration?.remove()
        profileListenerRegistration = null
        privateSettingsListenerRegistration?.remove()
        privateSettingsListenerRegistration = null
        privateAccountListenerRegistration?.remove()
        privateAccountListenerRegistration = null
    }

    /** A separate proof follows durable local application; profile upload is not device receipt. */
    internal fun acknowledgeAdminRevision(uid: String, revision: Long, generation: Long) {
        val repository = AuthRepository.activeInstance ?: return
        if (repository.profileAdminAppliedRevision(uid, generation) != revision) return
        synchronized(this) {
            if (acknowledgementJob?.isActive == true) return
            acknowledgementJob = scope.launch {
                try {
                    val ref = firestore.collection(COLLECTION_USERS).document(uid)
                    val confirmed = firestore.runTransaction { transaction ->
                        val data = transaction.get(ref).data.orEmpty()
                        if (repository.profileAdminAppliedRevision(uid, generation) != revision ||
                            !profileAcknowledgementNeeded(data, revision, NetworkConfig.APP_VERSION_CODE)) return@runTransaction false
                        transaction.update(ref, mapOf(
                            "adminAppliedRevision" to revision,
                            "adminAppliedAt" to FieldValue.serverTimestamp(),
                            "adminAppliedBuild" to NetworkConfig.APP_VERSION_CODE,
                            "appVersion" to NetworkConfig.APP_VERSION_NAME,
                            "appVersionCode" to NetworkConfig.APP_VERSION_CODE
                        ))
                        true
                    }.await()
                    if (confirmed) repository.recordProfileSyncStatus(uid, generation, "ADMIN_ACKNOWLEDGED")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(this@FirestoreSyncManager) {
                        if (acknowledgementRetry?.isActive != true) acknowledgementRetry = scope.launch {
                            kotlinx.coroutines.delay(5_000L)
                            synchronized(this@FirestoreSyncManager) { acknowledgementRetry = null }
                            val latest = repository.profileAdminAppliedRevision(uid, generation)
                            if (latest != null && latest > 0L) acknowledgeAdminRevision(uid, latest, generation)
                        }
                    }
                } finally {
                    synchronized(this@FirestoreSyncManager) { acknowledgementJob = null }
                    val latest = repository.profileAdminAppliedRevision(uid, generation)
                    if (latest != null && latest > revision) acknowledgeAdminRevision(uid, latest, generation)
                }
            }
        }
    }

    // ── 2. MATCH HISTORY SYNC ──

    /**
     * Appends a match record into the subcollection `users/{uid}/matches/{matchId}`.
     */
    private fun matchPayload(record: MatchRecord): Map<String, Any?> = mapOf(
        "id" to record.id, "mode" to record.mode, "opponentName" to record.opponentName,
        "didWin" to record.didWin, "isDraw" to record.isDraw, "boardSize" to record.boardSize,
        "timestamp" to record.timestamp, "matchTitle" to record.matchTitle, "opponentUid" to record.opponentUid,
        "turnsTaken" to record.turnsTaken, "linesCompleted" to record.linesCompleted,
        "durationSeconds" to record.durationSeconds, "finalBoardNumbers" to record.finalBoardNumbers,
        "markedNumbers" to record.markedNumbers
    )

    fun recordMatch(uid: String, record: MatchRecord) = syncAllMatches(uid, listOf(record))

    /** A stale device/legacy backup cannot recreate a record removed in admin. */
    fun syncAllMatches(uid: String, matches: List<MatchRecord>) {
        if (uid.isBlank() || matches.isEmpty()) return
        scope.launch {
            try {
                val owner = firestore.collection(COLLECTION_USERS).document(uid)
                matches.filter { it.id.isNotBlank() }.chunked(400).forEach { chunk ->
                    firestore.runTransaction { transaction ->
                        val policy = CloudAdminPolicy.from(transaction.get(owner).data.orEmpty())
                        chunk.filter(policy::permits).forEach { record ->
                            transaction.set(owner.collection(SUBCOLLECTION_MATCHES).document(record.id),
                                matchPayload(record), SetOptions.merge())
                        }
                    }.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync match history: ${e.message}")
                retryAfterNetworkFailure(uid, e)
            }
        }
    }

    /**
     * Streams match records from Firestore `users/{uid}/matches` ordered by timestamp.
     */
    fun streamMatchHistory(uid: String, maxLimit: Long = 250): Flow<List<MatchRecord>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = firestore.collection(COLLECTION_USERS)
            .document(uid)
            .collection(SUBCOLLECTION_MATCHES)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(maxLimit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore match history listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val finalBoardRaw = doc.get("finalBoardNumbers") as? List<Number>
                            @Suppress("UNCHECKED_CAST")
                            val markedRaw = doc.get("markedNumbers") as? List<Number>

                            MatchRecord(
                                id = doc.getString("id") ?: doc.id,
                                mode = doc.getString("mode") ?: "Online",
                                opponentName = doc.getString("opponentName") ?: "Opponent",
                                didWin = doc.getBoolean("didWin") ?: false,
                                isDraw = doc.getBoolean("isDraw") ?: false,
                                boardSize = doc.getLong("boardSize")?.toInt() ?: 5,
                                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                matchTitle = doc.getString("matchTitle") ?: "",
                                opponentUid = doc.getString("opponentUid") ?: "",
                                turnsTaken = doc.getLong("turnsTaken")?.toInt() ?: 0,
                                linesCompleted = doc.getLong("linesCompleted")?.toInt() ?: 0,
                                durationSeconds = doc.getLong("durationSeconds") ?: 0L,
                                finalBoardNumbers = finalBoardRaw?.map { it.toInt() } ?: emptyList(),
                                markedNumbers = markedRaw?.map { it.toInt() } ?: emptyList()
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    trySend(list)
                }
            }

        awaitClose { listener.remove() }
    }

    /**
     * One-time direct fetch of the player's cloud match records with a strict 3000ms timeout.
     * Used by LeaderboardSyncManager during app boot.
     */
    suspend fun fetchMatchHistoryOnce(uid: String, maxLimit: Long = 250): List<MatchRecord> = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext emptyList()
        try {
            val snapshot = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                firestore.collection(COLLECTION_USERS)
                    .document(uid)
                    .collection(SUBCOLLECTION_MATCHES)
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(maxLimit)
                    .get()
                    .await()
            } ?: return@withContext emptyList()

            snapshot.documents.mapNotNull { doc ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val finalBoardRaw = doc.get("finalBoardNumbers") as? List<Number>
                    @Suppress("UNCHECKED_CAST")
                    val markedRaw = doc.get("markedNumbers") as? List<Number>

                    MatchRecord(
                        id = doc.getString("id") ?: doc.id,
                        mode = doc.getString("mode") ?: "Online",
                        opponentName = doc.getString("opponentName") ?: "Opponent",
                        didWin = doc.getBoolean("didWin") ?: false,
                        isDraw = doc.getBoolean("isDraw") ?: false,
                        boardSize = doc.getLong("boardSize")?.toInt() ?: 5,
                        timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                        matchTitle = doc.getString("matchTitle") ?: "",
                        opponentUid = doc.getString("opponentUid") ?: "",
                        turnsTaken = doc.getLong("turnsTaken")?.toInt() ?: 0,
                        linesCompleted = doc.getLong("linesCompleted")?.toInt() ?: 0,
                        durationSeconds = doc.getLong("durationSeconds") ?: 0L,
                        finalBoardNumbers = finalBoardRaw?.map { it.toInt() } ?: emptyList(),
                        markedNumbers = markedRaw?.map { it.toInt() } ?: emptyList()
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMatchHistoryOnce error: ${e.message}")
            emptyList()
        }
    }

    // ── 3. FRIENDS LIST SYNC ──

    /**
     * Uploads the player's full friends list into Firestore subcollection `users/{uid}/friends`.
     */
    fun syncFriendsList(uid: String, friends: List<Friend>) {
        scope.launch { syncFriendsListConfirmed(uid, friends) }
    }

    /** Success is acknowledged before reconciliation remembers a card as synchronized. */
    internal suspend fun syncFriendsListConfirmed(uid: String, friends: List<Friend>): Boolean = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext false
        if (friends.isEmpty()) return@withContext true
        try {
            for (chunk in friends.chunked(400)) {
                val batch = firestore.batch()
                val collection = firestore.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_FRIENDS)
                chunk.forEach { friend ->
                    val docId = friend.uid.ifBlank { friend.username.trim().lowercase().removePrefix("@") }
                    if (docId.isNotBlank()) batch.set(collection.document(docId), mapOf(
                        "uid" to friend.uid, "username" to friend.username,
                        "displayName" to friend.displayName, "avatarUrl" to friend.avatarUrl,
                        "gamesPlayed" to friend.gamesPlayed, "gamesWon" to friend.gamesWon,
                        "currentStreak" to friend.currentStreak, "level" to friend.level, "adminRevision" to friend.adminRevision
                    ), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync friends to Firestore: ${e.message}")
            false
        }
    }

    /** Explicit acceptance may re-establish a previously removed friendship. Await before notifying. */
    internal suspend fun restoreFriend(uid: String, friend: Friend): Boolean {
        val docId = friend.uid.ifBlank { friend.username.trim().lowercase().removePrefix("@") }
        if (uid.isBlank() || docId.isBlank()) return false
        return try {
            val ref = firestore.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_FRIENDS).document(docId)
            firestore.runTransaction { transaction ->
                val previous = transaction.get(ref)
                transaction.set(ref, mapOf(
                    "uid" to friend.uid, "username" to friend.username,
                    "displayName" to friend.displayName, "avatarUrl" to friend.avatarUrl,
                    "removed" to false, "restoredRemovalId" to previous.getString("removalId")
                ), SetOptions.merge())
                true
            }.await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore accepted friendship backup: ${e.message}")
            false
        }
    }

    /** Read the existing backup before a partial KeyValue roster can replace the local cache. */
    internal suspend fun fetchFriendsList(uid: String): FriendsBackup? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        try {
            val snapshot = firestore.collection(COLLECTION_USERS).document(uid)
                .collection(SUBCOLLECTION_FRIENDS).get().await()
            decodeFriends(snapshot.documents)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read friends backup: ${e.message}")
            null
        }
    }

    /** Retain a removal marker so an older backup/HTTP response cannot resurrect this friend. */
    fun markFriendRemoved(uid: String, friend: Friend, removalId: String) {
        if (uid.isBlank()) return
        val docId = friend.uid.ifBlank { friend.username.trim().lowercase().removePrefix("@") }
        if (docId.isBlank()) return
        try {
            // Enqueue immediately: a later accept must observe this removal, even after an offline restart.
            firestore.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_FRIENDS)
                .document(docId).set(mapOf(
                    "uid" to friend.uid, "username" to friend.username,
                    "removed" to true, "removalId" to removalId, "removedAt" to System.currentTimeMillis()
                ), SetOptions.merge()).addOnFailureListener {
                    Log.w(TAG, "Could not back up friend removal: ${it.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not back up friend removal: ${e.message}")
        }
    }

    internal fun watchFriends(uid: String, onRoster: (com.bingo.multiplayer.domain.repository.FriendsBackup) -> Unit,
        onProfile: (PlayerRegistryEntry) -> Unit): AutoCloseable {
        val lock = Any()
        var closed = false
        val profiles = mutableMapOf<String, ListenerRegistration>()
        val roster = firestore.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_FRIENDS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                synchronized(lock) {
                    if (closed) return@addSnapshotListener
                    val backup = decodeFriends(snapshot.documents)
                    onRoster(backup)
                    val ids = backup.friends.map { it.uid }.filter { it.isNotBlank() }.toSet()
                    (profiles.keys - ids).forEach { profiles.remove(it)?.remove() }
                    for (id in ids - profiles.keys) {
                        profiles[id] = firestore.collection(COLLECTION_USERS).document(id).addSnapshotListener { doc, failure ->
                            synchronized(lock) {
                                if (!closed && id in profiles && failure == null && doc?.exists() == true && !doc.metadata.hasPendingWrites())
                                    onProfile(decodePlayerProfile(doc))
                            }
                        }
                    }
                }
            }
        return AutoCloseable { synchronized(lock) {
            closed = true; roster.remove(); profiles.values.forEach { it.remove() }; profiles.clear()
        } }
    }

    private fun decodeFriends(documents: List<com.google.firebase.firestore.DocumentSnapshot>): com.bingo.multiplayer.domain.repository.FriendsBackup {
        val friends = mutableListOf<Friend>(); val removed = mutableSetOf<String>()
        val restoredKeys = mutableSetOf<String>(); val restoredIds = mutableSetOf<String>()
        documents.forEach { doc ->
            val friend = Friend(uid = doc.getString("uid") ?: doc.id,
                username = doc.getString("username") ?: "", displayName = doc.getString("displayName") ?: doc.getString("username") ?: "Player",
                isOnline = doc.getBoolean("isOnline") ?: false, lastSeenTimestamp = doc.getLong("lastSeenTimestamp") ?: 0L,
                avatarUrl = doc.getString("avatarUrl"), gamesPlayed = doc.getLong("gamesPlayed")?.toInt() ?: 0,
                gamesWon = doc.getLong("gamesWon")?.toInt() ?: 0, currentStreak = doc.getLong("currentStreak")?.toInt() ?: 0,
                level = doc.getLong("level")?.toInt() ?: 1, adminRevision = doc.getLong("adminRevision") ?: 0L)
            if (doc.getBoolean("removed") == true) removed += friendKeys(friend)
            else {
                friends += friend
                doc.getString("restoredRemovalId")?.takeIf { it.isNotBlank() }?.let {
                    restoredIds += it; restoredKeys += friendKeys(friend)
                }
            }
        }
        return com.bingo.multiplayer.domain.repository.FriendsBackup(friends, removed, restoredKeys, restoredIds)
    }

    private fun decodePlayerProfile(doc: com.google.firebase.firestore.DocumentSnapshot): PlayerRegistryEntry {
        val data = CloudAdminPolicy.from(doc.data.orEmpty()).publicProjection(doc.data.orEmpty())
        fun text(key: String) = data[key] as? String
        fun number(key: String, fallback: Int = 0) = (data[key] as? Number)?.toInt() ?: text(key)?.toIntOrNull() ?: fallback
        val key = text("installationPublicKey") ?: text("publicKey")
        if (!key.isNullOrBlank()) AccountKeyStore.recordAuthoritativeKey(doc.id, key)
        return PlayerRegistryEntry(username = text("username") ?: "", uid = doc.id,
            displayName = text("displayName") ?: "Player", avatarUrl = text("avatarUrl"),
            gamesPlayed = number("gamesPlayed"), gamesWon = number("gamesWon"),
            currentStreak = number("currentStreak"), bestStreak = number("bestStreak", number("currentStreak")), level = number("level", 1),
            publicKey = key, adminRevision = (data["adminRevision"] as? Number)?.toLong() ?: 0L)
    }

    /**
     * Retrieves a single player's career profile from Firestore by their UID.
     */
    suspend fun fetchPlayerProfile(uid: String): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        try {
            val snapshot = firestore.collection(COLLECTION_USERS).document(uid).get().await()
            if (snapshot != null && snapshot.exists()) {
                return@withContext decodePlayerProfile(snapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlayerProfile error for $uid: ${e.message}")
        }
        null
    }

    /**
     * Batch-retrieves multiple player career profiles in a single query from Firestore: `users` collection.
     */
    suspend fun fetchPlayerProfiles(uids: List<String>): Map<String, PlayerRegistryEntry> = withContext(Dispatchers.IO) {
        val cleanUids = uids.filter { it.isNotBlank() }.distinct()
        if (cleanUids.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, PlayerRegistryEntry>()
        try {
            cleanUids.chunked(30).forEach { chunk ->
                try {
                    val querySnapshot = kotlinx.coroutines.withTimeoutOrNull(3500L) {
                        firestore.collection(COLLECTION_USERS)
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                            .get()
                            .await()
                    } ?: return@forEach
                    for (doc in querySnapshot.documents) {
                        result[doc.id] = decodePlayerProfile(doc)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchPlayerProfiles chunk error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlayerProfiles error: ${e.message}")
        }
        result
    }

    companion object {
        private const val TAG = "FirestoreSyncManager"
        const val COLLECTION_USERS = "users"
        const val COLLECTION_USERNAMES = "usernames"
        const val SUBCOLLECTION_MATCHES = "matches"
        const val SUBCOLLECTION_FRIENDS = "friends"

        @Volatile
        var instance: FirestoreSyncManager? = null

        fun getInstance(context: Context? = null): FirestoreSyncManager {
            return instance ?: synchronized(this) {
                instance ?: FirestoreSyncManager(context?.applicationContext).also { instance = it }
            }
        }
    }
}

/** A delayed profile backup must not put an older photo back over the current local photo. */
internal fun publicProfileAvatar(user: UserProfile, latest: UserProfile?): String? {
    val current = latest?.takeIf { it.uid == user.uid } ?: user
    return (current.avatarBase64?.takeIf { it.isNotBlank() } ?: current.avatarUrl)
        ?.takeIf { it.isNotBlank() && !isLocalFilePath(it) }
}
