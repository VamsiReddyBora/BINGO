package com.bingo.multiplayer.presentation.admin

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.bingo.multiplayer.domain.network.CloudLatencyPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InteractiveLatencyTouchTest {
    @Test fun nativePointersInspectPanZoomBothAxesAndResetTheActualChart() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val now = System.currentTimeMillis()
            val state = mutableStateOf(defaultLatencyViewport(now).copy(start = now - 3_600_000))
            val points = listOf(CloudLatencyPoint(now - 1_800_000, 98f, 102f, 3, 0, 1))
            val compose = ComposeView(activity).apply {
                setContent {
                    InteractiveLatencyGraph(state.value, points, true, AdminDashboardLayout,
                        Modifier.fillMaxSize(), onViewChanged = { state.value = it }, onInteractionChanged = {})
                }
            }
            activity.setContentView(compose)
            val density = activity.resources.displayMetrics.density
            val width = (340 * density).toInt(); val height = (190 * density).toInt()
            compose.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            compose.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            var clock = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float, y: Float, down: Long) {
                val event = MotionEvent.obtain(down, clock, action, x * density, y * density, 0)
                compose.dispatchTouchEvent(event); event.recycle()
                clock += 20
                shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            }
            val down = clock
            touch(MotionEvent.ACTION_DOWN, 180f, 100f, down)
            touch(MotionEvent.ACTION_UP, 180f, 100f, down)
            assertFalse("Tap must inspect a real point and pause following", state.value.following)
            val before = state.value.start
            clock += 500
            val drag = clock
            touch(MotionEvent.ACTION_DOWN, 160f, 110f, drag)
            touch(MotionEvent.ACTION_MOVE, 200f, 110f, drag)
            touch(MotionEvent.ACTION_MOVE, 250f, 110f, drag)
            touch(MotionEvent.ACTION_UP, 250f, 110f, drag)
            assertTrue("Rightward drag must reveal older measurements", state.value.start < before)
            fun two(action: Int, ax: Float, ay: Float, bx: Float, by: Float, downTime: Long) {
                val properties = Array(2) { i -> MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coords = arrayOf(ax to ay, bx to by).map { (x, y) -> MotionEvent.PointerCoords().apply {
                    this.x = x * density; this.y = y * density; pressure = 1f; size = 1f
                } }.toTypedArray()
                val event = MotionEvent.obtain(downTime, clock, action, 2, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                compose.dispatchTouchEvent(event); event.recycle(); clock += 20
                shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            }
            clock += 500
            val pinch = clock
            val previousDuration = state.value.duration
            touch(MotionEvent.ACTION_DOWN, 140f, 100f, pinch)
            two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 140f, 100f, 240f, 100f, pinch)
            two(MotionEvent.ACTION_MOVE, 120f, 100f, 260f, 100f, pinch)
            two(MotionEvent.ACTION_MOVE, 100f, 100f, 280f, 100f, pinch)
            two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 100f, 280f, 100f, pinch)
            touch(MotionEvent.ACTION_UP, 100f, 100f, pinch)
            assertTrue("Horizontal pinch must magnify time", state.value.duration < previousDuration)
            val timeBeforeVertical = state.value.start to state.value.end
            val spanBeforeVertical = state.value.maximum - state.value.minimum
            clock += 500
            val vertical = clock
            touch(MotionEvent.ACTION_DOWN, 190f, 80f, vertical)
            two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 190f, 80f, 190f, 130f, vertical)
            two(MotionEvent.ACTION_MOVE, 220f, 60f, 220f, 150f, vertical)
            two(MotionEvent.ACTION_MOVE, 250f, 55f, 250f, 155f, vertical)
            two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250f, 55f, 250f, 155f, vertical)
            touch(MotionEvent.ACTION_UP, 250f, 55f, vertical)
            assertTrue("Vertical pinch must magnify latency", state.value.maximum - state.value.minimum < spanBeforeVertical)
            assertEquals("Vertical pinch must keep the time range", timeBeforeVertical, state.value.start to state.value.end)
            clock += 500
            var tap = clock
            touch(MotionEvent.ACTION_DOWN, 180f, 100f, tap)
            touch(MotionEvent.ACTION_UP, 180f, 100f, tap)
            clock += 80; tap = clock
            touch(MotionEvent.ACTION_DOWN, 180f, 100f, tap)
            touch(MotionEvent.ACTION_UP, 180f, 100f, tap)
            assertEquals("Double tap resets to the latest minute", LATENCY_DEFAULT_WINDOW_MS, state.value.duration)
            assertTrue(state.value.following)
            assertEquals(0f, state.value.minimum)
            assertEquals(250f, state.value.maximum)
        } finally { controller.pause().stop().destroy() }
    }
}
