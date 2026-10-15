package com.bingo.multiplayer.presentation.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.FriendRequest
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.GameInvite
import com.bingo.multiplayer.domain.network.GameInviteManager
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import kotlinx.coroutines.launch

/**
 * Requests and invites tab displaying pending friend requests and real-time game invitations.
 */
@Composable
fun RequestsTabContent(
    user: UserProfile,
    pendingRequests: List<FriendRequest>,
    onAcceptFriendRequest: (FriendRequest) -> Unit,
    onDeclineFriendRequest: (FriendRequest) -> Unit,
    onAcceptInvite: ((GameInvite) -> Unit)? = null
) {
    val tokens = BingoTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var invites by remember { mutableStateOf<List<GameInvite>>(emptyList()) }
    var loadingInvites by remember { mutableStateOf(true) }

    // Initial fetch of pending invites
    LaunchedEffect(user.username) {
        loadingInvites = true
        invites = GameInviteManager.fetchInvitesForUser(user.username)
        loadingInvites = false
    }

    // Real-time reactive MQTT listener for instant sub-second invite alerts (zero polling!)
    DisposableEffect(user.username) {
        var isDisposed = false
        val listener = GameInviteManager.startInviteListener(user.username) { newInvite ->
            if (!isDisposed) {
                invites = (listOf(newInvite) + invites.filter { it.roomCode != newInvite.roomCode })
                loadingInvites = false
            }
        }
        onDispose {
            isDisposed = true
            listener.close()
        }
    }

    // Also collect foreground in-memory push
    LaunchedEffect(Unit) {
        GameInviteManager.foregroundInviteFlow.collect { newInvite ->
            invites = (listOf(newInvite) + invites.filter { it.roomCode != newInvite.roomCode })
            loadingInvites = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── 1. FRIEND REQUESTS SECTION ──
        Text(
            text = "PENDING FRIEND REQUESTS (${pendingRequests.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.cellNeutralText.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (pendingRequests.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.cellNeutralBg
            ) {
                Text(
                    text = "No pending friend requests.",
                    modifier = Modifier.padding(18.dp),
                    textAlign = TextAlign.Center,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pendingRequests.forEach { req ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerAvatar(
                                avatarPathOrUri = req.fromAvatarUrl,
                                displayName = req.fromDisplayName,
                                size = 42.dp,
                                username = req.fromUsername
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = req.fromDisplayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "wants to be your friend",
                                    fontSize = 11.5.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.6f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onAcceptFriendRequest(req) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = tokens.primaryButtonBg,
                                        contentColor = tokens.primaryButtonText
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Accept", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                                }

                                OutlinedButton(
                                    onClick = { onDeclineFriendRequest(req) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Ignore", fontSize = 12.sp, color = tokens.cellNeutralText)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 2. MATCH INVITATIONS SECTION ──
        Text(
            text = "MATCH INVITATIONS (${invites.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = tokens.cellNeutralText.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (loadingInvites) {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accentBrand, modifier = Modifier.size(24.dp))
            }
        } else if (invites.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = tokens.cellNeutralBg
            ) {
                Text(
                    text = "No pending game invites.",
                    modifier = Modifier.padding(18.dp),
                    textAlign = TextAlign.Center,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                invites.forEach { invite ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Invite from ${invite.fromDisplayName}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText
                                )
                                Text(
                                    text = "@${invite.fromUsername} invited you to room: #${invite.roomCode}",
                                    fontSize = 12.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.7f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            GameInviteManager.removeInvite(user.username, invite.roomCode)
                                        }
                                        invites = invites.filter { it.roomCode != invite.roomCode }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.padding(end = 6.dp)
                                ) {
                                    Text("Decline", fontSize = 11.sp, color = tokens.cellNeutralText)
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            GameInviteManager.removeInvite(user.username, invite.roomCode)
                                        }
                                        invites = invites.filter { it.roomCode != invite.roomCode }
                                        if (onAcceptInvite != null) {
                                            onAcceptInvite(invite)
                                        } else {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Bingo Room Code", invite.roomCode))
                                            Toast.makeText(context, "Room Code ${invite.roomCode} copied! Join Online Room.", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = tokens.primaryButtonBg,
                                        contentColor = tokens.primaryButtonText
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Accept & Play", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(84.dp))
    }
}
