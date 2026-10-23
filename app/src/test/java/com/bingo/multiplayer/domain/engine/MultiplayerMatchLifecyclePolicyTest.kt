package com.bingo.multiplayer.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiplayerMatchLifecyclePolicyTest {

    private class FakeClock(var currentMs: Long = 100_000L) : Clock {
        override fun elapsedRealtime(): Long = currentMs

        fun advance(ms: Long) {
            currentMs += ms
        }
    }

    private lateinit var clock: FakeClock
    private val hostUid = "host_alice"
    private val guestUid = "guest_bob"
    private val guestCharlie = "guest_charlie"
    private val participants = listOf(hostUid, guestUid, guestCharlie)

    @Before
    fun setup() {
        clock = FakeClock(100_000L)
    }

    // ── Rule 3: Host Absence "10 + 50 = 1 Minute" ──

    @Test
    fun testHostAbsence_silentBuffer_noInterruptionUnder10s() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Advance 5 seconds (5,000 ms)
        clock.advance(5_000L)
        val actions = policy.onTick()
        assertTrue(actions.isEmpty())
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }

    @Test
    fun testHostAbsence_entersHoldAt10s_andCountsDown50s() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Advance 10 seconds -> enters HOLD (50s remaining)
        clock.advance(10_000L)
        val actions10s = policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)
        val holdAction = actions10s.filterIsInstance<LifecycleAction.HostAbsenceHold>().firstOrNull()
        assertEquals(50, holdAction?.secondsRemaining)
        assertEquals(50, policy.hostHoldRemainingSeconds)

        // Advance another 20s (30s total silence) -> 30s remaining
        clock.advance(20_000L)
        val actions30s = policy.onTick()
        val hold30s = actions30s.filterIsInstance<LifecycleAction.HostAbsenceHold>().firstOrNull()
        assertEquals(30, hold30s?.secondsRemaining)
        assertEquals(30, policy.hostHoldRemainingSeconds)
    }

    @Test
    fun testHostAbsence_hostReturnsDuringHold_triggers3sCountdownAndResumes() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Host missing for 25s -> in HOLD
        clock.advance(25_000L)
        policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)

        // Host returns! (Sends heartbeat)
        val returnAction = policy.onHostHeartbeat()
        assertTrue(returnAction is LifecycleAction.HostReturningCountdown)
        assertEquals(3, (returnAction as LifecycleAction.HostReturningCountdown).secondsRemaining)
        assertEquals(HostAbsenceState.RETURNING, policy.hostAbsenceState)

        // Tick 1s -> countdown 2
        clock.advance(1_000L)
        val actions1 = policy.onTick()
        val count2 = actions1.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(2, count2?.secondsRemaining)

        // Tick 1s -> countdown 1
        clock.advance(1_000L)
        val actions2 = policy.onTick()
        val count1 = actions2.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(1, count1?.secondsRemaining)

        // Tick 1s -> Resumed!
        clock.advance(1_000L)
        val actions3 = policy.onTick()
        assertTrue(actions3.any { it is LifecycleAction.HostResumed })
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }

    @Test
    fun testHostAbsence_full60sExpires_terminatesMatch() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Advance 60,000 ms total silence (1 minute)
        clock.advance(60_000L)
        val actions = policy.onTick()
        assertEquals(HostAbsenceState.TERMINATED, policy.hostAbsenceState)
        assertTrue(actions.any { it is LifecycleAction.HostTerminated })
    }

    // ── Rule 4: Smart 30-Second Pause Flow & Extend (+30s) ──

    @Test
    fun testPause_30sCountdown_extendOnlyAvailableAt5sOrLess() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        policy.pauseMatch()
        assertTrue(policy.isMatchPaused)
        assertEquals(30, policy.pauseRemainingSeconds)
        assertFalse("At 30s remaining, extend must be false", policy.canExtendPause)

        // Advance 20s (10s remaining) -> canExtend still false
        clock.advance(20_000L)
        policy.onTick()
        assertEquals(10, policy.pauseRemainingSeconds)
        assertFalse("At 10s remaining, extend must be false", policy.canExtendPause)

        // Advance 5s (5s remaining) -> canExtend becomes TRUE!
        clock.advance(5_000L)
        policy.onTick()
        assertEquals(5, policy.pauseRemainingSeconds)
        assertTrue("At 5s remaining, extend must be true!", policy.canExtendPause)

        // Host clicks Extend (+30s)!
        val extended = policy.extendPause()
        assertTrue(extended)
        assertEquals(30, policy.pauseRemainingSeconds)
        assertFalse("After extend, timer resets to 30s and button hides!", policy.canExtendPause)
    }

    @Test
    fun testPause_expiresAt0s_autoResumesMatch() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        policy.pauseMatch()
        clock.advance(30_000L)
        val actions = policy.onTick()
        assertFalse(policy.isMatchPaused)
        assertTrue(actions.any { it is LifecycleAction.PauseAutoResumed })
    }

    // ── Rule 5: Player Reconnects During Pause ──

    @Test
    fun testPause_missingPlayerReconnects_triggers3sAutoResumeCountdown() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Guest Charlie goes silent for 12s -> marked disconnected
        clock.advance(12_000L)
        policy.onTick()
        assertTrue(policy.isPlayerDisconnected(guestCharlie))

        // Match is paused
        policy.pauseMatch()
        assertTrue(policy.isMatchPaused)

        // Charlie reconnects during pause!
        val action = policy.onPeerHeartbeat(guestCharlie)
        assertTrue(action is LifecycleAction.PauseResumingCountdown)
        assertEquals(3, (action as LifecycleAction.PauseResumingCountdown).secondsRemaining)

        // Countdown ticks to 0 and match resumes
        clock.advance(1_000L)
        val a1 = policy.onTick()
        assertEquals(2, a1.filterIsInstance<LifecycleAction.PauseResumingCountdown>().first().secondsRemaining)

        clock.advance(1_000L)
        val a2 = policy.onTick()
        assertEquals(1, a2.filterIsInstance<LifecycleAction.PauseResumingCountdown>().first().secondsRemaining)

        clock.advance(1_000L)
        val a3 = policy.onTick()
        assertTrue(a3.any { it is LifecycleAction.PauseAutoResumed })
        assertFalse(policy.isMatchPaused)
    }

    // ── Rule 1: Referee & Turn Inactivity ──

    @Test
    fun testReferee_turnPlayerInactive_triggersDecision_andAutoSkipsAt10s() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        policy.onTurnStarted(guestCharlie)

        // Charlie silent for 11s -> triggers Inactive Turn Decision
        clock.advance(11_000L)
        val actions11s = policy.onTick()
        assertTrue(policy.isTurnInactivityDecisionActive)
        val inactiveAction = actions11s.filterIsInstance<LifecycleAction.TurnPlayerInactive>().firstOrNull()
        assertNotNull(inactiveAction)
        assertEquals(guestCharlie, inactiveAction?.playerId)
        assertEquals(10, inactiveAction?.autoSkipSecondsRemaining)

        // Advance 10s without host action -> Auto-skips!
        clock.advance(10_000L)
        val actions21s = policy.onTick()
        assertFalse(policy.isTurnInactivityDecisionActive)
        val skipAction = actions21s.filterIsInstance<LifecycleAction.TurnAutoSkipped>().firstOrNull()
        assertNotNull(skipAction)
        assertEquals(guestCharlie, skipAction?.playerId)
    }

    // ── Rule 6: Elimination Policy (Kick / Eliminate) ──

    @Test
    fun testElimination_kickedPlayerTurnsSkippedInstantly() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Host kicks Charlie
        val kicked = policy.kickOrEliminatePlayer(guestCharlie)
        assertTrue(kicked)
        assertTrue(policy.isPlayerEliminated(guestCharlie))
        assertTrue(policy.shouldInstantlySkipTurn(guestCharlie))

        // When Charlie's turn starts, ticks immediately auto-skip
        policy.onTurnStarted(guestCharlie)
        val actions = policy.onTick()
        val autoSkip = actions.filterIsInstance<LifecycleAction.TurnAutoSkipped>().firstOrNull()
        assertNotNull(autoSkip)
        assertEquals(guestCharlie, autoSkip?.playerId)
    }

    // ── Rule 2: Non-Turn Players Dropping Off ──

    @Test
    fun testNonTurnPlayerDropsOff_markedDisconnectedWithoutInterruption() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // It is Bob's turn. Bob is active and sending heartbeats.
        policy.onTurnStarted(guestUid)

        // Charlie drops off (> 10s silence), but Bob continues sending heartbeats
        clock.advance(6_000L)
        policy.onPeerHeartbeat(guestUid)
        clock.advance(6_000L)
        policy.onPeerHeartbeat(guestUid)
        val actions = policy.onTick()

        // Charlie is marked disconnected
        assertTrue(policy.isPlayerDisconnected(guestCharlie))
        // Bob is active, so NO TurnInactivity or AutoSkip is triggered!
        assertTrue(actions.none { it is LifecycleAction.TurnPlayerInactive })
        assertTrue(actions.none { it is LifecycleAction.TurnAutoSkipped })
    }

    // ── R1 Regression: Active Host Must Not Lose Turn Due to Remote Heartbeat Silence ──

    @Test
    fun testActiveHost_waitingFullTurn_doesNotTriggerTurnInactivityOrAutoSkip() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Turn starts for the host
        policy.onTurnStarted(hostUid)

        // Remote guests keep sending heartbeats every 2 seconds
        for (i in 1..15) { // 30 seconds total
            clock.advance(2_000L)
            policy.onPeerHeartbeat(guestUid)
            policy.onPeerHeartbeat(guestCharlie)
            val actions = policy.onTick()

            // Active host must never be marked inactive or auto-skipped
            assertTrue("At ${i * 2}s, host must not receive TurnPlayerInactive",
                actions.none { it is LifecycleAction.TurnPlayerInactive })
            assertTrue("At ${i * 2}s, host must not receive TurnAutoSkipped",
                actions.none { it is LifecycleAction.TurnAutoSkipped })
        }
    }

    @Test
    fun testActiveHost_laterTurns_doesNotTriggerTurnInactivity() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Turn 1: Host plays
        policy.onTurnStarted(hostUid)
        clock.advance(5_000L)
        policy.onPeerHeartbeat(guestUid)
        policy.onPeerHeartbeat(guestCharlie)
        policy.onTick()

        // Turn 2: Bob plays (15s pass)
        policy.onTurnStarted(guestUid)
        for (i in 1..5) {
            clock.advance(3_000L)
            policy.onPeerHeartbeat(guestUid)
            policy.onPeerHeartbeat(guestCharlie)
            policy.onTick()
        }

        // Turn 3: Rotates back to Host!
        policy.onTurnStarted(hostUid)
        for (i in 1..10) { // 20s pass on host turn
            clock.advance(2_000L)
            policy.onPeerHeartbeat(guestUid)
            policy.onPeerHeartbeat(guestCharlie)
            val actions = policy.onTick()

            assertTrue("Later host turn must not be marked inactive",
                actions.none { it is LifecycleAction.TurnPlayerInactive })
            assertTrue("Later host turn must not be auto-skipped",
                actions.none { it is LifecycleAction.TurnAutoSkipped })
        }
    }

    // ── R7 Regression: 3-Second Ready Countdown Runs for Exactly 3 Real Seconds At Any Tick Rate ──

    @Test
    fun testReadyCountdown_monotonicExact3Seconds_at500msTicks() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Host in HOLD
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)

        // Host returns -> ready countdown initiated at 3s
        val ret = policy.onHostHeartbeat()
        assertTrue(ret is LifecycleAction.HostReturningCountdown)
        assertEquals(3, (ret as LifecycleAction.HostReturningCountdown).secondsRemaining)

        // At 500ms: still 3s (2.5s remaining, ceil=3)
        clock.advance(500L)
        val a500 = policy.onTick()
        val c500 = a500.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(3, c500?.secondsRemaining)

        // At 1000ms: 2s (2.0s remaining)
        clock.advance(500L)
        val a1000 = policy.onTick()
        val c1000 = a1000.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(2, c1000?.secondsRemaining)

        // At 1500ms: still 2s (1.5s remaining, ceil=2)
        clock.advance(500L)
        val a1500 = policy.onTick()
        val c1500 = a1500.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(2, c1500?.secondsRemaining)

        // At 2000ms: 1s (1.0s remaining)
        clock.advance(500L)
        val a2000 = policy.onTick()
        val c2000 = a2000.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(1, c2000?.secondsRemaining)

        // At 2500ms: still 1s (0.5s remaining, ceil=1)
        clock.advance(500L)
        val a2500 = policy.onTick()
        val c2500 = a2500.filterIsInstance<LifecycleAction.HostReturningCountdown>().firstOrNull()
        assertEquals(1, c2500?.secondsRemaining)

        // At 3000ms: Resumed!
        clock.advance(500L)
        val a3000 = policy.onTick()
        assertTrue("Must resume after exactly 3.0 real seconds",
            a3000.any { it is LifecycleAction.HostResumed })
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }

    @Test
    fun testReadyCountdown_monotonicExact3Seconds_at100msTicks() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        clock.advance(15_000L)
        policy.onTick()
        policy.onHostHeartbeat()

        // 30 ticks of 100ms = 3000ms
        for (i in 1..29) {
            clock.advance(100L)
            val actions = policy.onTick()
            assertFalse("Must NOT resume before 3000ms at tick $i",
                actions.any { it is LifecycleAction.HostResumed })
        }

        // 30th tick reaches 3000ms
        clock.advance(100L)
        val finalActions = policy.onTick()
        assertTrue("Must resume on 3000ms",
            finalActions.any { it is LifecycleAction.HostResumed })
    }

    @Test
    fun testReadyCountdown_repeatedHeartbeats_doNotAccelerateOrResetCountdown() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        clock.advance(15_000L)
        policy.onTick()
        policy.onHostHeartbeat()

        // Advance 1 second, send repeated heartbeats
        clock.advance(1_000L)
        policy.onHostHeartbeat()
        policy.onHostHeartbeat()
        val a1 = policy.onTick()
        assertEquals(2, a1.filterIsInstance<LifecycleAction.HostReturningCountdown>().first().secondsRemaining)

        // Advance another 1 second with more heartbeats
        clock.advance(1_000L)
        policy.onHostHeartbeat()
        val a2 = policy.onTick()
        assertEquals(1, a2.filterIsInstance<LifecycleAction.HostReturningCountdown>().first().secondsRemaining)

        // Final second completes
        clock.advance(1_000L)
        val a3 = policy.onTick()
        assertTrue(a3.any { it is LifecycleAction.HostResumed })
    }

    // ── R5 Regression: Authoritative Pause Extension ──

    @Test
    fun testAuthoritativePauseExtension_acceptedRegardlessOfLocalRemainingSeconds() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        policy.syncRemotePause(isPaused = true, remainingSeconds = 30)
        // Guest local clock has 6 seconds remaining (> 5s threshold)
        clock.advance(24_000L)
        policy.onTick()
        assertEquals(6, policy.pauseRemainingSeconds)
        assertFalse(policy.canExtendPause)

        // Authoritative extension arrives from host
        policy.applyAuthoritativePauseExtension()
        assertEquals(30, policy.pauseRemainingSeconds)
        assertTrue(policy.isMatchPaused)
    }

    // ── R4 Regression: Host Self-Elimination and Blank Elimination Rejection ──

    @Test
    fun testElimination_rejectsHostOrBlankTarget() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Cannot eliminate the host
        assertFalse(policy.kickOrEliminatePlayer(hostUid))
        assertFalse(policy.isPlayerEliminated(hostUid))

        // Cannot eliminate blank
        assertFalse(policy.kickOrEliminatePlayer(""))
        assertFalse(policy.kickOrEliminatePlayer("   "))

        // Valid guest elimination succeeds
        assertTrue(policy.kickOrEliminatePlayer(guestCharlie))
        assertTrue(policy.isPlayerEliminated(guestCharlie))
    }

    // ── F1 Verification: Authoritative Pause Extension Duplicate & Unpaused Rejection ──

    @Test
    fun testAuthoritativePauseExtension_rejectsUnpausedAndDuplicateTimestamps() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Cannot extend when not paused
        assertFalse(policy.isMatchPaused)
        assertFalse(policy.applyAuthoritativePauseExtension(timestamp = 1000L))

        // Start pause
        policy.syncRemotePause(isPaused = true, remainingSeconds = 30)
        assertTrue(policy.isMatchPaused)

        // Advance 20 seconds -> 10s remaining
        clock.advance(20_000L)
        assertEquals(10, policy.pauseRemainingSeconds)

        // Authoritative extension with timestamp 1000 succeeds
        assertTrue(policy.applyAuthoritativePauseExtension(timestamp = 1000L))
        assertEquals(30, policy.pauseRemainingSeconds)

        // Duplicate with same timestamp 1000 is rejected
        assertFalse(policy.applyAuthoritativePauseExtension(timestamp = 1000L))

        // Obsolete with older timestamp 500 is rejected
        assertFalse(policy.applyAuthoritativePauseExtension(timestamp = 500L))

        // Newer extension with timestamp 2000 succeeds
        clock.advance(10_000L)
        assertEquals(20, policy.pauseRemainingSeconds)
        assertTrue(policy.applyAuthoritativePauseExtension(timestamp = 2000L))
        assertEquals(30, policy.pauseRemainingSeconds)

        // Advance 30 seconds -> pause expires
        clock.advance(30_000L)
        policy.onTick()
        assertFalse(policy.isMatchPaused)

        // Extension after resume/expiry is rejected
        assertFalse(policy.applyAuthoritativePauseExtension(timestamp = 3000L))
        assertFalse(policy.isMatchPaused)
    }

    // ── F2 Verification: Host Heartbeat at Return Deadline Ordering ──

    @Test
    fun testHostHeartbeatAtReturnDeadline_heartbeatThenTick_resolvesNormalAndResumes() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Host silent for 15s -> HOLD
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)

        // Host returns -> starts 3s ready countdown
        val returnAction = policy.onHostHeartbeat()
        assertTrue(returnAction is LifecycleAction.HostReturningCountdown)
        assertEquals(3, (returnAction as LifecycleAction.HostReturningCountdown).secondsRemaining)
        assertEquals(HostAbsenceState.RETURNING, policy.hostAbsenceState)

        // Advance 3 full seconds to the exact deadline
        clock.advance(3_000L)

        // Event order 1: Heartbeat arrives at deadline before next tick
        val deadlineHeartbeatAction = policy.onHostHeartbeat()
        assertTrue(deadlineHeartbeatAction is LifecycleAction.HostResumed)
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
        assertNull(policy.hostReturnCountdownRemaining)

        // Subsequent tick sees policy is already NORMAL and emits NoOp
        val nextTickActions = policy.onTick()
        assertFalse(nextTickActions.any { it is LifecycleAction.HostReturningCountdown })
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }

    @Test
    fun testHostHeartbeatAtReturnDeadline_tickThenHeartbeat_resolvesNormalAndResumes() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_1",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Host silent for 15s -> HOLD
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)

        // Host returns -> starts 3s ready countdown
        policy.onHostHeartbeat()
        assertEquals(HostAbsenceState.RETURNING, policy.hostAbsenceState)

        // Advance 3 full seconds to the exact deadline
        clock.advance(3_000L)

        // Event order 2: Watchdog tick executes at deadline before heartbeat
        val tickActions = policy.onTick()
        assertTrue(tickActions.any { it is LifecycleAction.HostResumed })
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
        assertNull(policy.hostReturnCountdownRemaining)

        // Subsequent heartbeat arrives; sees policy is already NORMAL and returns NoOp
        val nextHeartbeatAction = policy.onHostHeartbeat()
        assertTrue(nextHeartbeatAction is LifecycleAction.NoOp)
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }

    // ── F4 Verification: Durable Pause Wall-Clock Rejoin Calculation ──

    @Test
    fun testRejoinDurablePauseTimeCalculation_20sIntoPause_restores10sRemaining() {
        val startTimeMs = 100_000L
        val pauseDurationMs = 30_000L
        val pauseDeadlineTimestamp = startTimeMs + pauseDurationMs

        // Host pauses at t=100,000ms
        val hostPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = hostUid,
            isHost = true,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )
        hostPolicy.pauseMatch()
        assertEquals(30, hostPolicy.pauseRemainingSeconds)

        // Advance 20 seconds
        clock.advance(20_000L)
        val nowMs = startTimeMs + 20_000L

        // Client rejoins at nowMs
        val remainingMs = pauseDeadlineTimestamp - nowMs
        val remSec = ((remainingMs + 999L) / 1000L).toInt()
        assertEquals(10, remSec)

        val clientPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )
        clientPolicy.syncRemotePause(isPaused = true, remainingSeconds = remSec)

        // Both live host and rejoined client have exactly 10s remaining
        assertEquals(10, hostPolicy.pauseRemainingSeconds)
        assertEquals(10, clientPolicy.pauseRemainingSeconds)
    }

    @Test
    fun testRejoinDurablePauseTimeCalculation_expiredWhileAway_resumesMatch() {
        val startTimeMs = 100_000L
        val pauseDurationMs = 30_000L
        val pauseDeadlineTimestamp = startTimeMs + pauseDurationMs

        // Advance 35 seconds (pause expired 5s ago)
        val nowMs = startTimeMs + 35_000L
        val remainingMs = pauseDeadlineTimestamp - nowMs
        assertTrue(remainingMs <= 0L)

        val isGamePaused = remainingMs > 0L
        val remSec = if (isGamePaused) ((remainingMs + 999L) / 1000L).toInt() else 0
        assertEquals(false, isGamePaused)
        assertEquals(0, remSec)

        val clientPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )
        clientPolicy.syncRemotePause(isPaused = isGamePaused, remainingSeconds = remSec)
        assertFalse(clientPolicy.isMatchPaused)
        assertEquals(0, clientPolicy.pauseRemainingSeconds)
    }

    @Test
    fun testRoutineSameTurnSyncDoesNotResetInactivePlayerDecisionWindow_autoSkipTriggered() {
        // C3: Player B is silent on their turn. Player A sends routine GAME_SYNC every 2.5s.
        // Idempotent onTurnStarted must not reset the 10-second inactive decision window.
        val h = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "host",
            isHost = true,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        h.onTurnStarted("B")
        var skipped = false
        var minDecision = 99

        for (n in 1..60) {
            clock.advance(500L)
            if (n % 5 == 0) {
                h.onPeerHeartbeat("A", clock.elapsedRealtime())
                h.onTurnStarted("B") // routine same-turn sync
            }
            for (action in h.onTick(clock.elapsedRealtime())) {
                if (action is LifecycleAction.TurnAutoSkipped) {
                    skipped = true
                }
                if (action is LifecycleAction.TurnPlayerInactive) {
                    minDecision = minOf(minDecision, action.autoSkipSecondsRemaining)
                }
            }
        }

        assertTrue("AutoSkip must be triggered after 10s decision window despite routine syncs", skipped)
        assertTrue("Decision seconds must count down through the decision window (<= 1s)", minDecision <= 1)
    }

    @Test
    fun testPauseExtensionDeduplication_preservedAcrossRoutineSync() {
        // C4: Routine sync during pause must not reset lastAppliedPauseExtensionTimestamp
        val g = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        g.syncRemotePause(isPaused = true, remainingSeconds = 30)
        clock.advance(25_000L)
        val first = g.applyAuthoritativePauseExtension(30_000L, clock.elapsedRealtime(), timestamp = 1000L)
        assertTrue("First extension should be accepted", first)

        clock.advance(2500L)
        // Routine GAME_SYNC arrives while paused
        g.syncRemotePause(isPaused = true, remainingSeconds = g.pauseRemainingSeconds, now = clock.elapsedRealtime())

        clock.advance(500L)
        // Redelivered duplicate extension packet arrives
        val duplicate = g.applyAuthoritativePauseExtension(30_000L, clock.elapsedRealtime(), timestamp = 1000L)
        assertFalse("Duplicate extension must be rejected after routine sync", duplicate)
        assertTrue(g.pauseRemainingSeconds > 0)
    }

    @Test
    fun testPauseExtensionFromEarlierEpisode_rejectedInNewPauseEpisode() {
        // C4: An extension from an earlier pause episode must be rejected in a new pause episode
        val g = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        // Episode 1
        g.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 1000L)
        val ext1 = g.applyAuthoritativePauseExtension(30_000L, clock.elapsedRealtime(), timestamp = 1025L)
        assertTrue(ext1)

        // Match resumes
        clock.advance(10_000L)
        g.syncRemotePause(isPaused = false, remainingSeconds = 0, now = clock.elapsedRealtime(), timestamp = 1050L)
        assertFalse(g.isMatchPaused)

        // Episode 2 starts at newer timestamp
        clock.advance(50_000L)
        g.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 2000L)
        assertTrue(g.isMatchPaused)

        // Old delayed extension from Episode 1 arrives
        val oldExt = g.applyAuthoritativePauseExtension(30_000L, clock.elapsedRealtime(), timestamp = 1025L)
        assertFalse("Old extension from earlier episode must be rejected", oldExt)

        // Valid new extension for Episode 2 is accepted
        val newExt = g.applyAuthoritativePauseExtension(30_000L, clock.elapsedRealtime(), timestamp = 2025L)
        assertTrue("Valid new extension for current episode must be accepted", newExt)
    }

    // ── D1: Stale & Inconsistent Turn Snapshot Rejection ──

    @Test
    fun testStaleTurnSnapshot_rejectedWhenOlderOrInconsistentPlayer() {
        val hostPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "host",
            isHost = true,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        // Host starts turn 4 for "host"
        clock.advance(15_000L)
        hostPolicy.onTurnStarted(newTurnPlayerId = "host", turnNumber = 4, forceReset = true)
        hostPolicy.onPeerHeartbeat("A")
        assertEquals(4, hostPolicy.currentTurnNumber)
        assertEquals("host", hostPolicy.currentTurnPlayerId)

        // Delayed guest snapshot arrives claiming turn 2 belonged to "B"
        hostPolicy.onTurnStarted(newTurnPlayerId = "B", turnNumber = 2, forceReset = false)
        // Must be rejected: turn number and player remain unchanged
        assertEquals(4, hostPolicy.currentTurnNumber)
        assertEquals("host", hostPolicy.currentTurnPlayerId)

        // Advance clock and check no bogus skip is emitted for "B"
        hostPolicy.onTick()
        clock.advance(10_000L)
        hostPolicy.onPeerHeartbeat("A")
        val actions = hostPolicy.onTick()
        val skippedForB = actions.filterIsInstance<LifecycleAction.TurnAutoSkipped>().any { it.playerId == "B" }
        assertFalse("Must not auto-skip B based on stale snapshot", skippedForB)

        // Also test inconsistent player for the SAME turn 4: packet claims turn 4 is "B" instead of "host"
        hostPolicy.onTurnStarted(newTurnPlayerId = "B", turnNumber = 4, forceReset = false)
        assertEquals(4, hostPolicy.currentTurnNumber)
        assertEquals("host", hostPolicy.currentTurnPlayerId)
    }

    // ── D3: Duplicate GAME_PAUSED / Snapshot Does Not Reset Pause Deadline ──

    @Test
    fun testDuplicateGamePaused_doesNotRestartPauseDeadline() {
        val guestPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        val packetTimestamp = 1000L
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = packetTimestamp)
        assertEquals(30, guestPolicy.pauseRemainingSeconds)

        // Advance 10 seconds: remaining should be 20s
        clock.advance(10_000L)
        val before = guestPolicy.pauseRemainingSeconds
        assertEquals(20, before)

        // Duplicate GAME_PAUSED packet arrives with identical timestamp
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = packetTimestamp)
        val after = guestPolicy.pauseRemainingSeconds
        assertEquals("Duplicate GAME_PAUSED with same timestamp must not restart pause duration", 20, after)

        // Even un-timestamped snapshot (timestamp=0) while paused must not push deadline out
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 0L)
        assertEquals("Passive snapshot must not push deadline into future", 20, guestPolicy.pauseRemainingSeconds)
    }

    // ── E2: Missed Pause Extension Recovered by Newer Host Snapshot ──

    @Test
    fun testMissedExtensionRecoveredByNewerHostSnapshot() {
        val hostPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "host",
            isHost = true,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )
        val guestPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        // Initial pause: 30 seconds
        val initialTimestamp = 1000L
        hostPolicy.pauseMatch(now = clock.elapsedRealtime())
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = initialTimestamp)
        assertEquals(30, guestPolicy.pauseRemainingSeconds)
        assertEquals(30, hostPolicy.pauseRemainingSeconds)

        // Advance 25 seconds: guest has 5s remaining (now = 125_000)
        clock.advance(25_000L)
        assertEquals(5, guestPolicy.pauseRemainingSeconds)
        assertEquals(5, hostPolicy.pauseRemainingSeconds)

        // Host extends pause at timestamp 26000
        val hostExtended = hostPolicy.extendPause(now = clock.elapsedRealtime())
        assertTrue("Host must successfully extend pause", hostExtended)
        val extensionTimestamp = 26_000L

        // Advance 1s: host has 29s left, guest has 4s left (now = 126_000)
        clock.advance(1_000L)
        val hostRemaining = hostPolicy.pauseRemainingSeconds
        assertEquals(29, hostRemaining)
        assertEquals(4, guestPolicy.pauseRemainingSeconds)

        // Guest MISSED the PAUSE_EXTEND packet.
        // Instead, guest receives newer host GAME_SYNC snapshot at timestamp 27000
        val hostSnapshotTimestamp = 27_000L
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = hostRemaining, now = clock.elapsedRealtime(), timestamp = hostSnapshotTimestamp)

        // Guest MUST recover the extension to 29 seconds!
        assertEquals("Guest must recover pause extension from newer authoritative host snapshot", 29, guestPolicy.pauseRemainingSeconds)

        // Delayed PAUSE_EXTEND packet arrives with older timestamp 26000 -> must be rejected
        val lateExtensionApplied = guestPolicy.applyAuthoritativePauseExtension(
            extensionMs = 30_000L,
            now = clock.elapsedRealtime(),
            timestamp = extensionTimestamp
        )
        assertFalse("Delayed PAUSE_EXTEND after newer snapshot must be rejected as obsolete", lateExtensionApplied)
        assertEquals("Pause remaining seconds must not be altered by obsolete extension", 29, guestPolicy.pauseRemainingSeconds)

        // Advance 4 seconds: without fix, guest would have auto-resumed here!
        clock.advance(4_000L)
        guestPolicy.onHostHeartbeat(clock.elapsedRealtime())
        val actions = guestPolicy.onTick(clock.elapsedRealtime())
        assertTrue("Guest must still be paused after original 30s deadline elapsed", guestPolicy.isMatchPaused)
        assertEquals(25, guestPolicy.pauseRemainingSeconds)
        assertEquals(25, hostPolicy.pauseRemainingSeconds)
        assertFalse("Must not auto-resume guest", actions.any { it is LifecycleAction.PauseAutoResumed })
    }

    @Test
    fun testDuplicateSnapshotAfterExtensionRecovery_doesNotDoubleExtend() {
        val guestPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("host", "A", "B"),
            clock = clock
        )

        // Guest paused with 4s left
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 1000L)
        clock.advance(26_000L)
        assertEquals(4, guestPolicy.pauseRemainingSeconds)

        // Snapshot carries extension: 29s at timestamp 27000
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 29, now = clock.elapsedRealtime(), timestamp = 27_000L)
        assertEquals(29, guestPolicy.pauseRemainingSeconds)

        // Advance 2s: remaining = 27s
        clock.advance(2_000L)
        assertEquals(27, guestPolicy.pauseRemainingSeconds)

        // Duplicate snapshot arrives with same timestamp 27000 and remaining 29
        guestPolicy.syncRemotePause(isPaused = true, remainingSeconds = 29, now = clock.elapsedRealtime(), timestamp = 27_000L)
        assertEquals("Duplicate snapshot must not reset pause countdown", 27, guestPolicy.pauseRemainingSeconds)
    }

    // ── F2: Pause Extension Recovery without Threshold Heuristics (Probes: 4s, 7s, 8s, 12s, 20s) ──

    @Test
    fun testPauseExtensionRecovery_probeWith8SecondsRemainingOnGuest() {
        // Reproduces Round6ReviewProbe extension(4)
        val hostPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "host",
            isHost = true,
            hostUid = "host",
            initialParticipants = listOf("A", "host", "B"),
            clock = clock
        )
        val guestPolicy = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("A", "host", "B"),
            clock = clock
        )

        // Host pauses at t=100_000
        hostPolicy.pauseMatch(clock.elapsedRealtime())
        assertEquals(1, hostPolicy.pauseEpisode)

        // Guest receives initial pause with 4s delivery delay at t=104_000
        clock.advance(4_000L)
        guestPolicy.syncRemotePause(
            isPaused = true,
            remainingSeconds = 30,
            now = clock.elapsedRealtime(),
            timestamp = 1000L,
            remotePauseEpisode = 1
        )
        assertEquals(30, guestPolicy.pauseRemainingSeconds)
        assertEquals(1, guestPolicy.pauseEpisode)

        // Advance to t=125_000 (host has 5s remaining, guest has 9s remaining)
        clock.advance(21_000L)
        assertEquals(5, hostPolicy.pauseRemainingSeconds)
        assertEquals(9, guestPolicy.pauseRemainingSeconds)

        // Host extends pause at t=125_000 (episode becomes 2)
        val extended = hostPolicy.extendPause(clock.elapsedRealtime())
        assertTrue("Host must successfully extend pause", extended)
        assertEquals(2, hostPolicy.pauseEpisode)
        assertEquals(30, hostPolicy.pauseRemainingSeconds)

        // 1 second later at t=126_000:
        clock.advance(1_000L)
        val hostRemaining = hostPolicy.pauseRemainingSeconds
        assertEquals(29, hostRemaining)
        val guestRemainingBeforeSync = guestPolicy.pauseRemainingSeconds
        assertEquals(8, guestRemainingBeforeSync) // Guest has 8s remaining! (Would fail < 7s heuristic)

        // Guest receives authoritative GAME_SYNC snapshot with remaining=29 and episode=2
        guestPolicy.syncRemotePause(
            isPaused = true,
            remainingSeconds = hostRemaining,
            now = clock.elapsedRealtime(),
            timestamp = 27_000L,
            remotePauseEpisode = hostPolicy.pauseEpisode
        )

        // Guest MUST have recovered extension to 29 seconds!
        assertEquals("Guest must recover pause extension even with 8 seconds remaining", 29, guestPolicy.pauseRemainingSeconds)
        assertEquals(2, guestPolicy.pauseEpisode)

        // Verify guest does NOT expire when the original 8 seconds elapse
        clock.advance(8_000L)
        guestPolicy.onHostHeartbeat(clock.elapsedRealtime())
        val actions = guestPolicy.onTick(clock.elapsedRealtime())
        assertTrue("Guest must remain paused", guestPolicy.isMatchPaused)
        assertEquals(21, guestPolicy.pauseRemainingSeconds)
        assertFalse("Must not auto-resume guest", actions.any { it is LifecycleAction.PauseAutoResumed })
    }

    @Test
    fun testPauseExtensionRecovery_recoversAcrossVariousGuestCountdowns_4s_7s_8s_12s_20s() {
        val testCountdowns = listOf(4, 7, 8, 12, 20)
        for (guestLeft in testCountdowns) {
            val localClock = FakeClock(100_000L)
            val guest = MultiplayerMatchLifecyclePolicy(
                matchId = "42",
                localUid = "A",
                isHost = false,
                hostUid = "host",
                initialParticipants = listOf("A", "host", "B"),
                clock = localClock
            )

            // Guest starts pause with 30s
            guest.syncRemotePause(isPaused = true, remainingSeconds = 30, now = localClock.elapsedRealtime(), timestamp = 1000L)
            // Advance clock so guest has `guestLeft` seconds remaining
            localClock.advance((30 - guestLeft) * 1000L)
            assertEquals("Guest countdown should be $guestLeft", guestLeft, guest.pauseRemainingSeconds)

            // Host snapshot arrives with remainingSeconds = 28 (extension occurred on host)
            guest.syncRemotePause(
                isPaused = true,
                remainingSeconds = 28,
                now = localClock.elapsedRealtime(),
                timestamp = 25_000L,
                remotePauseEpisode = 2
            )

            assertEquals("Guest must recover extension to 28s when countdown was $guestLeft", 28, guest.pauseRemainingSeconds)
            assertEquals(2, guest.pauseEpisode)
        }
    }

    @Test
    fun testPauseExtension_explicitEpisodeRejectsObsoleteOrOlderPackets() {
        val guest = MultiplayerMatchLifecyclePolicy(
            matchId = "42",
            localUid = "A",
            isHost = false,
            hostUid = "host",
            initialParticipants = listOf("A", "host", "B"),
            clock = clock
        )

        // Initial pause episode = 1
        guest.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 1000L, remotePauseEpisode = 1)
        clock.advance(25_000L)
        assertEquals(5, guest.pauseRemainingSeconds)

        // Episode = 2 applied
        guest.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 26_000L, remotePauseEpisode = 2)
        assertEquals(30, guest.pauseRemainingSeconds)
        assertEquals(2, guest.pauseEpisode)

        // Advance 5s (remaining = 25s)
        clock.advance(5_000L)
        assertEquals(25, guest.pauseRemainingSeconds)

        // Obsolete packet arrives with episode = 1 and remaining = 10
        guest.syncRemotePause(isPaused = true, remainingSeconds = 10, now = clock.elapsedRealtime(), timestamp = 15_000L, remotePauseEpisode = 1)
        assertEquals("Obsolete episode 1 must be ignored when episode 2 is active", 25, guest.pauseRemainingSeconds)

        // Duplicate packet with episode = 2 and remaining = 30 arrives
        guest.syncRemotePause(isPaused = true, remainingSeconds = 30, now = clock.elapsedRealtime(), timestamp = 26_000L, remotePauseEpisode = 2)
        assertEquals("Duplicate episode 2 must not overwrite active countdown", 25, guest.pauseRemainingSeconds)
    }

    @Test
    fun testTransferHostAuthority_actingHostBecomesHostAndCancelsHoldWatchdog() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_group",
            localUid = guestUid,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Advance 15s - enters HOLD because original host is silent
        clock.advance(15_000L)
        policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)

        // Original host left, transfer host authority to guestUid (local player)
        policy.transferHostAuthority(guestUid)

        assertTrue(policy.isHost)
        assertEquals(guestUid, policy.hostUid)
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
        assertTrue(policy.isPlayerEliminated(hostUid))

        // Next tick as host should NOT trigger guest hold or termination actions
        clock.advance(65_000L)
        val actions = policy.onTick()
        assertFalse(actions.any { it is LifecycleAction.HostTerminated })
        assertFalse(actions.any { it is LifecycleAction.HostAbsenceHold })
    }

    @Test
    fun testHostDeparture_whenHostInSurrenderedList_doesNotTriggerHostAbsenceWatchdog() {
        val policy = MultiplayerMatchLifecyclePolicy(
            matchId = "m_group",
            localUid = guestCharlie,
            isHost = false,
            hostUid = hostUid,
            initialParticipants = participants,
            clock = clock
        )

        // Transfer host authority to guestUid (Bob)
        policy.transferHostAuthority(guestUid)

        assertFalse(policy.isHost)
        assertEquals(guestUid, policy.hostUid)
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)

        // Bob sends heartbeat
        policy.onHostHeartbeat()

        // Advance 5 seconds - no hold
        clock.advance(5_000L)
        val actions = policy.onTick()
        assertTrue(actions.isEmpty())
        assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
    }
}
