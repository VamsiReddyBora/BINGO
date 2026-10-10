package com.bingo.multiplayer.domain.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.Keep
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

@Keep
data class FirebaseUserResult(
    val uid: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val token: String = "",
    val provider: AuthProvider = AuthProvider.GOOGLE
)

@Keep
interface FirebaseAuthProvider {
    suspend fun signInWithGoogle(idToken: String): FirebaseUserResult
    suspend fun signInWithPlayGames(authCode: String): FirebaseUserResult
    suspend fun signInAnonymously(): FirebaseUserResult
    suspend fun refreshToken(currentToken: String?): String?
    suspend fun fetchUserProfile(uid: String): UserProfile?
}

@Keep
class DefaultFirebaseAuthProvider : FirebaseAuthProvider {
    override suspend fun signInWithGoogle(idToken: String): FirebaseUserResult {
        return FirebaseUserResult(
            uid = "google_${idToken.hashCode()}",
            displayName = "Google Player",
            email = "player@gmail.com",
            token = "fb_tok_${System.currentTimeMillis()}",
            provider = AuthProvider.GOOGLE
        )
    }

    override suspend fun signInWithPlayGames(authCode: String): FirebaseUserResult {
        return FirebaseUserResult(
            uid = "pg_${authCode.hashCode()}",
            displayName = "PlayGames Player",
            token = "pg_tok_${System.currentTimeMillis()}",
            provider = AuthProvider.PLAY_GAMES
        )
    }

    override suspend fun signInAnonymously(): FirebaseUserResult {
        return FirebaseUserResult(
            uid = "guest_${System.currentTimeMillis()}",
            displayName = "Guest",
            token = "anon_tok_${System.currentTimeMillis()}",
            provider = AuthProvider.GUEST
        )
    }

    override suspend fun refreshToken(currentToken: String?): String? {
        return if (currentToken != null) "refreshed_tok_${System.currentTimeMillis()}" else null
    }

    override suspend fun fetchUserProfile(uid: String): UserProfile? {
        return UserProfile(
            uid = uid,
            displayName = "Player",
            provider = if (uid.startsWith("pg_")) AuthProvider.PLAY_GAMES else AuthProvider.GOOGLE
        )
    }
}

/**
 * Authentication Gatekeeper Repository.
 * Handles persisting user sessions obtained from Google Sign-In,
 * Play Games Services, Firebase Auth, or Guest login.
 */
class AuthRepository(
    private val context: Context? = null,
    private val firebaseAuthProvider: FirebaseAuthProvider = DefaultFirebaseAuthProvider(),
    private val customPrefs: SharedPreferences? = null
) {

    private val prefs: SharedPreferences =
        customPrefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: throw IllegalStateException("Either context or customPrefs must be provided")

    private val _authState = MutableStateFlow<AuthState>(
        getPersistedUserSync()?.let { AuthState.Authenticated(it) } ?: AuthState.Loading
    )
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

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

    init {
        checkPersistedSession()
    }

    /**
     * Checks if the user is already authenticated on app launch.
     */
    fun checkPersistedSession() {
        scope.launch {
            verifyPersistedSession()
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
            xp = xp
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
        val profile = getPersistedUserSync()
        val state = if (profile != null) {
            AuthState.Authenticated(profile)
        } else {
            AuthState.Unauthenticated
        }

        _authState.value = state
        if (state is AuthState.Authenticated) {
            val user = state.user
            com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(user.username)
            com.bingo.multiplayer.domain.network.PresenceManager.startPresence(user.username)
            val googleId = if (user.uid.startsWith("google_")) user.uid.removePrefix("google_") else null
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
                    level = user.level
                ),
                googleId = googleId
            )
            if (user.provider == AuthProvider.GOOGLE && googleId != null) {
                sessionManager.startSessionWatcher(googleId, deviceId) {
                    signOut()
                }
                scope.launch {
                    restoreCloudUserData(googleId)
                }
            }

            if (context != null) {
                val firestoreSync = com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
                // 1. Immediately upload/sync current user profile and custom theme settings
                firestoreSync.syncUserProfile(user, getSettings())

                // 2. Upload all existing match history records
                val existingMatches = getMatchHistory()
                if (existingMatches.isNotEmpty()) {
                    firestoreSync.syncAllMatches(user.uid, existingMatches)
                }

                // 3. Start realtime profile listener
                firestoreSync.startRealtimeProfileListener(user.uid) { _, remoteSettings ->
                    updateSettings(remoteSettings)
                }
            }
        }
        state
    }

    /**
     * Authenticates via Firebase Auth provider.
     */
    suspend fun loginWithFirebase(provider: AuthProvider, credentialToken: String): Result<UserProfile> =
        withContext(Dispatchers.IO) {
            try {
                val result = when (provider) {
                    AuthProvider.GOOGLE -> firebaseAuthProvider.signInWithGoogle(credentialToken)
                    AuthProvider.PLAY_GAMES -> firebaseAuthProvider.signInWithPlayGames(credentialToken)
                    AuthProvider.GUEST -> firebaseAuthProvider.signInAnonymously()
                }

                val existing = loadExistingStats()
                val username = prefs.getString(KEY_USERNAME, null)
                    ?: result.displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { result.uid.take(8) }
                val profile = UserProfile(
                    uid = result.uid,
                    username = username,
                    displayName = result.displayName.ifEmpty { "Player" },
                    email = result.email,
                    avatarUrl = result.avatarUrl ?: existing.avatarUrl,
                    provider = result.provider,
                    gamesPlayed = existing.gamesPlayed,
                    gamesWon = existing.gamesWon,
                    currentStreak = existing.currentStreak,
                    bestStreak = existing.bestStreak,
                    level = existing.level,
                    xp = existing.xp
                )

                persistUser(profile, result.token)
                _authState.value = AuthState.Authenticated(profile)
                Result.success(profile)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Refreshes Firebase session token.
     */
    suspend fun refreshToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val currentToken = prefs.getString(KEY_AUTH_TOKEN, null)
            val newToken = firebaseAuthProvider.refreshToken(currentToken)
            if (newToken != null) {
                prefs.edit().putString(KEY_AUTH_TOKEN, newToken).apply()
                Result.success(newToken)
            } else {
                signOut()
                Result.failure(IllegalStateException("Token refresh failed: session expired"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches user profile from backend / provider.
     */
    suspend fun fetchUserProfile(uid: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val fetched = firebaseAuthProvider.fetchUserProfile(uid)
            if (fetched != null) {
                val current = (_authState.value as? AuthState.Authenticated)?.user
                val merged = fetched.copy(
                    gamesPlayed = current?.gamesPlayed ?: fetched.gamesPlayed,
                    gamesWon = current?.gamesWon ?: fetched.gamesWon,
                    currentStreak = current?.currentStreak ?: fetched.currentStreak,
                    bestStreak = maxOf(current?.bestStreak ?: 0, fetched.bestStreak),
                    level = current?.level ?: fetched.level,
                    xp = current?.xp ?: fetched.xp
                )
                persistUser(merged, prefs.getString(KEY_AUTH_TOKEN, ""))
                _authState.value = AuthState.Authenticated(merged)
                Result.success(merged)
            } else {
                Result.failure(NoSuchElementException("User profile not found for uid $uid"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Called after a successful real Google Play Games sign-in.
     */
    fun onPlayGamesSignInSuccess(playerId: String, displayName: String) {
        val existing = loadExistingStats()
        val profile = UserProfile(
            uid = "pg_$playerId",
            displayName = displayName,
            email = null,
            avatarUrl = existing.avatarUrl,
            provider = AuthProvider.PLAY_GAMES,
            gamesPlayed = existing.gamesPlayed,
            gamesWon = existing.gamesWon,
            currentStreak = existing.currentStreak,
            level = existing.level,
            xp = existing.xp
        )
        persistUser(profile, "pg_token_$playerId")
        _authState.value = AuthState.Authenticated(profile)
    }

    /**
     * Called after a successful real Google Sign-In (account picker).
     */
    fun onGoogleSignInSuccess(
        googleId: String,
        displayName: String,
        email: String,
        customUsername: String? = null
    ) {
        setGoogleUserRegistered(googleId, true)
        setSavedGoogleDisplayName(googleId, displayName)
        sessionManager.startSessionWatcher(googleId, deviceId) {
            signOut()
        }
        val existing = loadExistingStats()
        val cleanUser = customUsername?.trim()?.lowercase()?.removePrefix("@")
            ?: displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { "user_$googleId".take(12) }
        setSavedGoogleUsername(googleId, cleanUser)

        val profile = UserProfile(
            uid = "google_$googleId",
            username = cleanUser,
            displayName = displayName,
            email = email,
            avatarUrl = existing.avatarUrl,
            avatarBase64 = existing.avatarBase64,
            provider = AuthProvider.GOOGLE,
            gamesPlayed = existing.gamesPlayed,
            gamesWon = existing.gamesWon,
            currentStreak = existing.currentStreak,
            level = existing.level,
            xp = existing.xp
        )
        persistUser(profile, "google_token_$googleId")
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
                level = profile.level
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
        val guestId = "guest_${System.currentTimeMillis()}"
        val existing = loadExistingStats()
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
            level = existing.level,
            xp = existing.xp
        )
        persistUser(profile, "guest_token_$guestId")
        com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(cleanUser)
        com.bingo.multiplayer.domain.network.PresenceManager.startPresence(cleanUser)
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
                level = profile.level
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
        val available = sessionManager.checkUsernameAvailable(clean, current.uid)
        if (!available) return false
        val updated = current.copy(username = clean)
        persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""))
        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
        sessionManager.claimUsername(
            com.bingo.multiplayer.domain.network.PlayerRegistryEntry(
                username = clean,
                uid = updated.uid,
                displayName = updated.displayName,
                avatarUrl = updated.avatarBase64 ?: updated.avatarUrl,
                gamesPlayed = updated.gamesPlayed,
                gamesWon = updated.gamesWon,
                currentStreak = updated.currentStreak,
                bestStreak = updated.bestStreak,
                level = updated.level
            ),
            googleId = googleId
        )
        _authState.value = AuthState.Authenticated(updated)
        com.bingo.multiplayer.domain.network.PresenceManager.setCurrentUser(clean)
        com.bingo.multiplayer.domain.network.PresenceManager.startPresence(clean)
        backupUserDataToCloud()
        return true
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
    fun saveProfileAvatar(uri: Uri): Boolean {
        return try {
            val current = (_authState.value as? AuthState.Authenticated)?.user ?: return false
            val cleanUser = current.username.trim().lowercase().removePrefix("@")

            // Remove any old avatar file in internal storage
            current.avatarUrl?.let { oldPath ->
                try {
                    val oldFile = File(oldPath)
                    if (context != null && oldFile.exists() && oldFile.parent == context.filesDir?.absolutePath) {
                        oldFile.delete()
                    }
                } catch (_: Exception) {}
            }

            val ctx = context ?: return false
            val filesDir = ctx.filesDir ?: ctx.cacheDir
            val targetFile = File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Compress and convert to Base64 for cross-device cloud persistence
            var decodedBitmap: android.graphics.Bitmap? = null
            val base64 = try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath)
                if (bitmap != null) {
                    decodedBitmap = bitmap
                    val maxDim = 160
                    val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val w = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
                        val h = if (ratio >= 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
                        android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                    } else bitmap
                    val baos = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                } else null
            } catch (_: Exception) {
                null
            }

            // 1. Immediately evict and pre-populate memory cache so all local UI renders the new avatar ON THE SPOT with ZERO delay
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.evict(current.username, current.avatarUrl)
            if (decodedBitmap != null) {
                val imageBmp = decodedBitmap.asImageBitmap()
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put(targetFile.absolutePath, imageBmp)
                com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put("u:$cleanUser", imageBmp)
                if (!base64.isNullOrBlank()) {
                    com.bingo.multiplayer.presentation.common.PlayerAvatarCache.put(base64, imageBmp)
                }
            }
            com.bingo.multiplayer.presentation.common.PlayerAvatarCache.notifyAvatarChanged(cleanUser)

            val updated = current.copy(
                avatarUrl = targetFile.absolutePath,
                avatarBase64 = base64
            )
            persistUser(updated, prefs.getString(KEY_AUTH_TOKEN, ""))
            _authState.value = AuthState.Authenticated(updated)
            backupUserDataToCloud()
            val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
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
                    level = updated.level
                ),
                googleId = googleId
            )
            scope.launch {
                if (base64 != null) {
                    sessionManager.saveUserAvatar(updated.username, base64)
                }
                // 2. Real-time broadcast to all players across MQTT
                sessionManager.broadcastAvatarUpdate(updated.username, base64)
                // 3. Update active match / lobby / friends
                com.bingo.multiplayer.domain.network.OnlineRoomSyncManager.activeInstance?.updateLocalAvatar(base64)
                com.bingo.multiplayer.domain.network.LanP2pSessionManager.activeInstance?.updateLocalAvatar(base64)
                com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.onRemoteAvatarUpdated(updated.username, base64)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
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
        backupUserDataToCloud()

        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
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
                level = updated.level
            ),
            googleId = googleId
        )

        scope.launch {
            sessionManager.deleteUserAvatar(cleanUser)
            sessionManager.broadcastAvatarUpdate(cleanUser, null)
            com.bingo.multiplayer.domain.network.OnlineRoomSyncManager.activeInstance?.updateLocalAvatar(null)
            com.bingo.multiplayer.domain.network.LanP2pSessionManager.activeInstance?.updateLocalAvatar(null)
            com.bingo.multiplayer.domain.repository.FriendsRepository.activeInstance?.onRemoteAvatarUpdated(cleanUser, null)
        }
        return true
    }

    fun getSettings(): com.bingo.multiplayer.domain.model.UserSettings {
        return com.bingo.multiplayer.domain.model.UserSettings(
            soundEnabled = prefs.getBoolean("settings_sound", true),
            hapticsEnabled = prefs.getBoolean("settings_haptics", true),
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

    fun updateSettings(settings: com.bingo.multiplayer.domain.model.UserSettings) {
        prefs.edit()
            .putBoolean("settings_sound", settings.soundEnabled)
            .putBoolean("settings_haptics", settings.hapticsEnabled)
            .putInt("settings_board_size", settings.preferredBoardSize)
            .putBoolean("settings_dark_theme", settings.darkTheme)
            .apply()
        context?.let { ctx ->
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setDarkTheme(ctx, settings.darkTheme)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setAccentColor(ctx, settings.accentColorId)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCustomColor(ctx, settings.customAccentHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setMyPickColor(ctx, settings.customMyPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setOpponentPickColor(ctx, settings.customOpponentPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setRecentPickColor(ctx, settings.customRecentPickHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCompletedLineColor(ctx, settings.customCompletedLineHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCellBorderEnabled(ctx, settings.cellBorderEnabled)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setCellBorderColor(ctx, settings.cellBorderColorHex)
            com.bingo.multiplayer.core.designsystem.ThemePreferences.setLiquidMetalTheme(ctx, settings.isLiquidMetalTheme)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setSystemNotificationsEnabled(ctx, settings.systemNotificationsEnabled)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setPlayerOnlineNotificationsEnabled(ctx, settings.playerOnlineNotificationsEnabled)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setPlayerInvitesNotificationsEnabled(ctx, settings.playerInvitesNotificationsEnabled)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setInAppNotificationsEnabled(ctx, settings.inAppNotificationsEnabled)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setInAppPlayerOnlineEnabled(ctx, settings.inAppPlayerOnlineEnabled)
            com.bingo.multiplayer.core.designsystem.NotificationPreferences.setInAppPlayerInvitesEnabled(ctx, settings.inAppPlayerInvitesEnabled)
        }
        val user = (_authState.value as? AuthState.Authenticated)?.user ?: getPersistedUserSync()
        if (context != null && user != null && user.uid.isNotBlank()) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).syncUserProfile(user, settings)
        }
        backupUserDataToCloud()
    }

    fun getMatchHistory(): List<com.bingo.multiplayer.domain.model.MatchRecord> {
        val raw = prefs.getString("match_history", null) ?: return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun streamFirestoreMatchHistory(): kotlinx.coroutines.flow.Flow<List<com.bingo.multiplayer.domain.model.MatchRecord>>? {
        val uid = (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, "") ?: ""
        return if (context != null && uid.isNotBlank()) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).streamMatchHistory(uid)
        } else null
    }

    fun recordMatch(
        mode: String,
        opponentName: String,
        didWin: Boolean,
        boardSize: Int,
        matchTitle: String = "",
        isDraw: Boolean = false
    ) {
        val currentHistory = getMatchHistory().toMutableList()
        val record = com.bingo.multiplayer.domain.model.MatchRecord(
            id = UUID.randomUUID().toString().take(8),
            mode = mode,
            opponentName = opponentName,
            didWin = didWin,
            boardSize = boardSize,
            timestamp = System.currentTimeMillis(),
            matchTitle = matchTitle,
            isDraw = isDraw
        )
        currentHistory.add(0, record)
        val trimmed = if (currentHistory.size > 1000) currentHistory.take(1000) else currentHistory
        prefs.edit().putString("match_history", Json.encodeToString(trimmed)).apply()

        if (context != null) {
            val uid = (_authState.value as? AuthState.Authenticated)?.user?.uid ?: prefs.getString(KEY_UID, "") ?: ""
            if (uid.isNotBlank()) {
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).recordMatch(uid, record)
            }
        }

        recordGameFinished(didWin = didWin, isDraw = isDraw)
    }

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
        backupUserDataToCloud()
        val googleId = if (updated.uid.startsWith("google_")) updated.uid.removePrefix("google_") else null
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
                level = updated.level
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

    suspend fun restoreCloudUserData(googleId: String): Boolean {
        val cloudData = sessionManager.fetchUserDataBackup(googleId) ?: return false
        val cloudProfile = cloudData.profile
        val localAvatar = if (!cloudProfile.avatarBase64.isNullOrBlank()) {
            restoreAvatarFromBase64(cloudProfile.avatarBase64, cloudProfile.uid) ?: cloudProfile.avatarUrl
        } else cloudProfile.avatarUrl

        val current = (_authState.value as? AuthState.Authenticated)?.user
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
        persistUser(finalProfile, prefs.getString(KEY_AUTH_TOKEN, ""))
        setGoogleUserRegistered(googleId, true)
        setSavedGoogleDisplayName(googleId, finalProfile.displayName)
        setSavedGoogleUsername(googleId, finalProfile.username)
        updateSettings(cloudData.settings)
        if (cloudData.matchHistory.isNotEmpty()) {
            val localHistory = getMatchHistory()
            val merged = (localHistory + cloudData.matchHistory)
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
                .let { if (it.size > 1000) it.take(1000) else it }
            prefs.edit().putString("match_history", Json.encodeToString(merged)).apply()
        }
        _authState.value = AuthState.Authenticated(finalProfile)
        sessionManager.startSessionWatcher(googleId, deviceId) {
            signOut()
        }
        return true
    }

    fun signOut() {
        val current = (_authState.value as? AuthState.Authenticated)?.user
        if (current != null) {
            val googleId = if (current.provider == AuthProvider.GOOGLE && current.uid.startsWith("google_")) {
                current.uid.removePrefix("google_")
            } else current.uid
            val backup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
                profile = current,
                settings = getSettings(),
                matchHistory = getMatchHistory(),
                lastBackupTimestamp = System.currentTimeMillis()
            )
            scope.launch(Dispatchers.IO) {
                sessionManager.backupUserDataSync(googleId, backup)
            }
        }
        sessionManager.stopSessionWatcher()
        com.bingo.multiplayer.domain.network.PresenceManager.stopPresence()
        if (context != null) {
            com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context).stopRealtimeProfileListener()
        }
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_NAME)
            .remove(KEY_USERNAME)
            .remove(KEY_EMAIL)
            .remove(KEY_AVATAR)
            .remove(KEY_AVATAR_BASE64)
            .remove(KEY_PROVIDER)
            .apply()
        _authState.value = AuthState.Unauthenticated
    }

    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    private fun loadExistingStats(): UserProfile {
        val streak = prefs.getInt(KEY_STREAK, 0)
        val bestStreak = maxOf(prefs.getInt(KEY_BEST_STREAK, streak), streak)
        return UserProfile(
            uid = "",
            displayName = "",
            avatarUrl = prefs.getString(KEY_AVATAR, null),
            avatarBase64 = prefs.getString(KEY_AVATAR_BASE64, null),
            gamesPlayed = prefs.getInt(KEY_GAMES_PLAYED, 0),
            gamesWon = prefs.getInt(KEY_GAMES_WON, 0),
            currentStreak = streak,
            bestStreak = bestStreak,
            level = prefs.getInt(KEY_LEVEL, 1),
            xp = prefs.getInt(KEY_XP, 0)
        )
    }

    private fun persistUser(profile: UserProfile, token: String? = null) {
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

        if (token != null) {
            editor.putString(KEY_AUTH_TOKEN, token)
        }
        editor.apply()

        if (context != null && profile.username.isNotBlank()) {
            com.bingo.multiplayer.domain.network.BingoFcmManager.syncCurrentUserToken(context, profile.username)
        }

        if (context != null && profile.uid.isNotBlank()) {
            val firestoreSync = com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
            firestoreSync.syncUserProfile(profile, getSettings())
            val existingMatches = getMatchHistory()
            if (existingMatches.isNotEmpty()) {
                firestoreSync.syncAllMatches(profile.uid, existingMatches)
            }
        }
    }

    companion object {
        @Volatile var activeInstance: AuthRepository? = null
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
