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
        val operation = WorkManager.getInstance(context).enqueueUniqueWork("notification:$name", ExistingWorkPolicy.KEEP, request.build())
        operation.result.addListener({
            try { operation.result.get() }
            catch (_: Exception) {
                if (NotificationRuntime.account(context) == account) {
                    NotificationDiagnostics.failure(context, extra.getString("eventId"), extra.getString("kind"), "QUEUE_FAILED",
                        NotificationApiException(503, "Notification work could not be saved", NotificationPhase.ENQUEUE))
                    if (op == "send") NotificationDeliveryStatus.pushUnavailable()
                    else if (op == "register") NotificationDeliveryStatus.failed(NotificationApiException(503, "Notification registration could not be queued", NotificationPhase.ENQUEUE))
                }
            }
        }, java.util.concurrent.Executor { it.run() })
        true
    } catch (_: RuntimeException) { false }
    fun register(context: Context, account: NotificationAccount, revision: String = "token") = enqueue(context, "register:${account.generation}:$revision", "register", account)
    fun sync(context: Context, account: NotificationAccount, eventId: String = "poll") =
        enqueue(context, "sync:${account.generation}:$eventId", "sync", account,
            urgent = eventId != "poll" && android.os.Build.VERSION.SDK_INT >= 31)
    fun send(context: Context, account: NotificationAccount, payload: JSONObject): Boolean {
        val queued = enqueue(context,
        "send:${account.generation}:${payload.getString("toUsername")}:${payload.getString("id")}", "send", account,
        workDataOf("payload" to payload.toString(), "expiresAt" to payload.getLong("expiresAt"), "eventId" to payload.optString("id"), "kind" to payload.optString("kind")),
        urgent = payload.optString("kind") in setOf("GAME_INVITE", "FRIEND_ONLINE") && android.os.Build.VERSION.SDK_INT >= 31)
        NotificationDiagnostics.recordReference(context, payload.optString("id"), payload.optString("kind"),
            if (queued) "SEND_QUEUED" else "QUEUE_FAILED")
        return queued
    }
    fun acknowledge(context: Context, account: NotificationAccount, event: NotificationEvent) = enqueue(context,
        "ack:${account.generation}:${event.id}", "ack", account, workDataOf("eventId" to event.id, "kind" to event.kind.name))
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
                    val registrationAccount = account ?: return Result.success()
                    NotificationAuthRecovery.registerToken(
                        isCurrent = { NotificationRuntime.account(applicationContext) == registrationAccount },
                        fetch = { force -> BingoFcmManager.ensureCurrentToken(applicationContext, forceRefresh = force) },
                        post = { token ->
                            val body = JSONObject().put("token", token.first).put("revision", token.second)
                                .put("onlineLifetimeMillis", NotificationKind.FRIEND_ONLINE.lifetimeMillis)
                            !NotificationApi.call(applicationContext, registrationAccount, "/v2/register", body).optBoolean("refreshToken")
                        })
                    NotificationDiagnostics.record(applicationContext, null, "TOKEN_REGISTERED")
                    if (NotificationRuntime.friendSnapshot(applicationContext, registrationAccount) == null)
                        NotificationRuntime.syncFriends(applicationContext, NotificationRuntime.friends(applicationContext, registrationAccount))
                    val friends = NotificationRuntime.friendSnapshot(applicationContext, registrationAccount) ?: return Result.retry()
                    NotificationApi.call(applicationContext, registrationAccount, "/v2/friends", JSONObject().put("uids", JSONArray(friends.first)).put("revision", friends.second))
                    NotificationRuntime.refresh(applicationContext, registrationAccount)
                    if (NotificationRuntime.account(applicationContext) == registrationAccount) NotificationDeliveryStatus.clear()
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
            NotificationDiagnostics.failure(applicationContext, inputData.getString("eventId"), inputData.getString("kind"), "${op.uppercase()}_FAILED", e)
            if (e.failure == NotificationFailure.ACCOUNT_CHANGED) return Result.success()
            if (NotificationRuntime.account(applicationContext) == account) {
                if (op == "register" || e.needsGoogleApproval || e.failure == NotificationFailure.GOOGLE_CONFIGURATION) NotificationDeliveryStatus.failed(e)
                else if (op == "send" && inputData.getString("kind") == NotificationKind.GAME_INVITE.name) NotificationDeliveryStatus.pushUnavailable()
            }
            if (NotificationAuthRecovery.shouldRetry(e, runAttemptCount, expiry, System.currentTimeMillis())) return Result.retry()
            return Result.failure(workDataOf("reason" to e.message.orEmpty().take(180)))
        } catch (_: Exception) {
            NotificationDiagnostics.recordReference(applicationContext, inputData.getString("eventId"), inputData.getString("kind"), "${op.uppercase()}_FAILED", 503)
            if (op == "register" && NotificationRuntime.account(applicationContext) == account)
                NotificationDeliveryStatus.failed(NotificationApiException(503, "Notification registration is temporarily unavailable"))
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
