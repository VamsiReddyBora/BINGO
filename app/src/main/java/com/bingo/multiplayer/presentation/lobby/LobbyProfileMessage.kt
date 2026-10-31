package com.bingo.multiplayer.presentation.lobby

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.LobbyProfileMessage
import com.bingo.multiplayer.presentation.common.noRippleCombinedClickable
import com.bingo.multiplayer.presentation.game.BouncingDotsIndicator

@Composable
internal fun LobbyActionRow(enabled: Boolean, onChat: () -> Unit, content: @Composable () -> Unit) {
    val colors = BingoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        OutlinedIconButton(onClick = onChat, enabled = enabled, modifier = Modifier.size(50.dp),
            shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceBorder)) {
            Icon(Icons.Default.ChatBubbleOutline, "Lobby message", tint = if (enabled) colors.cellNeutralText else colors.textMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LobbyProfileMessageBox(message: LobbyProfileMessage?, tuning: LobbyBubbleTuning,
    onDeleteRequest: (() -> Unit)? = null) {
    if (message == null || message.text.isEmpty()) return
    val colors = BingoTheme.colors
    LobbyBubbleFrame(tuning, message.id,
        modifier = if (onDeleteRequest != null) Modifier.noRippleCombinedClickable(
            onClick = {}, onLongClickLabel = "Delete my lobby message", onLongClick = onDeleteRequest) else Modifier) {
        Text(message.text.replace('\n', ' '), color = colors.badgeContent, fontSize = tuning.fontSizeSp.sp,
            maxLines = 1, softWrap = false,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE,
                initialDelayMillis = tuning.scrollPauseMs, delayMillis = tuning.scrollPauseMs,
                velocity = tuning.scrollSpeedDp.dp))
    }
}

@Composable
internal fun LobbyProfileTypingBubble(playerId: String, playerName: String, tuning: LobbyBubbleTuning) {
    val colors = BingoTheme.colors
    LobbyBubbleFrame(tuning, "typing:$playerId",
        Modifier.semantics { contentDescription = "$playerName is typing" }) {
        BouncingDotsIndicator(color = colors.badgeContent,
            modifier = Modifier.padding(vertical = 3.dp), dotSize = 3.5.dp, bounceHeight = 2.dp)
    }
}

/** The input replaces only the owner's bubble, in the same position as the final message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LobbyInlineMessageField(draft: LobbyInlineMessageDraft, tuning: LobbyBubbleTuning,
    focusRequest: Int, keyboardVisible: Boolean, onSubmit: () -> Unit,
    onFocusChanged: (Boolean) -> Unit) {
    val colors = BingoTheme.colors
    val focus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var placed by remember { mutableStateOf(false) }
    var activated by remember(focusRequest) { mutableStateOf(false) }
    // The editor is inserted above the avatar, often outside the current scroll viewport.
    // Wait until its focus node and scroll parents are placed, then ask Android for the IME.
    LaunchedEffect(focusRequest, placed, windowFocused) {
        if (!placed || !windowFocused || activated) return@LaunchedEffect
        focus.requestFocus()
        withFrameNanos { }
        keyboard?.show()
        activated = true
        bringIntoView.bringIntoView()
    }
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) bringIntoView.bringIntoView()
    }
    Column(Modifier.bringIntoViewRequester(bringIntoView)) {
        LobbyBubbleFrame(tuning, "inline-draft") {
            BasicTextField(value = draft.text, onValueChange = draft::change, enabled = !draft.sending,
                singleLine = true, textStyle = TextStyle(color = colors.badgeContent, fontSize = tuning.fontSizeSp.sp),
                cursorBrush = SolidColor(colors.badgeContent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }, onDone = { onSubmit() }),
                modifier = Modifier.widthIn(min = 80.dp, max = (tuning.maxWidthDp - tuning.paddingDp * 2).dp)
                    .focusRequester(focus)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .onGloballyPositioned { placed = true }
                    .semantics { contentDescription = "Lobby message input" }
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                            if (it.type == KeyEventType.KeyUp) onSubmit()
                            true
                        } else false
                    },
                decorationBox = { field ->
                    Box {
                        if (draft.text.isEmpty()) Text("Message…", color = colors.textMuted,
                            fontSize = tuning.fontSizeSp.sp, maxLines = 1)
                        field()
                    }
                })
            if (draft.sending) LinearProgressIndicator(Modifier.width(40.dp).height(2.dp), color = colors.accentBrand)
        }
        draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp) }
    }
}

@Composable
private fun LobbyBubbleFrame(tuning: LobbyBubbleTuning, animationKey: String, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = BingoTheme.colors
    key(animationKey) {
        val progress = remember { Animatable(if (tuning.animate) 0f else 1f) }
        LaunchedEffect(animationKey, tuning.animate) {
            if (tuning.animate) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(tuning.durationMs, easing = LinearEasing))
            } else progress.snapTo(1f)
        }
        Column(Modifier.widthIn(max = tuning.maxWidthDp.dp).offset(y = tuning.yDp.dp).graphicsLayer {
            val frame = LobbyBubbleMotion.frame(progress.value, tuning)
            transformOrigin = TransformOrigin((22.dp.toPx() - tuning.xDp.dp.toPx()) / size.width.coerceAtLeast(1f),
                1f + (tuning.avatarGapDp.dp.toPx() + 28.dp.toPx() - tuning.yDp.dp.toPx()) / size.height.coerceAtLeast(1f))
            scaleX = frame.scale; scaleY = frame.scale
            translationY = frame.translationYDp.dp.toPx(); alpha = frame.alpha
        }.then(modifier)) {
            Surface(shape = RoundedCornerShape(tuning.cornerDp.dp), color = colors.badgeSurface,
                modifier = Modifier.widthIn(min = 40.dp, max = tuning.maxWidthDp.dp)
                    .then(if (tuning.fitShortMessages) Modifier else Modifier.fillMaxWidth())
                    .heightIn(min = tuning.minHeightDp.dp)) {
                Column(Modifier.padding(horizontal = tuning.paddingDp.dp, vertical = 3.dp), content = content)
            }
            if (tuning.tailHeightDp > 0f) Canvas(Modifier.offset(x = 4.dp, y = (-1).dp)
                .size(width = tuning.tailWidthDp.dp, height = tuning.tailHeightDp.dp)) {
                drawPath(Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f)
                    lineTo(-size.width * 0.5f, size.height); close()
                }, colors.badgeSurface)
            }
        }
    }
}
