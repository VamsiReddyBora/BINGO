package com.bingo.multiplayer.domain.network

import android.content.Context
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Background social sync. It never blocks navigation or participates in match timing. */
object LeaderboardSyncManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var accountKey: String? = null
    private val _isSyncComplete = MutableStateFlow(false)
    val isSyncComplete = _isSyncComplete.asStateFlow()
    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress = _syncProgress.asStateFlow()
    private val _syncStageText = MutableStateFlow("Loading friends…")
    val syncStageText = _syncStageText.asStateFlow()

    @Synchronized
    fun startBootSync(
        context: Context,
        username: String,
        uid: String,
        friendsRepository: FriendsRepository? = null,
        authRepository: AuthRepository? = null
    ) {
        val key = "$uid:${username.trim().lowercase().removePrefix("@") }"
        if (key == accountKey && (syncJob?.isActive == true || _isSyncComplete.value)) return
        syncJob?.cancel()
        accountKey = key
        _isSyncComplete.value = false
        _syncProgress.value = 0f
        val appContext = context.applicationContext
        val friends = friendsRepository ?: FriendsRepository.activeInstance
        val auth = authRepository ?: AuthRepository.activeInstance
        syncJob = scope.launch {
            try {
                _syncStageText.value = "Loading match history…"
                _syncProgress.value = .1f
                val matches = FirestoreSyncManager.getInstance(appContext).fetchMatchHistoryOnce(uid)
                if (matches.isNotEmpty() && accountKey == key) auth?.persistMatchHistory(matches)
                _syncProgress.value = .5f
                _syncStageText.value = "Updating friend statistics…"
                friends?.refreshFriendsStats()
                _syncProgress.value = 1f
                _syncStageText.value = "Friends updated"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _syncStageText.value = "Showing saved statistics"
            } finally {
                if (accountKey == key) {
                    _isSyncComplete.value = true
                    AccountSessionManager.notifyRegistryUpdated()
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        accountKey = null
        syncJob?.cancel()
        syncJob = null
        _isSyncComplete.value = false
        _syncProgress.value = 0f
    }
}
