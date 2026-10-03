package com.bingo.multiplayer.presentation.auth

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.presentation.common.GoogleGLogo
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

private const val TAG = "LoginScreen"
private const val RELEASE_SHA1 = "88:99:C8:F8:7D:48:BF:7A:05:80:E2:06:87:42:87:AB:0C:2A:29:F1"
private const val DEBUG_SHA1 = "DE:FA:9C:0D:F9:26:C9:A3:77:34:2E:54:9B:86:0B:38:85:90:B9:E5"

/**
 * Aesthetic, minimal, and resilient Login Screen.
 *
 * Provides:
 * 1. "Continue with Google" -> Official Google Sign-In SDK
 * 2. Friendly Cloud Console helper card when SHA-1 is not yet registered
 * 3. Personalized or 1-tap "Play as Guest" instant entry
 * 4. Borderless, minimalist aesthetic styling without heavy boxes
 */
@Composable
fun LoginScreen(
    authRepository: AuthRepository,
    onLoginSuccess: () -> Unit
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val activity = context as Activity
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSha1Dialog by remember { mutableStateOf(false) }
    var showDeveloperErrorCard by remember { mutableStateOf(false) }

    // Multi-device conflict & first-time nickname state
    var conflictActiveDevice by remember { mutableStateOf<String?>(null) }
    var pendingGoogleAccount by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    var showFirstTimeNameDialog by remember { mutableStateOf(false) }
    var pendingGoogleId by remember { mutableStateOf<String?>(null) }
    var pendingGoogleEmail by remember { mutableStateOf<String?>(null) }
    var newPlayerNickname by remember { mutableStateOf("") }

    var guestNickname by remember { mutableStateOf("Player") }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    fun proceedWithGoogleAccount(account: GoogleSignInAccount) {
        val email = account.email ?: ""
        val googleId = account.id ?: System.currentTimeMillis().toString()
        val isRegistered = authRepository.isGoogleUserRegistered(googleId)
        val savedName = authRepository.getSavedGoogleDisplayName(googleId)
        val savedUsername = authRepository.getSavedGoogleUsername(googleId)

        coroutineScope.launch {
            isLoading = true
            loadingMessage = "Restoring your account..."

            // 1. Attempt cloud restore first to guarantee all stats, avatar, settings, and history are restored
            val restored = authRepository.restoreCloudUserData(googleId)
            if (restored) {
                Log.i(TAG, "Account restored successfully from cloud for Google ID: $googleId")
                isLoading = false
                errorMessage = null
                showDeveloperErrorCard = false
                onLoginSuccess()
                return@launch
            }

            // 2. If no cloud data found, but local registration exists on this device
            if (isRegistered && !savedName.isNullOrBlank()) {
                authRepository.onGoogleSignInSuccess(
                    googleId = googleId,
                    displayName = savedName,
                    email = email,
                    customUsername = savedUsername
                )
                isLoading = false
                errorMessage = null
                showDeveloperErrorCard = false
                onLoginSuccess()
                return@launch
            }

            // 3. Check if profile was registered in cloud registry
            val remoteProfile = authRepository.sessionManager.lookupGoogleProfile(googleId)
            if (remoteProfile != null && remoteProfile.displayName.isNotBlank()) {
                authRepository.setGoogleUserRegistered(googleId, true)
                authRepository.setSavedGoogleDisplayName(googleId, remoteProfile.displayName)
                authRepository.setSavedGoogleUsername(googleId, remoteProfile.username)
                authRepository.onGoogleSignInSuccess(
                    googleId = googleId,
                    displayName = remoteProfile.displayName,
                    email = email,
                    customUsername = remoteProfile.username
                )
                isLoading = false
                errorMessage = null
                showDeveloperErrorCard = false
                onLoginSuccess()
                return@launch
            }

            // 4. Truly first-time Google login: prompt user to choose unique username
            pendingGoogleId = googleId
            pendingGoogleEmail = email
            newPlayerNickname = ""
            showFirstTimeNameDialog = true
            isLoading = false
        }
    }

    // ── Real Google Sign-In Client ──
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        GoogleSignIn.getClient(activity, gso)
    }

    // ActivityResult launcher for Google Sign-In intent
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            val googleId = account.id ?: System.currentTimeMillis().toString()

            Log.d(TAG, "Google Sign-In account selected: $googleId. Verifying active sessions...")
            loadingMessage = "Checking account status..."

            coroutineScope.launch {
                val activeDevice = authRepository.sessionManager.checkForActiveDevice(
                    googleId = googleId,
                    localDeviceId = authRepository.deviceId
                )

                if (activeDevice != null) {
                    // Same google account is active on another device!
                    isLoading = false
                    pendingGoogleAccount = account
                    conflictActiveDevice = activeDevice
                } else {
                    proceedWithGoogleAccount(account)
                }
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In failed: code=${e.statusCode}", e)
            isLoading = false
            when (e.statusCode) {
                10 -> {
                    // DEVELOPER_ERROR (SHA-1 fingerprint missing from Google Cloud Console)
                    showDeveloperErrorCard = true
                    errorMessage = null
                }
                12501 -> {
                    // User dismissed the account chooser — reset quietly
                    errorMessage = null
                    showDeveloperErrorCard = false
                }
                12502 -> {
                    errorMessage = "Sign-in attempt is already in progress. Please retry."
                }
                7 -> {
                    errorMessage = "Network error. Check connection or play as Guest."
                }
                else -> {
                    errorMessage = "Sign-in error (code ${e.statusCode}). You can play as Guest below."
                }
            }
        }
    }

    // ── Dialog: Multi-Device Login Conflict Error Screen ──
    if (conflictActiveDevice != null) {
        AlertDialog(
            onDismissRequest = { /* Modal */ },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(42.dp)
                )
            },
            title = {
                Text(
                    text = "Already Logged In on Another Device",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = tokens.cellNeutralText,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "This Google account is currently logged in on another device ($conflictActiveDevice). For fair play and security, simultaneous logins are not supported.\n\nWould you like to log in here and log out the other device?",
                    fontSize = 13.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val account = pendingGoogleAccount
                        val gId = account?.id ?: ""
                        authRepository.sessionManager.forceLogoutOtherDevices(gId, authRepository.deviceId)
                        conflictActiveDevice = null
                        if (account != null) {
                            proceedWithGoogleAccount(account)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    )
                ) {
                    Text("Log In Here", fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        conflictActiveDevice = null
                        pendingGoogleAccount = null
                        isLoading = false
                    }
                ) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    // ── Dialog: First-Time Google Sign-In Username (Player ID) Prompt ──
    var usernameError by remember { mutableStateOf<String?>(null) }
    var isCheckingUsername by remember { mutableStateOf(false) }

    if (showFirstTimeNameDialog) {
        AlertDialog(
            onDismissRequest = { /* Mandatory setup */ },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Choose Your Player ID (Username)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose a unique username for your profile. Other players will search and challenge you using this Player ID:",
                        fontSize = 13.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.8f)
                    )

                    OutlinedTextField(
                        value = newPlayerNickname,
                        onValueChange = {
                            newPlayerNickname = it.trim().lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(16)
                            usernameError = null
                        },
                        prefix = { Text("@", color = tokens.accentBrand, fontWeight = FontWeight.Bold) },
                        label = { Text("Player ID / Username", fontSize = 12.sp) },
                        placeholder = { Text("e.g. shadow_player", fontSize = 12.sp) },
                        singleLine = true,
                        isError = usernameError != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accentBrand,
                            unfocusedBorderColor = tokens.surfaceBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    usernameError?.let { err ->
                        Text(
                            text = err,
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = newPlayerNickname.trim().lowercase().removePrefix("@")
                        if (cleanName.length < 3) {
                            usernameError = "Username must be at least 3 characters long."
                            return@Button
                        }
                        val gId = pendingGoogleId ?: System.currentTimeMillis().toString()
                        val gEmail = pendingGoogleEmail ?: ""

                        coroutineScope.launch {
                            isCheckingUsername = true
                            val available = authRepository.sessionManager.checkUsernameAvailable(cleanName, "google_$gId")
                            isCheckingUsername = false
                            if (!available) {
                                usernameError = "Username '@$cleanName' is already taken. Please choose another."
                            } else {
                                showFirstTimeNameDialog = false
                                authRepository.setGoogleUserRegistered(gId, true)
                                authRepository.setSavedGoogleDisplayName(gId, cleanName)
                                authRepository.onGoogleSignInSuccess(
                                    googleId = gId,
                                    displayName = cleanName,
                                    email = gEmail,
                                    customUsername = cleanName
                                )
                                authRepository.backupUserDataToCloud()
                                isLoading = false
                                onLoginSuccess()
                            }
                        }
                    },
                    enabled = newPlayerNickname.trim().length >= 3 && !isCheckingUsername,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    )
                ) {
                    Text(
                        text = if (isCheckingUsername) "Checking..." else "Confirm & Start",
                        fontWeight = FontWeight.Bold,
                        color = tokens.primaryButtonText
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFirstTimeNameDialog = false
                        isLoading = false
                    }
                ) {
                    Text("Cancel", color = tokens.cellNeutralText)
                }
            }
        )
    }

    // ── Dialog: Cloud Console & SHA-1 Helper ──
    if (showSha1Dialog) {
        AlertDialog(
            onDismissRequest = { showSha1Dialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Google Cloud Console Setup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "To enable Google Sign-In, add this Release SHA-1 to your Google Cloud Console / Firebase Console under Android OAuth 2.0 Client:",
                        fontSize = 13.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.8f)
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tokens.backgroundSecondary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Release Certificate SHA-1:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.accentBrand
                            )
                            Text(
                                text = RELEASE_SHA1,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = tokens.cellNeutralText
                            )
                        }
                    }

                    Button(
                        onClick = {
                            copyToClipboard("Release SHA-1", RELEASE_SHA1)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonBg,
                            contentColor = tokens.primaryButtonText
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = tokens.primaryButtonText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Release SHA-1", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.primaryButtonText)
                    }

                    Text(
                        text = "Package: com.bingo.multiplayer",
                        fontSize = 11.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSha1Dialog = false }) {
                    Text("Close", color = tokens.accentBrand, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Minimalist Aesthetic Logo Header ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Royal Bingo Crown Logo Emblem
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.bingo.multiplayer.R.drawable.ic_bingo_logo),
                    contentDescription = "Royal Bingo Logo",
                    modifier = Modifier.size(100.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "B I N G O",
                    style = BingoTheme.typography.logoTitle,
                    color = tokens.cellNeutralText,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "M U L T I P L A Y E R",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.45f)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Clean Minimalist Auth Container ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = tokens.surface,
                border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = tokens.accentBrand,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = loadingMessage,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.cellNeutralText
                            )
                        }
                    } else {
                        // Helpful Cloud Console Setup Banner (if code 10 occurred)
                        if (showDeveloperErrorCard) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFFFF7ED),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Google Sign-In Registration Needed",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFFC2410C)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Your release key SHA-1 is not yet registered in Google Cloud Console. You can copy the SHA-1 below to register it, or play as Guest instantly!",
                                        fontSize = 12.sp,
                                        color = Color(0xFF7C2D12),
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                copyToClipboard("Release SHA-1", RELEASE_SHA1)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy SHA-1", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        TextButton(
                                            onClick = {
                                                authRepository.onGuestLogin(guestNickname)
                                                onLoginSuccess()
                                            }
                                        ) {
                                            Text(
                                                text = "Play as Guest Now →",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFC2410C)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Generic Error Message
                        errorMessage?.let { msg ->
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = Color(0xFFDC2626),
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            )
                        }

                        // 1. Google Sign-In Button (Modern, clean, borderless icon)
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isLoading = true
                                loadingMessage = "Opening Google Sign-In..."
                                errorMessage = null
                                showDeveloperErrorCard = false

                                googleSignInClient.signOut().addOnCompleteListener {
                                    val signInIntent = googleSignInClient.signInIntent
                                    googleSignInLauncher.launch(signInIntent)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFFE2E8F0)),
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleGLogo(size = 20.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Continue with Google",
                                    color = Color(0xFF1E293B),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Divider with "or"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = tokens.surfaceBorder
                            )
                            Text(
                                text = "OR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.35f),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = tokens.surfaceBorder
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 2. Guest Nickname Input (Personalized Guest Experience)
                        OutlinedTextField(
                            value = guestNickname,
                            onValueChange = { guestNickname = it.take(16) },
                            label = { Text("Player Nickname", fontSize = 12.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    authRepository.onGuestLogin(guestNickname)
                                    onLoginSuccess()
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = tokens.accentBrand,
                                unfocusedBorderColor = tokens.surfaceBorder,
                                focusedContainerColor = tokens.backgroundSecondary.copy(alpha = 0.3f),
                                unfocusedContainerColor = tokens.backgroundSecondary.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 3. Guest Login Button (Soft plain purple tint, NO BORDER, bare icon)
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                authRepository.onGuestLogin(guestNickname)
                                onLoginSuccess()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (tokens.isDark) Color(0xFF2E1065) else Color(0xFFF5EEFF)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonOutline,
                                    contentDescription = null,
                                    tint = if (tokens.isDark) Color(0xFFE9D5FF) else Color(0xFF6B21A8),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Play as Guest",
                                    color = if (tokens.isDark) Color(0xFFE9D5FF) else Color(0xFF6B21A8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Instant play • No account required",
                            fontSize = 11.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Subtle Bottom Action: Cloud Console Info ──
            TextButton(
                onClick = { showSha1Dialog = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = tokens.cellNeutralText.copy(alpha = 0.45f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "App Signing Key (SHA-1) Info",
                    fontSize = 12.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.45f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
