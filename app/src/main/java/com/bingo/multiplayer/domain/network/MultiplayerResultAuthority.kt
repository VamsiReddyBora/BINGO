package com.bingo.multiplayer.domain.network

/** Optimistic marks stay immediate; only the original controller commits an online group result. */
object MultiplayerResultAuthority {
    fun mayFinalize(context: MultiplayerGameplayContext, confirmation: RoomMessagePacket? = null): Boolean {
        if (!context.requiresHostResultConfirmation || context.localId == context.hostId) return true
        return confirmation != null && MultiplayerGameplayReducer.belongsToMatch(confirmation, context) &&
            MultiplayerGameplayReducer.sender(confirmation) == context.hostId &&
            confirmation.type == "GAME_SYNC"
    }
}
