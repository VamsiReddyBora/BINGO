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
    val lastSeenTimestamp: Long = 0L
)

