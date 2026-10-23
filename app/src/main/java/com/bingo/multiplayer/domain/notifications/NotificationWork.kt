package com.bingo.multiplayer.domain.notifications

import android.content.Context
import androidx.work.*
import com.bingo.multiplayer.domain.network.BingoFcmManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NotificationWork {
    private fun enqueue(context: Context, name: String, op: String, account: NotificationAccount?, extra: Data = Data.EMPTY, urgent: Boolean = false): Boolean = try {
        val data = Data.Builder().putAll(extra).putString("op", op)
            .putString("uid", account?.uid).putString("generation", account?.generation).build()
        val request = OneTimeWorkRequestBuilder<NotificationWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        if (account != null) request.addTag("notifications:${account.uid}:${account.generation}")
        if (urgent) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork("notification:$name", ExistingWorkPolicy.KEEP, request.build())
        true
    } catch (_: RuntimeException) { false }
    fun register(context: Context, account: NotificationAccount, revision: String = "token") = enqueue(context, "register:${account.generation}:$revision", "register", account)
    fun sync(context: Context, account: NotificationAccount, eventId: String = "poll") =
        enqueue(context, "sync:${account.generation}:$eventId", "sync", account,
            urgent = eventId != "poll" && android.os.Build.VERSION.SDK_INT >= 31)
    fun send(context: Context, account: NotificationAccount, payload: JSONObject): Boolean {
        val queued = enqueue(context,
        "send:${account.generation}:${payload.getString("toUsername")}:${payload.getString("id")}", "send", account,
        workDataOf("payload" to payload.toString(), "expiresAt" to payload.getLong("expiresAt")),
        urgent = payload.optString("kind") in setOf("GAME_INVITE", "FRIEND_ONLINE") && android.os.Build.VERSION.SDK_INT >= 31)
        NotificationDiagnostics.recordReference(context, payload.optString("id"), payload.optString("kind"),
            if (queued) "SEND_QUEUED" else "QUEUE_FAILED")
        return queued
    }
    fun acknowledge(context: Context, account: NotificationAccount, event: NotificationEvent) = enqueue(context,
        "ack:${account.generation}:${event.id}", "ack", account, workDataOf("eventId" to event.id))
    fun revoke(context: Context, registration: String, secret: String) = enqueue(context, "revoke:$registration", "revoke", null,
        workDataOf("registration" to registration, "secret" to secret))
    fun cancelAccount(context: Context, account: NotificationAccount) {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag("notifications:${account.uid}:${account.generation}") }
    }
}

class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val op = inputData.getString("op") ?: return Result.failure()
        val account = NotificationRuntime.account(applicationContext)
        if (op != "revoke" && (account == null || account.uid != inputData.getString("uid") || account.generation != inputData.getString("generation"))) return Result.success()
        val expiry = inputData.getLong("expiresAt", Long.MAX_VALUE)
        if (expiry <= System.currentTimeMillis()) return Result.success()
        try {
            when (op) {
                "revoke" -> NotificationApi.revoke(inputData.getString("registration") ?: return Result.failure(), inputData.getString("secret") ?: return Result.failure())
                "register" -> {
                    val token = BingoFcmManager.ensureCurrentToken(applicationContext)
                    NotificationApi.session(applicationContext, account!!)
                    val body = JSONObject().put("token", token.first).put("revision", token.second)
                        .put("onlineLifetimeMillis", NotificationKind.FRIEND_ONLINE.lifetimeMillis)
                    val registered = NotificationApi.call(applicationContext, account, "/v2/register", body)
                    if (registered.optBoolean("refreshToken") || token.first.isBlank()) {
                        if (NotificationRuntime.account(applicationContext) != account) return Result.success()
                        BingoFcmManager.ensureCurrentToken(applicationContext, forceRefresh = registered.optBoolean("refreshToken"))
                    }
                    if (NotificationRuntime.friendSnapshot(applicationContext, account) == null)
                        NotificationRuntime.syncFriends(applicationContext, NotificationRuntime.friends(applicationContext, account))
                    val friends = NotificationRuntime.friendSnapshot(applicationContext, account) ?: return Result.retry()
                    NotificationApi.call(applicationContext, account, "/v2/friends", JSONObject().put("uids", JSONArray(friends.first)).put("revision", friends.second))
                    NotificationRuntime.refresh(applicationContext, account)
                    if (NotificationRuntime.account(applicationContext) == account) NotificationDeliveryStatus.clear()
                    NotificationDiagnostics.record(applicationContext, null, "REGISTERED")
                }
                "send" -> {
                    val payloadObj = JSONObject(inputData.getString("payload")!!)
                    val result = NotificationApi.call(applicationContext, account!!, "/v2/events", payloadObj)
                    NotificationDiagnostics.recordReference(applicationContext, payloadObj.optString("id"), payloadObj.optString("kind"),
                        if (result.optString("status") == "suppressed") "SEND_SUPPRESSED" else "RELAY_QUEUED")
                }
                "ack" -> {
                    val id = inputData.getString("eventId").orEmpty()
                    NotificationApi.call(applicationContext, account!!, "/v2/ack", JSONObject().put("id", id))
                }
                "sync" -> NotificationRuntime.refresh(applicationContext, account!!)
                else -> return Result.failure()
            }
            return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: NotificationApiException) {
            NotificationDiagnostics.record(applicationContext, null, "${op.uppercase()}_FAILED", e.status)
            if (op == "register" && NotificationRuntime.account(applicationContext) == account) NotificationDeliveryStatus.failed(e)
            if ((e.retryable || e.status == 401 || e.status == 403) && runAttemptCount < 8 && expiry > System.currentTimeMillis()) return Result.retry()
            return Result.failure(workDataOf("reason" to e.message.orEmpty().take(180)))
        } catch (_: Exception) {
            if (op == "register" && NotificationRuntime.account(applicationContext) == account)
                NotificationDeliveryStatus.failed(NotificationApiException(503, "Notification registration is temporarily unavailable"))
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
