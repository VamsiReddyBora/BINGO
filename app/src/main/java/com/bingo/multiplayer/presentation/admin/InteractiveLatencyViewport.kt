package com.bingo.multiplayer.presentation.admin

import com.bingo.multiplayer.domain.network.CLOUD_HISTORY_RETENTION_MS
import com.bingo.multiplayer.domain.network.CloudLatencyPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal const val LATENCY_DEFAULT_WINDOW_MS = 60_000L
internal const val LATENCY_MIN_WINDOW_MS = 60_000L

internal fun niceLatencyStep(value: Float): Float {
    val magnitude = 10.0.pow(floor(log10(value.coerceAtLeast(.001f).toDouble()))).toFloat()
    val ratio = value / magnitude
    return magnitude * when { ratio <= 1 -> 1f; ratio <= 2 -> 2f; ratio <= 2.5 -> 2.5f; ratio <= 5 -> 5f; else -> 10f }
}

/** Automatic mode keeps a zero baseline; manual Y zoom remains centred on the visible trace. */
internal data class LatencyViewport(
    val start: Long, val end: Long, val minimum: Float = 0f, val maximum: Float = 250f,
    val following: Boolean = true, val autoScale: Boolean = true, val preferredSpanMs: Float? = null
) {
    val duration: Long get() = end - start
    val axis: AdminLatencyAxis get() {
        val step = niceLatencyStep((maximum - minimum) / 6f)
        return AdminLatencyAxis(ceil(minimum / step) * step, maximum, step)
    }
}

internal fun defaultLatencyViewport(now: Long) = LatencyViewport(now - LATENCY_DEFAULT_WINDOW_MS, now)

internal fun boundLatencyTime(start: Long, duration: Long, now: Long, recordedFrom: Long? = null): Pair<Long, Long> {
    val earliest = maxOf(now - CLOUD_HISTORY_RETENTION_MS, recordedFrom ?: Long.MIN_VALUE).coerceAtMost(now - LATENCY_MIN_WINDOW_MS)
    val span = duration.coerceIn(LATENCY_MIN_WINDOW_MS, now - earliest)
    val boundedStart = start.coerceIn(earliest, now - span)
    return boundedStart to boundedStart + span
}

/** Finger translation cannot move a pinch off the trace. Only explicit one-finger drag pans time. */
internal fun transformLatencyViewport(
    view: LatencyViewport, now: Long, zoomX: Float = 1f, zoomY: Float = 1f,
    panX: Float = 0f, points: List<CloudLatencyPoint> = emptyList(), recordedFrom: Long? = null
): LatencyViewport {
    val zx = zoomX.takeIf { it.isFinite() && it > 0 } ?: 1f
    val zy = zoomY.takeIf { it.isFinite() && it > 0 } ?: 1f
    val duration = (view.duration / zx.toDouble()).toLong().coerceIn(LATENCY_MIN_WINDOW_MS, CLOUD_HISTORY_RETENTION_MS)
    val start = (view.start + (view.duration - duration) / 2.0 - duration * panX.toDouble()).toLong()
    val time = boundLatencyTime(start, duration, now, recordedFrom)
    val span = ((view.preferredSpanMs ?: (view.maximum - view.minimum)) / zy).coerceIn(2f, 120_000f)
    val middle = (view.minimum + view.maximum) / 2f
    val lower = (middle - span / 2).coerceIn(0f, 120_000f - span)
    val changedY = abs(zy - 1f) > .0001f
    val changedX = abs(zx - 1f) > .0001f || abs(panX) > .0001f
    val next = LatencyViewport(time.first, time.second, lower, lower + span,
        following = if (changedX) now - time.second <= 1000L else view.following,
        autoScale = view.autoScale && !changedY,
        preferredSpanMs = if (!view.autoScale || changedY) span else null)
    return fitLatencyValues(next, points)
}

/** Adjacent observations also bound segments crossing the viewport, without inventing samples. */
internal fun latencyTracePoints(view: LatencyViewport, points: List<CloudLatencyPoint>): List<CloudLatencyPoint> {
    val readings = points.filter { it.averageMs != null }
    val first = readings.indexOfFirst { it.at >= view.start }
    if (first == -1) return emptyList()
    val last = readings.indexOfLast { it.at <= view.end }
    if (last < 0) return emptyList()
    val from = (first - 1).coerceAtLeast(0)
    val to = (last + 1).coerceAtMost(readings.lastIndex)
    if (to < from) return emptyList()
    return readings.subList(from, to + 1)
}

/** Peaks stay in the tooltip; unplotted peaks must not stretch the average trace off screen. */
internal fun fitLatencyValues(view: LatencyViewport, points: List<CloudLatencyPoint>): LatencyViewport {
    val values = latencyTracePoints(view, points).mapNotNull { it.averageMs }
    if (values.isEmpty()) return view
    val low = values.minOrNull()!!; val high = values.maxOrNull()!!
    if (view.autoScale) {
        val padded = maxOf(250f, high * 1.1f)
        val step = niceLatencyStep(padded / 5f)
        return view.copy(minimum = 0f, maximum = ceil(padded / step) * step)
    }
    val span = maxOf(view.preferredSpanMs ?: (view.maximum - view.minimum), (high - low) * 1.2f, 2f)
    val step = niceLatencyStep(span / 6f)
    val roundedSpan = ceil(span / step) * step
    val lower = (floor(((low + high) / 2f - roundedSpan / 2f) / step) * step).coerceAtLeast(0f)
    val upper = maxOf(lower + roundedSpan, ceil((high + step * .25f) / step) * step)
    return view.copy(minimum = lower, maximum = upper)
}

internal fun alignLatencyViewport(view: LatencyViewport, now: Long, recordedFrom: Long?, points: List<CloudLatencyPoint>): LatencyViewport {
    val bounds = boundLatencyTime(view.start, view.duration, now, recordedFrom)
    return fitLatencyValues(view.copy(start = bounds.first, end = bounds.second), points)
}

internal data class LatencyTimeTick(val at: Long, val label: String?)
internal fun latencySecondTicks(view: LatencyViewport): List<LatencyTimeTick> =
    if (view.duration == LATENCY_DEFAULT_WINDOW_MS) (0..60).map {
        LatencyTimeTick(view.start + it * 1000L, if (it % 10 == 0) "${it}s" else null)
    } else emptyList()

/** Only choose actual observations, including failed checks; connecting lines are not readings. */
internal fun nearestLatencyPoint(points: List<CloudLatencyPoint>, view: LatencyViewport, fraction: Float): CloudLatencyPoint? {
    val at = view.start + (view.duration * fraction.coerceIn(0f, 1f).toDouble()).toLong()
    return points.filter { it.at in view.start..view.end }.minByOrNull { abs(it.at - at) }
}

internal fun latencyFetchRange(view: LatencyViewport, now: Long): Pair<Long, Long> {
    val padding = (view.duration / 3).coerceAtLeast(180_000L)
    val start = (view.start - padding).coerceAtLeast(now - CLOUD_HISTORY_RETENTION_MS)
    val end = (view.end + padding).coerceAtMost(now)
    return start to end.coerceAtLeast(start + 1)
}
