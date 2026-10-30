package com.bingo.multiplayer.domain.network

import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    val appVersionCode: Int = NetworkConfig.APP_VERSION_CODE,
    val publicKey: String? = null,
    val adminRevision: Long = 0L
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
    private var wireSubscription: AutoCloseable? = null
    private var activeGoogleId: String? = null
    private var currentDeviceId: String? = null
    private var onKickedCallback: (() -> Unit)? = null

    val activeBackgroundJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

    fun trackJob(job: kotlinx.coroutines.Job): kotlinx.coroutines.Job {
        activeBackgroundJobs.add(job)
        job.invokeOnCompletion { activeBackgroundJobs.remove(job) }
        return job
    }

    suspend fun awaitAllBackgroundJobs() {
        activeBackgroundJobs.toList().forEach { it.join() }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTPS Cloud Storage Engine (KeyVal + ExtendsClass)
    // ─────────────────────────────────────────────────────────────

    interface CloudStorageGateway {
        suspend fun getKeyValue(key: String): String?
        suspend fun setKeyValue(key: String, value: String): Boolean
        suspend fun setKeyValueConditional(key: String, expected: String?, value: String): Boolean =
            if (getKeyValue(key) == expected) setKeyValue(key, value) else false
        suspend fun createJsonBin(jsonPayload: String): String?
        suspend fun updateJsonBin(binId: String, jsonPayload: String): Boolean
        suspend fun getJsonBin(binId: String): String?
    }

    private suspend fun setKeyValueConditional(key: String, expected: String?, value: String): Boolean = withContext(Dispatchers.IO) {
        customCloudGateway?.let { return@withContext it.setKeyValueConditional(key, expected, value) }
        when (val recheck = getKeyValueResult(key)) {
            is KeyValueResult.Found -> {
                if (recheck.value != expected) return@withContext false
            }
            is KeyValueResult.NotFound -> {
                if (expected != null) return@withContext false
            }
            is KeyValueResult.Unavailable -> return@withContext false
        }
        try {
            val encKey = URLEncoder.encode(key.trim(), "UTF-8")
            val encVal = URLEncoder.encode(value.trim(), "UTF-8")
            val urlBuilder = StringBuilder("${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal")
            if (expected != null) {
                val encExp = URLEncoder.encode(expected.trim(), "UTF-8")
                urlBuilder.append("&expected=").append(encExp)
            } else {
                urlBuilder.append("&expected=")
            }
            val emptyBody = "".toRequestBody(null)
            val reqBuilder = Request.Builder()
                .url(urlBuilder.toString())
                .post(emptyBody)
                .header("Content-Length", "0")
            if (expected != null) {
                reqBuilder.header("If-Match", expected)
            }
            val ok = httpClient.newCall(reqBuilder.build()).execute().use { response ->
                response.isSuccessful
            }
            if (!ok) return@withContext false
            val verified = getKeyValue(key)
            verified == value
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "setKeyValueConditional error for $key: ${e.message}")
            false
        }
    }

    internal suspend fun setKeyValue(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        customCloudGateway?.let { return@withContext it.setKeyValue(key, value) }
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

    sealed interface KeyValueResult {
        data class Found(val value: String) : KeyValueResult
        object NotFound : KeyValueResult
        data class Unavailable(val cause: Throwable? = null) : KeyValueResult
    }

    private suspend fun getKeyValueResult(key: String): KeyValueResult = withContext(Dispatchers.IO) {
        customCloudGateway?.let {
            return@withContext try {
                val value = it.getKeyValue(key)
                if (value.isNullOrBlank()) KeyValueResult.NotFound else KeyValueResult.Found(value)
            } catch (e: Exception) {
                KeyValueResult.Unavailable(e)
            }
        }
        val encKey = URLEncoder.encode(key.trim(), "UTF-8")
        when (val primary = fetchKeyValueResult("$appKey/$encKey")) {
            is KeyValueResult.Found -> primary
            is KeyValueResult.Unavailable -> primary
            is KeyValueResult.NotFound -> fetchKeyValueResult("$legacyAppKey/$encKey")
        }
    }

    internal suspend fun getKeyValue(key: String): String? {
        return (getKeyValueResult(key) as? KeyValueResult.Found)?.value
    }

    private fun fetchKeyValueResult(path: String): KeyValueResult {
        return try {
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$path"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) return KeyValueResult.NotFound
                if (!response.isSuccessful) return KeyValueResult.Unavailable(IOException("HTTP ${response.code}"))
                val raw = response.body?.string()?.trim() ?: return KeyValueResult.NotFound
                if (raw.isBlank() || raw == "\"\"" || raw == "null") return KeyValueResult.NotFound
                val clean = raw.removeSurrounding("\"").trim()
                if (clean.isBlank() || clean == "null") KeyValueResult.NotFound else KeyValueResult.Found(clean)
            }
        } catch (e: Exception) {
            KeyValueResult.Unavailable(e)
        }
    }

    private fun fetchKeyValueInternal(path: String): String? {
        return (fetchKeyValueResult(path) as? KeyValueResult.Found)?.value
    }

    private suspend fun createJsonBin(jsonPayload: String): String? = withContext(Dispatchers.IO) {
        customCloudGateway?.let { return@withContext it.createJsonBin(jsonPayload) }
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
        customCloudGateway?.let { return@withContext it.updateJsonBin(binId, jsonPayload) }
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
        customCloudGateway?.let { return@withContext it.getJsonBin(binId) }
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
                val cleanUser = backup.profile.username.trim().lowercase().removePrefix("@")
                val existing = backupMemoryCache[gid] ?: (if (cleanUser.isNotBlank()) backupMemoryCache["user_$cleanUser"] else null)
                if (existing != null && (backup.lastBackupTimestamp < existing.lastBackupTimestamp || backup.profile.gamesPlayed < existing.profile.gamesPlayed)) {
                    Log.w("AccountSessionManager", "Ignoring stale backup for $gid: incoming games=${backup.profile.gamesPlayed}, ts=${backup.lastBackupTimestamp} vs existing games=${existing.profile.gamesPlayed}, ts=${existing.lastBackupTimestamp}")
                    return@withLock true
                }

                val jsonPayload = json.encodeToString(backup)

                var binId = binIdCache[gid]
                if (binId.isNullOrBlank()) {
                    binId = getKeyValue("gid_$gid")
                }

                var success = false
                if (!binId.isNullOrBlank()) {
                    val rawJson = getJsonBin(binId)
                    val existingBackup = rawJson?.let {
                        try { json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(it) } catch (_: Exception) { null }
                    }
                    if (existingBackup == null) {
                        Log.w("AccountSessionManager", "backupUserDataSync rejected bin $binId: unable to read/verify owner (read failure or decode error)")
                        return@withContext false
                    } else if (existingBackup.profile.uid != backup.profile.uid) {
                        Log.w("AccountSessionManager", "backupUserDataSync rejected bin $binId: owner mismatch (${existingBackup.profile.uid} != ${backup.profile.uid})")
                        return@withContext false
                    } else {
                        val updated = updateJsonBin(binId, jsonPayload)
                        if (updated) {
                            binIdCache[gid] = binId
                            success = true
                        }
                    }
                }

                if (!success) {
                    val newBin = createJsonBin(jsonPayload)
                    if (!newBin.isNullOrBlank()) {
                        binId = newBin
                        val gidOk = setKeyValue("gid_$gid", newBin)
                        if (gidOk) {
                            binIdCache[gid] = newBin
                            success = true
                        }
                    }
                }

                if (success && cleanUser.isNotBlank() && !binId.isNullOrBlank()) {
                    when (val currentRes = getKeyValueResult("user_$cleanUser")) {
                        is KeyValueResult.Unavailable -> {
                            Log.w("AccountSessionManager", "backupUserDataSync user_$cleanUser unavailable, refusing overwrite")
                            success = false
                        }
                        is KeyValueResult.NotFound -> {
                            val userOk = setKeyValueConditional("user_$cleanUser", expected = null, value = binId)
                            if (userOk) {
                                binIdCache["user_$cleanUser"] = binId
                            } else {
                                success = false
                            }
                        }
                        is KeyValueResult.Found -> {
                            val currentMapping = currentRes.value
                            if (currentMapping == binId) {
                                val userOk = setKeyValueConditional("user_$cleanUser", expected = currentMapping, value = binId)
                                if (userOk) {
                                    binIdCache["user_$cleanUser"] = binId
                                } else {
                                    success = false
                                }
                            } else {
                                val rawOwner = getJsonBin(currentMapping)
                                val ownerBackup = rawOwner?.let {
                                    try { json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(it) } catch (_: Exception) { null }
                                }
                                if (ownerBackup == null) {
                                    Log.w("AccountSessionManager", "backupUserDataSync user_$cleanUser owner read failed, refusing overwrite")
                                    success = false
                                } else if (ownerBackup.profile.uid != backup.profile.uid) {
                                    Log.w("AccountSessionManager", "backupUserDataSync user_$cleanUser belongs to ${ownerBackup.profile.uid}, refusing overwrite")
                                    success = false
                                } else {
                                    val userOk = setKeyValueConditional("user_$cleanUser", expected = currentMapping, value = binId)
                                    if (userOk) {
                                        binIdCache["user_$cleanUser"] = binId
                                    } else {
                                        success = false
                                    }
                                }
                            }
                        }
                    }
                }

                if (success) {
                    backupMemoryCache[gid] = backup
                    if (cleanUser.isNotBlank()) {
                        backupMemoryCache["user_$cleanUser"] = backup
                    }
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
                        lastSeenTimestamp = backup.lastBackupTimestamp,
                        publicKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(backup.profile.uid)
                    )
                    claimUsernameMqtt(entry, gid)
                } catch (_: Exception) {}

                Log.i("AccountSessionManager", "Cloud backup result for $gid: success=$success, binId=$binId, user=$cleanUser")
                success
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "backupUserDataSync failed for $gid: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Asynchronously fires backup in the background.
     */
    fun backupUserData(googleId: String, backup: com.bingo.multiplayer.domain.model.CloudUserDataBackup) {
        val gid = googleId.trim()
        if (gid.isBlank()) return
        trackJob(scope.launch(Dispatchers.IO) {
            val ok = backupUserDataSync(gid, backup)
            if (!ok) {
                kotlinx.coroutines.delay(2000L)
                backupUserDataSync(gid, backup)
            }
        })
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

        // Firebase is the current public profile store, including photos for non-friend searches.
        if (customCloudGateway == null) {
            val profile = FirestoreSyncManager.getInstance().findPlayerByUsername(clean)
            if (profile != null) {
                registryCache[clean] = Pair(System.currentTimeMillis(), profile)
                return@withContext profile
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
                when (val result = getKeyValueResult("user_$clean")) {
                    is KeyValueResult.Unavailable -> return@withTimeoutOrNull false
                    is KeyValueResult.NotFound -> return@withTimeoutOrNull true
                    is KeyValueResult.Found -> {
                        val binId = result.value
                        val rawJson = getJsonBin(binId) ?: return@withTimeoutOrNull false
                        val backup = json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                        val ownerUid = backup.profile.uid
                        val curUidNorm = if (currentUid.startsWith("google_")) currentUid else "google_$currentUid"
                        val ownerUidNorm = if (ownerUid.startsWith("google_")) ownerUid else "google_$ownerUid"

                        ownerUidNorm == curUidNorm
                    }
                }
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "checkUsernameAvailable error: ${e.message}")
                false
            }
        } ?: false
    }

    /**
     * Claims and registers a unique username (Player ID) globally and synchronously.
     * Returns true if the cloud registry acknowledges the claim.
     */
    suspend fun claimUsernameSync(entry: PlayerRegistryEntry, googleId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val clean = entry.username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext false
        val gid = googleId ?: entry.uid.removePrefix("google_").takeIf { entry.uid.startsWith("google_") }

        try {
            // 1. Authoritative conditional check: ensure user_$clean is not already claimed by a different UID
            when (val res = getKeyValueResult("user_$clean")) {
                is KeyValueResult.Found -> {
                    val existingBinId = res.value
                    if (existingBinId.isNotBlank()) {
                        val rawJson = getJsonBin(existingBinId)
                        if (rawJson == null) {
                            Log.w("AccountSessionManager", "claimUsernameSync rejected: existing bin unreadable for user_$clean")
                            return@withContext false
                        }
                        val backup = try { json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson) } catch (_: Exception) { null }
                        if (backup == null) {
                            Log.w("AccountSessionManager", "claimUsernameSync rejected: existing backup unparseable for user_$clean")
                            return@withContext false
                        }
                        val curUidNorm = if (entry.uid.startsWith("google_")) entry.uid else "google_${entry.uid}"
                        val ownerUidNorm = if (backup.profile.uid.startsWith("google_")) backup.profile.uid else "google_${backup.profile.uid}"
                        if (curUidNorm != ownerUidNorm) {
                            Log.w("AccountSessionManager", "claimUsernameSync rejected: user_$clean already owned by $ownerUidNorm, attempting claim by $curUidNorm")
                            return@withContext false
                        }
                    }
                }
                is KeyValueResult.Unavailable -> return@withContext false
                is KeyValueResult.NotFound -> { /* free to claim */ }
            }

            // 2. Resolve or create binId for user alias mapping (supported for all account types)
            var targetBinId: String? = null
            val accountKey = gid ?: entry.uid
            if (accountKey.isNotBlank()) {
                targetBinId = binIdCache[accountKey] ?: getKeyValue("acc_$accountKey")
                if (targetBinId.isNullOrBlank() && !gid.isNullOrBlank()) {
                    targetBinId = binIdCache[gid] ?: getKeyValue("gid_$gid")
                }
                if (targetBinId.isNullOrBlank()) {
                    val initialBackup = com.bingo.multiplayer.domain.model.CloudUserDataBackup(
                        profile = com.bingo.multiplayer.domain.model.UserProfile(
                            uid = entry.uid,
                            username = clean,
                            displayName = entry.displayName,
                            avatarUrl = entry.avatarUrl,
                            gamesPlayed = entry.gamesPlayed,
                            gamesWon = entry.gamesWon,
                            currentStreak = entry.currentStreak,
                            bestStreak = entry.bestStreak,
                            level = entry.level,
                            provider = if (entry.uid.startsWith("google_")) com.bingo.multiplayer.domain.model.AuthProvider.GOOGLE else com.bingo.multiplayer.domain.model.AuthProvider.GUEST
                        ),
                        settings = com.bingo.multiplayer.domain.model.UserSettings(),
                        matchHistory = emptyList(),
                        lastBackupTimestamp = System.currentTimeMillis()
                    )
                    targetBinId = createJsonBin(json.encodeToString(initialBackup))
                    if (!targetBinId.isNullOrBlank()) {
                        binIdCache[accountKey] = targetBinId
                        setKeyValue("acc_$accountKey", targetBinId)
                        if (!gid.isNullOrBlank()) {
                            binIdCache[gid] = targetBinId
                            setKeyValue("gid_$gid", targetBinId)
                        }
                    }
                }
                if (targetBinId.isNullOrBlank()) {
                    Log.w("AccountSessionManager", "claimUsernameSync rejected: could not establish binId for $accountKey")
                    return@withContext false
                }
                var observedExisting: String? = null
                when (val recheck = getKeyValueResult("user_$clean")) {
                    is KeyValueResult.Found -> {
                        val existing = recheck.value
                        if (existing.isNotBlank() && existing != targetBinId) {
                            Log.w("AccountSessionManager", "claimUsernameSync rejected: user_$clean was acquired concurrently ($existing != $targetBinId)")
                            return@withContext false
                        }
                        if (existing == targetBinId) {
                            observedExisting = targetBinId
                        }
                    }
                    is KeyValueResult.Unavailable -> return@withContext false
                    is KeyValueResult.NotFound -> { /* confirmed unassigned */ }
                }
                val aliasOk = if (observedExisting == targetBinId) {
                    true
                } else {
                    setKeyValueConditional("user_$clean", expected = null, value = targetBinId)
                }
                if (!aliasOk) {
                    Log.w("AccountSessionManager", "claimUsernameSync rejected: failed to persist user_$clean alias")
                    return@withContext false
                }
                val verifiedBin = if (observedExisting == targetBinId) targetBinId else getKeyValue("user_$clean")
                if (verifiedBin != targetBinId) {
                    Log.w("AccountSessionManager", "claimUsernameSync rejected: user_$clean alias read-back mismatch: expected $targetBinId, got $verifiedBin")
                    return@withContext false
                }
                binIdCache["user_$clean"] = targetBinId
                binIdCache[accountKey] = targetBinId
                if (!gid.isNullOrBlank()) {
                    binIdCache[gid] = targetBinId
                }
            }

            val entryWithKey = if (entry.publicKey.isNullOrBlank()) {
                val existingKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(entry.uid)
                entry.copy(publicKey = existingKey)
            } else entry

            val cleanAvatar = if (isLocalFilePath(entryWithKey.avatarUrl)) null else entryWithKey.avatarUrl
            val compactEntry = if (cleanAvatar != null && cleanAvatar.length > 500) {
                entryWithKey.copy(username = clean, avatarUrl = null)
            } else {
                entryWithKey.copy(username = clean, avatarUrl = cleanAvatar)
            }

            val jsonStr = json.encodeToString(compactEntry)
            val b64 = Base64.encodeToString(jsonStr.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trim()
            val ok = setKeyValue("reg_$clean", b64)
            if (!ok) return@withContext false

            registryCache[clean] = Pair(System.currentTimeMillis(), entryWithKey.copy(username = clean))

            try {
                val currentDir = getKeyValue("user_directory")
                if (!currentDir.isNullOrBlank() && currentDir != "null") {
                    val users = currentDir.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
                    if (users.add(clean)) {
                        setKeyValue("user_directory", users.sorted().joinToString(","))
                    }
                }
            } catch (_: Exception) {}

            if (!entryWithKey.avatarUrl.isNullOrBlank() && !isLocalFilePath(entryWithKey.avatarUrl)) {
                saveUserAvatar(clean, entryWithKey.avatarUrl)
            }

            claimUsernameMqtt(entryWithKey, gid)
            true
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "claimUsernameSync error: ${e.message}")
            false
        }
    }

    /**
     * Releases an old username alias only if it is still owned by the specified UID.
     */
    suspend fun releaseUsernameAlias(oldUsername: String, currentUid: String): Boolean = withContext(Dispatchers.IO) {
        val cleanOld = oldUsername.trim().lowercase().removePrefix("@")
        if (cleanOld.isBlank()) return@withContext false
        try {
            when (val res = getKeyValueResult("user_$cleanOld")) {
                is KeyValueResult.NotFound -> {
                    binIdCache.remove("user_$cleanOld")
                    backupMemoryCache.remove("user_$cleanOld")
                    true
                }
                is KeyValueResult.Found -> {
                    val binId = res.value
                    if (binId.isBlank()) {
                        binIdCache.remove("user_$cleanOld")
                        backupMemoryCache.remove("user_$cleanOld")
                        return@withContext true
                    }
                    val rawJson = getJsonBin(binId)
                    if (rawJson == null) {
                        Log.w("AccountSessionManager", "releaseUsernameAlias: failed to read bin $binId (503 or network error), keeping pending release")
                        return@withContext false
                    }
                    val backup = try {
                        json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(rawJson)
                    } catch (_: Exception) {
                        return@withContext false
                    }
                    val curUidNorm = if (currentUid.startsWith("google_")) currentUid else "google_$currentUid"
                    val ownerUidNorm = if (backup.profile.uid.startsWith("google_")) backup.profile.uid else "google_${backup.profile.uid}"
                    if (ownerUidNorm == curUidNorm) {
                        binIdCache.remove("user_$cleanOld")
                        backupMemoryCache.remove("user_$cleanOld")
                        val clearOk = setKeyValueConditional("user_$cleanOld", expected = binId, value = "")
                        if (!clearOk) {
                            when (val recheck = getKeyValueResult("user_$cleanOld")) {
                                is KeyValueResult.NotFound -> true
                                is KeyValueResult.Found -> {
                                    if (recheck.value.isBlank()) true
                                    else {
                                        val newRaw = getJsonBin(recheck.value)
                                        val newBackup = newRaw?.let {
                                            try { json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(it) } catch (_: Exception) { null }
                                        }
                                        if (newBackup != null && newBackup.profile.uid != backup.profile.uid) {
                                            true
                                        } else false
                                    }
                                }
                                is KeyValueResult.Unavailable -> false
                            }
                        } else {
                            true
                        }
                    } else {
                        Log.w("AccountSessionManager", "releaseUsernameAlias rejected: user_$cleanOld owned by $ownerUidNorm != $curUidNorm")
                        false
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "releaseUsernameAlias error: ${e.message}")
            false
        }
    }

    internal val googleProfileCache = ConcurrentHashMap<String, PlayerRegistryEntry>()

    fun lookupGoogleProfileSync(googleId: String): PlayerRegistryEntry? {
        val gid = googleId.trim()
        if (gid.isBlank()) return null
        googleProfileCache[gid]?.let { return it }
        val topic = "bingo/v3/google_account/$gid"
        val retainedBytes = runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { EmqxTransport.wire.readRetained(topic) }
        }.getOrNull()
        if (retainedBytes != null && retainedBytes.isNotEmpty()) {
            val parsed = runCatching {
                json.decodeFromString<PlayerRegistryEntry>(String(retainedBytes, StandardCharsets.UTF_8))
            }.getOrNull()
            if (parsed != null && parsed.uid == "google_$gid") {
                googleProfileCache[gid] = parsed
                return parsed
            }
        }
        return null
    }

    /**
     * Claims and registers a unique username (Player ID) globally.
     * Links the username to the Google ID so the account can be restored on any device.
     */
    fun claimUsername(entry: PlayerRegistryEntry, googleId: String? = null) {
        val entryWithKey = if (entry.publicKey.isNullOrBlank()) {
            val existingKey = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(entry.uid)
            entry.copy(publicKey = existingKey)
        } else entry
        val clean = entryWithKey.username.trim().lowercase().removePrefix("@")
        if (clean.isNotBlank()) {
            registryCache[clean] = Pair(System.currentTimeMillis(), entryWithKey.copy(username = clean))
        }
        if (!googleId.isNullOrBlank()) {
            googleProfileCache[googleId] = entryWithKey
        }
        trackJob(scope.launch(Dispatchers.IO) {
            claimUsernameSync(entryWithKey, googleId)
        })
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
        trackJob(scope.launch(Dispatchers.IO) {
            val topic = "bingo/v3/avatar_update/$clean"
            val payloadObj = org.json.JSONObject()
                .put("username", clean)
                .put("avatar", avatarBase64 ?: "")
                .put("timestamp", System.currentTimeMillis())
            val bytes = payloadObj.toString().toByteArray(StandardCharsets.UTF_8)

            // Fast-path via persistent EMQX socket
            if (EmqxTransport.wire.isHealthy) {
                try {
                    EmqxTransport.wire.publish(topic, bytes, retained = true)
                    Log.i("AccountSessionManager", "Broadcast avatar update for @$clean via persistent EMQX (hasAvatar=${!avatarBase64.isNullOrBlank()})")
                    return@launch
                } catch (_: Exception) {}
            }

            try {
                val tempClientId = "ava_up_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                client.connect(options).waitForCompletion(2000L)
                val message = MqttMessage(bytes).apply {
                    qos = 1
                    isRetained = true
                }
                client.publish(topic, message).waitForCompletion(2000L)
                MqttClientShutdown.close(client)
                Log.i("AccountSessionManager", "Broadcast avatar update for @$clean (hasAvatar=${!avatarBase64.isNullOrBlank()})")
            } catch (e: Exception) {
                Log.w("AccountSessionManager", "Failed to broadcast avatar update for @$clean: ${e.message}")
            }
        })
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

    internal fun getSessionTopic(googleId: String): String =
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
                MqttClientShutdown.close(tempClient, quiesceMillis = 500L, disconnectMillis = 500L)
            } catch (_: Exception) {}

            return result
        } catch (e: Exception) {
            Log.w("AccountSessionManager", "Session check completed with exception: ${e.message}")
            try {
                MqttClientShutdown.close(tempClient, quiesceMillis = 500L, disconnectMillis = 500L)
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
                MqttClientShutdown.close(kickerClient)
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

        val boundCallback = onForcedLogout
        val targetGoogleId = googleId
        activeGoogleId = googleId
        currentDeviceId = localDeviceId
        onKickedCallback = onForcedLogout

        val topic = getSessionTopic(googleId)
        val clientId = "session_${localDeviceId.take(8)}_${UUID.randomUUID().toString().take(4)}"

        val wire = com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride
        if (wire != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    wireSubscription = wire.subscribe(topic) { bytes, _ ->
                        try {
                            val payload = String(bytes, StandardCharsets.UTF_8)
                            val packet = json.decodeFromString<AccountSessionPacket>(payload)
                            if (packet.googleId == targetGoogleId && packet.deviceId != localDeviceId) {
                                when (packet.type) {
                                    "FORCE_LOGOUT" -> {
                                        Log.i("AccountSessionManager", "Session claimed by device ${packet.deviceId} (${packet.deviceModel}). Forcing logout via wire.")
                                        val dispatcher = try {
                                            val m = Dispatchers.Main
                                            m.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
                                            m
                                        } catch (_: Throwable) {
                                            Dispatchers.Default
                                        }
                                        scope.launch(dispatcher) {
                                            if (activeGoogleId == targetGoogleId) {
                                                boundCallback.invoke()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            return
        }

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
                                    if (packet.googleId == targetGoogleId && packet.deviceId != localDeviceId) {
                                        when (packet.type) {
                                            "CHECK_ACTIVE" -> {
                                                val resp = AccountSessionPacket(
                                                    type = "SESSION_ACTIVE",
                                                    googleId = targetGoogleId,
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
                                                val dispatcher = try {
                                                    val m = Dispatchers.Main
                                                    m.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
                                                    m
                                                } catch (_: Throwable) {
                                                    Dispatchers.Default
                                                }
                                                scope.launch(dispatcher) {
                                                    if (activeGoogleId == targetGoogleId) {
                                                        boundCallback.invoke()
                                                    }
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
        wireSubscription?.close()
        wireSubscription = null
        val client = activeMqttClient
        activeMqttClient = null
        activeGoogleId = null
        currentDeviceId = null
        onKickedCallback = null

        trackJob(scope.launch(Dispatchers.IO) {
            try {
                if (client != null && client.isConnected) {
                    MqttClientShutdown.close(client)
                }
            } catch (_: Exception) {}
        })
    }

    // ─────────────────────────────────────────────────────────────
    // MQTT Secondary Fallbacks
    // ─────────────────────────────────────────────────────────────

    internal fun claimUsernameMqtt(entry: PlayerRegistryEntry, googleId: String?) {
        val entryWithKey = if (entry.publicKey.isNullOrBlank()) {
            val key = com.bingo.multiplayer.domain.network.AccountKeyStore.getPublicKeyBase64(entry.uid)
            entry.copy(publicKey = key)
        } else entry
        val clean = entryWithKey.username.trim().lowercase().removePrefix("@")
        val topic = "bingo/v3/registry/$clean"
        trackJob(scope.launch(Dispatchers.IO) {
            val payload = json.encodeToString(entryWithKey.copy(username = clean))
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)

            // 1. Fast-path via persistent EMQX socket
            if (EmqxTransport.wire.isHealthy) {
                try {
                    // Firebase owns durable profiles. MQTT only announces the live change.
                    EmqxTransport.wire.publish(topic, bytes, retained = false)
                    if (!googleId.isNullOrBlank()) {
                        val gTopic = "bingo/v3/google_account/$googleId"
                        EmqxTransport.wire.publish(gTopic, bytes, retained = false)
                    }
                    Log.i("AccountSessionManager", "Claimed username @$clean via persistent EMQX")
                    return@launch
                } catch (_: Exception) {}
            }

            // 2. Fallback via ephemeral client
            try {
                val tempClientId = "claim_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(brokerUrl, tempClientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    NetworkConfig.applyMqttOptions(this)
                }
                client.connect(options).waitForCompletion(2000L)
                val message = MqttMessage(bytes).apply {
                    qos = 1
                    isRetained = false
                }
                client.publish(topic, message).waitForCompletion(2000L)

                if (!googleId.isNullOrBlank()) {
                    val gTopic = "bingo/v3/google_account/$googleId"
                    client.publish(gTopic, message).waitForCompletion(2000L)
                }

                MqttClientShutdown.close(client)
            } catch (_: Exception) {}
        })
    }

    internal suspend fun lookupGoogleProfileMqtt(googleId: String, timeoutMs: Long): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val topic = "bingo/v3/google_account/$gid"

        // 1. Fast-path via persistent EMQX socket
        try {
            if (EmqxTransport.wire.isHealthy) {
                val retainedBytes = withTimeoutOrNull(timeoutMs) {
                    EmqxTransport.wire.readRetained(topic)
                }
                if (retainedBytes != null && retainedBytes.isNotEmpty()) {
                    val payload = String(retainedBytes, StandardCharsets.UTF_8)
                    return@withContext json.decodeFromString<PlayerRegistryEntry>(payload)
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback via ephemeral client
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
                MqttClientShutdown.close(tempClient, quiesceMillis = 500L, disconnectMillis = 500L)
            } catch (_: Exception) {}

            result
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun searchPlayerByUsernameMqtt(username: String, timeoutMs: Long): PlayerRegistryEntry? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null

        val topic = "bingo/v3/registry/$clean"

        // 1. Fast-path via persistent EMQX socket (<60ms)
        try {
            if (EmqxTransport.wire.isHealthy) {
                val retainedBytes = withTimeoutOrNull(timeoutMs) {
                    EmqxTransport.wire.readRetained(topic)
                }
                if (retainedBytes != null && retainedBytes.isNotEmpty()) {
                    val payload = String(retainedBytes, StandardCharsets.UTF_8)
                    val entry = json.decodeFromString<PlayerRegistryEntry>(payload)
                    return@withContext entry
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback via ephemeral client
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
                MqttClientShutdown.close(tempClient, quiesceMillis = 500L, disconnectMillis = 500L)
            } catch (_: Exception) {}

            result
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun fetchUserDataBackupMqtt(
        googleId: String,
        timeoutMs: Long
    ): com.bingo.multiplayer.domain.model.CloudUserDataBackup? = withContext(Dispatchers.IO) {
        val gid = googleId.trim()
        if (gid.isBlank()) return@withContext null

        val topic = "bingo/v3/cloud_backup/$gid"

        // 1. Fast-path via persistent EMQX socket
        try {
            if (EmqxTransport.wire.isHealthy) {
                val retainedBytes = withTimeoutOrNull(timeoutMs) {
                    EmqxTransport.wire.readRetained(topic)
                }
                if (retainedBytes != null && retainedBytes.isNotEmpty()) {
                    val payload = String(retainedBytes, StandardCharsets.UTF_8)
                    return@withContext json.decodeFromString<com.bingo.multiplayer.domain.model.CloudUserDataBackup>(payload)
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback via ephemeral client
        if (com.bingo.multiplayer.domain.network.EmqxTransport.wireOverride != null) {
            // Unit test isolation: do not construct ephemeral client when wire fixture is installed
            return@withContext null
        }

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
            result
        } catch (_: Exception) {
            null
        } finally {
            try {
                MqttClientShutdown.close(tempClient, quiesceMillis = 500L, disconnectMillis = 500L)
            } catch (_: Exception) {}
        }
    }

    @androidx.annotation.VisibleForTesting
    fun drainBackgroundJobs() {
        while (activeBackgroundJobs.isNotEmpty()) {
            val jobs = runCatching { activeBackgroundJobs.toTypedArray().toList() }.getOrDefault(emptyList())
            jobs.forEach { kotlinx.coroutines.runBlocking { runCatching { it.join() } } }
        }
    }

    companion object {
        private val binIdCache = ConcurrentHashMap<String, String>()
        private val registryCache = ConcurrentHashMap<String, Pair<Long, PlayerRegistryEntry>>()
        private val _registryCacheVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val registryCacheVersion: kotlinx.coroutines.flow.StateFlow<Long> = _registryCacheVersion

        @Synchronized
        fun notifyRegistryUpdated() {
            _registryCacheVersion.value += 1L
        }
        private val userMutexes = ConcurrentHashMap<String, Mutex>()
        private val backupMemoryCache = ConcurrentHashMap<String, com.bingo.multiplayer.domain.model.CloudUserDataBackup>()
        @androidx.annotation.VisibleForTesting
        var customCloudGateway: CloudStorageGateway? = null

        @androidx.annotation.VisibleForTesting
        fun drainBackgroundJobsForTests() {
            defaultInstance.drainBackgroundJobs()
        }

        val defaultInstance by lazy { AccountSessionManager() }

        suspend fun setKeyValue(key: String, value: String): Boolean = defaultInstance.setKeyValue(key, value)
        suspend fun getKeyValue(key: String): String? = defaultInstance.getKeyValue(key)

        fun getCachedPlayer(username: String): PlayerRegistryEntry? {
            val clean = username.trim().lowercase().removePrefix("@")
            return registryCache[clean]?.second
        }

        fun cachePlayer(entry: PlayerRegistryEntry) {
            val clean = entry.username.trim().lowercase().removePrefix("@")
            if (clean.isNotBlank()) {
                val previous = registryCache[clean]?.second
                if (previous?.uid == entry.uid && previous.adminRevision > entry.adminRevision) return
                registryCache[clean] = Pair(System.currentTimeMillis(), entry)
                if (entry.uid.isNotBlank()) {
                    registryCache[entry.uid.trim().lowercase()] = Pair(System.currentTimeMillis(), entry)
                }
                notifyRegistryUpdated()
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
