package com.bingo.multiplayer.presentation.admin

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

internal data class AdminLatencyAxis(val minimum: Float, val maximum: Float, val step: Float) {
    val ticks: List<Float> get() = (0..((maximum - minimum) / step).toInt()).map { minimum + it * step }
}

/** Detail remains a stable 20ms band around the median; peaks are available in Overview. */
internal fun adminLatencyAxis(values: List<Float>, detailed: Boolean): AdminLatencyAxis {
    val valid = values.filter { it.isFinite() && it > 0 }.sorted()
    if (detailed) {
        val middle = if (valid.isEmpty()) 10f else if (valid.size % 2 == 1) valid[valid.size / 2]
            else (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2f
        val minimum = (round(middle / 10f) * 10f - 10f).coerceAtLeast(0f)
        return AdminLatencyAxis(minimum, minimum + 20f, 2f)
    }
    val maximum = (valid.lastOrNull() ?: 20f).coerceAtLeast(20f)
    val rough = maximum / 5f
    val magnitude = 10.0.pow(floor(log10(rough.toDouble()))).toFloat()
    val ratio = rough / magnitude
    val step = magnitude * when { ratio <= 1 -> 1f; ratio <= 2 -> 2f; ratio <= 5 -> 5f; else -> 10f }
    return AdminLatencyAxis(0f, (ceil(maximum / step) * step).coerceAtLeast(step), step)
}

/** Unclamped: the viewport clips out-of-band readings instead of inventing flat boundary values. */
internal fun adminLatencyY(value: Float, axis: AdminLatencyAxis, height: Float): Float =
    height * (1f - (value - axis.minimum) / (axis.maximum - axis.minimum))
