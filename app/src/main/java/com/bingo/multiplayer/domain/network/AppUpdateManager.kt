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
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/VamsiReddyBora/BINGO/releases/latest"

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

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
     * Checks GitHub releases for a newer version in the background.
     * @param manual If true, sets state to UpToDate if no update is found (for explicit user clicks).
     */
    fun checkForUpdates(context: Context, manual: Boolean = false) {
        scope.launch {
            if (_updateState.value is UpdateState.Downloading) {
                Log.d(TAG, "Download in progress, ignoring check request")
                return@launch
            }

            _updateState.value = UpdateState.Checking
            val currentVersion = getInstalledVersionName(context)

            try {
                val request = Request.Builder()
                    .url(GITHUB_LATEST_RELEASE_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "BingoMultiplayer-App")
                    .get()
                    .build()

                val response = NetworkConfig.httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val msg = "Server responded with HTTP ${response.code}"
                    Log.w(TAG, "Check update failed: $msg")
                    if (manual) {
                        _updateState.value = UpdateState.Error(msg)
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                    response.close()
                    return@launch
                }

                val bodyStr = response.body?.string().orEmpty()
                response.close()

                if (bodyStr.isBlank()) {
                    if (manual) _updateState.value = UpdateState.Error("Empty release response")
                    else _updateState.value = UpdateState.Idle
                    return@launch
                }

                val releaseJson = JSONObject(bodyStr)
                val tagName = releaseJson.optString("tag_name", "")
                val cleanTag = tagName.removePrefix("v").trim()
                val releaseNotes = releaseJson.optString("body", "Bug fixes and performance improvements.")

                var apkDownloadUrl = ""
                var apkSize = 0L

                val assets = releaseJson.optJSONArray("assets")
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

                val hasNewer = isNewerVersion(cleanTag, currentVersion)
                Log.i(TAG, "Update check result: remote=$cleanTag, local=$currentVersion, hasUpdate=$hasNewer")

                if (hasNewer && apkDownloadUrl.isNotBlank()) {
                    val info = AppUpdateInfo(
                        hasUpdate = true,
                        latestVersionTag = tagName,
                        latestVersionName = cleanTag,
                        downloadUrl = apkDownloadUrl,
                        releaseNotes = releaseNotes,
                        apkSize = apkSize
                    )
                    _updateState.value = UpdateState.UpdateAvailable(info)
                } else {
                    if (manual) {
                        _updateState.value = UpdateState.UpToDate
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for updates: ${e.message}", e)
                if (manual) {
                    _updateState.value = UpdateState.Error(e.message ?: "Failed to check updates")
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    private fun getUpdateDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Downloads APK from the given URL and tracks progress.
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
                    // Avoids Android's INSTALL_FAILED_VERSION_DOWNGRADE error while verifying download UI, FileProvider, and PackageInstaller
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

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "BingoMultiplayer-App")
                    .get()
                    .build()

                val response = NetworkConfig.httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _updateState.value = UpdateState.Error("Download failed with code ${response.code}")
                    response.close()
                    return@launch
                }

                val responseBody = response.body
                if (responseBody == null) {
                    _updateState.value = UpdateState.Error("Empty response body from download")
                    response.close()
                    return@launch
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

                Log.i(TAG, "APK download completed: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                _updateState.value = UpdateState.ReadyToInstall(targetFile)
                onComplete?.invoke(targetFile)
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
     * Dismisses the update dialog or resets state back to Idle.
     */
    fun dismiss() {
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
