package com.bingo.multiplayer.presentation.admin

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.domain.network.CloudLatencyPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun InteractiveLatencyGraph(
    view: LatencyViewport, points: List<CloudLatencyPoint>,
    isDark: Boolean, adjustments: AdminDashboardLayout, modifier: Modifier = Modifier,
    recordedFrom: Long? = null, onViewChanged: (LatencyViewport) -> Unit, onInteractionChanged: (Boolean) -> Unit
) {
    val currentView by rememberUpdatedState(view)
    val currentPoints by rememberUpdatedState(points)
    val currentRecordedFrom by rememberUpdatedState(recordedFrom)
    val changeView by rememberUpdatedState(onViewChanged)
    val changeInteraction by rememberUpdatedState(onInteractionChanged)
    var selected by remember { mutableStateOf<CloudLatencyPoint?>(null) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()) }
    fun reset() {
        selected = null
        changeView(defaultLatencyViewport(System.currentTimeMillis()))
    }
    fun transform(zx: Float = 1f, zy: Float = 1f, px: Float = 0f) {
        selected = null
        changeView(transformLatencyViewport(currentView, System.currentTimeMillis(),
            zoomX = zx, zoomY = zy, panX = px, points = currentPoints, recordedFrom = currentRecordedFrom))
    }
    fun inspect(point: CloudLatencyPoint?) {
        selected = point
        if (point != null) changeView(currentView.copy(following = false))
    }
    val selectionDescription = selected?.let { p ->
        "${dateFormat.format(Date(p.at))}, " + if (p.averageMs == null) "no response"
        else "average ${String.format(Locale.getDefault(), "%.2f", p.averageMs)} milliseconds, peak ${String.format(Locale.getDefault(), "%.2f", p.peakMs)} milliseconds"
    }.orEmpty()
    Canvas(modifier.semantics {
        contentDescription = "Cloud latency history graph. $selectionDescription"
        customActions = listOf(
            CustomAccessibilityAction("Zoom in time") { transform(zx = 2f); true },
            CustomAccessibilityAction("Zoom out time") { transform(zx = .5f); true },
            CustomAccessibilityAction("Zoom in latency") { transform(zy = 2f); true },
            CustomAccessibilityAction("Zoom out latency") { transform(zy = .5f); true },
            CustomAccessibilityAction("Earlier history") { transform(px = .5f); true },
            CustomAccessibilityAction("Later history") { transform(px = -.5f); true },
            CustomAccessibilityAction("Next measurement") {
                inspect(currentPoints.firstOrNull { it.at in currentView.start..currentView.end && it.at > (selected?.at ?: Long.MIN_VALUE) }); true
            },
            CustomAccessibilityAction("Previous measurement") {
                inspect(currentPoints.lastOrNull { it.at in currentView.start..currentView.end && it.at < (selected?.at ?: Long.MAX_VALUE) }); true
            },
            CustomAccessibilityAction("Reset graph") { reset(); true }
        )
    }.pointerInput(Unit) {
        var lastTapAt = 0L
        var lastTapPosition = Offset.Unspecified
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val left = 36.dp.toPx(); val top = 44.dp.toPx()
            val width = size.width - left - 6.dp.toPx()
            val height = size.height - top - 26.dp.toPx()
            val plot = down.position.x in left..(left + width) && down.position.y in top..(top + height)
            if (plot && width > 0 && height > 0) {
                var gestureView = currentView
                var moving = false
                var multi = false
                var initialSpread: Offset? = null
                var axis = 0 // 1: time; 2: latency; 3: both
                changeInteraction(true)
                try {
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed && it.previousPressed }
                        if (event.changes.any { it.isConsumed }) break
                        if (active.size >= 2) {
                            multi = true; lastTapAt = 0
                            val a = active[0]; val b = active[1]
                            val old = Offset(abs(a.previousPosition.x - b.previousPosition.x), abs(a.previousPosition.y - b.previousPosition.y))
                            val spread = Offset(abs(a.position.x - b.position.x), abs(a.position.y - b.position.y))
                            if (initialSpread == null) initialSpread = old
                            val initial = initialSpread
                            val dx = abs(spread.x - initial.x); val dy = abs(spread.y - initial.y)
                            if (axis == 0 && maxOf(dx, dy) > viewConfiguration.touchSlop) {
                                axis = when { dx > dy * 1.5f -> 1; dy > dx * 1.5f -> 2; else -> 3 }
                            }
                            // Consume multi-touch immediately so the enclosing page cannot take it.
                            event.changes.forEach { it.consume() }
                            if (axis != 0) {
                                moving = true; selected = null
                                fun scale(previous: Float, next: Float) =
                                    if (previous > 20.dp.toPx() && next > 20.dp.toPx()) (next / previous).coerceIn(.7f, 1.3f) else 1f
                                gestureView = transformLatencyViewport(gestureView, System.currentTimeMillis(),
                                    zoomX = if (axis == 1 || axis == 3) scale(old.x, spread.x) else 1f,
                                    zoomY = if (axis == 2 || axis == 3) scale(old.y, spread.y) else 1f,
                                    points = currentPoints, recordedFrom = currentRecordedFrom)
                                changeView(gestureView)
                            }
                        } else if (active.size == 1 && !multi) {
                            val finger = active.single()
                            val total = finger.position - down.position
                            if (!moving && total.getDistance() > viewConfiguration.touchSlop) {
                                if (abs(total.y) > abs(total.x)) break
                                moving = true; selected = null; lastTapAt = 0
                            }
                            if (moving) {
                                finger.consume()
                                gestureView = transformLatencyViewport(gestureView, System.currentTimeMillis(),
                                    panX = (finger.position.x - finger.previousPosition.x) / width,
                                    points = currentPoints, recordedFrom = currentRecordedFrom)
                                changeView(gestureView)
                            }
                        }
                        if (event.changes.none { it.pressed }) {
                            if (!moving && !multi) {
                                val up = event.changes.first()
                                if (lastTapAt > 0 && up.uptimeMillis - lastTapAt in 1..viewConfiguration.doubleTapTimeoutMillis &&
                                    lastTapPosition.isSpecified && (up.position - lastTapPosition).getDistance() < 40.dp.toPx()) {
                                    reset(); lastTapAt = 0
                                } else {
                                    inspect(nearestLatencyPoint(currentPoints, currentView, (up.position.x - left) / width))
                                    lastTapAt = up.uptimeMillis; lastTapPosition = up.position
                                }
                                up.consume()
                            }
                            break
                        }
                    } while (true)
                } finally { changeInteraction(false) }
            }
        }
    }) {
        val left = 36.dp.toPx(); val top = 44.dp.toPx()
        val w = size.width - left - 6.dp.toPx(); val h = size.height - top - 26.dp.toPx()
        if (w <= 0 || h <= 0) return@Canvas
        val labelColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF475569)
        val crosshairColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)
        paint.textSize = 8f * density * fontScale; paint.color = labelColor.toArgb()
        fun x(at: Long) = left + (at - view.start).toDouble().div(view.duration).toFloat() * w
        fun y(value: Float) = top + h * (1f - (value - view.minimum) / (view.maximum - view.minimum))
        fun number(value: Float) = if (abs(value - value.toInt()) < .001f) value.toInt().toString()
            else String.format(Locale.getDefault(), "%.1f", value)
        for (tick in view.axis.ticks) {
            val pos = y(tick)
            if (adjustments.graphShowGrid) drawLine(Color.Gray.copy(alpha = adjustments.graphGridAlpha),
                Offset(left, pos), Offset(left + w, pos), 1.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(number(tick), left - 5.dp.toPx(), pos - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("ms", 0f, top - 6.dp.toPx(), paint)
        val timeFormat = SimpleDateFormat(when {
            view.duration <= 300_000 -> "HH:mm:ss"
            view.duration <= 86_400_000 -> "HH:mm"
            else -> "dd/MM HH:mm"
        }, Locale.getDefault())
        val secondTicks = latencySecondTicks(view)
        val ticks = if (secondTicks.isNotEmpty()) secondTicks else {
            val divisions = if (view.duration > 86_400_000) 2 else 4
            (0..divisions).map { i ->
                val at = view.start + view.duration * i / divisions
                LatencyTimeTick(at, timeFormat.format(Date(at)))
            }
        }
        for (tick in ticks) {
            val pos = x(tick.at)
            if (tick.label != null && adjustments.graphShowGrid) drawLine(Color.Gray.copy(alpha = adjustments.graphGridAlpha),
                Offset(pos, top), Offset(pos, top + h), 1.dp.toPx())
            drawLine(labelColor.copy(alpha = .65f), Offset(pos, top + h),
                Offset(pos, top + h + (if (tick.label != null) 5.dp.toPx() else 2.dp.toPx())), .75.dp.toPx())
            tick.label?.let { label ->
                paint.textAlign = Paint.Align.CENTER
                val half = paint.measureText(label) / 2
                drawContext.canvas.nativeCanvas.drawText(label, pos.coerceIn(half, size.width - half), top + h + 16.dp.toPx(), paint)
            }
        }
        // Only actual averages get dots. A solid connector can span missing/failed checks.
        val trace = latencyTracePoints(view, points)
        clipRect(left, top, left + w, top + h) {
            for (i in trace.indices) {
                val sample = trace[i]
                val value = sample.averageMs ?: continue
                val pos = Offset(x(sample.at), y(value))
                val color = adminLatencyColor(value, isDark)
                val previous = trace.getOrNull(i - 1)
                if (previous?.averageMs != null) {
                    val before = Offset(x(previous.at), y(previous.averageMs))
                    if (adjustments.graphShowFill) {
                        val fill = Path().apply {
                            moveTo(before.x, top + h); lineTo(before.x, before.y)
                            lineTo(pos.x, pos.y); lineTo(pos.x, top + h); close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = adjustments.graphFillAlpha), Color.Transparent), top, top + h))
                    }
                    drawLine(color, before, pos, adjustments.graphStrokeWidthDp.dp.toPx(), StrokeCap.Round)
                }
                if (sample.at in view.start..view.end) drawCircle(color, 2.dp.toPx(), pos)
            }
        }
        selected?.takeIf { it.at in view.start..view.end }?.let { p ->
            val atX = x(p.at)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            drawLine(crosshairColor.copy(alpha = .8f), Offset(atX, top), Offset(atX, top + h), 1.dp.toPx(), pathEffect = dash)
            p.averageMs?.takeIf { it in view.minimum..view.maximum }?.let { value ->
                val atY = y(value)
                drawLine(crosshairColor.copy(alpha = .6f), Offset(left, atY), Offset(left + w, atY), 1.dp.toPx(), pathEffect = dash)
                drawCircle(crosshairColor, 5.dp.toPx(), Offset(atX, atY))
                drawCircle(adminLatencyColor(value, isDark), 3.dp.toPx(), Offset(atX, atY))
            }
            val title = dateFormat.format(Date(p.at))
            val value = if (p.averageMs == null) "No response · ${p.failedProbes} failed probes" else
                String.format(Locale.getDefault(), "Avg %.2f ms · Peak %.2f ms", p.averageMs, p.peakMs) +
                    if (p.observations > 1) " · ${p.observations} checks" else ""
            paint.textSize = 9f * density * fontScale
            val padding = 6.dp.toPx()
            var width = maxOf(paint.measureText(title), paint.measureText(value)) + padding * 2
            if (width > size.width - 8.dp.toPx()) {
                paint.textSize *= (size.width - 8.dp.toPx()) / width
                width = maxOf(paint.measureText(title), paint.measureText(value)) + padding * 2
            }
            val boxLeft = (atX - width / 2).coerceIn(2.dp.toPx(), (size.width - width - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
            drawRoundRect(if (isDark) Color(0xFF27272A) else Color.White,
                Offset(boxLeft, 2.dp.toPx()), androidx.compose.ui.geometry.Size(width, 36.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            drawLine(crosshairColor.copy(alpha = .8f), Offset(atX, 38.dp.toPx()), Offset(atX, top), 1.dp.toPx())
            paint.textAlign = Paint.Align.CENTER; paint.color = crosshairColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(title, boxLeft + width / 2, 15.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText(value, boxLeft + width / 2, 29.dp.toPx(), paint)
        }
    }
}
