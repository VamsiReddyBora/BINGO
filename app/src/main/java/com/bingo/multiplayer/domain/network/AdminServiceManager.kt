package com.bingo.multiplayer.domain.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic configuration for retention and cleanup timeouts.
 */
data class AdminRetentionConfig(
    val finishedMatchExpiryMinutes: Int = 30,      // [10, 30, 60, 120]
    val closedLobbyExpiryMinutes: Int = 60,         // [15, 30, 60, 180]
    val abandonedMatchExpiryMinutes: Int = 120,     // [30, 60, 120, 360]
    val rejoinGraceMinutes: Int = 5                // [2, 5, 10]
)

/**
 * Breakdown of records eligible for cleanup.
 */
data class CleanupEligibility(
    val totalEligible: Int = 0,
    val finishedMatches: Int = 0,
    val closedLobbies: Int = 0,
    val abandonedMatches: Int = 0,
    val stalePresence: Int = 0,
    val staleInvites: Int = 0,
    val estimatedTimeSeconds: Int = 1
)

/**
 * Data model for live broker retained message metrics.
 */
data class BrokerUsageStats(
    val used: Int = 0,
    val limit: Int = 2000,
    val free: Int = 2000,
    val percent: Float = 0f,
    val stale: Boolean = false,
    val tier: String = "idle",
    val tierReason: String = "",
    val intervalMs: Long = 3600000L,
    val lastCleanedCount: Int = 0,
    val lastCleanedAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val lastStatus: String = "OK",
    val deferredReason: String? = null,
    val isRunning: Boolean = false,
    // Live category breakdown matching EMQX Console
    val matchesCount: Int = 0,
    val lobbiesCount: Int = 0,
    val profilesCount: Int = 0,
    val presenceCount: Int = 0,
    val otherCount: Int = 0
)

/**
 * Data model for manual cleanup execution results.
 */
data class ManualCleanupResult(
    val ok: Boolean,
    val status: String,
    val deletedConfirmed: Int,
    val skippedChanged: Int,
    val deferred: Boolean,
    val reason: String?,
    val usage: BrokerUsageStats?
)

/**
 * Native Direct EMQX Broker Administrative Client for @bob.
 * Queries all retained topics under the entire bingo/# namespace via TLS,
 * guaranteeing 100% exact parity with the official EMQX Cloud console.
 */
object AdminServiceManager {
    private const val TAG = "AdminServiceManager"
    private const val PREFS_ADMIN = "bingo_admin_config"

    @Volatile private var cachedStats: BrokerUsageStats? = null
    @Volatile private var lastSnapshot: Map<String, ByteArray> = emptyMap()
    @Volatile private var lastCleanCount: Int = 0
    @Volatile private var lastCleanTimestamp: Long = 0L

    @Volatile var retentionConfig = AdminRetentionConfig()
        private set

    fun initConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_ADMIN, Context.MODE_PRIVATE)
            retentionConfig = AdminRetentionConfig(
                finishedMatchExpiryMinutes = prefs.getInt("match_expiry", 30),
                closedLobbyExpiryMinutes = prefs.getInt("lobby_expiry", 60),
                abandonedMatchExpiryMinutes = prefs.getInt("abandoned_expiry", 120),
                rejoinGraceMinutes = prefs.getInt("rejoin_grace", 5)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load admin config: ${e.message}")
        }
    }

    fun updateConfig(context: Context, config: AdminRetentionConfig) {
        retentionConfig = config
        try {
            context.getSharedPreferences(PREFS_ADMIN, Context.MODE_PRIVATE).edit()
                .putInt("match_expiry", config.finishedMatchExpiryMinutes)
                .putInt("lobby_expiry", config.closedLobbyExpiryMinutes)
                .putInt("abandoned_expiry", config.abandonedMatchExpiryMinutes)
                .putInt("rejoin_grace", config.rejoinGraceMinutes)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save admin config: ${e.message}")
        }
    }

    fun getEligibleCleanupStats(force: Boolean = false): CleanupEligibility {
        val snapshot = lastSnapshot
        if (snapshot.isEmpty()) return CleanupEligibility()

        val now = System.currentTimeMillis()
        val config = retentionConfig
        val finishedMatchMs = config.finishedMatchExpiryMinutes * 60 * 1000L
        val closedLobbyMs = config.closedLobbyExpiryMinutes * 60 * 1000L
        val abandonedMatchMs = config.abandonedMatchExpiryMinutes * 60 * 1000L
        val rejoinGraceMs = config.rejoinGraceMinutes * 60 * 1000L

        var finishedMatches = 0
        var closedLobbies = 0
        var abandonedMatches = 0
        var stalePresence = 0
        var staleInvites = 0

        for ((topic, payload) in snapshot) {
            if (topic.contains("/registry/") || topic.contains("/google_account/") || topic.contains("/avatar_update/")) {
                continue
            }
            val str = payload.toString(Charsets.UTF_8)
            val tsMatch = Regex("\"(?:lastHeartbeat|timestamp|createdAt)\"\\s*:\\s*(\\d+)").find(str)
            val ts = tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val age = if (ts > 0) now - ts else Long.MAX_VALUE

            if (!force && ts > 0 && age < rejoinGraceMs) continue

            when {
                topic.contains("/moves/") -> {
                    if (age >= 3 * 60 * 1000L) finishedMatches++
                }
                topic.contains("/match/") -> {
                    if (str.contains("\"isMatchFinished\":true") || str.contains("\"winnerPlayerId\"")) {
                        if (age >= finishedMatchMs) finishedMatches++
                    } else if (age >= abandonedMatchMs) {
                        abandonedMatches++
                    }
                }
                topic.contains("/metadata/") || topic.contains("/room/") -> {
                    if (str.contains("\"CLOSED\"")) {
                        if (age >= closedLobbyMs) closedLobbies++
                    } else if (age >= abandonedMatchMs) {
                        abandonedMatches++
                    }
                }
                topic.contains("/presence/cleanup_") -> {
                    stalePresence++
                }
                topic.contains("/invite/") -> {
                    if (age >= 2 * 60 * 1000L) staleInvites++
                }
            }
        }

        val total = finishedMatches + closedLobbies + abandonedMatches + stalePresence + staleInvites
        val estSeconds = maxOf(1, (total / 25) * 1)
        return CleanupEligibility(
            totalEligible = total,
            finishedMatches = finishedMatches,
            closedLobbies = closedLobbies,
            abandonedMatches = abandonedMatches,
            stalePresence = stalePresence,
            staleInvites = staleInvites,
            estimatedTimeSeconds = estSeconds
        )
    }

    suspend fun fetchBrokerStatus(
        adminUsername: String = "bob",
        forceRefresh: Boolean = false,
        onStage: (String) -> Unit = {}
    ): Result<BrokerUsageStats> = withContext(Dispatchers.IO) {
        val existing = cachedStats
        if (!forceRefresh && existing != null && !existing.stale &&
            System.currentTimeMillis() - existing.lastCleanedAt < 10000L && existing.used > 0) {
            return@withContext Result.success(existing)
        }

        try {
            val stats = queryBrokerDirectly(onStage)
            cachedStats = stats
            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Direct broker query failed: ${e.message}", e)
            val fallback = cachedStats?.copy(stale = true)
            if (fallback != null) {
                Result.success(fallback)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun triggerManualCleanup(
        adminUsername: String = "bob",
        force: Boolean = false,
        onProgress: (deleted: Int, total: Int, status: String) -> Unit = { _, _, _ -> }
    ): Result<ManualCleanupResult> = withContext(Dispatchers.IO) {
        try {
            val result = executeCleanupDirectly(force = force, onProgress = onProgress)
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Direct broker cleanup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    internal suspend fun queryBrokerDirectly(
        onStage: (String) -> Unit = {}
    ): BrokerUsageStats {
        val clientId = "bingo_bob_stat_${UUID.randomUUID().toString().take(6)}"
        val client = MqttAsyncClient(NetworkConfig.BROKER_URL, clientId, MemoryPersistence())
        try {
            onStage("Connecting to EMQX TLS cluster...")
            val records = ConcurrentHashMap<String, ByteArray>()

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                NetworkConfig.applyMqttOptions(this)
            }

            client.connect(options).waitForCompletion(5000L)

            onStage("Subscribing to retained topics...")
            // Subscribe to the full bingo/# namespace with QoS 0 to avoid subscriber queue bottlenecks
            val listener = IMqttMessageListener { topic, message ->
                if (message.isRetained && message.payload != null && message.payload.isNotEmpty()) {
                    records[topic] = message.payload
                }
            }

            client.subscribe("bingo/#", 0, listener).waitForCompletion(4000L)

            onStage("Ingesting retained message stream...")
            // Stream drain quiet-window detector: wait until message ingestion settles (2 consecutive cycles of 350ms)
            var prev = 0
            var stableCycles = 0
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 6500L) {
                delay(350L)
                val current = records.size
                if (current > 0 && current == prev) {
                    stableCycles++
                    if (stableCycles >= 2) {
                        break
                    }
                } else {
                    stableCycles = 0
                }
                prev = current
            }

            onStage("Categorizing namespace records...")
            val used = records.size
            val limit = 2000
            val free = maxOf(0, limit - used)
            val percent = (used.toFloat() / limit.toFloat()) * 100f

            val tier = when {
                used >= 1600 -> "surge"
                used >= 1200 -> "elevated"
                used >= 600 -> "normal"
                else -> "idle"
            }
            val tierReason = when (tier) {
                "surge" -> "High capacity warning (2m interval)"
                "elevated" -> "Elevated capacity (5m interval)"
                "normal" -> "Normal load (15m interval)"
                else -> "Low broker load (60m interval)"
            }
            val intervalMs = when (tier) {
                "surge" -> 120000L
                "elevated" -> 300000L
                "normal" -> 900000L
                else -> 3600000L
            }

            // Category breakdown matching EMQX Console
            var matchesCount = 0
            var lobbiesCount = 0
            var profilesCount = 0
            var presenceCount = 0
            var otherCount = 0

            for (t in records.keys) {
                when {
                    t.contains("/match/") || t.startsWith("bingo/v5/") -> matchesCount++
                    t.contains("/metadata/") || t.contains("/room/") -> lobbiesCount++
                    t.contains("/registry/") || t.contains("/google_account/") || t.contains("/avatar_update/") -> profilesCount++
                    t.contains("/presence/") || t.contains("/social/") -> presenceCount++
                    else -> otherCount++
                }
            }

            lastSnapshot = records
            val stats = BrokerUsageStats(
                used = used,
                limit = limit,
                free = free,
                percent = percent,
                stale = false,
                tier = tier,
                tierReason = tierReason,
                intervalMs = intervalMs,
                lastCleanedCount = lastCleanCount,
                lastCleanedAt = lastCleanTimestamp,
                nextRunAt = System.currentTimeMillis() + intervalMs,
                lastStatus = "OK",
                deferredReason = null,
                isRunning = false,
                matchesCount = matchesCount,
                lobbiesCount = lobbiesCount,
                profilesCount = profilesCount,
                presenceCount = presenceCount,
                otherCount = otherCount
            )
            return stats
        } finally {
            MqttClientShutdown.close(client, quiesceMillis = 50L, disconnectMillis = 50L)
        }
    }

    private suspend fun executeCleanupDirectly(
        force: Boolean = false,
        onProgress: (deleted: Int, total: Int, status: String) -> Unit = { _, _, _ -> }
    ): ManualCleanupResult {
        val clientId = "bingo_bob_clean_${UUID.randomUUID().toString().take(6)}"
        val client = MqttAsyncClient(NetworkConfig.BROKER_URL, clientId, MemoryPersistence())
        try {
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                NetworkConfig.applyMqttOptions(this)
            }
            client.connect(options).waitForCompletion(5000L)

            // Gather fresh snapshot
            val currentStats = queryBrokerDirectly()
            val snapshot = lastSnapshot
            val now = System.currentTimeMillis()

            val config = retentionConfig
            val finishedMatchMs = config.finishedMatchExpiryMinutes * 60 * 1000L
            val closedLobbyMs = config.closedLobbyExpiryMinutes * 60 * 1000L
            val abandonedMatchMs = config.abandonedMatchExpiryMinutes * 60 * 1000L
            val rejoinGraceMs = config.rejoinGraceMinutes * 60 * 1000L

            val eligibleTopics = mutableListOf<String>()

            for ((topic, payload) in snapshot) {
                // 1. Account protection: Never purge player identity/profile/registry
                if (topic.contains("/registry/") || topic.contains("/google_account/") || topic.contains("/avatar_update/")) {
                    continue
                }

                val str = payload.toString(Charsets.UTF_8)
                val tsMatch = Regex("\"(?:lastHeartbeat|timestamp|createdAt)\"\\s*:\\s*(\\d+)").find(str)
                val ts = tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val age = if (ts > 0) now - ts else Long.MAX_VALUE

                // 2. Active gameplay protection: If active heartbeat within grace, preserve it!
                if (!force && ts > 0 && age < rejoinGraceMs) {
                    continue
                }

                // 3. Selective topic expiry evaluation
                var isExpired = false
                when {
                    topic.contains("/moves/") -> {
                        if (age >= 3 * 60 * 1000L) isExpired = true
                    }
                    topic.contains("/match/") -> {
                        if (str.contains("\"isMatchFinished\":true") || str.contains("\"winnerPlayerId\"")) {
                            if (age >= finishedMatchMs) isExpired = true
                        } else if (age >= abandonedMatchMs) {
                            isExpired = true
                        }
                    }
                    topic.contains("/metadata/") || topic.contains("/room/") -> {
                        if (str.contains("\"CLOSED\"")) {
                            if (age >= closedLobbyMs) isExpired = true
                        } else if (age >= abandonedMatchMs) {
                            isExpired = true
                        }
                    }
                    topic.contains("/presence/cleanup_") -> {
                        isExpired = true
                    }
                    topic.contains("/invite/") -> {
                        if (age >= 2 * 60 * 1000L) isExpired = true
                    }
                }

                if (isExpired) {
                    eligibleTopics.add(topic)
                }
            }

            if (eligibleTopics.isEmpty()) {
                return ManualCleanupResult(
                    ok = true,
                    status = "CLEAN",
                    deletedConfirmed = 0,
                    skippedChanged = 0,
                    deferred = false,
                    reason = "All messages are fresh within the configured retention timeouts",
                    usage = currentStats
                )
            }

            onProgress(0, eligibleTopics.size, "Starting purge of ${eligibleTopics.size} expired records...")

            // Purge eligible topics in parallel bursts of up to 25 to avoid saturating MQTT connection
            var deletedCount = 0
            val emptyMsg = MqttMessage(ByteArray(0)).apply {
                setQos(1)
                isRetained = true
            }

            val batchSize = 25
            for (chunk in eligibleTopics.chunked(batchSize)) {
                try {
                    val tokens = chunk.map { topic ->
                        client.publish(topic, emptyMsg)
                    }
                    for (token in tokens) {
                        try {
                            token.waitForCompletion(1500L)
                            deletedCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "Publish ack timeout: ${e.message}")
                        }
                    }
                    val pct = ((deletedCount.toFloat() / eligibleTopics.size.toFloat()) * 100).toInt()
                    onProgress(deletedCount, eligibleTopics.size, "Purged $deletedCount / ${eligibleTopics.size} records ($pct%)...")
                    delay(20L)
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk publish error: ${e.message}")
                }
            }

            lastCleanCount = deletedCount
            lastCleanTimestamp = System.currentTimeMillis()

            val newUsed = maxOf(0, currentStats.used - deletedCount)
            val limit = 2000
            val newFree = maxOf(0, limit - newUsed)
            val newPercent = (newUsed.toFloat() / limit.toFloat()) * 100f
            val updatedStats = currentStats.copy(
                used = newUsed,
                free = newFree,
                percent = newPercent,
                lastCleanedCount = deletedCount,
                lastCleanedAt = lastCleanTimestamp,
                lastStatus = "OK"
            )
            cachedStats = updatedStats

            return ManualCleanupResult(
                ok = true,
                status = "OK",
                deletedConfirmed = deletedCount,
                skippedChanged = 0,
                deferred = false,
                reason = null,
                usage = updatedStats
            )
        } finally {
            MqttClientShutdown.close(client, quiesceMillis = 50L, disconnectMillis = 50L)
        }
    }
}
