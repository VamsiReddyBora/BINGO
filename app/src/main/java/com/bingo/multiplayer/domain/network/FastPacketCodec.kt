package com.bingo.multiplayer.domain.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun encode(packet: RoomMessagePacket): String {
        return when (packet.type) {
            "PICK_NUMBER" -> {
                val historyStr = packet.pickedHistory.joinToString(",")
                if (packet.senderInstanceId.isNotBlank()) {
                    "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}|${packet.senderInstanceId}"
                } else if (packet.seed != 0L) {
                    "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}"
                } else {
                    "P|${packet.number}|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr"
                }
            }
            "TURN_TIMEOUT" -> {
                val historyStr = packet.pickedHistory.joinToString(",")
                if (packet.senderInstanceId.isNotBlank()) {
                    "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}|${packet.senderInstanceId}"
                } else if (packet.seed != 0L) {
                    "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr|${packet.seed}"
                } else {
                    "T|${packet.playerId}|${packet.turnNumber}|${packet.currentTurnPlayerId}|$historyStr"
                }
            }
            "BOARD_READY" -> {
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
                if (packet.senderInstanceId.isNotBlank()) {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}|${packet.readyVersion}|${packet.senderInstanceId}"
                } else if (packet.readyVersion > 0L) {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}|${packet.readyVersion}"
                } else if (packet.username.isBlank() && packet.displayName.isBlank()) {
                    "R|${packet.playerId}|${packet.readyStatus}"
                } else {
                    "R|${packet.playerId}|${packet.readyStatus}|${packet.username}|${packet.displayName}"
                }
            }
            "KICK_PLAYER" -> "K|${packet.targetPlayerId}|${packet.playerId}"
            "START_GAME" -> json.encodeToString(packet)
            "PLAY_AGAIN" -> json.encodeToString(packet)
            "HEARTBEAT" -> {
                if (packet.players.isNotEmpty()) {
                    json.encodeToString(packet)
                } else if (packet.username.isNullOrEmpty() && packet.avatarUrl.isNullOrEmpty() && (packet.readyStatus.isEmpty() || packet.readyStatus == "NOT_READY") && packet.readyVersion == 0L) {
                    "H|${packet.playerId}|${packet.displayName}|${if (packet.isHost) "1" else "0"}|${packet.timestamp}"
                } else {
                    "H|${packet.playerId}|${packet.displayName}|${if (packet.isHost) "1" else "0"}|${packet.timestamp}|${packet.username}|${packet.avatarUrl ?: ""}|${packet.readyStatus}|${packet.readyVersion}"
                }
            }
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
                    val history = if (historyRaw.isNotBlank()) {
                        historyRaw.split(",").mapNotNull { it.toIntOrNull() }
                    } else emptyList()

                    RoomMessagePacket(
                        type = "PICK_NUMBER",
                        number = number,
                        playerId = playerId,
                        turnNumber = turnNumber,
                        currentTurnPlayerId = currentTurnId,
                        pickedHistory = history,
                        seed = seed,
                        senderInstanceId = senderInstanceId
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
                    val history = if (historyRaw.isNotBlank()) {
                        historyRaw.split(",").mapNotNull { it.toIntOrNull() }
                    } else emptyList()

                    RoomMessagePacket(
                        type = "TURN_TIMEOUT",
                        number = -1,
                        playerId = playerId,
                        turnNumber = turnNumber,
                        currentTurnPlayerId = currentTurnId,
                        pickedHistory = history,
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
                        senderInstanceId = senderInstanceId
                    )
                }

                trimmed.startsWith("K|") -> {
                    val parts = trimmed.split("|")
                    RoomMessagePacket(
                        type = "KICK_PLAYER",
                        targetPlayerId = parts.getOrNull(1) ?: "",
                        playerId = parts.getOrNull(2) ?: ""
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
                        readyVersion = readyVer
                    )
                }

                else -> json.decodeFromString<RoomMessagePacket>(trimmed)
            }
        } catch (_: Exception) {
            json.decodeFromString<RoomMessagePacket>(trimmed)
        }
    }
}
