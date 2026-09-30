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
 * Defaults strictly to clean Light mode with limited curated colors.
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

    // Cells - Player Choice (Soft Lavender)
    val cellPlayerPickBg: Color,
    val cellPlayerPickText: Color,
    val cellPlayerPickBorder: Color,
    val cellPlayerPickBevel: Color,

    // Cells - Opponent Choice (Soft Amber)
    val cellOpponentPickBg: Color,
    val cellOpponentPickText: Color,
    val cellOpponentPickBorder: Color,
    val cellOpponentPickBevel: Color,

    // Cells - Recent Picked
    val recentPickBg: Color,
    val recentPickBorder: Color,
    val recentPickText: Color,
    val recentPickGlow: Color,

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

// Minimal Clean Light Theme (DEFAULT)
val CleanLightColors = BingoColors(
    isDark = false,
    background = Color(0xFFFAFAFC),
    backgroundSecondary = Color(0xFFF4F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceBorder = Color(0xFFF1F5F9),
    surfaceCardShadow = Color(0x00000000),

    // Unpicked Cell: Clean flat white card, hairline subtle border, dark slate text
    cellNeutralBg = Color(0xFFFFFFFF),
    cellNeutralText = Color(0xFF1E293B),
    cellNeutralBorder = Color(0xFFF1F5F9),
    cellNeutralBevel = Color(0xFFFFFFFF),
    cellNeutralHighlight = Color(0xFFFFFFFF),

    // Player Choice: Soft lavender with clear presence, NO BORDER
    cellPlayerPickBg = Color(0xFFEADBFF),
    cellPlayerPickText = Color(0xFF6B21A8),
    cellPlayerPickBorder = Color(0xFF7C3AED), // Non-transparent accent fallback for buttons/badges
    cellPlayerPickBevel = Color(0xFFEADBFF),

    // Opponent Choice: Soft sky blue with clear presence, NO BORDER
    cellOpponentPickBg = Color(0xFFD3EEFF),
    cellOpponentPickText = Color(0xFF0369A1),
    cellOpponentPickBorder = Color(0xFF0284C7), // Non-transparent accent fallback
    cellOpponentPickBevel = Color(0xFFD3EEFF),

    // Recent Picked: Soft warm orange 🧡 with clear presence, NO BORDER
    recentPickBg = Color(0xFFFFE0B8),
    recentPickBorder = Color(0xFFEA580C), // Non-transparent accent fallback
    recentPickText = Color(0xFFC2410C),
    recentPickGlow = Color(0xFFFED7AA),

    // Completed B-I-N-G-O Letters: Clean amber gold
    completedLetterGradientStart = Color(0xFFFBBF24),
    completedLetterGradientEnd = Color(0xFFF59E0B),
    completedLetterBorder = Color(0xFFF59E0B),
    completedLetterText = Color(0xFFFFFFFF),
    completedLetterGlow = Color(0x26F59E0B),

    // Accents
    turnBannerPlayer = Color(0xFF7C3AED),
    turnBannerOpponent = Color(0xFF0284C7),
    accentBrand = Color(0xFF7C3AED),
    accentPlayGames = Color(0xFF16A34A),
    accentGoogle = Color(0xFF2563EB),

    // Subtle flat depth
    faux3dBevelBottom = Color(0xFFF1F5F9),
    faux3dInnerHighlight = Color(0xFFFFFFFF)
)

// Minimal Dark Scheme (Fallback if explicitly enabled)
val MinimalDarkColors = BingoColors(
    isDark = true,
    background = Color(0xFF121216),
    backgroundSecondary = Color(0xFF181820),
    surface = Color(0xFF1E1E26),
    surfaceBorder = Color(0xFF2D2D3A),
    surfaceCardShadow = Color(0x40000000),

    cellNeutralBg = Color(0xFF1E1E26),
    cellNeutralText = Color(0xFFE2E8F0),
    cellNeutralBorder = Color(0xFF2D2D3A),
    cellNeutralBevel = Color(0xFF181820),
    cellNeutralHighlight = Color(0x1AFFFFFF),

    cellPlayerPickBg = Color(0x33A855F7),
    cellPlayerPickText = Color(0xFFE9D5FF),
    cellPlayerPickBorder = Color(0xFFA855F7),
    cellPlayerPickBevel = Color(0xFF6B21A8),

    cellOpponentPickBg = Color(0x330284C7),
    cellOpponentPickText = Color(0xFFBAE6FD),
    cellOpponentPickBorder = Color(0xFF0284C7),
    cellOpponentPickBevel = Color(0xFF0369A1),

    recentPickBg = Color(0x33EA580C),
    recentPickBorder = Color(0xFFEA580C),
    recentPickText = Color(0xFFFFEDD5),
    recentPickGlow = Color(0x33EA580C),

    completedLetterGradientStart = Color(0xFFFBBF24),
    completedLetterGradientEnd = Color(0xFFF59E0B),
    completedLetterBorder = Color(0xFFFBBF24),
    completedLetterText = Color(0xFF181820),
    completedLetterGlow = Color(0x40F59E0B),

    turnBannerPlayer = Color(0xFF8B5CF6),
    turnBannerOpponent = Color(0xFF0284C7),
    accentBrand = Color(0xFF8B5CF6),
    accentPlayGames = Color(0xFF22C55E),
    accentGoogle = Color(0xFF3B82F6),

    faux3dBevelBottom = Color(0xFF14141A),
    faux3dInnerHighlight = Color(0x1AFFFFFF)
)

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

// Default is CleanLightColors
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

private val MaterialLightScheme = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3E8FF),
    onPrimaryContainer = Color(0xFF6B21A8),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1E293B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E8F0)
)

private val MaterialDarkScheme = darkColorScheme(
    primary = Color(0xFFA855F7),
    onPrimary = Color(0xFF181820),
    primaryContainer = Color(0xFF2D2D3A),
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF181820),
    background = Color(0xFF121216),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E1E26),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF2D2D3A),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2D2D3A)
)

/**
 * Main app theme wrapper. Defaults strictly to clean Light mode.
 */
@Composable
fun BingoAppTheme(
    darkTheme: Boolean = false, // Default is Light mode only as instructed
    content: @Composable () -> Unit
) {
    val bingoColors = if (darkTheme) MinimalDarkColors else CleanLightColors
    val materialColorScheme = if (darkTheme) MaterialDarkScheme else MaterialLightScheme

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
