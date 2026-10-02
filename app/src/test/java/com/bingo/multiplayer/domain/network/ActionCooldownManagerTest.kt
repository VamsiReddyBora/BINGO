package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActionCooldownManagerTest {

    @Test
    fun testInviteCooldown_activationAndNormalization() {
        val user = "@TestPlayer_1"
        assertFalse(ActionCooldownManager.isInviteOnCooldown(user))
        assertEquals(0, ActionCooldownManager.getInviteRemainingSeconds(user))

        // Trigger cooldown
        ActionCooldownManager.startInviteCooldown(user, durationMs = 15_000L)

        // Verify active cooldown with different case and prefix
        assertTrue(ActionCooldownManager.isInviteOnCooldown(user))
        assertTrue(ActionCooldownManager.isInviteOnCooldown("testplayer_1"))
        assertTrue(ActionCooldownManager.isInviteOnCooldown("TESTPLAYER_1"))
        assertTrue(ActionCooldownManager.getInviteRemainingSeconds(user) in 1..15)
    }

    @Test
    fun testFriendRequestCooldown_activationAndNormalization() {
        val user = "Friend_Candidate_X"
        assertFalse(ActionCooldownManager.isFriendRequestOnCooldown(user))
        assertEquals(0, ActionCooldownManager.getFriendRequestRemainingSeconds(user))

        // Trigger cooldown
        ActionCooldownManager.startFriendRequestCooldown(user, durationMs = 15_000L)

        // Verify active cooldown
        assertTrue(ActionCooldownManager.isFriendRequestOnCooldown(user))
        assertTrue(ActionCooldownManager.isFriendRequestOnCooldown("@friend_candidate_x"))
        assertTrue(ActionCooldownManager.getFriendRequestRemainingSeconds(user) in 1..15)
    }

    @Test
    fun testCooldownExpiration_withShortDuration() = runBlocking {
        val user = "short_timer_user"
        ActionCooldownManager.startInviteCooldown(user, durationMs = 50L)
        assertTrue(ActionCooldownManager.isInviteOnCooldown(user))

        kotlinx.coroutines.delay(80L)

        assertFalse(ActionCooldownManager.isInviteOnCooldown(user))
        assertEquals(0, ActionCooldownManager.getInviteRemainingSeconds(user))
    }
}
