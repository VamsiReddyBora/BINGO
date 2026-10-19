package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.CloudUserDataBackup
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Administrative edits are applied once. Future match results remain ordinary player statistics. */
@Serializable
data class AdminField(val value: JsonElement = JsonNull, val revision: Long = 0)

@Serializable
data class AdminPlayerPolicy(
    val username: String = "",
    val uid: String? = null,
    val revision: Long = 0,
    val deleted: Boolean = false,
    val fields: Map<String, AdminField> = emptyMap(),
    val deletedMatches: Set<String> = emptySet(),
    val clearMatchesBefore: Long = 0
) {
    fun apply(profile: UserProfile): UserProfile? {
        if (deleted || (uid != null && uid != profile.uid)) return null
        fun changed(key: String) = fields[key]?.takeIf { it.revision > profile.adminRevision }
        fun number(key: String, fallback: Int) = changed(key)?.value?.jsonPrimitive?.intOrNull ?: fallback
        fun photo(key: String, fallback: String?): String? {
            val change = changed(key) ?: return fallback
            return change.value.jsonPrimitive.contentOrNull?.substringAfter("base64,")
        }
        return profile.copy(
            displayName = changed("displayName")?.value?.jsonPrimitive?.contentOrNull ?: profile.displayName,
            gamesPlayed = number("gamesPlayed", profile.gamesPlayed),
            gamesWon = number("gamesWon", profile.gamesWon),
            currentStreak = number("currentStreak", profile.currentStreak),
            bestStreak = number("bestStreak", profile.bestStreak),
            level = number("level", profile.level),
            xp = number("xp", profile.xp),
            avatarUrl = photo("avatarUrl", profile.avatarUrl),
            avatarBase64 = photo("avatarBase64", profile.avatarBase64),
            adminRevision = maxOf(profile.adminRevision, revision)
        )
    }

    fun filter(matches: List<MatchRecord>): List<MatchRecord> = matches.filterNot {
        it.id in deletedMatches || (clearMatchesBefore > 0 && it.timestamp <= clearMatchesBefore)
    }
}

/** This background control channel is never called by the move/turn packet path. */
object AdminControlClient {
    private const val BASE = "https://bingo-admin-control.vamsireddy2534.workers.dev"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null
    private val policies = ConcurrentHashMap<String, AdminPlayerPolicy>()
    private val checked = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()
    @Volatile private var deleted: Map<String, String> = emptyMap()

    @Serializable private data class DeletedPlayer(val username: String, val uid: String)
    @Serializable private data class Deletions(val deleted: List<DeletedPlayer> = emptyList())

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("bingo_admin_control", Context.MODE_PRIVATE)
        runCatching { deleted = json.decodeFromString<Deletions>(prefs?.getString("deletions", null) ?: "{}").deleted.associate { it.username to it.uid } }
    }

    private fun clean(name: String) = name.trim().lowercase().removePrefix("@")
    fun removed(name: String = "", uid: String = ""): Boolean = deleted.containsKey(clean(name)) || (uid.isNotEmpty() && deleted.containsValue(uid)) || policies[clean(name)]?.deleted == true

    fun cached(name: String): AdminPlayerPolicy? {
        val key = clean(name)
        return policies[key] ?: runCatching {
            prefs?.getString("policy:$key", null)?.let { json.decodeFromString<AdminPlayerPolicy>(it) }?.also { policies[key] = it }
        }.getOrNull()
    }

    fun apply(profile: UserProfile): UserProfile? {
        if (removed(profile.username, profile.uid)) return null
        val policy = cached(profile.username) ?: return profile
        return policy.apply(profile)
    }
    fun filter(name: String, matches: List<MatchRecord>) = cached(name)?.filter(matches) ?: matches

    internal fun accept(policy: AdminPlayerPolicy) {
        val key = clean(policy.username)
        val previous = cached(key)
        if (previous != null && previous.revision > policy.revision) return
        policies[key] = policy
        prefs?.edit()?.putString("policy:$key", json.encodeToString(policy))?.apply()
    }

    internal fun resetForTests() { policies.clear(); checked.clear(); deleted = emptyMap(); prefs = null }

    suspend fun refresh(name: String, force: Boolean = false): AdminPlayerPolicy? = withContext(Dispatchers.IO) {
        val key = clean(name)
        if (prefs == null || key.isEmpty()) return@withContext cached(key)
        mutex.withLock {
            if (!force && System.currentTimeMillis() - (checked[key] ?: 0) < 15_000) return@withLock cached(key)
            runCatching {
                val policy = json.decodeFromString<AdminPlayerPolicy>(read("/client/policy/$key"))
                accept(policy)
                checked[key] = System.currentTimeMillis()
            }
            cached(key)
        }
    }

    suspend fun refreshDeletions() = withContext(Dispatchers.IO) {
        if (prefs == null) return@withContext
        runCatching {
            val raw = read("/client/deletions")
            val result = json.decodeFromString<Deletions>(raw)
            deleted = result.deleted.associate { it.username to it.uid }
            prefs?.edit()?.putString("deletions", raw)?.apply()
        }
    }

    suspend fun prepareBackup(backup: CloudUserDataBackup): CloudUserDataBackup? {
        refresh(backup.profile.username)
        val profile = apply(backup.profile) ?: return null
        return backup.copy(profile = profile, matchHistory = filter(profile.username, backup.matchHistory))
    }

    fun apply(entry: PlayerRegistryEntry): PlayerRegistryEntry? {
        val p = apply(UserProfile(uid = entry.uid, username = entry.username, displayName = entry.displayName,
            avatarUrl = entry.avatarUrl, gamesPlayed = entry.gamesPlayed, gamesWon = entry.gamesWon,
            currentStreak = entry.currentStreak, bestStreak = entry.bestStreak, level = entry.level,
            adminRevision = entry.adminRevision)) ?: return null
        return entry.copy(displayName = p.displayName, avatarUrl = p.avatarBase64 ?: p.avatarUrl,
            gamesPlayed = p.gamesPlayed, gamesWon = p.gamesWon, currentStreak = p.currentStreak,
            bestStreak = p.bestStreak, level = p.level, adminRevision = p.adminRevision)
    }

    private fun read(path: String): String {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            check(connection.responseCode == 200)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
