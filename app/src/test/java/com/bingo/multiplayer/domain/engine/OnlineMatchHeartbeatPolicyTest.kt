package com.bingo.multiplayer.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OnlineMatchHeartbeatPolicy validating exact 1v1 heartbeat specifications:
 * Tests: H01, H02, H03, H04, H05, H06, H07, H08, H09, C01, C02, C03, H12, R11.
 */
class OnlineMatchHeartbeatPolicyTest {

    private class FakeClock(var currentMs: Long = 100_000L) : Clock {
        override fun elapsedRealtime(): Long = currentMs

        fun advance(ms: Long) {
            currentMs += ms
        }
    }

    private lateinit var clock: FakeClock
    private lateinit var policy: OnlineMatchHeartbeatPolicy

    private val matchId = "match_123"
    private val peerUid = "peer_stuart"
    private val myUid = "user_kevin"
    private val generation = "gen_abc_1"

    @Before
    fun setUp() {
        clock = FakeClock(100_000L)
        policy = OnlineMatchHeartbeatPolicy(
            matchId = matchId,
            peerUid = peerUid,
            clock = clock
        )
        policy.initializeObservation(generation = generation, initialObservationTime = clock.elapsedRealtime())
    }

    @Test
    fun `H01 - healthy heartbeat traffic causes no incidents and stays responsive`() {
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertEquals(0, policy.committedEpisodeCount)
        assertFalse(policy.isEpisodeOpen)

        // Simulate healthy probes every 2500ms for 30 seconds
        for (i in 1..12) {
            clock.advance(2500L)
            val tickDecision = policy.onTick()
            assertEquals(HeartbeatDecision.NoOp, tickDecision)

            val respDecision = policy.onFreshPeerResponse()
            assertEquals(HeartbeatDecision.NoOp, respDecision)
            assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        }

        assertEquals(0, policy.committedEpisodeCount)
        assertFalse(policy.isEpisodeOpen)
        assertFalse(policy.isClaimVictoryEligible)
    }

    @Test
    fun `H02 - silence at 14999 ms causes zero incidents, exactly 15000 ms triggers BecameUnreachable`() {
        // Advance to 14,999 ms
        clock.advance(14_999L)
        val tickBefore = policy.onTick()
        assertEquals(HeartbeatDecision.NoOp, tickBefore)
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertEquals(0, policy.committedEpisodeCount)
        assertFalse(policy.isEpisodeOpen)

        // Advance 1 ms to reach exactly 15,000 ms threshold
        clock.advance(1L)
        val tickAtThreshold = policy.onTick()
        assertTrue(tickAtThreshold is HeartbeatDecision.BecameUnreachable)
        val becameUnreachable = tickAtThreshold as HeartbeatDecision.BecameUnreachable
        assertEquals(1, becameUnreachable.episodeCount)
        assertFalse(becameUnreachable.isClaimEligible) // Only 1st episode, claim not eligible

        assertEquals(PeerAvailabilityState.UNREACHABLE, policy.state)
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)
        assertNotNull(policy.currentEpisodeId)
    }

    @Test
    fun `H03 - healthy replies with occasional dropped probes judges fresh response time not packet count`() {
        // Drop probes at 2.5s, 5.0s, 7.5s, but receive reply at 10.0s
        clock.advance(10_000L)
        policy.onTick()
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertEquals(0, policy.committedEpisodeCount)

        // Fresh response arrives at 10s
        policy.onFreshPeerResponse()
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)

        // Another gap of 12 seconds
        clock.advance(12_000L)
        policy.onTick()
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertEquals(0, policy.committedEpisodeCount)

        policy.onFreshPeerResponse()
        assertEquals(0, policy.committedEpisodeCount)
    }

    @Test
    fun `H04 - continuous silence for 45 seconds and 5 minutes remains exactly one episode`() {
        clock.advance(15_000L)
        val firstOutage = policy.onTick()
        assertTrue(firstOutage is HeartbeatDecision.BecameUnreachable)
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)

        // Advance 30 more seconds (total 45s)
        clock.advance(30_000L)
        val tickAt45s = policy.onTick()
        assertEquals(HeartbeatDecision.NoOp, tickAt45s)
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)

        // Advance 4 more minutes (total ~5 minutes)
        clock.advance(240_000L)
        val tickAt5m = policy.onTick()
        assertEquals(HeartbeatDecision.NoOp, tickAt5m)
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)
        assertEquals(PeerAvailabilityState.UNREACHABLE, policy.state)
    }

    @Test
    fun `H05 - two separate outages after stable recovery produces count 1 then 2 without claim victory`() {
        // Outage 1: 15s silence
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)

        // Peer returns and starts recovering
        val recoverDecision = policy.onFreshPeerResponse()
        assertTrue(recoverDecision is HeartbeatDecision.StartedRecovering)
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)

        // Complete state sync
        policy.onStateSynchronized()

        // 10 seconds of stable heartbeats (sent every 2s)
        for (i in 1..5) {
            clock.advance(2000L)
            policy.onTick()
            policy.onFreshPeerResponse()
        }

        // Episode 1 is now closed
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertFalse(policy.isEpisodeOpen)
        assertEquals(1, policy.committedEpisodeCount)

        // Outage 2: another 15s silence
        clock.advance(15_000L)
        val outage2 = policy.onTick()
        assertTrue(outage2 is HeartbeatDecision.BecameUnreachable)
        assertEquals(2, (outage2 as HeartbeatDecision.BecameUnreachable).episodeCount)
        assertEquals(2, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)
        assertFalse(policy.isClaimVictoryEligible) // Count 2 is NOT eligible for Claim Victory!
    }

    @Test
    fun `H06 - third separate outage makes manual Claim Victory eligible while unreachable`() {
        // Episode 1
        clock.advance(15_000L)
        policy.onTick()
        policy.onFreshPeerResponse()
        policy.onStateSynchronized()
        clock.advance(10_000L)
        policy.onTick() // closes ep 1

        // Episode 2
        clock.advance(15_000L)
        policy.onTick()
        policy.onFreshPeerResponse()
        policy.onStateSynchronized()
        clock.advance(10_000L)
        policy.onTick() // closes ep 2

        assertEquals(2, policy.committedEpisodeCount)
        assertFalse(policy.isEpisodeOpen)

        // Episode 3
        clock.advance(15_000L)
        val outage3 = policy.onTick()
        assertTrue(outage3 is HeartbeatDecision.BecameUnreachable)
        val becameUnreachable = outage3 as HeartbeatDecision.BecameUnreachable
        assertEquals(3, becameUnreachable.episodeCount)
        assertTrue(becameUnreachable.isClaimEligible)

        assertEquals(3, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)
        assertTrue(policy.isClaimVictoryEligible)

        // Evaluate claim
        val claimResult = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claimResult is ClaimResult.Accepted)
        assertEquals(3, (claimResult as ClaimResult.Accepted).episodeCount)
    }

    @Test
    fun `H07 - return before 15 seconds creates no new incident and no popup`() {
        // Silence for 14.5 seconds
        clock.advance(14_500L)
        val tick = policy.onTick()
        assertEquals(HeartbeatDecision.NoOp, tick)
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)

        // Peer responds
        val response = policy.onFreshPeerResponse()
        assertEquals(HeartbeatDecision.NoOp, response)
        assertEquals(0, policy.committedEpisodeCount)
        assertFalse(policy.isEpisodeOpen)
    }

    @Test
    fun `H08 - return, sync, disappear before 10 stable seconds reopens SAME episode without extra incident`() {
        // Outage 1 triggers
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(1, policy.committedEpisodeCount)
        val episode1Id = policy.currentEpisodeId

        // Peer returns
        policy.onFreshPeerResponse()
        policy.onStateSynchronized()
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)

        // 4 seconds of heartbeats
        clock.advance(4000L)
        policy.onFreshPeerResponse()

        // Disappears for 15 seconds (before 10s stability is reached)
        clock.advance(15_000L)
        val reUnreachable = policy.onTick()

        assertTrue(reUnreachable is HeartbeatDecision.ReopenedUnreachable)
        val re = reUnreachable as HeartbeatDecision.ReopenedUnreachable
        assertEquals(1, re.episodeCount)
        assertEquals(episode1Id, re.episodeId)
        // Episode count must remain 1!
        assertEquals(1, policy.committedEpisodeCount)
        assertTrue(policy.isEpisodeOpen)
    }

    @Test
    fun `H09 - recovery stable-gap greater than 5 seconds restarts stability window`() {
        clock.advance(15_000L)
        policy.onTick()
        policy.onFreshPeerResponse() // recovery starts at T=115,000
        policy.onStateSynchronized()

        // 6 seconds pass with no heartbeat (gap = 6000ms > 5000ms)
        clock.advance(6000L)
        policy.onFreshPeerResponse() // T=121,000 -> gap exceeded, restarts stability window at 121,000!

        // Advance 7 seconds (total 13s from start, but only 7s from restart)
        clock.advance(7000L)
        val tickDecision = policy.onTick()
        // Must NOT be completed yet because restart was at 121,000 and now is 128,000 (7s < 10s)
        assertEquals(HeartbeatDecision.NoOp, tickDecision)
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)

        // Advance 3 more seconds with heartbeats (total 10s from restart)
        clock.advance(3000L)
        val finalTick = policy.onTick()
        assertTrue(finalTick is HeartbeatDecision.RecoveryCompleted)
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)
        assertFalse(policy.isEpisodeOpen)
    }

    @Test
    fun `C01 - claim on incident 1 or 2 is strictly rejected`() {
        // Incident 1
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(1, policy.committedEpisodeCount)

        val claim1 = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claim1 is ClaimResult.Rejected)
        assertEquals("EPISODE_COUNT_LESS_THAN_3", (claim1 as ClaimResult.Rejected).reason)

        // Recover and trigger Incident 2
        policy.onFreshPeerResponse()
        policy.onStateSynchronized()
        clock.advance(10_000L)
        policy.onTick()

        clock.advance(15_000L)
        policy.onTick()
        assertEquals(2, policy.committedEpisodeCount)

        val claim2 = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claim2 is ClaimResult.Rejected)
        assertEquals("EPISODE_COUNT_LESS_THAN_3", (claim2 as ClaimResult.Rejected).reason)
    }

    @Test
    fun `C02 - eligible third incident claim confirmed once succeeds`() {
        // Fast-forward to incident 3
        policy.restoreCheckpoint(savedEpisodeCount = 3, savedEpisodeOpen = true, savedEpisodeId = "ep_3", generation = generation)
        assertTrue(policy.isClaimVictoryEligible)

        val claim = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claim is ClaimResult.Accepted)
        val accepted = claim as ClaimResult.Accepted
        assertEquals("ep_3", accepted.episodeId)
        assertEquals(3, accepted.episodeCount)
    }

    @Test
    fun `C03 - fresh recovery races with claim confirmation and rejects claim`() {
        policy.restoreCheckpoint(savedEpisodeCount = 3, savedEpisodeOpen = true, savedEpisodeId = "ep_3", generation = generation)
        assertTrue(policy.isClaimVictoryEligible)

        // Fresh peer response arrives just before user confirms claim
        policy.onFreshPeerResponse()
        assertEquals(PeerAvailabilityState.RECOVERING, policy.state)
        assertFalse(policy.isClaimVictoryEligible)

        val claim = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claim is ClaimResult.Rejected)
        assertEquals("PEER_NOT_UNREACHABLE", (claim as ClaimResult.Rejected).reason)
    }

    @Test
    fun `H12 - local device loses network sets LOCAL_UNAVAILABLE and rejects claim`() {
        policy.restoreCheckpoint(savedEpisodeCount = 3, savedEpisodeOpen = true, savedEpisodeId = "ep_3", generation = generation)

        policy.onLocalHealthLost()
        assertEquals(PeerAvailabilityState.LOCAL_UNAVAILABLE, policy.state)
        assertFalse(policy.isLocalHealthy)
        assertFalse(policy.isClaimVictoryEligible)

        val claim = policy.evaluateClaimVictory(actorUid = myUid, matchAlreadyFinished = false)
        assertTrue(claim is ClaimResult.Rejected)
        assertEquals("LOCAL_DEVICE_UNHEALTHY", (claim as ClaimResult.Rejected).reason)

        // Restoring local health transitions to SYNCING and resets observation window
        clock.advance(30_000L) // 30s offline
        policy.onLocalHealthRestored()
        assertEquals(PeerAvailabilityState.SYNCING, policy.state)
        assertTrue(policy.isLocalHealthy)

        // Peer is NOT blamed for local downtime
        policy.onTick()
        assertEquals(PeerAvailabilityState.SYNCING, policy.state)
    }

    @Test
    fun `R11 - monotonic clock prevents wall clock time shifts from affecting duration`() {
        // Monotonic clock strictly increments regardless of system time changes
        clock.advance(10_000L)
        policy.onTick()
        assertEquals(PeerAvailabilityState.RESPONSIVE, policy.state)

        // Advancing 5000ms more triggers exactly at 15000ms monotonic
        clock.advance(5000L)
        val tick = policy.onTick()
        assertTrue(tick is HeartbeatDecision.BecameUnreachable)
    }
}
