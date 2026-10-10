package com.bingo.multiplayer.domain.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
enum class AuthProvider {
    PLAY_GAMES,
    GOOGLE,
    GUEST
}

@Keep
@Serializable
data class UserProfile(
    val uid: String,
    val username: String = "",
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val avatarBase64: String? = null,
    val provider: AuthProvider = AuthProvider.PLAY_GAMES,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val level: Int = 1,
    val xp: Int = 0,
    val appVersion: String = com.bingo.multiplayer.domain.network.NetworkConfig.APP_VERSION_NAME,
    val appVersionCode: Int = com.bingo.multiplayer.domain.network.NetworkConfig.APP_VERSION_CODE
) {
    val activeStreak: Int
        get() = currentStreak
    val playerId: String
        get() = username.ifEmpty { displayName.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { uid.take(8) } }
    val winRatePercentage: Int
        get() = if (gamesPlayed > 0) ((gamesWon.toFloat() / gamesPlayed) * 100).toInt() else 0

    val providerBadgeName: String
        get() = when (provider) {
            AuthProvider.PLAY_GAMES -> "Play Games"
            AuthProvider.GOOGLE -> "Google Account"
            AuthProvider.GUEST -> "Guest"
        }

    val rankTitle: String
        get() = when {
            level >= 8 -> "Grandmaster"
            level >= 5 -> "Gold Master"
            level >= 3 -> "Silver Competitor"
            else -> "Bronze Player"
        }
}

@Keep
@Serializable
data class UserSettings(
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val pickSoundPresetId: String = "classic_pop",
    val preferredBoardSize: Int = 5,
    val darkTheme: Boolean = false,
    val accentColorId: String = "matte_slate",
    val customAccentHex: String = "#64748B",
    val customMyPickHex: String? = null,
    val customOpponentPickHex: String? = null,
    val customRecentPickHex: String? = null,
    val customCompletedLineHex: String? = null,
    val cellBorderEnabled: Boolean = false,
    val cellBorderColorHex: String = "#FFFFFF",
    val isLiquidMetalTheme: Boolean = false,
    val systemNotificationsEnabled: Boolean = true,
    val playerOnlineNotificationsEnabled: Boolean = true,
    val playerInvitesNotificationsEnabled: Boolean = true,
    val inAppNotificationsEnabled: Boolean = true,
    val inAppPlayerOnlineEnabled: Boolean = true,
    val inAppPlayerInvitesEnabled: Boolean = true
)

@Keep
@Serializable
data class MatchRecord(
    val id: String = "",
    val mode: String = "Online",
    val opponentName: String = "Opponent",
    val didWin: Boolean = false,
    val boardSize: Int = 5,
    val timestamp: Long = System.currentTimeMillis(),
    val matchTitle: String = "",
    val isDraw: Boolean = false
)

@Keep
@Serializable
data class CloudUserDataBackup(
    val profile: UserProfile,
    val settings: UserSettings = UserSettings(),
    val matchHistory: List<MatchRecord> = emptyList(),
    val lastBackupTimestamp: Long = System.currentTimeMillis()
)

@Keep
sealed interface AuthState {
    @Keep
    data object Loading : AuthState
    @Keep
    data object Unauthenticated : AuthState
    @Keep
    data class Authenticated(val user: UserProfile) : AuthState
}

