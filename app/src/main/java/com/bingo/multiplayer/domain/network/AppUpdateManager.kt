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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionTag: String,
    val latestVersionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSize: Long,
    val versionCode: Long = 0,
    val sha256: String = "",
    val audience: String = "ALL",
    val accountUid: String? = null
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

    /** Public fallback feeds remain compatible with older installed apps. */
    val UPDATE_ENDPOINTS = listOf(
        "https://vamsireddybora.github.io/BINGO/version.json",
        "https://raw.githubusercontent.com/VamsiReddyBora/BINGO/gh-pages/version.json",
        "https://keyvalue.immanuel.co/api/KeyVal/GetValue/${NetworkConfig.KEYVALUE_APP_KEY}/latest_app_version",
        "https://cdn.jsdelivr.net/gh/VamsiReddyBora/BINGO@gh-pages/version.json",
        "https://api.github.com/repos/VamsiReddyBora/BINGO/releases/latest"
    )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountEpoch = AtomicLong()
    private val checkEpoch = AtomicLong()
    @Volatile private var observedUid: String? = null
    @Volatile private var checkJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var preparedInstall: Pair<File, AppUpdateInfo>? = null
    private var lastCheckAt = -60_000L
    private val updateHttp by lazy { NetworkConfig.httpClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build() }

    private val downloadHttp by lazy { updateHttp.newBuilder().callTimeout(2, TimeUnit.MINUTES).build() }

    @Synchronized private fun publish(context: Context, owner: String, epoch: Long, value: UpdateState, request: Long? = null) {
        if (uid(context) == owner && accountEpoch.get() == epoch && (request == null || request == checkEpoch.get()))
            _updateState.value = value
    }
    private fun uid(context: Context) = com.bingo.multiplayer.domain.notifications.NotificationRuntime.profile(context)?.uid.orEmpty()
    fun isOfferForCurrentAccount(context: Context, info: AppUpdateInfo) = ApkUpdateFeeds.allowed(info, uid(context))
    @Synchronized fun onAccountChanged(context: Context) {
        val current = uid(context)
        if (observedUid == current) return
        observedUid = current; accountEpoch.incrementAndGet(); checkEpoch.incrementAndGet()
        checkJob?.cancel(); downloadJob?.cancel(); pendingInstallApk = null
        preparedInstall?.first?.delete(); preparedInstall = null
        hasDismissedInSession = false; lastCheckAt = -60_000L
        _updateState.value = UpdateState.Idle
        BingoNotificationManager.cancelUpdateNotification(context)
    }
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
     * Retrieves current installed versionCode from PackageInfo.
     */
    fun getInstalledVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            51L
        }
    }

    /**
     * Parses version information from either simplified version.json format or full GitHub release JSON.
     */
    fun parseUpdatePayload(bodyStr: String, currentVersion: String, currentVersionCode: Long = 51L): AppUpdateInfo? {
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

            val remoteCode = json.optLong("versionCode", json.optLong("version_code", 0L))
            val hasNewer = isNewerVersion(cleanTag, currentVersion) || (remoteCode > 0L && remoteCode > currentVersionCode)
            return AppUpdateInfo(
                hasUpdate = hasNewer,
                latestVersionTag = if (tagName.startsWith("v")) tagName else "v$cleanTag",
                latestVersionName = cleanTag,
                downloadUrl = apkDownloadUrl,
                releaseNotes = releaseNotes,
                apkSize = apkSize,
                versionCode = remoteCode,
                sha256 = json.optString("sha256", "").lowercase()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse update payload: ${e.message}")
            return null
        }
    }

    private suspend fun resolve(context: Context, owner: String): AppUpdateInfo? {
        val feeds = ApkUpdateFeeds { endpoint ->
            withContext(Dispatchers.IO) {
                val url = if (endpoint.contains("github.io") && endpoint.endsWith(".json")) endpoint + "?check=" + System.currentTimeMillis() else endpoint
                updateHttp.newCall(Request.Builder().url(url).header("Accept", "application/json")
                    .header("Cache-Control", "no-cache").header("User-Agent", "BingoMultiplayer-App").build()).execute().use { response ->
                    if (response.code == 404) return@withContext null
                    require(response.isSuccessful) { "Update feed unavailable" }
                    val body = requireNotNull(response.body)
                    require(body.contentLength() <= 131072)
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val bytes = ByteArray(8192)
                        while (true) { val n = input.read(bytes); if (n < 0) break
                            require(output.size() + n <= 131072); output.write(bytes, 0, n) }
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }
        return feeds.resolve(owner, getInstalledVersionName(context), getInstalledVersionCode(context))
    }

    @Synchronized fun checkForUpdates(context: Context, manual: Boolean = false) {
        val app = context.applicationContext
        onAccountChanged(app)
        com.bingo.multiplayer.core.designsystem.NotificationPreferences.init(app)
        if (!manual && (!com.bingo.multiplayer.core.designsystem.NotificationPreferences.inAppNotificationsEnabled.value || hasDismissedInSession)) return
        if (_updateState.value is UpdateState.Downloading || _updateState.value is UpdateState.ReadyToInstall) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && now - lastCheckAt < 60_000L) return
        lastCheckAt = now
        val owner = uid(app); val epoch = accountEpoch.get(); val request = checkEpoch.incrementAndGet()
        checkJob?.cancel()
        _updateState.value = UpdateState.Checking
        checkJob = scope.launch {
            try {
                val info = resolve(app, owner)
                if (epoch != accountEpoch.get() || request != checkEpoch.get() || uid(app) != owner) return@launch
                publish(app, owner, epoch, when {
                    info == null -> if (manual) UpdateState.Error("Unable to check for updates. Please try again.") else UpdateState.Idle
                    info.hasUpdate -> UpdateState.UpdateAvailable(info)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }, request)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == accountEpoch.get() && request == checkEpoch.get() && uid(app) == owner)
                    publish(app, owner, epoch, if (manual) UpdateState.Error("Unable to check for updates. Please try again.") else UpdateState.Idle, request)
            }
        }
    }

    suspend fun queryUpdateSilently(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        onAccountChanged(context)
        val owner = uid(context); val epoch = accountEpoch.get()
        val info = resolve(context, owner)
        if (epoch == accountEpoch.get() && uid(context) == owner) info else null
    }

    private fun getUpdateDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Targeted downloads never substitute the public APK. Verify bytes, package, build and signer. */
    @Synchronized fun startDownload(context: Context, downloadUrl: String, onComplete: ((File) -> Unit)? = null) {
        val app = context.applicationContext
        onAccountChanged(app)
        val offer = (_updateState.value as? UpdateState.UpdateAvailable)?.info ?: return
        if (offer.downloadUrl != downloadUrl || !isOfferForCurrentAccount(app, offer)) return
        val owner = uid(app); val epoch = accountEpoch.get()
        _updateState.value = UpdateState.Downloading(0f, 0, offer.apkSize)
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val file = File(getUpdateDir(app), "Bingo_Update_" + UUID.randomUUID() + ".apk")
            var completed = false
            try {
                val urls = ApkUpdateFeeds.downloadCandidates(offer)
                var last: Exception? = null
                for (url in urls) {
                    try {
                        downloadHttp.newCall(Request.Builder().url(url).header("User-Agent", "BingoMultiplayer-App").build()).execute().use { response ->
                            require(response.isSuccessful) { "APK download unavailable" }
                            val body = requireNotNull(response.body)
                            require(body.contentLength() <= ApkUpdateFiles.MAX_BYTES)
                            var reportedAt = 0L
                            body.byteStream().use { input -> ApkUpdateFiles.copy(input, file, offer.apkSize, offer.sha256,
                                stillCurrent = { isActive && epoch == accountEpoch.get() && uid(app) == owner },
                                progress = { bytes ->
                                    val now = System.currentTimeMillis()
                                    if (now - reportedAt >= 100 || bytes == offer.apkSize) {
                                        if (epoch == accountEpoch.get() && uid(app) == owner)
                                            publish(app, owner, epoch, UpdateState.Downloading(if (offer.apkSize > 0) bytes.toFloat() / offer.apkSize else 0f, bytes, offer.apkSize))
                                        reportedAt = now
                                    }
                                }) }
                        }
                        validateArchive(app, file, offer)
                        synchronized(AppUpdateManager) {
                            check(isActive && epoch == accountEpoch.get() && uid(app) == owner)
                            preparedInstall?.first?.delete()
                            preparedInstall = file to offer
                            _updateState.value = UpdateState.ReadyToInstall(file)
                            completed = true
                        }
                        break
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { file.delete(); last = e }
                }
                if (!completed) throw (last ?: IllegalStateException("APK download failed"))
                runCatching { onComplete?.invoke(file) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == accountEpoch.get() && uid(app) == owner)
                    publish(app, owner, epoch, UpdateState.Error("The update could not be verified or downloaded. Please check again and retry."))
            } finally { if (!completed) file.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(context: Context, file: File, offer: AppUpdateInfo) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "Invalid APK" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && archive.versionName == offer.latestVersionName && code > getInstalledVersionCode(context))
        require(offer.versionCode == 0L || code == offer.versionCode)
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toList() }.toSet()
        require(signatures(installed).isNotEmpty() && signatures(installed) == signatures(archive)) { "APK signing key changed" }
        require(isOfferForCurrentAccount(context, offer)) { "Update account changed" }
    }

    /**
     * Launches the native Android Package Installer for the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val prepared = preparedInstall
            if (prepared == null || prepared.first != apkFile || !isOfferForCurrentAccount(context, prepared.second)) {
                pendingInstallApk = null
                _updateState.value = UpdateState.Error("Please check for updates again for this account.")
                return
            }
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
    @Synchronized fun dismiss(context: Context? = null) {
        hasDismissedInSession = true
        checkEpoch.incrementAndGet(); checkJob?.cancel()
        if (context != null) {
            BingoNotificationManager.cancelUpdateNotification(context)
        }
        _updateState.value = UpdateState.Idle
    }

}
