package com.bingo.multiplayer.presentation.admin

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun adminUiColor(hex: String, fallback: Color): Color =
    if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) Color(android.graphics.Color.parseColor(hex)) else fallback

internal data class AdminLatencySample(val at: Long, val ms: Float?, val peakMs: Float? = ms)

/** A timestamped, bounded RAM window. It is never used as persistent history. */
internal class AdminLatencyHistory {
    private val values = mutableListOf<AdminLatencySample>()
    fun sample(pingMs: Long, capacity: Int, at: Long = System.currentTimeMillis()): List<AdminLatencySample> {
        values.add(AdminLatencySample(at, pingMs.takeIf { it > 0 }?.toFloat()))
        while (values.isNotEmpty() && (values.size > capacity.coerceIn(8, 128) || values.first().at < at - 60_000)) values.removeAt(0)
        return values.toList()
    }
}

internal suspend fun sampleAdminLatency(
    history: AdminLatencyHistory, intervalMs: Long, capacity: Int, readPing: () -> Long,
    now: () -> Long = { System.currentTimeMillis() }, emit: (List<AdminLatencySample>) -> Unit
) {
    while (currentCoroutineContext().isActive) {
        emit(history.sample(readPing(), capacity, now()))
        delay(intervalMs.coerceIn(100L, 5000L))
    }
}

internal fun adminLatencyFraction(value: Float, maximum: Float): Float =
    (value / maximum.coerceAtLeast(1f)).coerceIn(0f, 1f)

internal fun adminLatencyColor(pingMs: Float, isDark: Boolean = true): Color = when {
    pingMs <= 0f -> Color.Gray
    pingMs <= 250f -> if (isDark) Color.White else Color(0xFF0F172A)
    pingMs <= 500f -> if (isDark) Color(0xFFEAB308) else Color(0xFFA16207)
    else -> if (isDark) Color(0xFFDC2626) else Color(0xFFB91C1C)
}

