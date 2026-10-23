package com.bingo.multiplayer.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Cards/rows retain click semantics and actions without painting a full-area ripple. */
internal fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.noRippleCombinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, onClickLabel = onClickLabel, role = role,
        onLongClickLabel = onLongClickLabel, onLongClick = onLongClick,
        onDoubleClick = onDoubleClick, onClick = onClick)
}

/** Material's clickable Surface has its own ripple; use the non-clickable surface for cards. */
@Composable
internal fun NoRippleSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    Surface(modifier = modifier.noRippleClickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = shape, color = color, contentColor = contentColor,
        tonalElevation = tonalElevation, shadowElevation = shadowElevation, border = border,
        content = content)
}
