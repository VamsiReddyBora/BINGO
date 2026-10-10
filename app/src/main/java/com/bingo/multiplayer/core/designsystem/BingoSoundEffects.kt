package com.bingo.multiplayer.core.designsystem

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Metadata representation of a number pick sound preset.
 */
@Keep
data class PickSoundPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val tag: String,
    val isDefault: Boolean = false
)

/**
 * Ultra-low-latency game audio and haptic feedback manager.
 * Features:
 * - 10 distinct synthesized audio waveforms (Classic Pop, Arcade Blip, Crystal Chime, etc.)
 * - Zero external media files needed, ensuring 100% offline reliability and tiny APK footprint
 * - Thread-safe AudioTrack static playback with automatic resource cleanup
 * - Native haptic vibration integration
 */
object BingoSoundEffects {

    const val SAMPLE_RATE = 22050

    val PRESETS = listOf(
        PickSoundPreset(
            id = "classic_pop",
            name = "Classic Pop",
            subtitle = "Crisp, gentle modern tap pop",
            tag = "Default",
            isDefault = true
        ),
        PickSoundPreset(
            id = "arcade_blip",
            name = "Arcade Blip",
            subtitle = "Retro 8-bit game pickup jump",
            tag = "8-Bit"
        ),
        PickSoundPreset(
            id = "crystal_chime",
            name = "Crystal Chime",
            subtitle = "Sparkling bell chime harmonic",
            tag = "Bell"
        ),
        PickSoundPreset(
            id = "wooden_block",
            name = "Wooden Block",
            subtitle = "Organic acoustic wood thud",
            tag = "Organic"
        ),
        PickSoundPreset(
            id = "bubble_drop",
            name = "Bubble Drop",
            subtitle = "Playful water droplet bubble",
            tag = "Fluid"
        ),
        PickSoundPreset(
            id = "glass_tap",
            name = "Glass Tap",
            subtitle = "Subtle metallic glass ping",
            tag = "Sharp"
        ),
        PickSoundPreset(
            id = "coin_clink",
            name = "Coin Clink",
            subtitle = "Cheerful double-tone gold coin",
            tag = "Reward"
        ),
        PickSoundPreset(
            id = "digital_beep",
            name = "Digital Beep",
            subtitle = "Clean futuristic minimalist tone",
            tag = "Modern"
        ),
        PickSoundPreset(
            id = "marimba_note",
            name = "Marimba Note",
            subtitle = "Warm resonant wooden bar note",
            tag = "Acoustic"
        ),
        PickSoundPreset(
            id = "laser_zap",
            name = "Laser Zap",
            subtitle = "Energetic descending arcade zap",
            tag = "Arcade"
        )
    )

    private val pcmCache = ConcurrentHashMap<String, ShortArray>()
    private val audioScope = CoroutineScope(Dispatchers.Default)

    init {
        // Pre-warm the default sound into cache
        audioScope.launch {
            getPcmForPreset("classic_pop")
        }
    }

    fun getPreset(id: String): PickSoundPreset {
        return PRESETS.find { it.id == id } ?: PRESETS.first()
    }

    /**
     * Synthesizes 16-bit PCM waveform samples for each sound preset on demand.
     */
    private fun getPcmForPreset(presetId: String): ShortArray {
        return pcmCache.getOrPut(presetId) {
            val sr = SAMPLE_RATE
            when (presetId) {
                "arcade_blip" -> {
                    val dur = 0.09
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val f = if (t < 0.035) 520.0 else 1040.0
                        val env = max(0.0, 1.0 - (t / dur))
                        val pulse = if (sin(2 * PI * f * t) > 0) 1.0 else -1.0
                        (pulse * env * 20000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "crystal_chime" -> {
                    val dur = 0.18
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val env = exp(-t * 22.0)
                        val s = (sin(2 * PI * 1200.0 * t) * 0.7 + sin(2 * PI * 2400.0 * t) * 0.3) * env
                        (s * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "wooden_block" -> {
                    val dur = 0.06
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val env = exp(-t * 60.0)
                        val s = (sin(2 * PI * 380.0 * t) * 0.7 + sin(2 * PI * 760.0 * t) * 0.3) * env
                        (s * 24000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "bubble_drop" -> {
                    val dur = 0.11
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val p = t / dur
                        val f = 350.0 + 600.0 * sqrt(p)
                        val env = sin(p * PI) * exp(-t * 15.0)
                        val s = sin(2 * PI * f * t) * env
                        (s * 28000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "glass_tap" -> {
                    val dur = 0.12
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val env = exp(-t * 30.0)
                        val s = (sin(2 * PI * 1800.0 * t) * 0.8 + sin(2 * PI * 3600.0 * t) * 0.2) * env
                        (s * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "coin_clink" -> {
                    val dur = 0.14
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val (f, env) = if (t < 0.045) {
                            988.0 to (1.0 - (t / 0.045) * 0.3)
                        } else {
                            1318.0 to exp(-(t - 0.045) * 25.0)
                        }
                        val s = sin(2 * PI * f * t) * env
                        (s * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "digital_beep" -> {
                    val dur = 0.08
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val f = 750.0
                        val attack = min(1.0, t / 0.01)
                        val decay = max(0.0, 1.0 - max(0.0, (t - 0.05) / 0.03))
                        val env = attack * decay
                        val s = sin(2 * PI * f * t) * env
                        (s * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "marimba_note" -> {
                    val dur = 0.15
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val env = exp(-t * 24.0)
                        val s = (sin(2 * PI * 440.0 * t) * 0.8 + sin(2 * PI * 880.0 * t) * 0.2) * env
                        (s * 28000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                "laser_zap" -> {
                    val dur = 0.09
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val f = 1600.0 - 1300.0 * (t / dur)
                        val env = exp(-t * 28.0)
                        val s = sin(2 * PI * f * t) * env
                        (s * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                else -> { // "classic_pop" (Default)
                    val dur = 0.08
                    val n = (sr * dur).toInt()
                    ShortArray(n) { i ->
                        val t = i.toDouble() / sr
                        val f = 420.0 + 480.0 * (t / dur)
                        val env = exp(-t * 40.0)
                        val s = sin(2 * PI * f * t) * env
                        (s * 28000).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
            }
        }
    }

    /**
     * Plays the selected number pick sound preset if sounds are enabled.
     * Can also be called directly with a specific presetId for previewing in Settings.
     */
    fun playPickSound(context: Context, overridePresetId: String? = null, forceSound: Boolean = false) {
        if (!forceSound && !SoundPreferences.soundEnabled.value) return

        val activePreset = overridePresetId ?: SoundPreferences.pickSoundPresetId.value
        val pcm = getPcmForPreset(activePreset)

        audioScope.launch {
            try {
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(pcm, 0, pcm.size)
                track.play()

                // Wait for the duration of the audio clip plus a safe margin, then release
                val durationMs = (pcm.size * 1000L / SAMPLE_RATE) + 60L
                delay(durationMs)
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            } catch (_: Throwable) {
                // Non-fatal audio fallback
            }
        }
    }

    /**
     * Triggers a subtle tactile haptic vibration click if vibration is enabled.
     */
    fun playVibration(context: Context, durationMs: Long = 35L, forceVibrate: Boolean = false) {
        if (!forceVibrate && !SoundPreferences.isHapticsAllowed()) return

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {
            // Ignore non-fatal haptic error
        }
    }
}
