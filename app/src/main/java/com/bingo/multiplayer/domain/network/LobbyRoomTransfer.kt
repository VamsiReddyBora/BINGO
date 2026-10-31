package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Admit first; the old lobby stays intact when admission or departure cannot be confirmed. */
object LobbyRoomTransfer {
    suspend fun departHostedLobby(code: String, sessionId: String, uid: String, approvedOtherPlayers: Set<String>,
        coordinator: RoomMetadataCoordinator = RoomMetadataApi.coordinator()): Boolean {
        val closed = coordinator.mutate(code) { previous ->
            if (previous.sessionId != sessionId || previous.hostId != uid || previous.status != "WAITING" ||
                previous.players.any { it.id != uid && it.lobbyReadyStatus != "LEFT_LOBBY" && it.id !in approvedOtherPlayers }) null
            else previous.copy(status = "CLOSED", isTerminalClosed = true, lastHeartbeat = System.currentTimeMillis())
        }
        return closed is MutationResult.Applied
    }
    suspend fun join(
        admit: suspend () -> RoomJoinResult,
        depart: suspend () -> Boolean,
        rollback: suspend () -> Unit,
        isCurrent: () -> Boolean
    ): RoomJoinResult {
        if (!isCurrent()) return RoomJoinResult.Error("The join request is no longer current.")
        val result = admit()
        if (result !is RoomJoinResult.Success) return result
        try {
            if (isCurrent() && depart()) return result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { withTimeoutOrNull(2500L) { rollback() } }
            throw cancelled
        } catch (_: Exception) { }
        withContext(NonCancellable) { withTimeoutOrNull(2500L) { runCatching { rollback() } } }
        return RoomJoinResult.Error("Couldn’t leave your current lobby safely. Please try again.")
    }
}
