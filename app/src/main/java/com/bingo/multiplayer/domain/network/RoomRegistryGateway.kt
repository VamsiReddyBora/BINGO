package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player

/** Existing cloud lobby operations, separate from MQTT so transport tests cannot touch live rooms. */
interface RoomRegistryGateway {
    suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession?
    suspend fun ready(code: String, uid: String, status: String, version: Long)
    suspend fun remove(code: String, uid: String)
    suspend fun close(code: String)
}

internal object CloudRoomRegistryGateway : RoomRegistryGateway {
    override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>) =
        OnlineRoomRegistry.syncRoom(code, player, known, banned)
    override suspend fun ready(code: String, uid: String, status: String, version: Long) {
        OnlineRoomRegistry.updatePlayerReadyStatus(code, uid, status, version)
    }
    override suspend fun remove(code: String, uid: String) { OnlineRoomRegistry.removePlayerFromRoom(code, uid) }
    override suspend fun close(code: String) { OnlineRoomRegistry.closeRoom(code) }
}
