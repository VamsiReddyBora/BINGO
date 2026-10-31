package com.bingo.multiplayer.presentation.lobby

/** Shared by the live window observer and keyboard-dismissal regressions. */
internal object LobbyKeyboardPresence {
    fun visible(composeVisible: Boolean, windowImeVisible: Boolean?, windowHeightPx: Int,
        visibleBottomPx: Int, density: Float): Boolean {
        val validFrame = windowHeightPx > 0 && visibleBottomPx in 1..windowHeightPx
        val keyboardHeight = windowHeightPx - visibleBottomPx
        // Ignore system bars/small resize noise. The fallback supports fitted decor on older
        // Android versions where IME insets may already have been consumed before Compose.
        val resizedForKeyboard = validFrame && keyboardHeight > maxOf(
            100f * density.coerceAtLeast(1f), windowHeightPx * 0.15f)
        return windowImeVisible?.let { it || resizedForKeyboard }
            ?: (composeVisible || resizedForKeyboard)
    }
}
