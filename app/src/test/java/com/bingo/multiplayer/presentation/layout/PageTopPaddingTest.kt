package com.bingo.multiplayer.presentation.layout

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.core.designsystem.AppPageLayout
import com.bingo.multiplayer.core.designsystem.appPagePadding
import java.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageTopPaddingTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("bingo_page_padding_preview_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences("bingo_page_padding_preview_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun permanentPaddingPositionsPagesAndHeadersWithoutChangingOtherMargins() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(dispatcher + frames)
        val recomposition = backgroundScope.launch(dispatcher + frames) { recomposer.runRecomposeAndApplyChanges() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val view = ComposeView(activity.get())
        view.setParentCompositionContext(recomposer)
        activity.get().setContentView(view)
        var rootBounds: Rect? = null
        var contentBounds: Rect? = null
        var headerBounds: Rect? = null
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        view.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Row(Modifier.fillMaxSize().onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
                    Box(Modifier.weight(1f).fillMaxHeight().appPagePadding(horizontal = 20.dp, bottom = 30.dp)) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { contentBounds = it.boundsInRoot() })
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = AppPageLayout.TopGap)) {
                        Box(Modifier.size(32.dp).onGloballyPositioned { headerBounds = it.boundsInRoot() })
                    }
                }
            }
        }
        var frameNanos = 0L
        fun layoutFrame() {
            Snapshot.sendApplyNotifications()
            repeat(3) {
                runCurrent()
                frameNanos += 16_000_000L
                frames.sendFrame(frameNanos)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 800, 1200)
                // Internal Compose layouts can change without requesting a new Android
                // View size; dispatchDraw applies those pending remeasure/layout passes.
                view.draw(Canvas(bitmap))
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        try {
            layoutFrame()
            assertNotNull("Compose must lay out the actual production modifier", contentBounds)
            assertEquals(30f, contentBounds!!.top - rootBounds!!.top, 0.01f)
            assertEquals(30f, headerBounds!!.top - rootBounds!!.top, 0.01f)
            assertEquals(40f, contentBounds!!.left - rootBounds!!.left, 0.01f)
            assertEquals(60f, rootBounds!!.bottom - contentBounds!!.bottom, 0.01f)

        } finally {
            view.disposeComposition()
            recomposer.cancel()
            recomposition.cancel()
            runCurrent()
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            bitmap.recycle()
        }
    }

    @Test fun historicalPreviewPreferencesCannotOverrideTheApprovedValue() {
        context.getSharedPreferences("bingo_page_padding_preview_v1", Context.MODE_PRIVATE).edit()
            .putFloat("page_top_padding_dp", 72f).commit()
        assertEquals(15f, AppPageLayout.TopGap.value)
    }
}
