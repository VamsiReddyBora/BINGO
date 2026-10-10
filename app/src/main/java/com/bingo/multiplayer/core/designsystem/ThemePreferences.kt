package com.bingo.multiplayer.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * Curated matte accent palette for Bingo.
 * Designed with elegant, muted shades and dark matte tones — zero neon or eye-straining glare.
 */
data class AppAccentPalette(
    val id: String,
    val name: String,
    val previewColor: Color,
    // Primary brand accents
    val primaryLight: Color,
    val primaryDark: Color,
    // Board pick colors (Board theme is decoupled from App theme)
    val cellPlayerPickBgLight: Color,
    val cellPlayerPickTextLight: Color,
    val cellPlayerPickBgDark: Color,
    val cellPlayerPickTextDark: Color,
    val cellPlayerPickBorderDark: Color
)

data class BorderColorPreset(
    val hex: String,
    val name: String
)

object ThemePreferences {
    private const val PREFS_NAME = "bingo_theme_prefs"
    private const val KEY_IS_DARK = "is_dark_theme"
    private const val KEY_ACCENT_ID = "accent_color_id"
    private const val KEY_CUSTOM_COLOR = "custom_accent_hex"
    private const val KEY_LIQUID_METAL_ENABLED = "is_liquid_metal_theme_enabled"

    // 12 curated, elegant matte palettes
    val PALETTES: List<AppAccentPalette> = listOf(
        AppAccentPalette(
            id = "matte_slate",
            name = "Matte Slate",
            previewColor = Color(0xFF64748B),
            primaryLight = Color(0xFF475569),
            primaryDark = Color(0xFF94A3B8),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "royal_violet",
            name = "Royal Violet",
            previewColor = Color(0xFF7C3AED),
            primaryLight = Color(0xFF7C3AED),
            primaryDark = Color(0xFF8B5CF6),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "deep_indigo",
            name = "Deep Indigo",
            previewColor = Color(0xFF4F46E5),
            primaryLight = Color(0xFF4F46E5),
            primaryDark = Color(0xFF6366F1),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "ocean_blue",
            name = "Ocean Blue",
            previewColor = Color(0xFF2563EB),
            primaryLight = Color(0xFF2563EB),
            primaryDark = Color(0xFF3B82F6),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "midnight_cyan",
            name = "Midnight Cyan",
            previewColor = Color(0xFF0284C7),
            primaryLight = Color(0xFF0284C7),
            primaryDark = Color(0xFF38BDF8),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "forest_teal",
            name = "Forest Teal",
            previewColor = Color(0xFF0D9488),
            primaryLight = Color(0xFF0D9488),
            primaryDark = Color(0xFF14B8A6),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "emerald_sage",
            name = "Emerald Sage",
            previewColor = Color(0xFF16A34A),
            primaryLight = Color(0xFF16A34A),
            primaryDark = Color(0xFF22C55E),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "olive_moss",
            name = "Olive Moss",
            previewColor = Color(0xFF65A30D),
            primaryLight = Color(0xFF65A30D),
            primaryDark = Color(0xFF84CC16),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "burnt_amber",
            name = "Burnt Amber",
            previewColor = Color(0xFFD97706),
            primaryLight = Color(0xFFD97706),
            primaryDark = Color(0xFFF59E0B),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "warm_copper",
            name = "Warm Copper",
            previewColor = Color(0xFFEA580C),
            primaryLight = Color(0xFFEA580C),
            primaryDark = Color(0xFFF97316),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "crimson_ruby",
            name = "Crimson Ruby",
            previewColor = Color(0xFFDC2626),
            primaryLight = Color(0xFFDC2626),
            primaryDark = Color(0xFFEF4444),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        ),
        AppAccentPalette(
            id = "rose_wine",
            name = "Rose Wine",
            previewColor = Color(0xFFE11D48),
            primaryLight = Color(0xFFE11D48),
            primaryDark = Color(0xFFF43F5E),
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        )
    )

    private const val KEY_CUSTOM_MY_PICK = "custom_my_pick_hex"
    private const val KEY_CUSTOM_OPPONENT_PICK = "custom_opponent_pick_hex"
    private const val KEY_CUSTOM_RECENT_PICK = "custom_recent_pick_hex"
    private const val KEY_CUSTOM_COMPLETED_LINE = "custom_completed_line_hex"
    private const val KEY_CELL_BORDER_ENABLED = "cell_border_enabled"
    private const val KEY_CELL_BORDER_COLOR = "cell_border_color_hex"
    private const val KEY_USER_CUSTOMIZED_ACCENT = "user_customized_accent"

    val BORDER_COLOR_PRESETS = listOf(
        BorderColorPreset("#FFFFFF", "Pure White"),
        BorderColorPreset("#38BDF8", "Ice Blue"),
        BorderColorPreset("#F97316", "Vibrant Orange"),
        BorderColorPreset("#F59E0B", "Amber Gold"),
        BorderColorPreset("#10B981", "Emerald Green"),
        BorderColorPreset("#A855F7", "Bright Purple"),
        BorderColorPreset("#EF4444", "Crimson Red"),
        BorderColorPreset("#94A3B8", "Slate Grey"),
        BorderColorPreset("#383838", "Charcoal")
    )

    const val DEFAULT_MY_PICK_HEX = "#7E22CE"
    const val DEFAULT_OPPONENT_PICK_HEX = "#C2410C"
    const val DEFAULT_RECENT_PICK_LIGHT_HEX = "#D9B13D"
    const val DEFAULT_RECENT_PICK_DARK_HEX = "#F59E0B"
    const val DEFAULT_COMPLETED_LINE_HEX = "#64748B"

    fun getDefaultRecentPickHex(isDark: Boolean): String =
        if (isDark) DEFAULT_RECENT_PICK_DARK_HEX else DEFAULT_RECENT_PICK_LIGHT_HEX

    val isDarkTheme: MutableState<Boolean> = mutableStateOf(false)
    val accentColorId: MutableState<String> = mutableStateOf("matte_slate")
    val customColorHex: MutableState<String> = mutableStateOf("#64748B")

    // Board cell customized colors
    val customMyPickHex: MutableState<String?> = mutableStateOf(null)
    val customOpponentPickHex: MutableState<String?> = mutableStateOf(null)
    val customRecentPickHex: MutableState<String?> = mutableStateOf(null)
    val customCompletedLineHex: MutableState<String?> = mutableStateOf(null)
    val cellBorderEnabled: MutableState<Boolean> = mutableStateOf(false)
    val cellBorderColorHex: MutableState<String> = mutableStateOf("#FFFFFF")

    // Liquid Metal Edition (Exclusive Theme)
    val isLiquidMetalTheme: MutableState<Boolean> = mutableStateOf(false)

    fun initWithPrefs(prefs: SharedPreferences, authPrefs: SharedPreferences? = null) {
        val savedDark = if (prefs.contains(KEY_IS_DARK)) {
            prefs.getBoolean(KEY_IS_DARK, false)
        } else {
            authPrefs?.getBoolean("settings_dark_theme", false) ?: false
        }
        isDarkTheme.value = savedDark
        applyNightModeSafely(savedDark)
        val defaultAccent = "matte_slate"
        val savedAccent = prefs.getString(KEY_ACCENT_ID, defaultAccent) ?: defaultAccent
        // If saved accent was the old default "royal_violet" and user hadn't explicitly chosen it, default to "matte_slate"
        accentColorId.value = if (savedAccent == "royal_violet" && !prefs.getBoolean(KEY_USER_CUSTOMIZED_ACCENT, false)) {
            defaultAccent
        } else {
            savedAccent
        }
        customColorHex.value = prefs.getString(KEY_CUSTOM_COLOR, "#64748B") ?: "#64748B"

        customMyPickHex.value = prefs.getString(KEY_CUSTOM_MY_PICK, null)
        customOpponentPickHex.value = prefs.getString(KEY_CUSTOM_OPPONENT_PICK, null)
        customRecentPickHex.value = prefs.getString(KEY_CUSTOM_RECENT_PICK, null)
        customCompletedLineHex.value = prefs.getString(KEY_CUSTOM_COMPLETED_LINE, null)
        cellBorderEnabled.value = prefs.getBoolean(KEY_CELL_BORDER_ENABLED, false)
        cellBorderColorHex.value = prefs.getString(KEY_CELL_BORDER_COLOR, "#FFFFFF") ?: "#FFFFFF"
        isLiquidMetalTheme.value = prefs.getBoolean(KEY_LIQUID_METAL_ENABLED, false)
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val authPrefs = context.getSharedPreferences("bingo_user_profile", Context.MODE_PRIVATE)
        initWithPrefs(prefs, authPrefs)
    }

    fun setLiquidMetalTheme(context: Context, enabled: Boolean) {
        isLiquidMetalTheme.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LIQUID_METAL_ENABLED, enabled)
            .apply()
    }

    fun setDarkTheme(prefs: SharedPreferences, authPrefs: SharedPreferences?, isDark: Boolean) {
        isDarkTheme.value = isDark
        // Automatically sync recent pick preset when switching themes if not manually overridden
        val isRecentPickDefault = customRecentPickHex.value == null ||
                customRecentPickHex.value.equals(DEFAULT_RECENT_PICK_LIGHT_HEX, ignoreCase = true) ||
                customRecentPickHex.value.equals("#D9B43D", ignoreCase = true) ||
                customRecentPickHex.value.equals(DEFAULT_RECENT_PICK_DARK_HEX, ignoreCase = true) ||
                customRecentPickHex.value.equals("#FFFFFF", ignoreCase = true)
        if (isRecentPickDefault) {
            customRecentPickHex.value = null
            prefs.edit().remove(KEY_CUSTOM_RECENT_PICK).apply()
        }
        prefs.edit().putBoolean(KEY_IS_DARK, isDark).apply()
        authPrefs?.edit()?.putBoolean("settings_dark_theme", isDark)?.apply()
        applyNightModeSafely(isDark)
    }

    private fun applyNightModeSafely(isDark: Boolean) {
        try {
            val targetMode = if (isDark) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            } else {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            }
            if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != targetMode) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        } catch (_: Throwable) {
            // Graceful fallback for non-Android / testing environments
        }
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val authPrefs = context.getSharedPreferences("bingo_user_profile", Context.MODE_PRIVATE)
        setDarkTheme(prefs, authPrefs, isDark)
        triggerCloudSync(context)
    }

    fun setAccentColor(context: Context, id: String) {
        accentColorId.value = id
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT_ID, id)
            .putBoolean(KEY_USER_CUSTOMIZED_ACCENT, true)
            .apply()
        triggerCloudSync(context)
    }

    fun setCustomColor(context: Context, hex: String) {
        val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
        customColorHex.value = cleanHex
        accentColorId.value = "custom"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT_ID, "custom")
            .putString(KEY_CUSTOM_COLOR, cleanHex)
            .apply()
        triggerCloudSync(context)
    }

    fun setMyPickColor(context: Context, hex: String?) {
        val cleanHex = hex?.let { if (it.startsWith("#")) it else "#$it" }
        customMyPickHex.value = cleanHex
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleanHex == null) editor.remove(KEY_CUSTOM_MY_PICK) else editor.putString(KEY_CUSTOM_MY_PICK, cleanHex)
        editor.apply()
        triggerCloudSync(context)
    }

    fun setOpponentPickColor(context: Context, hex: String?) {
        val cleanHex = hex?.let { if (it.startsWith("#")) it else "#$it" }
        customOpponentPickHex.value = cleanHex
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleanHex == null) editor.remove(KEY_CUSTOM_OPPONENT_PICK) else editor.putString(KEY_CUSTOM_OPPONENT_PICK, cleanHex)
        editor.apply()
        triggerCloudSync(context)
    }

    fun setRecentPickColor(context: Context, hex: String?) {
        val cleanHex = hex?.let { if (it.startsWith("#")) it else "#$it" }
        customRecentPickHex.value = cleanHex
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleanHex == null) editor.remove(KEY_CUSTOM_RECENT_PICK) else editor.putString(KEY_CUSTOM_RECENT_PICK, cleanHex)
        editor.apply()
        triggerCloudSync(context)
    }

    fun setCompletedLineColor(context: Context, hex: String?) {
        val cleanHex = hex?.let { if (it.startsWith("#")) it else "#$it" }
        customCompletedLineHex.value = cleanHex
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleanHex == null) editor.remove(KEY_CUSTOM_COMPLETED_LINE) else editor.putString(KEY_CUSTOM_COMPLETED_LINE, cleanHex)
        editor.apply()
        triggerCloudSync(context)
    }

    fun setCellBorderEnabled(context: Context, enabled: Boolean) {
        cellBorderEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CELL_BORDER_ENABLED, enabled)
            .apply()
        triggerCloudSync(context)
    }

    fun setCellBorderColor(context: Context, hex: String) {
        val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
        cellBorderColorHex.value = cleanHex
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CELL_BORDER_COLOR, cleanHex)
            .apply()
        triggerCloudSync(context)
    }

    fun resetBoardColors(context: Context) {
        customMyPickHex.value = null
        customOpponentPickHex.value = null
        customRecentPickHex.value = null
        customCompletedLineHex.value = null
        cellBorderEnabled.value = false
        cellBorderColorHex.value = "#FFFFFF"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CUSTOM_MY_PICK)
            .remove(KEY_CUSTOM_OPPONENT_PICK)
            .remove(KEY_CUSTOM_RECENT_PICK)
            .remove(KEY_CUSTOM_COMPLETED_LINE)
            .remove(KEY_CELL_BORDER_ENABLED)
            .remove(KEY_CELL_BORDER_COLOR)
            .apply()
        triggerCloudSync(context)
    }

    private fun triggerCloudSync(context: Context) {
        try {
            val authRepo = com.bingo.multiplayer.domain.repository.AuthRepository.activeInstance
            val user = authRepo?.getPersistedUserSync()
            if (user != null && user.uid.isNotBlank()) {
                val settings = authRepo.getSettings()
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
                    .syncUserProfile(user, settings)
            }
        } catch (_: Exception) {}
    }

    fun createCustomPalette(hex: String): AppAccentPalette {
        val parsed = try {
            val validHex = if (hex.startsWith("#")) hex else "#$hex"
            Color(android.graphics.Color.parseColor(validHex))
        } catch (_: Exception) {
            Color(0xFF7C3AED)
        }
        return AppAccentPalette(
            id = "custom",
            name = "Custom",
            previewColor = parsed,
            primaryLight = parsed,
            primaryDark = parsed,
            cellPlayerPickBgLight = Color(0xFFEADBFF),
            cellPlayerPickTextLight = Color(0xFF6B21A8),
            cellPlayerPickBgDark = Color(0xFF38BDF8),
            cellPlayerPickTextDark = Color(0xFF032642),
            cellPlayerPickBorderDark = Color(0xFF7DD3FC)
        )
    }

    fun getPalette(id: String): AppAccentPalette {
        if (id == "custom") {
            return createCustomPalette(customColorHex.value)
        }
        return PALETTES.find { it.id == id } ?: PALETTES.first()
    }
}
