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
            effectiveReadyStatus = packet.readyStatus.ifBlank {
                if (packet.isHost) STATUS_READY else STATUS_NOT_READY
            }
            effectiveReadyVer = maxOf(now, packet.readyVersion, (existing?.readyVersion ?: 0L) + 1L)
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
        if (!isMe && (System.currentTimeMillis() - player.lastSeenTimestamp) > 60_000L && rawPresenceStatus.equals("offline", ignoreCase = true)) {
            return true
        }
        return false
    }
}
