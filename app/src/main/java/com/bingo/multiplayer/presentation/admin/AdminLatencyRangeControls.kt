package com.bingo.multiplayer.presentation.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.AdminAccountReconnectRequired
import com.bingo.multiplayer.domain.network.CloudLatencyApi
import com.bingo.multiplayer.domain.network.CloudLatencyHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun AdminLatencyChart(adjustments: AdminDashboardLayout, user: UserProfile,
    authRevision: Int = 0, onReconnect: () -> Unit = {}) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    var view by remember(user.uid) { mutableStateOf(defaultLatencyViewport(System.currentTimeMillis())) }
    var history by remember(user.uid) { mutableStateOf<CloudLatencyHistory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var needsReconnect by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var interacting by remember { mutableStateOf(false) }
    // Pause the moving edge during a gesture or point inspection. Historical views never jump.
    LaunchedEffect(user.uid) {
        while (true) {
            delay(60_000)
            if (view.following && !interacting) {
                val now = System.currentTimeMillis()
                view = view.copy(start = now - view.duration, end = now)
            }
            refresh++
        }
    }
    LaunchedEffect(user.uid, authRevision, retry) {
        snapshotFlow { Triple(view.start to view.end, refresh, interacting) }
            .map { if (it.third) null else it.first to it.second }
            .distinctUntilChanged().debounce(350)
            .collectLatest { request ->
                if (request == null) return@collectLatest
                val now = System.currentTimeMillis()
                val range = latencyFetchRange(view, now)
                loading = true
                try {
                    val response = CloudLatencyApi.fetch(context, user, range.first, range.second)
                    history = response
                    if (!interacting) view = alignLatencyViewport(view, System.currentTimeMillis(), response.startedAt, response.points)
                    failed = false; needsReconnect = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failed = true
                    needsReconnect = error is AdminAccountReconnectRequired
                } finally { loading = false }
            }
    }
    val points = history?.points.orEmpty()
    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Cloud monitor · 1 min checks", fontSize = 8.sp, color = tokens.textMuted)
        Text(if (view.following) "Following latest" else "Inspecting history", fontSize = 8.sp, color = tokens.textMuted)
    }
    Box(Modifier.fillMaxWidth().height((adjustments.waveformHeightDp + 70f).dp)
        .clip(RoundedCornerShape(6.dp))
        .background(adminUiColor(adjustments.graphBackgroundColorHex,
            if (tokens.isDark) Color(0xFF0C0C0E) else Color(0xFFE2E8F0))), contentAlignment = Alignment.Center) {
        InteractiveLatencyGraph(view, points,
            tokens.isDark, adjustments, Modifier.fillMaxSize(),
            recordedFrom = history?.startedAt,
            onViewChanged = { view = it }, onInteractionChanged = { interacting = it
                if (!it) view = fitLatencyValues(view, history?.points.orEmpty()) })
        val trace = latencyTracePoints(view, points)
        val hasTrace = trace.size > 1 || trace.any { it.at in view.start..view.end }
        if (!hasTrace) Text(when {
            loading -> "Loading cloud history…"
            needsReconnect -> "Reconnect admin account to view history"
            failed -> "Cloud history not available yet"
            points.any { it.at in view.start..view.end } -> "No response in this range"
            else -> "No cloud measurements in this range"
        }, fontSize = 10.sp, color = tokens.textMuted,
            modifier = Modifier.padding(start = 36.dp, top = 44.dp, bottom = 26.dp))
    }
    Spacer(Modifier.height(4.dp))
    Text("Pinch ↔ time · Pinch ↕ latency · Drag to explore", fontSize = 8.sp, color = tokens.textMuted)
    Text("Tap a point for values · Double-tap to reset", fontSize = 8.sp, color = tokens.textMuted)
    val format = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Text("${format.format(Date(view.start))} – ${format.format(Date(view.end))}", fontSize = 8.sp, color = tokens.textMuted)
    Text(history?.lastRecordedAt?.let { "Last cloud check ${format.format(Date(it))} · Up to 30 days" }
        ?: "Cloud history starts when monitoring begins · Up to 30 days", fontSize = 8.sp, color = tokens.textMuted)
    if (failed) TextButton(onClick = { if (needsReconnect) onReconnect() else retry++ }, enabled = !loading) {
        Text(if (needsReconnect) "Reconnect admin account" else "Retry", fontSize = 10.sp)
    }
}
