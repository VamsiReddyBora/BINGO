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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.graphicsLayer
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.core.designsystem.computeContrastText
import com.bingo.multiplayer.domain.network.EmojiPreferences
import com.bingo.multiplayer.presentation.components.AnimatedEmoji
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.UpdateState
import com.bingo.multiplayer.domain.network.BingoNotificationManager
import com.bingo.multiplayer.domain.network.BingoFcmManager
import com.google.firebase.messaging.FirebaseMessaging
import android.content.Intent
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.bingo.multiplayer.domain.network.QuickChatPreferences
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import com.bingo.multiplayer.presentation.common.PlayerProfileDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ColorPickerTarget {
    APP_ACCENT,
    MY_PICK,
    OPPONENT_PICK,
    RECENT_PICK,
    LINE_COMPLETION,
    CELL_BORDER
}

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

    // Dropdown arrow collapsed/expanded state for every major card (default: all collapsed)
    var isProfileExpanded by remember { mutableStateOf(false) }
    var isAccountExpanded by remember { mutableStateOf(false) }
    var isDashboardExpanded by remember { mutableStateOf(false) }
    var isAppearanceExpanded by remember { mutableStateOf(false) }
    var isEmojisExpanded by remember { mutableStateOf(false) }
    var isQuickChatExpanded by remember { mutableStateOf(false) }

    var selectedProfilePlayer by remember { mutableStateOf<PlayerProfileData?>(null) }

    var quickChatPhrases by remember { mutableStateOf(QuickChatPreferences.getPhrases(context)) }
    var editingPhraseIndex by remember { mutableStateOf<Int?>(null) }
    var editingPhraseText by remember { mutableStateOf("") }
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var newPhraseText by remember { mutableStateOf("") }
    val isDarkTheme = ThemePreferences.isDarkTheme.value
    val selectedAccentId = ThemePreferences.accentColorId.value
    var activeColorPickerTarget by remember { mutableStateOf<ColorPickerTarget?>(null) }

    var favoriteEmojis by remember {
        mutableStateOf(EmojiPreferences.getFavoriteEmojis(context))
    }
    var showAddFavoriteEmojiDialog by remember { mutableStateOf(false) }

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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    )
                ) {
                    if (isCheckingUsername) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tokens.primaryButtonText, strokeWidth = 2.dp)
                    } else {
                        Text("Save", fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
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

    if (editingPhraseIndex != null) {
        val index = editingPhraseIndex!!
        AlertDialog(
            onDismissRequest = { editingPhraseIndex = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Edit Quick Chat #${index + 1}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column {
                    Text(
                        text = "Customize this in-game phrase (max ${QuickChatPreferences.MAX_PHRASE_LENGTH} characters):",
                        fontSize = 13.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editingPhraseText,
                        onValueChange = {
                            if (it.length <= QuickChatPreferences.MAX_PHRASE_LENGTH) {
                                editingPhraseText = it
                            }
                        },
                        singleLine = true,
                        placeholder = { Text("e.g. Good move! 🔥") },
                        supportingText = {
                            Text(
                                text = "${editingPhraseText.length}/${QuickChatPreferences.MAX_PHRASE_LENGTH}",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                color = if (editingPhraseText.length == QuickChatPreferences.MAX_PHRASE_LENGTH) tokens.accentOpponent else tokens.cellNeutralText.copy(alpha = 0.5f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentBrand,
                            cursorColor = tokens.accentBrand
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editingPhraseText.trim()
                        if (trimmed.isNotBlank()) {
                            QuickChatPreferences.savePhrase(context, index, trimmed)
                            quickChatPhrases = QuickChatPreferences.getPhrases(context)
                            Toast.makeText(context, "Phrase updated!", Toast.LENGTH_SHORT).show()
                        }
                        editingPhraseIndex = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPhraseIndex = null }) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    if (showAddPhraseDialog) {
        AlertDialog(
            onDismissRequest = { showAddPhraseDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Add Quick Chat Message",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column {
                    Text(
                        text = "Add a new phrase for live match communication (max ${QuickChatPreferences.MAX_PHRASE_LENGTH} chars):",
                        fontSize = 13.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPhraseText,
                        onValueChange = {
                            if (it.length <= QuickChatPreferences.MAX_PHRASE_LENGTH) {
                                newPhraseText = it
                            }
                        },
                        singleLine = true,
                        placeholder = { Text("e.g. Well played! 👏") },
                        supportingText = {
                            Text(
                                text = "${newPhraseText.length}/${QuickChatPreferences.MAX_PHRASE_LENGTH}",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                color = if (newPhraseText.length == QuickChatPreferences.MAX_PHRASE_LENGTH) tokens.accentOpponent else tokens.cellNeutralText.copy(alpha = 0.5f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentBrand,
                            cursorColor = tokens.accentBrand
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newPhraseText.trim()
                        if (trimmed.isNotBlank()) {
                            val added = QuickChatPreferences.addPhrase(context, trimmed)
                            if (added) {
                                quickChatPhrases = QuickChatPreferences.getPhrases(context)
                                Toast.makeText(context, "New phrase added!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Maximum phrases reached (${QuickChatPreferences.MAX_ALLOWED_PHRASES})", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showAddPhraseDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    )
                ) {
                    Text("Add", fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhraseDialog = false }) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    val popularPickerEmojis = remember {
        listOf(
            "🔥", "🏆", "🥇", "🎉", "🎊", "✨", "⭐", "🌟", "🥳", "👑",
            "❤️", "💖", "💀", "🚀", "⚡", "💥", "🎈", "💎", "🦾", "💪",
            "🫡", "😎", "🤩", "🤑", "🍿", "🎲", "🧩", "🎳", "😂", "🤣",
            "🎯", "👏", "😱", "😭", "🥺", "😩", "🤧", "😡", "👍", "👌",
            "✌️", "🤝", "🙏", "👀", "💃", "🕺", "🛸", "🦄", "🍀", "🌈"
        )
    }



    if (showAddFavoriteEmojiDialog) {
        AlertDialog(
            onDismissRequest = { showAddFavoriteEmojiDialog = false },
            title = {
                Text(
                    text = "Add Favourite Emoji",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column {
                    Text(
                        text = "Select an emoji to pin at the start of your match reaction strip:",
                        fontSize = 12.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(260.dp)
                    ) {
                        items(popularPickerEmojis.size) { idx ->
                            val emoji = popularPickerEmojis[idx]
                            val isAlreadyFav = favoriteEmojis.contains(emoji)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isAlreadyFav) tokens.accentBrand.copy(alpha = 0.15f) else tokens.backgroundSecondary,
                                border = BorderStroke(
                                    width = if (isAlreadyFav) 1.5.dp else 0.5.dp,
                                    color = if (isAlreadyFav) tokens.accentBrand else tokens.surfaceBorder
                                ),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable {
                                        if (isAlreadyFav) {
                                            Toast.makeText(context, "Already in favourites", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val added = EmojiPreferences.addFavoriteEmoji(context, emoji)
                                            if (added) {
                                                favoriteEmojis = EmojiPreferences.getFavoriteEmojis(context)
                                                Toast.makeText(context, "Added to favourites!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Maximum ${EmojiPreferences.MAX_FAVORITES} favourites reached", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showAddFavoriteEmojiDialog = false
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    AnimatedEmoji(emoji = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddFavoriteEmojiDialog = false }) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    val currentPickerTarget = activeColorPickerTarget
    if (currentPickerTarget != null) {
        val initialHex = when (currentPickerTarget) {
            ColorPickerTarget.APP_ACCENT -> ThemePreferences.customColorHex.value
            ColorPickerTarget.MY_PICK -> ThemePreferences.customMyPickHex.value ?: if (isDarkTheme) "#38BDF8" else "#EADBFF"
            ColorPickerTarget.OPPONENT_PICK -> ThemePreferences.customOpponentPickHex.value ?: if (isDarkTheme) "#F97316" else "#D3EEFF"
            ColorPickerTarget.RECENT_PICK -> ThemePreferences.customRecentPickHex.value ?: if (isDarkTheme) "#FB923C" else "#FFE0B8"
            ColorPickerTarget.LINE_COMPLETION -> ThemePreferences.customCompletedLineHex.value ?: ThemePreferences.DEFAULT_COMPLETED_LINE_HEX
            ColorPickerTarget.CELL_BORDER -> ThemePreferences.cellBorderColorHex.value
        }
        val targetTitle = when (currentPickerTarget) {
            ColorPickerTarget.APP_ACCENT -> "App Theme Palette"
            ColorPickerTarget.MY_PICK -> "My Pick Cell Color"
            ColorPickerTarget.OPPONENT_PICK -> "Opponent Pick Cell Color"
            ColorPickerTarget.RECENT_PICK -> "Recent Pick Cell Color"
            ColorPickerTarget.LINE_COMPLETION -> "Line Completion Cell Color"
            ColorPickerTarget.CELL_BORDER -> "Cell Border Color"
        }

        var hexInputText by remember(currentPickerTarget) { mutableStateOf(initialHex) }
        val currentHue = remember(currentPickerTarget) {
            val initialColorInt = try {
                val clean = if (initialHex.startsWith("#")) initialHex else "#$initialHex"
                android.graphics.Color.parseColor(clean)
            } catch (_: Exception) {
                0xFF7C3AED.toInt()
            }
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(initialColorInt, hsv)
            mutableFloatStateOf(hsv[0])
        }

        val parsedColor: Color? = try {
            val clean = if (hexInputText.startsWith("#")) hexInputText else "#$hexInputText"
            if (clean.length == 7) Color(android.graphics.Color.parseColor(clean)) else null
        } catch (_: Exception) {
            null
        }

        val spectrumShades = remember {
            listOf(
                "#EF4444", "#DC2626", "#B91C1C", "#F97316", "#EA580C", "#C2410C",
                "#F59E0B", "#D97706", "#B45309", "#84CC16", "#65A30D", "#4D7C0F",
                "#10B981", "#059669", "#047857", "#14B8A6", "#0D9488", "#0F766E",
                "#06B6D4", "#0284C7", "#0369A1", "#3B82F6", "#2563EB", "#1D4ED8",
                "#6366F1", "#4F46E5", "#4338CA", "#8B5CF6", "#7C3AED", "#6D28D9",
                "#A855F7", "#9333EA", "#7E22CE", "#EC4899", "#DB2777", "#BE185D",
                "#F43F5E", "#E11D48", "#BE123C", "#64748B", "#475569", "#334155"
            )
        }

        AlertDialog(
            onDismissRequest = { activeColorPickerTarget = null },
            containerColor = tokens.surface,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = parsedColor ?: tokens.accentBrand,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = targetTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Pick a shade from the spectrum or slide the hue controller to customize your chosen color:",
                        fontSize = 12.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Preview Card
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(parsedColor ?: tokens.accentBrand)
                                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = hexInputText.uppercase(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.cellNeutralText
                                    )
                                    Text(
                                        text = if (parsedColor != null) "Ready to apply" else "Invalid Hex Code",
                                        fontSize = 10.5.sp,
                                        color = if (parsedColor != null) Color(0xFF16A34A) else Color(0xFFDC2626)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = parsedColor ?: tokens.accentBrand
                            ) {
                                Text(
                                    text = "Preview",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = parsedColor?.let { computeContrastText(it) } ?: Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Spectrum Grid
                    Text(
                        text = "COLOR SPECTRUM",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(7),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(180.dp)
                    ) {
                        items(spectrumShades.size) { idx ->
                            val shadeHex = spectrumShades[idx]
                            val shadeColor = Color(android.graphics.Color.parseColor(shadeHex))
                            val isShadeSelected = hexInputText.equals(shadeHex, ignoreCase = true)

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(shadeColor)
                                    .border(
                                        width = if (isShadeSelected) 2.5.dp else 0.5.dp,
                                        color = if (isShadeSelected) Color.White else Color.Black.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        hexInputText = shadeHex
                                        val colorInt = android.graphics.Color.parseColor(shadeHex)
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(colorInt, hsv)
                                        currentHue.floatValue = hsv[0]
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isShadeSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hue Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HUE CONTROLLER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "${currentHue.floatValue.toInt()}°",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.cellNeutralText.copy(alpha = 0.6f)
                        )
                    }

                    Slider(
                        value = currentHue.floatValue,
                        onValueChange = { hue ->
                            currentHue.floatValue = hue
                            val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.85f))
                            hexInputText = String.format("#%06X", 0xFFFFFF and colorInt)
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            thumbColor = parsedColor ?: tokens.accentBrand,
                            activeTrackColor = parsedColor ?: tokens.accentBrand
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hex input field
                    OutlinedTextField(
                        value = hexInputText,
                        onValueChange = { input ->
                            if (input.length <= 7) {
                                hexInputText = input
                                try {
                                    val clean = if (input.startsWith("#")) input else "#$input"
                                    if (clean.length == 7) {
                                        val colorInt = android.graphics.Color.parseColor(clean)
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(colorInt, hsv)
                                        currentHue.floatValue = hsv[0]
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        label = { Text("Hex Code (#RRGGBB)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = parsedColor ?: tokens.accentBrand,
                            unfocusedBorderColor = tokens.surfaceBorder
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        parsedColor?.let {
                            val clean = if (hexInputText.startsWith("#")) hexInputText else "#$hexInputText"
                            when (currentPickerTarget) {
                                ColorPickerTarget.APP_ACCENT -> {
                                    ThemePreferences.setCustomColor(context, clean)
                                    Toast.makeText(context, "Custom theme color applied!", Toast.LENGTH_SHORT).show()
                                }
                                ColorPickerTarget.MY_PICK -> {
                                    ThemePreferences.setMyPickColor(context, clean)
                                    Toast.makeText(context, "My pick cell color updated!", Toast.LENGTH_SHORT).show()
                                }
                                ColorPickerTarget.OPPONENT_PICK -> {
                                    ThemePreferences.setOpponentPickColor(context, clean)
                                    Toast.makeText(context, "Opponent pick cell color updated!", Toast.LENGTH_SHORT).show()
                                }
                                ColorPickerTarget.RECENT_PICK -> {
                                    ThemePreferences.setRecentPickColor(context, clean)
                                    Toast.makeText(context, "Recent pick cell color updated!", Toast.LENGTH_SHORT).show()
                                }
                                ColorPickerTarget.LINE_COMPLETION -> {
                                    ThemePreferences.setCompletedLineColor(context, clean)
                                    Toast.makeText(context, "Line completion cell color updated!", Toast.LENGTH_SHORT).show()
                                }
                                ColorPickerTarget.CELL_BORDER -> {
                                    ThemePreferences.setCellBorderColor(context, clean)
                                    Toast.makeText(context, "Cell border color updated!", Toast.LENGTH_SHORT).show()
                                }
                            }
                            activeColorPickerTarget = null
                        }
                    },
                    enabled = parsedColor != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = parsedColor ?: tokens.accentBrand
                    )
                ) {
                    Text("Apply Color", fontWeight = FontWeight.Bold, color = parsedColor?.let { computeContrastText(it) } ?: tokens.primaryButtonText)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeColorPickerTarget = null }) {
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "PROFILE",
                        subtitle = "Photo & display name",
                        icon = Icons.Default.Person,
                        isExpanded = isProfileExpanded,
                        onToggle = { isProfileExpanded = !isProfileExpanded }
                    )

                    AnimatedVisibility(visible = isProfileExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Profile Photo with Pencil Badge
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

                                // Pencil edit icon badge (black icon on white background in dark mode)
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (tokens.isDark) Color.White else tokens.accentBrand)
                                        .padding(5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Change photo",
                                        tint = if (tokens.isDark) Color.Black else Color.White,
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
                                        imageVector = Icons.Default.Edit,
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
                                        containerColor = tokens.primaryButtonBg,
                                        contentColor = tokens.primaryButtonText
                                    )
                                ) {
                                    Text(
                                        text = "Save Name",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.primaryButtonText
                                    )
                                }
                            }
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "ACCOUNT DETAILS",
                        subtitle = "Sign-in method & player ID",
                        icon = Icons.Default.Badge,
                        isExpanded = isAccountExpanded,
                        onToggle = { isAccountExpanded = !isAccountExpanded }
                    )

                    AnimatedVisibility(visible = isAccountExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                        ) {
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "DASHBOARD & FRIENDS",
                        subtitle = "Stats, ranks & social hub",
                        icon = Icons.Default.Insights,
                        isExpanded = isDashboardExpanded,
                        onToggle = { isDashboardExpanded = !isDashboardExpanded }
                    )

                    AnimatedVisibility(visible = isDashboardExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                        ) {
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
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Insights,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = tokens.primaryButtonText
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Open Dashboard & Social Hub",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.primaryButtonText
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 4: Appearance & AMOLED Dark Theme + Curated Color Palette ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "APPEARANCE & THEME",
                        subtitle = "AMOLED black, palette & board colors",
                        icon = Icons.Default.Palette,
                        isExpanded = isAppearanceExpanded,
                        onToggle = { isAppearanceExpanded = !isAppearanceExpanded }
                    )

                    AnimatedVisibility(visible = isAppearanceExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        ) {
                            Text(
                                text = "Customize your visual style. AMOLED Dark delivers 100% pitch-black background with soft matte tones.",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.7f)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Theme Mode Selector: Light vs AMOLED Pure Black
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Light Mode Option
                                val isLightActive = !isDarkTheme
                                Surface(
                                    onClick = { ThemePreferences.setDarkTheme(context, false) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isLightActive) (if (tokens.isDark) Color(0xFF1E1E1E) else tokens.backgroundSecondary) else tokens.surface,
                                    border = BorderStroke(
                                        width = if (isLightActive) 2.dp else 1.dp,
                                        color = if (isLightActive) (if (tokens.isDark) Color.White else tokens.accentBrand) else tokens.surfaceBorder
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (tokens.isDark) Color(0xFF262626) else Color(0xFFFBBF24).copy(alpha = 0.15f),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text(text = "☀️", fontSize = 16.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Clean Light",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = tokens.textPrimary
                                            )
                                            Text(
                                                text = "Minimal white",
                                                fontSize = 10.5.sp,
                                                color = tokens.textMuted
                                            )
                                        }
                                    }
                                }

                                // AMOLED Black Option
                                val isDarkActive = isDarkTheme
                                Surface(
                                    onClick = { ThemePreferences.setDarkTheme(context, true) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isDarkActive) (if (tokens.isDark) Color(0xFF000000) else Color(0xFF0D0D10)) else tokens.surface,
                                    border = BorderStroke(
                                        width = if (isDarkActive) 2.dp else 1.dp,
                                        color = if (isDarkActive) (if (tokens.isDark) Color.White else tokens.accentBrand) else tokens.surfaceBorder
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (tokens.isDark) Color(0xFF222222) else Color(0xFF8B5CF6).copy(alpha = 0.18f),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text(text = "🌙", fontSize = 16.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "AMOLED Black",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = tokens.textPrimary
                                            )
                                            Text(
                                                text = "Pure #000000",
                                                fontSize = 10.5.sp,
                                                color = tokens.textMuted
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // ── Exclusive Theme: Liquid Metal Design (Experimental) ──
                            val isLiquidMetal = ThemePreferences.isLiquidMetalTheme.value
                            Surface(
                                onClick = {
                                    ThemePreferences.setLiquidMetalTheme(context, !isLiquidMetal)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(
                                        elevation = if (isLiquidMetal) 14.dp else 2.dp,
                                        shape = RoundedCornerShape(18.dp),
                                        spotColor = if (isLiquidMetal) Color(0x60000000) else Color.Transparent
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                color = if (isLiquidMetal) Color.Transparent else tokens.surface,
                                border = BorderStroke(
                                    width = if (isLiquidMetal) 1.6.dp else 1.dp,
                                    brush = if (isLiquidMetal) {
                                        Brush.linearGradient(
                                            listOf(
                                                Color(0xFFFFFFFF),
                                                Color(0xFFCBD5E1),
                                                Color(0xFF94A3B8),
                                                Color(0xFF1E242F),
                                                Color(0xFF090B0F),
                                                Color(0xFF475569),
                                                Color(0xFFE2E8F0)
                                            )
                                        )
                                    } else {
                                        SolidColor(tokens.surfaceBorder)
                                    }
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .then(
                                            if (isLiquidMetal) {
                                                Modifier.background(
                                                    Brush.verticalGradient(
                                                        listOf(
                                                            Color(0xFFFFFFFF),
                                                            Color(0xFFF4F7FB),
                                                            Color(0xFFE2E8F0),
                                                            Color(0xFFCAD2DF),
                                                            Color(0xFFB5BFCE)
                                                        )
                                                    )
                                                )
                                            } else Modifier
                                        )
                                ) {
                                    if (isLiquidMetal) {
                                        // Crescent top-left specular highlight
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    Brush.radialGradient(
                                                        colors = listOf(
                                                            Color(0xB0FFFFFF),
                                                            Color(0x35FFFFFF),
                                                            Color(0x00FFFFFF)
                                                        ),
                                                        center = Offset(120f, 10f),
                                                        radius = 220f
                                                    )
                                                )
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            // Metallic Icon Badge
                                            Surface(
                                                shape = CircleShape,
                                                color = if (isLiquidMetal) Color(0xFFF1F5F9) else tokens.backgroundSecondary,
                                                border = if (isLiquidMetal) {
                                                    BorderStroke(
                                                        1.2.dp,
                                                        Brush.verticalGradient(
                                                            listOf(
                                                                Color(0xFFFFFFFF),
                                                                Color(0xFF94A3B8),
                                                                Color(0xFF1E242F)
                                                            )
                                                        )
                                                    )
                                                } else null,
                                                shadowElevation = if (isLiquidMetal) 4.dp else 0.dp,
                                                modifier = Modifier.size(38.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                    Text(
                                                        text = "✦",
                                                        fontSize = 18.sp,
                                                        color = if (isLiquidMetal) Color(0xFF090C11) else tokens.accentBrand
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "Liquid Metal Design",
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (isLiquidMetal) Color(0xFF090C11) else tokens.textPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = if (isLiquidMetal) Color(0xFF1E242F) else tokens.accentBrand.copy(alpha = 0.12f),
                                                        border = BorderStroke(
                                                            0.8.dp,
                                                            if (isLiquidMetal) Color(0xFFCBD5E1) else tokens.accentBrand.copy(alpha = 0.3f)
                                                        )
                                                    ) {
                                                        Text(
                                                            text = "EXCLUSIVE",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            letterSpacing = 0.6.sp,
                                                            color = if (isLiquidMetal) Color(0xFFFFFFFF) else tokens.accentBrand,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Fluid mercury chrome aesthetic with dynamic specular highlights",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = if (isLiquidMetal) FontWeight.Medium else FontWeight.Normal,
                                                    color = if (isLiquidMetal) Color(0xFF2B3342) else tokens.textMuted
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Switch toggle
                                        androidx.compose.material3.Switch(
                                            checked = isLiquidMetal,
                                            onCheckedChange = { checked ->
                                                ThemePreferences.setLiquidMetalTheme(context, checked)
                                            },
                                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                                checkedThumbColor = Color(0xFFFFFFFF),
                                                checkedTrackColor = Color(0xFF090C11),
                                                checkedBorderColor = Color(0xFFFFFFFF),
                                                uncheckedThumbColor = tokens.textMuted,
                                                uncheckedTrackColor = tokens.backgroundSecondary,
                                                uncheckedBorderColor = tokens.surfaceBorder
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Curated Color Palette Section
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "APP COLOR PALETTE",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                                )

                                TextButton(
                                    onClick = { activeColorPickerTarget = ColorPickerTarget.APP_ACCENT },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Full Palette 🎨",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.accentBrand
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Select an accent tone for buttons, turn banners, and highlights:",
                                fontSize = 11.5.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.65f)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Palette Swatches (horizontal scroll with swatch and name)
                            val availablePalettes = remember { ThemePreferences.PALETTES }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                availablePalettes.forEach { palette ->
                                    val isSelected = (palette.id == selectedAccentId)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                ThemePreferences.setAccentColor(context, palette.id)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(palette.previewColor)
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 1.dp,
                                                    color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.2f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = palette.name,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) tokens.accentBrand else tokens.cellNeutralText.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                // Custom Palette Swatch
                                val isCustomSelected = (selectedAccentId == "custom")
                                val customColorParsed = try {
                                    val clean = if (ThemePreferences.customColorHex.value.startsWith("#")) ThemePreferences.customColorHex.value else "#${ThemePreferences.customColorHex.value}"
                                    Color(android.graphics.Color.parseColor(clean))
                                } catch (_: Exception) {
                                    Color(0xFF7C3AED)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            activeColorPickerTarget = ColorPickerTarget.APP_ACCENT
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isCustomSelected) {
                                                    Brush.sweepGradient(listOf(customColorParsed, customColorParsed))
                                                } else {
                                                    Brush.sweepGradient(
                                                        listOf(
                                                            Color(0xFFEF4444),
                                                            Color(0xFFF59E0B),
                                                            Color(0xFF10B981),
                                                            Color(0xFF06B6D4),
                                                            Color(0xFF3B82F6),
                                                            Color(0xFF8B5CF6),
                                                            Color(0xFFEC4899),
                                                            Color(0xFFEF4444)
                                                        )
                                                    )
                                                }
                                            )
                                            .border(
                                                width = if (isCustomSelected) 2.5.dp else 1.dp,
                                                color = if (isCustomSelected) Color.White else Color.Black.copy(alpha = 0.2f),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isCustomSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Text(text = "🎨", fontSize = 16.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = if (isCustomSelected) "Custom" else "Custom...",
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCustomSelected) tokens.accentBrand else tokens.cellNeutralText.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // ── BOARD CELLS CUSTOMIZATION ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "BOARD CELL COLORS",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                                )

                                TextButton(
                                    onClick = {
                                        ThemePreferences.resetBoardColors(context)
                                        Toast.makeText(context, "Board colors reset to default", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Reset Board",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tokens.accentBrand
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Customize colors for your picks, opponent picks, and recent turns:",
                                fontSize = 11.5.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.65f)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 1. My Pick Row
                            val myPickHex = ThemePreferences.customMyPickHex.value ?: ThemePreferences.DEFAULT_MY_PICK_HEX
                            BoardPickColorRow(
                                title = "My Pick",
                                subtitle = "Your claimed numbers",
                                colorHex = myPickHex,
                                sampleNumber = "7",
                                onEdit = { activeColorPickerTarget = ColorPickerTarget.MY_PICK }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 2. Opponent Pick Row
                            val opponentPickHex = ThemePreferences.customOpponentPickHex.value ?: ThemePreferences.DEFAULT_OPPONENT_PICK_HEX
                            BoardPickColorRow(
                                title = "Opponent Pick",
                                subtitle = "Opponent's claims",
                                colorHex = opponentPickHex,
                                sampleNumber = "24",
                                onEdit = { activeColorPickerTarget = ColorPickerTarget.OPPONENT_PICK }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 3. Recent Pick Row
                            val recentPickHex = ThemePreferences.customRecentPickHex.value ?: ThemePreferences.getDefaultRecentPickHex(tokens.isDark)
                            BoardPickColorRow(
                                title = "Recent Pick",
                                subtitle = "Most recent played number",
                                colorHex = recentPickHex,
                                sampleNumber = "15",
                                onEdit = { activeColorPickerTarget = ColorPickerTarget.RECENT_PICK }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 4. Line Completion Row
                            val lineCompletionHex = ThemePreferences.customCompletedLineHex.value ?: ThemePreferences.DEFAULT_COMPLETED_LINE_HEX
                            BoardPickColorRow(
                                title = "Line Completion",
                                subtitle = "Cells in completed winning lines",
                                colorHex = lineCompletionHex,
                                sampleNumber = "✓",
                                onEdit = { activeColorPickerTarget = ColorPickerTarget.LINE_COMPLETION }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Cell Border Checkbox & Dropdown
                            var borderDropdownExpanded by remember { mutableStateOf(false) }
                            val cellBorderEnabled = ThemePreferences.cellBorderEnabled.value
                            val currentBorderHex = ThemePreferences.cellBorderColorHex.value
                            val currentBorderColor = try {
                                Color(android.graphics.Color.parseColor(currentBorderHex))
                            } catch (_: Exception) { Color.White }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = tokens.backgroundSecondary,
                                border = BorderStroke(1.dp, tokens.surfaceBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    ThemePreferences.setCellBorderEnabled(context, !cellBorderEnabled)
                                                }
                                        ) {
                                            Checkbox(
                                                checked = cellBorderEnabled,
                                                onCheckedChange = { isChecked ->
                                                    ThemePreferences.setCellBorderEnabled(context, isChecked)
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = tokens.accentBrand,
                                                    uncheckedColor = tokens.textMuted
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(
                                                    text = "Cell Border",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = tokens.textPrimary
                                                )
                                                Text(
                                                    text = if (cellBorderEnabled) "Border outline enabled" else "No border outlines on cells",
                                                    fontSize = 10.5.sp,
                                                    color = tokens.textMuted
                                                )
                                            }
                                        }

                                        if (cellBorderEnabled) {
                                            Box {
                                                OutlinedButton(
                                                    onClick = { borderDropdownExpanded = true },
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(34.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clip(CircleShape)
                                                            .background(currentBorderColor)
                                                            .border(0.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = currentBorderHex.uppercase(),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = tokens.textPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowDropDown,
                                                        contentDescription = "Select border color",
                                                        tint = tokens.textMuted,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                DropdownMenu(
                                                    expanded = borderDropdownExpanded,
                                                    onDismissRequest = { borderDropdownExpanded = false },
                                                    modifier = Modifier.background(tokens.surface)
                                                ) {
                                                    ThemePreferences.BORDER_COLOR_PRESETS.forEach { preset ->
                                                        val pColor = Color(android.graphics.Color.parseColor(preset.hex))
                                                        val isCur = currentBorderHex.equals(preset.hex, ignoreCase = true)
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(18.dp)
                                                                            .clip(CircleShape)
                                                                        .background(pColor)
                                                                        .border(0.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text(
                                                                        text = preset.name,
                                                                        fontSize = 13.sp,
                                                                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                                                        color = tokens.textPrimary
                                                                    )
                                                                    if (isCur) {
                                                                        Spacer(modifier = Modifier.width(8.dp))
                                                                        Icon(
                                                                            imageVector = Icons.Default.Check,
                                                                            contentDescription = null,
                                                                            tint = tokens.accentBrand,
                                                                            modifier = Modifier.size(14.dp)
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                            onClick = {
                                                                ThemePreferences.setCellBorderColor(context, preset.hex)
                                                                borderDropdownExpanded = false
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(text = "🎨", fontSize = 14.sp)
                                                                Spacer(modifier = Modifier.width(10.dp))
                                                                Text(
                                                                    text = "Custom Color...",
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = tokens.accentBrand
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            borderDropdownExpanded = false
                                                            activeColorPickerTarget = ColorPickerTarget.CELL_BORDER
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 5: Favourite In-Game Emojis ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "FAVOURITE IN-GAME EMOJIS",
                        subtitle = "Pinned match reaction strip",
                        icon = Icons.Default.Mood,
                        isExpanded = isEmojisExpanded,
                        onToggle = { isEmojisExpanded = !isEmojisExpanded }
                    )

                    AnimatedVisibility(visible = isEmojisExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "QUICK REACTIONS",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                                )

                                TextButton(
                                    onClick = {
                                        favoriteEmojis = EmojiPreferences.resetFavoritesToDefault(context)
                                        Toast.makeText(context, "Favourites reset to defaults", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Reset", fontSize = 11.5.sp, color = tokens.accentBrand)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Favourites always appear at the very start of your match reaction strip for instant 1-tap reactions (no scrolling required).",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.7f)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                favoriteEmojis.forEach { emoji ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = tokens.backgroundSecondary,
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f)),
                                        modifier = Modifier
                                            .clickable {
                                                if (favoriteEmojis.size <= 1) {
                                                    Toast.makeText(context, "Keep at least 1 favourite emoji", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    EmojiPreferences.removeFavoriteEmoji(context, emoji)
                                                    favoriteEmojis = EmojiPreferences.getFavoriteEmojis(context)
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Fav",
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            AnimatedEmoji(emoji = emoji, fontSize = 21.sp)
                                        }
                                    }
                                }

                                if (favoriteEmojis.size < EmojiPreferences.MAX_FAVORITES) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = tokens.accentBrand.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, tokens.accentBrand.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clickable {
                                                showAddFavoriteEmojiDialog = true
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add Favourite",
                                                tint = tokens.accentBrand,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 6: In-Game Quick Chat Phrases ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardHeader(
                        title = "IN-GAME QUICK CHAT",
                        subtitle = "Phrases for live matches",
                        icon = Icons.Default.ChatBubbleOutline,
                        isExpanded = isQuickChatExpanded,
                        onToggle = { isQuickChatExpanded = !isQuickChatExpanded }
                    )

                    AnimatedVisibility(visible = isQuickChatExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "MATCH PHRASES",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            newPhraseText = ""
                                            showAddPhraseDialog = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Phrase",
                                            tint = tokens.accentBrand,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    TextButton(
                                        onClick = {
                                            quickChatPhrases = QuickChatPreferences.resetToDefaults(context)
                                            Toast.makeText(context, "Phrases reset to defaults", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Reset Defaults", fontSize = 12.sp, color = tokens.accentBrand)
                                    }
                                }
                            }

                            Text(
                                text = "Customize phrases sent during live matches (max ${QuickChatPreferences.MAX_PHRASE_LENGTH} chars). Recently used phrases appear first in the in-game toast:",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            quickChatPhrases.forEachIndexed { idx, phrase ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = tokens.backgroundSecondary,
                                    border = BorderStroke(0.5.dp, tokens.surfaceBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${idx + 1}.",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = tokens.accentBrand
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = phrase,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = tokens.cellNeutralText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    editingPhraseIndex = idx
                                                    editingPhraseText = phrase
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit Phrase",
                                                    tint = tokens.accentBrand,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }

                                            if (quickChatPhrases.size > 1) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                IconButton(
                                                    onClick = {
                                                        val deleted = QuickChatPreferences.deletePhrase(context, idx)
                                                        if (deleted) {
                                                            quickChatPhrases = QuickChatPreferences.getPhrases(context)
                                                            Toast.makeText(context, "Phrase removed", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Delete Phrase",
                                                        tint = tokens.accentOpponent.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 7: Sign Out ──
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

            Spacer(modifier = Modifier.height(16.dp))

            // ── App Version & In-App Updates ──
            val appVersionName = remember {
                try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    pInfo.versionName ?: "1.3"
                } catch (e: Exception) {
                    "1.3"
                }
            }
            val updateState by AppUpdateManager.updateState.collectAsState()

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Version $appVersionName",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textMuted
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { AppUpdateManager.checkForUpdates(context, manual = true) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.4f)),
                    modifier = Modifier.height(38.dp)
                ) {
                    if (updateState is UpdateState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = tokens.accentBrand,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Checking...", fontSize = 12.sp, color = tokens.cellNeutralText)
                    } else {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = tokens.accentBrand,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check for Updates", fontSize = 12.sp, color = tokens.cellNeutralText, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (updateState is UpdateState.UpToDate) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "✓ You are running the latest version",
                        fontSize = 11.sp,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Notification Diagnostics & Testing ──
            NotificationDiagnosticsCard(context = context)

            Spacer(modifier = Modifier.height(84.dp))
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
private fun NotificationDiagnosticsCard(
    context: Context
) {
    val tokens = BingoTheme.colors
    var areNotificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var fcmToken by remember {
        mutableStateOf(BingoFcmManager.getSavedToken(context))
    }
    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var isSendingFcm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (fcmToken.isNullOrBlank()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    val tok = task.result
                    fcmToken = tok
                    BingoFcmManager.saveToken(context, tok)
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = tokens.accentBrand,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Notification Diagnostics",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verify heads-up alerts, action buttons, and background FCM pushes directly on your device.",
                fontSize = 12.sp,
                color = tokens.textMuted,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Permission Status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (areNotificationsEnabled) "✓ System Notifications: Enabled" else "⚠️ System Notifications: Blocked (Tap to Enable)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (areNotificationsEnabled) Color(0xFF10B981) else Color(0xFFEF4444)
                )
            }

            // FCM Token Status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hasToken = !fcmToken.isNullOrBlank()
                Text(
                    text = if (hasToken) "✓ FCM Cloud Messaging: Active" else "⏳ FCM Cloud Messaging: Registering...",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (hasToken) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
            }

            if (testStatusMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = testStatusMessage.orEmpty(),
                    fontSize = 11.sp,
                    color = tokens.accentBrand,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "1-Tap Verification Triggers:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.cellNeutralText
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Row 1: Invite & Friend Online
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        testStatusMessage = "Heads-up invite sent with [Accept] & [Decline] buttons"
                        BingoNotificationManager.testInviteNotification(context)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("🎮 Test Invite Alert", fontSize = 11.sp, color = tokens.cellNeutralText, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        testStatusMessage = "Friend online alert sent with [Invite to Game] button"
                        BingoNotificationManager.testFriendOnlineNotification(context)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("🟢 Test Friend Online", fontSize = 11.sp, color = tokens.cellNeutralText, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Announcement & FCM Push
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        testStatusMessage = "Announcement notification posted"
                        BingoNotificationManager.testBroadcastNotification(context)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tokens.cellNeutralBorder.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("📢 Test Announcement", fontSize = 11.sp, color = tokens.cellNeutralText, maxLines = 1)
                }

                Button(
                    onClick = {
                        if (!isSendingFcm) {
                            isSendingFcm = true
                            testStatusMessage = "Sending FCM push... Minimize or lock phone to see it wake device!"
                            Toast.makeText(context, "Minimize or lock your phone now! Push will arrive in ~2-4s", Toast.LENGTH_LONG).show()
                            BingoFcmManager.sendSelfTestPush(context) { success, msg ->
                                isSendingFcm = false
                                testStatusMessage = if (success) "✓ FCM push sent successfully to Google servers!" else "❌ FCM failed: $msg"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (isSendingFcm) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("☁️ Test FCM Push", fontSize = 11.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Rule Guide: Live friend alerts require >1h absence. Invites show in-app modal when open, heads-up notification with Accept/Decline when minimized. Auto-dismisses on app open.",
                fontSize = 10.sp,
                color = tokens.textMuted.copy(alpha = 0.8f),
                lineHeight = 14.sp
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

@Composable
private fun SettingsCardHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val tokens = BingoTheme.colors
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "chevronRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (tokens.isDark) Color(0xFF1E1E1E) else tokens.accentBrand.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (tokens.isDark) Color.White else tokens.accentBrand,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = tokens.textPrimary
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = tokens.textMuted
                    )
                }
            }
        }

        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = tokens.textMuted,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

@Composable
private fun BoardPickColorRow(
    title: String,
    subtitle: String,
    colorHex: String,
    sampleNumber: String,
    onEdit: () -> Unit
) {
    val tokens = BingoTheme.colors
    val parsedColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (_: Exception) {
        tokens.cellNeutralBg
    }
    val textColor = computeContrastText(parsedColor)
    val hasBorder = ThemePreferences.cellBorderEnabled.value
    val borderColor = try {
        Color(android.graphics.Color.parseColor(ThemePreferences.cellBorderColorHex.value))
    } catch (_: Exception) { Color.White }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tokens.backgroundSecondary,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Color swatch circle
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(parsedColor)
                        .border(
                            1.dp,
                            if (tokens.isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.2f),
                            CircleShape
                        )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary
                    )
                    Text(
                        text = "$subtitle ($colorHex)",
                        fontSize = 10.5.sp,
                        color = tokens.textMuted
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Edit button
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.textPrimary),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Edit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Live Preview Mini Cell (tactile look)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = parsedColor,
                    border = if (hasBorder) BorderStroke(1.5.dp, borderColor) else if (tokens.isDark) BorderStroke(1.dp, parsedColor) else null,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = sampleNumber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
