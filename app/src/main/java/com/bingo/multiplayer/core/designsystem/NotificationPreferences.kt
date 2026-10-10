package com.bingo.multiplayer.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.bingo.multiplayer.domain.repository.AuthRepository

/**
 * Enterprise-grade preferences manager for application notification settings.
 * Manages separate control hierarchies for:
 * 1. System Push / Device Notifications (OS Status Bar & Lockscreen)
 *    - All Notifications (Master ON/OFF)
 *    - Players Online Notifications (ON/OFF)
 *    - Player Invites Notifications (ON/OFF)
 * 2. In-App Notifications (Popups & Banners while inside the app)
 *    - All In-App Notifications (Master ON/OFF)
 *    - Players Online In-App Alerts (ON/OFF)
 *    - Player Invites In-App Alerts (ON/OFF)
 *
 * Persisted in SharedPreferences and synchronized bidirectionally with Cloud Firestore.
 */
object NotificationPreferences {
    private const val PREFS_NAME = "bingo_notification_prefs"

    // System Push Notification Keys
    private const val KEY_SYSTEM_NOTIFS = "pref_system_notifs_enabled"
    private const val KEY_PLAYER_ONLINE_NOTIFS = "pref_player_online_notifs_enabled"
    private const val KEY_PLAYER_INVITES_NOTIFS = "pref_player_invites_notifs_enabled"

    // In-App Notification Keys
    private const val KEY_IN_APP_NOTIFS = "pref_in_app_notifs_enabled"
    private const val KEY_IN_APP_ONLINE = "pref_in_app_player_online_enabled"
    private const val KEY_IN_APP_INVITES = "pref_in_app_player_invites_enabled"

    // ── Reactive Compose State Variables ──
    val systemNotificationsEnabled: MutableState<Boolean> = mutableStateOf(true)
    val playerOnlineNotificationsEnabled: MutableState<Boolean> = mutableStateOf(true)
    val playerInvitesNotificationsEnabled: MutableState<Boolean> = mutableStateOf(true)

    val inAppNotificationsEnabled: MutableState<Boolean> = mutableStateOf(true)
    val inAppPlayerOnlineEnabled: MutableState<Boolean> = mutableStateOf(true)
    val inAppPlayerInvitesEnabled: MutableState<Boolean> = mutableStateOf(true)

    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        systemNotificationsEnabled.value = prefs.getBoolean(KEY_SYSTEM_NOTIFS, true)
        playerOnlineNotificationsEnabled.value = prefs.getBoolean(KEY_PLAYER_ONLINE_NOTIFS, true)
        playerInvitesNotificationsEnabled.value = prefs.getBoolean(KEY_PLAYER_INVITES_NOTIFS, true)

        inAppNotificationsEnabled.value = prefs.getBoolean(KEY_IN_APP_NOTIFS, true)
        inAppPlayerOnlineEnabled.value = prefs.getBoolean(KEY_IN_APP_ONLINE, true)
        inAppPlayerInvitesEnabled.value = prefs.getBoolean(KEY_IN_APP_INVITES, true)

        isInitialized = true
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Mutators with automatic persistence and Cloud sync ──

    fun setSystemNotificationsEnabled(context: Context, enabled: Boolean) {
        systemNotificationsEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_SYSTEM_NOTIFS, enabled).apply()
        triggerCloudSync(context)
    }

    fun setPlayerOnlineNotificationsEnabled(context: Context, enabled: Boolean) {
        playerOnlineNotificationsEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_PLAYER_ONLINE_NOTIFS, enabled).apply()
        triggerCloudSync(context)
    }

    fun setPlayerInvitesNotificationsEnabled(context: Context, enabled: Boolean) {
        playerInvitesNotificationsEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_PLAYER_INVITES_NOTIFS, enabled).apply()
        triggerCloudSync(context)
    }

    fun setInAppNotificationsEnabled(context: Context, enabled: Boolean) {
        inAppNotificationsEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_IN_APP_NOTIFS, enabled).apply()
        triggerCloudSync(context)
    }

    fun setInAppPlayerOnlineEnabled(context: Context, enabled: Boolean) {
        inAppPlayerOnlineEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_IN_APP_ONLINE, enabled).apply()
        triggerCloudSync(context)
    }

    fun setInAppPlayerInvitesEnabled(context: Context, enabled: Boolean) {
        inAppPlayerInvitesEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_IN_APP_INVITES, enabled).apply()
        triggerCloudSync(context)
    }

    // ── Evaluators for dispatch logic ──

    /**
     * Evaluates whether an OS status bar game invite notification can be shown.
     */
    fun canShowSystemInviteNotification(context: Context): Boolean {
        init(context)
        return systemNotificationsEnabled.value && playerInvitesNotificationsEnabled.value
    }

    /**
     * Evaluates whether an OS status bar friend online notification can be shown.
     */
    fun canShowSystemPlayerOnlineNotification(context: Context): Boolean {
        init(context)
        return systemNotificationsEnabled.value && playerOnlineNotificationsEnabled.value
    }

    /**
     * Evaluates whether an in-app invite modal dialog can be presented.
     */
    fun canShowInAppInvite(context: Context): Boolean {
        init(context)
        return inAppNotificationsEnabled.value && inAppPlayerInvitesEnabled.value
    }

    /**
     * Evaluates whether an in-app banner/toast alert for player online can be shown.
     */
    fun canShowInAppPlayerOnline(context: Context): Boolean {
        init(context)
        return inAppNotificationsEnabled.value && inAppPlayerOnlineEnabled.value
    }

    private fun triggerCloudSync(context: Context) {
        AuthRepository.activeInstance?.let { repo ->
            val user = repo.getPersistedUserSync()
            if (user != null && user.uid.isNotBlank()) {
                val currentSettings = repo.getSettings()
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
                    .syncUserProfile(user, currentSettings)
            }
        }
    }
}
