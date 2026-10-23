package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Friend

/** Display order only: never changes a friend's stored order or invitation policy. */
internal object LobbyFriendOrdering {
    fun onlineFirst(friends: List<Friend>, presence: Map<String, PlayerPresence>, now: Long): List<Friend> =
        friends.sortedBy { friend ->
            when (FriendPlayAvailability.of(presence[friend.username.trim().lowercase().removePrefix("@")], now)) {
                FriendPlayAvailability.AVAILABLE -> 0
                FriendPlayAvailability.BUSY, FriendPlayAvailability.IN_LOBBY -> 1
                FriendPlayAvailability.UNKNOWN -> 2
            }
        } // Stable sort: ordinary heartbeat timestamps never reshuffle a status group.
}
