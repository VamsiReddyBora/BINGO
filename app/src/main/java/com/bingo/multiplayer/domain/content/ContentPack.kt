package com.bingo.multiplayer.domain.content

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Data only. Native handlers, permissions, room rules and executable code are not OTA payloads. */
@Serializable
data class ContentHomeStyle(
    val verticalPaddingDp: Int? = null,
    val titleSp: Int? = null,
    val descriptionSp: Int? = null,
    val descriptionMaxLines: Int? = null,
    val backgroundOpacity: Float = .08f
)
@Serializable
data class ContentAsset(val url: String, val sha256: String, val bytes: Long)
@Serializable
data class ContentPack(
    val schema: Int = 1,
    val revision: Long,
    val audience: String,
    val minBuild: Long = 74,
    val maxBuild: Long = 0,
    val enabled: Boolean = true,
    val inheritGlobal: Boolean = false,
    val strings: Map<String, String> = emptyMap(),
    val homeStyle: ContentHomeStyle = ContentHomeStyle(),
    val background: ContentAsset? = null
) {
    fun compatible(build: Long) = build >= minBuild && (maxBuild == 0L || build <= maxBuild)
}
@Serializable
data class VerifiedContent(val pack: ContentPack, val digest: String) {
    val key: String get() = "${pack.audience}/${pack.revision}/$digest"
}
@Serializable
data class ContentSeen(val revision: Long, val digest: String)
@Serializable
data class ContentCache(
    val active: VerifiedContent? = null,
    val previous: VerifiedContent? = null,
    val staged: VerifiedContent? = null,
    val seen: Map<String, ContentSeen> = emptyMap(),
    val blocked: Set<String> = emptySet(),
    val automatic: Boolean = true
)
enum class ContentStage { IDLE, CHECKING, DOWNLOADING, READY, APPLYING, APPLIED, ERROR, ROLLED_BACK }
data class ContentUpdateStatus(
    val uid: String = "", val stage: ContentStage = ContentStage.IDLE,
    val downloaded: Long = 0, val total: Long = 0, val message: String = "",
    val cache: ContentCache = ContentCache()
)

object ContentPacks {
    const val BASE = "https://vamsireddybora.github.io/BINGO/content/"
    const val MAX_MANIFEST_BYTES = 32_768
    const val MAX_ASSET_BYTES = 2 * 1024 * 1024L
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    val stringLimits = mapOf("home.ai.title" to 48, "home.ai.description" to 140,
        "home.online.title" to 48, "home.online.description" to 140,
        "home.nearby.title" to 48, "home.nearby.description" to 140,
        "home.developerNote" to 60, "home.serverNote" to 400)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun audience(uid: String) = hash(uid.toByteArray(Charsets.UTF_8))
    fun path(audience: String) = if (audience == "ALL") "index.json" else "users/$audience.json"
    fun parse(body: String, expectedAudience: String, build: Long): VerifiedContent {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Content manifest is too large" }
        val p = json.decodeFromString<ContentPack>(body)
        require(p.schema == 1 && p.revision in 1..9_007_199_254_740_991L) { "Unsupported content revision/schema" }
        require(p.audience == expectedAudience && (p.audience == "ALL" || p.audience.matches(Regex("[a-f0-9]{64}")))) { "Content audience mismatch" }
        require(p.minBuild >= 74 && (p.maxBuild == 0L || p.maxBuild >= p.minBuild)) { "Invalid content compatibility" }
        require(p.compatible(build)) { "This content needs a different app build" }
        require(p.strings.all { (key, value) -> key in stringLimits && value.isNotBlank() &&
            value.length <= stringLimits.getValue(key) && value.none { it.isISOControl() } }) { "Unsupported content text" }
        val s = p.homeStyle
        require((s.verticalPaddingDp == null || s.verticalPaddingDp in 8..20) &&
            (s.titleSp == null || s.titleSp in 13..18) && (s.descriptionSp == null || s.descriptionSp in 10..14) &&
            (s.descriptionMaxLines == null || s.descriptionMaxLines in 1..3) &&
            s.backgroundOpacity.isFinite() && s.backgroundOpacity in 0f..0.15f) { "Unsupported Home styling" }
        p.background?.let { asset ->
            require(asset.bytes in 1..MAX_ASSET_BYTES && asset.sha256.matches(Regex("[a-f0-9]{64}")) &&
                asset.url.matches(Regex(Regex.escape(BASE) + "assets/${asset.sha256}\\.(png|jpg|webp)"))) { "Unsupported content asset" }
        }
        require(p.enabled || (p.strings.isEmpty() && p.background == null && p.homeStyle == ContentHomeStyle())) { "Disabled content must restore built-in defaults" }
        require(!p.inheritGlobal || (p.audience != "ALL" && p.strings.isEmpty() && p.background == null && p.homeStyle == ContentHomeStyle())) { "Public inheritance cannot contain targeted overrides" }
        return VerifiedContent(p, hash(body.toByteArray(Charsets.UTF_8)))
    }
    fun text(status: ContentUpdateStatus, uid: String?, key: String, fallback: String): String =
        status.takeIf { it.uid == uid }?.cache?.active?.pack?.takeIf { it.enabled }?.strings?.get(key) ?: fallback
}
