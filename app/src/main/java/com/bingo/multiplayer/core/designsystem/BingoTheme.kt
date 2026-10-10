package com.bingo.multiplayer.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal, clean design tokens for Bingo.
 * Supports both Clean Light mode and AMOLED Pure Black mode with curated or custom palettes.
 * Board colors are strictly decoupled from the app theme accent.
 */
data class BingoColors(
    val isDark: Boolean,

    // Base canvas & surfaces
    val background: Color,
    val backgroundSecondary: Color,
    val surface: Color,
    val surfaceBorder: Color,
    val surfaceCardShadow: Color,

    // Cells - Unpicked Neutral
    val cellNeutralBg: Color,
    val cellNeutralText: Color,
    val cellNeutralBorder: Color,
    val cellNeutralBevel: Color,
    val cellNeutralHighlight: Color,

    // Cells - Player Choice
    val cellPlayerPickBg: Color,
    val cellPlayerPickText: Color,
    val cellPlayerPickBorder: Color,
    val cellPlayerPickBevel: Color,

    // Cells - Opponent Choice
    val cellOpponentPickBg: Color,
    val cellOpponentPickText: Color,
    val cellOpponentPickBorder: Color,
    val cellOpponentPickBevel: Color,

    // Cells - Recent Picked
    val recentPickBg: Color,
    val recentPickBorder: Color,
    val recentPickText: Color,
    val recentPickGlow: Color,

    // Cells - Completed Winning Line
    val completedLineBg: Color,
    val completedLineText: Color,
    val completedLineBorder: Color = Color.Transparent,

    // Completed B-I-N-G-O letters
    val completedLetterGradientStart: Color,
    val completedLetterGradientEnd: Color,
    val completedLetterBorder: Color,
    val completedLetterText: Color,
    val completedLetterGlow: Color,

    // Game accents
    val turnBannerPlayer: Color,
    val turnBannerOpponent: Color,
    val accentBrand: Color,
    val accentPlayGames: Color,
    val accentGoogle: Color,

    // Subtle tactile depth
    val faux3dBevelBottom: Color,
    val faux3dInnerHighlight: Color
) {
    val accentOpponent: Color get() = turnBannerOpponent
    val accentOrange: Color get() = Color(0xFFEA580C)
    val winningCellGlow: Color get() = completedLetterGlow
    val winningCellBorder: Color get() = completedLetterBorder
    val bingoGold: Color get() = completedLetterGradientEnd

    // High-visibility monochrome text hierarchy (never dull or hidden)
    val textPrimary: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1E293B)
    val textSecondary: Color get() = if (isDark) Color(0xFFD4D4D8) else Color(0xFF475569)
    val textMuted: Color get() = if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B)

    // Monochrome Card, Badge & Button helpers
    val badgeSurface: Color get() = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF1F5F9)
    val badgeOutline: Color get() = if (isDark) Color(0xFF2E2E32) else Color(0xFFE2E8F0)
    val badgeContent: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F172A)
    val primaryButtonBg: Color get() = if (isDark) Color(0xFFFFFFFF) else accentBrand
    val primaryButtonText: Color get() = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)

    val completedLetterBrush: Brush
        get() = Brush.verticalGradient(
            listOf(completedLetterGradientStart, completedLetterGradientEnd)
        )

    val playerPickBrush: Brush
        get() = Brush.verticalGradient(
            listOf(cellPlayerPickBg, cellPlayerPickBg)
        )

    val opponentPickBrush: Brush
        get() = Brush.verticalGradient(
            listOf(cellOpponentPickBg, cellOpponentPickBg)
        )
}

/**
 * Builds the Clean Light theme.
 * Board colors remain the classic clean light mode palette (Lavender for player, Sky for opponent, Orange for recent).
 * App accent brand is determined by the selected app theme palette.
 */
fun buildCleanLightColors(palette: AppAccentPalette): BingoColors = BingoColors(
    isDark = false,
    background = Color(0xFFFAFAFC),
    backgroundSecondary = Color(0xFFF4F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceBorder = Color(0xFFF1F5F9),
    surfaceCardShadow = Color(0x00000000),

    // Unpicked Cell: Clean flat white card, clear subtle border, dark slate text
    cellNeutralBg = Color(0xFFFFFFFF),
    cellNeutralText = Color(0xFF1E293B),
    cellNeutralBorder = Color(0xFFCBD5E1),
    cellNeutralBevel = Color(0xFFFFFFFF),
    cellNeutralHighlight = Color(0xFFFFFFFF),

    // Player Choice: #7E22CE with pure white text
    cellPlayerPickBg = Color(0xFF7E22CE),
    cellPlayerPickText = Color(0xFFFFFFFF),
    cellPlayerPickBorder = Color(0xFF9333EA),
    cellPlayerPickBevel = Color(0xFF6B21A8),

    // Opponent Choice: #C2410C with pure white text
    cellOpponentPickBg = Color(0xFFC2410C),
    cellOpponentPickText = Color(0xFFFFFFFF),
    cellOpponentPickBorder = Color(0xFFEA580C),
    cellOpponentPickBevel = Color(0xFF9A3412),

    // Recent Picked: #D9B13D with dark slate text
    recentPickBg = Color(0xFFD9B13D),
    recentPickBorder = Color(0xFFB45309),
    recentPickText = Color(0xFF0F172A),
    recentPickGlow = Color(0x30D9B13D),

    // Cells - Completed Winning Line: #64748B with pure white text
    completedLineBg = Color(0xFF64748B),
    completedLineText = Color(0xFFFFFFFF),
    completedLineBorder = Color.Transparent,

    // Completed B-I-N-G-O Letters: Clean amber gold
    completedLetterGradientStart = Color(0xFFFBBF24),
    completedLetterGradientEnd = Color(0xFFF59E0B),
    completedLetterBorder = Color(0xFFF59E0B),
    completedLetterText = Color(0xFFFFFFFF),
    completedLetterGlow = Color(0x26F59E0B),

    // App Accents
    turnBannerPlayer = palette.primaryLight,
    turnBannerOpponent = Color(0xFFC2410C),
    accentBrand = palette.primaryLight,
    accentPlayGames = Color(0xFF16A34A),
    accentGoogle = Color(0xFF2563EB),

    // Subtle flat depth
    faux3dBevelBottom = Color(0xFFF1F5F9),
    faux3dInnerHighlight = Color(0xFFFFFFFF)
)

/**
 * Builds the AMOLED Pure Black theme (#000000).
 * Default Board cells: #7E22CE for player pick, #C2410C for opponent pick, #FFFFFF for recent pick, #64748B for completed line.
 */
fun buildAmoledDarkColors(palette: AppAccentPalette): BingoColors = BingoColors(
    isDark = true,
    // 100% OLED Pure Black canvas
    background = Color(0xFF000000),
    backgroundSecondary = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
    surfaceBorder = Color(0xFF262626),
    surfaceCardShadow = Color(0x00000000),

    // Unpicked Cell: Matte carbon card with crisp pure white number
    cellNeutralBg = Color(0xFF141414),
    cellNeutralText = Color(0xFFFFFFFF),
    cellNeutralBorder = Color(0xFF282828),
    cellNeutralBevel = Color(0xFF0A0A0A),
    cellNeutralHighlight = Color(0x14FFFFFF),

    // Player Choice: #7E22CE with pure white text
    cellPlayerPickBg = Color(0xFF7E22CE),
    cellPlayerPickText = Color(0xFFFFFFFF),
    cellPlayerPickBorder = Color(0xFF9333EA),
    cellPlayerPickBevel = Color(0xFF6B21A8),

    // Opponent Choice: #C2410C with pure white text
    cellOpponentPickBg = Color(0xFFC2410C),
    cellOpponentPickText = Color(0xFFFFFFFF),
    cellOpponentPickBorder = Color(0xFFEA580C),
    cellOpponentPickBevel = Color(0xFF9A3412),

    // Recent Picked: Vibrant Amber Gold #F59E0B with white text and gold border
    recentPickBg = Color(0xFFF59E0B),
    recentPickBorder = Color(0xFFD97706),
    recentPickText = Color(0xFFFFFFFF),
    recentPickGlow = Color(0x35F59E0B),

    // Cells - Completed Winning Line: #64748B with pure white text
    completedLineBg = Color(0xFF64748B),
    completedLineText = Color(0xFFFFFFFF),
    completedLineBorder = Color.Transparent,

    // Completed B-I-N-G-O Letters: Clean gold
    completedLetterGradientStart = Color(0xFFF59E0B),
    completedLetterGradientEnd = Color(0xFFD97706),
    completedLetterBorder = Color(0xFFB45309),
    completedLetterText = Color(0xFFFFFFFF),
    completedLetterGlow = Color(0x30F59E0B),

    // App Accents: In AMOLED Dark, all cards, badges, and elements are pure white-to-black monochrome
    turnBannerPlayer = Color(0xFFFFFFFF),
    turnBannerOpponent = Color(0xFFF97316),
    accentBrand = Color(0xFFFFFFFF),
    accentPlayGames = Color(0xFFFFFFFF),
    accentGoogle = Color(0xFFFFFFFF),

    // Subtle tactile depth
    faux3dBevelBottom = Color(0xFF080808),
    faux3dInnerHighlight = Color(0x10FFFFFF)
)

val CleanLightColors: BingoColors by lazy {
    buildCleanLightColors(ThemePreferences.getPalette("matte_slate"))
}

val AmoledDarkColors: BingoColors by lazy {
    buildAmoledDarkColors(ThemePreferences.getPalette("matte_slate"))
}

data class BingoTypography(
    val logoTitle: TextStyle = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        letterSpacing = 3.sp
    ),
    val sectionHeader: TextStyle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp
    ),
    val cardTitle: TextStyle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    val cardSubtitle: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp
    ),
    val cellNumber: TextStyle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default
    ),
    val buttonText: TextStyle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp
    ),
    val badgeLetter: TextStyle = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 18.sp
    )
)

data class BingoShapes(
    val cellCornerRadius: Dp = 12.dp,
    val cellShape: RoundedCornerShape = RoundedCornerShape(12.dp),
    val cardShape: RoundedCornerShape = RoundedCornerShape(16.dp),
    val buttonShape: RoundedCornerShape = RoundedCornerShape(50.dp),
    val badgeShape: RoundedCornerShape = RoundedCornerShape(10.dp),
    val dialogShape: RoundedCornerShape = RoundedCornerShape(20.dp)
)

data class BingoElevations(
    val cellNormalElevation: Dp = 1.dp,
    val cellPressedElevation: Dp = 0.dp,
    val cardElevation: Dp = 2.dp,
    val bottomBevelHeight: Dp = 1.5.dp
)

val LocalBingoColors = staticCompositionLocalOf { CleanLightColors }
val LocalBingoTypography = staticCompositionLocalOf { BingoTypography() }
val LocalBingoShapes = staticCompositionLocalOf { BingoShapes() }
val LocalBingoElevations = staticCompositionLocalOf { BingoElevations() }

object BingoDesignSystem {
    val colors: BingoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBingoColors.current

    val typography: BingoTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalBingoTypography.current

    val shapes: BingoShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalBingoShapes.current

    val elevations: BingoElevations
        @Composable
        @ReadOnlyComposable
        get() = LocalBingoElevations.current
}

typealias BingoTheme = BingoDesignSystem

fun computeContrastText(color: Color): Color {
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (luminance > 0.55f) Color(0xFF0F172A) else Color(0xFFFFFFFF)
}

/**
 * Main app theme wrapper.
 * Dynamically switches between Clean Light mode and AMOLED Pure Black mode,
 * styled with the user's selected accent tone and customized board cell colors.
 */
@Composable
fun BingoAppTheme(
    darkTheme: Boolean = ThemePreferences.isDarkTheme.value,
    accentColorId: String = ThemePreferences.accentColorId.value,
    customMyPickHex: String? = ThemePreferences.customMyPickHex.value,
    customOpponentPickHex: String? = ThemePreferences.customOpponentPickHex.value,
    customRecentPickHex: String? = ThemePreferences.customRecentPickHex.value,
    customCompletedLineHex: String? = ThemePreferences.customCompletedLineHex.value,
    cellBorderEnabled: Boolean = ThemePreferences.cellBorderEnabled.value,
    cellBorderColorHex: String = ThemePreferences.cellBorderColorHex.value,
    content: @Composable () -> Unit
) {
    val palette = ThemePreferences.getPalette(accentColorId)
    val baseColors = if (darkTheme) {
        buildAmoledDarkColors(palette)
    } else {
        buildCleanLightColors(palette)
    }

    val customBorderColor = try {
        Color(android.graphics.Color.parseColor(cellBorderColorHex))
    } catch (_: Exception) {
        if (darkTheme) Color.White else Color(0xFF64748B)
    }

    val customPlayerPickBg = customMyPickHex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
    }
    val customOpponentPickBg = customOpponentPickHex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
    }
    val customRecentPickBg = customRecentPickHex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
    }
    val customCompletedLineBg = customCompletedLineHex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
    }

    val bingoColors = baseColors.copy(
        cellPlayerPickBg = customPlayerPickBg ?: baseColors.cellPlayerPickBg,
        cellPlayerPickText = customPlayerPickBg?.let { computeContrastText(it) } ?: baseColors.cellPlayerPickText,
        cellPlayerPickBorder = if (cellBorderEnabled) customBorderColor else (customPlayerPickBg ?: baseColors.cellPlayerPickBorder),

        cellOpponentPickBg = customOpponentPickBg ?: baseColors.cellOpponentPickBg,
        cellOpponentPickText = customOpponentPickBg?.let { computeContrastText(it) } ?: baseColors.cellOpponentPickText,
        cellOpponentPickBorder = if (cellBorderEnabled) customBorderColor else (customOpponentPickBg ?: baseColors.cellOpponentPickBorder),

        recentPickBg = customRecentPickBg ?: baseColors.recentPickBg,
        recentPickText = customRecentPickBg?.let { computeContrastText(it) } ?: baseColors.recentPickText,
        recentPickBorder = if (cellBorderEnabled) customBorderColor else (customRecentPickBg ?: baseColors.recentPickBorder),

        completedLineBg = customCompletedLineBg ?: baseColors.completedLineBg,
        completedLineText = customCompletedLineBg?.let { computeContrastText(it) } ?: baseColors.completedLineText,
        completedLineBorder = if (cellBorderEnabled) customBorderColor else Color.Transparent,

        cellNeutralBorder = if (cellBorderEnabled) customBorderColor else baseColors.cellNeutralBorder
    )

    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF262626),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFE4E4E7),
            onSecondary = Color.Black,
            background = Color(0xFF000000),
            onBackground = Color(0xFFFFFFFF),
            surface = Color(0xFF141414),
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF1E1E1E),
            onSurfaceVariant = Color(0xFFA1A1AA),
            outline = Color(0xFF262626)
        )
    } else {
        lightColorScheme(
            primary = palette.primaryLight,
            onPrimary = Color.White,
            primaryContainer = palette.primaryLight.copy(alpha = 0.12f),
            onPrimaryContainer = palette.primaryLight,
            secondary = Color(0xFF0284C7),
            onSecondary = Color.White,
            background = Color(0xFFFAFAFC),
            surface = Color.White,
            onBackground = Color(0xFF1E293B),
            onSurface = Color(0xFF1E293B),
            surfaceVariant = Color(0xFFF1F3F5),
            onSurfaceVariant = Color(0xFF64748B),
            outline = Color(0xFFCBD5E1)
        )
    }

    CompositionLocalProvider(
        LocalBingoColors provides bingoColors,
        LocalBingoTypography provides BingoTypography(),
        LocalBingoShapes provides BingoShapes(),
        LocalBingoElevations provides BingoElevations()
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content
        )
    }
}
