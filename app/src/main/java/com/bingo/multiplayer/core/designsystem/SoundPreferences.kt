package com.bingo.multiplayer.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.bingo.multiplayer.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Enterprise preferences manager for Sound and Vibration settings.
 * Manages:
 * 1. Sound Master (ON/OFF) - Controls in-game number pick sounds and app sound effects.
 * 2. Vibration Master (ON/OFF) - Controls tactile haptic feedback during gameplay.
 * 3. Number Pick Sound Preset - 10 presets with "classic_pop" as default.
 *
 * Persisted in SharedPreferences ("bingo_sound_prefs") and synchronized bidirectionally
 * with Cloud Firestore and account backup.
 */
object SoundPreferences {
    private const val TAG = "SoundPreferences"
    private const val PREFS_NAME = "bingo_sound_prefs"

    const val KEY_SOUND_ENABLED = "pref_sound_enabled"
    const val KEY_HAPTICS_ENABLED = "pref_haptics_enabled"
    const val KEY_PICK_SOUND_PRESET = "pref_pick_sound_preset"

    const val DEFAULT_PRESET_ID = "classic_pop"

    val soundEnabled: MutableState<Boolean> = mutableStateOf(true)
    val hapticsEnabled: MutableState<Boolean> = mutableStateOf(true)
    val pickSoundPresetId: MutableState<String> = mutableStateOf(DEFAULT_PRESET_ID)

    @Volatile
    var isHapticsActive: Boolean = true
        private set

    @Volatile
    private var isInitialized = false

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val authPrefs = context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)

        soundEnabled.value = if (prefs.contains(KEY_SOUND_ENABLED)) {
            prefs.getBoolean(KEY_SOUND_ENABLED, true)
        } else {
            authPrefs.getBoolean("settings_sound", true)
        }

        val hapticsVal = if (prefs.contains(KEY_HAPTICS_ENABLED)) {
            prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
        } else {
            authPrefs.getBoolean("settings_haptics", true)
        }
        hapticsEnabled.value = hapticsVal
        isHapticsActive = hapticsVal

        pickSoundPresetId.value = if (prefs.contains(KEY_PICK_SOUND_PRESET)) {
            prefs.getString(KEY_PICK_SOUND_PRESET, DEFAULT_PRESET_ID) ?: DEFAULT_PRESET_ID
        } else {
            authPrefs.getString("settings_pick_sound_preset", DEFAULT_PRESET_ID) ?: DEFAULT_PRESET_ID
        }

        isInitialized = true
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHapticsAllowed(): Boolean = isHapticsActive && hapticsEnabled.value

    fun setSoundEnabled(context: Context, enabled: Boolean, syncToCloud: Boolean = true) {
        if (soundEnabled.value == enabled) return
        soundEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
        context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("settings_sound", enabled).apply()
        if (syncToCloud) triggerCloudSync(context)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean, syncToCloud: Boolean = true) {
        hapticsEnabled.value = enabled
        isHapticsActive = enabled
        getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
        context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("settings_haptics", enabled).apply()
        if (syncToCloud) triggerCloudSync(context)
    }

    fun setPickSoundPresetId(context: Context, presetId: String, syncToCloud: Boolean = true) {
        if (pickSoundPresetId.value == presetId) return
        pickSoundPresetId.value = presetId
        getPrefs(context).edit().putString(KEY_PICK_SOUND_PRESET, presetId).apply()
        context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("settings_pick_sound_preset", presetId).apply()
        if (syncToCloud) triggerCloudSync(context)
    }

    fun applySettings(
        sound: Boolean,
        haptics: Boolean,
        presetId: String,
        context: Context,
        syncToCloud: Boolean = false
    ) {
        soundEnabled.value = sound
        hapticsEnabled.value = haptics
        isHapticsActive = haptics
        pickSoundPresetId.value = presetId

        getPrefs(context).edit()
            .putBoolean(KEY_SOUND_ENABLED, sound)
            .putBoolean(KEY_HAPTICS_ENABLED, haptics)
            .putString(KEY_PICK_SOUND_PRESET, presetId)
            .apply()

        context.getSharedPreferences("bingo_auth_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("settings_sound", sound)
            .putBoolean("settings_haptics", haptics)
            .putString("settings_pick_sound_preset", presetId)
            .apply()

        if (syncToCloud) triggerCloudSync(context)
    }

    fun triggerCloudSync(context: Context) {
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            try {
                val authRepo = AuthRepository.activeInstance ?: return@launch
                val user = authRepo.getPersistedUserSync() ?: return@launch
                if (user.uid.isBlank()) return@launch

                val settings = authRepo.getSettings()
                com.bingo.multiplayer.domain.network.FirestoreSyncManager.getInstance(context)
                    .syncUserProfile(user, settings)
                authRepo.backupUserDataToCloud()
                Log.d(TAG, "Sound & Vibration preferences synced to cloud successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync sound preferences to cloud: ${e.message}")
            }
        }
    }
}
