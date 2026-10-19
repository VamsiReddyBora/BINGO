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

    // ── 1. USER PROFILE & THEME SETTINGS SYNC ──

    /**
     * Uploads the full user profile and settings to Firestore: `users/{uid}`.
     * Also claims the username record in `usernames/{username}`.
     */
    fun syncUserProfile(user: UserProfile, settings: UserSettings) {
        if (user.uid.isBlank()) return
        scope.launch {
            try {
                val userDocRef = firestore.collection(COLLECTION_USERS).document(user.uid)
                val payload = hashMapOf<String, Any?>(
                    "uid" to user.uid,
                    "username" to user.username,
                    "displayName" to user.displayName,
                    "email" to user.email,
                    "avatarUrl" to (user.avatarBase64 ?: user.avatarUrl),
                    "provider" to user.provider.name,
                    "gamesPlayed" to user.gamesPlayed,
                    "gamesWon" to user.gamesWon,
                    "currentStreak" to user.currentStreak,
                    "bestStreak" to user.bestStreak,
                    "level" to user.level,
                    "xp" to user.xp,
                    "lastSeenTimestamp" to FieldValue.serverTimestamp(),
                    "appVersion" to user.appVersion,
                    "settings" to hashMapOf(
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
                )

                userDocRef.set(payload, SetOptions.merge()).await()
                Log.d(TAG, "Successfully synced user profile and settings to Firestore for uid: ${user.uid}")

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
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync user profile to Firestore: ${e.message}")
            }
        }
    }

    /**
     * Subscribes to real-time changes on the user's Firestore document.
     * When remote changes occur (e.g. from another device or cloud admin),
     * automatically invokes the callback to update local state.
     */
    fun startRealtimeProfileListener(
        uid: String,
        onProfileUpdated: (UserProfile, UserSettings) -> Unit
    ) {
        if (uid.isBlank()) return
        stopRealtimeProfileListener()

        try {
            val userDocRef = firestore.collection(COLLECTION_USERS).document(uid)
            profileListenerRegistration = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Profile snapshot listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) {
                        // Local write confirmation pending; ignore to prevent echo feedback loop
                        return@addSnapshotListener
                    }
                    try {
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

                        val profile = UserProfile(
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
                        val settings = if (settingsMap != null) {
                            UserSettings(
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
                        } else {
                            UserSettings()
                        }

                        onProfileUpdated(profile, settings)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Firestore profile snapshot: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Firestore profile listener: ${e.message}")
        }
    }

    fun stopRealtimeProfileListener() {
        profileListenerRegistration?.remove()
        profileListenerRegistration = null
    }

    // ── 2. MATCH HISTORY SYNC ──

    /**
     * Appends a match record into the subcollection `users/{uid}/matches/{matchId}`.
     */
    fun recordMatch(uid: String, record: MatchRecord) {
        if (uid.isBlank() || record.id.isBlank()) return
        scope.launch {
            try {
                val matchDoc = firestore.collection(COLLECTION_USERS)
                    .document(uid)
                    .collection(SUBCOLLECTION_MATCHES)
                    .document(record.id)

                val payload = hashMapOf<String, Any?>(
                    "id" to record.id,
                    "mode" to record.mode,
                    "opponentName" to record.opponentName,
                    "didWin" to record.didWin,
                    "isDraw" to record.isDraw,
                    "boardSize" to record.boardSize,
                    "timestamp" to record.timestamp,
                    "matchTitle" to record.matchTitle,
                    "opponentUid" to record.opponentUid,
                    "turnsTaken" to record.turnsTaken,
                    "linesCompleted" to record.linesCompleted,
                    "durationSeconds" to record.durationSeconds,
                    "finalBoardNumbers" to record.finalBoardNumbers,
                    "markedNumbers" to record.markedNumbers
                )

                matchDoc.set(payload, SetOptions.merge()).await()
                Log.d(TAG, "Recorded match ${record.id} in Firestore subcollection for uid $uid")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record match in Firestore: ${e.message}")
            }
        }
    }

    /**
     * Bulk uploads an entire list of match records to `users/{uid}/matches`.
     */
    fun syncAllMatches(uid: String, matches: List<MatchRecord>) {
        if (uid.isBlank() || matches.isEmpty()) return
        scope.launch {
            try {
                matches.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    val matchesColl = firestore.collection(COLLECTION_USERS)
                        .document(uid)
                        .collection(SUBCOLLECTION_MATCHES)

                    chunk.forEach { record ->
                        val docRef = matchesColl.document(record.id)
                        val payload = hashMapOf<String, Any?>(
                            "id" to record.id,
                            "mode" to record.mode,
                            "opponentName" to record.opponentName,
                            "didWin" to record.didWin,
                            "isDraw" to record.isDraw,
                            "boardSize" to record.boardSize,
                            "timestamp" to record.timestamp,
                            "matchTitle" to record.matchTitle,
                            "opponentUid" to record.opponentUid,
                            "turnsTaken" to record.turnsTaken,
                            "linesCompleted" to record.linesCompleted,
                            "durationSeconds" to record.durationSeconds,
                            "finalBoardNumbers" to record.finalBoardNumbers,
                            "markedNumbers" to record.markedNumbers
                        )
                        batch.set(docRef, payload, SetOptions.merge())
                    }
                    batch.commit().await()
                }
                Log.d(TAG, "Successfully bulk-synced ${matches.size} matches to Firestore for uid $uid")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bulk-sync matches to Firestore: ${e.message}")
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
        if (uid.isBlank()) return
        scope.launch {
            try {
                val batch = firestore.batch()
                val friendsColl = firestore.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_FRIENDS)

                friends.forEach { friend ->
                    val docId = friend.uid.ifBlank { friend.username.trim().lowercase().removePrefix("@") }
                    if (docId.isNotBlank()) {
                        val docRef = friendsColl.document(docId)
                        val data = hashMapOf(
                            "uid" to friend.uid,
                            "username" to friend.username,
                            "displayName" to friend.displayName,
                            "avatarUrl" to friend.avatarUrl,
                            "isOnline" to friend.isOnline,
                            "lastSeenTimestamp" to friend.lastSeenTimestamp,
                            "gamesPlayed" to friend.gamesPlayed,
                            "gamesWon" to friend.gamesWon,
                            "currentStreak" to friend.currentStreak,
                            "level" to friend.level
                        )
                        batch.set(docRef, data, SetOptions.merge())
                    }
                }
                batch.commit().await()
                Log.d(TAG, "Synced ${friends.size} friends to Firestore subcollection for uid $uid")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync friends to Firestore: ${e.message}")
            }
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
            val friends = mutableListOf<Friend>()
            val removed = mutableSetOf<String>()
            val restoredKeys = mutableSetOf<String>()
            val restoredIds = mutableSetOf<String>()
            snapshot.documents.forEach { doc ->
                val friend = Friend(
                    uid = doc.getString("uid") ?: doc.id,
                    username = doc.getString("username") ?: "",
                    displayName = doc.getString("displayName") ?: doc.getString("username") ?: "Player",
                    avatarUrl = doc.getString("avatarUrl"),
                    isOnline = doc.getBoolean("isOnline") ?: false,
                    lastSeenTimestamp = doc.getLong("lastSeenTimestamp") ?: 0L,
                    gamesPlayed = doc.getLong("gamesPlayed")?.toInt() ?: 0,
                    gamesWon = doc.getLong("gamesWon")?.toInt() ?: 0,
                    currentStreak = doc.getLong("currentStreak")?.toInt() ?: 0,
                    level = doc.getLong("level")?.toInt() ?: 1
                )
                if (doc.getBoolean("removed") == true) removed += friendKeys(friend)
                else {
                    friends += friend
                    doc.getString("restoredRemovalId")?.takeIf { it.isNotBlank() }?.let {
                        restoredIds += it
                        restoredKeys += friendKeys(friend)
                    }
                }
            }
            FriendsBackup(friends, removed, restoredKeys, restoredIds)
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
                    "removed" to true, "removalId" to removalId
                ), SetOptions.merge()).addOnFailureListener {
                    Log.w(TAG, "Could not back up friend removal: ${it.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not back up friend removal: ${e.message}")
        }
    }

    /**
     * Retrieves a single player's career profile from Firestore by their UID.
     */
    suspend fun fetchPlayerProfile(uid: String): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        try {
            val snapshot = firestore.collection(COLLECTION_USERS).document(uid).get().await()
            if (snapshot != null && snapshot.exists()) {
                val username = snapshot.getString("username") ?: ""
                val displayName = snapshot.getString("displayName") ?: "Player"
                val avatarUrl = snapshot.getString("avatarUrl")
                val gamesPlayed = snapshot.getLong("gamesPlayed")?.toInt() ?: 0
                val gamesWon = snapshot.getLong("gamesWon")?.toInt() ?: 0
                val currentStreak = snapshot.getLong("currentStreak")?.toInt() ?: 0
                val bestStreak = snapshot.getLong("bestStreak")?.toInt() ?: currentStreak
                val level = snapshot.getLong("level")?.toInt() ?: 1
                return@withContext PlayerRegistryEntry(
                    username = username,
                    uid = uid,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    gamesPlayed = gamesPlayed,
                    gamesWon = gamesWon,
                    currentStreak = currentStreak,
                    bestStreak = bestStreak,
                    level = level
                )
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
                        val uid = doc.id
                        val username = doc.getString("username") ?: ""
                        val displayName = doc.getString("displayName") ?: "Player"
                        val avatarUrl = doc.getString("avatarUrl")
                        val gamesPlayed = (doc.get("gamesPlayed") as? Number)?.toInt() ?: doc.getString("gamesPlayed")?.toIntOrNull() ?: doc.getLong("gamesPlayed")?.toInt() ?: 0
                        val gamesWon = (doc.get("gamesWon") as? Number)?.toInt() ?: doc.getString("gamesWon")?.toIntOrNull() ?: doc.getLong("gamesWon")?.toInt() ?: 0
                        val currentStreak = (doc.get("currentStreak") as? Number)?.toInt() ?: doc.getString("currentStreak")?.toIntOrNull() ?: doc.getLong("currentStreak")?.toInt() ?: 0
                        val bestStreak = (doc.get("bestStreak") as? Number)?.toInt() ?: doc.getString("bestStreak")?.toIntOrNull() ?: doc.getLong("bestStreak")?.toInt() ?: currentStreak
                        val level = (doc.get("level") as? Number)?.toInt() ?: doc.getString("level")?.toIntOrNull() ?: doc.getLong("level")?.toInt() ?: 1
                        result[uid] = PlayerRegistryEntry(
                            username = username,
                            uid = uid,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            gamesPlayed = gamesPlayed,
                            gamesWon = gamesWon,
                            currentStreak = currentStreak,
                            bestStreak = bestStreak,
                            level = level
                        )
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
