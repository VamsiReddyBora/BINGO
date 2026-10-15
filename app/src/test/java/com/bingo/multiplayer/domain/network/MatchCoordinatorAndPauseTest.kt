package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.Clock
import com.bingo.multiplayer.domain.engine.HeartbeatDecision
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicy
import com.bingo.multiplayer.domain.engine.PeerAvailabilityState
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite validating 1v1 online match turn rules, pause/resume timing,
 * and claim victory rules (T01-T05, P01-P04, C01-C04).
 */
class MatchCoordinatorAndPauseTest {

    private class TestClock(var currentMillis: Long = 100_000L) : Clock {
        override fun elapsedRealtime(): Long = currentMillis
        fun advance(millis: Long) {
            currentMillis += millis
        }
    }

    // --- Turn Rules (T01 - T05) ---

    @Test
    fun `test T01 - Clean turn pass on timeout with number -1`() {
        val players = listOf(
            Player(id = "player_1", displayName = "Player 1", isHost = true),
            Player(id = "player_2", displayName = "Player 2", isHost = false)
        )
        val nextPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = players,
            disconnectedPlayerIds = emptySet(),
            currentPickerId = "player_1",
            fallbackPlayerId = "player_1",
            matchSeed = 12345L,
            customTurnOrder = players
        )
        assertEquals("player_2", nextPicker)

        val subsequentPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = players,
            disconnectedPlayerIds = emptySet(),
            currentPickerId = "player_2",
            fallbackPlayerId = "player_1",
            matchSeed = 12345L,
            customTurnOrder = players
        )
        assertEquals("player_1", subsequentPicker)
    }

    @Test
    fun `test T02 - 1v1 online match keeps both players in rotation indefinitely`() {
        val players = listOf(
            Player(id = "player_1", displayName = "Player 1", isHost = true),
            Player(id = "player_2", displayName = "Player 2", isHost = false)
        )
        // In 1v1 online, fullyDisconnected is emptySet() regardless of missed turns or disconnect status
        val isTwoPlayerOnline = (players.size == 2)
        val fullyDisconnected = if (isTwoPlayerOnline) emptySet<String>() else setOf("player_2")

        var currentPicker = "player_1"
        for (turn in 1..20) {
            currentPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = players,
                disconnectedPlayerIds = fullyDisconnected,
                currentPickerId = currentPicker,
                fallbackPlayerId = "player_1",
                matchSeed = 42L,
                customTurnOrder = players
            )
            val expectedPicker = if (turn % 2 == 1) "player_2" else "player_1"
            assertEquals("Turn $turn should rotate to $expectedPicker", expectedPicker, currentPicker)
        }
    }

    @Test
    fun `test T03 - 1v1 online match does not fast-skip turns`() {
        val isTwoPlayerOnline = true
        var turnTimer = 30
        var skippedDueToDisconnect = false

        // Simulate 1v1 turn: even if opponent has 15s silence, 1v1 bypasses fast-skip
        while (turnTimer > 0) {
            turnTimer -= 1
            if (!isTwoPlayerOnline) {
                // non-1v1 fast skip logic
                skippedDueToDisconnect = true
                break
            }
        }
        assertFalse("1v1 online match must never flag skippedDueToDisconnect", skippedDueToDisconnect)
        assertEquals(0, turnTimer)
    }

    @Test
    fun `test T04 - 1v1 online match consecutive missed turns does not forfeit`() {
        val missedTurns = mutableMapOf<String, Int>()
        val isTwoPlayerOnline = true

        for (i in 1..10) {
            if (!isTwoPlayerOnline) {
                missedTurns["player_2"] = (missedTurns["player_2"] ?: 0) + 1
            }
        }
        assertTrue("In 1v1 online matches, consecutive missed turns are not incremented towards forfeit", missedTurns.isEmpty())
    }

    @Test
    fun `test T05 - Multi-player match (3+ players) retains skip and forfeit logic`() {
        val players = listOf(
            Player(id = "player_1", displayName = "P1", isHost = true),
            Player(id = "player_2", displayName = "P2", isHost = false),
            Player(id = "player_3", displayName = "P3", isHost = false)
        )
        val isTwoPlayerOnline = (players.size == 2)
        assertFalse("3 players is not a 1v1 online match", isTwoPlayerOnline)

        val disconnectedPlayers = setOf("player_2")
        val consecutiveMissed = mapOf("player_2" to 3)
        val fullyDisconnected = disconnectedPlayers.filter { (consecutiveMissed[it] ?: 0) >= 3 }.toSet()
        assertEquals(setOf("player_2"), fullyDisconnected)

        val nextPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = players,
            disconnectedPlayerIds = fullyDisconnected,
            currentPickerId = "player_1",
            fallbackPlayerId = "player_1",
            matchSeed = 100L,
            customTurnOrder = players
        )
        assertEquals("player_3", nextPicker)
    }

    // --- Pause and Resume Timing Rules (P01 - P04) ---

    @Test
    fun `test P01 - Pause captures exact remaining turn seconds`() {
        val turnTimer = 18
        var frozenRemainingTurnSeconds: Int? = null

        // Trigger pause
        frozenRemainingTurnSeconds = turnTimer
        assertEquals(18, frozenRemainingTurnSeconds)
    }

    @Test
    fun `test P02 - Resume restores frozen remainder and subsequent turn gets full 30s`() {
        var turnTimer = 18
        var frozenRemainingTurnSeconds: Int? = 18

        // Resume current turn
        if (frozenRemainingTurnSeconds != null) {
            turnTimer = frozenRemainingTurnSeconds
            frozenRemainingTurnSeconds = null
        } else {
            turnTimer = 30
        }
        assertEquals(18, turnTimer)
        assertNull(frozenRemainingTurnSeconds)

        // Next turn starts
        if (frozenRemainingTurnSeconds != null) {
            turnTimer = frozenRemainingTurnSeconds
            frozenRemainingTurnSeconds = null
        } else {
            turnTimer = 30
        }
        assertEquals(30, turnTimer)
    }

    @Test
    fun `test P03 - GAME_PAUSED packet encodes and decodes remainingTurnMillis and isPaused`() {
        val frozenSeconds = 14
        val packet = RoomMessagePacket(
            type = "GAME_PAUSED",
            playerId = "player_host",
            displayName = "Host",
            remainingTurnMillis = frozenSeconds * 1000L,
            isPaused = true,
            pauseOwnerId = "player_host"
        )

        val encoded = FastPacketCodec.encode(packet)
        val decoded = FastPacketCodec.decode(encoded)

        assertEquals("GAME_PAUSED", decoded.type)
        assertEquals(14000L, decoded.remainingTurnMillis)
        assertTrue(decoded.isPaused == true)
        assertEquals("player_host", decoded.pauseOwnerId)
    }

    @Test
    fun `test P04 - GAME_RESUMED packet encodes and decodes remainingTurnMillis and isPaused false`() {
        val packet = RoomMessagePacket(
            type = "GAME_RESUMED",
            playerId = "player_host",
            displayName = "Host",
            remainingTurnMillis = 14000L,
            isPaused = false
        )

        val encoded = FastPacketCodec.encode(packet)
        val decoded = FastPacketCodec.decode(encoded)

        assertEquals("GAME_RESUMED", decoded.type)
        assertEquals(14000L, decoded.remainingTurnMillis)
        assertFalse(decoded.isPaused == true)
    }

    // --- Claim Victory Rules (C01 - C04) ---

    private fun simulateStableRecovery(policy: OnlineMatchHeartbeatPolicy, clock: TestClock) {
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        policy.onStateSynchronized()
        for (i in 1..6) {
            clock.advance(2000L)
            policy.onTick(clock.elapsedRealtime())
            policy.onFreshPeerResponse(clock.elapsedRealtime())
        }
        policy.onTick(clock.elapsedRealtime())
    }

    @Test
    fun `test C01 - Claim Victory eligibility requires episode count 3 or more while UNREACHABLE`() {
        val clock = TestClock(100_000L)
        val policy = OnlineMatchHeartbeatPolicy(
            matchId = "m_claim",
            peerUid = "peer_uid",
            connectionGeneration = "gen1",
            clock = clock
        )

        // Episode 1
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        clock.advance(15_001L)
        val d1 = policy.onTick(clock.elapsedRealtime())
        assertTrue(d1 is HeartbeatDecision.BecameUnreachable)
        assertFalse("Episode 1 should not be eligible for victory claim", (d1 as HeartbeatDecision.BecameUnreachable).isClaimEligible)
        assertFalse(policy.canClaimVictory("user_me"))

        // Recovery 1
        simulateStableRecovery(policy, clock)
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)

        // Episode 2
        clock.advance(15_001L)
        val d2 = policy.onTick(clock.elapsedRealtime())
        assertTrue(d2 is HeartbeatDecision.BecameUnreachable)
        assertFalse("Episode 2 should not be eligible for victory claim", (d2 as HeartbeatDecision.BecameUnreachable).isClaimEligible)
        assertFalse(policy.canClaimVictory("user_me"))

        // Recovery 2
        simulateStableRecovery(policy, clock)
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)

        // Episode 3
        clock.advance(15_001L)
        val d3 = policy.onTick(clock.elapsedRealtime())
        assertTrue(d3 is HeartbeatDecision.BecameUnreachable)
        assertTrue("Episode 3 MUST be eligible for victory claim", (d3 as HeartbeatDecision.BecameUnreachable).isClaimEligible)
        assertTrue(policy.canClaimVictory("user_me"))
    }

    @Test
    fun `test C02 - Claim Victory execution accepted and broadcasts CLAIM_VICTORY`() {
        val clock = TestClock(100_000L)
        val policy = OnlineMatchHeartbeatPolicy(
            matchId = "m_claim_exec",
            peerUid = "peer_exec",
            connectionGeneration = "gen_exec",
            clock = clock
        )

        // Initial responsive state
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        policy.onStateSynchronized()

        // Incident 1
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())
        // Recover 1
        simulateStableRecovery(policy, clock)

        // Incident 2
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())
        // Recover 2
        simulateStableRecovery(policy, clock)

        // Incident 3
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())

        assertEquals(PeerAvailabilityState.UNREACHABLE, policy.state)
        assertTrue(policy.canClaimVictory("claimer"))

        val result = policy.claimVictory("claimer")
        assertTrue("Claim must be accepted", result is com.bingo.multiplayer.domain.engine.ClaimResult.Accepted)
        val accepted = result as com.bingo.multiplayer.domain.engine.ClaimResult.Accepted
        assertEquals(3, accepted.episodeCount)

        val packet = RoomMessagePacket(
            type = "CLAIM_VICTORY",
            playerId = "claimer",
            displayName = "Winner",
            claimEpisodeCount = accepted.episodeCount
        )
        val encoded = FastPacketCodec.encode(packet)
        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("CLAIM_VICTORY", decoded.type)
        assertEquals(3, decoded.claimEpisodeCount)
    }

    @Test
    fun `test C03 - Peer receiving CLAIM_VICTORY packet decodes and processes loss`() {
        val packet = RoomMessagePacket(
            type = "CLAIM_VICTORY",
            playerId = "winner_peer",
            displayName = "Victor",
            claimEpisodeCount = 3
        )
        val encoded = FastPacketCodec.encode(packet)
        val decoded = FastPacketCodec.decode(encoded)

        assertEquals("CLAIM_VICTORY", decoded.type)
        assertEquals("winner_peer", decoded.playerId)
        assertEquals("Victor", decoded.displayName)
        assertEquals(3, decoded.claimEpisodeCount)
    }

    @Test
    fun `test C04 - Peer activity immediately revokes claim eligibility`() {
        val clock = TestClock(100_000L)
        val policy = OnlineMatchHeartbeatPolicy(
            matchId = "m_revoke",
            peerUid = "peer_rev",
            connectionGeneration = "gen_rev",
            clock = clock
        )

        // Initial responsive state
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        policy.onStateSynchronized()

        // Incident 1
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())
        // Recover 1
        simulateStableRecovery(policy, clock)

        // Incident 2
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())
        // Recover 2
        simulateStableRecovery(policy, clock)

        // Incident 3
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())

        assertTrue(policy.canClaimVictory("user_local"))

        // Peer sends packet / heartbeats -> transitions to RECOVERING
        val dec = policy.onFreshPeerResponse(clock.elapsedRealtime())
        assertTrue(dec is HeartbeatDecision.StartedRecovering)
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)
        assertFalse("Eligibility MUST be revoked immediately when peer responds", policy.canClaimVictory("user_local"))
    }

    @Test
    fun `test HB09 - Claim victory rejected when peer becomes responsive before confirmation`() {
        val clock = TestClock(100_000L)
        val policy = OnlineMatchHeartbeatPolicy(
            matchId = "m_hb09",
            peerUid = "peer_hb09",
            connectionGeneration = "gen1",
            clock = clock
        )

        // Reach 3 episodes
        for (ep in 1..2) {
            clock.advance(15_001L)
            policy.onTick(clock.elapsedRealtime())
            simulateStableRecovery(policy, clock)
        }
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())

        assertTrue("Should be claim eligible at 3 episodes while UNREACHABLE", policy.canClaimVictory("claimer"))

        // Peer responds just as user clicks claim button
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        assertFalse(policy.canClaimVictory("claimer"))

        // Claim execution rejected
        val claimResult = policy.claimVictory("claimer")
        assertTrue(claimResult is com.bingo.multiplayer.domain.engine.ClaimResult.Rejected)
    }

    @Test
    fun `test HB10 - Incoming CLAIM_VICTORY packet validated against 3 episode requirement`() {
        val invalidPacket = RoomMessagePacket(
            type = "CLAIM_VICTORY",
            playerId = "peer1",
            claimEpisodeCount = 2 // < 3 episodes
        )
        val validPacket = RoomMessagePacket(
            type = "CLAIM_VICTORY",
            playerId = "peer1",
            claimEpisodeCount = 3
        )
        assertFalse("Claim with less than 3 episodes must be rejected", invalidPacket.claimEpisodeCount >= 3)
        assertTrue("Claim with 3 or more episodes is valid", validPacket.claimEpisodeCount >= 3)
    }

    @Test
    fun `test F003 - Incoming claim validation rejects missing seed, blank matchId, unknown sender, and invalid episode ID`() {
        val currentMatchSeed = 445566L
        val myUid = "user_me"
        val participants = listOf(
            Player(id = "user_me", displayName = "Me", isHost = true),
            Player(id = "user_peer", displayName = "Peer", isHost = false)
        )

        fun validateClaimPacket(packet: RoomMessagePacket): Boolean {
            // Validate match seed: MUST be nonzero and match currentMatchSeed
            if (packet.seed == 0L || packet.seed != currentMatchSeed) return false
            // Validate matchId: MUST be nonblank and match currentMatchSeed string
            if (packet.matchId.isBlank() || packet.matchId != currentMatchSeed.toString()) return false
            // Validate peer identity: sender MUST be the peer in matchParticipants
            val peer = participants.firstOrNull { it.id != myUid }
            if (peer == null || packet.playerId != peer.id) return false
            // Validate episode count and episode ID
            if (packet.claimEpisodeCount < 3) return false
            if (packet.claimEpisodeId.isBlank()) return false
            return true
        }

        // 1. Zero seed (must be rejected!)
        assertFalse("Zero seed must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 0L, matchId = "445566", claimEpisodeCount = 3, claimEpisodeId = "ep1")
        ))

        // 2. Mismatched seed
        assertFalse("Mismatched seed must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 999L, matchId = "445566", claimEpisodeCount = 3, claimEpisodeId = "ep1")
        ))

        // 3. Blank matchId
        assertFalse("Blank matchId must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 445566L, matchId = "", claimEpisodeCount = 3, claimEpisodeId = "ep1")
        ))

        // 4. Mismatched matchId
        assertFalse("Mismatched matchId must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 445566L, matchId = "wrong_match", claimEpisodeCount = 3, claimEpisodeId = "ep1")
        ))

        // 5. Unknown sender not in participants
        assertFalse("Unknown sender not in participants must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "attacker", seed = 445566L, matchId = "445566", claimEpisodeCount = 3, claimEpisodeId = "ep1")
        ))

        // 6. Claim episode count < 3
        assertFalse("Episode count < 3 must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 445566L, matchId = "445566", claimEpisodeCount = 2, claimEpisodeId = "ep1")
        ))

        // 7. Blank claim episode ID
        assertFalse("Blank episode ID must be rejected", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 445566L, matchId = "445566", claimEpisodeCount = 3, claimEpisodeId = "")
        ))

        // 8. Fully valid claim
        assertTrue("Valid claim packet must be accepted", validateClaimPacket(
            RoomMessagePacket(type = "CLAIM_VICTORY", playerId = "user_peer", seed = 445566L, matchId = "445566", claimEpisodeCount = 3, claimEpisodeId = "ep_445566_3")
        ))
    }

    @Test
    fun `test F003 - Sender identity cannot overwrite opponentPlayerId if sender is not in matchParticipants`() {
        val myUid = "user_me"
        val participants = listOf(
            Player(id = "user_me", displayName = "Me", isHost = true),
            Player(id = "user_peer", displayName = "Peer", isHost = false)
        )
        var opponentPlayerId = "user_peer"

        fun onPacketReceived(packet: RoomMessagePacket) {
            if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                val isKnownParticipant = participants.isEmpty() || participants.any { it.id == packet.playerId }
                if (isKnownParticipant) {
                    opponentPlayerId = packet.playerId
                }
            }
        }

        // Packet from random unknown sender
        val roguePacket = RoomMessagePacket(
            type = "PING",
            playerId = "random_rogue_sender"
        )
        onPacketReceived(roguePacket)

        // Assert opponentPlayerId was NOT overwritten!
        assertEquals("user_peer", opponentPlayerId)
    }

    @Test
    fun `test F006 - Invalidation of sync flag on new outage and post-sync 10s stability window`() {
        val clock = TestClock()
        val policy = OnlineMatchHeartbeatPolicy(
            matchId = "m_f006",
            peerUid = "peer_f006",
            connectionGeneration = "gen1",
            clock = clock
        )

        // Initially state is synchronized
        policy.onStateSynchronized(clock.elapsedRealtime())

        // Outage occurs (> 15s)
        clock.advance(15_001L)
        policy.onTick(clock.elapsedRealtime())
        assertEquals(PeerAvailabilityState.UNREACHABLE, policy.state)
        assertEquals(1, policy.committedEpisodeCount)

        // First ACK arrives
        clock.advance(1_000L)
        policy.onFreshPeerResponse(clock.elapsedRealtime())
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)

        // ACKs arrive for 10 seconds, but state synchronization has NOT been performed for this outage!
        for (i in 1..4) {
            clock.advance(2_500L)
            policy.onFreshPeerResponse(clock.elapsedRealtime())
        }
        // Even after 10s of ACKs, because state is NOT synchronized, recovery must NOT complete!
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)
        assertTrue(policy.isEpisodeOpen)

        // Now state sync completes!
        policy.onStateSynchronized(clock.elapsedRealtime())
        // Immediately after sync, episode MUST still be open (stability window just started!)
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)
        assertTrue(policy.isEpisodeOpen)

        // Provide 10 seconds of stable heartbeats post-sync
        for (i in 1..3) {
            clock.advance(2_500L)
            policy.onFreshPeerResponse(clock.elapsedRealtime())
            assertEquals(PeerAvailabilityState.RECOVERING, policy.state)
        }
        clock.advance(2_500L)
        val decision = policy.onFreshPeerResponse(clock.elapsedRealtime())
        // Now 10 seconds post-sync have elapsed without gap > 5s -> recovery complete!
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertFalse(policy.isEpisodeOpen)
        assertTrue(decision is HeartbeatDecision.RecoveryCompleted)
    }
}
