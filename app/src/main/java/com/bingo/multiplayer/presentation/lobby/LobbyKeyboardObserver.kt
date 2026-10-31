package com.bingo.multiplayer.presentation.lobby

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private data class KeyboardWindowSample(val imeVisible: Boolean? = null,
    val height: Int = 0, val visibleBottom: Int = 0)

/** Observe the decor window without replacing Compose's inset listener or changing app layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberLobbyKeyboardVisible(): Boolean {
    val root = LocalView.current.rootView
    val density = LocalDensity.current.density
    val composeVisible = WindowInsets.isImeVisible
    var sample by remember(root) { mutableStateOf(KeyboardWindowSample()) }
    DisposableEffect(root) {
        val frame = Rect()
        val position = IntArray(2)
        fun readWindow() {
            if (!root.isAttachedToWindow) return
            root.getWindowVisibleDisplayFrame(frame)
            root.getLocationOnScreen(position)
            sample = KeyboardWindowSample(
                ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()),
                root.height, frame.bottom - position[1])
        }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { readWindow() }
        // Insets can change during the final animation frame without a new global layout.
        val drawListener = ViewTreeObserver.OnPreDrawListener { readWindow(); true }
        sample = KeyboardWindowSample()
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnPreDrawListener(drawListener)
        readWindow()
        onDispose {
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                root.viewTreeObserver.removeOnPreDrawListener(drawListener)
            }
        }
    }
    return LobbyKeyboardPresence.visible(composeVisible, sample.imeVisible,
        sample.height, sample.visibleBottom, density)
}
