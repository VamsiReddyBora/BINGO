package com.bingo.multiplayer.domain.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    DECLINED
}

@Keep
@Serializable
data class FriendRequest(
    val id: String,
    val fromUid: String,
    val fromUsername: String,
    val fromDisplayName: String,
    val fromAvatarUrl: String? = null,
    val toUsername: String,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
@Serializable
data class FriendRequestPacket(
    val type: String, // "FRIEND_REQUEST", "FRIEND_ACCEPT", "FRIEND_DECLINE"
    val request: FriendRequest,
    val acceptorFriend: Friend? = null
)
