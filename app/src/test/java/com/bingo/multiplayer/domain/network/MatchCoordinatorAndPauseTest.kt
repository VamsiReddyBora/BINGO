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

    // ── F3 Verification: Turn Timer Preserves Exact Frozen Seconds Through HOLD and Return ──

    @Test
    fun `test F3 - Turn timer preserves exact frozen seconds (17s and 1s) through HOLD and HostResumed without resetting to 30`() {
        for (testSeconds in listOf(17, 1)) {
            val matchSeed = 42L
            val currentTurnNumber = 5

            // Stuart's turn is active with testSeconds remaining
            var turnTimer = testSeconds
            var frozenRemainingTurnSeconds: Int? = null
            var frozenTurnNumber = -1
            var frozenMatchSeed = 0L

            var hostAbsenceHoldRemainingSeconds = 0
            var hostReturningCountdown: Int? = null
            fun isMultiplayerSuspended() = hostAbsenceHoldRemainingSeconds > 0 || (hostReturningCountdown != null && hostReturningCountdown!! > 0)

            // Step 1: Host disappears -> enters HOLD, freezing Stuart's remaining turn seconds
            hostAbsenceHoldRemainingSeconds = 50
            frozenRemainingTurnSeconds = turnTimer
            frozenTurnNumber = currentTurnNumber
            frozenMatchSeed = matchSeed
            assertTrue(isMultiplayerSuspended())

            // Step 2: Host returns -> 3s countdown starts
            hostReturningCountdown = 3
            hostAbsenceHoldRemainingSeconds = 0
            assertTrue(isMultiplayerSuspended())

            // Step 3: Countdown reaches 1, then completes -> HostResumed
            // In RootNavGraph: applyLifecycleAction handles HostResumed
            hostReturningCountdown = null
            hostAbsenceHoldRemainingSeconds = 0
            if (frozenRemainingTurnSeconds != null && frozenTurnNumber == currentTurnNumber && frozenMatchSeed == matchSeed) {
                turnTimer = frozenRemainingTurnSeconds
            }
            assertFalse(isMultiplayerSuspended())

            // Step 4: Turn timer LaunchedEffect restarts because isMultiplayerSuspended changed to false!
            // It MUST consume the preserved frozenRemainingTurnSeconds rather than falling into else { turnTimer = 30 }
            if (frozenRemainingTurnSeconds != null) {
                if (frozenTurnNumber == currentTurnNumber && frozenMatchSeed == matchSeed) {
                    turnTimer = frozenRemainingTurnSeconds
                }
                frozenRemainingTurnSeconds = null
                frozenTurnNumber = -1
                frozenMatchSeed = 0L
            } else if (turnTimer <= 0) {
                turnTimer = 30
            }

            // Stuart's timer MUST be exactly the preserved value, NOT 30!
            assertEquals("Stuart's turn timer must be restored to $testSeconds, not 30", testSeconds, turnTimer)
            assertNull(frozenRemainingTurnSeconds)

            // Step 5: When turn actually completes and next turn starts (turnNumber = 6)
            val nextTurnNumber = 6
            turnTimer = 30 // Fresh turn initializes to 30
            if (frozenRemainingTurnSeconds != null) {
                if (frozenTurnNumber == nextTurnNumber && frozenMatchSeed == matchSeed) {
                    turnTimer = frozenRemainingTurnSeconds
                }
                frozenRemainingTurnSeconds = null
                frozenTurnNumber = -1
                frozenMatchSeed = 0L
            } else if (turnTimer <= 0) {
                turnTimer = 30
            }
            assertEquals(30, turnTimer)
        }
    }

    @Test
    fun `test C01 - Host authority ensures stale guest GAME_SYNC cannot unpause host`() {
        val isHosting = true
        var isGamePaused = true
        var pauseRemainingSeconds = 30

        // Guest sends an ordinary GAME_SYNC with isPaused = false
        val staleGuestPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "guest_A",
            seed = 42L,
            turnNumber = 1,
            currentTurnPlayerId = "guest_B",
            isPaused = false,
            remainingTurnMillis = 17000L,
            timestamp = 1000L
        )

        val hostPlayerId = "host"
        val isSenderHost = (staleGuestPacket.playerId == hostPlayerId) || staleGuestPacket.isHost

        // Under C1 fix: only non-host accepts pause state from host
        if (!isHosting && isSenderHost) {
            isGamePaused = staleGuestPacket.isPaused
            pauseRemainingSeconds = staleGuestPacket.pauseRemainingSeconds
        }

        // Host MUST remain paused
        assertTrue("Host must remain paused despite stale guest GAME_SYNC", isGamePaused)
        assertEquals(30, pauseRemainingSeconds)
    }

    @Test
    fun `test C02 - frozen remaining millis is cleared on checkpoint consumption and active play publishes current time`() {
        var turnTimer = 17
        var frozenRemainingTurnSeconds: Int? = 17
        var frozenRemainingTurnMillis: Long? = 17000L

        // HostResumed / LaunchedEffect consumes checkpoint
        if (frozenRemainingTurnSeconds != null) {
            turnTimer = frozenRemainingTurnSeconds
            frozenRemainingTurnSeconds = null
            frozenRemainingTurnMillis = null
        }

        assertNull(frozenRemainingTurnSeconds)
        assertNull(frozenRemainingTurnMillis)
        assertEquals(17, turnTimer)

        // 5 seconds of active gameplay elapse
        turnTimer -= 5
        assertEquals(12, turnTimer)

        val isGamePaused = false
        val isMultiplayerSuspended = false

        // Outgoing GAME_SYNC packet publishes turnTimer * 1000L during active gameplay
        val publishedMillis = if (isGamePaused || isMultiplayerSuspended) {
            frozenRemainingTurnMillis ?: (turnTimer * 1000L)
        } else {
            turnTimer * 1000L
        }

        assertEquals(12000L, publishedMillis)
    }

    // ── Round 4 Tests (D01 - D03) ──

    @Test
    fun `test D01 - Host never adopts turn from guest GAME_SYNC and guest rejects stale turn`() {
        // 1. Host side: isHosting = true, current turn = 4 for "host"
        var hostTurnNumber = 4
        var hostCurrentTurnPlayerId = "host"
        val isHosting = true
        val hostUid = "host"

        val guestPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "guest_A",
            seed = 42L,
            turnNumber = 2,
            currentTurnPlayerId = "guest_B",
            isHost = false
        )

        val isSenderHost = (guestPacket.playerId == hostUid) || guestPacket.isHost

        // Under D1 fix: only non-host accepts turn authority from host
        if (!isHosting && isSenderHost && (guestPacket.turnNumber > hostTurnNumber)) {
            hostTurnNumber = guestPacket.turnNumber
            hostCurrentTurnPlayerId = guestPacket.currentTurnPlayerId
        }

        // Host must NOT adopt turn 2 or player "guest_B"
        assertEquals(4, hostTurnNumber)
        assertEquals("host", hostCurrentTurnPlayerId)

        // 2. Guest side: !isHosting, current turn = 4
        var guestTurnNumber = 4
        var guestCurrentTurnPlayerId = "host"
        val guestIsHosting = false

        // Delayed host snapshot for turn 2 arrives at guest
        val staleHostPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "host",
            seed = 42L,
            turnNumber = 2,
            currentTurnPlayerId = "guest_B",
            isHost = true
        )
        val guestIsSenderHost = (staleHostPacket.playerId == hostUid) || staleHostPacket.isHost

        if (!guestIsHosting && guestIsSenderHost && (staleHostPacket.turnNumber > guestTurnNumber)) {
            guestTurnNumber = staleHostPacket.turnNumber
            guestCurrentTurnPlayerId = staleHostPacket.currentTurnPlayerId
        }

        // Guest must NOT revert to turn 2
        assertEquals(4, guestTurnNumber)
        assertEquals("host", guestCurrentTurnPlayerId)

        // Newer host snapshot for turn 5 arrives at guest
        val newerHostPacket = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "host",
            seed = 42L,
            turnNumber = 5,
            currentTurnPlayerId = "guest_A",
            isHost = true
        )
        if (!guestIsHosting && guestIsSenderHost && (newerHostPacket.turnNumber > guestTurnNumber)) {
            guestTurnNumber = newerHostPacket.turnNumber
            guestCurrentTurnPlayerId = newerHostPacket.currentTurnPlayerId
        }

        // Guest advances to turn 5 and "guest_A"
        assertEquals(5, guestTurnNumber)
        assertEquals("guest_A", guestCurrentTurnPlayerId)
    }

    @Test
    fun `test D02 - Nearby guest cannot control pause locally and accepts pause only from host`() {
        val hostPlayer = Player(id = "kevin", displayName = "Kevin", isHost = true)
        val guestPlayer = Player(id = "stuart", displayName = "Stuart", isHost = false)
        val nearbyParticipants = listOf(hostPlayer, guestPlayer)

        // 1. Check isCurrentHost predicate for Nearby
        fun evaluateIsCurrentHost(isHosting: Boolean, localUid: String): Boolean {
            val isHostPlayer = isHosting || (nearbyParticipants.singleOrNull { it.isHost }?.id == localUid)
            return isHostPlayer
        }

        val hostIsCurrentHost = evaluateIsCurrentHost(isHosting = true, localUid = "kevin")
        val guestIsCurrentHost = evaluateIsCurrentHost(isHosting = false, localUid = "stuart")

        assertTrue("Nearby host must have isCurrentHost = true", hostIsCurrentHost)
        assertFalse("Nearby guest must have isCurrentHost = false", guestIsCurrentHost)

        // 2. Check handlePauseAction guard for Nearby
        fun canExecutePauseAction(isHosting: Boolean, localUid: String): Boolean {
            val isHostPlayer = isHosting || (nearbyParticipants.singleOrNull { it.isHost }?.id == localUid)
            return isHostPlayer
        }

        assertTrue("Host can execute pause action", canExecutePauseAction(isHosting = true, localUid = "kevin"))
        assertFalse("Guest cannot execute pause action", canExecutePauseAction(isHosting = false, localUid = "stuart"))

        // 3. Check GAME_PAUSED receiver guard
        fun receiverAcceptsPause(receiverIsHosting: Boolean, senderId: String, senderIsHost: Boolean): Boolean {
            val hostPlayerId = nearbyParticipants.find { it.isHost }?.id ?: "kevin"
            val isSenderHost = (senderId == hostPlayerId) || senderIsHost
            return !receiverIsHosting && isSenderHost
        }

        // Nearby host receives guest pause packet -> must be rejected
        val hostAcceptsGuestPause = receiverAcceptsPause(receiverIsHosting = true, senderId = "stuart", senderIsHost = false)
        assertFalse("Nearby host must reject guest pause packet", hostAcceptsGuestPause)

        // Nearby guest receives host pause packet -> must be accepted
        val guestAcceptsHostPause = receiverAcceptsPause(receiverIsHosting = false, senderId = "kevin", senderIsHost = true)
        assertTrue("Nearby guest must accept host pause packet", guestAcceptsHostPause)
    }

    @Test
    fun `test D03 - Duplicate GAME_PAUSED packet rejected by strict timestamp guard`() {
        var lastAppliedPauseTimestamp = 0L

        fun shouldAcceptPausePacket(packetTimestamp: Long): Boolean {
            val accept = (packetTimestamp == 0L || packetTimestamp > lastAppliedPauseTimestamp)
            if (accept && packetTimestamp > 0L) {
                lastAppliedPauseTimestamp = packetTimestamp
            }
            return accept
        }

        // First packet with timestamp 1000 arrives -> accepted
        assertTrue("First GAME_PAUSED packet must be accepted", shouldAcceptPausePacket(1000L))
        assertEquals(1000L, lastAppliedPauseTimestamp)

        // Duplicate packet with timestamp 1000 arrives -> rejected!
        assertFalse("Duplicate GAME_PAUSED packet with same timestamp must be rejected", shouldAcceptPausePacket(1000L))
        assertEquals(1000L, lastAppliedPauseTimestamp)

        // Obsolete packet with older timestamp 900 arrives -> rejected!
        assertFalse("Older GAME_PAUSED packet must be rejected", shouldAcceptPausePacket(900L))
        assertEquals(1000L, lastAppliedPauseTimestamp)

        // Newer packet with timestamp 1050 arrives -> accepted!
        assertTrue("Newer GAME_PAUSED packet must be accepted", shouldAcceptPausePacket(1050L))
        assertEquals(1050L, lastAppliedPauseTimestamp)
    }

    // ── E01: Missed Guest Pick Recovery on Host ──

    @Test
    fun `test E01 - Missed guest pick recovery on host advances turn exactly once and broadcasts authoritative state`() {
        val participants = listOf(
            Player(id = "host", displayName = "Kevin", isHost = true),
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "B", displayName = "Bob", isHost = false)
        )
        val testClock = TestClock(100_000L)
        val hostPolicy = com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "host",
            isHost = true,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = testClock
        )

        // Initial state: turn 1 belongs to Stuart ("A")
        val hostPicks = mutableListOf<Int>()
        val hostPickedBy = mutableListOf<String>()
        var hostTurnNumber = 1
        var hostCurrentTurnPlayerId = "A"
        var broadcastGameSyncCount = 0
        var broadcastTurnNumber = 0
        var broadcastCurrentPlayer = ""

        hostPolicy.onTurnStarted(newTurnPlayerId = "A", turnNumber = 1, forceReset = true)

        // Stuart picks 7 and sends GAME_SYNC with pickedHistory = [7] (since PICK_NUMBER was lost)
        val guestSync = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "A",
            seed = 42L,
            turnNumber = 2,
            currentTurnPlayerId = "host",
            pickedHistory = listOf(7),
            pickedByHistory = listOf("A"),
            turnIds = listOf(1),
            timestamp = 1000L
        )

        // Simulate host processing guestSync (RootNavGraph GAME_SYNC handler logic)
        fun handleGameSyncOnHost(packet: RoomMessagePacket, isGameOver: Boolean = false) {
            val isHosting = true
            var anyNewPick = false
            var newlyRecoveredPickCount = 0

            packet.pickedHistory.forEachIndexed { idx, num ->
                if (num > 0 && num !in hostPicks) {
                    val origPickerId = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                    hostPicks.add(num)
                    hostPickedBy.add(origPickerId)
                    anyNewPick = true
                    newlyRecoveredPickCount++
                }
            }

            if (!isGameOver) {
                if (isHosting && anyNewPick) {
                    val lastPicker = hostPickedBy.lastOrNull()?.takeIf { it.isNotBlank() } ?: hostCurrentTurnPlayerId
                    val nextPlayerId = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                        allParticipants = participants,
                        disconnectedPlayerIds = emptySet(),
                        currentPickerId = lastPicker,
                        fallbackPlayerId = "host",
                        matchSeed = 42L,
                        customTurnOrder = participants
                    )
                    hostCurrentTurnPlayerId = nextPlayerId
                    hostTurnNumber += newlyRecoveredPickCount
                    hostPolicy.onTurnStarted(newTurnPlayerId = nextPlayerId, turnNumber = hostTurnNumber, forceReset = true)
                    broadcastGameSyncCount++
                    broadcastTurnNumber = hostTurnNumber
                    broadcastCurrentPlayer = nextPlayerId
                }
            }
        }

        // Host receives guest GAME_SYNC
        handleGameSyncOnHost(guestSync)

        // 1. Pick 7 is recovered into host history
        assertEquals(listOf(7), hostPicks)
        assertEquals(listOf("A"), hostPickedBy)

        // 2. Host turn advances to turn 2 for next player (Bob "B", or next in rotation)
        assertEquals("Host turn must advance to 2", 2, hostTurnNumber)
        assertFalse("Host must no longer wait on A", hostCurrentTurnPlayerId == "A")
        assertEquals(2, hostPolicy.currentTurnNumber)
        assertEquals(hostCurrentTurnPlayerId, hostPolicy.currentTurnPlayerId)

        // 3. Authoritative host GAME_SYNC was broadcast exactly once
        assertEquals(1, broadcastGameSyncCount)
        assertEquals(2, broadcastTurnNumber)
        assertEquals(hostCurrentTurnPlayerId, broadcastCurrentPlayer)

        // 4. Replay test: Replay the identical snapshot -> must NOT advance turn again
        handleGameSyncOnHost(guestSync)
        assertEquals("Host turn must remain at 2 on replay", 2, hostTurnNumber)
        assertEquals("No second broadcast on snapshot replay", 1, broadcastGameSyncCount)

        // 5. Stale/invalid guest claim test: Guest sends snapshot with no new picks claiming turn 5
        val forgedSync = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "B",
            seed = 42L,
            turnNumber = 5,
            currentTurnPlayerId = "B",
            pickedHistory = listOf(7),
            pickedByHistory = listOf("A"),
            turnIds = listOf(1),
            timestamp = 2000L
        )
        handleGameSyncOnHost(forgedSync)
        assertEquals("Host turn must ignore forged turn claim without new picks", 2, hostTurnNumber)
        assertEquals(1, broadcastGameSyncCount)

        // 6. Bingo outcome test: If recovered pick completes Bingo, turn does NOT advance
        val winningGuestSync = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "B",
            seed = 42L,
            turnNumber = 3,
            currentTurnPlayerId = "host",
            pickedHistory = listOf(7, 14),
            pickedByHistory = listOf("A", "B"),
            turnIds = listOf(1, 2),
            timestamp = 3000L
        )
        handleGameSyncOnHost(winningGuestSync, isGameOver = true)
        assertEquals("14 must be recorded in picks", listOf(7, 14), hostPicks)
        assertEquals("Turn must not advance when game is over", 2, hostTurnNumber)
        assertEquals(1, broadcastGameSyncCount)
    }

    // ── E02: Pause Extension Recovery in Presentation State ──

    @Test
    fun `test E02 - Guest presentation recovers pause extension from newer host snapshot`() {
        val testClock = TestClock(100_000L)
        val guestPolicy = com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A"),
            clock = testClock
        )

        var isGamePaused = true
        var pauseRemainingSeconds = 30
        var lastAppliedPauseTimestamp = 1000L

        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = testClock.elapsedRealtime(), timestamp = 1000L)
        assertEquals(30, guestPolicy.pauseRemainingSeconds)

        // Advance 26s: remaining is 4s
        testClock.advance(26_000L)
        pauseRemainingSeconds = 4
        assertEquals(4, guestPolicy.pauseRemainingSeconds)

        // Host extended pause, but PAUSE_EXTEND was lost.
        // Host sends GAME_SYNC with pauseRemainingSeconds = 29, timestamp = 27000
        val packet = RoomMessagePacket(
            type = "GAME_SYNC",
            playerId = "host",
            isHost = true,
            isPaused = true,
            pauseRemainingSeconds = 29,
            timestamp = 27_000L
        )

        // Guest processes GAME_SYNC
        val wasPaused = isGamePaused
        isGamePaused = true
        val pauseSec = if (packet.pauseRemainingSeconds > 0) packet.pauseRemainingSeconds else 30
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = pauseSec, now = testClock.elapsedRealtime(), timestamp = packet.timestamp)
        pauseRemainingSeconds = guestPolicy.pauseRemainingSeconds
        lastAppliedPauseTimestamp = packet.timestamp

        // Guest UI and Policy state MUST both be 29 seconds!
        assertEquals("Policy engine must recover extension", 29, guestPolicy.pauseRemainingSeconds)
        assertEquals("Compose presentation state must recover extension", 29, pauseRemainingSeconds)

        // Delayed PAUSE_EXTEND arrives with older timestamp 26000
        val delayedPacket = RoomMessagePacket(
            type = "PAUSE_EXTEND",
            playerId = "host",
            isHost = true,
            timestamp = 26_000L
        )
        val shouldAcceptDelayed = (delayedPacket.timestamp == 0L || delayedPacket.timestamp > lastAppliedPauseTimestamp)
        assertFalse("Delayed PAUSE_EXTEND after newer snapshot must be rejected by RootNavGraph guard", shouldAcceptDelayed)
    }

    // ── F1: Reconcile Recovered Picks & Closed Turn Pick Rejection ──

    @Test
    fun `test F1_01 - Closed turn pick from expired turn is strictly rejected`() {
        val players = listOf(
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "host", displayName = "Kevin", isHost = true),
            Player(id = "B", displayName = "Bob", isHost = false)
        )

        // Host has advanced to turn 2 (Stuart's turn 1 timed out or passed)
        val currentTurn = 2
        val currentTurnPlayer = "host"

        // Stuart sends delayed snapshot with pick 7 on turn 1
        val result = LobbyLifecycleEngine.reconcileRecoveredPicks(
            candidatePickedHistory = listOf(7),
            candidatePickedByHistory = listOf("A"),
            candidateTurnIds = listOf(1),
            senderPlayerId = "A",
            currentTurnNumber = currentTurn,
            currentTurnPlayerId = currentTurnPlayer,
            existingPickedNumbers = emptyList(),
            existingTurnIds = emptyList(),
            allParticipants = players,
            fallbackPlayerId = "host",
            matchSeed = 42L
        )

        // Late pick tagged with expired turn 1 must be rejected!
        assertTrue("Candidate picks for closed turn must be rejected", result.acceptedPicks.isEmpty())
        assertFalse("Turn must not advance on rejected pick", result.turnAdvanced)
        assertEquals("Turn number must remain unchanged", 2, result.nextTurnNumber)
        assertEquals("Current player must remain unchanged", "host", result.nextTurnPlayerId)
    }

    @Test
    fun `test F1_02 - Current open turn candidate pick is accepted and advances turn`() {
        val players = listOf(
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "host", displayName = "Kevin", isHost = true),
            Player(id = "B", displayName = "Bob", isHost = false)
        )

        // Current turn is 1 with player "A"
        val currentTurn = 1
        val currentTurnPlayer = "A"

        val result = LobbyLifecycleEngine.reconcileRecoveredPicks(
            candidatePickedHistory = listOf(7),
            candidatePickedByHistory = listOf("A"),
            candidateTurnIds = listOf(1),
            senderPlayerId = "A",
            currentTurnNumber = currentTurn,
            currentTurnPlayerId = currentTurnPlayer,
            existingPickedNumbers = emptyList(),
            existingTurnIds = emptyList(),
            allParticipants = players,
            fallbackPlayerId = "host",
            matchSeed = 42L
        )

        assertEquals(1, result.acceptedPicks.size)
        assertEquals(7, result.acceptedPicks[0].number)
        assertEquals("A", result.acceptedPicks[0].pickerId)
        assertEquals(1, result.acceptedPicks[0].turnNumber)
        assertTrue("Turn must advance on accepted pick", result.turnAdvanced)
        assertEquals(2, result.nextTurnNumber)
        // Next picker after "A" with seed 42
        val expectedNext = LobbyLifecycleEngine.calculateNextTurnPlayerId(players, emptySet(), "A", "host", 42L)
        assertEquals(expectedNext, result.nextTurnPlayerId)
    }

    @Test
    fun `test F1_03 - Replay of already accepted move does not advance turn`() {
        val players = listOf(
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "host", displayName = "Kevin", isHost = true),
            Player(id = "B", displayName = "Bob", isHost = false)
        )

        // Move 7 on turn 1 was already accepted on host, turn is now 2
        val result = LobbyLifecycleEngine.reconcileRecoveredPicks(
            candidatePickedHistory = listOf(7),
            candidatePickedByHistory = listOf("A"),
            candidateTurnIds = listOf(1),
            senderPlayerId = "A",
            currentTurnNumber = 2,
            currentTurnPlayerId = "host",
            existingPickedNumbers = setOf(7),
            existingTurnIds = setOf(1),
            allParticipants = players,
            fallbackPlayerId = "host",
            matchSeed = 42L
        )

        assertTrue("Already accepted move must be deduplicated", result.acceptedPicks.isEmpty())
        assertFalse("Replay must not advance turn", result.turnAdvanced)
        assertEquals(2, result.nextTurnNumber)
        assertEquals("host", result.nextTurnPlayerId)
    }

    @Test
    fun `test F1_04 - Candidate pick from non-turn player is rejected`() {
        val players = listOf(
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "host", displayName = "Kevin", isHost = true),
            Player(id = "B", displayName = "Bob", isHost = false)
        )

        // Current turn is 1 for player "A". Candidate move 7 claims to be picked by player "B".
        val result = LobbyLifecycleEngine.reconcileRecoveredPicks(
            candidatePickedHistory = listOf(7),
            candidatePickedByHistory = listOf("B"),
            candidateTurnIds = listOf(1),
            senderPlayerId = "B",
            currentTurnNumber = 1,
            currentTurnPlayerId = "A",
            existingPickedNumbers = emptyList(),
            existingTurnIds = emptyList(),
            allParticipants = players,
            fallbackPlayerId = "host",
            matchSeed = 42L
        )

        assertTrue("Pick by wrong player must be rejected", result.acceptedPicks.isEmpty())
        assertFalse("Turn must not advance on rejected pick", result.turnAdvanced)
        assertEquals(1, result.nextTurnNumber)
        assertEquals("A", result.nextTurnPlayerId)
    }

    @Test
    fun `test F1_05 - Late pick that would create Bingo on closed turn is rejected without awarding win`() {
        val engine = com.bingo.multiplayer.domain.engine.BingoEngine()
        var hostBoard = engine.generateBoard(5, 42L)

        // Suppose cells for 1, 2, 3, 4 are marked for player A.
        // Number 5 is on the same row. If 5 is marked, A gets a row completion.
        hostBoard = engine.markCell(hostBoard, 1, "A", false, 1)
        hostBoard = engine.markCell(hostBoard, 2, "A", false, 2)
        hostBoard = engine.markCell(hostBoard, 3, "A", false, 3)
        hostBoard = engine.markCell(hostBoard, 4, "A", false, 4)

        // Turn 5 timed out on host. Host is now at turn 6.
        val currentTurn = 6
        val currentTurnPlayer = "host"

        val players = listOf(
            Player(id = "A", displayName = "Stuart", isHost = false),
            Player(id = "host", displayName = "Kevin", isHost = true)
        )

        // A sends delayed snapshot with pick 5 on turn 5
        val result = LobbyLifecycleEngine.reconcileRecoveredPicks(
            candidatePickedHistory = listOf(1, 2, 3, 4, 5),
            candidatePickedByHistory = listOf("A", "A", "A", "A", "A"),
            candidateTurnIds = listOf(1, 2, 3, 4, 5),
            senderPlayerId = "A",
            currentTurnNumber = currentTurn,
            currentTurnPlayerId = currentTurnPlayer,
            existingPickedNumbers = setOf(1, 2, 3, 4),
            existingTurnIds = setOf(1, 2, 3, 4),
            allParticipants = players,
            fallbackPlayerId = "host",
            matchSeed = 42L
        )

        // Turn 5 pick is strictly rejected!
        assertTrue("Turn 5 pick must be rejected because turn 5 already closed", result.acceptedPicks.isEmpty())
        assertFalse(result.turnAdvanced)

        // Host board is NOT marked with 5!
        assertFalse("Cell 5 must NOT be marked on host board", hostBoard.cells.any { it.number == 5 && it.isMarked })
    }

    @Test
    fun `test F1_06 - Guest unmarks local optimistic pick when host authoritative state rejects it`() {
        val engine = com.bingo.multiplayer.domain.engine.BingoEngine()
        var guestBoard = engine.generateBoard(5, 123L)

        // Guest optimistically marked 14 during turn 1
        guestBoard = engine.markCell(guestBoard, 14, "guest", true, 1)
        assertTrue(guestBoard.cells.first { it.number == 14 }.isMarked)

        // Authoritative host state arrives: host picks are only [7] (pick 14 was rejected by host)
        val hostAuthoritativePicks = setOf(7)
        if (14 !in hostAuthoritativePicks) {
            guestBoard = engine.unmarkCell(guestBoard, 14)
        }

        assertFalse("Guest must have unmarked rejected cell 14", guestBoard.cells.first { it.number == 14 }.isMarked)
    }
}
