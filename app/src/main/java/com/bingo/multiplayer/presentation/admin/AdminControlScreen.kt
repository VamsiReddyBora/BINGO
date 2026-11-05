package com.bingo.multiplayer.presentation.admin

import com.bingo.multiplayer.core.designsystem.appPagePadding
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoColors
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.core.security.AdminControlAccess
import com.bingo.multiplayer.core.security.BiometricAuthManager
import com.bingo.multiplayer.core.security.findActivity
import com.bingo.multiplayer.domain.model.UserProfile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.bingo.multiplayer.domain.network.AdminAccountReconnectRequired
import com.bingo.multiplayer.domain.network.EmqxTransport
import com.bingo.multiplayer.domain.network.AutomaticCleanupApi
import com.bingo.multiplayer.domain.network.AutomaticCleanupState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bingo.multiplayer.domain.network.AdminRetentionConfig
import com.bingo.multiplayer.domain.network.AdminServiceManager
import com.bingo.multiplayer.domain.network.BrokerUsageStats
import com.bingo.multiplayer.domain.network.CleanupEligibility
import com.bingo.multiplayer.domain.network.NetworkPingMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/**
 * APEX SERVER OPS // Masterwork Minimalist Black Admin Cockpit for @bob and @vampire.
 *
 * Core Design Philosophy:
 * - Pure OLED Black canvas (#000000) with deep obsidian carbon surfaces (#0D0E14)
 * - Fine 1px engineering hairline borders (#1E202B) with zero rainbow neon clutter
 * - Single purposeful emerald beacon (#10B981) for live cluster link vitality
 * - UNIFIED SERVER VITALITY CORE: 240° radial vector gauge displaying broker capacity
 *   flanked symmetrically by measured latency RTT and a labelled throughput preview
 * - 5-partition topic matrix strip with proportional micro-gauges
 * - Biometric-authenticated retention policy engine
 * - Fixed cockpit command dock at the base with zero scrolling and zero blank voids
 */
@Composable
fun AdminControlScreen(
    currentUser: UserProfile?,
    onBack: () -> Unit
) {
    if (!AdminControlAccess.canAccess(currentUser)) {
        LaunchedEffect(currentUser?.uid) { onBack() }
        return
    }
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var usageStats by remember { mutableStateOf<BrokerUsageStats?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var liveStageText by remember { mutableStateOf<String?>(null) }

    var isCleaning by remember { mutableStateOf(false) }
    var cleanProgress by remember { mutableFloatStateOf(0f) }
    var cleanStatusText by remember { mutableStateOf<String?>(null) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var forceCleanSelected by remember { mutableStateOf(false) }
    var eligibilityStats by remember { mutableStateOf(CleanupEligibility()) }

    var automaticCleanup by remember(currentUser?.uid) { mutableStateOf<AutomaticCleanupState?>(null) }
    var cleanupSettingBusy by remember(currentUser?.uid) { mutableStateOf(false) }
    var cleanupSettingRefreshing by remember(currentUser?.uid) { mutableStateOf(false) }
    var cleanupSettingError by remember(currentUser?.uid) { mutableStateOf<String?>(null) }
    var cleanupNeedsReconnect by remember(currentUser?.uid) { mutableStateOf(false) }
    val brokerReachable by EmqxTransport.connection.healthy.collectAsState()
    var adminAuthRevision by remember(currentUser?.uid) { mutableIntStateOf(0) }
    val cleanupLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle

    suspend fun refreshCleanupSetting(tokenOverride: String? = null) {
        if (cleanupSettingBusy || cleanupSettingRefreshing) return
        cleanupSettingRefreshing = true
        try {
            automaticCleanup = AutomaticCleanupApi.fetch(context, requireNotNull(currentUser), tokenOverride)
            cleanupNeedsReconnect = false
            cleanupSettingError = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            cleanupNeedsReconnect = error is AdminAccountReconnectRequired
            // A failed read is not proof of ON or OFF. Require a fresh read before changes.
            automaticCleanup = null
            cleanupSettingError = if (cleanupNeedsReconnect) "Reconnect this admin account to use the control." else null
        } finally {
            cleanupSettingRefreshing = false
        }
    }

    val reconnectAdmin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(com.google.android.gms.common.api.ApiException::class.java)
            AutomaticCleanupApi.requireMatchingAccount(requireNotNull(currentUser), account.id)
            val token = account.idToken ?: throw AdminAccountReconnectRequired()
            adminAuthRevision++
            coroutineScope.launch { refreshCleanupSetting(token) }
        } catch (_: Exception) {
            cleanupSettingError = "Choose the Google account already used by this administrator."
        }
    }

    fun changeAutomaticCleanup(enabled: Boolean) {
        val previous = automaticCleanup ?: return
        if (cleanupSettingBusy || cleanupSettingRefreshing || previous.enabled == enabled) return
        val activity = context.findActivity()
        if (activity == null || !BiometricAuthManager.isBiometricAvailable(activity)) {
            cleanupSettingError = "Enrol a fingerprint to change automatic cleanup."
            return
        }
        cleanupSettingBusy = true
        BiometricAuthManager.authenticate(
            activity = activity,
            title = "Automatic cleanup",
            subtitle = if (enabled) "Turn automatic cleanup ON" else "Pause automatic cleanup",
            description = "Verify your fingerprint to change this shared setting",
            requireBiometric = true,
            onSuccess = {
                coroutineScope.launch {
                    try {
                        automaticCleanup = AutomaticCleanupApi.update(context, requireNotNull(currentUser), enabled, previous.revision)
                        cleanupSettingError = null
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // The server might have committed before a connection failed. Re-read it.
                        automaticCleanup = null
                        statusMessage = "Cleanup change not confirmed: ${error.message}"
                    } finally {
                        cleanupSettingBusy = false
                    }
                    if (automaticCleanup == null) refreshCleanupSetting()
                }
            },
            onError = {
                cleanupSettingBusy = false
                cleanupSettingError = "Verification cancelled or unavailable. Setting unchanged."
            }
        )
    }

    LaunchedEffect(currentUser?.uid, cleanupLifecycle) {
        cleanupLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refreshCleanupSetting()
                delay(15_000L)
            }
        }
    }

    // All 87 owner-approved values are fixed for both authorised accounts.
    val uiAdjustments = AdminDashboardLayout

    // Dynamic retention config with biometric lock state & auto-lock countdown
    var retentionConfig by remember { mutableStateOf(AdminServiceManager.retentionConfig) }
    var isConfigUnlocked by remember { mutableStateOf(false) }
    var unlockRemainingSeconds by remember { mutableIntStateOf(60) }

    LaunchedEffect(isConfigUnlocked) {
        if (isConfigUnlocked) {
            unlockRemainingSeconds = 60
            while (unlockRemainingSeconds > 0) {
                delay(1000L)
                unlockRemainingSeconds--
            }
            isConfigUnlocked = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Live Socket Ping from NetworkPingMonitor
    val realPingMs by EmqxTransport.connection.rtt.collectAsState()
    val displayPing = if (brokerReachable) realPingMs else 0L
    var displayThroughput by remember { mutableIntStateOf(142) }

    // Existing throughput animation is a preview, not a measured broker rate.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200L)
            displayThroughput = (135 + Random.nextInt(-8, 12)).coerceIn(110, 180)
        }
    }

    val adminUsername = currentUser?.username?.ifEmpty { currentUser.playerId } ?: ""

    // Continuous refresh icon rotation while loading
    val infiniteRotation = rememberInfiniteTransition(label = "infiniteRot")
    val spinnerAngle by infiniteRotation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinnerAngle"
    )

    // Breathing glow on retention card when biometric unlocked
    val unlockedPulseAlpha by infiniteRotation.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "unlockedPulseAlpha"
    )

    // Subtle broom tilt animation during cleanup
    val broomTilt by infiniteRotation.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "broomTilt"
    )

    // Pulsing sonar beacon on live broker connection
    val beaconRingScale by infiniteRotation.animateFloat(
        initialValue = 1f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconRingScale"
    )
    val beaconRingAlpha by infiniteRotation.animateFloat(
        initialValue = 0.75f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconRingAlpha"
    )

    // Breathing border on clean expired button when records are eligible
    val eligiblePulseAlpha by infiniteRotation.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eligiblePulseAlpha"
    )

    // ==================== ENGINE REV SPEEDOMETER ANIMATION ====================
    val gaugeProgressAnim = remember { Animatable(0.042f) }
    val gaugeNumberAnim = remember { Animatable(84f) }
    var isRevving by remember { mutableStateOf(false) }

    fun triggerEngineRev(targetP: Float, targetNum: Int) {
        if (isRevving) return // Single animation execution guard
        isRevving = true
        coroutineScope.launch {
            try {
                val numJob = launch {
                    gaugeNumberAnim.animateTo(
                        targetValue = 1840f,
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                    )
                    gaugeNumberAnim.animateTo(
                        targetValue = targetNum.toFloat(),
                        animationSpec = spring(
                            dampingRatio = 0.75f,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                // Single decisive rev up like hitting the throttle
                gaugeProgressAnim.animateTo(
                    targetValue = 0.92f,
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                )
                // Smooth spring settle down to target value (single clean motion)
                gaugeProgressAnim.animateTo(
                    targetValue = targetP,
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = Spring.StiffnessLow
                    )
                )
                numJob.join()
            } finally {
                isRevving = false
            }
        }
    }

    fun loadStatus(force: Boolean = false) {
        isLoading = true
        liveStageText = "Connecting to EMQX TLS cluster..."
        coroutineScope.launch {
            val result = AdminServiceManager.fetchBrokerStatus(
                adminUsername = adminUsername,
                forceRefresh = force,
                onStage = { stage ->
                    liveStageText = stage
                }
            )
            result.onSuccess { stats ->
                usageStats = stats
                statusMessage = null
                isLoading = false
                liveStageText = null
            }.onFailure { err ->
                statusMessage = null
                isLoading = false
                liveStageText = null
            }
        }
    }

    fun triggerClean(force: Boolean = false) {
        isCleaning = true
        cleanProgress = 0f
        cleanStatusText = "Connecting to broker purge pipeline..."
        coroutineScope.launch {
            val result = AdminServiceManager.triggerManualCleanup(
                adminUsername = adminUsername,
                force = force,
                onProgress = { deleted, total, status ->
                    cleanProgress = if (total > 0) deleted.toFloat() / total.toFloat() else 0f
                    cleanStatusText = status
                }
            )
            result.onSuccess { res ->
                if (res.deletedConfirmed > 0) {
                    statusMessage = "🎉 Successfully removed ${res.deletedConfirmed} expired messages!"
                    res.usage?.let { usageStats = it }
                } else if (res.deferred) {
                    statusMessage = "⚠️ Cleanup deferred: ${res.reason ?: "Active match in progress"}"
                } else {
                    statusMessage = "ℹ️ ${res.reason ?: "All messages are fresh within configured timeouts."}"
                    res.usage?.let { usageStats = it }
                }
                isCleaning = false
                cleanStatusText = null
            }.onFailure { err ->
                statusMessage = "❌ Cleanup failed: ${err.message}"
                isCleaning = false
                cleanStatusText = null
            }
        }
    }

    fun requestBiometricUnlock(onUnlocked: () -> Unit) {
        val activity = context.findActivity()
        if (activity != null && BiometricAuthManager.isBiometricAvailable(activity)) {
            BiometricAuthManager.authenticate(
                activity = activity,
                title = "Security Verification",
                subtitle = "Fingerprint required for @$adminUsername",
                description = "Verify biometric identity to modify retention timeouts",
                onSuccess = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isConfigUnlocked = true
                    onUnlocked()
                },
                onError = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            )
        } else {
            isConfigUnlocked = true
            onUnlocked()
        }
    }

    LaunchedEffect(Unit) {
        AdminServiceManager.initConfig(context)
        retentionConfig = AdminServiceManager.retentionConfig
        triggerEngineRev(0.042f, 84)
        loadStatus(force = false)
    }

    BackHandler {
        onBack()
    }

    val currentUsed = usageStats?.used ?: 0
    val currentLimit = usageStats?.limit ?: 2000
    val currentFree = usageStats?.free ?: (currentLimit - currentUsed)

    // Animated rolling numbers with spring physics
    val springIntSpec = spring<Int>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )
    val animMatches by animateIntAsState(targetValue = usageStats?.matchesCount ?: 0, animationSpec = springIntSpec, label = "animMatches")
    val animLobbies by animateIntAsState(targetValue = usageStats?.lobbiesCount ?: 0, animationSpec = springIntSpec, label = "animLobbies")
    val animPresence by animateIntAsState(targetValue = usageStats?.presenceCount ?: 0, animationSpec = springIntSpec, label = "animPresence")
    val animProfiles by animateIntAsState(targetValue = usageStats?.profilesCount ?: 0, animationSpec = springIntSpec, label = "animProfiles")
    val animOther by animateIntAsState(targetValue = usageStats?.otherCount ?: 0, animationSpec = springIntSpec, label = "animOther")

    // Smooth sync when stats update normally without rev
    LaunchedEffect(currentUsed, currentLimit) {
        if (!isRevving && usageStats != null) {
            val targetP = (currentUsed.toFloat() / currentLimit.toFloat()).coerceIn(0f, 1f)
            launch {
                gaugeProgressAnim.animateTo(
                    targetValue = targetP,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                )
            }
            launch {
                gaugeNumberAnim.animateTo(
                    targetValue = currentUsed.toFloat(),
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                )
            }
        }
    }

    // Animated lock rotation
    val lockRotation by animateFloatAsState(
        targetValue = if (isConfigUnlocked) 360f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lockRot"
    )
    val configBorderColor by animateColorAsState(
        targetValue = if (isConfigUnlocked) Color(0xFF10B981).copy(alpha = unlockedPulseAlpha) else tokens.surfaceBorder,
        animationSpec = tween(350),
        label = "configBorder"
    )

    val displayGaugeProgress = gaugeProgressAnim.value
    val displayUsedNumber = gaugeNumberAnim.value.toInt()
    val displayPercent = (gaugeProgressAnim.value * 100f).coerceIn(0f, 100f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ==================== 1. PINNED TOP COCKPIT HEADER ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .appPagePadding(horizontal = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.badgeSurface)
                            .border(1.dp, tokens.badgeOutline, RoundedCornerShape(8.dp))
                            .bounceClick {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onBack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = tokens.cellNeutralText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "Admin Control",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Superadmin Cryptographic Badge
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tokens.badgeSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "👑", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "@$adminUsername",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                        }
                    }
                }
            }

            // ==================== 2. SCROLLABLE BODY (CARDS & TELEMETRY) ====================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(uiAdjustments.globalCardSpacingDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ==================== A. THE HERO RETAINED MESSAGES SPEEDOMETER ====================
                if (uiAdjustments.showSpeedometer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // BIG REALISTIC AUTOMOTIVE SPEEDOMETER
                        Box(
                            modifier = Modifier.size(uiAdjustments.speedometerSizeDp.dp).brokerGaugeAppearance(brokerReachable),
                            contentAlignment = Alignment.Center
                        ) {
                            RealisticSpeedometerGaugeCanvas(
                                progress = displayGaugeProgress,
                                modifier = Modifier.fillMaxSize(),
                                isDark = tokens.isDark,
                                needleLengthScale = uiAdjustments.needleLengthScale,
                                needleBaseWidthDp = uiAdjustments.needleBaseWidthDp,
                                needleGlowAlpha = uiAdjustments.needleGlowAlpha,
                                dialBacklightAlpha = uiAdjustments.dialBacklightAlpha,
                                redlineStartPercent = uiAdjustments.redlineStartPercent
                            )

                            // Central Speedometer Readout: Positioned in real-time via readoutOffsetYDp
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = uiAdjustments.readoutOffsetYDp.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (usageStats == null) "—" else "$displayUsedNumber",
                                    fontSize = uiAdjustments.readoutNumberSizeSp.sp,
                                    fontWeight = FontWeight.Black,
                                    color = tokens.textPrimary,
                                    lineHeight = uiAdjustments.readoutNumberSizeSp.sp
                                )
                                Text(
                                    text = "RETAINED MESSAGES",
                                    fontSize = uiAdjustments.readoutLabelSizeSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.textMuted,
                                    letterSpacing = 1.2.sp
                                )
                            }
                        }
                    }
                }

                // STATUS DISPLAYED DIRECTLY BELOW THE SPEEDOMETER!
                if (uiAdjustments.showStatusPill) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tokens.badgeSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline),
                        modifier = Modifier.offset(y = uiAdjustments.statusPillOffsetYDp.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = uiAdjustments.statusPillPaddingHorizDp.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .scale(beaconRingScale)
                                        .clip(CircleShape)
                                        .border(1.dp, (if (brokerReachable) Color(0xFF10B981) else Color.Gray).copy(alpha = beaconRingAlpha), CircleShape)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (!brokerReachable) Color.Gray else if (displayPercent >= (uiAdjustments.redlineStartPercent * 100f)) Color(0xFFEF4444) else Color(0xFF10B981))
                                )
                            }
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "$displayUsedNumber / $currentLimit Quota (${String.format(Locale.US, "%.1f", displayPercent)}% Used) • $currentFree Free",
                                fontSize = uiAdjustments.statusPillFontSizeSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.textPrimary
                            )
                        }
                    }
                }

                // Live Fetch / Syncing Status Indicator (displayed below speedometer when active)
                AnimatedVisibility(
                    visible = uiAdjustments.showLiveSyncMessage && isLoading && liveStageText != null,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.badgeSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = tokens.textPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = liveStageText ?: "Syncing with EMQX...",
                                fontSize = uiAdjustments.liveSyncFontSizeSp.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.textSecondary
                            )
                        }
                    }
                }

                // Status Message Banner (displayed below speedometer when active)
                AnimatedVisibility(
                    visible = statusMessage != null && brokerReachable,
                    enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    statusMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (msg.startsWith("❌")) Color(0xFFEF4444).copy(alpha = 0.12f)
                            else if (msg.startsWith("⚠️") || msg.startsWith("ℹ️")) Color(0xFFF59E0B).copy(alpha = 0.12f)
                            else Color(0xFF10B981).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (msg.startsWith("❌")) Color(0xFFEF4444).copy(alpha = 0.3f)
                                else if (msg.startsWith("⚠️") || msg.startsWith("ℹ️")) Color(0xFFF59E0B).copy(alpha = 0.3f)
                                else Color(0xFF10B981).copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = uiAdjustments.alertBannerFontSizeSp.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "✕",
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .clickable { statusMessage = null },
                                        color = tokens.textMuted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                // ==================== B. CARD 1: LATENCY & REAL-TIME SOCKET TELEMETRY (BIG WAVEFORM) ====================
                if (uiAdjustments.showTelemetryCard) {
                    Surface(
                        shape = RoundedCornerShape(uiAdjustments.telemetryCardCornerDp.dp),
                        color = adminUiColor(uiAdjustments.telemetryCardColorHex, tokens.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, adminUiColor(uiAdjustments.telemetryBorderColorHex, tokens.surfaceBorder)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(uiAdjustments.telemetryCardPaddingDp.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "📡", fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE LATENCY & THROUGHPUT",
                                        maxLines = 2,
                                        fontSize = uiAdjustments.telemetryLabelFontSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textMuted,
                                        letterSpacing = 0.6.sp
                                    )
                                }
                                Text(
                                    text = "Interactive",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF10B981)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Latency & Ingress Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "PHONE LATENCY (RAW RTT)",
                                        fontSize = uiAdjustments.telemetryMetricLabelSizeSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tokens.textMuted
                                    )
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = if (displayPing > 0) "$displayPing" else "—",
                                            fontSize = uiAdjustments.telemetryValueFontSizeSp.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.textPrimary
                                        )
                                        Text(
                                            text = " ms",
                                            fontSize = uiAdjustments.telemetryUnitSizeSp.sp,
                                            color = tokens.textMuted,
                                            modifier = Modifier.padding(bottom = 3.dp)
                                        )
                                    }
                                    if (uiAdjustments.telemetryShowDetails) Text(
                                        text = if (displayPing > 0) "Latest measured RTT" else "Awaiting measured ping",
                                        fontSize = 9.sp,
                                        color = tokens.textMuted
                                    )
                                }

                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "INGRESS (PREVIEW)",
                                        fontSize = uiAdjustments.telemetryMetricLabelSizeSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tokens.textMuted
                                    )
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = if (brokerReachable) "$displayThroughput" else "—",
                                            fontSize = uiAdjustments.telemetryValueFontSizeSp.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.textPrimary
                                        )
                                        Text(
                                            text = " msg/s",
                                            fontSize = uiAdjustments.telemetryUnitSizeSp.sp,
                                            color = tokens.textMuted,
                                            modifier = Modifier.padding(bottom = 3.dp)
                                        )
                                    }
                                    if (uiAdjustments.telemetryShowDetails) Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                                    ) {
                                        Text(
                                            text = "SIMULATED",
                                            fontSize = uiAdjustments.telemetryMetricLabelSizeSp.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            AdminLatencyChart(adjustments = uiAdjustments,
                                user = requireNotNull(currentUser), authRevision = adminAuthRevision,
                                onReconnect = { reconnectAdmin.launch(GoogleSignIn.getClient(context,
                                    AutomaticCleanupApi.googleOptions(requireNotNull(currentUser))).signInIntent) })
                        }
                    }
                }

                // ==================== C. CARD 2: TECHNICAL SPECIFICATIONS CARD ====================
                if (uiAdjustments.showTechnicalSpecsCard) {
                    Surface(
                        shape = RoundedCornerShape(uiAdjustments.techCardCornerDp.dp),
                        color = tokens.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(uiAdjustments.techCardPaddingDp.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "⚡", fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "TECHNICAL SPECIFICATIONS",
                                        fontSize = uiAdjustments.techHeaderFontSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textMuted,
                                        letterSpacing = 0.6.sp
                                    )
                                }
                                Text(
                                    text = "EMQX Serverless AP",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.textSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Technical Spec Rows
                            CockpitTechSpecRow(
                                label1 = "LOCATION", value1 = "🇸🇬 Singapore",
                                label2 = "REGION", value2 = "ap-southeast-1",
                                tokens = tokens,
                                valueFontSize = uiAdjustments.techValueFontSizeSp
                            )
                            HorizontalDivider(color = tokens.surfaceBorder, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 8.dp))

                            CockpitTechSpecRow(
                                label1 = "BROKER PORT", value1 = "8883 (MQTTS/SSL)",
                                label2 = "SECURITY", value2 = "TLS 1.3 (ChaCha20)",
                                tokens = tokens,
                                valueFontSize = uiAdjustments.techValueFontSizeSp
                            )
                            HorizontalDivider(color = tokens.surfaceBorder, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 8.dp))

                            CockpitTechSpecRow(
                                label1 = "SOCKET TRANSPORT", value1 = "ALPN mqtt (Direct TCP)",
                                label2 = "CLUSTER TOPOLOGY", value2 = if (brokerReachable) "● Connected" else "Not connected",
                                isGreen2 = brokerReachable,
                                tokens = tokens,
                                valueFontSize = uiAdjustments.techValueFontSizeSp
                            )
                            HorizontalDivider(color = tokens.surfaceBorder, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 8.dp))

                            CockpitTechSpecRow(
                                label1 = "STORAGE QUOTA", value1 = "2,000 Messages",
                                label2 = "UPTIME SLA", value2 = "99.99% SLA",
                                tokens = tokens,
                                valueFontSize = uiAdjustments.techValueFontSizeSp
                            )
                        }
                    }
                }

                // ==================== D. CARD 3: TOPIC PARTITIONS MATRIX ====================
                if (uiAdjustments.showTopicMatrixCard) {
                    AdminTopicPartitionsCard(
                        partitions = listOf(
                            AdminTopicPartition("🎮", "Matches", "bingo/match/*", animMatches),
                            AdminTopicPartition("🚪", "Lobbies", "bingo/lobby/*", animLobbies),
                            AdminTopicPartition("📡", "Presence", "bingo/presence/*", animPresence),
                            AdminTopicPartition("👤", "Profiles", "bingo/profile/*", animProfiles),
                            AdminTopicPartition("📦", "System", "bingo/meta/*", animOther)
                        ),
                        total = currentUsed,
                        adjustments = uiAdjustments,
                        tokens = tokens
                    )
                }

                // ==================== E. CARD 4: RETENTION TIMEOUT POLICY ENGINE ====================
                if (uiAdjustments.showRetentionCard) {
                    Surface(
                        shape = RoundedCornerShape(uiAdjustments.retentionCardCornerDp.dp),
                        color = tokens.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, configBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(uiAdjustments.retentionCardPaddingDp.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "🛡️", fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "RETENTION ENGINE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textMuted,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // Biometric Lock/Unlock Switch with Live Auto-Lock Countdown
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = tokens.badgeSurface,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline),
                                    modifier = Modifier.bounceClick {
                                        if (isConfigUnlocked) {
                                            isConfigUnlocked = false
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } else {
                                            requestBiometricUnlock { }
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isConfigUnlocked) "🔓" else "🔒",
                                            fontSize = 10.sp,
                                            modifier = Modifier.rotate(lockRotation)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isConfigUnlocked) "Unlocked (${unlockRemainingSeconds}s)" else "Locked",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isConfigUnlocked) Color(0xFF10B981) else tokens.textMuted
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Automatic cleanup", fontSize = 12.sp, color = tokens.cellNeutralText)
                                }
                                Switch(
                                    checked = automaticCleanup?.enabled == true,
                                    onCheckedChange = ::changeAutomaticCleanup,
                                    enabled = automaticCleanup != null && !cleanupSettingBusy && !cleanupSettingRefreshing &&
                                        (automaticCleanup?.configured == true || automaticCleanup?.enabled == true),
                                    modifier = Modifier.semantics {
                                        contentDescription = "Automatic cleanup"
                                        stateDescription = when {
                                            cleanupSettingBusy -> "Verifying / saving"
                                            automaticCleanup == null -> "Status unavailable"
                                            automaticCleanup?.enabled == true -> "On"
                                            else -> "Off"
                                        }
                                    }
                                )
                            }
                            cleanupSettingError?.let { error ->
                                Text(error, fontSize = 9.sp, color = tokens.textMuted)
                            }
                            if (automaticCleanup == null && !cleanupSettingBusy) {
                                TextButton(onClick = {
                                        if (cleanupNeedsReconnect) reconnectAdmin.launch(GoogleSignIn.getClient(context,
                                            AutomaticCleanupApi.googleOptions(requireNotNull(currentUser))).signInIntent)
                                        else coroutineScope.launch { refreshCleanupSetting() }
                                    },
                                    enabled = !cleanupSettingRefreshing) {
                                    Text(if (cleanupSettingRefreshing) "Loading…" else if (cleanupNeedsReconnect) "Reconnect admin account" else "Retry", fontSize = 10.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Compact Inline Policy Rows
                            CockpitTimeoutInlineRow(
                                label = "Finished",
                                options = listOf(10 to "10m", 30 to "30m", 60 to "1h", 120 to "2h"),
                                selected = retentionConfig.finishedMatchExpiryMinutes,
                                onSelect = { newValue ->
                                    if (!isConfigUnlocked) {
                                        requestBiometricUnlock {
                                            val updated = retentionConfig.copy(finishedMatchExpiryMinutes = newValue)
                                            retentionConfig = updated
                                            AdminServiceManager.updateConfig(context, updated)
                                        }
                                    } else {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val updated = retentionConfig.copy(finishedMatchExpiryMinutes = newValue)
                                        retentionConfig = updated
                                        AdminServiceManager.updateConfig(context, updated)
                                    }
                                },
                                tokens = tokens,
                                pillHeight = uiAdjustments.retentionPillHeightDp,
                                pillFontSize = uiAdjustments.retentionPillFontSizeSp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            CockpitTimeoutInlineRow(
                                label = "Lobbies",
                                options = listOf(15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h"),
                                selected = retentionConfig.closedLobbyExpiryMinutes,
                                onSelect = { newValue ->
                                    if (!isConfigUnlocked) {
                                        requestBiometricUnlock {
                                            val updated = retentionConfig.copy(closedLobbyExpiryMinutes = newValue)
                                            retentionConfig = updated
                                            AdminServiceManager.updateConfig(context, updated)
                                        }
                                    } else {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val updated = retentionConfig.copy(closedLobbyExpiryMinutes = newValue)
                                        retentionConfig = updated
                                        AdminServiceManager.updateConfig(context, updated)
                                    }
                                },
                                tokens = tokens,
                                pillHeight = uiAdjustments.retentionPillHeightDp,
                                pillFontSize = uiAdjustments.retentionPillFontSizeSp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            CockpitTimeoutInlineRow(
                                label = "Dead",
                                options = listOf(30 to "30m", 60 to "1h", 120 to "2h", 360 to "6h"),
                                selected = retentionConfig.abandonedMatchExpiryMinutes,
                                onSelect = { newValue ->
                                    if (!isConfigUnlocked) {
                                        requestBiometricUnlock {
                                            val updated = retentionConfig.copy(abandonedMatchExpiryMinutes = newValue)
                                            retentionConfig = updated
                                            AdminServiceManager.updateConfig(context, updated)
                                        }
                                    } else {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val updated = retentionConfig.copy(abandonedMatchExpiryMinutes = newValue)
                                        retentionConfig = updated
                                        AdminServiceManager.updateConfig(context, updated)
                                    }
                                },
                                tokens = tokens,
                                pillHeight = uiAdjustments.retentionPillHeightDp,
                                pillFontSize = uiAdjustments.retentionPillFontSizeSp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            // ==================== 3. PINNED BOTTOM ACTION DOCK ====================
            Surface(
                color = tokens.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(uiAdjustments.actionDockHeightDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Refresh Button (triggers Engine Rev Animation)
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val currentP = (currentUsed.toFloat() / currentLimit.toFloat()).coerceIn(0f, 1f)
                            triggerEngineRev(currentP, currentUsed)
                            loadStatus(force = true)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(uiAdjustments.actionDockCornerDp.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.badgeSurface,
                            contentColor = tokens.textPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline)
                    ) {
                        if (isLoading && !isRevving) {
                            Text(
                                text = "↻",
                                fontSize = (uiAdjustments.actionDockButtonFontSizeSp + 3f).sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.rotate(spinnerAngle)
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "↻",
                                    fontSize = (uiAdjustments.actionDockButtonFontSizeSp + 2f).sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.rotate(if (isRevving) spinnerAngle else 0f)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Refresh",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = uiAdjustments.actionDockButtonFontSizeSp.sp
                                )
                            }
                        }
                    }

                    // Clean Expired Button
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            eligibilityStats = AdminServiceManager.getEligibleCleanupStats(force = forceCleanSelected)
                            showConfirmDialog = true
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(uiAdjustments.actionDockCornerDp.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tokens.isDark) Color.White else tokens.accentBrand,
                            contentColor = if (tokens.isDark) Color.Black else Color.White
                        ),
                        border = if (eligibilityStats.totalEligible > 0) {
                            androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981).copy(alpha = eligiblePulseAlpha))
                        } else null
                    ) {
                        if (isCleaning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 1.6.dp,
                                    color = if (tokens.isDark) Color.Black else Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (cleanProgress > 0f) "Purging ${(cleanProgress * 100).toInt()}%" else "Purging...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (uiAdjustments.actionDockButtonFontSizeSp - 1f).sp
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🧹",
                                    fontSize = (uiAdjustments.actionDockButtonFontSizeSp + 1f).sp,
                                    modifier = Modifier.rotate(if (eligibilityStats.totalEligible > 0) broomTilt else 0f)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (eligibilityStats.totalEligible > 0) "Clean Expired (${eligibilityStats.totalEligible})" else "Clean Expired",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = uiAdjustments.actionDockButtonFontSizeSp.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== BIOMETRIC CONFIRMATION MODAL ====================
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BiometricLaserScanner()
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Clean Expired Messages?",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "Biometric confirmation required",
                                fontSize = 10.sp,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                },
                text = {
                    Column {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = tokens.badgeSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.badgeOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Eligible for Cleanup",
                                        fontSize = 11.sp,
                                        color = tokens.textSecondary
                                    )
                                    Text(
                                        text = "${eligibilityStats.totalEligible} records",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "• Finished Matches", fontSize = 10.sp, color = tokens.textMuted)
                                    Text(
                                        text = "${eligibilityStats.finishedMatches + eligibilityStats.abandonedMatches}",
                                        fontSize = 10.sp,
                                        color = tokens.textSecondary
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "• Closed Lobbies", fontSize = 10.sp, color = tokens.textMuted)
                                    Text(
                                        text = "${eligibilityStats.closedLobbies}",
                                        fontSize = 10.sp,
                                        color = tokens.textSecondary
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "• Stale Presence / Invites", fontSize = 10.sp, color = tokens.textMuted)
                                    Text(
                                        text = "${eligibilityStats.stalePresence + eligibilityStats.staleInvites}",
                                        fontSize = 10.sp,
                                        color = tokens.textSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Estimated Time", fontSize = 10.sp, color = tokens.textMuted)
                                    Text(
                                        text = "~${eligibilityStats.estimatedTimeSeconds} seconds",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    forceCleanSelected = !forceCleanSelected
                                    eligibilityStats = AdminServiceManager.getEligibleCleanupStats(force = forceCleanSelected)
                                }
                        ) {
                            Checkbox(
                                checked = forceCleanSelected,
                                onCheckedChange = {
                                    forceCleanSelected = it
                                    eligibilityStats = AdminServiceManager.getEligibleCleanupStats(force = forceCleanSelected)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = if (tokens.isDark) Color.White else tokens.accentBrand
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Force clean all inactive games",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "🛡️ User profiles and account registries are never deleted.",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity != null && BiometricAuthManager.isBiometricAvailable(activity)) {
                                BiometricAuthManager.authenticate(
                                    activity = activity,
                                    title = "Confirm Broker Cleanup",
                                    subtitle = "Fingerprint required for @$adminUsername",
                                    description = "Verify identity to purge ${eligibilityStats.totalEligible} expired messages",
                                    onSuccess = {
                                        showConfirmDialog = false
                                        triggerClean(force = forceCleanSelected)
                                    },
                                    onError = {
                                        // cancelled or failed
                                    }
                                )
                            } else {
                                showConfirmDialog = false
                                triggerClean(force = forceCleanSelected)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tokens.isDark) Color.White else tokens.accentBrand,
                            contentColor = if (tokens.isDark) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "Confirm & Clean", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text("Cancel", color = tokens.textMuted, fontSize = 12.sp)
                    }
                },
                containerColor = tokens.surface,
                shape = RoundedCornerShape(12.dp)
            )
        }

    }
}

/**
 * Realistic Automotive Speedometer Gauge Canvas
 * Features:
 * - 240° Precision scale with real numeric range values (0, 400, 800, 1200, 1600, 2000)
 * - Major, medium, and minor precision tick marks (every 50 msgs)
 * - Tachometer Redline warning zone (1600 to 2000 quota)
 * - Real 3D tapered needle with counterweight tail, drop shadow, and illuminated pointer tip
 * - Ambient backlit background gradient light & dynamic illumination glow following the needle
 * - Brushed metallic bezel rim and concentric machined chronometer texture
 * - Realistic center pivot hub cap with metallic bevel and jewel pin
 */
@Composable
private fun RealisticSpeedometerGaugeCanvas(
    progress: Float,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    needleLengthScale: Float = 1.0f,
    needleBaseWidthDp: Float = 4.0f,
    needleGlowAlpha: Float = 0.28f,
    dialBacklightAlpha: Float = 0.55f,
    redlineStartPercent: Float = 0.8f
) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val cx = width / 2f
        val cy = height / 2f
        val radius = (size.minDimension / 2f) - 14.dp.toPx()

        // 1. OUTER MACHINED INSTRUMENT BEZEL & RIM
        drawCircle(
            brush = Brush.linearGradient(
                colors = if (isDark) listOf(Color(0xFF33384A), Color(0xFF1B1E28), Color(0xFF0F1017))
                else listOf(Color(0xFFCBD5E1), Color(0xFF94A3B8), Color(0xFF64748B)),
                start = Offset(0f, 0f),
                end = Offset(width, height)
            ),
            radius = radius + 10.dp.toPx()
        )
        drawCircle(
            color = if (isDark) Color(0xFF383E54) else Color(0xFF94A3B8),
            radius = radius + 10.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 2. BACKGROUND GRADIENT LIGHT (Ambient Backlit Dial Glow)
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (isDark) listOf(
                    Color(0xFF1E2638).copy(alpha = dialBacklightAlpha.coerceIn(0f, 1f)),
                    Color(0xFF121520).copy(alpha = (dialBacklightAlpha * 0.75f).coerceIn(0f, 1f)),
                    Color(0xFF090B10).copy(alpha = (dialBacklightAlpha * 0.5f).coerceIn(0f, 1f)),
                    Color(0xFF050608)
                ) else listOf(
                    Color(0xFFF8FAFC),
                    Color(0xFFF1F5F9),
                    Color(0xFFE2E8F0),
                    Color(0xFFCBD5E1)
                ),
                center = Offset(cx, cy),
                radius = radius + 8.dp.toPx()
            ),
            radius = radius + 8.dp.toPx()
        )
        drawCircle(
            color = if (isDark) Color(0xFF181B24) else Color(0xFFCBD5E1),
            radius = radius + 8.dp.toPx(),
            style = Stroke(width = 1.dp.toPx())
        )

        // Concentric precision machined texture rings
        drawCircle(
            color = if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.04f),
            radius = radius - 16.dp.toPx(),
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = if (isDark) Color.White.copy(alpha = 0.025f) else Color.Black.copy(alpha = 0.025f),
            radius = radius - 30.dp.toPx(),
            style = Stroke(width = 0.8.dp.toPx())
        )

        // 3. SPEEDOMETER SCALE ARC & REDLINE ZONE
        val startAngle = 150f
        val totalSweep = 240f
        val arcRadius = radius - 2.dp.toPx()
        val arcDiameter = arcRadius * 2f
        val arcTopLeft = Offset(cx - arcRadius, cy - arcRadius)
        val arcSize = Size(arcDiameter, arcDiameter)

        // Background groove track
        drawArc(
            color = if (isDark) Color(0xFF161924) else Color(0xFFCBD5E1),
            startAngle = startAngle,
            sweepAngle = totalSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Redline warning arc zone (e.g. 80% to 100% quota)
        val redlineStart = startAngle + totalSweep * redlineStartPercent.coerceIn(0.5f, 0.95f)
        val redlineSweep = totalSweep * (1f - redlineStartPercent.coerceIn(0.5f, 0.95f))
        drawArc(
            color = Color(0xFFEF4444).copy(alpha = 0.5f),
            startAngle = redlineStart,
            sweepAngle = redlineSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )

        // Active illuminated arc up to current needle position
        val activeSweep = totalSweep * progress.coerceIn(0f, 1f)
        if (activeSweep > 1f) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF94A3B8),
                        Color.White,
                        if (progress >= redlineStartPercent) Color(0xFFEF4444) else Color.White
                    ),
                    center = Offset(cx, cy)
                ),
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // 4. PRECISION TICKS & RANGE VALUES
        // Minor ticks (every 50 msgs, 40 divisions)
        val minorSteps = 40
        for (i in 0..minorSteps) {
            val isMajor = i % 8 == 0
            val isMedium = i % 4 == 0 && !isMajor
            if (isMajor || isMedium) continue

            val tickAngle = startAngle + (totalSweep / minorSteps) * i
            val tickRad = Math.toRadians(tickAngle.toDouble())
            val isRed = (i.toFloat() / minorSteps) >= redlineStartPercent
            val outerR = radius - 2.dp.toPx()
            val innerR = outerR - 3.5.dp.toPx()

            val x1 = (cx + innerR * Math.cos(tickRad)).toFloat()
            val y1 = (cy + innerR * Math.sin(tickRad)).toFloat()
            val x2 = (cx + outerR * Math.cos(tickRad)).toFloat()
            val y2 = (cy + outerR * Math.sin(tickRad)).toFloat()

            drawLine(
                color = if (isRed) Color(0xFFEF4444).copy(alpha = 0.35f)
                else if (isDark) Color.White.copy(alpha = 0.16f)
                else Color.Black.copy(alpha = 0.18f),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Medium ticks (every 200 msgs, 10 divisions)
        val mediumSteps = 10
        for (i in 0..mediumSteps) {
            val isMajor = i % 2 == 0
            if (isMajor) continue

            val tickAngle = startAngle + (totalSweep / mediumSteps) * i
            val tickRad = Math.toRadians(tickAngle.toDouble())
            val isRed = (i.toFloat() / mediumSteps) >= redlineStartPercent
            val outerR = radius - 2.dp.toPx()
            val innerR = outerR - 6.dp.toPx()

            val x1 = (cx + innerR * Math.cos(tickRad)).toFloat()
            val y1 = (cy + innerR * Math.sin(tickRad)).toFloat()
            val x2 = (cx + outerR * Math.cos(tickRad)).toFloat()
            val y2 = (cy + outerR * Math.sin(tickRad)).toFloat()

            drawLine(
                color = if (isRed) Color(0xFFEF4444).copy(alpha = 0.7f)
                else if (isDark) Color.White.copy(alpha = 0.38f)
                else Color.Black.copy(alpha = 0.4f),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 1.6.dp.toPx()
            )
        }

        // Major ticks & Range Values (0, 400, 800, 1200, 1600, 2000)
        val majorSteps = 5
        val rangeLabels = arrayOf("0", "400", "800", "1200", "1600", "2000")

        for (i in 0..majorSteps) {
            val tickAngle = startAngle + (totalSweep / majorSteps) * i
            val tickRad = Math.toRadians(tickAngle.toDouble())
            val isRed = (i.toFloat() / majorSteps) >= redlineStartPercent
            val outerR = radius - 2.dp.toPx()
            val innerR = outerR - 9.dp.toPx()

            val x1 = (cx + innerR * Math.cos(tickRad)).toFloat()
            val y1 = (cy + innerR * Math.sin(tickRad)).toFloat()
            val x2 = (cx + outerR * Math.cos(tickRad)).toFloat()
            val y2 = (cy + outerR * Math.sin(tickRad)).toFloat()

            // Major Tick Line
            drawLine(
                color = if (isRed) Color(0xFFEF4444) else if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 2.4.dp.toPx()
            )
        }

        // Draw Range Labels with Native Canvas Paint
        drawIntoCanvas { canvas ->
            textPaint.textSize = 9.sp.toPx()
            for (i in 0..majorSteps) {
                val tickAngle = startAngle + (totalSweep / majorSteps) * i
                val tickRad = Math.toRadians(tickAngle.toDouble())
                val textR = radius - 20.dp.toPx()
                val tx = (cx + textR * Math.cos(tickRad)).toFloat()
                val ty = (cy + textR * Math.sin(tickRad)).toFloat()
                val isRed = (i.toFloat() / majorSteps) >= redlineStartPercent

                textPaint.color = if (isRed) {
                    android.graphics.Color.parseColor("#EF4444")
                } else if (isDark) {
                    android.graphics.Color.parseColor("#E2E8F0")
                } else {
                    android.graphics.Color.parseColor("#1E293B")
                }

                val textY = ty - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.nativeCanvas.drawText(rangeLabels[i], tx, textY, textPaint)
            }
        }

        // 5. DYNAMIC ILLUMINATION LIGHT HALO (Follows the needle tip)
        val needleAngle = startAngle + totalSweep * progress.coerceIn(0f, 1f)
        val needleRad = Math.toRadians(needleAngle.toDouble())
        val needleTipR = (radius - 8.dp.toPx()) * needleLengthScale.coerceIn(0.6f, 1.3f)
        val tipX = (cx + needleTipR * Math.cos(needleRad)).toFloat()
        val tipY = (cy + needleTipR * Math.sin(needleRad)).toFloat()

        // Soft ambient radial glow cast by the illuminated needle
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    if (progress >= redlineStartPercent) Color(0xFFEF4444).copy(alpha = needleGlowAlpha.coerceIn(0f, 1f))
                    else Color(0xFF38BDF8).copy(alpha = (needleGlowAlpha * 0.6f).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = Offset(tipX, tipY),
                radius = 34.dp.toPx()
            ),
            radius = 34.dp.toPx(),
            center = Offset(tipX, tipY)
        )

        // 6. REAL NEEDLE (Tapered 3D blade with drop shadow and counterweight)
        val perpRad = needleRad + Math.PI / 2.0
        val baseW = needleBaseWidthDp.dp.toPx()
        val tailLen = 16.dp.toPx() * needleLengthScale.coerceIn(0.6f, 1.3f)
        val tailW = 2.5.dp.toPx()

        val tailX = (cx - tailLen * Math.cos(needleRad)).toFloat()
        val tailY = (cy - tailLen * Math.sin(needleRad)).toFloat()

        val bx1 = (cx + baseW * Math.cos(perpRad)).toFloat()
        val by1 = (cy + baseW * Math.sin(perpRad)).toFloat()
        val bx2 = (cx - baseW * Math.cos(perpRad)).toFloat()
        val by2 = (cy - baseW * Math.sin(perpRad)).toFloat()

        val tx1 = (tailX + tailW * Math.cos(perpRad)).toFloat()
        val ty1 = (tailY + tailW * Math.sin(perpRad)).toFloat()
        val tx2 = (tailX - tailW * Math.cos(perpRad)).toFloat()
        val ty2 = (tailY - tailW * Math.sin(perpRad)).toFloat()

        // 3D Drop Shadow
        val shadowOffset = Offset(2.5.dp.toPx(), 3.5.dp.toPx())
        val shadowPath = Path().apply {
            moveTo(tipX + shadowOffset.x, tipY + shadowOffset.y)
            lineTo(bx1 + shadowOffset.x, by1 + shadowOffset.y)
            lineTo(tx1 + shadowOffset.x, ty1 + shadowOffset.y)
            lineTo(tx2 + shadowOffset.x, ty2 + shadowOffset.y)
            lineTo(bx2 + shadowOffset.x, by2 + shadowOffset.y)
            close()
        }
        drawPath(shadowPath, color = Color.Black.copy(alpha = 0.55f))

        // Main Needle Polygon
        val needlePath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx1, by1)
            lineTo(tx1, ty1)
            lineTo(tx2, ty2)
            lineTo(bx2, by2)
            close()
        }

        // Sport needle gradient: chrome white body with illuminated crimson tip
        drawPath(
            path = needlePath,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to (if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)),
                    0.68f to Color.White,
                    0.69f to (if (progress >= redlineStartPercent) Color(0xFFDC2626) else Color(0xFFFF3B30)),
                    1.0f to (if (progress >= redlineStartPercent) Color(0xFFEF4444) else Color(0xFFFF5252))
                ),
                start = Offset(tailX, tailY),
                end = Offset(tipX, tipY)
            )
        )

        // Faceted 3D center spine crease
        drawLine(
            color = Color.White.copy(alpha = 0.75f),
            start = Offset(tailX, tailY),
            end = Offset(tipX, tipY),
            strokeWidth = 0.8.dp.toPx()
        )

        // Glowing white tip pin
        drawCircle(
            color = Color.White,
            radius = 1.8.dp.toPx(),
            center = Offset(tipX, tipY)
        )

        // 7. CENTER PIVOT HUB CAP
        // Hub shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f),
            radius = 16.dp.toPx(),
            center = Offset(cx, cy + 1.5.dp.toPx())
        )

        // Outer metallic ring
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (isDark) listOf(Color(0xFF474F66), Color(0xFF242838), Color(0xFF151722))
                else listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF64748B)),
                center = Offset(cx - 2.dp.toPx(), cy - 2.dp.toPx()),
                radius = 15.dp.toPx()
            ),
            radius = 15.dp.toPx(),
            center = Offset(cx, cy)
        )
        drawCircle(
            color = if (isDark) Color(0xFF383E52) else Color(0xFF64748B),
            radius = 15.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )

        // Inner dark core cap
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (isDark) listOf(Color(0xFF1E212D), Color(0xFF0C0D12))
                else listOf(Color(0xFF334155), Color(0xFF1E293B)),
                center = Offset(cx, cy),
                radius = 11.dp.toPx()
            ),
            radius = 11.dp.toPx(),
            center = Offset(cx, cy)
        )

        // Central jewel screw & specular pin
        drawCircle(
            color = Color(0xFF71717A),
            radius = 3.dp.toPx(),
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color.White,
            radius = 1.2.dp.toPx(),
            center = Offset(cx - 0.8.dp.toPx(), cy - 0.8.dp.toPx())
        )
    }
}

/**
 * Technical Specification Key-Value Row with 2 Columns.
 */
@Composable
private fun CockpitTechSpecRow(
    label1: String,
    value1: String,
    label2: String,
    value2: String,
    tokens: BingoColors,
    isGreen2: Boolean = false,
    valueFontSize: Float = 11.5f
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label1,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value1,
                fontSize = valueFontSize.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = label2,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value2,
                fontSize = valueFontSize.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGreen2) Color(0xFF10B981) else tokens.textPrimary
            )
        }
    }
}

/**
 * Single-Line Timeout Row for Cockpit.
 */
@Composable
private fun CockpitTimeoutInlineRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    tokens: BingoColors,
    pillHeight: Float = 32f,
    pillFontSize: Float = 9f
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.textSecondary,
            modifier = Modifier.width(52.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, title) ->
                val isSelected = value == selected
                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (tokens.isDark) Color.White else tokens.accentBrand
                    } else {
                        tokens.badgeSurface
                    },
                    animationSpec = tween(150),
                    label = "chipBg"
                )
                val chipText by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (tokens.isDark) Color.Black else Color.White
                    } else {
                        tokens.textMuted
                    },
                    animationSpec = tween(150),
                    label = "chipText"
                )

                Surface(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(pillHeight.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = chipBg,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) Color.Transparent else tokens.badgeOutline
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = pillFontSize.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipText
                        )
                    }
                }
            }
        }
    }
}

/**
 * Biometric laser scanline animation for confirmation modal with holographic ripple aura.
 */
@Composable
private fun BiometricLaserScanner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val scanLineY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLine"
    )
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerRipple"
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerRippleAlpha"
    )

    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        // Holographic biometric aura ring
        Box(
            modifier = Modifier
                .size(24.dp)
                .scale(rippleScale)
                .border(1.dp, Color(0xFF10B981).copy(alpha = rippleAlpha), CircleShape)
        )
        Text(text = "👆", fontSize = 18.sp)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height * scanLineY
            // Glowing halo around laser
            drawLine(
                color = Color(0xFF10B981).copy(alpha = 0.3f),
                start = Offset(2.dp.toPx(), y),
                end = Offset(size.width - 2.dp.toPx(), y),
                strokeWidth = 3.2.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Core laser beam
            drawLine(
                color = Color(0xFF10B981).copy(alpha = 0.95f),
                start = Offset(2.dp.toPx(), y),
                end = Offset(size.width - 2.dp.toPx(), y),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Tactile bounce click modifier for interactive elements.
 */
@Composable
internal fun Modifier.bounceClick(scaleDown: Float = 0.95f, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bounceScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}
