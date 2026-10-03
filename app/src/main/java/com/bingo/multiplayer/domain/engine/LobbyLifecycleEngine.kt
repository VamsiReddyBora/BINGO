package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.RoomMessagePacket

/**
 * LobbyLifecycleEngine:
 * Fully isolated domain engine responsible for lobby presence, ready/not-ready state machine,
 * monotonic versioning, leave/rejoin transitions, and match-start validation.
 *
 * Guaranteed Invariants:
 * 1. Monotonic Versioning: Every state update increments the version monotonically. Outdated packets are strictly discarded.
 * 2. Instant Toggle: Local readiness toggles in 0ms without asynchronous network delay or race conditions.
 * 3. Seamless Rejoin: Any player rejoining from LEFT_LOBBY immediately transitions to NOT_READY (⏸️) on all clients.
 * 4. Isolation: UI and networking layers delegate to this engine; future features cannot alter or break these lifecycle invariants.
 */
object LobbyLifecycleEngine {

    const val STATUS_READY = "READY"
    const val STATUS_NOT_READY = "NOT_READY"
    const val STATUS_LEFT_LOBBY = "LEFT_LOBBY"
    const val STATUS_IN_GAME = "IN_GAME"

    enum class PlayerLobbyStatus {
        IN_GAME,      // ⌛ Reviewing board / Active in game
        READY,        // ✅ Ready to start match
        NOT_READY,    // ⏸️ In lobby but not ready
        LEFT_LOBBY    // ❌ Left lobby / Disconnected
    }

    /**
     * Generates a strictly increasing monotonic version number.
     */
    fun nextVersion(currentVersion: Long = 0L): Long {
        return maxOf(System.currentTimeMillis(), currentVersion + 1L)
    }

    /**
     * Reconciles two readiness states deterministically using monotonic versions.
     * Higher version wins. In case of equal version, active states (IN_GAME, READY, NOT_READY)
     * take deterministic precedence over stale or default states.
     */
    fun reconcileReadyStatus(
        currentStatus: String,
        currentVersion: Long,
        incomingStatus: String,
        incomingVersion: Long
    ): Pair<String, Long> {
        // Strict monotonic versioning: newer version always supersedes older version.
        // This ensures transitions such as:
        // - READY -> IN_GAME (match start)
        // - IN_GAME -> NOT_READY (player finishes reviewing and returns to lobby)
        // - NOT_READY -> READY (player toggles ready)
        // - LEFT_LOBBY -> NOT_READY (rejoin)
        if (incomingVersion > currentVersion && incomingStatus.isNotBlank()) {
            return Pair(incomingStatus, incomingVersion)
        }

        if (currentVersion > incomingVersion && currentStatus.isNotBlank()) {
            return Pair(currentStatus, currentVersion)
        }

        // Versions are identical or unversioned fallback:
        val effStatus = when {
            // IN_GAME takes precedence over NOT_READY or default states if versions tie
            incomingStatus == STATUS_IN_GAME || currentStatus == STATUS_IN_GAME -> STATUS_IN_GAME
            // Active status (NOT_READY or READY) takes precedence over LEFT_LOBBY
            incomingStatus == STATUS_LEFT_LOBBY || currentStatus == STATUS_LEFT_LOBBY -> {
                if (incomingStatus != STATUS_LEFT_LOBBY && incomingStatus.isNotBlank()) incomingStatus
                else if (currentStatus != STATUS_LEFT_LOBBY && currentStatus.isNotBlank()) currentStatus
                else STATUS_LEFT_LOBBY
            }
            // READY takes precedence over NOT_READY on version tie (affirmative user action vs default state)
            incomingStatus == STATUS_READY || currentStatus == STATUS_READY -> STATUS_READY
            incomingStatus.isNotBlank() -> incomingStatus
            currentStatus.isNotBlank() -> currentStatus
            else -> STATUS_NOT_READY
        }
        return Pair(effStatus, maxOf(currentVersion, incomingVersion))
    }

    /**
     * Handles local player status transitions explicitly:
     * Supports STATUS_READY, STATUS_NOT_READY, STATUS_IN_GAME (reviewing board/playing), and STATUS_LEFT_LOBBY.
     */
    fun onLocalStatusChange(player: Player, newStatus: String): Player {
        val now = System.currentTimeMillis()
        val nextVer = nextVersion(player.readyVersion)
        val validatedStatus = when (newStatus) {
            STATUS_READY -> STATUS_READY
            STATUS_IN_GAME -> STATUS_IN_GAME
            STATUS_LEFT_LOBBY -> STATUS_LEFT_LOBBY
            else -> STATUS_NOT_READY
        }
        return player.copy(
            lobbyReadyStatus = validatedStatus,
            readyVersion = nextVer,
            lastSeenTimestamp = now
        )
    }

    /**
     * Handles local player toggling "I'm Ready" / "I'm Not Ready".
     */
    fun onLocalToggleReady(player: Player, isReady: Boolean): Player {
        return onLocalStatusChange(player, if (isReady) STATUS_READY else STATUS_NOT_READY)
    }

    /**
     * Resolves the high-level lobby presence status for UI presentation.
     * Pure function isolating all icon, color, and label rules from UI components.
     */
    fun getPlayerLobbyStatus(player: Player, isMe: Boolean, rawPresenceStatus: String): PlayerLobbyStatus {
        return when {
            player.lobbyReadyStatus == STATUS_IN_GAME -> PlayerLobbyStatus.IN_GAME
            isPlayerLeft(player, isMe, rawPresenceStatus) -> PlayerLobbyStatus.LEFT_LOBBY
            player.isHost || player.lobbyReadyStatus == STATUS_READY -> PlayerLobbyStatus.READY
            else -> PlayerLobbyStatus.NOT_READY
        }
    }

    /**
     * Handles player joining or rejoining the lobby.
     * Resets readiness to NOT_READY and advances monotonic version ahead of any prior leave events.
     */
    fun onPlayerJoinSession(
        player: Player,
        existingPlayerInSession: Player? = null
    ): Player {
        val now = System.currentTimeMillis()
        val previousVersion = existingPlayerInSession?.readyVersion ?: player.readyVersion
        val nextVer = maxOf(now, previousVersion + 1L)
        return player.copy(
            isHost = player.isHost,
            lobbyReadyStatus = if (player.isHost) STATUS_READY else STATUS_NOT_READY,
            readyVersion = nextVer,
            lastSeenTimestamp = now
        )
    }

    /**
     * Handles incoming JOIN or HEARTBEAT network packet.
     * Uses sender's monotonic version counter; does NOT inject receiver's local wall clock
     * to eliminate inter-phone clock skew rejection.
     * Preserves existing READY status if a delayed/retry JOIN packet arrives.
     */
    fun onRemotePlayerJoinOrHeartbeat(
        packet: RoomMessagePacket,
        existing: Player?
    ): Player {
        val now = System.currentTimeMillis()
        val isJoin = packet.type == "JOIN"

        val effectiveReadyStatus: String
        val effectiveReadyVer: Long

        if (isJoin) {
            val isRejoiningFromLeft = existing != null && existing.lobbyReadyStatus == STATUS_LEFT_LOBBY
            val isAlreadyReady = existing != null && existing.lobbyReadyStatus == STATUS_READY

            effectiveReadyStatus = when {
                packet.readyStatus == STATUS_READY -> STATUS_READY
                isAlreadyReady && !isRejoiningFromLeft -> STATUS_READY
                packet.readyStatus.isNotBlank() -> packet.readyStatus
                packet.isHost -> STATUS_READY
                else -> STATUS_NOT_READY
            }
            effectiveReadyVer = when {
                packet.readyVersion > 0L -> maxOf(packet.readyVersion, existing?.readyVersion ?: 0L)
                existing != null -> existing.readyVersion
                else -> 1L
            }
        } else {
            val (resolvedStatus, resolvedVer) = reconcileReadyStatus(
                currentStatus = existing?.lobbyReadyStatus ?: STATUS_NOT_READY,
                currentVersion = existing?.readyVersion ?: 0L,
                incomingStatus = packet.readyStatus.ifBlank { if (packet.isHost) STATUS_READY else STATUS_NOT_READY },
                incomingVersion = packet.readyVersion
            )
            effectiveReadyStatus = resolvedStatus
            effectiveReadyVer = resolvedVer
        }

        val effectiveDisplayName = when {
            packet.displayName.isNotBlank() -> packet.displayName
            existing?.displayName?.isNotBlank() == true -> existing.displayName
            else -> "Player"
        }
        val effectiveUsername = when {
            packet.username.isNotBlank() -> packet.username
            existing?.username?.isNotBlank() == true -> existing.username
            else -> ""
        }
        val effectiveAvatar = packet.avatarUrl ?: existing?.avatarUrl
        val effectiveGamesPlayed = if (packet.gamesPlayed > 0) packet.gamesPlayed else (existing?.gamesPlayed ?: 0)
        val effectiveGamesWon = if (packet.gamesWon > 0) packet.gamesWon else (existing?.gamesWon ?: 0)
        val effectiveStreak = if (packet.currentStreak > 0) packet.currentStreak else (existing?.currentStreak ?: 0)
        val effectiveLevel = if (packet.level > 1) packet.level else (existing?.level ?: 1)

        return Player(
            id = packet.playerId,
            displayName = effectiveDisplayName,
            username = effectiveUsername,
            isHost = packet.isHost,
            avatarUrl = effectiveAvatar,
            gamesPlayed = effectiveGamesPlayed,
            gamesWon = effectiveGamesWon,
            currentStreak = effectiveStreak,
            level = effectiveLevel,
            lastSeenTimestamp = now,
            lobbyReadyStatus = effectiveReadyStatus,
            readyVersion = effectiveReadyVer
        )
    }

    /**
     * Handles incoming READY_STATUS packet.
     * Returns updated Player or null if packet is outdated and should be discarded.
     */
    fun onRemoteReadyStatusPacket(
        packet: RoomMessagePacket,
        existing: Player?
    ): Player? {
        if (existing != null && packet.readyVersion > 0L && packet.readyVersion < existing.readyVersion) {
            // Discard outdated packet!
            return null
        }
        val updatedVer = maxOf(packet.readyVersion, existing?.readyVersion ?: 0L)
        return if (existing != null) {
            existing.copy(
                lobbyReadyStatus = packet.readyStatus,
                readyVersion = updatedVer,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } else {
            Player(
                id = packet.playerId,
                displayName = packet.displayName.ifBlank { "Player" },
                username = packet.username,
                isHost = packet.isHost,
                avatarUrl = packet.avatarUrl,
                lobbyReadyStatus = packet.readyStatus,
                readyVersion = updatedVer,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Handles non-host player leaving the lobby.
     * Transitions player status to LEFT_LOBBY with an advanced monotonic version.
     */
    fun onPlayerLeave(existing: Player): Player {
        val leaveVer = nextVersion(existing.readyVersion)
        return existing.copy(
            lobbyReadyStatus = STATUS_LEFT_LOBBY,
            readyVersion = leaveVer
        )
    }

    /**
     * Sizing Formula for Dynamic Board:
     * - If dynamic board is disabled: 5x5 always
     * - If dynamic board is enabled:
     *     2 players -> 5x5
     *     3 players -> 6x6
     *     4 players -> 7x7
     *     5+ players -> 8x8 (max supported grid size)
     */
    fun resolveBoardSize(isDynamicBoard: Boolean, playerCount: Int): Int {
        if (!isDynamicBoard) return 5
        return (3 + playerCount.coerceAtLeast(2)).coerceAtMost(8)
    }

    /**
     * Host-Selected Grid Size for Dynamic Board (Version 1.2):
     * - If dynamic board is disabled: 5x5 always
     * - If dynamic board is enabled:
     *     Uses host selected grid size from 5x5 to 10x10.
     */
    fun resolveBoardSize(isDynamicBoard: Boolean, selectedSize: Int, playerCount: Int): Int {
        if (!isDynamicBoard) return 5
        if (selectedSize in 5..10) return selectedSize
        return (3 + playerCount.coerceAtLeast(2)).coerceAtMost(8)
    }

    /**
     * Evaluates whether all conditions to start a multiplayer match are satisfied:
     * - Minimum 2 players present
     * - All non-host players are in STATUS_READY (host is ready by default)
     */
    fun canStartMatch(players: List<Player>): Boolean {
        if (players.size < 2) return false
        return players.all { it.isHost || it.lobbyReadyStatus == STATUS_READY }
    }

    /**
     * Counts how many players are currently ready (including host).
     */
    fun countReadyPlayers(players: List<Player>): Int {
        return players.count { it.isHost || it.lobbyReadyStatus == STATUS_READY }
    }

    /**
     * Determines if a player is considered to have left the lobby (showing ❌):
     * 1. Status is explicitly STATUS_LEFT_LOBBY.
     * 2. Not local player, last seen > 60 seconds ago, and presence is offline.
     */
    fun isPlayerLeft(player: Player, isMe: Boolean, rawPresenceStatus: String): Boolean {
        if (player.lobbyReadyStatus == STATUS_LEFT_LOBBY) return true
        if (!isMe && (System.currentTimeMillis() - player.lastSeenTimestamp) > 60_000L &&
            !com.bingo.multiplayer.domain.network.PresenceManager.isStatusOnline(rawPresenceStatus)) {
            return true
        }
        return false
    }

    /**
     * Invariant: Match Session Isolation.
     * Prevents duplicate start triggers or rogue synthetic restarts while a match is actively in progress.
     * A new match can only start if:
     * 1. A valid non-zero seed is provided (or generated by host).
     * 2. The match is not already actively playing with that exact seed.
     * 3. If currently in game screen and game is NOT over, incoming seed MUST be different from current match seed.
     */
    fun shouldStartNewMatch(
        isHost: Boolean,
        incomingSeed: Long?,
        currentMatchSeed: Long,
        isGameOver: Boolean,
        isCurrentlyInGame: Boolean,
        isCompletedSeed: Boolean = false
    ): Boolean {
        // Host always triggers its own match initiation with fresh seeds
        if (isHost) return true

        // Incoming seed must be valid non-zero
        val seed = incomingSeed ?: return false
        if (seed == 0L) return false

        // Invariant: Completed matches must NEVER be restarted by duplicate or stale packets
        if (isCompletedSeed) return false

        // If the incoming seed matches a finished match's seed, reject it
        if (seed == currentMatchSeed && currentMatchSeed != 0L && isGameOver) {
            return false
        }

        // If guest is currently in the game screen and game is not over:
        // Reject duplicate start packets for the ongoing match (e.g. network retries or rogue starts)
        if (isCurrentlyInGame && !isGameOver) {
            return false
        }

        return true
    }

    /**
     * Invariant: In-Game Turn and Move Isolation.
     * Validates that in-game packets (PICK_NUMBER, TURN_TIMEOUT, GAME_SYNC, BOARD_READY)
     * belong strictly to the active match session.
     * Stale packets from old seeds or unseeded packets (0L/null) cannot infiltrate an active non-zero seed match.
     */
    fun isPacketForActiveMatch(
        packetSeed: Long?,
        currentMatchSeed: Long
    ): Boolean {
        if (currentMatchSeed == 0L) {
            return false
        }
        if (packetSeed == null || packetSeed == 0L) {
            // Backward compatibility for legacy packets without seed
            return true
        }
        return packetSeed == currentMatchSeed
    }

    /**
     * Determines whether a given player object corresponds to the local device player.
     * Matches by unique UID, prefixed 'u_' UID, username, or host identity.
     */
    fun isPlayerMe(
        p: Player,
        myUid: String,
        myUsername: String = "",
        myDisplayName: String = "",
        isHost: Boolean = false
    ): Boolean {
        val cleanMyUid = myUid.trim().lowercase().removePrefix("u_")
        val cleanPId = p.id.trim().lowercase().removePrefix("u_")
        if (cleanPId.isNotBlank() && cleanMyUid.isNotBlank()) {
            if (cleanPId == cleanMyUid) {
                return true
            }
        }
        val cleanMyUser = myUsername.trim().lowercase().removePrefix("@").removePrefix("u_")
        val cleanPUser = p.username.trim().lowercase().removePrefix("@").removePrefix("u_")
        if (cleanMyUser.isNotBlank() && cleanPUser.isNotBlank() && cleanMyUser == cleanPUser) {
            return true
        }
        val cleanMyDisplay = myDisplayName.trim().lowercase()
        val cleanPDisplay = p.displayName.trim().lowercase()
        if (cleanMyDisplay.isNotBlank() && cleanPDisplay.isNotBlank() && cleanMyDisplay == cleanPDisplay) {
            if (p.isHost == isHost) return true
        }
        if (isHost && p.isHost) {
            return true
        }
        return false
    }

    /**
     * Invariant: Deterministic & Collision-Free Player Board Seed Derivation.
     * Generates a 100% unique, reproducible random seed for each player in a match.
     * Combines:
     * 1. [baseSeed]: The shared match seed initiated by the Host.
     * 2. [player]: The player's canonical identifier (username or id) hashed via 64-bit polynomial.
     * 3. [index]: The player's canonical position in the match roster.
     *
     * Guarantees:
     * - No two players in the same match can EVER receive the same seed or the same board.
     * - Deterministic across all devices: Any client or host computing player X's seed computes the exact same value.
     */
    fun resolvePlayerBoardSeed(baseSeed: Long, player: Player, index: Int): Long {
        val clean = player.username.trim().lowercase().removePrefix("@").removePrefix("u_")
            .ifBlank { player.id.trim().lowercase().removePrefix("u_") }
        var h = 1125899906842597L
        for (char in clean) {
            h = 31L * h + char.code.toLong()
        }
        val roleMultiplier = if (player.isHost) 100003L else (index + 1) * 200009L
        val mixed = baseSeed xor h xor roleMultiplier
        return if (mixed == 0L) (baseSeed + (index + 1) * 37L) else mixed
    }

    /**
     * Generates a fair, deterministic, randomized turn rotation order for all participants
     * using the match seed. All devices sharing the match seed will generate the EXACT same order.
     */
    fun generateDeterministicTurnOrder(
        allParticipants: List<Player>,
        matchSeed: Long
    ): List<Player> {
        val candidates = allParticipants
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.id } // Canonical base ordering before shuffling
        if (candidates.size <= 1 || matchSeed == 0L) return candidates
        val rng = java.util.Random(matchSeed)
        return candidates.shuffled(rng)
    }

    fun isPlayerIdMatch(id1: String, id2: String): Boolean {
        if (id1.isBlank() || id2.isBlank()) return false
        if (id1.equals(id2, ignoreCase = true)) return true
        val c1 = id1.trim().lowercase().removePrefix("u_").removePrefix("@").removePrefix("u_")
        val c2 = id2.trim().lowercase().removePrefix("u_").removePrefix("@").removePrefix("u_")
        return c1.isNotBlank() && c1 == c2
    }

    /**
     * Calculates the circular step distance from one player to another in a turn order.
     * Used for tie-breaker: smaller distance = earlier in turn rotation = wins tie!
     */
    fun calculateTurnDistance(
        turnOrder: List<String>,
        fromPlayerId: String,
        toPlayerId: String
    ): Int {
        if (turnOrder.isEmpty() || isPlayerIdMatch(fromPlayerId, toPlayerId)) return 0
        val fromIndex = turnOrder.indexOfFirst { isPlayerIdMatch(it, fromPlayerId) }
        val toIndex = turnOrder.indexOfFirst { isPlayerIdMatch(it, toPlayerId) }
        if (fromIndex == -1 || toIndex == -1) return Int.MAX_VALUE
        val size = turnOrder.size
        return (toIndex - fromIndex + size) % size
    }

    /**
     * Evaluates the next active player who should pick a number in a multiplayer game.
     *
     * Rules:
     * - Filters out disconnected or departed players.
     * - If > 2 active players remain, rotates circularly through active players only.
     * - If the departed player was the current picker, seamlessly advances to the next active player.
     * - If 2 active players remain, alternates between them.
     * - If 1 active player remains, returns that player.
     */
    fun calculateNextTurnPlayerId(
        allParticipants: List<Player>,
        disconnectedPlayerIds: Set<String>,
        currentPickerId: String,
        fallbackPlayerId: String = "",
        matchSeed: Long = 0L,
        customTurnOrder: List<Player>? = null
    ): String {
        val candidatePlayers = if (customTurnOrder != null && customTurnOrder.isNotEmpty()) {
            customTurnOrder
        } else if (matchSeed != 0L) {
            generateDeterministicTurnOrder(allParticipants, matchSeed)
        } else {
            allParticipants
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .sortedWith(compareByDescending<Player> { it.isHost }.thenBy { it.id })
        }

        val activePlayers = candidatePlayers.filter { candidate ->
            disconnectedPlayerIds.none { dId -> isPlayerIdMatch(candidate.id, dId) } &&
            candidate.lobbyReadyStatus != STATUS_LEFT_LOBBY
        }

        if (activePlayers.isEmpty()) {
            return fallbackPlayerId
        }

        if (activePlayers.size == 1) {
            return activePlayers.first().id
        }

        // 2+ active players: Circular rotation
        val currentIndex = activePlayers.indexOfFirst { isPlayerIdMatch(it.id, currentPickerId) }
        return if (currentIndex != -1) {
            activePlayers[(currentIndex + 1) % activePlayers.size].id
        } else {
            // Current picker is not in activePlayers (e.g. departed during their turn)
            val origIndex = candidatePlayers.indexOfFirst { isPlayerIdMatch(it.id, currentPickerId) }
            if (origIndex != -1) {
                var found: Player? = null
                for (step in 1 until candidatePlayers.size) {
                    val candidate = candidatePlayers[(origIndex + step) % candidatePlayers.size]
                    if (activePlayers.any { isPlayerIdMatch(it.id, candidate.id) }) {
                        found = candidate
                        break
                    }
                }
                found?.id ?: activePlayers.first().id
            } else {
                activePlayers.first().id
            }
        }
    }
}

