package com.bingo.multiplayer.presentation.admin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrokerGaugeAppearanceTest {
    @Test fun disconnectedGaugeDesaturatesActualPixelsAndConnectedGaugeKeepsColor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(dispatcher + frames)
        val job = backgroundScope.launch(dispatcher + frames) { recomposer.runRecomposeAndApplyChanges() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val view = ComposeView(activity.get()).apply { setParentCompositionContext(recomposer) }
        activity.get().setContentView(view)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        view.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Row {
                    Box(Modifier.size(80.dp).brokerGaugeAppearance(true).background(Color.Red))
                    Box(Modifier.size(80.dp).brokerGaugeAppearance(false).background(Color.Red))
                }
            }
        }
        try {
            Snapshot.sendApplyNotifications()
            repeat(4) { index ->
                runCurrent(); frames.sendFrame((index + 1) * 16_000_000L); runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 200, 100); view.draw(Canvas(bitmap))
            }
            val online = bitmap.getPixel(40, 40)
            val offline = bitmap.getPixel(120, 40)
            assertTrue(android.graphics.Color.red(online) > android.graphics.Color.green(online) + 100)
            assertEquals(android.graphics.Color.red(offline), android.graphics.Color.green(offline))
            assertEquals(android.graphics.Color.green(offline), android.graphics.Color.blue(offline))
            assertTrue(android.graphics.Color.alpha(offline) > 0)
        } finally {
            view.disposeComposition(); recomposer.cancel(); job.cancel(); runCurrent()
            activity.pause().stop().destroy(); bitmap.recycle()
        }
    }
}
