package com.bingo.multiplayer.presentation.game

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.bingo.multiplayer.core.designsystem.BingoTheme

internal object HostDisconnectedDialogSpec {
    const val title = "Host Disconnected"
    const val message = "Waiting for the host to continue the game."
    val properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
}

/** Visibility is owned by synchronized match state; the user cannot dismiss this hold. */
@Composable
internal fun HostDisconnectedDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = HostDisconnectedDialogSpec.properties,
        containerColor = BingoTheme.colors.surface,
        title = { Text(HostDisconnectedDialogSpec.title) },
        text = { Text(HostDisconnectedDialogSpec.message) },
        confirmButton = {}
    )
}
