package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.content.ContentPacks
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/** The public APK feed remains backward compatible. Only this separate feed may target an account. */
class ApkUpdateFeeds(private val fetch: suspend (String) -> String?) {
    companion object {
        const val BASE = "https://vamsireddybora.github.io/BINGO/"
        fun targetUrl(uid: String) = BASE + "app-updates/users/" + ContentPacks.audience(uid) + ".json"
        fun allowed(info: AppUpdateInfo, uid: String) =
            (info.accountUid == null || info.accountUid == uid) && (info.audience == "ALL" ||
                uid.isNotBlank() && info.audience == ContentPacks.audience(uid))
        fun downloadCandidates(info: AppUpdateInfo): List<String> = if (info.audience != "ALL") listOf(info.downloadUrl)
            else (listOf(info.downloadUrl, BASE + "Bingo.apk",
                "https://raw.githubusercontent.com/VamsiReddyBora/BINGO/gh-pages/Bingo.apk")).distinct()
    }
    fun parseTarget(body: String, uid: String, version: String, code: Long): AppUpdateInfo? {
        require(uid.isNotBlank() && body.toByteArray().size <= 32768)
        val j = Json.parseToJsonElement(body).jsonObject
        require(j.keys.all { it in setOf("schema", "audience", "revision", "enabled", "release") })
        val audience = ContentPacks.audience(uid)
        require(j["schema"]?.jsonPrimitive?.intOrNull == 1 && j["audience"]?.jsonPrimitive?.content == audience)
        require((j["revision"]?.jsonPrimitive?.longOrNull ?: 0) in 1..9_007_199_254_740_991L)
        val enabled = requireNotNull(j["enabled"]?.jsonPrimitive?.booleanOrNull)
        if (!enabled) { require(j["release"] == JsonNull); return null }
        val info = requireNotNull(AppUpdateManager.parseUpdatePayload(j.getValue("release").toString(), version, code))
        require(info.versionCode > 0 && info.apkSize in 1..ApkUpdateFiles.MAX_BYTES && info.sha256.matches(Regex("[a-f0-9]{64}")))
        require(info.downloadUrl.matches(Regex(Regex.escape(BASE) + "app-updates/apks/Bingo-[A-Za-z0-9.-]+-build${info.versionCode}-${info.sha256}\\.apk")))
        return info.copy(hasUpdate = info.versionCode > code, audience = audience, accountUid = uid)
    }
    suspend fun resolve(uid: String, version: String, code: Long): AppUpdateInfo? = coroutineScope {
        val target = async {
            if (uid.isBlank()) null else try { fetch(targetUrl(uid))?.let { parseTarget(it, uid, version, code) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        val global = async {
            var resolved: AppUpdateInfo? = null
            for (url in AppUpdateManager.UPDATE_ENDPOINTS) {
                try {
                    var body = fetch(url)?.trim() ?: continue
                    if (body.startsWith('"')) body = Json.parseToJsonElement(body).jsonPrimitive.content
                    val parsed = AppUpdateManager.parseUpdatePayload(body, version, code) ?: continue
                    resolved = parsed.copy(hasUpdate = if (parsed.versionCode > 0) parsed.versionCode > code else parsed.hasUpdate,
                        accountUid = uid)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
            }
            resolved
        }
        choose(global.await(), target.await())
    }
    /** Known build numbers prevent downgrade/reoffer; a newer public build supersedes a test APK. */
    fun choose(global: AppUpdateInfo?, target: AppUpdateInfo?): AppUpdateInfo? = when {
        target?.hasUpdate != true -> global
        global?.hasUpdate != true -> target
        global.versionCode > 0 -> if (global.versionCode >= target.versionCode) global else target
        AppUpdateManager.isNewerVersion(global.latestVersionName, target.latestVersionName) -> global
        else -> target
    }
}

/** Shared by real APK downloads and JVM tests; no partial bytes are accepted as an APK. */
object ApkUpdateFiles {
    const val MAX_BYTES = 12 * 1024 * 1024L
    fun copy(input: InputStream, destination: File, expectedSize: Long, sha256: String,
        stillCurrent: () -> Boolean = { true }, progress: (Long) -> Unit = {}) {
        require(expectedSize in 0..MAX_BYTES && (sha256.isBlank() || sha256.matches(Regex("[a-f0-9]{64}"))))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            destination.outputStream().use { output ->
                val bytes = ByteArray(16384)
                while (true) {
                    check(stillCurrent()) { "Update account changed" }
                    val n = input.read(bytes); if (n < 0) break
                    count += n
                    require(count <= MAX_BYTES && (expectedSize == 0L || count <= expectedSize)) { "Unexpected APK size" }
                    output.write(bytes, 0, n); digest.update(bytes, 0, n); progress(count)
                }
                output.fd.sync()
            }
            require(count > 0 && (expectedSize == 0L || count == expectedSize)) { "Incomplete APK download" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha256.isBlank() || actual == sha256) { "APK checksum failed" }
            check(stillCurrent()) { "Update account changed" }
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
