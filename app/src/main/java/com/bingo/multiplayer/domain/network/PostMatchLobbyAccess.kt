package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PostMatchLobbyStatus { CHECKING, AVAILABLE, WAITING, CLOSED }

/** Room membership is independent of the host's app-wide online status. A temporary failure
 * never closes a room, and an explicit departure cannot be undone by an old read response. */
internal class PostMatchLobbyAccess(private val roomCode: String, private val completedSeed: Long) {
    private val _status = MutableStateFlow(PostMatchLobbyStatus.CHECKING)
    val status = _status.asStateFlow()
    private val refreshLock = Mutex()

    @Synchronized fun acceptDeparture(packet: RoomMessagePacket, hostId: String, sessionId: String): Boolean {
        if (hostId.isBlank() || packet.seed != 0L || packet.transportOnly ||
            packet.type !in setOf("HOST_LEFT", "LEAVE", "LOBBY_EXPIRED") ||
            packet.playerId != hostId || packet.senderPlayerId.ifBlank { packet.playerId } != hostId ||
            (packet.roomCode.isNotBlank() && packet.roomCode != roomCode) ||
            (sessionId.isNotBlank() && packet.lobbySessionId.isNotBlank() && packet.lobbySessionId != sessionId)) return false
        _status.value = PostMatchLobbyStatus.CLOSED
        return true
    }

    suspend fun refresh(hostId: String, sessionId: String, hostUnavailable: () -> Boolean,
        readLive: suspend (String) -> Result<OnlineRoomSession?>): PostMatchLobbyStatus {
        if (!refreshLock.tryLock()) return status.value
        try {
            synchronized(this) {
                if (status.value == PostMatchLobbyStatus.CLOSED) return status.value
                _status.value = PostMatchLobbyStatus.CHECKING
            }
            val room = try { withTimeoutOrNull(2500L) { readLive(roomCode).getOrNull() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            return synchronized(this) {
                if (status.value != PostMatchLobbyStatus.CLOSED) {
                    _status.value = when {
                        room == null -> PostMatchLobbyStatus.WAITING
                        room.roomCode != roomCode || room.status == "CLOSED" || room.isTerminalClosed ||
                            (sessionId.isNotBlank() && room.sessionId != sessionId) || room.hostId != hostId ||
                            (room.status == "PLAYING" && room.currentSeed != completedSeed) ||
                            room.players.none { it.id == hostId && it.lobbyReadyStatus != "LEFT_LOBBY" } -> PostMatchLobbyStatus.CLOSED
                        hostUnavailable() -> PostMatchLobbyStatus.WAITING
                        else -> PostMatchLobbyStatus.AVAILABLE
                    }
                }
                status.value
            }
        } finally {
            synchronized(this) {
                if (status.value == PostMatchLobbyStatus.CHECKING) _status.value = PostMatchLobbyStatus.WAITING
            }
            refreshLock.unlock()
        }
    }

    suspend fun enterIfAvailable(hostId: String, sessionId: String, hostUnavailable: () -> Boolean,
        readLive: suspend (String) -> Result<OnlineRoomSession?>, enter: () -> Unit): Boolean {
        refresh(hostId, sessionId, hostUnavailable, readLive)
        return synchronized(this) {
            if (status.value != PostMatchLobbyStatus.AVAILABLE || hostUnavailable()) false
            else { enter(); true }
        }
    }
}
