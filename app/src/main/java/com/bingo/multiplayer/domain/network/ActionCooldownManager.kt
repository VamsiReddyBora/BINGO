package com.bingo.multiplayer.domain.network

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages 15-second action cooldowns for social interactions:
 * - Sending game invites to friends/players
 * - Sending friend requests
 *
 * Exposes reactive state so Compose components re-render smoothly
 * when cooldowns are active or expire.
 */
object ActionCooldownManager {
    const val COOLDOWN_DURATION_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Maps clean username -> expiry timestamp in millis
    private val inviteCooldowns = mutableStateMapOf<String, Long>()
    private val friendRequestCooldowns = mutableStateMapOf<String, Long>()

    // ── Invite Cooldowns ──
    fun startInviteCooldown(username: String, durationMs: Long = COOLDOWN_DURATION_MS) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isNotBlank()) {
            val expiry = System.currentTimeMillis() + durationMs
            inviteCooldowns[clean] = expiry
            scope.launch {
                delay(durationMs)
                inviteCooldowns.remove(clean)
            }
        }
    }

    fun isInviteOnCooldown(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        val expiry = inviteCooldowns[clean] ?: return false
        val now = System.currentTimeMillis()
        if (now >= expiry) {
            inviteCooldowns.remove(clean)
            return false
        }
        return true
    }

    fun getInviteRemainingSeconds(username: String): Int {
        val clean = username.trim().lowercase().removePrefix("@")
        val expiry = inviteCooldowns[clean] ?: return 0
        val remaining = ((expiry - System.currentTimeMillis()) / 1000L).toInt()
        return if (remaining > 0) remaining else 0
    }

    // ── Friend Request Cooldowns ──
    fun startFriendRequestCooldown(username: String, durationMs: Long = COOLDOWN_DURATION_MS) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isNotBlank()) {
            val expiry = System.currentTimeMillis() + durationMs
            friendRequestCooldowns[clean] = expiry
            scope.launch {
                delay(durationMs)
                friendRequestCooldowns.remove(clean)
            }
        }
    }

    fun isFriendRequestOnCooldown(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        val expiry = friendRequestCooldowns[clean] ?: return false
        val now = System.currentTimeMillis()
        if (now >= expiry) {
            friendRequestCooldowns.remove(clean)
            return false
        }
        return true
    }

    fun getFriendRequestRemainingSeconds(username: String): Int {
        val clean = username.trim().lowercase().removePrefix("@")
        val expiry = friendRequestCooldowns[clean] ?: return 0
        val remaining = ((expiry - System.currentTimeMillis()) / 1000L).toInt()
        return if (remaining > 0) remaining else 0
    }
}
