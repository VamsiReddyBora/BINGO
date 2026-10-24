package com.bingo.multiplayer.domain.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bingo.multiplayer.domain.network.AccountSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Authentication Gatekeeper Repository.
 * Persists Google Sign-In sessions, saved player profiles, statistics and settings.
 */
class AuthRepository(
    private val context: Context? = null,
    private val customPrefs: SharedPreferences? = null
) {

    private val prefs: SharedPreferences =
        customPrefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: throw IllegalStateException("Either context or customPrefs must be provided")

    private val _authState = MutableStateFlow<AuthState>(
        getPersistedUserSync()?.let { AuthState.Authenticated(it) } ?: AuthState.Loading
    )
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    private val _matchHistoryVersion = MutableStateFlow(0L)
    val matchHistoryVersion: StateFlow<Long> = _matchHistoryVersion.asStateFlow()
    private var cloudListenerUid: String? = null
    private var cloudListenerGeneration = -1L

    private val scope = CoroutineScope(Dispatchers.IO)
    internal val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        activeInstance = this
    }

    val sessionManager = AccountSessionManager()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = "dev_" + UUID.randomUUID().toString().take(12)
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }

    val deviceModel: String
        get() = AccountSessionManager.getDeviceModelName()

    fun isGoogleUserRegistered(googleId: String): Boolean {
        return prefs.getBoolean("google_registered_$googleId", false)
    }

    fun setGoogleUserRegistered(googleId: String, registered: Boolean = true) {
        prefs.edit().putBoolean("google_registered_$googleId", registered).apply()
    }

    fun getSavedGoogleDisplayName(googleId: String): String? {
        return prefs.getString("google_name_$googleId", null)
    }

    fun setSavedGoogleDisplayName(googleId: String, name: String) {
        prefs.edit().putString("google_name_$googleId", name).apply()
    }

    fun getSavedGoogleUsername(googleId: String): String? {
        return prefs.getString("google_username_$googleId", null)
    }

    fun setSavedGoogleUsername(googleId: String, username: String) {
        prefs.edit().putString("google_username_$googleId", username).apply()
    }

    private val aliasReleaseLock = Any()

    init {
        checkPersistedSession()
        retryPendingAliasReleases()
    }

    /**
     * Checks if the user is already authenticated on app launch.
     */
    fun checkPersistedSession() {
        scope.launch {
            verifyPersistedSession()
            retryPendingAliasReleases()
        }
    }

    /**
     * Reads the persisted user profile directly and synchronously from SharedPreferences.
     * Prevents race conditions during cold start when AuthState is temporarily Loading.
     */
    fun getPersistedUserSync(): UserProfile? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val displayName = prefs.getString(KEY_NAME, "Player") ?: "Player"
        val username = prefs.getString(KEY_USERNAME, null) ?: displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { uid.take(8) }
        val email = prefs.getString(KEY_EMAIL, null)
        val avatarUrl = prefs.getString(KEY_AVATAR, null)
        val avatarBase64 = prefs.getString(KEY_AVATAR_BASE64, null)
        val providerStr = prefs.getString(KEY_PROVIDER, AuthProvider.GOOGLE.name)
        val provider = runCatching { AuthProvider.valueOf(providerStr!!) }
            .getOrDefault(AuthProvider.GOOGLE)
        val gamesPlayed = prefs.getInt(KEY_GAMES_PLAYED, 0)
        val gamesWon = prefs.getInt(KEY_GAMES_WON, 0)
        val streak = prefs.getInt(KEY_STREAK, 0)
        val bestStreak = maxOf(prefs.getInt(KEY_BEST_STREAK, streak), streak)
        val level = prefs.getInt(KEY_LEVEL, 1)
        val xp = prefs.getInt(KEY_XP, 0)

        return UserProfile(
            uid = uid,
            username = username,
            displayName = displayName,
            email = email,
            avatarUrl = avatarUrl,
            avatarBase64 = avatarBase64,
            provider = provider,
            gamesPlayed = gamesPlayed,
            gamesWon = gamesWon,
            currentStreak = streak,
            bestStreak = bestStreak,
            level = level,
            xp = xp,
            adminRevision = prefs.getLong("${uid}_admin_revision", 0L)
        )
    }

    /**
     * Awaits authenticated user profile resolution, or falls back to synchronous cache.
     */
    suspend fun awaitAuthenticatedUser(timeoutMs: Long = 3000L): UserProfile? {
        val current = (_authState.value as? AuthState.Authenticated)?.user
        if (current != null) return current

        if (_authState.value !is AuthState.Loading) {
            return getPersistedUserSync()
        }

        val start = System.currentTimeMillis()
        while (_authState.value is AuthState.Loading && (System.currentTimeMillis() - start) < timeoutMs) {
            delay(50L)
        }

        return (_authState.value as? AuthState.Authenticated)?.user ?: getPersistedUserSync()
    }

    suspend fun verifyPersistedSession(): AuthState = withContext(Dispatchers.IO) {
        val (state, capturedGen) = synchronized(this@AuthRepository) {
            val profile = getPersistedUserSync()
            val resolved = if (profile != null) AuthState.Authenticated(profile) else AuthState.Unauthenticated
            _authState.value = resolved
            Pair(resolved, sessionGeneration.get())
        }
        if (state is AuthState.Authenticated) {
            val user = state.user
            if (sessionGeneration.get() != capturedGen) return@withContext state
            com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(user.username)
            com.bingo.multiplayer.domain.network.PresenceManager.startPresence(user.username)
            val googleId = if (user.uid.startsWith("google_")) user.uid.removePrefix("google_") else null
            if (sessionGeneration.get() != capturedGen) return@withContext state
            val userKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(user.uid)
            sessionManager.claimUsername(
                com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                    username = user.username,
                    uid = user.uid,
                    displayName = user.displayName,
                    avatarUrl = user.avatarBase64 ?: user.avatarUrl,
                    gamesPlayed = user.gamesPlayed,
                    gamesWon = user.gamesWon,
                    currentStreak = user.currentStreak,
                    bestStreak = user.bestStreak,
                    level = user.level,
                    publicKey = userKey
                ),
                googleId = googleId
            )
            if (user.provider == AuthProvider.GOOGLE && googleId != null) {
                if (sessionGeneration.get() == capturedGen) {
                    sessionManager.startSessionWatcher(googleId, deviceId) {
                        if (sessionGeneration.get() == capturedGen) {
                            signOut()
                        }
                    }
                }
                scope.launch {
                    if (sessionGeneration.get() == capturedGen) {
                        restoreCloudUserData(googleId)
                    }
                }
            }

            startCloudSynchronization(user, capturedGen)
        }
        state
    }

    /** Called by both the Firebase listener and its guarded profile-upload transaction. */
    @Synchronized
    internal fun applyCloudAdminSnapshot(snapshot: Map<String, Any?>, uid: String, expectedGen: Long): Boolean {
        if (sessionGeneration.get() != expectedGen) return false
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
        if (current.uid != uid || getPersistedUserSync()?.uid != uid) return false
        val policy = com.bingo.multiplayer.domain.network.CloudAdminPolicy.from(snapshot)
        if (policy.revision <= current.adminRevision) return false
        val updated = policy.applyTo(current)
        val retained = getMatchHistory(uid).filter(policy::permits)
        val historyJson = Json.encodeToString(retained)
        // Store deletion intent before acknowledging the revision; a restart can safely replay it.
        val historySaved = prefs.edit().putString("${uid}_admin_deleted_matches", Json.encodeToString(policy.deletedMatchIds.toList()))
            .putLong("${uid}_admin_clear_matches_before", policy.clearMatchesBefore)
            .putString("${uid}_match_history", historyJson).putString("match_history", historyJson).commit()
        if (!historySaved) return false
        if (!persistUser(updated, expectedGeneration = expectedGen, syncToCloud = false)) return false
        _authState.value = AuthState.Authenticated(updated)
        if (updated.uid.startsWith("google_")) setSavedGoogleDisplayName(updated.uid.removePrefix("google_"), updated.displayName)
        _matchHistoryVersion.value++
        if (policy.fields.any { (key, change) -> key.startsWith("avatar") && change.revision > current.adminRevision }) {
            com.bingo.multiplayer.domain.network.ProfileAvatars.state.adminChange(updated.username,
                updated.avatarBase64 ?: updated.avatarUrl)
        }
        return true
    }

    private fun startCloudSynchronization(user: UserProfile, capturedGen: Long, token: String? = null) {
        val ctx = context ?: return
        scope.launch(Dispatchers.IO) {
            val tokenToUse = token?.takeIf { !it.startsWith("google_token_") && !it.startsWith("guest_token_") && user.provider == AuthProvider.GOOGLE }
            if (!awaitFirebaseAuth(tokenToUse, capturedGen, user.uid) || sessionGeneration.get() != capturedGen) return@launch
            val firestoreSync = com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(ctx)
            synchronized(this@AuthRepository) {
                if (sessionGeneration.get() != capturedGen || getPersistedUserSync()?.uid != user.uid) return@launch
                if (cloudListenerUid != user.uid || cloudListenerGeneration != capturedGen) {
                    cloudListenerUid = user.uid
                    cloudListenerGeneration = capturedGen
                    firestoreSync.startRealtimeProfileListener(user.uid,
                        fallbackSettings = getSettings(),
                        onAdminUpdate = { snapshot -> applyCloudAdminSnapshot(snapshot, user.uid, capturedGen) },
                        onAccountRemoved = { removeAdminAccount(user.uid, capturedGen) }
                    ) { _, remoteSettings ->
                        // Preserve local dark theme, sound/vibration, and notification preferences to prevent remote echo from reverting local settings
                        val localDark = com.bingo.multiplayer.core.designsystem.ThemePreferences.isDarkTheme.value
                        val localSound = com.bingo.multiplayer.core.designsystem.SoundPreferences.soundEnabled.value
                        val localHaptics = com.bingo.multiplayer.core.designsystem.SoundPreferences.hapticsEnabled.value
                        val localPreset = com.bingo.multiplayer.core.designsystem.SoundPreferences.pickSoundPresetId.value
                        val isNotifsConfigured = com.bingo.multiplayer.core.designsystem.NotificationPreferences.isLocallyConfigured(ctx)
                        val consistentSettings = remoteSettings.copy(
                            darkTheme = localDark,
                            soundEnabled = localSound,
                            hapticsEnabled = localHaptics,
                            pickSoundPresetId = localPreset,
                            systemNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.systemNotificationsEnabled.value else remoteSettings.systemNotificationsEnabled,
                            playerOnlineNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerOnlineNotificationsEnabled.value else remoteSettings.playerOnlineNotificationsEnabled,
                            playerInvitesNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerInvitesNotificationsEnabled.value else remoteSettings.playerInvitesNotificationsEnabled,
                            inAppNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppNotificationsEnabled.value else remoteSettings.inAppNotificationsEnabled,
                            inAppPlayerOnlineEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerOnlineEnabled.value else remoteSettings.inAppPlayerOnlineEnabled,
                            inAppPlayerInvitesEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerInvitesEnabled.value else remoteSettings.inAppPlayerInvitesEnabled
                        )
                        updateSettings(consistentSettings, syncToCloud = false, expectedGen = capturedGen)
                    }
                }
            }
            // Upload uses a transaction that reads the latest admin edits before writing.
            firestoreSync.syncUserProfile(getPersistedUserSync()?.takeIf { it.uid == user.uid } ?: return@launch, getSettings())
            val matches = getMatchHistory(user.uid)
            if (matches.isNotEmpty()) firestoreSync.syncAllMatches(user.uid, matches)
        }
    }

    @Synchronized
    internal fun removeAdminAccount(uid: String, expectedGen: Long): Boolean {
        if (sessionGeneration.get() != expectedGen || getPersistedUserSync()?.uid != uid) return false
        // A removed account must not back up its old profile during the forced sign-out.
        signOut(backUpToCloud = false)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${uid}_") || it.startsWith("applied_result_${uid}:") }
            .forEach(editor::remove)
        editor.apply()
        _matchHistoryVersion.value++
        return true
    }

    /**
     * Called after a successful real Google Sign-In (account picker).
     */
    @Synchronized
    fun onGoogleSignInSuccess(
        googleId: String,
        displayName: String,
        email: String,
        customUsername: String? = null,
        idToken: String? = null
    ) {
        val genAtLogin = sessionGeneration.incrementAndGet()
        setGoogleUserRegistered(googleId, true)
        setSavedGoogleDisplayName(googleId, displayName)
        val targetUid = "google_$googleId"
        sessionManager.startSessionWatcher(googleId, deviceId) {
            if (sessionGeneration.get() == genAtLogin && (_authState.value as? AuthState.Authenticated)?.user?.uid == targetUid) {
                signOut()
            }
        }
        ensureFirebaseAuth(idToken)
        val existing = loadExistingStats(targetUid)
        val cleanUser = customUsername?.trim()?.lowercase()?.removePrefix("@")
            ?: displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { "user_$googleId".take(12) }
        setSavedGoogleUsername(googleId, cleanUser)

        val profile = UserProfile(
            uid = targetUid,
            username = cleanUser,
            displayName = displayName,
            email = email,
            avatarUrl = existing.avatarUrl,
            avatarBase64 = existing.avatarBase64,
            provider = AuthProvider.GOOGLE,
            gamesPlayed = existing.gamesPlayed,
            gamesWon = existing.gamesWon,
            currentStreak = existing.currentStreak,
            bestStreak = existing.bestStreak,
            level = existing.level,
            xp = existing.xp,
            adminRevision = existing.adminRevision
        )
        persistUser(profile, idToken ?: "google_token_$googleId")
        val googleHostKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(targetUid)
        sessionManager.claimUsername(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = cleanUser,
                uid = "google_$googleId",
                displayName = displayName,
                avatarUrl = profile.avatarBase64 ?: existing.avatarUrl,
                gamesPlayed = profile.gamesPlayed,
                gamesWon = profile.gamesWon,
                currentStreak = profile.currentStreak,
                bestStreak = profile.bestStreak,
                level = profile.level,
                publicKey = googleHostKey
            ),
            googleId = googleId
        )
        _authState.value = AuthState.Authenticated(profile)
        backupUserDataToCloud()
    }

    /**
     * Guest login — no account needed, instant access.
     */
    fun onGuestLogin(customName: String? = null, customUsername: String? = null) {
        sessionGeneration.incrementAndGet()
        ensureFirebaseAuth(null)
        val auth = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance() }.getOrNull()
        val anonUid = auth?.currentUser?.uid
        val guestId = runCatching { com.bingo.multiplayer.domain.notifications.NotificationIdentity.guestUid() }.getOrNull()
            ?: if (!anonUid.isNullOrBlank()) "guest_$anonUid"
            else "guest_${System.currentTimeMillis()}"
        val existing = loadExistingStats(guestId)
        val finalName = customName?.trim()?.ifEmpty { null } ?: "Guest"
        val cleanUser = customUsername?.trim()?.lowercase()?.removePrefix("@")
            ?: finalName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { guestId.take(10) }

        val profile = UserProfile(
            uid = guestId,
            username = cleanUser,
            displayName = finalName,
            email = null,
            avatarUrl = existing.avatarUrl,
            provider = AuthProvider.GUEST,
            gamesPlayed = existing.gamesPlayed,
            gamesWon = existing.gamesWon,
            currentStreak = existing.currentStreak,
            bestStreak = existing.bestStreak,
            level = existing.level,
            xp = existing.xp,
            adminRevision = existing.adminRevision
        )
        persistUser(profile, "guest_token_$guestId")
        com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(cleanUser)
        com.bingo.multiplayer.domain.network.PresenceManager.startPresence(cleanUser)
        val guestHostKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(guestId)
        sessionManager.claimUsername(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = cleanUser,
                uid = guestId,
                displayName = finalName,
                avatarUrl = profile.avatarBase64 ?: existing.avatarUrl,
                gamesPlayed = profile.gamesPlayed,
                gamesWon = profile.gamesWon,
                currentStreak = profile.currentStreak,
                bestStreak = profile.bestStreak,
                level = profile.level,
                publicKey = guestHostKey
            )
        )
        _authState.value = AuthState.Authenticated(profile)
    }

    /**
     * Updates the player's unique username (Player ID) if available.
     */
    suspend fun updateUsername(newUsername: String): Boolean {
        val clean = newUsername.trim().lowercase().removePrefix("@")
        if (clean.length < 3 || clean.length > 20 || !clean.all { it.isLetterOrDigit() || it == '_' }) return false
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
        val expectedGen = sessionGeneration.get()
        val available = sessionManager.checkUsernameAvailable(clean, current.uid)
        if (!available) return false
        if (sessionGeneration.get() != expectedGen || (_authState.value as? AuthState.Authenticated)?.user?.uid != current.uid) return false

        val updated = current.copy(username = clean)
        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
        val updatedKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(updated.uid)
        val claimOk = sessionManager.claimUsernameSync(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = clean,
                uid = updated.uid,
                displayName = updated.displayName,
                avatarUrl = updated.avatarBase64 ?: updated.avatarUrl,
                gamesPlayed = updated.gamesPlayed,
                gamesWon = updated.gamesWon,
                currentStreak = updated.currentStreak,
                bestStreak = updated.bestStreak,
                level = updated.level,
                publicKey = updatedKey
            ),
            googleId = googleId
        )
        if (!claimOk) return false
        val success = synchronized(this@AuthRepository) {
            if (sessionGeneration.get() != expectedGen || (_authState.value as? AuthState.Authenticated)?.user?.uid != current.uid) return@synchronized false
            val persisted = persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""), expectedGeneration = expectedGen)
            if (!persisted || sessionGeneration.get() != expectedGen || (_authState.value as? AuthState.Authenticated)?.user?.uid != current.uid) return@synchronized false
            _authState.value = AuthState.Authenticated(updated)
            true
        }
        if (!success) return false

        com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(clean)
        com.bingo.multiplayer.domain.network.PresenceManager.startPresence(clean)
        backupUserDataToCloud()
        val oldClean = current.username.lowercase().trim().removePrefix("@")
        if (oldClean.isNotBlank() && oldClean != clean) {
            val released = sessionManager.releaseUsernameAlias(oldClean, current.uid)
            synchronized(aliasReleaseLock) {
                val pending = prefs.getStringSet("pending_alias_releases", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (!released) {
                    pending.add("$oldClean:${current.uid}")
                } else {
                    pending.remove("$oldClean:${current.uid}")
                }
                prefs.edit().putStringSet("pending_alias_releases", pending).apply()
            }
        }
        retryPendingAliasReleases()
        return true
    }

    fun retryPendingAliasReleases() {
        scope.launch(Dispatchers.IO) {
            val pending = synchronized(aliasReleaseLock) {
                prefs.getStringSet("pending_alias_releases", emptySet())?.toSet().orEmpty()
            }
            if (pending.isEmpty()) return@launch
            val acknowledged = mutableSetOf<String>()
            for (entry in pending) {
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val alias = parts[0]
                    val uid = parts[1]
                    if (sessionManager.releaseUsernameAlias(alias, uid)) {
                        acknowledged.add(entry)
                    }
                }
            }
            if (acknowledged.isNotEmpty()) {
                synchronized(aliasReleaseLock) {
                    val current = prefs.getStringSet("pending_alias_releases", emptySet())?.toMutableSet() ?: mutableSetOf()
                    current.removeAll(acknowledged)
                    prefs.edit().putStringSet("pending_alias_releases", current).apply()
                }
            }
        }
    }

    /**
     * Updates the player's display name and persists it.
     */
    fun updateDisplayName(newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
        val updated = current.copy(displayName = trimmed)
        persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""))
        _authState.value = AuthState.Authenticated(updated)
        backupUserDataToCloud()
        return true
    }

    /**
     * Copies an image chosen from local storage to app-internal storage
     * and persists the file path as the player's profile avatar.
     */
    fun saveProfileAvatar(uri: Uri?): Boolean {
        val expectedGen = sessionGeneration.get()
        return try {
            val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
            val cleanUser = current.username.trim().lowercase().removePrefix("@")

            val filesDir = context?.filesDir ?: context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            val targetFile = File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            val stream = contentStreamProvider?.invoke(uri) ?: (uri?.let { context?.contentResolver?.openInputStream(it) })
            if (stream == null) return false
            stream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Downsample and compress to Base64 for cross-device cloud persistence
            var decodedBitmap: android.graphics.Bitmap? = null
            val maxDim = 160
            val base64 = try {
                if (bitmapDecoderOverride != null) {
                    val overrideBmp = bitmapDecoderOverride?.invoke(targetFile.absolutePath)
                    if (overrideBmp != null) {
                        val dims = bitmapDimensionsOverride?.invoke(overrideBmp) ?: Pair(overrideBmp.width, overrideBmp.height)
                        val scaled = if (dims.first > maxDim || dims.second > maxDim) {
                            val (w, h) = computeTargetDimensions(dims.first, dims.second, maxDim)
                            val s = bitmapScalerOverride?.invoke(overrideBmp, w, h) ?: android.graphics.Bitmap.createScaledBitmap(overrideBmp, w, h, true)
                            if (s != overrideBmp) {
                                overrideBmp.recycle()
                            }
                            s
                        } else overrideBmp
                        decodedBitmap = scaled
                        val baos = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                        android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    } else null
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, bounds)
                    val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, opts)
                    if (bitmap != null) {
                        val dims = bitmapDimensionsOverride?.invoke(bitmap) ?: Pair(bitmap.width, bitmap.height)
                        val scaled = if (dims.first > maxDim || dims.second > maxDim) {
                            val (w, h) = computeTargetDimensions(dims.first, dims.second, maxDim)
                            val s = bitmapScalerOverride?.invoke(bitmap, w, h) ?: android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                            if (s != bitmap) {
                                bitmap.recycle()
                            }
                            s
                        } else bitmap
                        decodedBitmap = scaled
                        val baos = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                        android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    } else null
                }
            } catch (_: Exception) {
                null
            }

            if (decodedBitmap == null) {
                try { targetFile.delete() } catch (_: Exception) {}
                return false
            }

            val oldPath = current.avatarUrl
            val imageBmp = decodedBitmap.asImageBitmap()

            var committedProfile: UserProfile? = null
            val success = synchronized(this@AuthRepository) {
                if (sessionGeneration.get() != expectedGen || (_authState.value as? AuthState.Authenticated)?.user?.uid != current.uid) {
                    try { targetFile.delete() } catch (_: Exception) {}
                    return@synchronized false
                }

                // Delete old file only after ownership and generation fence pass
                oldPath?.let { p ->
                    try {
                        val oldFile = File(p)
                        if (oldFile.exists() && oldFile.parent == context?.filesDir?.absolutePath && oldFile.absolutePath != targetFile.absolutePath) {
                            oldFile.delete()
                        }
                    } catch (_: Exception) {}
                }

                // Pre-populate memory cache under lock
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(current.username, oldPath)
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put(targetFile.absolutePath, imageBmp)
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:$cleanUser", imageBmp)
                if (!base64.isNullOrBlank()) {
                    com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put(base64, imageBmp)
                }
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(cleanUser)

                val updated = current.copy(
                    avatarUrl = targetFile.absolutePath,
                    avatarBase64 = base64
                )
                val persisted = persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""), expectedGeneration = expectedGen)
                if (!persisted || sessionGeneration.get() != expectedGen || (_authState.value as? AuthState.Authenticated)?.user?.uid != current.uid) {
                    try { targetFile.delete() } catch (_: Exception) {}
                    return@synchronized false
                }
                _authState.value = AuthState.Authenticated(updated)
                committedProfile = updated
                true
            }

            if (!success) return false
            val finalUser = committedProfile ?: return false
            if (sessionGeneration.get() != expectedGen) return false
            com.bingo.multiplayer.domain.network.ProfileAvatars.state.localChange(finalUser.username, base64)
            context?.let { com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(it)
                .syncUserProfile(finalUser, getSettings()) }

            backupUserDataToCloud()
            val googleId = if (finalUser.uid.startsWith("google_")) finalUser.uid.removePrefix("google_") else null
            val finalUserKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(finalUser.uid)
            sessionManager.claimUsername(
                com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                    username = finalUser.username,
                    uid = finalUser.uid,
                    displayName = finalUser.displayName,
                    avatarUrl = finalUser.avatarBase64 ?: finalUser.avatarUrl,
                    gamesPlayed = finalUser.gamesPlayed,
                    gamesWon = finalUser.gamesWon,
                    currentStreak = finalUser.currentStreak,
                    bestStreak = finalUser.bestStreak,
                    level = finalUser.level,
                    publicKey = finalUserKey
                ),
                googleId = googleId
            )
            scope.launch {
                if (sessionGeneration.get() == expectedGen) {
                    // Photos persist in Firebase; visible avatars share its live public-profile watch.
                    // 3. Update active match / lobby / friends
                    com.bingo.multiplayer.domain.network.OnlineRoomSyncManager.activeInstance?.updateLocalAvatar(base64)
                    com.bingo.multiplayer.domain.network.LanP2pSessionManager.activeInstance?.updateLocalAvatar(base64)
                    com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.onRemoteAvatarUpdated(finalUser.username, base64)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restoreAvatarFromBase64(base64: String, uid: String): String? {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
            val ctx = context ?: return null
            val filesDir = ctx.filesDir ?: ctx.cacheDir
            val targetFile = File(filesDir, "avatar_${uid.filter { it.isLetterOrDigit() }}.jpg")
            targetFile.writeBytes(bytes)
            targetFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clears the current custom profile avatar.
     */
    fun removeProfileAvatar(): Boolean {
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
        val cleanUser = current.username.trim().lowercase().removePrefix("@")
        current.avatarUrl?.let { oldPath ->
            try {
                val oldFile = File(oldPath)
                if (context != null && oldFile.exists() && oldFile.parent == context.filesDir?.absolutePath) {
                    oldFile.delete()
                }
            } catch (_: Exception) {}
        }

        // 1. Evict and notify local UI
        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(current.username, current.avatarUrl)
        com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(cleanUser)

        val updated = current.copy(avatarUrl = null, avatarBase64 = null)
        persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""))
        _authState.value = AuthState.Authenticated(updated)
        com.bingo.multiplayer.domain.network.ProfileAvatars.state.localChange(cleanUser, null)
        context?.let { com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(it)
            .syncUserProfile(updated, getSettings()) }
        backupUserDataToCloud()

        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
        val updatedUserKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(updated.uid)
        sessionManager.claimUsername(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = updated.username,
                uid = updated.uid,
                displayName = updated.displayName,
                avatarUrl = null,
                gamesPlayed = updated.gamesPlayed,
                gamesWon = updated.gamesWon,
                currentStreak = updated.currentStreak,
                bestStreak = updated.bestStreak,
                level = updated.level,
                publicKey = updatedUserKey
            ),
            googleId = googleId
        )

        scope.launch {
            com.bingo.multiplayer.domain.network.OnlineRoomSyncManager.activeInstance?.updateLocalAvatar(null)
            com.bingo.multiplayer.domain.network.LanP2pSessionManager.activeInstance?.updateLocalAvatar(null)
            com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.onRemoteAvatarUpdated(cleanUser, null)
        }
        return true
    }

    fun getSettings(): com.bingo.multiplayer.domain.model.UserSettings {
        return com.bingo.multiplayer.domain.model.UserSettings(
            soundEnabled = com.bingo.multiplayer.core.designsystem.SoundPreferences.soundEnabled.value,
            hapticsEnabled = com.bingo.multiplayer.core.designsystem.SoundPreferences.hapticsEnabled.value,
            pickSoundPresetId = com.bingo.multiplayer.core.designsystem.SoundPreferences.pickSoundPresetId.value,
            preferredBoardSize = prefs.getInt("settings_board_size", 5),
            darkTheme = com.bingo.multiplayer.core.designsystem.ThemePreferences.isDarkTheme.value,
            accentColorId = com.bingo.multiplayer.core.designsystem.ThemePreferences.accentColorId.value,
            customAccentHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.customColorHex.value,
            customMyPickHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.customMyPickHex.value,
            customOpponentPickHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.customOpponentPickHex.value,
            customRecentPickHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.customRecentPickHex.value,
            customCompletedLineHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.customCompletedLineHex.value,
            cellBorderEnabled = com.bingo.multiplayer.core.designsystem.ThemePreferences.cellBorderEnabled.value,
            cellBorderColorHex = com.bingo.multiplayer.core.designsystem.ThemePreferences.cellBorderColorHex.value,
            isLiquidMetalTheme = com.bingo.multiplayer.core.designsystem.ThemePreferences.isLiquidMetalTheme.value,
            systemNotificationsEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.systemNotificationsEnabled.value,
            playerOnlineNotificationsEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerOnlineNotificationsEnabled.value,
            playerInvitesNotificationsEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerInvitesNotificationsEnabled.value,
            inAppNotificationsEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppNotificationsEnabled.value,
            inAppPlayerOnlineEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerOnlineEnabled.value,
            inAppPlayerInvitesEnabled = com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerInvitesEnabled.value
        )
    }

    @Synchronized
    fun updateSettings(settings: com.bingo.multiplayer.domain.model.UserSettings, syncToCloud: Boolean = true, expectedGen: Long? = null) {
        if (expectedGen != null && sessionGeneration.get() != expectedGen) return
        val current = getSettings()
        if (current == settings) return

        prefs.edit()
            .putBoolean("settings_sound", settings.soundEnabled)
            .putBoolean("settings_haptics", settings.hapticsEnabled)
            .putString("settings_pick_sound_preset", settings.pickSoundPresetId)
            .putInt("settings_board_size", settings.preferredBoardSize)
            .putBoolean("settings_dark_theme", settings.darkTheme)
            .putBoolean("settings_system_notifs", settings.systemNotificationsEnabled)
            .putBoolean("settings_player_online_notifs", settings.playerOnlineNotificationsEnabled)
            .putBoolean("settings_player_invites_notifs", settings.playerInvitesNotificationsEnabled)
            .putBoolean("settings_in_app_notifs", settings.inAppNotificationsEnabled)
            .putBoolean("settings_in_app_online", settings.inAppPlayerOnlineEnabled)
            .putBoolean("settings_in_app_invites", settings.inAppPlayerInvitesEnabled)
            .apply()
        context?.let { ctx ->
            com.bingo.multiplayer.core.designsystem.SoundPreferences.applySettings(
                sound = settings.soundEnabled,
                haptics = settings.hapticsEnabled,
                presetId = settings.pickSoundPresetId,
                context = ctx,
                syncToCloud = false
            )
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setDarkThemeDirect(ctx, settings.darkTheme)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setAccentColor(ctx, settings.accentColorId)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCustomColor(ctx, settings.customAccentHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setMyPickColor(ctx, settings.customMyPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setOpponentPickColor(ctx, settings.customOpponentPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setRecentPickColor(ctx, settings.customRecentPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCompletedLineColor(ctx, settings.customCompletedLineHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCellBorderEnabled(ctx, settings.cellBorderEnabled)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCellBorderColor(ctx, settings.cellBorderColorHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setLiquidMetalTheme(ctx, settings.isLiquidMetalTheme)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.applySettings(
                systemNotifs = settings.systemNotificationsEnabled,
                playerOnlineNotifs = settings.playerOnlineNotificationsEnabled,
                playerInvitesNotifs = settings.playerInvitesNotificationsEnabled,
                inAppNotifs = settings.inAppNotificationsEnabled,
                inAppOnline = settings.inAppPlayerOnlineEnabled,
                inAppInvites = settings.inAppPlayerInvitesEnabled,
                context = ctx,
                syncToCloud = false
            )
        }
        if (syncToCloud) {
            val user = (_authState.value as? AuthState.Authenticated)?.user ?: getPersistedUserSync()
            if (context != null && user != null && user.uid.isNotBlank()) {
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).syncUserProfile(user, settings)
            }
            backupUserDataToCloud()
        }
    }

    fun getMatchHistory(uid: String? = null): List<com.bingo.multiplayer.domain.model.MatchRecord> {
        val targetUid = uid ?: (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, null)
        val scopedKey = if (!targetUid.isNullOrBlank()) "${targetUid}_match_history" else "match_history"
        val raw = prefs.getString(scopedKey, null) ?: prefs.getString("match_history", null) ?: return emptyList()
        return try {
            filterAdminMatchHistory(kotlinx.serialization.json.Json.decodeFromString(raw), targetUid)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun filterAdminMatchHistory(matches: List<com.bingo.multiplayer.domain.model.MatchRecord>, uid: String? = null): List<com.bingo.multiplayer.domain.model.MatchRecord> {
        val target = uid ?: getPersistedUserSync()?.uid ?: return matches
        val removed = runCatching { Json.decodeFromString<List<String>>(prefs.getString("${target}_admin_deleted_matches", "[]") ?: "[]").toSet() }.getOrDefault(emptySet())
        val cutoff = prefs.getLong("${target}_admin_clear_matches_before", 0L)
        return matches.filter { it.id !in removed && (cutoff <= 0L || it.timestamp > cutoff) }
    }

    @Synchronized
    fun persistMatchHistory(
        matches: List<com.bingo.multiplayer.domain.model.MatchRecord>,
        uid: String? = null,
        expectedGeneration: Long? = null
    ) {
        if (matches.isEmpty()) return
        val currentAuth = (_authState.value as? AuthState.Authenticated)?.user
        if (expectedGeneration != null && sessionGeneration.get() != expectedGeneration) {
            android.util.Log.w("AuthRepository", "persistMatchHistory rejected: session generation mismatch")
            return
        }
        if (uid != null && currentAuth != null && currentAuth.uid != uid) {
            android.util.Log.w("AuthRepository", "persistMatchHistory rejected: account switched from $uid to ${currentAuth.uid}")
            return
        }
        val targetUid = uid ?: currentAuth?.uid ?: prefs.getString(KEY_UID, null)
        if (targetUid.isNullOrBlank()) return

        val merged = filterAdminMatchHistory(getMatchHistory(targetUid) + matches, targetUid).distinctBy { it.id }
            .sortedByDescending { it.timestamp }.take(1000)
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(merged)
        val editor = prefs.edit().putString("${targetUid}_match_history", jsonStr)
        if (currentAuth?.uid == targetUid) {
            editor.putString("match_history", jsonStr)
        }
        editor.apply()
        _matchHistoryVersion.value++
    }

    fun streamFirestoreMatchHistory(): kotlinx.coroutines.flow.Flow<List<com.bingo.multiplayer.domain.model.MatchRecord>>? {
        val uid = (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, "") ?: ""
        return if (context != null && uid.isNotBlank()) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).streamMatchHistory(uid)
        } else null
    }

    @Synchronized
    fun recordMatch(
        mode: String,
        opponentName: String,
        didWin: Boolean,
        boardSize: Int,
        matchTitle: String = "",
        isDraw: Boolean = false,
        opponentUid: String = "",
        turnsTaken: Int = 0,
        linesCompleted: Int = 0,
        durationSeconds: Long = 0L,
        finalBoardNumbers: List<Int> = emptyList(),
        markedNumbers: List<Int> = emptyList(),
        stableResultId: String? = null,
        expectedAccountUid: String? = null
    ): Boolean {
        if (expectedAccountUid != null && (getPersistedUserSync()?.uid != expectedAccountUid ||
            (_authState.value as? AuthState.Authenticated)?.user?.uid != expectedAccountUid)) return false
        val currentHistory = getMatchHistory().toMutableList()
        val receiptKey = stableResultId?.let { "applied_result_${getPersistedUserSync()?.uid}:$it" }
        if (receiptKey != null && prefs.getBoolean(receiptKey, false)) return true
        if (stableResultId != null && currentHistory.any { it.id == stableResultId }) return true
        val record = com.bingo.multiplayer.domain.model.MatchRecord(
            id = stableResultId ?: UUID.randomUUID().toString().take(8),
            mode = mode,
            opponentName = opponentName,
            didWin = didWin,
            boardSize = boardSize,
            timestamp = System.currentTimeMillis(),
            matchTitle = matchTitle,
            isDraw = isDraw,
            opponentUid = opponentUid,
            turnsTaken = turnsTaken,
            linesCompleted = linesCompleted,
            durationSeconds = durationSeconds,
            finalBoardNumbers = finalBoardNumbers,
            markedNumbers = markedNumbers
        )
        val previousHistory = currentHistory.toList()
        currentHistory.add(0, record)
        val trimmed = if (currentHistory.size > 1000) currentHistory.take(1000) else currentHistory
        val targetUid = (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, "") ?: ""
        val historyJson = Json.encodeToString(trimmed)
        val prevHistoryJson = Json.encodeToString(previousHistory)
        val scopedHistoryKey = if (targetUid.isNotBlank()) "${targetUid}_match_history" else null

        if (stableResultId != null) {
            val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
            val streak = if (didWin) current.currentStreak + 1 else if (isDraw) current.currentStreak else 0
            val xp = current.xp + if (didWin) 100 else if (isDraw) 50 else 25
            val updated = current.copy(gamesPlayed = current.gamesPlayed + 1,
                gamesWon = current.gamesWon + if (didWin) 1 else 0,
                currentStreak = streak, bestStreak = maxOf(current.bestStreak, streak),
                xp = xp, level = (xp / 400).coerceAtLeast(1))
            // The result identity, history and profile counters commit together. A restart cannot
            // observe an increment without its deduplication record, or the inverse.
            val editor = prefs.edit().putString("match_history", historyJson)
                .putInt(KEY_GAMES_PLAYED, updated.gamesPlayed).putInt(KEY_GAMES_WON, updated.gamesWon)
                .putInt(KEY_STREAK, updated.currentStreak).putInt(KEY_BEST_STREAK, updated.bestStreak)
                .putInt(KEY_XP, updated.xp).putInt(KEY_LEVEL, updated.level).putBoolean(receiptKey!!, true)
            if (scopedHistoryKey != null) {
                editor.putString(scopedHistoryKey, historyJson)
                    .putInt("${targetUid}_games_played", updated.gamesPlayed)
                    .putInt("${targetUid}_games_won", updated.gamesWon)
                    .putInt("${targetUid}_streak", updated.currentStreak)
                    .putInt("${targetUid}_best_streak", updated.bestStreak)
                    .putInt("${targetUid}_xp", updated.xp)
                    .putInt("${targetUid}_level", updated.level)
            }
            val saved = editor.commit()
            if (!saved) {
                // Android updates SharedPreferences memory even when its disk commit fails.
                // Restore the touched memory values so a retry cannot trust an unpersisted receipt.
                // The rollback itself may fail on disk; the result stays pending either way.
                val rollbackEditor = prefs.edit().putString("match_history", prevHistoryJson)
                    .putInt(KEY_GAMES_PLAYED, current.gamesPlayed).putInt(KEY_GAMES_WON, current.gamesWon)
                    .putInt(KEY_STREAK, current.currentStreak).putInt(KEY_BEST_STREAK, current.bestStreak)
                    .putInt(KEY_XP, current.xp).putInt(KEY_LEVEL, current.level).remove(receiptKey)
                if (scopedHistoryKey != null) {
                    rollbackEditor.putString(scopedHistoryKey, prevHistoryJson)
                        .putInt("${targetUid}_games_played", current.gamesPlayed)
                        .putInt("${targetUid}_games_won", current.gamesWon)
                        .putInt("${targetUid}_streak", current.currentStreak)
                        .putInt("${targetUid}_best_streak", current.bestStreak)
                        .putInt("${targetUid}_xp", current.xp)
                        .putInt("${targetUid}_level", current.level)
                }
                rollbackEditor.commit()
                return false
            }
            _authState.value = AuthState.Authenticated(updated)
            if (context != null) {
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).recordMatch(updated.uid, record)
            }
            publishCareerProfile(updated)
            return true
        }
        val editor = prefs.edit().putString("match_history", historyJson)
        if (scopedHistoryKey != null) {
            editor.putString(scopedHistoryKey, historyJson)
        }
        editor.apply()

        if (context != null) {
            val uid = (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, "") ?: ""
            if (uid.isNotBlank()) {
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).recordMatch(uid, record)
            }
        }

        recordGameFinished(didWin = didWin, isDraw = isDraw)
        return true
    }

    @Synchronized
    fun recordGameFinished(didWin: Boolean, isDraw: Boolean = false) {
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return
        val newPlayed = current.gamesPlayed + 1
        val newWon = if (didWin) current.gamesWon + 1 else current.gamesWon
        val newStreak = if (didWin) current.currentStreak + 1 else if (isDraw) current.currentStreak else 0
        val newBestStreak = maxOf(current.bestStreak, newStreak)
        val newXp = current.xp + when {
            didWin -> 100
            isDraw -> 50
            else -> 25
        }
        val newLevel = (newXp / 400).coerceAtLeast(1)

        val updated = current.copy(
            gamesPlayed = newPlayed,
            gamesWon = newWon,
            currentStreak = newStreak,
            bestStreak = newBestStreak,
            level = newLevel,
            xp = newXp
        )
        persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""))
        _authState.value = AuthState.Authenticated(updated)
        publishCareerProfile(updated)
    }

    private fun publishCareerProfile(updated: UserProfile) {
        backupUserDataToCloud()
        if (context != null && updated.uid.isNotBlank()) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
                .syncUserProfile(updated, getSettings())
        }
        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
        val careerUserKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(updated.uid)
        sessionManager.claimUsername(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = updated.username,
                uid = updated.uid,
                displayName = updated.displayName,
                avatarUrl = updated.avatarBase64 ?: updated.avatarUrl,
                gamesPlayed = updated.gamesPlayed,
                gamesWon = updated.gamesWon,
                currentStreak = updated.currentStreak,
                bestStreak = updated.bestStreak,
                level = updated.level,
                publicKey = careerUserKey
            ),
            googleId = googleId
        )
    }

    fun backupUserDataToCloud() {
        val current = (_authState.value as? AuthState.Authenticated)?.user ?: return
        val googleId = if (current.provider == AuthProvider.GOOGLE && current.uid.startsWith("google_")) {
            current.uid.removePrefix("google_")
        } else current.uid

        val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
            profile = current,
            settings = getSettings(),
            matchHistory = getMatchHistory(),
            lastBackupTimestamp = System.currentTimeMillis()
        )
        sessionManager.backupUserData(googleId, backup)
    }

    suspend fun restoreCloudUserData(googleId: String, idToken: String? = null): Boolean {
        val expectedGen = sessionGeneration.get()
        val targetUid = "google_$googleId"
        if (context != null && !awaitFirebaseAuth(idToken, expectedGen, targetUid = targetUid)) return false
        val cloudData = sessionManager.fetchUserDataBackup(googleId) ?: return false
        if (sessionGeneration.get() != expectedGen) return false
        if (cloudData.profile.uid != targetUid || cloudData.profile.provider != AuthProvider.GOOGLE) return false
        val current = (_authState.value as? AuthState.Authenticated)?.user
        if (current != null && current.uid != targetUid) return false
        val cloudProfile = cloudData.profile
        if ((current?.adminRevision ?: 0L) > cloudProfile.adminRevision) return true
        val localAvatar = if (!cloudProfile.avatarBase64.isNullOrBlank()) {
            restoreAvatarFromBase64(cloudProfile.avatarBase64, cloudProfile.uid) ?: cloudProfile.avatarUrl
        } else cloudProfile.avatarUrl

        val finalProfile = cloudProfile.copy(
            avatarUrl = localAvatar,
            avatarBase64 = cloudProfile.avatarBase64,
            gamesPlayed = maxOf(current?.gamesPlayed ?: 0, cloudProfile.gamesPlayed),
            gamesWon = maxOf(current?.gamesWon ?: 0, cloudProfile.gamesWon),
            currentStreak = maxOf(current?.currentStreak ?: 0, cloudProfile.currentStreak),
            bestStreak = maxOf(current?.bestStreak ?: 0, cloudProfile.bestStreak, cloudProfile.currentStreak),
            level = maxOf(current?.level ?: 1, cloudProfile.level),
            xp = maxOf(current?.xp ?: 0, cloudProfile.xp)
        )
        val currentCheck = (_authState.value as? AuthState.Authenticated)?.user
        if (currentCheck != null && currentCheck.uid != targetUid) return false
        if (!persistUser(finalProfile, prefs.getString(KEY_AUTH_TOKEN, ""), expectedGeneration = expectedGen)) return false
        if (sessionGeneration.get() != expectedGen) return false

        synchronized(this@AuthRepository) {
            if (sessionGeneration.get() != expectedGen) return@synchronized false
            setGoogleUserRegistered(googleId, true)
            setSavedGoogleDisplayName(googleId, finalProfile.displayName)
            setSavedGoogleUsername(googleId, finalProfile.username)
            val localDark = com.bingo.multiplayer.core.designsystem.ThemePreferences.isDarkTheme.value
            val localSound = com.bingo.multiplayer.core.designsystem.SoundPreferences.soundEnabled.value
            val localHaptics = com.bingo.multiplayer.core.designsystem.SoundPreferences.hapticsEnabled.value
            val localPreset = com.bingo.multiplayer.core.designsystem.SoundPreferences.pickSoundPresetId.value
            val isNotifsConfigured = context?.let { com.bingo.multiplayer.core.designsystem.NotificationPreferences.isLocallyConfigured(it) } ?: false

            updateSettings(
                cloudData.settings.copy(
                    darkTheme = localDark,
                    soundEnabled = localSound,
                    hapticsEnabled = localHaptics,
                    pickSoundPresetId = localPreset,
                    systemNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.systemNotificationsEnabled.value else cloudData.settings.systemNotificationsEnabled,
                    playerOnlineNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerOnlineNotificationsEnabled.value else cloudData.settings.playerOnlineNotificationsEnabled,
                    playerInvitesNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.playerInvitesNotificationsEnabled.value else cloudData.settings.playerInvitesNotificationsEnabled,
                    inAppNotificationsEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppNotificationsEnabled.value else cloudData.settings.inAppNotificationsEnabled,
                    inAppPlayerOnlineEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerOnlineEnabled.value else cloudData.settings.inAppPlayerOnlineEnabled,
                    inAppPlayerInvitesEnabled = if (isNotifsConfigured) com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppPlayerInvitesEnabled.value else cloudData.settings.inAppPlayerInvitesEnabled
                ),
                syncToCloud = false,
                expectedGen = expectedGen
            )

            if (cloudData.matchHistory.isNotEmpty()) {
                val localHistory = getMatchHistory(finalProfile.uid)
                val merged = filterAdminMatchHistory(localHistory + cloudData.matchHistory, finalProfile.uid)
                    .distinctBy { it.id }
                    .sortedByDescending { it.timestamp }
                    .let { if (it.size > 1000) it.take(1000) else it }
                val historyJson = Json.encodeToString(merged)
                prefs.edit().putString("match_history", historyJson)
                    .putString("${finalProfile.uid}_match_history", historyJson)
                    .commit()
            }
            if (sessionGeneration.get() != expectedGen) return@synchronized false
            _authState.value = AuthState.Authenticated(finalProfile)
            sessionManager.startSessionWatcher(googleId, deviceId) {
                if (sessionGeneration.get() == expectedGen) {
                    signOut()
                }
            }
            true
        }
        val currentDark = com.bingo.multiplayer.core.designsystem.ThemePreferences.isDarkTheme.value
        if (cloudData.settings.darkTheme != currentDark && sessionGeneration.get() == expectedGen) {
            backupUserDataToCloud()
        }
        return true
    }

    @Synchronized
    fun signOut(backUpToCloud: Boolean = true) {
        sessionGeneration.incrementAndGet()
        com.bingo.multiplayer.domain.network.LeaderboardSyncManager.reset()
        val current = (_authState.value as? AuthState.Authenticated)?.user
        if (backUpToCloud && current != null) {
            val googleId = if (current.provider == AuthProvider.GOOGLE && current.uid.startsWith("google_")) {
                current.uid.removePrefix("google_")
            } else current.uid
            val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
                profile = current,
                settings = getSettings(),
                matchHistory = getMatchHistory(current.uid),
                lastBackupTimestamp = System.currentTimeMillis()
            )
            scope.launch(Dispatchers.IO) {
                sessionManager.backupUserDataSync(googleId, backup)
            }
        }
        cloudListenerUid = null
        cloudListenerGeneration = -1L
        sessionManager.stopSessionWatcher()
        com.bingo.multiplayer.domain.network.PresenceManager.stopPresence()
        if (context != null) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).stopRealtimeProfileListener()
        }
        if (context != null) com.bingo.multiplayer.domain.notifications.NotificationRuntime.bind(context, null, null)
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        } catch (_: Throwable) {}
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_NAME)
            .remove(KEY_USERNAME)
            .remove(KEY_EMAIL)
            .remove(KEY_AVATAR)
            .remove(KEY_AVATAR_BASE64)
            .remove(KEY_PROVIDER)
            .remove(KEY_GAMES_PLAYED)
            .remove(KEY_GAMES_WON)
            .remove(KEY_STREAK)
            .remove(KEY_BEST_STREAK)
            .remove(KEY_LEVEL)
            .remove(KEY_XP)
            .remove("match_history")
            .apply()
        _authState.value = AuthState.Unauthenticated
    }


    suspend fun awaitFirebaseAuth(idToken: String? = null, expectedGen: Long = sessionGeneration.get(), targetUid: String? = null): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext true
        try {
            if (com.google.firebase.FirebaseApp.getApps(ctx).isEmpty()) return@withContext true
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val currentAuth = auth.currentUser
            val isGenuineToken = !idToken.isNullOrBlank() && !idToken.startsWith("google_token_") && !idToken.startsWith("guest_token_")
            val task = if (isGenuineToken) {
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential)
            } else if (targetUid != null && targetUid.startsWith("guest_")) {
                val customToken = runCatching { obtainGuestCustomToken(targetUid) }.getOrNull()
                if (!customToken.isNullOrBlank()) {
                    auth.signInWithCustomToken(customToken)
                } else if (currentAuth == null || !currentAuth.isAnonymous) {
                    if (currentAuth != null && !currentAuth.isAnonymous) {
                        auth.signOut()
                    }
                    auth.signInAnonymously()
                } else null
            } else if (currentAuth == null) {
                auth.signInAnonymously()
            } else null

            if (task != null) {
                com.google.android.gms.tasks.Tasks.await(task, 5, java.util.concurrent.TimeUnit.SECONDS)
            }
            if (sessionGeneration.get() != expectedGen) return@withContext false
            val current = auth.currentUser ?: return@withContext false
            if (targetUid != null) {
                if (targetUid.startsWith("google_")) {
                    val expectedGid = targetUid.removePrefix("google_")
                    val isMatchingGoogle = current.providerData.any { it.providerId == "google.com" && it.uid == expectedGid } ||
                        current.uid == targetUid || current.uid == expectedGid
                    if (!isMatchingGoogle) {
                        return@withContext false
                    }
                } else if (targetUid.startsWith("guest_")) {
                    if (!current.isAnonymous && current.uid != targetUid) {
                        return@withContext false
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun obtainGuestCustomToken(targetUid: String): String? = withContext(Dispatchers.IO) {
        if (context == null) return@withContext null
        try {
            val keyPair = com.bingo.multiplayer.domain.notifications.NotificationIdentity.getKeyPair()
            val pubKey = com.bingo.multiplayer.domain.notifications.NotificationIdentity.installationPublicKey()
            val timestamp = System.currentTimeMillis()
            val nonce = java.util.UUID.randomUUID().toString()
            val firebaseUid = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: targetUid
            val message = "bingo-firebase-guest-binding\n$targetUid\n$firebaseUid\n$pubKey\n$timestamp\n$nonce"
            val signer = java.security.Signature.getInstance("SHA256withECDSA").apply {
                initSign(keyPair.private)
                update(message.toByteArray(Charsets.UTF_8))
            }
            val sig = android.util.Base64.encodeToString(signer.sign(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).trim()
            val payload = org.json.JSONObject()
                .put("guestUid", targetUid)
                .put("firebaseUid", firebaseUid)
                .put("publicKey", pubKey)
                .put("timestamp", timestamp)
                .put("nonce", nonce)
                .put("signature", sig)

            val base = com.bingo.multiplayer.domain.network.NetworkConfig.FCM_RELAY_URL.removeSuffix("/send")
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder()
                .url("$base/v2/auth/firebase-guest-binding")
                .post(body)
                .build()

            com.bingo.multiplayer.domain.network.NetworkConfig.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val respBody = resp.body?.string() ?: return@withContext null
                val json = org.json.JSONObject(respBody)
                json.optString("customToken").takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureFirebaseAuth(idToken: String? = null, targetUid: String? = null) {
        val gen = sessionGeneration.get()
        scope.launch(Dispatchers.IO) {
            awaitFirebaseAuth(idToken, gen, targetUid)
        }
    }

    private fun loadExistingStats(uid: String = ""): UserProfile {
        val pfx = if (uid.isNotBlank()) "${uid}_" else ""
        val streak = if (pfx.isNotEmpty() && prefs.contains("${pfx}streak")) prefs.getInt("${pfx}streak", 0) else if (pfx.isEmpty()) prefs.getInt(KEY_STREAK, 0) else 0
        val bestStreak = maxOf(if (pfx.isNotEmpty() && prefs.contains("${pfx}best_streak")) prefs.getInt("${pfx}best_streak", streak) else if (pfx.isEmpty()) prefs.getInt(KEY_BEST_STREAK, streak) else 0, streak)
        return UserProfile(
            uid = uid,
            displayName = "",
            avatarUrl = if (pfx.isNotEmpty() && prefs.contains("${pfx}avatar")) prefs.getString("${pfx}avatar", null) else if (pfx.isEmpty()) prefs.getString(KEY_AVATAR, null) else null,
            avatarBase64 = if (pfx.isNotEmpty() && prefs.contains("${pfx}avatar_base64")) prefs.getString("${pfx}avatar_base64", null) else if (pfx.isEmpty()) prefs.getString(KEY_AVATAR_BASE64, null) else null,
            gamesPlayed = if (pfx.isNotEmpty() && prefs.contains("${pfx}games_played")) prefs.getInt("${pfx}games_played", 0) else if (pfx.isEmpty()) prefs.getInt(KEY_GAMES_PLAYED, 0) else 0,
            gamesWon = if (pfx.isNotEmpty() && prefs.contains("${pfx}games_won")) prefs.getInt("${pfx}games_won", 0) else if (pfx.isEmpty()) prefs.getInt(KEY_GAMES_WON, 0) else 0,
            currentStreak = streak,
            bestStreak = bestStreak,
            level = if (pfx.isNotEmpty() && prefs.contains("${pfx}level")) prefs.getInt("${pfx}level", 1) else if (pfx.isEmpty()) prefs.getInt(KEY_LEVEL, 1) else 1,
            xp = if (pfx.isNotEmpty() && prefs.contains("${pfx}xp")) prefs.getInt("${pfx}xp", 0) else if (pfx.isEmpty()) prefs.getInt(KEY_XP, 0) else 0,
            adminRevision = prefs.getLong("${pfx}admin_revision", 0L)
        )
    }

    @Synchronized
    private fun persistUser(profile: UserProfile, token: String? = null, expectedGeneration: Long? = null, syncToCloud: Boolean = true): Boolean {
        if (expectedGeneration != null && sessionGeneration.get() != expectedGeneration) return false
        val currentAuth = (_authState.value as? AuthState.Authenticated)?.user
        if (expectedGeneration != null && currentAuth != null && currentAuth.uid != profile.uid) return false
        if (currentAuth?.uid == profile.uid && currentAuth.adminRevision > profile.adminRevision) return false
        val currentSavedUid = prefs.getString(KEY_UID, null)
        if (expectedGeneration != null && currentSavedUid != null && currentSavedUid != profile.uid) return false

        val editor = prefs.edit()
            .putString(KEY_UID, profile.uid)
            .putString(KEY_USERNAME, profile.username)
            .putString(KEY_NAME, profile.displayName)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_AVATAR, profile.avatarUrl)
            .putString(KEY_AVATAR_BASE64, profile.avatarBase64)
            .putString(KEY_PROVIDER, profile.provider.name)
            .putInt(KEY_GAMES_PLAYED, profile.gamesPlayed)
            .putInt(KEY_GAMES_WON, profile.gamesWon)
            .putInt(KEY_STREAK, profile.currentStreak)
            .putInt(KEY_BEST_STREAK, profile.bestStreak)
            .putInt(KEY_LEVEL, profile.level)
            .putInt(KEY_XP, profile.xp)

        if (profile.uid.isNotBlank()) {
            val pfx = "${profile.uid}_"
            editor.putLong("${pfx}admin_revision", profile.adminRevision)
                .putString("${pfx}username", profile.username)
                .putString("${pfx}name", profile.displayName)
                .putString("${pfx}avatar", profile.avatarUrl)
                .putString("${pfx}avatar_base64", profile.avatarBase64)
                .putInt("${pfx}games_played", profile.gamesPlayed)
                .putInt("${pfx}games_won", profile.gamesWon)
                .putInt("${pfx}streak", profile.currentStreak)
                .putInt("${pfx}best_streak", profile.bestStreak)
                .putInt("${pfx}level", profile.level)
                .putInt("${pfx}xp", profile.xp)
        }

        if (token != null) {
            editor.putString(KEY_AUTH_TOKEN, token)
        }
        editor.apply()

        if (syncToCloud && context != null && profile.username.isNotBlank()) {
            com.bingo.multiplayer.domain.notifications.NotificationRuntime.bind(context, profile.uid, profile.username)
            com.bingo.multiplayer.domain.network.BingoFcmManager.syncCurrentUserToken(context, profile.username)
        }

        if (syncToCloud && profile.uid.isNotBlank()) startCloudSynchronization(profile, sessionGeneration.get(), token)
        return true
    }

    companion object {
        @Volatile var activeInstance: AuthRepository? = null
        internal var contentStreamProvider: ((Uri?) -> java.io.InputStream?)? = null
        internal var bitmapDecoderOverride: ((String) -> android.graphics.Bitmap?)? = null
        internal var bitmapDimensionsOverride: ((android.graphics.Bitmap) -> Pair<Int, Int>)? = null
        internal var bitmapScalerOverride: ((android.graphics.Bitmap, Int, Int) -> android.graphics.Bitmap)? = null

        fun computeSampleSize(outWidth: Int, outHeight: Int, maxDim: Int = 160): Int {
            var sampleSize = 1
            while ((outWidth / sampleSize > maxDim * 2) || (outHeight / sampleSize > maxDim * 2)) {
                sampleSize *= 2
            }
            return sampleSize
        }

        fun computeTargetDimensions(width: Int, height: Int, maxDim: Int = 160): Pair<Int, Int> {
            if (width <= maxDim && height <= maxDim) return Pair(width, height)
            val ratio = width.toFloat() / height.toFloat()
            val w = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
            val h = if (ratio >= 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
            return Pair(w, h)
        }
        private const val PREFS_NAME = "bingo_auth_prefs"
        private const val KEY_UID = "uid"
        private const val KEY_USERNAME = "username"
        private const val KEY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_AVATAR = "avatar_url"
        private const val KEY_AVATAR_BASE64 = "avatar_base64"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_GAMES_PLAYED = "games_played"
        private const val KEY_GAMES_WON = "games_won"
        private const val KEY_STREAK = "streak"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_LEVEL = "level"
        private const val KEY_XP = "xp"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
