package com.bingo.multiplayer.domain.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * High-speed Micro-Payload Codec for multiplayer Bingo moves:
 * Encodes in-game moves into 15-30 byte micro-strings, bypassing verbose JSON overhead.
 * Falls back to JSON for infrequent game setup packets.
 */
object FastPacketCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val compactJson = Json { encodeDefaults = false }
    private fun encodeCompact(packet: RoomMessagePacket): String {
        val fields = compactJson.encodeToJsonElement(RoomMessagePacket.serializer(), packet).jsonObject.toMutableMap()
        // A dynamic constructor default must never replace the original send time on decode.
        fields["timestamp"] = JsonPrimitive(packet.timestamp)
        return JsonObject(fields).toString()
    }

    fun encode(packet: RoomMessagePacket): String {
        if (packet.type == "LOBBY_TYPING") return encodeCompact(packet)
        if (packet.nearbyControl != null) return encodeCompact(packet)
        if (packet.clockOffsetMillis != 0L) return encodeCompact(packet)
        if (packet.lobbySessionId.isNotBlank() && packet.type in setOf("READY_STATUS", "HEARTBEAT")) return encodeCompact(packet)
        return when (packet.type) {
            "PICK_NUMBER" -> {
                if (packet.senderPlayerId.isNotBlank() || packet.closedTurnNumber > 0) {
                    encodeCompact(packet)
                } else if (packet.winnerPlayerId.isNotBlank() || packet.turnIds.isNotEmpty()) {
                    json.encodeToString(packet)
                } else {
                    val historyStr = packet.pickedHistory.joinToString(",")
                    val pickedByStr = packet.pickedByHistory.joinToString(",")
                    if (packet.boardSize != 5) {
                        "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}|${packet.senderInstanceId}|$pickedByStr|${packet.boardHash}|${packet.boardSize}"
                    } else if (packet.senderInstanceId.isNotBlank() || packet.pickedByHistory.isNotEmpty() || packet.boardHash != 0L) {
                        "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}|${packet.senderInstanceId}|$pickedByStr|${packet.boardHash}"
                    } else if (packet.seed != 0L) {
                        "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}"
                    } else {
                        "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr"
                    }
                }
            }
            "TURN_TIMEOUT" -> {
                if (packet.senderPlayerId.isNotBlank() || packet.closedTurnNumber > 0) {
                    encodeCompact(packet)
                } else if (packet.turnIds.isNotEmpty()) {
                    json.encodeToString(packet)
                } else {
                    val historyStr = packet.pickedHistory.joinToString(",")
                    val pickedByStr = packet.pickedByHistory.joinToString(",")
                    if (packet.senderInstanceId.isNotBlank() || packet.pickedByHistory.isNotEmpty()) {
                        "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}|${packet.senderInstanceId}|$pickedByStr"
                    } else if (packet.seed != 0L) {
                        "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}"
                    } else {
                        "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr"
                    }
                }
            }
            "BOARD_READY" -> {
                if (packet.simplifiedRulesVersion > 0) return encodeCompact(packet)
                val boardStr = packet.pickedHistory.joinToString(",")
                if (packet.senderInstanceId.isNotBlank()) {
                    "B|${packet.playerId}|${packet.seed}|$boardStr|${packet.senderInstanceId}"
                } else {
                    "B|${packet.playerId}|${packet.seed}|$boardStr"
                }
            }
            "PING" -> "G|${packet.playerId}|${packet.pingTimestamp}"
            "PONG" -> "O|${packet.playerId}|${packet.pingTimestamp}"
            "READY_STATUS" -> {
                if (packet.reliablePolicyVersion != 0) {
                    if (listOf(packet.playerId, packet.username, packet.displayName, packet.senderInstanceId).any { '|' in it }) {
                        encodeCompact(packet.copy(avatarUrl = null))
                    } else {
                        "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}|${packet.readyVersion}|${packet.senderInstanceId}|${packet.reliablePolicyVersion}|${packet.hostWaitVersion}|${packet.protocolVersion}"
                    }
                } else if (packet.senderInstanceId.isNotBlank()) {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}|${packet.readyVersion}|${packet.senderInstanceId}"
                } else if (packet.readyVersion > 0L) {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}|${packet.readyVersion}"
                } else if (packet.username.isBlank() && packet.displayName.isBlank()) {
                    "R|${packet.playerId}|${packet.readyStatus}"
                } else {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}"
                }
            }
            "KICK_PLAYER" -> {
                if (packet.seed != 0L || packet.roomCode.isNotBlank() || packet.matchId.isNotBlank()) {
                    "K|${packet.targetPlayerId}|${packet.playerId}|${packet.seed}|${packet.roomCode}|${packet.matchId}"
                } else {
                    "K|${packet.targetPlayerId}|${packet.playerId}"
                }
            }
            // Versioned control must preserve its revision and match identity end to end.
            "PAUSE_EXTEND" -> if (packet.pauseEpisode > 0) json.encodeToString(packet) else
                "PEX|${packet.playerId}|${packet.remainingTurnMillis}|${packet.timestamp}|${packet.seed}|${packet.matchId.ifBlank { if (packet.seed != 0L) packet.seed.toString() else "" }}"
            "HOST_HOLD" -> "HH|${packet.playerId}|${packet.remainingTurnMillis}|${packet.timestamp}|${packet.seed}|${packet.matchId.ifBlank { if (packet.seed != 0L) packet.seed.toString() else "" }}"
            "HOST_RESUMED" -> "HR|${packet.playerId}|${packet.remainingTurnMillis}|${packet.timestamp}|${packet.seed}|${packet.matchId.ifBlank { if (packet.seed != 0L) packet.seed.toString() else "" }}"
            "PLAYER_ELIMINATED" -> "ELIM|${packet.targetPlayerId}|${packet.playerId}|${packet.timestamp}|${packet.seed}|${packet.matchId.ifBlank { if (packet.seed != 0L) packet.seed.toString() else "" }}"
            "START_GAME" -> json.encodeToString(packet)
            "PLAY_AGAIN" -> json.encodeToString(packet)
            "HEARTBEAT" -> {
                if (packet.reliablePolicyVersion != 0 && listOf(packet.playerId, packet.displayName, packet.username, packet.avatarUrl.orEmpty()).any { '|' in it }) {
                    encodeCompact(packet)
                } else if (packet.players.isNotEmpty()) {
                    json.encodeToString(packet)
                } else if (packet.reliablePolicyVersion != 0) {
                    "H|${packet.playerId}|${packet.displayName}|${if (packet.isHost) "1" else "0"}|${packet.timestamp}|${packet.username}|${packet.avatarUrl ?: ""}|${packet.readyStatus}|${packet.readyVersion}|${packet.reliablePolicyVersion}"
                } else if (packet.username.isNullOrEmpty() && packet.avatarUrl.isNullOrEmpty() && (packet.readyStatus.isEmpty() || packet.readyStatus == "NOT_READY") && packet.readyVersion == 0L) {
                    "H|${packet.playerId}|${packet.displayName}|${if (packet.isHost) "1" else "0"}|${packet.timestamp}"
                } else {
                    "H|${packet.playerId}|${packet.displayName}|${if (packet.isHost) "1" else "0"}|${packet.timestamp}|${packet.username}|${packet.avatarUrl ?: ""}|${packet.readyStatus}|${packet.readyVersion}"
                }
            }
            "MATCH_HEARTBEAT" -> {
                "MHB|${packet.protocolVersion}|${packet.matchId}|${packet.heartbeatAction}|${packet.playerId}|${packet.peerUid}|${packet.connectionGeneration}|${packet.sequence}|${packet.challengeId}|${packet.timestamp}"
            }
            "LOBBY_CAPS_REQUEST", "LOBBY_CAPS_RESPONSE", "MULTIPLAYER_PRESENCE_REQUEST", "MULTIPLAYER_PRESENCE_RESPONSE" -> encodeCompact(packet)
            else -> json.encodeToString(packet)
        }
    }

    fun decode(payload: String): RoomMessagePacket {
        val trimmed = payload.trim()
        return try {
            when {
                trimmed.startsWith("P|") -> {
                    val parts = trimmed.split("|")
                    val number = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val playerId = parts.getOrNull(2) ?: ""
                    val turnNumber = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    val currentTurnId = parts.getOrNull(4) ?: ""
                    val historyRaw = parts.getOrNull(5) ?: ""
                    val seed = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val senderInstanceId = parts.getOrNull(7) ?: ""
                    val pickedByRaw = parts.getOrNull(8) ?: ""
                    val boardHash = parts.getOrNull(9)?.toLongOrNull() ?: 0L
                    val boardSize = parts.getOrNull(10)?.toIntOrNull() ?: 5
                    val history = if (historyRaw.isNotBlank()) {
                        historyRaw.split(",").mapNotNull { it.toIntOrNull() }
                    } else emptyList()
                    val pickedBy = if (pickedByRaw.isNotBlank()) {
                        pickedByRaw.split(",")
                    } else emptyList()

                    RoomMessagePacket(
                        type = "PICK_NUMBER",
                        number = number,
                        playerId = playerId,
                        turnNumber = turnNumber,
                        currentTurnPlayerId = currentTurnId,
                        pickedHistory = history,
                        pickedByHistory = pickedBy,
                        boardHash = boardHash,
                        seed = seed,
                        senderInstanceId = senderInstanceId,
                        boardSize = boardSize
                    )
                }

                trimmed.startsWith("T|") -> {
                    val parts = trimmed.split("|")
                    val playerId = parts.getOrNull(1) ?: ""
                    val turnNumber = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    val currentTurnId = parts.getOrNull(3) ?: ""
                    val historyRaw = parts.getOrNull(4) ?: ""
                    val seed = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val senderInstanceId = parts.getOrNull(6) ?: ""
                    val pickedByRaw = parts.getOrNull(7) ?: ""
                    val history = if (historyRaw.isNotBlank()) {
                        historyRaw.split(",").mapNotNull { it.toIntOrNull() }
                    } else emptyList()
                    val pickedBy = if (pickedByRaw.isNotBlank()) {
                        pickedByRaw.split(",")
                    } else emptyList()

                    RoomMessagePacket(
                        type = "TURN_TIMEOUT",
                        number = -1,
                        playerId = playerId,
                        turnNumber = turnNumber,
                        currentTurnPlayerId = currentTurnId,
                        pickedHistory = history,
                        pickedByHistory = pickedBy,
                        seed = seed,
                        senderInstanceId = senderInstanceId
                    )
                }

                trimmed.startsWith("B|") -> {
                    val parts = trimmed.split("|")
                    val playerId = parts.getOrNull(1) ?: ""
                    val seed = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val boardRaw = parts.getOrNull(3) ?: ""
                    val senderInstanceId = parts.getOrNull(4) ?: ""
                    val boardNumbers = if (boardRaw.isNotBlank()) {
                        boardRaw.split(",").mapNotNull { it.toIntOrNull() }
                    } else emptyList()

                    RoomMessagePacket(
                        type = "BOARD_READY",
                        playerId = playerId,
                        seed = seed,
                        pickedHistory = boardNumbers,
                        senderInstanceId = senderInstanceId
                    )
                }

                trimmed.startsWith("G|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "PING",
                        playerId = parts.getOrNull(1) ?: "",
                        pingTimestamp = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    )
                }

                trimmed.startsWith("O|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "PONG",
                        playerId = parts.getOrNull(1) ?: "",
                        pingTimestamp = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    )
                }

                trimmed.startsWith("R|") -> {
                    val parts = trimmed.split("|")
                    val senderInstanceId = parts.getOrNull(6) ?: ""
                    RoomMessagePacket(
                        type = "READY_STATUS",
                        playerId = parts.getOrNull(1) ?: "",
                        readyStatus = parts.getOrNull(2) ?: "NOT_READY",
                        username = parts.getOrNull(3) ?: "",
                        displayName = parts.getOrNull(4) ?: "",
                        readyVersion = parts.getOrNull(5)?.toLongOrNull() ?: 0L,
                        senderInstanceId = senderInstanceId,
                        reliablePolicyVersion = parts.getOrNull(7)?.toIntOrNull() ?: 0,
                        hostWaitVersion = parts.getOrNull(8)?.toIntOrNull() ?: 0,
                        protocolVersion = parts.getOrNull(9)?.toIntOrNull() ?: 0
                    )
                }

                trimmed.startsWith("K|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "KICK_PLAYER",
                        targetPlayerId = parts.getOrNull(1) ?: "",
                        playerId = parts.getOrNull(2) ?: "",
                        seed = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                        roomCode = parts.getOrNull(4) ?: "",
                        matchId = parts.getOrNull(5) ?: ""
                    )
                }

                trimmed.startsWith("PEX|") -> {
                    val parts = trimmed.split("|")
                    val seedVal = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val matchIdVal = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: (if (seedVal != 0L) seedVal.toString() else "")
                    RoomMessagePacket(
                        type = "PAUSE_EXTEND",
                        playerId = parts.getOrNull(1) ?: "",
                        remainingTurnMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                        timestamp = parts.getOrNull(3)?.toLongOrNull() ?: System.currentTimeMillis(),
                        seed = seedVal,
                        matchId = matchIdVal
                    )
                }

                trimmed.startsWith("HH|") -> {
                    val parts = trimmed.split("|")
                    val seedVal = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val matchIdVal = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: (if (seedVal != 0L) seedVal.toString() else "")
                    RoomMessagePacket(
                        type = "HOST_HOLD",
                        playerId = parts.getOrNull(1) ?: "",
                        remainingTurnMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                        timestamp = parts.getOrNull(3)?.toLongOrNull() ?: System.currentTimeMillis(),
                        seed = seedVal,
                        matchId = matchIdVal
                    )
                }

                trimmed.startsWith("HR|") -> {
                    val parts = trimmed.split("|")
                    val seedVal = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val matchIdVal = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: (if (seedVal != 0L) seedVal.toString() else "")
                    RoomMessagePacket(
                        type = "HOST_RESUMED",
                        playerId = parts.getOrNull(1) ?: "",
                        remainingTurnMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                        timestamp = parts.getOrNull(3)?.toLongOrNull() ?: System.currentTimeMillis(),
                        seed = seedVal,
                        matchId = matchIdVal
                    )
                }

                trimmed.startsWith("ELIM|") -> {
                    val parts = trimmed.split("|")
                    val seedVal = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val matchIdVal = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: (if (seedVal != 0L) seedVal.toString() else "")
                    RoomMessagePacket(
                        type = "PLAYER_ELIMINATED",
                        targetPlayerId = parts.getOrNull(1) ?: "",
                        playerId = parts.getOrNull(2) ?: "",
                        timestamp = parts.getOrNull(3)?.toLongOrNull() ?: System.currentTimeMillis(),
                        seed = seedVal,
                        matchId = matchIdVal
                    )
                }

                trimmed.startsWith("S|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "START_GAME",
                        playerId = parts.getOrNull(1) ?: "",
                        boardSize = parts.getOrNull(2)?.toIntOrNull() ?: 5,
                        seed = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                        isManualBoard = parts.getOrNull(4) == "1"
                    )
                }

                trimmed.startsWith("A|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "PLAY_AGAIN",
                        playerId = parts.getOrNull(1) ?: "",
                        boardSize = parts.getOrNull(2)?.toIntOrNull() ?: 5,
                        seed = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                        isManualBoard = parts.getOrNull(4) == "1"
                    )
                }

                trimmed.startsWith("H|") -> {
                    val parts = trimmed.split("|")
                    val isHost = parts.getOrNull(3) == "1"
                    val ready = parts.getOrNull(7)?.takeIf { it.isNotBlank() } ?: if (isHost) "READY" else "NOT_READY"
                    val readyVer = parts.getOrNull(8)?.toLongOrNull() ?: 0L
                    RoomMessagePacket(
                        type = "HEARTBEAT",
                        playerId = parts.getOrNull(1) ?: "",
                        displayName = parts.getOrNull(2) ?: "",
                        isHost = isHost,
                        timestamp = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis(),
                        username = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: (parts.getOrNull(2) ?: ""),
                        avatarUrl = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
                        readyStatus = ready,
                        readyVersion = readyVer,
                        reliablePolicyVersion = parts.getOrNull(9)?.toIntOrNull() ?: 0
                    )
                }

                trimmed.startsWith("MHB|") -> {
                    val parts = trimmed.split("|")
                    val proto = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    val matchId = parts.getOrNull(2) ?: ""
                    val action = parts.getOrNull(3) ?: ""
                    val senderUid = parts.getOrNull(4) ?: ""
                    val peerUid = parts.getOrNull(5) ?: ""
                    val generation = parts.getOrNull(6) ?: ""
                    val seq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                    val challenge = parts.getOrNull(8) ?: ""
                    val ts = parts.getOrNull(9)?.toLongOrNull() ?: System.currentTimeMillis()
                    RoomMessagePacket(
                        type = "MATCH_HEARTBEAT",
                        protocolVersion = proto,
                        matchId = matchId,
                        heartbeatAction = action,
                        playerId = senderUid,
                        peerUid = peerUid,
                        connectionGeneration = generation,
                        sequence = seq,
                        challengeId = challenge,
                        timestamp = ts
                    )
                }

                else -> json.decodeFromString<RoomMessagePacket>(trimmed)
            }
        } catch (_: Exception) {
            try {
                json.decodeFromString<RoomMessagePacket>(trimmed)
            } catch (_: Exception) {
                RoomMessagePacket(type = "UNKNOWN")
            }
        }
    }
}
