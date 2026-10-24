package com.bingo.multiplayer.domain.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * In-app friend with online/offline status.
 */
@Keep
@Serializable
data class Friend(
    val uid: String,
    val username: String = "",
    val displayName: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val lastSeenTimestamp: Long = 0L,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val level: Int = 1,
    val generation: String = "",
    val addedAt: Long = 0L,
    val adminRevision: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Friend) return false
        return uid == other.uid &&
                username == other.username &&
                displayName == other.displayName &&
                avatarUrl == other.avatarUrl &&
                isOnline == other.isOnline &&
                lastSeenTimestamp == other.lastSeenTimestamp &&
                gamesPlayed == other.gamesPlayed &&
                gamesWon == other.gamesWon &&
                currentStreak == other.currentStreak &&
                level == other.level &&
                adminRevision == other.adminRevision
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (avatarUrl?.hashCode() ?: 0)
        result = 31 * result + isOnline.hashCode()
        result = 31 * result + lastSeenTimestamp.hashCode()
        result = 31 * result + gamesPlayed
        result = 31 * result + gamesWon
        result = 31 * result + currentStreak
        result = 31 * result + level
        result = 31 * result + adminRevision.hashCode()
        return result
    }
}
