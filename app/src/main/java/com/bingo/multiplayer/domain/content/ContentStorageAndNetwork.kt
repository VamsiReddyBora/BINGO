package com.bingo.multiplayer.domain.content

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class FileContentStorage(context: Context) : ContentStorage {
    private val root = File(context.filesDir, "ota-content-v1").apply { mkdirs() }
    private fun directory(uid: String) = File(root, ContentPacks.audience(uid)).apply { mkdirs() }
    private fun pointer(uid: String) = AtomicFile(File(directory(uid), "state.json"))
    override fun load(uid: String): ContentCache = runCatching {
        pointer(uid).openRead().bufferedReader().use { ContentPacks.json.decodeFromString<ContentCache>(it.readText()) }
    }.getOrDefault(ContentCache())
    override fun save(uid: String, cache: ContentCache) {
        val file = pointer(uid)
        val output = file.startWrite()
        try {
            output.write(ContentPacks.json.encodeToString(ContentCache.serializer(), cache).toByteArray())
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
    override fun asset(uid: String, asset: ContentAsset) = File(directory(uid), asset.url.substringAfterLast('/'))
    override fun validateAsset(file: File, asset: ContentAsset): Boolean = ContentFiles.verified(file, asset) && runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.outWidth in 1..2048 && options.outHeight in 1..2048
    }.getOrDefault(false)
    override fun cleanup(uid: String, cache: ContentCache) {
        val keep = listOfNotNull(cache.active, cache.previous, cache.staged).mapNotNull { it.pack.background?.url?.substringAfterLast('/') }.toSet()
        directory(uid).listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)(\\.part)?")) && it.name !in keep }?.forEach { it.delete() }
    }
}

object ContentFiles {
    fun verified(file: File, asset: ContentAsset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(8192); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.sha256
    }.getOrDefault(false)
}

/** Dedicated bounded HTTPS reads; no executable files, ZIP extraction or per-move traffic. */
class HttpContentSource(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) : ContentSource {
    override suspend fun manifest(path: String): String? = withContext(Dispatchers.IO) {
        require(path == "index.json" || path.matches(Regex("users/[a-f0-9]{64}\\.json")))
        val request = Request.Builder().url(ContentPacks.BASE + path + "?check=" + System.currentTimeMillis())
            .header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            require(response.isSuccessful) { "Content feed is unavailable" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= ContentPacks.MAX_MANIFEST_BYTES)
            body.byteStream().use { input ->
                val bytes = input.readBytesBounded(ContentPacks.MAX_MANIFEST_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        }
    }
    override suspend fun download(asset: ContentAsset, destination: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val temporary = File(destination.path + ".part")
        try {
            http.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
                require(response.isSuccessful)
                val body = requireNotNull(response.body)
                require(body.contentLength() < 0 || body.contentLength() == asset.bytes)
                require(response.header("Content-Type")?.substringBefore(';') in setOf("image/png", "image/jpeg", "image/webp", "application/octet-stream"))
                var count = 0L; var shownAt = 0L
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            count += n; require(count <= asset.bytes && count <= ContentPacks.MAX_ASSET_BYTES)
                            output.write(buffer, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - shownAt >= 100 || count == asset.bytes) { progress(count); shownAt = now }
                        }
                    }
                    output.fd.sync()
                }
                require(ContentFiles.verified(temporary, asset)) { "Content checksum failed" }
                require(temporary.renameTo(destination)) { "Content could not be saved" }
            }
        } finally { temporary.delete() }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit); output.write(buffer, 0, n) }
        return output.toByteArray()
    }
}
