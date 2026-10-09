package com.bingo.multiplayer.domain.network

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("UpdateCheckWorker", "Starting periodic background update check...")
        return try {
            val updateInfo = AppUpdateManager.queryUpdateSilently(applicationContext)
            if (updateInfo != null && updateInfo.hasUpdate) {
                Log.i("UpdateCheckWorker", "Update available in background: ${updateInfo.latestVersionTag}")
                if (!AppLifecycleObserver.isAppInForeground.value) {
                    BingoNotificationManager.showUpdateNotification(applicationContext, updateInfo)
                }
            } else {
                Log.d("UpdateCheckWorker", "App is up to date.")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("UpdateCheckWorker", "Update check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "bingo_periodic_update_check"

        /**
         * Schedules periodic background update checks using WorkManager.
         * Runs every 6 hours with a 30-minute flex interval when network is connected.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.i("UpdateCheckWorker", "Scheduled periodic update checks via WorkManager (every 6 hours)")
        }
    }
}
