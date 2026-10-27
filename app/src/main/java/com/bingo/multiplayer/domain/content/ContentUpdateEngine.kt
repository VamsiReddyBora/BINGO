package com.bingo.multiplayer.domain.content

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ContentStorage {
    fun load(uid: String): ContentCache
    /** Atomic pointer commit; an interrupted write must retain the previous readable state. */
    fun save(uid: String, cache: ContentCache)
    fun asset(uid: String, asset: ContentAsset): File
    fun validateAsset(file: File, asset: ContentAsset): Boolean
    fun cleanup(uid: String, cache: ContentCache)
}
interface ContentSource {
    /** Null means a confirmed 404. Other failures preserve cached content. */
    suspend fun manifest(path: String): String?
    suspend fun download(asset: ContentAsset, destination: File, progress: (Long) -> Unit)
}

/** One implementation for foreground/background fetches; only a visible Home lease may activate. */
class ContentUpdateEngine(private val scope: CoroutineScope, private val storage: ContentStorage,
    private val source: ContentSource, private val build: Long,
    private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var homeUid: String? = null
    @Volatile private var boundUid: String? = null
    private val mutable = MutableStateFlow(ContentUpdateStatus())
    val status: StateFlow<ContentUpdateStatus> = mutable

    @Synchronized fun enterHome(uid: String): AutoCloseable {
        val token = generation.incrementAndGet()
        boundUid = uid; homeUid = uid
        if (mutable.value.uid != uid) mutable.value = ContentUpdateStatus(uid = uid)
        scope.launch {
            try { mutex.withLock { show(uid, ContentStage.IDLE); applyStaged(uid) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { show(uid, ContentStage.ERROR, "Content could not be applied; your saved version is unchanged.") }
        }
        return AutoCloseable { synchronized(this) { if (generation.get() == token) { generation.incrementAndGet(); homeUid = null } } }
    }
    private fun usable(value: VerifiedContent?, uid: String): VerifiedContent? = value?.takeIf {
        runCatching { ContentPacks.parse(ContentPacks.json.encodeToString(ContentPack.serializer(), it.pack), it.pack.audience, build) }.isSuccess &&
            it.pack.audience in setOf("ALL", ContentPacks.audience(uid)) &&
            (it.pack.background == null || storage.validateAsset(storage.asset(uid, it.pack.background), it.pack.background))
    }
    private fun loaded(uid: String): ContentCache {
        val cache = storage.load(uid)
        return cache.copy(active = usable(cache.active, uid), previous = usable(cache.previous, uid), staged = usable(cache.staged, uid))
    }
    private fun show(uid: String, stage: ContentStage, message: String = "", downloaded: Long = 0, total: Long = 0) {
        if (boundUid == uid) {
            val cache = if (stage == ContentStage.DOWNLOADING && mutable.value.uid == uid) mutable.value.cache
                else runCatching { loaded(uid) }.getOrDefault(ContentCache())
            mutable.value = ContentUpdateStatus(uid, stage, downloaded, total, message, cache)
        }
    }
    private fun checkRevision(cache: ContentCache, value: VerifiedContent) {
        val previous = cache.seen[value.pack.audience]
        require(previous == null || value.pack.revision >= previous.revision) { "An older content revision was ignored" }
        require(previous == null || value.pack.revision != previous.revision || value.digest == previous.digest) { "Content changed without a new revision" }
    }
    suspend fun refresh(uid: String): Boolean = mutex.withLock {
        try {
            if (uid.isBlank() || !storage.load(uid).automatic) return@withLock true
            show(uid, ContentStage.CHECKING)
            val target = ContentPacks.audience(uid)
            val direct = source.manifest(ContentPacks.path(target))
            var cache = storage.load(uid)
            val targeted = direct?.let { ContentPacks.parse(it, target, build) }
            if (targeted != null) {
                checkRevision(cache, targeted)
                // Keep the revocation/inheritance high-water mark even if the public fetch fails.
                if (targeted.pack.inheritGlobal) {
                    cache = cache.copy(seen = cache.seen + (target to ContentSeen(targeted.pack.revision, targeted.digest)))
                    storage.save(uid, cache)
                }
            }
            val global = if (targeted == null || targeted.pack.inheritGlobal) source.manifest(ContentPacks.path("ALL")) else null
            val verified = if (targeted != null && !targeted.pack.inheritGlobal) targeted
                else global?.let { ContentPacks.parse(it, "ALL", build) } ?: targeted
            if (verified == null) { show(uid, ContentStage.IDLE); return@withLock true }
            checkRevision(cache, verified)
            if (verified.key in cache.blocked) { show(uid, ContentStage.ROLLED_BACK, "This revision was rolled back on this device"); return@withLock true }
            // Removing a targeted file is not a revocation: a newer disabled target pack restores defaults.
            if (direct == null && cache.active?.pack?.audience == target) {
                show(uid, ContentStage.IDLE); return@withLock true
            }
            verified.pack.background?.let { asset ->
                val file = storage.asset(uid, asset)
                if (!storage.validateAsset(file, asset)) {
                    show(uid, ContentStage.DOWNLOADING, "Downloading app content", total = asset.bytes)
                    source.download(asset, file) { bytes -> show(uid, ContentStage.DOWNLOADING, "Downloading app content", bytes, asset.bytes) }
                    require(storage.validateAsset(file, asset)) { "Downloaded content did not verify" }
                }
            }
            if (cache.active?.key == verified.key && cache.staged == null) { show(uid, ContentStage.IDLE); return@withLock true }
            cache = cache.copy(staged = verified, seen = cache.seen + (verified.pack.audience to ContentSeen(verified.pack.revision, verified.digest)))
            storage.save(uid, cache)
            show(uid, ContentStage.READY, "Downloaded; waiting for Home")
            applyStaged(uid)
            true
        } catch (cancelled: CancellationException) {
            val cache = runCatching { storage.load(uid) }.getOrDefault(ContentCache())
            runCatching { storage.cleanup(uid, cache) }
            show(uid, if (cache.staged == null) ContentStage.IDLE else ContentStage.READY)
            throw cancelled
        }
        catch (_: Exception) {
            runCatching { storage.cleanup(uid, storage.load(uid)) }
            show(uid, ContentStage.ERROR, "Content update could not finish. Your saved version is unchanged.")
            false
        }
    }
    private suspend fun applyStaged(uid: String) {
        val token = generation.get()
        if (homeUid != uid) return
        val cache = loaded(uid)
        if (!cache.automatic) return
        val next = cache.staged ?: return
        show(uid, ContentStage.APPLYING, "Applying app content")
        pause(500)
        synchronized(this) {
            if (homeUid != uid || generation.get() != token) { show(uid, ContentStage.READY, "Ready for your next Home visit"); return }
            storage.save(uid, cache.copy(previous = cache.active, active = next, staged = null))
        }
        show(uid, ContentStage.APPLIED, "App content is up to date")
        runCatching { storage.cleanup(uid, storage.load(uid)) }
        pause(2_000)
        show(uid, ContentStage.IDLE)
    }
    suspend fun rollback(uid: String): Boolean = mutex.withLock {
        if (homeUid != uid) return@withLock false
        try {
            val cache = loaded(uid)
            val bad = cache.active ?: return@withLock false
            storage.save(uid, cache.copy(active = cache.previous, previous = null, staged = null,
                blocked = (cache.blocked + bad.key).toList().takeLast(32).toSet()))
            runCatching { storage.cleanup(uid, storage.load(uid)) }
            show(uid, ContentStage.ROLLED_BACK, "Previous content restored")
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            show(uid, ContentStage.ERROR, "Previous content could not be restored. Your saved version is unchanged.")
            false
        }
    }
    suspend fun automatic(uid: String, enabled: Boolean): Boolean = mutex.withLock {
        try {
            storage.save(uid, storage.load(uid).copy(automatic = enabled))
            show(uid, ContentStage.IDLE)
            if (enabled) applyStaged(uid)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            show(uid, ContentStage.ERROR, "The content-update preference could not be saved.")
            false
        }
    }
}
