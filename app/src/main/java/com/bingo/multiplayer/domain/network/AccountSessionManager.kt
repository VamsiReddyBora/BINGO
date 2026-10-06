package com.bingo.multiplayer.domain.network

import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Keep
@Serializable
data class AccountSessionPacket(
    val type: String, // "CHECK_ACTIVE", "SESSION_ACTIVE", "FORCE_LOGOUT"
    val googleId: String,
    val deviceId: String,
    val deviceModel: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@Serializable
data class PlayerRegistryEntry(
    val username: String,
    val uid: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val level: Int = 1,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val appVersion: String = NetworkConfig.APP_VERSION_NAME,
    val appVersionCode: Int = NetworkConfig.APP_VERSION_CODE
) {
    val isOnline: Boolean
        get() = (System.currentTimeMillis() - lastSeenTimestamp) < 120_000L

    val winRatePercentage: Int
        get() = if (gamesPlayed > 0) ((gamesWon.toFloat() / gamesPlayed) * 100).toInt() else 0

    val rankTitle: String
        get() = when {
            level >= 8 -> "Grandmaster"
            level >= 5 -> "Gold Master"
            level >= 3 -> "Silver Competitor"
            else -> "Bronze Player"
        }
}

/**
 * Checks whether a given string is a local filesystem path on an Android device.
 * CRITICAL: Base64-encoded JPEG images start with `/9j/` (due to JPEG magic bytes 0xFF 0xD8 0xFF).
 * Therefore, we must never assume that a string starting with `/` is a local file path!
 */
fun isLocalFilePath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    if (path.length > 500) return false // Base64 images are thousands of chars
    if (path.startsWith("data:image")) return false
    if (path.startsWith("/9j/") || path.startsWith("iVBORw") || path.startsWith("R0lGOD")) return false
    return path.startsWith("/") && (
        path.contains("/files/") ||
        path.contains("/data/") ||
        path.contains("/storage/") ||
        path.contains("/sdcard/") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".png") ||
        path.endsWith(".webp") ||
        try { File(path).exists() } catch (_: Exception) { false }
    )
}

/**
 * Manages cloud user persistence, account restore, and active device sessions.
 * Uses persistent HTTPS cloud storage (KeyVal index + ExtendsClass JSON Bin)
 * ensuring user data (username, photo, stats, settings, match history) survives
 * logouts, app uninstalls, and device transfers.
 */
class AccountSessionManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = NetworkConfig.httpClient

    private val appKey = NetworkConfig.KEYVALUE_APP_KEY
    private val legacyAppKey = "uewa9cmq"


    private fun getUserMutex(googleId: String): Mutex =
        userMutexes.computeIfAbsent(googleId) { Mutex() }

    private val brokerUrl = NetworkConfig.BROKER_URL
    private var activeMqttClient: MqttAsyncClient? = null
    private var activeGoogleId: String? = null
    private var currentDeviceId: String? = null
    private var onKickedCallback: (() -> Unit)? = null

    // ─────────────────────────────────────────────────────────────
    // HTTPS Cloud Storage Engine (KeyVal + ExtendsClass)
    // ─────────────────────────────────────────────────────────────

    private suspend fun setKeyValue(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encKey = URLEncoder.encode(key.trim(), "UTF-8")
            val encVal = URLEncoder.encode(value.trim(), "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
            val emptyBody = "".toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("Content-Length", "0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "setKeyValue error for $key: ${e.message}")
            false
        }
    }

    private suspend fun getKeyValue(key: String): String? = withContext(Dispatchers.IO) {
        val encKey = URLEncoder.encode(key.trim(), "UTF-8")
        val clean = fetchKeyValueInternal("$appKey/$encKey")
        if (!clean.isNullOrBlank()) return@withContext clean
        // Fallback to legacy key namespace if not found
        fetchKeyValueInternal("$legacyAppKey/$encKey")
    }

    private fun fetchKeyValueInternal(path: String): String? {
        return try {
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$path"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string()?.trim() ?: return null
                if (raw.isBlank() || raw == "\"\"" || raw == "null") return null
                val clean = raw.removeSurrounding("\"").trim()
                if (clean.isBlank() || clean == "null") null else clean
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createJsonBin(jsonPayload: String): String? = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://extendsclass.com/api/json-storage/bin")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("AccountSessionManager", "createJsonBin failed: code=${response.code}")
                    return@use null
                }
                val respBody = response.body?.string() ?: return@use null
                val regex = "\"id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val match = regex.find(respBody)
                val id = match?.groupValues?.getOrNull(1)
                Log.d("AccountSessionManager", "Created json bin: id=$id")
                id
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "createJsonBin exception: ${e.message}")
            null
        }
    }

    private suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://extendsclass.com/api/json-storage/bin/$binId")
                .put(body)
                .header("Content-Type", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("AccountSessionManager", "updateJsonBin failed: code=${response.code}")
                    false
                } else {
                    Log.d("AccountSessionManager", "Updated json bin: id=$binId")
                    true
                }
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "updateJsonBin exception: ${e.message}")
            false
        }
    }

    private suspend fun getJsonBin(binId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://extendsclass.com/api/json-storage/bin/$binId")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("AccountSessionManager", "getJsonBin failed: code=${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "getJsonBin exception: ${e.message}")
            null
        }
    }

    /**
     * Synchronously backs up full user profile, custom photo, settings, and match records.
     * Guaranteed to persist across app uninstalls and device switches.
     */
    suspend fun backupUserDataSync(
        googleId: String,
        backup: com.bingo.multiplayer.domain.model.CloudUserDataBackup
    ): Boolean = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext false

        val mutex = getUserMutex(gid)
        mutex.withLock {
            try {
                val jsonPayload = json.encodeToString(backup)

                val cleanUser = backup.profile.username.trim().lowercase().removePrefix("@")
                var binId = binIdCache[gid]
                if (binId.isNullOrBlank() && cleanUser.isNotBlank()) {
                    binId = binIdCache["user_$cleanUser"]
                }
                if (binId.isNullOrBlank()) {
                    binId = getKeyValue("gid_$gid")
                }
                if (binId.isNullOrBlank() && cleanUser.isNotBlank()) {
                    binId = getKeyValue("user_$cleanUser")
                }

                var success = false
                if (!binId.isNullOrBlank()) {
                    val updated = updateJsonBin(binId, jsonPayload)
                    if (updated) {
                        binIdCache[gid] = binId
                        if (cleanUser.isNotBlank()) binIdCache["user_$cleanUser"] = binId
                        success = true
                    }
                }

                if (!success) {
                    val newBin = createJsonBin(jsonPayload)
                    if (!newBin.isNullOrBlank()) {
                        binId = newBin
                        binIdCache[gid] = newBin
                        setKeyValue("gid_$gid", newBin)
                        success = true
                    }
                }

                backupMemoryCache[gid] = backup
                if (cleanUser.isNotBlank()) {
                    backupMemoryCache["user_$cleanUser"] = backup
                }
                if (cleanUser.isNotBlank() && !binId.isNullOrBlank()) {
                    setKeyValue("user_$cleanUser", binId)
                    binIdCache["user_$cleanUser"] = binId
                }

                // Also broadcast lightweight entry to MQTT as secondary notification
                try {
                    val entry = PlayerRegistryEntry(
                        username = cleanUser,
                        uid = backup.profile.uid,
                        displayName = backup.profile.displayName,
                        avatarUrl = backup.profile.avatarUrl,
                        gamesPlayed = backup.profile.gamesPlayed,
                        gamesWon = backup.profile.gamesWon,
                        currentStreak = backup.profile.currentStreak,
                        bestStreak = backup.profile.bestStreak,
                        level = backup.profile.level,
                        lastSeenTimestamp = backup.lastBackupTimestamp
                    )
                    claimUsernameMqtt(entry, gid)
                } catch (_: Exception) {}

                Log.i("AccountSessionManager", "Cloud backup result for $gid: success=$success, binId=$binId, user=$cleanUser")
                success || backupMemoryCache.containsKey(gid)
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "backupUserDataSync failed for $gid: ${e.message}", e)
                backupMemoryCache.containsKey(gid)
            }
        }
    }

    /**
     * Asynchronously fires backup in the background.
     */
    fun backupUserData(googleId: String, backup: com.bingo.multiplayer.domain.model.CloudUserDataBackup) {
        val gid = googleId.trim()
        if (gid.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val ok = backupUserDataSync(gid, backup)
            if (!ok) {
                kotlinx.coroutines.delay(2000L)
                backupUserDataSync(gid, backup)
            }
        }
    }

    /**
     * Fetches complete cloud backup (profile, photo, settings, game stats).
     * First checks HTTPS cloud bin, then falls back to MQTT if needed.
     */
    suspend fun fetchUserDataBackup(
        googleId: String,
        timeoutMs: Long = 7000L
    ): com.bingo.multiplayer.domain.model.CloudUserDataBackup? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val mutex = getUserMutex(gid)
        val result = withTimeoutOrNull(timeoutMs) {
            mutex.withLock {
                try {
                    var binId = binIdCache[gid]
                    if (binId.isNullOrBlank()) {
                        binId = getKeyValue("gid_$gid")
                    }
                    if (binId.isNullOrBlank()) {
                        Log.d("AccountSessionManager", "No cloud bin found in KV for Google ID $gid")
                        return@withLock backupMemoryCache[gid]
                    }
                    binIdCache[gid] = binId

                    val rawJson = getJsonBin(binId)
                    if (rawJson.isNullOrBlank()) {
                        Log.d("AccountSessionManager", "Empty bin content for binId $binId")
                        return@withLock backupMemoryCache[gid]
                    }

                    val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                    val cleanUser = backup.profile.username.trim().lowercase().removePrefix("@")
                    if (cleanUser.isNotBlank()) {
                        binIdCache["user_$cleanUser"] = binId
                        backupMemoryCache["user_$cleanUser"] = backup
                    }
                    backupMemoryCache[gid] = backup
                    Log.i(
                        "AccountSessionManager",
                        "Restored cloud backup for $gid: user=${backup.profile.username}, played=${backup.profile.gamesPlayed}, won=${backup.profile.gamesWon}, hasAvatar=${backup.profile.avatarBase64 != null}"
                    )
                    backup
                } catch (e: Exception) {
                    Log.w("AccountSessionManager", "fetchUserDataBackup error: ${e.message}", e)
                    backupMemoryCache[gid]
                }
            }
        }

        result ?: backupMemoryCache[gid] ?: fetchUserDataBackupMqtt(gid, timeoutMs = 2000L)
    }

    /**
     * Restores a player's profile by their Google ID from the cloud.
     */
    suspend fun lookupGoogleProfile(
        googleId: String,
        timeoutMs: Long = 7000L
    ): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val backup = fetchUserDataBackup(gid, timeoutMs)
        if (backup != null) {
            val p = backup.profile
            return@withContext PlayerRegistryEntry(
                username = p.username,
                uid = p.uid,
                displayName = p.displayName,
                avatarUrl = p.avatarUrl,
                gamesPlayed = p.gamesPlayed,
                gamesWon = p.gamesWon,
                currentStreak = p.currentStreak,
                bestStreak = p.bestStreak,
                level = p.level,
                lastSeenTimestamp = backup.lastBackupTimestamp
            )
        }

        lookupGoogleProfileMqtt(gid, timeoutMs = 2000L)
    }

    fun clearRegistryCache(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        registryCache.remove(clean)
    }

    /**
     * Searches for a player globally by their unique username (Player ID).
     */
    suspend fun searchPlayerByUsername(
        username: String,
        timeoutMs: Long = 6000L,
        forceRefresh: Boolean = false
    ): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null

        if (forceRefresh) {
            registryCache.remove(clean)
            binIdCache.remove("ava_$clean")
            binIdCache.remove("user_$clean")
        }

        // 0. In-memory hot cache for instant 0ms repeat searches (only if not forced)
        if (!forceRefresh) {
            val cached = registryCache[clean]
            if (cached != null && (System.currentTimeMillis() - cached.first) < 30_000L) {
                return@withContext cached.second
            }
        }

        // 1. Direct Cloud Registry Lookup via Base64 KeyValue (fastest, guaranteed)
        try {
            val b64 = getKeyValue("reg_$clean")
            if (!b64.isNullOrBlank()) {
                val jsonStr = try {
                    val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP)
                    String(bytes, StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    b64
                }
                if (jsonStr.startsWith("{")) {
                    var entry = json.decodeFromString<PlayerRegistryEntry>(jsonStr)

                    // 1. Resolve cloud avatar if local or missing
                    val currentAvatar = entry.avatarUrl
                    if (currentAvatar.isNullOrBlank() || isLocalFilePath(currentAvatar)) {
                        try {
                            val avaBinId = binIdCache["ava_$clean"] ?: getKeyValue("ava_$clean")
                            if (!avaBinId.isNullOrBlank()) {
                                binIdCache["ava_$clean"] = avaBinId
                                val rawJson = getJsonBin(avaBinId)
                                if (!rawJson.isNullOrBlank()) {
                                    val avatarData = try {
                                        org.json.JSONObject(rawJson).optString("avatar").takeIf { it.isNotBlank() && !isLocalFilePath(it) }
                                    } catch (_: Exception) {
                                        val regex = "\"avatar\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                                        regex.find(rawJson)?.groupValues?.getOrNull(1)?.takeIf { !isLocalFilePath(it) }
                                    }
                                    if (!avatarData.isNullOrBlank()) {
                                        entry = entry.copy(avatarUrl = avatarData)
                                    }
                                }
                            }
                            if (entry.avatarUrl.isNullOrBlank() || isLocalFilePath(entry.avatarUrl)) {
                                val binId = binIdCache["user_$clean"] ?: getKeyValue("user_$clean")
                                if (!binId.isNullOrBlank()) {
                                    binIdCache["user_$clean"] = binId
                                    val rawJson = getJsonBin(binId)
                                    if (!rawJson.isNullOrBlank()) {
                                        val cloudAvatar = try {
                                            val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                                            backup.profile.avatarBase64?.takeIf { !isLocalFilePath(it) }
                                                ?: backup.profile.avatarUrl?.takeIf { !isLocalFilePath(it) }
                                        } catch (_: Exception) {
                                            try {
                                                val obj = org.json.JSONObject(rawJson)
                                                val prof = obj.optJSONObject("profile")
                                                prof?.optString("avatarBase64")?.takeIf { it.isNotBlank() && !isLocalFilePath(it) }
                                                    ?: prof?.optString("avatarUrl")?.takeIf { it.isNotBlank() && !isLocalFilePath(it) }
                                            } catch (_: Exception) {
                                                val b64Regex = "\"avatarBase64\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                                                b64Regex.find(rawJson)?.groupValues?.getOrNull(1)?.takeIf { !isLocalFilePath(it) }
                                            }
                                        }
                                        if (!cloudAvatar.isNullOrBlank()) {
                                            entry = entry.copy(avatarUrl = cloudAvatar)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // 1b. If avatar still missing, query MQTT retained registry topic
                    if (entry.avatarUrl.isNullOrBlank() || isLocalFilePath(entry.avatarUrl)) {
                        try {
                            val mqttEntry = searchPlayerByUsernameMqtt(clean, timeoutMs = 1500L)
                            if (mqttEntry != null && !mqttEntry.avatarUrl.isNullOrBlank() && !isLocalFilePath(mqttEntry.avatarUrl)) {
                                entry = entry.copy(avatarUrl = mqttEntry.avatarUrl)
                            }
                        } catch (_: Exception) {}
                    }

                    // 2. Fetch fresh real-time cloud presence
                    try {
                        val cloudPres = com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresence(clean)
                        if (cloudPres != null && cloudPres.timestamp > 0L) {
                            entry = entry.copy(lastSeenTimestamp = cloudPres.timestamp)
                        }
                    } catch (_: Exception) {}

                    registryCache[clean] = Pair(System.currentTimeMillis(), entry)
                    return@withContext entry
                }
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "searchPlayerByUsername reg_ lookup error: ${e.message}")
        }

        // 2. Real-time MQTT Registry Lookup
        val mqttResult = searchPlayerByUsernameMqtt(clean, timeoutMs = 2500L)
        if (mqttResult != null) {
            var entry: PlayerRegistryEntry = mqttResult
            val currentAvatar = entry.avatarUrl
            if (currentAvatar.isNullOrBlank() || isLocalFilePath(currentAvatar)) {
                try {
                    val avaBinId = binIdCache["ava_$clean"] ?: getKeyValue("ava_$clean")
                    if (!avaBinId.isNullOrBlank()) {
                        binIdCache["ava_$clean"] = avaBinId
                        val rawJson = getJsonBin(avaBinId)
                        if (!rawJson.isNullOrBlank()) {
                            val regex = "\"avatar\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                            val match = regex.find(rawJson)
                            val avatarData = match?.groupValues?.getOrNull(1)?.takeIf { !isLocalFilePath(it) }
                            if (!avatarData.isNullOrBlank()) {
                                entry = entry.copy(avatarUrl = avatarData)
                            }
                        }
                    }
                    if (entry.avatarUrl.isNullOrBlank() || isLocalFilePath(entry.avatarUrl)) {
                        val binId = binIdCache["user_$clean"] ?: getKeyValue("user_$clean")
                        if (!binId.isNullOrBlank()) {
                            binIdCache["user_$clean"] = binId
                            val rawJson = getJsonBin(binId)
                            if (!rawJson.isNullOrBlank()) {
                                val cloudAvatar = try {
                                    val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                                    backup.profile.avatarBase64?.takeIf { !isLocalFilePath(it) }
                                        ?: backup.profile.avatarUrl?.takeIf { !isLocalFilePath(it) }
                                } catch (_: Exception) {
                                    val b64Regex = "\"avatarBase64\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                                    b64Regex.find(rawJson)?.groupValues?.getOrNull(1)?.takeIf { !isLocalFilePath(it) }
                                }
                                if (!cloudAvatar.isNullOrBlank()) {
                                    entry = entry.copy(avatarUrl = cloudAvatar)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            try {
                val cloudPres = com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresence(clean)
                if (cloudPres != null && cloudPres.timestamp > 0L) {
                    entry = entry.copy(lastSeenTimestamp = cloudPres.timestamp)
                }
            } catch (_: Exception) {}

            registryCache[clean] = Pair(System.currentTimeMillis(), entry)
            return@withContext entry
        }

        // 3. Fallback: Legacy binId Lookup
        val legacyResult = withTimeoutOrNull(timeoutMs) {
            try {
                var binId = binIdCache["user_$clean"]
                if (binId.isNullOrBlank()) {
                    binId = getKeyValue("user_$clean")
                }
                if (binId.isNullOrBlank()) return@withTimeoutOrNull null
                binIdCache["user_$clean"] = binId

                val rawJson = getJsonBin(binId) ?: return@withTimeoutOrNull null
                val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                val p = backup.profile
                val entry = PlayerRegistryEntry(
                    username = p.username.ifBlank { clean },
                    uid = p.uid,
                    displayName = p.displayName,
                    avatarUrl = backup.profile.avatarBase64 ?: p.avatarUrl,
                    gamesPlayed = p.gamesPlayed,
                    gamesWon = p.gamesWon,
                    currentStreak = p.currentStreak,
                    bestStreak = p.bestStreak,
                    level = p.level,
                    lastSeenTimestamp = backup.lastBackupTimestamp
                )
                registryCache[clean] = Pair(System.currentTimeMillis(), entry)
                entry
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "searchPlayerByUsername error: ${e.message}")
                null
            }
        }

        legacyResult
    }

    /**
     * Checks if a unique username (Player ID) is available globally.
     * Returns true if no conflicting owner is registered.
     */
    suspend fun checkUsernameAvailable(
        username: String,
        currentUid: String,
        timeoutMs: Long = 5000L
    ): Boolean = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.length < 3 || clean.length > 20 || !clean.all { it.isLetterOrDigit() || it == '_' }) {
            return@withContext false
        }

        withTimeoutOrNull(timeoutMs) {
            try {
                val binId = getKeyValue("user_$clean")
                if (binId.isNullOrBlank()) {
                    return@withTimeoutOrNull true
                }

                // If binId exists, check who owns it
                val rawJson = getJsonBin(binId) ?: return@withTimeoutOrNull true
                val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                val ownerUid = backup.profile.uid
                val curUidNorm = if (currentUid.startsWith("google_")) currentUid else "google_$currentUid"
                val ownerUidNorm = if (ownerUid.startsWith("google_")) ownerUid else "google_$ownerUid"

                ownerUidNorm == curUidNorm
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "checkUsernameAvailable error: ${e.message}")
                true
            }
        } ?: true
    }

    /**
     * Claims and registers a unique username (Player ID) globally.
     * Links the username to the Google ID so the account can be restored on any device.
     */
    fun claimUsername(entry: PlayerRegistryEntry, googleId: String? = null) {
        val clean = entry.username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        val gid = googleId ?: entry.uid.removePrefix("google_").takeIf { entry.uid.startsWith("google_") }

        registryCache[clean] = Pair(System.currentTimeMillis(), entry.copy(username = clean))

        scope.launch(Dispatchers.IO) {
            try {
                // If entry has a large avatarUrl (Base64) or local path, don't put 30KB into KeyValue query param URL!
                val cleanAvatar = if (isLocalFilePath(entry.avatarUrl)) null else entry.avatarUrl
                val compactEntry = if (cleanAvatar != null && cleanAvatar.length > 500) {
                    entry.copy(username = clean, avatarUrl = null)
                } else {
                    entry.copy(username = clean, avatarUrl = cleanAvatar)
                }

                // 1. Direct Cloud Registry Entry (Fast, persistent, 1-hop lookup)
                val jsonStr = json.encodeToString(compactEntry)
                val b64 = Base64.encodeToString(jsonStr.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trim()
                setKeyValue("reg_$clean", b64)

                // 1b. Maintain cloud user directory index
                try {
                    val currentDir = getKeyValue("user_directory")
                    if (!currentDir.isNullOrBlank() && currentDir != "null") {
                        val users = currentDir.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
                        if (users.add(clean)) {
                            setKeyValue("user_directory", users.sorted().joinToString(","))
                        }
                    }
                } catch (_: Exception) {}

                // 2. Save avatar JSON bin if avatar is a Base64 string
                if (!entry.avatarUrl.isNullOrBlank() && !isLocalFilePath(entry.avatarUrl)) {
                    saveUserAvatar(clean, entry.avatarUrl)
                }

                // 3. Legacy binId mapping if available
                if (!gid.isNullOrBlank()) {
                    var binId = binIdCache[gid]
                    if (binId.isNullOrBlank()) {
                        binId = getKeyValue("gid_$gid")
                    }
                    if (!binId.isNullOrBlank()) {
                        setKeyValue("user_$clean", binId)
                        binIdCache["user_$clean"] = binId
                        binIdCache[gid] = binId
                    }
                }

                // 4. MQTT broadcast
                claimUsernameMqtt(entry, gid)
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "claimUsername error: ${e.message}")
            }
        }
    }

    /**
     * Uploads Base64 profile avatar to ExtendsClass and links it to ava_$clean in KeyValue.
     */
    suspend fun saveUserAvatar(cleanUsername: String, avatarBase64: String): Boolean = withContext(Dispatchers.IO) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank() || avatarBase64.isBlank() || isLocalFilePath(avatarBase64)) return@withContext false
        try {
            val payload = org.json.JSONObject().put("avatar", avatarBase64).toString()
            val binId = createJsonBin(payload)
            if (!binId.isNullOrBlank()) {
                binIdCache["ava_$clean"] = binId
                setKeyValue("ava_$clean", binId)
                Log.i("AccountSessionManager", "Saved cloud avatar bin for @$clean: $binId")
                true
            } else false
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "saveUserAvatar error: ${e.message}")
            false
        }
    }

    /**
     * Removes avatar from KeyValue store.
     */
    suspend fun deleteUserAvatar(cleanUsername: String): Boolean = withContext(Dispatchers.IO) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext false
        try {
            binIdCache.remove("ava_$clean")
            setKeyValue("ava_$clean", "")
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "deleteUserAvatar error: ${e.message}")
            false
        }
    }

    /**
     * Broadcasts profile avatar update across MQTT with retained flag.
     * All listening devices (in lobby, match, friends list, or app) receive this immediately.
     */
    fun broadcastAvatarUpdate(cleanUsername: String, avatarBase64: String?) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val topic = "bingo/v3/avatar_update/$clean"
                val tempClientId = "ava_up_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                client.connect(options).waitForCompletion(2000L)
                val payloadObj = org.json.JSONObject()
                    .put("username", clean)
                    .put("avatar", avatarBase64 ?: "")
                    .put("timestamp", System.currentTimeMillis())
                val message = MqttMessage(payloadObj.toString().toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                client.publish(topic, message).waitForCompletion(2000L)
                client.disconnect()
                client.close()
                Log.i("AccountSessionManager", "Broadcast avatar update for @$clean (hasAvatar=${!avatarBase64.isNullOrBlank()})")
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "Failed to broadcast avatar update for @$clean: ${e.message}")
            }
        }
    }

    /**
     * Handles an incoming real-time avatar update for a user.
     * Updates in-memory registryCache and invalidates binIdCache.
     */
    fun onRemoteAvatarUpdated(cleanUsername: String, avatarUrl: String?) {
        val clean = cleanUsername.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        binIdCache.remove("ava_$clean")
        val cached = registryCache[clean]?.second
        if (cached != null) {
            registryCache[clean] = Pair(System.currentTimeMillis(), cached.copy(avatarUrl = avatarUrl?.takeIf { it.isNotBlank() }))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Multi-Device Session Coordination (MQTT Real-time)
    // ─────────────────────────────────────────────────────────────

    private fun getSessionTopic(googleId: String): String =
        "bingo/v3/auth_session/${googleId.trim()}"

    /**
     * Checks if another device is currently active for this Google Account.
     * Returns the other device's model name if an active session is found, or null if none.
     */
    suspend fun checkForActiveDevice(
        googleId: String,
        localDeviceId: String,
        timeoutMs: Long = 1200L
    ): String? {
        val topic = getSessionTopic(googleId)
        val responseDeferred = CompletableDeferred<String?>()
        val tempClientId = "check_${localDeviceId.take(8)}_${UUID.randomUUID().toString().take(4)}"

        val tempClient = try {
            MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "Failed to init MQTT client for check: ${e.message}")
            return null
        }

        try {
            val connectOptions = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 2
                keepAliveInterval = 10
                NetworkConfig.applyMqttOptions(this)
            }

            tempClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        tempClient.subscribe(topic, 1) { _, message ->
                            try {
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val packet = json.decodeFromString<AccountSessionPacket>(payload)
                                if (packet.googleId == googleId && packet.deviceId != localDeviceId) {
                                    if (packet.type == "SESSION_ACTIVE") {
                                        responseDeferred.complete(packet.deviceModel.ifBlank { "Another Device" })
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        // Broadcast inquiry for any active device
                        scope.launch {
                            delay(150)
                            val checkPacket = AccountSessionPacket(
                                type = "CHECK_ACTIVE",
                                googleId = googleId,
                                deviceId = localDeviceId,
                                deviceModel = getDeviceModelName()
                            )
                            val msg = MqttMessage(json.encodeToString(checkPacket).toByteArray(StandardCharsets.UTF_8)).apply {
                                qos = 1
                            }
                            tempClient.publish(topic, msg)
                        }
                    } catch (e: Exception) {
                        responseDeferred.complete(null)
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    responseDeferred.complete(null)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            tempClient.connect(connectOptions).waitForCompletion(2000)

            val result = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            }

            try {
                if (tempClient.isConnected) {
                    tempClient.disconnect().waitForCompletion(500)
                }
                tempClient.close()
            } catch (_: Exception) {}

            return result
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "Session check completed with exception: ${e.message}")
            try {
                tempClient.close()
            } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Kicks any other device active on this Google Account.
     */
    fun forceLogoutOtherDevices(googleId: String, localDeviceId: String) {
        val topic = getSessionTopic(googleId)
        val kickerClientId = "kick_${localDeviceId.take(8)}_${UUID.randomUUID().toString().take(4)}"

        scope.launch(Dispatchers.IO) {
            try {
                val kickerClient = MqttAsyncClient(brokerUrl, kickerClientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                kickerClient.connect(options).waitForCompletion(2000)

                val packet = AccountSessionPacket(
                    type = "FORCE_LOGOUT",
                    googleId = googleId,
                    deviceId = localDeviceId,
                    deviceModel = getDeviceModelName()
                )
                val msg = MqttMessage(json.encodeToString(packet).toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                }
                kickerClient.publish(topic, msg).waitForCompletion(1000)
                kickerClient.disconnect().waitForCompletion(500)
                kickerClient.close()
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "forceLogoutOtherDevices error: ${e.message}")
            }
        }
    }

    /**
     * Starts listening on the account session topic for the currently authenticated device.
     */
    fun startSessionWatcher(
        googleId: String,
        localDeviceId: String,
        onForcedLogout: () -> Unit
    ) {
        stopSessionWatcher()

        activeGoogleId = googleId
        currentDeviceId = localDeviceId
        onKickedCallback = onForcedLogout

        val topic = getSessionTopic(googleId)
        val clientId = "session_${localDeviceId.take(8)}_${UUID.randomUUID().toString().take(4)}"

        scope.launch(Dispatchers.IO) {
            try {
                val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
                activeMqttClient = client

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 5
                    keepAliveInterval = 15
                    NetworkConfig.applyMqttOptions(this)
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        try {
                            client.subscribe(topic, 1) { _, message ->
                                try {
                                    val payload = String(message.payload, StandardCharsets.UTF_8)
                                    val packet = json.decodeFromString<AccountSessionPacket>(payload)
                                    if (packet.googleId == googleId && packet.deviceId != localDeviceId) {
                                        when (packet.type) {
                                            "CHECK_ACTIVE" -> {
                                                val resp = AccountSessionPacket(
                                                    type = "SESSION_ACTIVE",
                                                    googleId = googleId,
                                                    deviceId = localDeviceId,
                                                    deviceModel = getDeviceModelName()
                                                )
                                                val msg = MqttMessage(json.encodeToString(resp).toByteArray(StandardCharsets.UTF_8)).apply {
                                                    qos = 1
                                                }
                                                client.publish(topic, msg)
                                            }
                                            "FORCE_LOGOUT" -> {
                                                Log.i("AccountSessionManager", "Session claimed by device ${packet.deviceId} (${packet.deviceModel}). Forcing logout.")
                                                scope.launch(Dispatchers.Main) {
                                                    onKickedCallback?.invoke()
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            Log.w("AccountSessionManager", "Subscribe error in session watcher: ${e.message}")
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client.connect(options)
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "Failed to start session watcher: ${e.message}")
            }
        }
    }

    /**
     * Stops listening and disconnects the session client.
     */
    fun stopSessionWatcher() {
        val client = activeMqttClient
        activeMqttClient = null
        activeGoogleId = null
        currentDeviceId = null
        onKickedCallback = null

        scope.launch(Dispatchers.IO) {
            try {
                if (client != null && client.isConnected) {
                    client.disconnect()
                    client.close()
                }
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MQTT Secondary Fallbacks
    // ─────────────────────────────────────────────────────────────

    private fun claimUsernameMqtt(entry: PlayerRegistryEntry, googleId: String?) {
        val clean = entry.username.trim().lowercase().removePrefix("@")
        val topic = "bingo/v3/registry/$clean"
        try {
            val tempClientId = "claim_${UUID.randomUUID().toString().take(8)}"
            val client = MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 3
                NetworkConfig.applyMqttOptions(this)
            }
            client.connect(options).waitForCompletion(2000L)
            val payload = json.encodeToString(entry.copy(username = clean))
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message).waitForCompletion(2000L)

            if (!googleId.isNullOrBlank()) {
                val gTopic = "bingo/v3/google_account/$googleId"
                client.publish(gTopic, message).waitForCompletion(2000L)
            }

            client.disconnect()
            client.close()
        } catch (_: Exception) {}
    }

    private suspend fun lookupGoogleProfileMqtt(googleId: String, timeoutMs: Long): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val topic = "bingo/v3/google_account/$gid"
        val responseDeferred = CompletableDeferred<PlayerRegistryEntry?>()
        val tempClientId = "glookup_${UUID.randomUUID().toString().take(8)}"

        val tempClient = try {
            MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
        } catch (_: Exception) {
            return@withContext null
        }

        try {
            val connectOptions = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 2
                NetworkConfig.applyMqttOptions(this)
            }

            tempClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        tempClient.subscribe(topic, 1) { _, message ->
                            try {
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val entry = json.decodeFromString<PlayerRegistryEntry>(payload)
                                responseDeferred.complete(entry)
                            } catch (_: Exception) {
                                responseDeferred.complete(null)
                            }
                        }
                    } catch (_: Exception) {
                        responseDeferred.complete(null)
                    }
                }

                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            tempClient.connect(connectOptions)
            val result = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            }

            try {
                if (tempClient.isConnected) {
                    tempClient.disconnect()
                }
                tempClient.close()
            } catch (_: Exception) {}

            result
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchPlayerByUsernameMqtt(username: String, timeoutMs: Long): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null

        val topic = "bingo/v3/registry/$clean"
        val responseDeferred = CompletableDeferred<PlayerRegistryEntry?>()
        val tempClientId = "search_${UUID.randomUUID().toString().take(8)}"

        val tempClient = try {
            MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
        } catch (_: Exception) {
            return@withContext null
        }

        try {
            val connectOptions = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 2
                NetworkConfig.applyMqttOptions(this)
            }

            tempClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        tempClient.subscribe(topic, 1) { _, message ->
                            try {
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val entry = json.decodeFromString<PlayerRegistryEntry>(payload)
                                responseDeferred.complete(entry)
                            } catch (_: Exception) {
                                responseDeferred.complete(null)
                            }
                        }
                    } catch (_: Exception) {
                        responseDeferred.complete(null)
                    }
                }

                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            tempClient.connect(connectOptions)
            val result = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            }

            try {
                if (tempClient.isConnected) {
                    tempClient.disconnect()
                }
                tempClient.close()
            } catch (_: Exception) {}

            result
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchUserDataBackupMqtt(
        googleId: String,
        timeoutMs: Long
    ): com.bingo.multiplayer.domain.model.CloudUserDataBackup? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val topic = "bingo/v3/cloud_backup/$gid"
        val responseDeferred = CompletableDeferred<com.bingo.multiplayer.domain.model.CloudUserDataBackup?>()
        val tempClientId = "fetch_${UUID.randomUUID().toString().take(8)}"

        val tempClient = try {
            MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
        } catch (_: Exception) {
            return@withContext null
        }

        try {
            val connectOptions = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 2
                NetworkConfig.applyMqttOptions(this)
            }

            tempClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        tempClient.subscribe(topic, 1) { _, message ->
                            try {
                                val payload = String(message.payload, StandardCharsets.UTF_8)
                                val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(payload)
                                responseDeferred.complete(backup)
                            } catch (_: Exception) {
                                responseDeferred.complete(null)
                            }
                        }
                    } catch (_: Exception) {
                        responseDeferred.complete(null)
                    }
                }

                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            tempClient.connect(connectOptions)
            val result = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            }

            try {
                if (tempClient.isConnected) {
                    tempClient.disconnect()
                }
                tempClient.close()
            } catch (_: Exception) {}

            result
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val binIdCache = ConcurrentHashMap<String, String>()
        private val registryCache = ConcurrentHashMap<String, Pair<Long, PlayerRegistryEntry>>()
        private val userMutexes = ConcurrentHashMap<String, Mutex>()
        private val backupMemoryCache = ConcurrentHashMap<String, com.bingo.multiplayer.domain.model.CloudUserDataBackup>()

        val defaultInstance by lazy { AccountSessionManager() }

        fun getCachedPlayer(username: String): PlayerRegistryEntry? {
            val clean = username.trim().lowercase().removePrefix("@")
            return registryCache[clean]?.second
        }

        fun cachePlayer(entry: PlayerRegistryEntry) {
            val clean = entry.username.trim().lowercase().removePrefix("@")
            if (clean.isNotBlank()) {
                registryCache[clean] = Pair(System.currentTimeMillis(), entry)
            }
        }

        fun prefetchPlayer(username: String, scope: kotlinx.coroutines.CoroutineScope) {
            val clean = username.trim().lowercase().removePrefix("@")
            if (clean.isBlank()) return
            val cached = registryCache[clean]
            if (cached != null && (System.currentTimeMillis() - cached.first) < 120_000L) {
                return
            }
            scope.launch(Dispatchers.IO) {
                try {
                    searchPlayerByUsername(clean, timeoutMs = 4000L, forceRefresh = false)
                } catch (_: Exception) {}
            }
        }

        suspend fun searchPlayerByUsername(
            username: String,
            timeoutMs: Long = 6000L,
            forceRefresh: Boolean = false
        ): PlayerRegistryEntry? {
            return defaultInstance.searchPlayerByUsername(username, timeoutMs, forceRefresh)
        }

        fun getDeviceModelName(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim().ifEmpty { "Android Device" }
        }
    }
}
