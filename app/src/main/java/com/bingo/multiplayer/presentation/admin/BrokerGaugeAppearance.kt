package com.bingo.multiplayer.presentation.admin

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint

/** Desaturates the entire gauge, including native Canvas paint and glows. */
internal fun Modifier.brokerGaugeAppearance(connected: Boolean): Modifier = if (connected) this else drawWithCache {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    onDrawWithContent {
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        drawContext.canvas.restore()
    }
}
