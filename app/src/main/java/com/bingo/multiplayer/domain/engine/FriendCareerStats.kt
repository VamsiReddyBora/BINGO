package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry

/** Career totals accumulate, but the current streak may return to zero after a loss. */
object FriendCareerStats {
    fun merge(friend: Friend, entry: PlayerRegistryEntry): Friend {
        val username = friend.username.trim().lowercase().removePrefix("@")
        val entryUsername = entry.username.trim().lowercase().removePrefix("@")
        val sameUid = friend.uid.isNotBlank() && friend.uid == entry.uid
        if (!sameUid && (username.isBlank() || username != entryUsername)) return friend
        val wins = maxOf(friend.gamesWon, entry.gamesWon, 0)
        return friend.copy(
            gamesPlayed = maxOf(friend.gamesPlayed, entry.gamesPlayed, wins),
            gamesWon = wins,
            currentStreak = if (entry.gamesPlayed >= friend.gamesPlayed && entry.gamesWon >= friend.gamesWon)
                entry.currentStreak.coerceAtLeast(0) else friend.currentStreak,
            level = maxOf(friend.level, entry.level, 1),
            avatarUrl = entry.avatarUrl?.takeIf { it.isNotBlank() } ?: friend.avatarUrl
        )
    }
}
