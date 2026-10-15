package com.bingo.multiplayer.domain.engine

/**
 * Monotonic clock abstraction for time-critical heartbeat and recovery policies.
 * Enables zero-wait, deterministic testing with synthetic clock ticks.
 */
interface Clock {
    fun elapsedRealtime(): Long
}

/**
 * Standard Android implementation delegating to [android.os.SystemClock.elapsedRealtime].
 */
object SystemMonotonicClock : Clock {
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * The 5 required states representing local observation of an online 1v1 match peer.
 */
enum class PeerAvailabilityState {
    /** Startup or local recovery; peer failure cannot yet be judged. */
    SYNCING,
    /** Fresh peer responses verified and state synchronized. */
    RESPONSIVE,
    /** Qualified 15-second silence threshold reached; one open outage episode. */
    UNREACHABLE,
    /** Peer responded again; state synchronization and 10s stability window in progress. */
    RECOVERING,
    /** This local device cannot reliably observe its peer (e.g. backgrounded, broker lost). */
    LOCAL_UNAVAILABLE
}

/**
 * Immutable snapshot of current heartbeat policy state.
 */
data class HeartbeatPolicySnapshot(
    val matchId: String,
    val peerUid: String,
    val connectionGeneration: String,
    val state: PeerAvailabilityState,
    val committedEpisodeCount: Int,
    val currentEpisodeId: String?,
    val isEpisodeOpen: Boolean,
    val lastFreshPeerResponseAt: Long,
    val recoveryStartedAt: Long?,
    val lastRecoveryProbeAt: Long?,
    val isStateSynchronized: Boolean,
    val isClaimVictoryEligible: Boolean,
    val isLocalHealthy: Boolean
)

/**
 * Decision returned by policy transitions for the coordinator/UI to consume.
 */
sealed class HeartbeatDecision {
    object NoOp : HeartbeatDecision()
    data class BecameUnreachable(
        val episodeId: String,
        val episodeCount: Int,
        val isClaimEligible: Boolean
    ) : HeartbeatDecision()
    data class ReopenedUnreachable(
        val episodeId: String,
        val episodeCount: Int,
        val isClaimEligible: Boolean
    ) : HeartbeatDecision()
    data class StartedRecovering(
        val episodeId: String?,
        val episodeCount: Int
    ) : HeartbeatDecision()
    data class RecoveryCompleted(
        val closedEpisodeCount: Int
    ) : HeartbeatDecision()
    object LocalUnavailable : HeartbeatDecision()
    object LocalRestoredSyncing : HeartbeatDecision()
}

/**
 * Result of evaluating an explicit Claim Victory action.
 */
sealed class ClaimResult {
    data class Accepted(val episodeId: String, val episodeCount: Int) : ClaimResult()
    data class Rejected(val reason: String) : ClaimResult()
}

/**
 * Pure Kotlin policy reducer for online 1v1 match heartbeats and recovery.
 *
 * Rules:
 * - Heartbeat interval: 2,500 ms probes (managed by transport scheduler).
 * - Unavailable threshold: 15,000 ms silence since last verified fresh peer response.
 * - Single continuous outage is ONE episode (45s, 5m, etc. never increment repeatedly).
 * - Claim Victory is only eligible on episode >= 3 while peer remains UNREACHABLE and local is healthy.
 * - Recovery requires complete state synchronization + 10,000 ms stable verified heartbeats.
 * - If gap between heartbeats exceeds 5,000 ms during recovery, stability window restarts.
 * - If silence >= 15,000 ms occurs during recovery, reopens the SAME episode (no count increment).
 * - Local unavailability suspends judgments and never penalizes the peer.
 */
class OnlineMatchHeartbeatPolicy(
    val matchId: String,
    val peerUid: String,
    connectionGeneration: String = "",
    private val clock: Clock = SystemMonotonicClock
) {
    companion object {
        const val PROBE_INTERVAL_MS = 2500L
        const val UNAVAILABLE_THRESHOLD_MS = 15000L
        const val RECOVERY_STABILITY_REQUIRED_MS = 10000L
        const val STABLE_HEARTBEAT_MAX_GAP_MS = 5000L
        const val MAX_ACK_CHALLENGE_AGE_MS = 5000L
        const val NORMAL_TURN_DURATION_MS = 30000L
    }

    var connectionGeneration: String = connectionGeneration
        private set

    fun updateConnectionGeneration(newGen: String) {
        connectionGeneration = newGen
    }

    var state: PeerAvailabilityState = PeerAvailabilityState.SYNCING
        private set

    var committedEpisodeCount: Int = 0
        private set

    var currentEpisodeId: String? = null
        private set

    var isEpisodeOpen: Boolean = false
        private set

    var lastFreshPeerResponseAt: Long = 0L
        private set

    var recoveryStartedAt: Long? = null
        private set

    var lastRecoveryProbeAt: Long? = null
        private set

    var isStateSynchronized: Boolean = false
        private set

    var isClaimVictoryEligible: Boolean = false
        private set

    var isLocalHealthy: Boolean = true
        private set

    /**
     * Initializes observation on handshake or connection establishment.
     * Sets initial observation time so an unset time is never treated as infinitely old.
     */
    fun initializeObservation(
        generation: String,
        initialObservationTime: Long = clock.elapsedRealtime()
    ) {
        connectionGeneration = generation
        lastFreshPeerResponseAt = initialObservationTime
        state = PeerAvailabilityState.RESPONSIVE
        isLocalHealthy = true
        isStateSynchronized = true
        isClaimVictoryEligible = false
    }

    /**
     * Restores state from a durable checkpoint on app launch/rejoin before evaluating eligibility.
     */
    fun restoreCheckpoint(
        savedEpisodeCount: Int,
        savedEpisodeOpen: Boolean,
        savedEpisodeId: String?,
        generation: String,
        observationTime: Long = clock.elapsedRealtime()
    ) {
        committedEpisodeCount = savedEpisodeCount
        isEpisodeOpen = savedEpisodeOpen
        currentEpisodeId = savedEpisodeId
        connectionGeneration = generation
        lastFreshPeerResponseAt = observationTime
        isLocalHealthy = true
        isStateSynchronized = true
        if (isEpisodeOpen) {
            state = PeerAvailabilityState.UNREACHABLE
            isClaimVictoryEligible = (committedEpisodeCount >= 3)
        } else {
            state = PeerAvailabilityState.RESPONSIVE
            isClaimVictoryEligible = false
        }
    }

    /**
     * Periodic watchdog tick (evaluated at least every 250 ms).
     */
    fun onTick(now: Long = clock.elapsedRealtime()): HeartbeatDecision {
        if (!isLocalHealthy) {
            return HeartbeatDecision.NoOp
        }

        when (state) {
            PeerAvailabilityState.RESPONSIVE -> {
                val silence = now - lastFreshPeerResponseAt
                if (silence >= UNAVAILABLE_THRESHOLD_MS) {
                    state = PeerAvailabilityState.UNREACHABLE
                    if (!isEpisodeOpen) {
                        isEpisodeOpen = true
                        committedEpisodeCount++
                        currentEpisodeId = "ep_${matchId}_${committedEpisodeCount}_$now"
                    }
                    isClaimVictoryEligible = (committedEpisodeCount >= 3)
                    return HeartbeatDecision.BecameUnreachable(
                        episodeId = currentEpisodeId!!,
                        episodeCount = committedEpisodeCount,
                        isClaimEligible = isClaimVictoryEligible
                    )
                }
            }

            PeerAvailabilityState.RECOVERING -> {
                val silence = now - lastFreshPeerResponseAt
                if (silence >= UNAVAILABLE_THRESHOLD_MS) {
                    // Outage recurred before stability requirement was met.
                    // Reopen the warning for the SAME episode; do NOT increment incident count!
                    state = PeerAvailabilityState.UNREACHABLE
                    isEpisodeOpen = true
                    recoveryStartedAt = null
                    lastRecoveryProbeAt = null
                    isClaimVictoryEligible = (committedEpisodeCount >= 3)
                    return HeartbeatDecision.ReopenedUnreachable(
                        episodeId = currentEpisodeId ?: "ep_${matchId}_${committedEpisodeCount}_$now",
                        episodeCount = committedEpisodeCount,
                        isClaimEligible = isClaimVictoryEligible
                    )
                }

                // Check stability requirement
                val recoveryStart = recoveryStartedAt ?: now
                if (isStateSynchronized && (now - recoveryStart >= RECOVERY_STABILITY_REQUIRED_MS)) {
                    state = PeerAvailabilityState.RESPONSIVE
                    val closedCount = committedEpisodeCount
                    isEpisodeOpen = false
                    currentEpisodeId = null
                    recoveryStartedAt = null
                    lastRecoveryProbeAt = null
                    isClaimVictoryEligible = false
                    return HeartbeatDecision.RecoveryCompleted(closedEpisodeCount = closedCount)
                }
            }

            PeerAvailabilityState.UNREACHABLE -> {
                // Continued silence remains in UNREACHABLE without repeatedly incrementing count.
                isClaimVictoryEligible = (committedEpisodeCount >= 3)
            }

            PeerAvailabilityState.SYNCING -> {
                // Startup or local sync; wait for peer response or handshake.
            }

            PeerAvailabilityState.LOCAL_UNAVAILABLE -> {
                // Device cannot judge peer.
            }
        }

        return HeartbeatDecision.NoOp
    }

    /**
     * Called when a verified fresh peer ACK/response arrives for a challenge issued within 5,000 ms.
     */
    fun onFreshPeerResponse(now: Long = clock.elapsedRealtime()): HeartbeatDecision {
        if (!isLocalHealthy) {
            return HeartbeatDecision.NoOp
        }

        when (state) {
            PeerAvailabilityState.UNREACHABLE -> {
                // First verified fresh response after silence!
                state = PeerAvailabilityState.RECOVERING
                lastFreshPeerResponseAt = now
                recoveryStartedAt = now
                lastRecoveryProbeAt = now
                isClaimVictoryEligible = false // Immediately disable claim victory!
                return HeartbeatDecision.StartedRecovering(
                    episodeId = currentEpisodeId,
                    episodeCount = committedEpisodeCount
                )
            }

            PeerAvailabilityState.RECOVERING -> {
                val prevProbe = lastRecoveryProbeAt ?: now
                val gap = now - prevProbe
                if (gap > STABLE_HEARTBEAT_MAX_GAP_MS) {
                    // Gap exceeded 5,000 ms! Restart the 10-second stability window.
                    recoveryStartedAt = now
                }
                lastRecoveryProbeAt = now
                lastFreshPeerResponseAt = now

                // Check if stability is now reached
                val recoveryStart = recoveryStartedAt ?: now
                if (isStateSynchronized && (now - recoveryStart >= RECOVERY_STABILITY_REQUIRED_MS)) {
                    state = PeerAvailabilityState.RESPONSIVE
                    val closedCount = committedEpisodeCount
                    isEpisodeOpen = false
                    currentEpisodeId = null
                    recoveryStartedAt = null
                    lastRecoveryProbeAt = null
                    isClaimVictoryEligible = false
                    return HeartbeatDecision.RecoveryCompleted(closedEpisodeCount = closedCount)
                }
            }

            PeerAvailabilityState.RESPONSIVE,
            PeerAvailabilityState.SYNCING -> {
                state = PeerAvailabilityState.RESPONSIVE
                lastFreshPeerResponseAt = now
            }

            PeerAvailabilityState.LOCAL_UNAVAILABLE -> {
                // Local device is marked unavailable; response ignored until local health restored.
            }
        }

        return HeartbeatDecision.NoOp
    }

    /**
     * Marks that state synchronization with the peer has completed.
     */
    fun onStateSynchronized(now: Long = clock.elapsedRealtime()): HeartbeatDecision {
        isStateSynchronized = true
        if (state == PeerAvailabilityState.RECOVERING) {
            val recoveryStart = recoveryStartedAt ?: now
            if (now - recoveryStart >= RECOVERY_STABILITY_REQUIRED_MS) {
                state = PeerAvailabilityState.RESPONSIVE
                val closedCount = committedEpisodeCount
                isEpisodeOpen = false
                currentEpisodeId = null
                recoveryStartedAt = null
                lastRecoveryProbeAt = null
                isClaimVictoryEligible = false
                return HeartbeatDecision.RecoveryCompleted(closedEpisodeCount = closedCount)
            }
        }
        return HeartbeatDecision.NoOp
    }

    /**
     * Marks that state synchronization is pending / invalid.
     */
    fun onStateDesynchronized() {
        isStateSynchronized = false
    }

    /**
     * Local transport/network lost, app backgrounded, or broker disconnected.
     * Suspends peer failure judgments; does NOT penalize peer.
     */
    fun onLocalHealthLost(): HeartbeatDecision {
        isLocalHealthy = false
        state = PeerAvailabilityState.LOCAL_UNAVAILABLE
        isClaimVictoryEligible = false
        return HeartbeatDecision.LocalUnavailable
    }

    /**
     * Local transport restored and room re-subscribed.
     * Resets observation window to prevent counting our offline time against the peer.
     */
    fun onLocalHealthRestored(now: Long = clock.elapsedRealtime()): HeartbeatDecision {
        isLocalHealthy = true
        state = PeerAvailabilityState.SYNCING
        isStateSynchronized = false
        lastFreshPeerResponseAt = now
        recoveryStartedAt = null
        lastRecoveryProbeAt = null
        isClaimVictoryEligible = false
        return HeartbeatDecision.LocalRestoredSyncing
    }

    /**
     * Evaluates explicit Claim Victory action.
     */
    fun evaluateClaimVictory(
        actorUid: String,
        matchAlreadyFinished: Boolean
    ): ClaimResult {
        if (actorUid.isBlank() || actorUid == peerUid) {
            return ClaimResult.Rejected("INVALID_ACTOR_UID")
        }
        if (matchAlreadyFinished) {
            return ClaimResult.Rejected("MATCH_ALREADY_FINISHED")
        }
        if (!isLocalHealthy || state == PeerAvailabilityState.LOCAL_UNAVAILABLE) {
            return ClaimResult.Rejected("LOCAL_DEVICE_UNHEALTHY")
        }
        if (state != PeerAvailabilityState.UNREACHABLE) {
            return ClaimResult.Rejected("PEER_NOT_UNREACHABLE")
        }
        if (committedEpisodeCount < 3) {
            return ClaimResult.Rejected("EPISODE_COUNT_LESS_THAN_3")
        }
        if (!isEpisodeOpen || currentEpisodeId == null) {
            return ClaimResult.Rejected("NO_OPEN_EPISODE")
        }
        return ClaimResult.Accepted(
            episodeId = currentEpisodeId!!,
            episodeCount = committedEpisodeCount
        )
    }

    /**
     * Helper to test if a claim victory action would currently succeed.
     */
    fun canClaimVictory(actorUid: String = "local_player"): Boolean {
        return evaluateClaimVictory(actorUid, false) is ClaimResult.Accepted
    }

    /**
     * Executes claim victory evaluation with sensible defaults.
     */
    fun claimVictory(actorUid: String = "local_player", matchAlreadyFinished: Boolean = false): ClaimResult {
        return evaluateClaimVictory(actorUid, matchAlreadyFinished)
    }

    /**
     * Creates an immutable snapshot of the current state.
     */
    fun toSnapshot(): HeartbeatPolicySnapshot {
        return HeartbeatPolicySnapshot(
            matchId = matchId,
            peerUid = peerUid,
            connectionGeneration = connectionGeneration,
            state = state,
            committedEpisodeCount = committedEpisodeCount,
            currentEpisodeId = currentEpisodeId,
            isEpisodeOpen = isEpisodeOpen,
            lastFreshPeerResponseAt = lastFreshPeerResponseAt,
            recoveryStartedAt = recoveryStartedAt,
            lastRecoveryProbeAt = lastRecoveryProbeAt,
            isStateSynchronized = isStateSynchronized,
            isClaimVictoryEligible = isClaimVictoryEligible,
            isLocalHealthy = isLocalHealthy
        )
    }
}
