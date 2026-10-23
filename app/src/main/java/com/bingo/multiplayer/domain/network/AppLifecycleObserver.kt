package com.bingo.multiplayer.domain.network

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level lifecycle observer that tracks whether the app is in the foreground or background.
 * Combines Android's ProcessLifecycleOwner and Activity callbacks for 0ms immediate transition detection.
 *
 * Rules:
 *  - App in foreground → isAppInForeground = true → PresenceManager publishes ONLINE
 *  - App goes to background → isAppInForeground = false → PresenceManager publishes OFFLINE with timestamp
 *  - This is NOT tied to screen transitions. Only actual app foreground/background events.
 */
object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _lastBackgroundTimestamp = MutableStateFlow(0L)
    val lastBackgroundTimestamp: StateFlow<Long> = _lastBackgroundTimestamp.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * Must be called once from Application.onCreate() or MainActivity.onCreate().
     * Registers this observer with ProcessLifecycleOwner.
     */
    @Synchronized
    fun init() {
        if (initialized) return
        initialized = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver initialized with ProcessLifecycleOwner")
    }

    fun onForegroundImmediate() {
        if (_isAppInForeground.value) return
        _isAppInForeground.value = true
        Log.d(TAG, "App is now in FOREGROUND (immediate)")
        PresenceManager.onAppForeground()
    }

    fun onBackgroundImmediate() {
        if (!_isAppInForeground.value) return
        val now = System.currentTimeMillis()
        _isAppInForeground.value = false
        _lastBackgroundTimestamp.value = now
        Log.d(TAG, "App is now in BACKGROUND at $now (immediate)")
        PresenceManager.onAppBackground()
    }

    override fun onStart(owner: LifecycleOwner) {
        onForegroundImmediate()
    }

    override fun onStop(owner: LifecycleOwner) {
        onBackgroundImmediate()
    }
}
