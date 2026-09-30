package com.bingo.multiplayer.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import com.bingo.multiplayer.presentation.common.PlayerProfileDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authRepository: AuthRepository,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onNavigateToDashboard: () -> Unit = {},
    friendsRepository: FriendsRepository? = null
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val authState by authRepository.authState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val user = (authState as? AuthState.Authenticated)?.user ?: UserProfile(
        uid = "guest",
        displayName = "Player"
    )

    var nameInput by remember(user.displayName) { mutableStateOf(user.displayName) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showEditUsernameDialog by remember { mutableStateOf(false) }
    var editUsernameInput by remember { mutableStateOf("") }
    var isCheckingUsername by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var isNameSavedNotice by remember { mutableStateOf(false) }

    var searchUsernameInput by remember { mutableStateOf("") }
    var isSearchingPlayer by remember { mutableStateOf(false) }
    var foundPlayer by remember { mutableStateOf<PlayerRegistryEntry?>(null) }
    var searchAttempted by remember { mutableStateOf(false) }
    var selectedProfilePlayer by remember { mutableStateOf<PlayerProfileData?>(null) }

    val presenceMap by com.bingo.multiplayer.domain.network.PresenceManager.presenceFlow.collectAsState()
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000L)
            ticker = System.currentTimeMillis()
        }
    }

    // System Image Picker launcher for picking photo from local storage
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val success = authRepository.saveProfileAvatar(uri)
            if (success) {
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }


    if (showEditUsernameDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCheckingUsername) showEditUsernameDialog = false },
            title = { Text("Edit Username", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text("Enter your new unique username:", fontSize = 13.sp, color = tokens.cellNeutralText)
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = editUsernameInput,
                        onValueChange = { 
                            editUsernameInput = it.take(20).filter { char -> char.isLetterOrDigit() || char == '_' }
                            usernameError = null
                        },
                        singleLine = true,
                        placeholder = { Text("new_username") },
                        prefix = { Text("@", color = tokens.accentBrand) },
                        isError = usernameError != null,
                        supportingText = if (usernameError != null) { { Text(usernameError!!) } } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newUsername = editUsernameInput.trim()
                        if (newUsername.length < 3) {
                            usernameError = "Minimum 3 characters required"
                            return@Button
                        }
                        if (newUsername.lowercase() == user.username.lowercase()) {
                            showEditUsernameDialog = false
                            return@Button
                        }
                        
                        isCheckingUsername = true
                        usernameError = null
                        coroutineScope.launch {
                            // First, manually clear the old username from the cloud so someone else can claim it
                            val oldClean = user.username.lowercase()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val request = okhttp3.Request.Builder()
                                        .url("${com.bingo.multiplayer.domain.network.NetworkConfig.KEYVALUE_API_URL}/UpdateValue/${com.bingo.multiplayer.domain.network.NetworkConfig.KEYVALUE_APP_KEY}/user_$oldClean")
                                        .post(okhttp3.RequestBody.Companion.create(null, ""))
                                        .header("Content-Length", "0")
                                        .build()
                                    com.bingo.multiplayer.domain.network.NetworkConfig.httpClient.newCall(request).execute().close()
                                } catch (_: Exception) {}
                            }
                            
                            val success = authRepository.updateUsername(newUsername)
                            isCheckingUsername = false
                            if (success) {
                                showEditUsernameDialog = false
                                Toast.makeText(context, "Username updated!", Toast.LENGTH_SHORT).show()
                            } else {
                                usernameError = "Username is already taken"
                            }
                        }
                    },
                    enabled = !isCheckingUsername,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand)
                ) {
                    if (isCheckingUsername) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditUsernameDialog = false },
                    enabled = !isCheckingUsername
                ) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            },
            containerColor = tokens.surface,
            titleContentColor = tokens.cellNeutralText,
            textContentColor = tokens.cellNeutralText
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to sign out of your account?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        authRepository.signOut()
                        onSignedOut()
                    }
                ) {
                    Text("Sign Out", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = tokens.cellNeutralText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 1: Profile Customization (Photo + Name) ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PROFILE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Profile Photo with Camera Badge
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier
                            .size(86.dp)
                            .clickable { photoPickerLauncher.launch("image/*") }
                    ) {
                        PlayerAvatar(
                            avatarPathOrUri = user.avatarUrl,
                            displayName = user.displayName,
                            size = 86.dp,
                            borderWidth = 2.dp,
                            borderColor = tokens.accentBrand,
                            username = user.username
                        )

                        // Camera edit icon badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(tokens.accentBrand)
                                .padding(5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Change photo",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Photo Action Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { photoPickerLauncher.launch("image/*") },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, tokens.surfaceBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = tokens.cellNeutralText
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (user.avatarUrl == null) "Set Photo" else "Change Photo",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (user.avatarUrl != null) {
                            TextButton(
                                onClick = {
                                    authRepository.removeProfileAvatar()
                                    Toast.makeText(context, "Photo removed", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Remove",
                                    fontSize = 12.sp,
                                    color = Color(0xFFDC2626),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Player Name Field
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            if (it.length <= 25) {
                                nameInput = it
                                isNameSavedNotice = false
                            }
                        },
                        label = { Text("Display Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentBrand,
                            unfocusedBorderColor = tokens.surfaceBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Save Name Button
                    val isNameChanged = nameInput.trim() != user.displayName && nameInput.trim().isNotEmpty()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isNameSavedNotice) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Name saved",
                                    fontSize = 12.sp,
                                    color = Color(0xFF16A34A),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Button(
                            onClick = {
                                val success = authRepository.updateDisplayName(nameInput)
                                if (success) {
                                    isNameSavedNotice = true
                                    Toast.makeText(context, "Display name updated", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = isNameChanged,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.accentBrand
                            )
                        ) {
                            Text(
                                text = "Save Name",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 2: Account Details ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "ACCOUNT DETAILS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Sign-in Provider
                    AccountDetailRow(
                        icon = Icons.Default.Lock,
                        label = "Sign-in Method",
                        value = when (user.provider) {
                            AuthProvider.GOOGLE -> "Google Account"
                            AuthProvider.GUEST -> "Guest (Local)"
                            AuthProvider.PLAY_GAMES -> "Play Games"
                        },
                        badgeColor = if (user.provider == AuthProvider.GOOGLE) Color(0xFF1A73E8) else tokens.cellNeutralText.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Email
                    AccountDetailRow(
                        icon = Icons.Default.Email,
                        label = "Email",
                        value = user.email ?: "No email linked (Guest mode)"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Player ID (Unique Username for Search)
                    Surface(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Player ID", "@${user.playerId}"))
                            Toast.makeText(context, "Player ID @${user.playerId} copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent
                    ) {
                        AccountDetailRow(
                            icon = Icons.Default.Person,
                            label = "Player ID (Tap to Copy Username)",
                            value = "@${user.playerId}",
                            onEdit = {
                                editUsernameInput = user.username
                                usernameError = null
                                showEditUsernameDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Section 2b: Player Search & Directory ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "SEARCH PLAYERS BY USERNAME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchUsernameInput,
                            onValueChange = { searchUsernameInput = it },
                            placeholder = {
                                Text("Search unique @username", fontSize = 13.sp, color = tokens.cellNeutralText.copy(alpha = 0.4f))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = tokens.accentBrand,
                                unfocusedBorderColor = tokens.surfaceBorder
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (searchUsernameInput.isNotBlank()) {
                                    isSearchingPlayer = true
                                    foundPlayer = null
                                    searchAttempted = true
                                    coroutineScope.launch {
                                        foundPlayer = authRepository.sessionManager.searchPlayerByUsername(searchUsernameInput)
                                        foundPlayer?.let { fp ->
                                            val cleanUser = fp.username.trim().lowercase().removePrefix("@")
                                            com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresence(cleanUser)
                                        }
                                        isSearchingPlayer = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                            enabled = !isSearchingPlayer && searchUsernameInput.isNotBlank()
                        ) {
                            if (isSearchingPlayer) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Find", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    val currentFoundPlayer = foundPlayer
                    if (currentFoundPlayer != null) {
                        val player = currentFoundPlayer
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.backgroundSecondary,
                            border = BorderStroke(1.dp, tokens.surfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val cleanUser = player.username.trim().lowercase().removePrefix("@")
                                    val livePres = presenceMap[cleanUser]
                                    val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                                    selectedProfilePlayer = PlayerProfileData.fromRegistryEntry(player.copy(lastSeenTimestamp = effectiveTs))
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlayerAvatar(
                                    avatarPathOrUri = player.avatarUrl,
                                    displayName = player.displayName,
                                    size = 40.dp,
                                    borderWidth = 1.5.dp,
                                    borderColor = tokens.accentBrand,
                                    username = player.username
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = player.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                val cleanUser = player.username.trim().lowercase().removePrefix("@")
                                val livePres = presenceMap[cleanUser]
                                val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                                if (ticker >= 0L) Unit
                                val statusText = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, effectiveTs)
                                val isOnline = statusText.equals("online", ignoreCase = true)
                                val displayStatus = when {
                                    isOnline -> "online"
                                    statusText.equals("offline", ignoreCase = true) -> "offline"
                                    else -> statusText
                                }
                                val statusColor = when {
                                    isOnline -> Color(0xFF16A34A)
                                    displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
                                    else -> Color(0xFFEAB308)
                                }

                                Text(
                                    text = displayStatus,
                                    fontSize = 11.sp,
                                    color = statusColor,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else if (searchAttempted && !isSearchingPlayer) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No player found with username \"$searchUsernameInput\"",
                            fontSize = 12.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 3: Player Dashboard & Friends Social Hub ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DASHBOARD & FRIENDS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.5f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    UpcomingFeatureItem(
                        icon = Icons.Default.Insights,
                        title = "Player Dashboard & Stats",
                        subtitle = "Track win streaks, tier ranks, XP progress, and match history."
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    UpcomingFeatureItem(
                        icon = Icons.Default.Group,
                        title = "Friends & Social Network",
                        subtitle = "Live online statuses, search players, and send 1-tap match invites."
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onNavigateToDashboard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Insights,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Dashboard & Social Hub",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 4: Sign Out ──
            OutlinedButton(
                onClick = { showSignOutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFDC2626)
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sign Out",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        val profileToDisplay = selectedProfilePlayer
        if (profileToDisplay != null) {
            PlayerProfileDialog(
                playerData = profileToDisplay,
                currentUser = user,
                friendsRepository = friendsRepository,
                onDismiss = { selectedProfilePlayer = null }
            )
        }
    }
}

@Composable
private fun AccountDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    badgeColor: Color? = null,
    onEdit: (() -> Unit)? = null
) {
    val tokens = BingoTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.cellNeutralText.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = tokens.cellNeutralText.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                color = badgeColor ?: tokens.cellNeutralText,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        if (onEdit != null) {
            androidx.compose.material3.IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = tokens.accentBrand,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun UpcomingFeatureItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val tokens = BingoTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.background)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.cellNeutralText.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.cellNeutralText.copy(alpha = 0.8f)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = tokens.cellNeutralText.copy(alpha = 0.5f)
            )
        }
    }
}
