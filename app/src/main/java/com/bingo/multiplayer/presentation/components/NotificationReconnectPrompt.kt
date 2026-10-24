package com.bingo.multiplayer.presentation.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.core.designsystem.NotificationPreferences
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.BroadcastMessageManager
import com.bingo.multiplayer.domain.notifications.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kept composed by the root; only the safe, fully rendered Home screen may show it. */
@Composable
internal fun NotificationReconnectPrompt(user: UserProfile?, canPrompt: Boolean) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val needsApproval by NotificationDeliveryStatus.needsGoogleAuthorization.collectAsState()
    val foreground by AppLifecycleObserver.isAppInForeground.collectAsState()
    val update by AppUpdateManager.updateState.collectAsState()
    val broadcast by BroadcastMessageManager.activeBroadcast.collectAsState()
    val scope = rememberCoroutineScope()
    // Survives rotation/navigation, resets for the next Activity launch. No persistent opt-out.
    var handledAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var renderedHome by remember { mutableStateOf(false) }
    var pendingAccountKey by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(canPrompt, foreground) {
        renderedHome = false
        if (canPrompt && foreground) {
            withFrameNanos { }
            delay(1250L) // Existing startup update/broadcast checks get first priority.
            renderedHome = true
        }
    }

    fun failed(expected: NotificationAccount, error: NotificationApiException) {
        if (NotificationRuntime.account(context) != expected) return
        NotificationDeliveryStatus.failed(error)
        NotificationDiagnostics.failure(context, null, null, "RECONNECT_FAILED", error)
        Toast.makeText(context, NotificationDeliveryStatus.message.value ?: "Couldn't reconnect notifications. Try again in Settings → Notifications.", Toast.LENGTH_LONG).show()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = NotificationReconnectPromptPolicy.pendingAccount(NotificationRuntime.account(context), pendingAccountKey)
        pendingAccountKey = null
        if (expected == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val google = try { GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(com.google.android.gms.common.api.ApiException::class.java) }
                    catch (error: com.google.android.gms.common.api.ApiException) { throw NotificationGoogleAuth.failure(error.statusCode) }
                NotificationGoogleAuth.reconnect(context.applicationContext, expected, google.id, google.idToken)
                if (NotificationRuntime.account(context) == expected)
                    Toast.makeText(context, "Google reconnected. Registering background notifications…", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: NotificationApiException) { failed(expected, error) }
            catch (_: Exception) { failed(expected, NotificationApiException(503, "Notification reconnection is temporarily unavailable")) }
            finally { busy = false }
        }
    }

    val account = NotificationRuntime.account(context)?.takeIf { it.uid == user?.uid }
    val blocked = busy || NotificationReconnectPromptPolicy.updateBlocksPrompt(update) ||
        (broadcast != null && NotificationPreferences.inAppNotificationsEnabled.value)
    if (!NotificationReconnectPromptPolicy.shouldShow(account, user?.provider == AuthProvider.GOOGLE,
            needsApproval, renderedHome && canPrompt, foreground, blocked, handledAccount)) return
    val expected = account ?: return
    fun finishChoice() { handledAccount = NotificationReconnectPromptPolicy.accountKey(expected) }

    AlertDialog(
        onDismissRequest = { finishChoice() },
        title = { Text("Reconnect notifications") },
        text = { Text("Reconnect your Google account to receive match invitations and friend alerts while Bingo is closed.") },
        confirmButton = {
            Button(onClick = {
                finishChoice()
                try {
                    val intent = NotificationGoogleAuth.intent(context, expected)
                    pendingAccountKey = NotificationReconnectPromptPolicy.accountKey(expected)
                    busy = true
                    launcher.launch(intent)
                } catch (error: NotificationApiException) {
                    pendingAccountKey = null; busy = false; failed(expected, error)
                } catch (_: RuntimeException) {
                    pendingAccountKey = null; busy = false
                    failed(expected, NotificationApiException(503, "Google sign-in is unavailable on this device"))
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg, contentColor = tokens.primaryButtonText)) {
                Text("Reconnect")
            }
        },
        dismissButton = { TextButton(onClick = { finishChoice() }) { Text("Later", color = tokens.textPrimary) } },
        containerColor = tokens.surface,
        titleContentColor = tokens.textPrimary,
        textContentColor = tokens.textSecondary
    )
}
