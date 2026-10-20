package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.NotificationApi
import com.bingo.multiplayer.domain.notifications.NotificationRuntime
import com.bingo.multiplayer.domain.notifications.NotificationAccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface RoomMetadataCommit {
    data class Applied(val room: OnlineRoomSession) : RoomMetadataCommit
    data class Conflict(val room: OnlineRoomSession?) : RoomMetadataCommit
    data class Rejected(val reason: String) : RoomMetadataCommit
    data class Unavailable(val error: Throwable) : RoomMetadataCommit
}

interface RoomMetadataTransport {
    suspend fun read(code: String): Result<OnlineRoomSession?>
    suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit
}

/** Shared production transition; independent clients retry transforms against the committed record. */
class RoomMetadataCoordinator(private val transport: RoomMetadataTransport,
    private val observe: (OnlineRoomSession) -> Unit = {}) {
    suspend fun mutate(code: String, expectedSeed: Long? = null, allowReset: Boolean = false,
        allowAlreadyClosed: Boolean = false,
        initial: OnlineRoomSession? = null,
        transform: (OnlineRoomSession) -> OnlineRoomSession?): MutationResult {
        var current = transport.read(code).getOrElse { return MutationResult.Unavailable(it) }
        repeat(4) {
            val base = current ?: initial ?: return MutationResult.Rejected("Room not found")
            current?.let(observe)
            if (expectedSeed != null && base.currentSeed != expectedSeed)
                return MutationResult.Rejected("Seed mismatch: current=${base.currentSeed}, expected=$expectedSeed")
            if (current?.let { it.status == "CLOSED" || it.isTerminalClosed } == true)
                return if (allowAlreadyClosed) MutationResult.Applied(base) else MutationResult.Rejected("Room is closed")
            val changed = transform(base) ?: return MutationResult.Rejected("Transform rejected")
            if (base.status == "PLAYING" && changed.status == "WAITING" && !allowReset)
                return MutationResult.Rejected("Cannot downgrade PLAYING to WAITING")
            val next = changed.copy(revision = (current?.revision ?: 0L) + 1L,
                metadataProtocolVersion = 3, isTerminalClosed = changed.status == "CLOSED")
            when (val result = transport.commit(code, current, next)) {
                is RoomMetadataCommit.Applied -> { observe(result.room); return MutationResult.Applied(result.room) }
                is RoomMetadataCommit.Conflict -> current = result.room
                is RoomMetadataCommit.Rejected -> return MutationResult.Rejected(result.reason)
                is RoomMetadataCommit.Unavailable -> return MutationResult.Unavailable(result.error)
            }
        }
        return MutationResult.Unavailable(IOException("Room changed repeatedly; retry the operation"))
    }
}

internal class HttpRoomMetadataTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val invalidateAuthorization: suspend () -> Unit = {},
    private val authorization: suspend () -> String
) : RoomMetadataTransport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Envelope(val payload: String? = null, val revision: Long = 0L,
        val closed: Boolean = false, val error: String? = null)
    @Serializable private data class Commit(val expectedRevision: Long, val expectedSeed: String?, val payload: String)

    private fun decode(envelope: Envelope): OnlineRoomSession? = envelope.payload?.let {
        json.decodeFromString<OnlineRoomSession>(it).let { room ->
            room.copy(revision = envelope.revision, metadataProtocolVersion = 3,
                status = if (envelope.closed) "CLOSED" else room.status,
                isTerminalClosed = envelope.closed || room.isTerminalClosed)
        }
    }

    private suspend fun request(code: String, body: Commit? = null): Pair<Int, Envelope> {
        val builder = Request.Builder().url("${baseUrl.trimEnd('/')}/v2/rooms/$code")
        if (body != null) builder.header("Authorization", "Bearer ${authorization()}")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching { response.use {
                        it.code to json.decodeFromString<Envelope>(it.body?.string().orEmpty())
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    override suspend fun read(code: String): Result<OnlineRoomSession?> = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(6000L) { request(code) }
                ?: return@withContext Result.failure(IOException("Room metadata read timed out"))
            if (result.first != 200) Result.failure(IOException(result.second.error ?: "Room metadata HTTP ${result.first}"))
            else Result.success(decode(result.second))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit = withContext(Dispatchers.IO) {
        try {
            val commit = Commit(base?.revision ?: 0L, base?.currentSeed?.toString(), json.encodeToString(next))
            var result = withTimeoutOrNull(8000L) { request(code, commit) }
                ?: return@withContext reconcileUncertainCommit(code, next, IOException("Room metadata commit timed out"))
            if (result.first == 401) {
                invalidateAuthorization()
                result = withTimeoutOrNull(8000L) { request(code, commit) }
                    ?: return@withContext RoomMetadataCommit.Unavailable(IOException("Room authorization refresh timed out"))
            }
            when (result.first) {
                200 -> decode(result.second)?.let { RoomMetadataCommit.Applied(it) }
                    ?: RoomMetadataCommit.Unavailable(IOException("Empty commit result"))
                409 -> RoomMetadataCommit.Conflict(decode(result.second))
                400, 403, 404, 410 -> RoomMetadataCommit.Rejected(result.second.error ?: "Room mutation rejected")
                else -> reconcileUncertainCommit(code, next, IOException(result.second.error ?: "Room metadata HTTP ${result.first}"))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reconcileUncertainCommit(code, next, e) }
    }

    private suspend fun reconcileUncertainCommit(code: String, next: OnlineRoomSession, error: Throwable): RoomMetadataCommit {
        val stored = read(code).getOrNull()
        val sameCommit = stored != null && stored.roomCode == next.roomCode && stored.currentSeed == next.currentSeed &&
            (stored == next.copy(revision = stored.revision) ||
                (stored.status == next.status && (!next.canonicalStartPacketJson.isNullOrBlank() &&
                    stored.canonicalStartPacketJson == next.canonicalStartPacketJson || next.status == "CLOSED")))
        return if (sameCommit) RoomMetadataCommit.Applied(stored!!) else RoomMetadataCommit.Unavailable(error)
    }
}

internal object RoomMetadataApi {
    @androidx.annotation.VisibleForTesting internal var transportOverride: RoomMetadataTransport? = null
    val enabled: Boolean get() = transportOverride != null || NotificationRuntime.context()?.let {
        NotificationRuntime.account(it) != null
    } == true
    private fun transport(): RoomMetadataTransport = transportOverride ?: HttpRoomMetadataTransport(
        OnlineRoomRegistry.client, NetworkConfig.FCM_RELAY_URL.removeSuffix("/send"), invalidateAuthorization = {
            NotificationRuntime.context()?.let { context ->
                NotificationRuntime.account(context)?.let { NotificationAccountStore.get(context).forgetSession(it) }
            }
        }) {
        val context = NotificationRuntime.context() ?: throw IOException("Application is unavailable")
        val account = NotificationRuntime.account(context) ?: throw IOException("Sign in before updating a room")
        NotificationApi.session(context, account)
    }
    suspend fun read(code: String): Result<OnlineRoomSession?> = transport().read(code)
    fun coordinator() = RoomMetadataCoordinator(transport(), OnlineRoomRegistry::rememberCanonicalRoom)
}
