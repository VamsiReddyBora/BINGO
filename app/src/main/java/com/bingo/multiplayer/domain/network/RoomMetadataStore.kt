package com.bingo.multiplayer.domain.network


import com.bingo.multiplayer.domain.notifications.NotificationRuntime
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
        val readResult = if (initial != null) {
            val cached = (transport as? MqttRoomMetadataTransport)?.let { runCatching { it.readCandidate(code) }.getOrNull() }
            if (cached != null) Result.success(cached) else Result.success(null)
        } else {
            transport.read(code)
        }
        var current = readResult.getOrNull()
        if (current == null && readResult.isFailure) {
            val ex = readResult.exceptionOrNull()
            if (ex?.message != "Unauthenticated metadata signature") {
                return MutationResult.Unavailable(ex ?: IOException("Transport error"))
            }
        }
        val candidate: OnlineRoomSession? = if (current == null && initial == null && transport is MqttRoomMetadataTransport) {
            val mqtt = transport as MqttRoomMetadataTransport
            runCatching { mqtt.readCandidate(code) }.getOrNull()
        } else null
        repeat(4) {
            val base = current ?: candidate ?: initial ?: return MutationResult.Rejected("Room not found")
            current?.let(observe)
            if (expectedSeed != null && base.currentSeed != expectedSeed)
                return MutationResult.Rejected("Seed mismatch: current=${base.currentSeed}, expected=$expectedSeed")
            if (current?.let { it.status == "CLOSED" || it.isTerminalClosed } == true)
                return if (allowAlreadyClosed) MutationResult.Applied(base) else MutationResult.Rejected("Room is closed")
            val changed = transform(base) ?: return MutationResult.Rejected("Transform rejected")
            if (changed.revision < base.revision) return MutationResult.Rejected("Superseded by newer revision")
            if (base.status == "PLAYING" && changed.status == "WAITING" && !allowReset)
                return MutationResult.Rejected("Cannot downgrade status from PLAYING to WAITING")
            val next = changed.copy(revision = base.revision + 1L,
                metadataProtocolVersion = base.metadataProtocolVersion.takeIf { it > 0 } ?: 7, isTerminalClosed = changed.status == "CLOSED")
            when (val result = transport.commit(code, current ?: candidate, next)) {
                is RoomMetadataCommit.Applied -> { observe(result.room); return MutationResult.Applied(result.room) }
                is RoomMetadataCommit.Conflict -> current = result.room
                is RoomMetadataCommit.Rejected -> return MutationResult.Rejected(result.reason)
                is RoomMetadataCommit.Unavailable -> {
                    // The host may have applied the operation before its reply was lost.
                    // Reconcile only an actual newer matching snapshot, never a local roster copy.
                    val observed = transport.read(code).getOrNull()
                    if (observed != null && observed.sessionId == next.sessionId && observed.revision >= next.revision &&
                        observed.copy(revision = next.revision, lastHeartbeat = next.lastHeartbeat,
                            publisherKey = next.publisherKey, publisherSignature = next.publisherSignature) == next) {
                        observe(observed)
                        return MutationResult.Applied(observed)
                    }
                    return MutationResult.Unavailable(result.error)
                }
            }
        }
        return MutationResult.Unavailable(IOException("Room changed repeatedly; retry the operation"))
    }
}

internal object RoomMetadataApi {
    @androidx.annotation.VisibleForTesting internal var transportOverride: RoomMetadataTransport? = null
    // Unconditional: no account/device-dependent transport routing or legacy HTTP fallback.
    val enabled: Boolean get() = true

    @Volatile private var cachedWire: EmqxWire? = null
    @Volatile private var cachedTransport: MqttRoomMetadataTransport? = null

    @Synchronized
    private fun defaultMqttTransport(): MqttRoomMetadataTransport {
        val currentWire = EmqxTransport.wire
        val existing = cachedTransport
        if (existing != null && cachedWire === currentWire) {
            return existing
        }
        existing?.close()
        val created = MqttRoomMetadataTransport(currentWire, {
            val authUid = com.bingo.multiplayer.domain.repository.AuthRepository.activeInstance?.let { repo ->
                (repo.authState.value as? com.bingo.multiplayer.domain.model.AuthState.Authenticated)?.user?.uid
            }
            val ctx = NotificationRuntime.context()
            val notifUid = ctx?.let { NotificationRuntime.account(it)?.uid }.orEmpty()
            authUid?.takeIf { it.isNotBlank() } ?: notifUid
        }, OnlineRoomRegistry::rememberCanonicalRoom)
        cachedWire = currentWire
        cachedTransport = created
        return created
    }

    private fun initializedMqtt(): MqttRoomMetadataTransport? =
        (transportOverride as? MqttRoomMetadataTransport) ?: cachedTransport
    fun retireLocalWriter(exit: PendingMatchExit) {
        initializedMqtt()?.retireLocalWriter(exit.roomCode, exit.packet.seed, exit.accountUid)
    }
    suspend fun prepareExitDelivery(exit: PendingMatchExit) {
        initializedMqtt()?.let {
            it.retireLocalWriter(exit.roomCode, exit.packet.seed, exit.accountUid)
            it.drainRetiredWriter(exit.roomCode)
        }
    }
    private fun transport(): RoomMetadataTransport = transportOverride ?: defaultMqttTransport()
    suspend fun read(code: String): Result<OnlineRoomSession?> = transport().read(code)
    suspend fun readLive(code: String): Result<OnlineRoomSession?> {
        val current = transport()
        return if (current is MqttRoomMetadataTransport) current.readLive(code) else current.read(code)
    }
    suspend fun readCandidate(code: String): OnlineRoomSession? =
        runCatching { (transport() as? MqttRoomMetadataTransport)?.readCandidate(code) }.getOrNull()
    fun coordinator() = RoomMetadataCoordinator(transport(), OnlineRoomRegistry::rememberCanonicalRoom)

    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun resetForTests() {
        transportOverride = null
        cachedTransport?.close()
        cachedTransport = null
        cachedWire = null
    }
}
