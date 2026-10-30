package com.bingo.multiplayer.presentation.game

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bingo.multiplayer.core.designsystem.AnimationFeatureFlags
import com.bingo.multiplayer.core.designsystem.AnimationPreferences

/** Decorative drawing only: no touch interception, network events, sound or vibration. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun ResultEmojiOverlay(
    result: StampResultType,
    seed: Long,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!AnimationFeatureFlags.WINNING_EMOJI_ANIMATION_ENABLED ||
        !AnimationPreferences.winningEmojiAnimationEnabled) return

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val finish = rememberUpdatedState(onFinished)
    val burst = remember(result, seed) { ResultEmojiMotion.create(result, seed) }
    val elapsed = remember(burst) { Animatable(0f) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val pool = remember(burst) { burst.particles.map { it.emoji }.distinct() }
    val images = remember(pool, measurer, density, layoutDirection) {
        pool.associateWith {
            // Rasterize each complete emoji (including ZWJ sequences) once at the largest size.
            // Draw frames reuse these textures instead of repainting per-particle text layouts.
            val label = measurer.measure(it, TextStyle(fontSize = 75.sp))
            ImageBitmap(label.size.width.coerceAtLeast(1), label.size.height.coerceAtLeast(1)).also { image ->
                CanvasDrawScope().draw(density, layoutDirection, GraphicsCanvas(image),
                    Size(image.width.toFloat(), image.height.toFloat())) { drawText(label) }
                image.prepareToDraw()
            }
        }
    }
    val sprites = remember(burst, images, density) {
        with(density) {
            burst.particles.map { particle ->
                val image = images.getValue(particle.emoji)
                ResultEmojiSprite(particle, image, particle.sizeDp.dp.toPx() / 75.sp.toPx(),
                    Offset(-image.width / 2f, -image.height / 2f))
            }
        }
    }
    val frame = remember { ResultEmojiFrame() }

    DisposableEffect(view) {
        val window = context.resultAnimationActivity()?.window
        val previous = window?.attributes?.preferredRefreshRate ?: 0f
        // On modern Android this is a seamless frame-rate hint, never a forced display mode.
        // Older versions require a supported rate; leave other owners' explicit preferences alone.
        val supportsHint = Build.VERSION.SDK_INT >= 31 || view.display?.supportedModes?.any {
            kotlin.math.abs(it.refreshRate - 120f) < 1f
        } == true
        val applied = window != null && previous == 0f &&
            window.attributes.preferredDisplayModeId == 0 && supportsHint && runCatching {
                window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
                true
            }.getOrDefault(false)
        onDispose {
            if (applied && window != null) runCatching {
                // Do not overwrite a preference subsequently changed by another window owner.
                if (window.attributes.preferredRefreshRate == 120f) {
                    window.attributes = window.attributes.apply { preferredRefreshRate = previous }
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) finish.value()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(burst, lifecycle) {
        val motionAllowed = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
        if (motionAllowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            elapsed.animateTo(burst.durationMillis.toFloat(), tween(burst.durationMillis.toInt(), easing = LinearEasing))
        }
        finish.value()
    }

    Canvas(modifier.fillMaxSize().clipToBounds().clearAndSetSemantics { }) {
        // Only this draw pass reads the clock; the board/chat do not recompose for every frame.
        val time = elapsed.value
        for (sprite in sprites) {
            if (!sprite.particle.writeFrame(time, frame)) continue
            withTransform({
                translate(frame.x * size.width, frame.y * size.height)
                rotate(frame.rotation, pivot = Offset.Zero)
                scale(sprite.scale, sprite.scale, pivot = Offset.Zero)
            }) {
                drawImage(sprite.image, topLeft = sprite.topLeft)
            }
        }
    }
}

private data class ResultEmojiSprite(
    val particle: ResultEmojiParticle, val image: ImageBitmap, val scale: Float, val topLeft: Offset
)

private tailrec fun Context.resultAnimationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.resultAnimationActivity() else null
    else -> null
}
