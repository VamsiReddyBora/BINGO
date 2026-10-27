package com.bingo.multiplayer.domain.content

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import com.bingo.multiplayer.domain.network.NetworkConfig

class ContentUpdateRuntime private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ContentUpdateRuntime? = null
        fun get(context: Context): ContentUpdateRuntime = instance ?: synchronized(this) {
            instance ?: ContentUpdateRuntime(context.applicationContext).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storage = FileContentStorage(app)
    private val engine = ContentUpdateEngine(scope, storage, HttpContentSource(), NetworkConfig.APP_VERSION_CODE.toLong())
    val status = engine.status
    private val checks = mutableMapOf<String, Long>()
    fun enterHome(uid: String): AutoCloseable {
        val lease = engine.enterHome(uid)
        schedule(uid)
        check(uid)
        return lease
    }
    @Synchronized fun check(uid: String, manual: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && now - (checks[uid] ?: -60_000L) < 60_000L) return
        checks[uid] = now
        scope.launch { engine.refresh(uid) }
    }
    fun rollback(uid: String) { scope.launch { engine.rollback(uid) } }
    fun automatic(uid: String, value: Boolean) { scope.launch { if (engine.automatic(uid, value) && value) check(uid, true) } }
    fun backgroundFile(uid: String, status: ContentUpdateStatus): java.io.File? {
        if (status.uid != uid) return null
        val asset = status.cache.active?.pack?.takeIf { it.enabled }?.background ?: return null
        return storage.asset(uid, asset).takeIf { it.isFile }
    }
    private fun schedule(uid: String) {
        val work = PeriodicWorkRequestBuilder<ContentUpdateWorker>(6, TimeUnit.HOURS)
            .setInputData(workDataOf("uid" to uid))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        runCatching { WorkManager.getInstance(app).enqueueUniquePeriodicWork("bingo_content_updates_v1", ExistingPeriodicWorkPolicy.UPDATE, work) }
    }
    suspend fun fetchInBackground(uid: String) = engine.refresh(uid)
}

class ContentUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid")?.takeIf { it.isNotBlank() } ?: return Result.success()
        return try {
            val success = ContentUpdateRuntime.get(applicationContext).fetchInBackground(uid)
            if (success) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
