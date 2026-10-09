package com.bingo.multiplayer.domain.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionTag: String,
    val latestVersionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSize: Long
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    /**
     * Resilient multi-tier update endpoints checked in sequence:
     * 1. Primary: Fastly CDN edge on GitHub Pages (vamsireddybora.github.io) - zero rate limit, instant.
     * 2. Secondary: Raw GitHub blob endpoint.
     * 3. Tertiary: KeyValue cloud store (keyvalue.immanuel.co) - already verified connected for game accounts.
     * 4. Quaternary: jsDelivr multi-CDN network.
     * 5. Quinary: Official GitHub Releases API.
     */
    val UPDATE_ENDPOINTS = listOf(
        "https://vamsireddybora.github.io/BINGO/version.json",
        "https://raw.githubusercontent.com/VamsiReddyBora/BINGO/gh-pages/version.json",
        "https://keyvalue.immanuel.co/api/KeyVal/GetValue/2464j24f/latest_app_version",
        "https://cdn.jsdelivr.net/gh/VamsiReddyBora/BINGO@gh-pages/version.json",
        "https://api.github.com/repos/VamsiReddyBora/BINGO/releases/latest"
    )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var hasDismissedInSession = false
    @Volatile
    private var pendingInstallApk: File? = null

    /**
     * Compares semantic version strings (e.g. "1.3" vs "1.2", "1.2.1" vs "1.2.0").
     * Returns true if remote is strictly greater than local.
     */
    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.trim().removePrefix("v").split(".")
                .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
            val localParts = localVersion.trim().removePrefix("v").split(".")
                .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse versions: $remoteVersion vs $localVersion", e)
            return false
        }
    }

    /**
     * Retrieves current installed versionName from PackageInfo.
     */
    fun getInstalledVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.3"
        } catch (_: Exception) {
            "1.3"
        }
    }

    /**
     * Parses version information from either simplified version.json format or full GitHub release JSON.
     */
    fun parseUpdatePayload(bodyStr: String, currentVersion: String): AppUpdateInfo? {
        try {
            val json = JSONObject(bodyStr)
            val tagName = json.optString("tag_name", json.optString("tagName", ""))
            val rawVersion = json.optString("versionName", tagName.removePrefix("v")).trim()
            val cleanTag = if (rawVersion.isNotBlank()) rawVersion else tagName.removePrefix("v").trim()
            val releaseNotes = json.optString(
                "releaseNotes",
                json.optString("body", "Bug fixes and performance improvements.")
            )

            var apkDownloadUrl = json.optString("downloadUrl", json.optString("apk_url", ""))
            var apkSize = json.optLong("apkSize", json.optLong("apk_size", 0L))

            // Check if it's GitHub Release payload format (assets array)
            if (apkDownloadUrl.isBlank()) {
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }
            }

            if (cleanTag.isBlank()) return null
            if (apkDownloadUrl.isBlank()) {
                // Default to GitHub Pages CDN APK
                apkDownloadUrl = "https://vamsireddybora.github.io/BINGO/Bingo.apk"
            }

            val hasNewer = isNewerVersion(cleanTag, currentVersion)
            return AppUpdateInfo(
                hasUpdate = hasNewer,
                latestVersionTag = if (tagName.startsWith("v")) tagName else "v$cleanTag",
                latestVersionName = cleanTag,
                downloadUrl = apkDownloadUrl,
                releaseNotes = releaseNotes,
                apkSize = apkSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse update payload: ${e.message}")
            return null
        }
    }

    /**
     * Checks multiple redundant cloud sources for a newer version in the background.
     * @param manual If true, sets state to UpToDate if no update is found (for explicit user clicks in Settings).
     */
    fun checkForUpdates(context: Context, manual: Boolean = false) {
        if (!manual && hasDismissedInSession) {
            Log.d(TAG, "Update check skipped: user already dismissed update prompt in this session")
            return
        }

        scope.launch {
            if (_updateState.value is UpdateState.Downloading) {
                Log.d(TAG, "Download in progress, ignoring check request")
                return@launch
            }

            _updateState.value = UpdateState.Checking
            val currentVersion = getInstalledVersionName(context)

            var resolvedUpdateInfo: AppUpdateInfo? = null
            var lastErrorMessage: String? = null

            for (endpointUrl in UPDATE_ENDPOINTS) {
                try {
                    val request = Request.Builder()
                        .url(endpointUrl)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("User-Agent", "BingoMultiplayer-App/$currentVersion")
                        .get()
                        .build()

                    val response = NetworkConfig.httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty().trim()
                        response.close()

                        var cleanBody = body
                        // If response is a quoted JSON string from KeyValue, unwrap it
                        if (cleanBody.startsWith("\"") && cleanBody.endsWith("\"") && cleanBody.length >= 2) {
                            cleanBody = cleanBody.substring(1, cleanBody.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                        }

                        if (cleanBody.isNotBlank() && cleanBody != "null" && cleanBody != "\"\"") {
                            val info = parseUpdatePayload(cleanBody, currentVersion)
                            if (info != null) {
                                resolvedUpdateInfo = info
                                Log.i(TAG, "Successfully resolved update info from $endpointUrl: $info")
                                break
                            }
                        }
                    } else {
                        response.close()
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                    Log.w(TAG, "Failed to fetch update from $endpointUrl: ${e.message}")
                }
            }

            if (resolvedUpdateInfo != null) {
                if (resolvedUpdateInfo.hasUpdate) {
                    _updateState.value = UpdateState.UpdateAvailable(resolvedUpdateInfo)
                } else {
                    if (manual) {
                        _updateState.value = UpdateState.UpToDate
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } else {
                if (manual) {
                    val friendlyMsg = if (lastErrorMessage?.contains("resolve host", ignoreCase = true) == true) {
                        "No internet connection. Please check your network and try again."
                    } else {
                        "Unable to check for updates. Please check your connection and try again."
                    }
                    _updateState.value = UpdateState.Error(friendlyMsg)
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    /**
     * Silently queries update endpoints without mutating in-app dialog state.
     * Used by background WorkManager to check for updates and post system notifications.
     */
    suspend fun queryUpdateSilently(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val currentVersion = getInstalledVersionName(context)
        for (endpointUrl in UPDATE_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(endpointUrl)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "BingoMultiplayer-App/$currentVersion")
                    .get()
                    .build()

                val response = NetworkConfig.httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty().trim()
                    response.close()

                    var cleanBody = body
                    if (cleanBody.startsWith("\"") && cleanBody.endsWith("\"") && cleanBody.length >= 2) {
                        cleanBody = cleanBody.substring(1, cleanBody.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    }

                    if (cleanBody.isNotBlank() && cleanBody != "null" && cleanBody != "\"\"") {
                        val info = parseUpdatePayload(cleanBody, currentVersion)
                        if (info != null) {
                            return@withContext info
                        }
                    }
                } else {
                    response.close()
                }
            } catch (_: Exception) {}
        }
        null
    }

    private fun getUpdateDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Downloads APK from the given URL and tracks progress with automatic CDN fallback.
     */
    fun startDownload(context: Context, downloadUrl: String, onComplete: ((File) -> Unit)? = null) {
        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0f, 0L, 0L)
                val targetFile = File(getUpdateDir(context), "Bingo_Update.apk")
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                if (downloadUrl == "test://self_install") {
                    // Test Simulation: safely test the OTA installer pipeline using this build's APK (matching versionCode 34 and signature)
                    val sourceApk = File(context.applicationInfo.sourceDir)
                    val totalBytes = if (sourceApk.exists()) sourceApk.length() else 1L
                    var totalCopied = 0L

                    FileInputStream(sourceApk).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                totalCopied += read
                                val progress = (totalCopied.toFloat() / totalBytes).coerceIn(0f, 1f)
                                _updateState.value = UpdateState.Downloading(progress, totalCopied, totalBytes)
                                delay(25) // Smooth UI progress animation simulation
                            }
                            output.flush()
                        }
                    }

                    Log.i(TAG, "Test simulation prepared APK: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    _updateState.value = UpdateState.ReadyToInstall(targetFile)
                    onComplete?.invoke(targetFile)
                    return@launch
                }

                // Prepare redundant download candidate URLs in case one CDN node is blocked
                val downloadCandidates = mutableListOf(downloadUrl)
                if (!downloadUrl.contains("vamsireddybora.github.io")) {
                    downloadCandidates.add("https://vamsireddybora.github.io/BINGO/Bingo.apk")
                }
                if (!downloadUrl.contains("raw.githubusercontent.com")) {
                    downloadCandidates.add("https://raw.githubusercontent.com/VamsiReddyBora/BINGO/gh-pages/Bingo.apk")
                }

                var downloadSuccess = false
                var lastDownloadError: String? = null

                for (url in downloadCandidates) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "BingoMultiplayer-App")
                            .get()
                            .build()

                        val response = NetworkConfig.httpClient.newCall(request).execute()
                        if (!response.isSuccessful) {
                            response.close()
                            continue
                        }

                        val responseBody = response.body
                        if (responseBody == null) {
                            response.close()
                            continue
                        }

                        val totalBytes = responseBody.contentLength()

                        responseBody.byteStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(16384)
                                var bytesRead: Int
                                var totalDownloaded = 0L
                                var lastProgressReport = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalDownloaded += bytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressReport > 100 || totalDownloaded == totalBytes) {
                                        lastProgressReport = now
                                        val progress = if (totalBytes > 0) totalDownloaded.toFloat() / totalBytes else 0f
                                        _updateState.value = UpdateState.Downloading(progress, totalDownloaded, totalBytes)
                                    }
                                }
                                output.flush()
                            }
                        }
                        response.close()

                        if (targetFile.exists() && targetFile.length() > 0) {
                            downloadSuccess = true
                            Log.i(TAG, "APK download completed from $url: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                            break
                        }
                    } catch (e: Exception) {
                        lastDownloadError = e.message
                        Log.w(TAG, "Failed downloading from $url: ${e.message}")
                    }
                }

                if (downloadSuccess) {
                    _updateState.value = UpdateState.ReadyToInstall(targetFile)
                    onComplete?.invoke(targetFile)
                } else {
                    _updateState.value = UpdateState.Error(lastDownloadError ?: "Download failed. Please check internet connection.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "APK download failed: ${e.message}", e)
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    /**
     * Launches the native Android Package Installer for the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Log.e(TAG, "APK file does not exist or is empty: ${apkFile.absolutePath}")
                _updateState.value = UpdateState.Error("Downloaded file is missing or corrupt.")
                return
            }

            // On Android 8.0+ (Oreo, API 26+), check if app is permitted to request installs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Log.i(TAG, "Requesting UNKNOWN_APP_SOURCES permission")
                    pendingInstallApk = apkFile
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        if (context !is Activity) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }
            pendingInstallApk = null

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // Explicitly grant URI read permission to all matching package installer handlers
            val resolvedActivities = context.packageManager.queryIntentActivities(installIntent, 0)
            for (resolveInfo in resolvedActivities) {
                val pkgName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(pkgName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Log.i(TAG, "Launching Android PackageInstaller with URI: $apkUri (${apkFile.length()} bytes)")
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            _updateState.value = UpdateState.Error("Installer error: ${e.message}")
        }
    }

    /**
     * Checks if a pending APK installation was paused waiting for UNKNOWN_APP_SOURCES permission.
     * If the permission is now granted, automatically triggers the installer upon returning to the app.
     */
    fun resumePendingInstall(context: Context) {
        val pending = pendingInstallApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.packageManager.canRequestPackageInstalls()) {
                Log.i(TAG, "UNKNOWN_APP_SOURCES granted, resuming pending install for ${pending.name}")
                pendingInstallApk = null
                installApk(context, pending)
            }
        } else {
            pendingInstallApk = null
            installApk(context, pending)
        }
    }

    /**
     * Dismisses the update dialog or resets state back to Idle.
     * Records dismissal for this session so the user is not prompted again until restart.
     */
    fun dismiss(context: Context? = null) {
        hasDismissedInSession = true
        if (context != null) {
            BingoNotificationManager.cancelUpdateNotification(context)
        }
        _updateState.value = UpdateState.Idle
    }

    /**
     * Test verification trigger: Simulates an update flow by testing
     * the download UI and native package installer without triggering a downgrade rejection.
     */
    fun triggerVerificationTest(context: Context) {
        scope.launch {
            _updateState.value = UpdateState.Checking
            val appFile = File(context.applicationInfo.sourceDir)
            val size = if (appFile.exists()) appFile.length() else 5500000L
            val testInfo = AppUpdateInfo(
                hasUpdate = true,
                latestVersionTag = "v1.3",
                latestVersionName = "1.3 (Test Simulation)",
                downloadUrl = "test://self_install",
                releaseNotes = "• Test verification of In-App OTA Update Engine\n• Verifies Android PackageInstaller & FileProvider on this device\n• Re-installs/updates current build preserving all settings",
                apkSize = size
            )
            _updateState.value = UpdateState.UpdateAvailable(testInfo)
        }
    }
}
