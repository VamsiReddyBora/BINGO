package com.bingo.multiplayer.domain.network

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.Keep
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Keep
@Serializable
data class NearbyHostQrPayload(
    val ssid: String,
    val password: String = "",
    val roomCode: String,
    val hostIp: String,
    val hostName: String,
    val boardSize: Int = 5,
    val port: Int = 8999
)

@Keep
@Serializable
data class PlayerProfileQrPayload(
    val username: String,
    val uid: String = "",
    val displayName: String = "",
    val level: Int = 1
)

@Keep
@Serializable
data class OnlineRoomQrPayload(
    val roomCode: String,
    val hostName: String = "",
    val boardSize: Int = 5
)

object QrCodeHelper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private const val QR_PREFIX = "BINGO_LAN:"
    const val PROFILE_QR_PREFIX = "BINGO_PROFILE:"
    const val ROOM_QR_PREFIX = "BINGO_ROOM:"

    /**
     * Serializes online room details into a QR code payload.
     */
    fun createRoomQrContent(payload: OnlineRoomQrPayload): String {
        return "$ROOM_QR_PREFIX${json.encodeToString(payload)}"
    }

    /**
     * Serializes online room details into a QR code payload from individual arguments.
     */
    fun createRoomQrContent(roomCode: String, hostName: String = "", boardSize: Int = 5): String {
        return createRoomQrContent(OnlineRoomQrPayload(roomCode = roomCode, hostName = hostName, boardSize = boardSize))
    }

    /**
     * Parses scanned QR content into a structured OnlineRoomQrPayload.
     * Supports BINGO_ROOM: json, raw BINGO_ROOM:CODE, web/deep-link parameters, or 6-char room codes.
     */
    fun parseRoomQrContent(raw: String): OnlineRoomQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.startsWith(ROOM_QR_PREFIX, ignoreCase = true)) {
            val payloadStr = trimmed.substring(ROOM_QR_PREFIX.length).trim()
            // Check if it's JSON
            if (payloadStr.startsWith("{")) {
                try {
                    val payload = json.decodeFromString<OnlineRoomQrPayload>(payloadStr)
                    return normalizeRoom(payload)
                } catch (_: Exception) {}
            }
            // Check if it's just the 6-character code after prefix (e.g. BINGO_ROOM:ABC123)
            val codeOnly = payloadStr.trim().uppercase()
            if (validRoomCode(codeOnly)) {
                return OnlineRoomQrPayload(roomCode = codeOnly)
            }
        }

        // Deep links or web links: bingo://room?code=ABC123 or https://...?code=ABC123 or ?room=ABC123
        if (trimmed.contains("code=", ignoreCase = true) || trimmed.contains("room=", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", "").ifBlank { trimmed }
            val params = query.split("&").mapNotNull {
                val parts = it.split("=")
                if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
            }.toMap()

            val code = (params["code"] ?: params["room"])?.uppercase()
            if (code != null && validRoomCode(code)) {
                val host = params["host"] ?: ""
                val size = params["size"]?.toIntOrNull() ?: 5
                return normalizeRoom(OnlineRoomQrPayload(roomCode = code, hostName = host, boardSize = size))
            }
        }

        // Direct 6-character alphanumeric code
        if (validRoomCode(trimmed.uppercase())) {
            return OnlineRoomQrPayload(roomCode = trimmed.uppercase())
        }

        return null
    }

    /**
     * Extracts a clean, verified 6-character room code from arbitrary text or QR code content.
     * Handles clipboard text (e.g. "Join my room: ABC123"), QR prefixes, and deep links.
     */
    fun extractRoomCode(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // 1. Check if direct 6-char code
        if (validRoomCode(trimmed.uppercase())) {
            return trimmed.uppercase()
        }

        // 2. Check if structured room QR
        val qrParsed = parseRoomQrContent(trimmed)
        if (qrParsed != null && qrParsed.roomCode.length == 6) {
            return qrParsed.roomCode.uppercase()
        }

        // Avoid interpreting ordinary six-letter words as a room code.
        val labeled = Regex("""\broom(?:\s+code)?\s*[:=#]\s*([A-Z0-9]{6})(?![A-Z0-9])""", RegexOption.IGNORE_CASE)
        return labeled.find(trimmed)?.groupValues?.get(1)?.uppercase()
    }

    private fun validRoomCode(code: String): Boolean = code.matches(Regex("[A-Z0-9]{6}"))

    private fun normalizeRoom(payload: OnlineRoomQrPayload): OnlineRoomQrPayload? {
        val code = payload.roomCode.trim().uppercase()
        return if (validRoomCode(code) && payload.boardSize in 5..10) payload.copy(roomCode = code) else null
    }

    /**
     * Serializes player profile into a QR code payload.
     */
    fun createProfileQrContent(payload: PlayerProfileQrPayload): String {
        return "$PROFILE_QR_PREFIX${json.encodeToString(payload)}"
    }

    /**
     * Parses scanned QR content into a structured PlayerProfileQrPayload.
     * Supports BINGO_PROFILE: json, web profile links, bingo:// custom scheme, and raw @usernames.
     */
    fun parseProfileQrContent(raw: String): PlayerProfileQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.startsWith(PROFILE_QR_PREFIX)) {
            val jsonStr = trimmed.removePrefix(PROFILE_QR_PREFIX)
            return try {
                json.decodeFromString<PlayerProfileQrPayload>(jsonStr)
            } catch (_: Exception) {
                null
            }
        }

        // Web or custom scheme link fallback: https://.../?user=xyz, ?u=xyz, or bingo://profile?user=xyz
        if (trimmed.contains("user=", ignoreCase = true) || trimmed.contains("profile=", ignoreCase = true) || trimmed.contains("u=", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", "").ifBlank { trimmed }
            val params = query.split("&").mapNotNull {
                val parts = it.split("=")
                if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
            }.toMap()

            val user = params["user"] ?: params["profile"] ?: params["u"]
            if (!user.isNullOrBlank()) {
                val cleanUser = user.lowercase().removePrefix("@")
                val name = params["name"] ?: cleanUser
                val uid = params["uid"] ?: ""
                val level = params["level"]?.toIntOrNull() ?: 1
                return PlayerProfileQrPayload(username = cleanUser, uid = uid, displayName = name, level = level)
            }
        }

        // Plain @username fallback
        if (trimmed.startsWith("@") && trimmed.length > 2) {
            val clean = trimmed.lowercase().removePrefix("@")
            return PlayerProfileQrPayload(username = clean, displayName = clean)
        }

        return null
    }

    /**
     * Serializes host details into universal standard Wi-Fi QR code format
     * with embedded game metadata (WIFI:T:WPA;S:ssid;P:password;ROOM:...;IP:...;PORT:...;NAME:...;;).
     */
    fun createQrContent(payload: NearbyHostQrPayload): String {
        val sec = if (payload.password.isBlank()) "nopass" else "WPA"
        return "WIFI:T:$sec;S:${payload.ssid};P:${payload.password};ROOM:${payload.roomCode};IP:${payload.hostIp};PORT:${payload.port};NAME:${payload.hostName};;"
    }

    /**
     * Parses scanned QR content into a structured payload.
     * Supports both custom Bingo format and standard Wi-Fi QR strings (WIFI:S:xxx;P:yyy;;).
     */
    fun parseQrContent(raw: String): NearbyHostQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.startsWith(QR_PREFIX)) {
            val jsonStr = trimmed.removePrefix(QR_PREFIX)
            return try {
                json.decodeFromString<NearbyHostQrPayload>(jsonStr)
            } catch (_: Exception) {
                null
            }
        }

        // Standard Wi-Fi QR fallback (WIFI:T:WPA;S:ssid;P:pass;;)
        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            return try {
                var ssid = ""
                var pass = ""
                var room = "LAN_${(1000..9999).random()}"
                var ip = ""
                var name = "Nearby Host"
                var port = 8999

                val tokens = trimmed.removePrefix("WIFI:").split(";")
                for (token in tokens) {
                    val part = token.trim()
                    when {
                        part.startsWith("S:", ignoreCase = true) -> ssid = part.substring(2)
                        part.startsWith("P:", ignoreCase = true) -> pass = part.substring(2)
                        part.startsWith("ROOM:", ignoreCase = true) -> room = part.substring(5)
                        part.startsWith("IP:", ignoreCase = true) -> ip = part.substring(3)
                        part.startsWith("PORT:", ignoreCase = true) -> port = part.substring(5).toIntOrNull() ?: 8999
                        part.startsWith("NAME:", ignoreCase = true) -> name = part.substring(5)
                    }
                }
                if (ssid.isNotBlank()) {
                    val displayName = if (name.isNotBlank() && name != "Nearby Host") name else ssid
                    NearbyHostQrPayload(
                        ssid = ssid,
                        password = pass,
                        roomCode = room,
                        hostIp = ip,
                        hostName = displayName,
                        port = port
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /**
     * Generates a crisp QR Code Bitmap.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
