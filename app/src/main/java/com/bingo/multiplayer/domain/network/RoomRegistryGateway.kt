package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player

/** Lobby operations backed by the configured EMQX registry; controlled gateways isolate native tests. */
interface RoomRegistryGateway {
    suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession?
    suspend fun ready(code: String, uid: String, status: String, version: Long)
    suspend fun remove(code: String, uid: String)
    suspend fun close(code: String)
}

/** Historical name retained for source compatibility. It performs no HTTP or cloud commits. */
internal object CloudRoomRegistryGateway : RoomRegistryGateway {
    override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>) =
        OnlineRoomRegistry.syncRoom(code, player, known, banned)
    override suspend fun ready(code: String, uid: String, status: String, version: Long) {
        OnlineRoomRegistry.updatePlayerReadyStatus(code, uid, status, version)
    }
    override suspend fun remove(code: String, uid: String) { OnlineRoomRegistry.removePlayerFromRoom(code, uid) }
    override suspend fun close(code: String) { OnlineRoomRegistry.closeRoom(code) }
}
