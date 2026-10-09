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

object QrCodeHelper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private const val QR_PREFIX = "BINGO_LAN:"

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
