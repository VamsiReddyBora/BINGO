package com.bingo.multiplayer.domain.network

/** Shared validation for the live peer flow, including lifecycle and chat controls. */
object SimplifiedMatchPacketGate {
    val types = MultiplayerGameplayReducer.gameplayTypes + setOf("GAME_SYNC", "GAME_SYNC_ACK", "REJOIN_GAME", "SYNC_REQUEST",
        "GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND", "PLAYER_PRESENCE", "PLAYER_DISCONNECTED", "LEAVE", "HOST_LEFT",
        "CHAT_MESSAGE", "CHAT_TYPING", "EMOTE", "CHAT_PHRASE", "BOARD_READY")

    fun accepts(packet: RoomMessagePacket, seed: Long, room: String, participantIds: Set<String>): Boolean =
        seed != 0L && packet.seed == seed && (packet.matchId.isBlank() || packet.matchId == seed.toString()) &&
            (packet.roomCode.isBlank() || packet.roomCode == room) &&
            MultiplayerGameplayReducer.sender(packet) in participantIds && packet.playerId in participantIds &&
            (!packet.transportOnly || packet.type == "PLAYER_DISCONNECTED")

    fun acceptsWill(packet: RoomMessagePacket, room: String, participantIds: Set<String>): Boolean =
        packet.type == "PLAYER_DISCONNECTED" && packet.transportOnly && packet.roomCode == room &&
            packet.playerId in participantIds && packet.connectionGeneration.isNotBlank() && packet.senderInstanceId.isNotBlank()
}
