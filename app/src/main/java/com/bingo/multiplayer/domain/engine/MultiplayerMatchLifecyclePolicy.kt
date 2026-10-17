package com.bingo.multiplayer.domain.engine

/**
 * State representing guest observation of the authoritative match Host.
 */
enum class HostAbsenceState {
    /** Host active, heartbeats arriving normally within the last 10,000 ms. */
    NORMAL,
    /**
     * Silent buffer: host has been silent for 0..10,000 ms.
     * Gameplay continues seamlessly with zero UI interruptions.
     */
    SILENT_BUFFER,
    /**
     * Silence exceeded 10,000 ms.
     * Gameplay freezes and guests display a 50s countdown banner.
     */
    HOLD,
    /** Host returned while in HOLD: 3-second ready countdown (3... 2... 1... ▶️) active. */
    RETURNING,
    /** Host was absent for 60,000 ms total (10s buffer + 50s countdown expired). Match terminates. */
    TERMINATED
}

/**
 * Decisions and events emitted by the multiplayer lifecycle policy.
 */
sealed class LifecycleAction {
    object NoOp : LifecycleAction()
    data class HostAbsenceHold(val secondsRemaining: Int) : LifecycleAction()
    data class HostReturningCountdown(val secondsRemaining: Int) : LifecycleAction()
    object HostResumed : LifecycleAction()
    object HostTerminated : LifecycleAction()

    data class PauseTick(val secondsRemaining: Int, val canExtend: Boolean) : LifecycleAction()
    data class PauseResumingCountdown(val secondsRemaining: Int) : LifecycleAction()
    object PauseAutoResumed : LifecycleAction()

    data class TurnPlayerInactive(val playerId: String, val autoSkipSecondsRemaining: Int, val turnNumber: Int = -1) : LifecycleAction()
    data class TurnAutoSkipped(val playerId: String, val turnNumber: Int = -1) : LifecycleAction()
}

/**
 * Pure Kotlin policy engine for multiplayer (3-8 players) match lifecycle,
 * refereeing, host absence management, friendly pause system, and player rotation.
 */
class MultiplayerMatchLifecyclePolicy(
    val matchId: String,
    val localUid: String,
    val isHost: Boolean,
    val hostUid: String,
    initialParticipants: List<String>,
    private val clock: Clock = SystemMonotonicClock,
    initialHostHoldRemainingSeconds: Int? = null
) {
    companion object {
        const val HOST_SILENT_BUFFER_MS = 10_000L
        const val HOST_TOTAL_ABSENCE_MS = 60_000L // 10s silent buffer + 50s countdown = 1 min
        const val PAUSE_DURATION_MS = 30_000L
        const val PAUSE_EXTEND_THRESHOLD_SECONDS = 5
        const val READY_COUNTDOWN_SECONDS = 3
        const val PEER_INACTIVITY_THRESHOLD_MS = 10_000L
        const val HOST_DECISION_AUTO_SKIP_MS = 10_000L
    }

    var hostAbsenceState: HostAbsenceState = HostAbsenceState.NORMAL
        private set

    var lastHostHeartbeatAt: Long = clock.elapsedRealtime()
        private set

    var hostReturnCountdownRemaining: Int? = null
        private set

    var hostReturnDeadlineAt: Long = 0L
        private set

    // Pause state (Authoritative on Host, mirrored to guests)
    var isMatchPaused: Boolean = false
        private set

    var pauseStartedAt: Long = 0L
        private set

    var pauseDeadlineAt: Long = 0L
        private set

    var pauseResumeCountdownRemaining: Int? = null
        private set

    var pauseResumeDeadlineAt: Long = 0L
        private set

    var lastAppliedPauseExtensionTimestamp: Long = 0L
        private set

    var lastAppliedPauseTimestamp: Long = 0L
        private set

    // Monotonic host control revision; pause, extension and resume each advance it.
    var hasVersionedPauseControl: Boolean = false
        private set

    var pauseEpisode: Int = 0
        private set

    var lastAppliedPauseEpisode: Int = 0
        private set

    // Turn Inactivity State (Authoritative on Host)
    var currentTurnPlayerId: String = ""
        private set

    var currentTurnNumber: Int = -1
        private set

    var turnPlayerInactiveSince: Long? = null
        private set

    var isTurnInactivityDecisionActive: Boolean = false
        private set

    var turnInactivityDecisionDeadlineAt: Long = 0L
        private set

    // Rosters
    val activeParticipants: MutableSet<String> = initialParticipants.toMutableSet()
    val disconnectedPlayerIds: MutableSet<String> = mutableSetOf()
    val kickedOrEliminatedPlayerIds: MutableSet<String> = mutableSetOf()
    val surrenderedPlayerIds: MutableSet<String> = mutableSetOf()
    val peerLastSeenAt: MutableMap<String, Long> = mutableMapOf()

    init {
        val now = clock.elapsedRealtime()
        initialParticipants.forEach { pid ->
            peerLastSeenAt[pid] = now
        }
        if (initialHostHoldRemainingSeconds != null && !isHost) {
            val remSec = initialHostHoldRemainingSeconds.coerceIn(0, 50)
            if (remSec <= 0) {
                hostAbsenceState = HostAbsenceState.TERMINATED
                lastHostHeartbeatAt = now - HOST_TOTAL_ABSENCE_MS
            } else {
                hostAbsenceState = HostAbsenceState.HOLD
                lastHostHeartbeatAt = now - (HOST_TOTAL_ABSENCE_MS - (remSec * 1000L))
            }
        }
    }

    /**
     * Current remaining seconds for host hold banner (50s down to 0s).
     */
    val hostHoldRemainingSeconds: Int
        get() {
            if (hostAbsenceState != HostAbsenceState.HOLD) return 0
            val now = clock.elapsedRealtime()
            val silence = now - lastHostHeartbeatAt
            val remainingMs = (HOST_TOTAL_ABSENCE_MS - silence).coerceAtLeast(0L)
            return ((remainingMs + 999L) / 1000L).coerceIn(0L, 50L).toInt()
        }

    /**
     * Current remaining seconds for pause countdown (30s down to 0s).
     */
    val pauseRemainingSeconds: Int
        get() {
            if (!isMatchPaused) return 0
            val now = clock.elapsedRealtime()
            val remainingMs = (pauseDeadlineAt - now).coerceAtLeast(0L)
            return ((remainingMs + 999L) / 1000L).toInt()
        }

    val pauseRemainingMillis: Long
        get() = if (isMatchPaused) (pauseDeadlineAt - clock.elapsedRealtime()).coerceAtLeast(0L) else 0L

    /**
     * Whether the Extend (+30s) button should be visible (only at <= 5s remaining).
     */
    val canExtendPause: Boolean
        get() = isMatchPaused && pauseResumeCountdownRemaining == null && pauseRemainingSeconds in 1..PAUSE_EXTEND_THRESHOLD_SECONDS

    /**
     * Records a fresh heartbeat received from the room Host.
     */
    @Synchronized
    fun onHostHeartbeat(now: Long = clock.elapsedRealtime()): LifecycleAction {
        val silence = now - lastHostHeartbeatAt
        if (silence >= HOST_TOTAL_ABSENCE_MS || hostAbsenceState == HostAbsenceState.TERMINATED) {
            hostAbsenceState = HostAbsenceState.TERMINATED
            return LifecycleAction.HostTerminated
        }
        lastHostHeartbeatAt = now
        peerLastSeenAt[hostUid] = now
        disconnectedPlayerIds.remove(hostUid)

        return when (hostAbsenceState) {
            HostAbsenceState.HOLD -> {
                // Host returned during the 50s hold! Start 3s ready countdown.
                hostAbsenceState = HostAbsenceState.RETURNING
                hostReturnDeadlineAt = now + (READY_COUNTDOWN_SECONDS * 1000L)
                hostReturnCountdownRemaining = READY_COUNTDOWN_SECONDS
                LifecycleAction.HostReturningCountdown(READY_COUNTDOWN_SECONDS)
            }
            HostAbsenceState.RETURNING -> {
                // Still counting down return; derive from monotonic deadline
                val remainingMs = hostReturnDeadlineAt - now
                if (remainingMs <= 0L) {
                    hostReturnCountdownRemaining = null
                    hostReturnDeadlineAt = 0L
                    hostAbsenceState = HostAbsenceState.NORMAL
                    LifecycleAction.HostResumed
                } else {
                    val sec = ((remainingMs + 999L) / 1000L).coerceIn(1L, READY_COUNTDOWN_SECONDS.toLong()).toInt()
                    hostReturnCountdownRemaining = sec
                    LifecycleAction.HostReturningCountdown(sec)
                }
            }
            HostAbsenceState.TERMINATED -> {
                // Already terminated after 1 min; do not resuscitate
                LifecycleAction.HostTerminated
            }
            else -> {
                hostAbsenceState = HostAbsenceState.NORMAL
                LifecycleAction.NoOp
            }
        }
    }

    /**
     * Records a fresh heartbeat or packet from a peer.
     */
    @Synchronized
    fun onPeerHeartbeat(peerUid: String, now: Long = clock.elapsedRealtime()): LifecycleAction {
        if (peerUid in surrenderedPlayerIds) return LifecycleAction.NoOp
        peerLastSeenAt[peerUid] = now
        val wasDisconnected = disconnectedPlayerIds.remove(peerUid)

        if (peerUid == currentTurnPlayerId) {
            // Active turn player is responsive again
            turnPlayerInactiveSince = null
            isTurnInactivityDecisionActive = false
        }

        // Rule 5: If a missing player reconnects during a pause, auto-resume with 3s countdown!
        if (wasDisconnected && isMatchPaused && pauseResumeCountdownRemaining == null) {
            pauseResumeDeadlineAt = now + (READY_COUNTDOWN_SECONDS * 1000L)
            pauseResumeCountdownRemaining = READY_COUNTDOWN_SECONDS
            return LifecycleAction.PauseResumingCountdown(READY_COUNTDOWN_SECONDS)
        }

        return LifecycleAction.NoOp
    }

    /**
     * Called by Host to begin a new turn for [newTurnPlayerId].
     * Idempotent if invoked repeatedly for the same player and turn.
     */
    @Synchronized
    @JvmOverloads
    fun onTurnStarted(
        newTurnPlayerId: String,
        turnNumber: Int = -1,
        forceReset: Boolean = false,
        now: Long = clock.elapsedRealtime()
    ) {
        if (!forceReset && turnNumber != -1 && currentTurnNumber != -1) {
            // Reject stale older turn numbers
            if (turnNumber < currentTurnNumber) {
                return
            }
            // Reject inconsistent players for an already established turn number
            if (turnNumber == currentTurnNumber && currentTurnPlayerId.isNotBlank() && newTurnPlayerId.isNotBlank() && newTurnPlayerId != currentTurnPlayerId) {
                return
            }
        }

        val isSameTurn = !forceReset &&
                newTurnPlayerId.isNotBlank() &&
                newTurnPlayerId == currentTurnPlayerId &&
                (turnNumber == -1 || currentTurnNumber == -1 || turnNumber == currentTurnNumber)

        if (isSameTurn) {
            if (turnNumber != -1) {
                currentTurnNumber = turnNumber
            }
            return
        }

        currentTurnPlayerId = newTurnPlayerId
        currentTurnNumber = turnNumber
        turnPlayerInactiveSince = null
        isTurnInactivityDecisionActive = false
        if (newTurnPlayerId.isNotBlank()) {
            peerLastSeenAt.putIfAbsent(newTurnPlayerId, now)
        }
    }

    /**
     * Periodic watchdog tick (e.g. called every 250ms - 1000ms).
     */
    @Synchronized
    fun onTick(now: Long = clock.elapsedRealtime()): List<LifecycleAction> {
        val actions = mutableListOf<LifecycleAction>()

        // 1. Host Presence Tracking (for guests)
        if (!isHost) {
            val silence = now - lastHostHeartbeatAt
            when (hostAbsenceState) {
                HostAbsenceState.NORMAL,
                HostAbsenceState.SILENT_BUFFER -> {
                    if (silence >= HOST_TOTAL_ABSENCE_MS) {
                        hostAbsenceState = HostAbsenceState.TERMINATED
                        actions.add(LifecycleAction.HostTerminated)
                    } else if (silence >= HOST_SILENT_BUFFER_MS) {
                        hostAbsenceState = HostAbsenceState.HOLD
                        actions.add(LifecycleAction.HostAbsenceHold(hostHoldRemainingSeconds))
                    }
                }
                HostAbsenceState.HOLD -> {
                    if (silence >= HOST_TOTAL_ABSENCE_MS) {
                        hostAbsenceState = HostAbsenceState.TERMINATED
                        actions.add(LifecycleAction.HostTerminated)
                    } else {
                        actions.add(LifecycleAction.HostAbsenceHold(hostHoldRemainingSeconds))
                    }
                }
                HostAbsenceState.RETURNING -> {
                    val remainingMs = hostReturnDeadlineAt - now
                    if (remainingMs <= 0L) {
                        hostReturnCountdownRemaining = null
                        hostReturnDeadlineAt = 0L
                        hostAbsenceState = HostAbsenceState.NORMAL
                        actions.add(LifecycleAction.HostResumed)
                    } else {
                        val sec = ((remainingMs + 999L) / 1000L).coerceIn(1L, READY_COUNTDOWN_SECONDS.toLong()).toInt()
                        hostReturnCountdownRemaining = sec
                        actions.add(LifecycleAction.HostReturningCountdown(sec))
                    }
                }
                HostAbsenceState.TERMINATED -> {
                    // Match terminated
                }
            }
        }

        // 2. Peer Inactivity Tracking
        activeParticipants.forEach { pid ->
            if (pid != localUid && pid !in kickedOrEliminatedPlayerIds) {
                val lastSeen = peerLastSeenAt[pid] ?: now
                if (now - lastSeen >= PEER_INACTIVITY_THRESHOLD_MS) {
                    disconnectedPlayerIds.add(pid)
                }
            }
        }

        // 3. Turn Inactivity Referee (Authoritative on Host)
        if (isHost && !isMatchPaused && currentTurnPlayerId.isNotBlank() && currentTurnPlayerId !in surrenderedPlayerIds) {
            if (currentTurnPlayerId in kickedOrEliminatedPlayerIds) {
                // Eliminated player: instant auto-skip
                actions.add(LifecycleAction.TurnAutoSkipped(currentTurnPlayerId, currentTurnNumber))
            } else if (currentTurnPlayerId != localUid) {
                val isTurnPlayerSilent = disconnectedPlayerIds.contains(currentTurnPlayerId) ||
                    ((now - (peerLastSeenAt[currentTurnPlayerId] ?: now)) >= PEER_INACTIVITY_THRESHOLD_MS)

                if (isTurnPlayerSilent) {
                    if (turnPlayerInactiveSince == null) {
                        turnPlayerInactiveSince = now
                        isTurnInactivityDecisionActive = true
                        turnInactivityDecisionDeadlineAt = now + HOST_DECISION_AUTO_SKIP_MS
                    }

                    val autoSkipRemainingMs = (turnInactivityDecisionDeadlineAt - now).coerceAtLeast(0L)
                    val autoSkipSec = ((autoSkipRemainingMs + 999L) / 1000L).toInt()

                    if (autoSkipRemainingMs <= 0L) {
                        // 10s decision window expired without host choice -> auto-skip!
                        isTurnInactivityDecisionActive = false
                        turnPlayerInactiveSince = null
                        actions.add(LifecycleAction.TurnAutoSkipped(currentTurnPlayerId, currentTurnNumber))
                    } else {
                        actions.add(LifecycleAction.TurnPlayerInactive(currentTurnPlayerId, autoSkipSec, currentTurnNumber))
                    }
                }
            }
        }

        // 4. Pause Timer Management (Authoritative on Host or mirrored)
        if (isMatchPaused) {
            if (pauseResumeDeadlineAt > 0L) {
                val remMs = pauseResumeDeadlineAt - now
                if (remMs <= 0L) {
                    pauseResumeCountdownRemaining = null
                    pauseResumeDeadlineAt = 0L
                    finishPause()
                    actions.add(LifecycleAction.PauseAutoResumed)
                } else {
                    val sec = ((remMs + 999L) / 1000L).coerceIn(1L, READY_COUNTDOWN_SECONDS.toLong()).toInt()
                    pauseResumeCountdownRemaining = sec
                    actions.add(LifecycleAction.PauseResumingCountdown(sec))
                }
            } else {
                val remSec = pauseRemainingSeconds
                if (remSec <= 0) {
                    finishPause()
                    actions.add(LifecycleAction.PauseAutoResumed)
                } else {
                    actions.add(LifecycleAction.PauseTick(remSec, canExtendPause))
                }
            }
        }

        return actions
    }

    private fun advancePauseRevision() {
        pauseEpisode = maxOf(pauseEpisode, lastAppliedPauseEpisode) + 1
        lastAppliedPauseEpisode = pauseEpisode
        hasVersionedPauseControl = true
    }

    private fun finishPause() {
        isMatchPaused = false
        pauseDeadlineAt = 0L
        pauseResumeDeadlineAt = 0L
        pauseResumeCountdownRemaining = null
        // Guests expiring their display timer must not invent host control revisions.
        if (isHost) advancePauseRevision()
    }

    /** Restores the durable control fence without creating a new pause decision. */
    @Synchronized
    fun restorePauseCheckpoint(
        isPaused: Boolean,
        remainingSeconds: Int,
        controlRevision: Int,
        versioned: Boolean,
        timestamp: Long = 0L,
        now: Long = clock.elapsedRealtime()
    ) {
        pauseEpisode = controlRevision.coerceAtLeast(0)
        lastAppliedPauseEpisode = pauseEpisode
        hasVersionedPauseControl = versioned
        lastAppliedPauseTimestamp = timestamp
        lastAppliedPauseExtensionTimestamp = timestamp
        isMatchPaused = isPaused
        pauseStartedAt = if (isPaused) now else 0L
        pauseDeadlineAt = if (isPaused) now + remainingSeconds.coerceIn(0, 30) * 1000L else 0L
        pauseResumeDeadlineAt = 0L
        pauseResumeCountdownRemaining = null
    }

    /** Host pauses the match for the existing 30-second window. */
    @Synchronized
    fun pauseMatch(now: Long = clock.elapsedRealtime()): Boolean {
        if (isMatchPaused) return false
        isMatchPaused = true
        pauseStartedAt = now
        pauseDeadlineAt = now + PAUSE_DURATION_MS
        pauseResumeDeadlineAt = 0L
        pauseResumeCountdownRemaining = null
        isTurnInactivityDecisionActive = false
        turnPlayerInactiveSince = null
        advancePauseRevision()
        return true
    }

    /** Resumes the match, retaining the existing optional ready countdown. */
    @Synchronized
    fun resumeMatch(withCountdown: Boolean = true, now: Long = clock.elapsedRealtime()): Boolean {
        if (!isMatchPaused) return false
        if (withCountdown) {
            pauseResumeDeadlineAt = now + (READY_COUNTDOWN_SECONDS * 1000L)
            pauseResumeCountdownRemaining = READY_COUNTDOWN_SECONDS
        } else {
            finishPause()
        }
        return true
    }

    /** Host extends the pause with a new 30-second window at <= 5s remaining. */
    @Synchronized
    fun extendPause(now: Long = clock.elapsedRealtime()): Boolean {
        if (!canExtendPause) return false
        pauseDeadlineAt = now + PAUSE_DURATION_MS
        pauseResumeDeadlineAt = 0L
        pauseResumeCountdownRemaining = null
        advancePauseRevision()
        return true
    }

    @Synchronized
    @JvmOverloads
    fun applyAuthoritativePauseExtension(
        extensionMs: Long = PAUSE_DURATION_MS,
        now: Long = clock.elapsedRealtime(),
        timestamp: Long = 0L,
        remotePauseEpisode: Int = 0
    ): Boolean {
        if (pauseResumeCountdownRemaining != null) return false
        if (remotePauseEpisode > 0) {
            if (hasVersionedPauseControl && remotePauseEpisode <= lastAppliedPauseEpisode) return false
        } else {
            // Compatibility is timestamp-based only until this match receives versioned control.
            if (hasVersionedPauseControl || !isMatchPaused || (timestamp > 0L &&
                (timestamp <= lastAppliedPauseExtensionTimestamp || timestamp <= lastAppliedPauseTimestamp))) return false
            if (timestamp == 0L) {
                // Original unversioned API: unavailable after any versioned host control.
                pauseDeadlineAt = now + extensionMs.coerceIn(0L, PAUSE_DURATION_MS)
                return true
            }
        }
        val applied = syncRemotePause(true, ((extensionMs + 999L) / 1000L).toInt(), now, timestamp, remotePauseEpisode)
        if (applied) lastAppliedPauseExtensionTimestamp = maxOf(lastAppliedPauseExtensionTimestamp, timestamp)
        return applied
    }

    /**
     * Accept host control in revision order. Equal-revision samples can shorten the
     * local expiry but cannot extend it or reopen a pause already expired locally.
     * Legacy timestamp inference is isolated to matches without versioned control.
     */
    @Synchronized
    @JvmOverloads
    fun syncRemotePause(
        isPaused: Boolean,
        remainingSeconds: Int,
        now: Long = clock.elapsedRealtime(),
        timestamp: Long = 0L,
        remotePauseEpisode: Int = 0
    ): Boolean {
        val explicit = remotePauseEpisode > 0
        val newerRevision = explicit && (!hasVersionedPauseControl || remotePauseEpisode > lastAppliedPauseEpisode)
        if (explicit && hasVersionedPauseControl) {
            if (remotePauseEpisode < lastAppliedPauseEpisode) return false
            if (remotePauseEpisode == lastAppliedPauseEpisode && isPaused != isMatchPaused) return false
        } else if (!explicit && hasVersionedPauseControl) return false
        if (!newerRevision && timestamp > 0L && timestamp <= lastAppliedPauseTimestamp) return false

        val candidateDeadline = now + remainingSeconds.coerceIn(0, 30) * 1000L
        if (isPaused) {
            val legacyExtension = !explicit && timestamp > lastAppliedPauseTimestamp && remainingSeconds > pauseRemainingSeconds
            pauseDeadlineAt = if (!isMatchPaused || newerRevision || legacyExtension) candidateDeadline
                else minOf(pauseDeadlineAt, candidateDeadline)
            if (!isMatchPaused) pauseStartedAt = now
            isMatchPaused = true
            pauseResumeDeadlineAt = 0L
            pauseResumeCountdownRemaining = null
            if (!explicit && (pauseEpisode == 0 || legacyExtension)) {
                pauseEpisode = lastAppliedPauseEpisode + 1
                lastAppliedPauseEpisode = pauseEpisode
            }
        } else {
            isMatchPaused = false
            pauseDeadlineAt = 0L
            pauseStartedAt = 0L
            pauseResumeDeadlineAt = 0L
            pauseResumeCountdownRemaining = null
        }
        if (explicit) {
            hasVersionedPauseControl = true
            pauseEpisode = remotePauseEpisode
            lastAppliedPauseEpisode = remotePauseEpisode
        }
        lastAppliedPauseTimestamp = maxOf(lastAppliedPauseTimestamp, timestamp)
        return true
    }

    /** Explicit departure is permanent for this match and distinct from missed heartbeats. */
    @Synchronized
    fun recordSurrender(playerId: String) {
        if (playerId.isBlank()) return
        surrenderedPlayerIds.add(playerId)
        activeParticipants.remove(playerId)
        disconnectedPlayerIds.add(playerId)
        if (currentTurnPlayerId == playerId) {
            turnPlayerInactiveSince = null
            isTurnInactivityDecisionActive = false
            turnInactivityDecisionDeadlineAt = 0L
        }
    }

    /** A queued watchdog decision must still describe the screen's accepted open turn. */
    fun isCurrentTurnAction(action: LifecycleAction, playerId: String, turnNumber: Int): Boolean =
        when (action) {
            is LifecycleAction.TurnAutoSkipped -> action.playerId == playerId && action.turnNumber == turnNumber &&
                action.playerId !in surrenderedPlayerIds
            is LifecycleAction.TurnPlayerInactive -> action.playerId == playerId && action.turnNumber == turnNumber &&
                action.playerId !in surrenderedPlayerIds
            else -> false
        }

    /**
     * Host kicks / eliminates a player.
     * The player remains in history, but future turns are skipped in 0s.
     */
    @Synchronized
    fun kickOrEliminatePlayer(targetPlayerId: String): Boolean {
        if (targetPlayerId.isBlank() || targetPlayerId == hostUid) return false
        kickedOrEliminatedPlayerIds.add(targetPlayerId)
        disconnectedPlayerIds.add(targetPlayerId)
        return true
    }

    fun isPlayerEliminated(playerId: String): Boolean =
        kickedOrEliminatedPlayerIds.contains(playerId)

    fun isPlayerDisconnected(playerId: String): Boolean =
        disconnectedPlayerIds.contains(playerId)

    /**
     * Returns true if turn for [playerId] should be skipped automatically in 0s.
     */
    fun shouldInstantlySkipTurn(playerId: String): Boolean =
        isPlayerEliminated(playerId)
}
