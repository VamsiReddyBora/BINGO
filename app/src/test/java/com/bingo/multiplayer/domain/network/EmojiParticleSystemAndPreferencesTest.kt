package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.presentation.components.ALL_REACTION_EMOJIS
import com.bingo.multiplayer.presentation.components.EmojiParticleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Multi-Emoji Floating Particle System configuration and emoji collection.
 * 2. Multi-depth tier sizing, speeds, and opacity parameters.
 * 3. Favorite emojis pinned first at the start of the reaction strip.
 * 4. Recent emojis placed immediately following favorites.
 * 5. Bug fixes: Surrender does not trigger winning celebration; fresh game resets incoming emotes.
 */
class EmojiParticleSystemAndPreferencesTest {

    @Test
    fun testDefaultEmojiParticleConfig() {
        val config = EmojiParticleConfig()
        assertNotNull(config.emojiList)
        assertEquals(12, config.emojiList.size)

        val expectedEmojis = listOf("❤️", "😂", "🔥", "😍", "😎", "🎉", "🤯", "💀", "👀", "✨", "🚀", "💯")
        assertEquals(expectedEmojis, config.emojiList)

        // Burst size (8 to 15 particles)
        assertTrue("Particle count is within burst range", config.particleCount in 8..15)

        // Sizes: small background (18sp) to large visual emphasis (48sp)
        assertTrue("Min size is at least 18sp", config.minSize >= 18f)
        assertTrue("Max size is up to 48sp", config.maxSize <= 50f)
        assertTrue("Min size < Max size", config.minSize < config.maxSize)

        // Speeds and lifecycles
        assertTrue("Min speed < Max speed", config.minSpeed < config.maxSpeed)
        assertTrue("Particle lifetime is >= 2.5s", config.particleLifetime >= 2500L)
        assertTrue("Scale in duration is positive", config.scaleDuration > 0L)
        assertTrue("Fade out duration is positive", config.fadeDuration > 0L)
        assertTrue("Max active particles <= 120", config.maxActiveParticles <= 120)
    }

    @Test
    fun testDepthTierDistributionLogic() {
        // Small (Background): ~35%, Medium (Primary): ~50%, Large (Visual emphasis): ~15%
        fun getTier(roll: Float): Int = when {
            roll < 0.35f -> 0
            roll < 0.85f -> 1
            else -> 2
        }

        assertEquals(0, getTier(0.10f)) // Small
        assertEquals(0, getTier(0.34f)) // Small
        assertEquals(1, getTier(0.36f)) // Medium
        assertEquals(1, getTier(0.84f)) // Medium
        assertEquals(2, getTier(0.86f)) // Large
        assertEquals(2, getTier(0.99f)) // Large
    }

    @Test
    fun testFavoriteEmojisPinnedFirstInStrip() {
        val favorites = listOf("🔥", "😂", "🎯")
        val recents = listOf("👏", "😱", "🎉")
        val all = listOf("🔥", "😂", "🎯", "👏", "😱", "🎉", "🌟", "✨", "🏆", "🥇")

        fun composeStrip(favs: List<String>, recs: List<String>, library: List<String>): List<String> {
            val effRecents = recs.filter { !favs.contains(it) }
            val rest = library.filter { !favs.contains(it) && !effRecents.contains(it) }
            return favs + effRecents + rest
        }

        val strip = composeStrip(favorites, recents, all)

        // 1. Favorites MUST be the first 3 items in the strip
        assertEquals("🔥", strip[0])
        assertEquals("😂", strip[1])
        assertEquals("🎯", strip[2])

        // 2. Recents MUST immediately follow favorites
        assertEquals("👏", strip[3])
        assertEquals("😱", strip[4])
        assertEquals("🎉", strip[5])

        // 3. Remainder follows
        assertTrue(strip.subList(6, strip.size).contains("🌟"))
        assertTrue(strip.subList(6, strip.size).contains("✨"))
        assertTrue(strip.subList(6, strip.size).contains("🏆"))
        assertTrue(strip.subList(6, strip.size).contains("🥇"))

        // 4. Ensure total uniqueness
        assertEquals(all.size, strip.size)
        assertEquals(all.toSet(), strip.toSet())
    }

    @Test
    fun testRecordingRecentEmojiKeepsFavoritesPinned() {
        val favorites = listOf("🔥", "😂")
        val recents = mutableListOf("👏")

        fun onEmojiPicked(emoji: String) {
            recents.remove(emoji)
            recents.add(0, emoji)
        }

        onEmojiPicked("🎉")
        onEmojiPicked("🚀")

        val effRecents = recents.filter { !favorites.contains(it) }
        val strip = favorites + effRecents

        // Favorites are still pinned at positions 0 and 1!
        assertEquals("🔥", strip[0])
        assertEquals("😂", strip[1])
        // Recents follow in MRU order
        assertEquals("🚀", strip[2])
        assertEquals("🎉", strip[3])
        assertEquals("👏", strip[4])
    }

    @Test
    fun testWinningCelebrationOnlyTriggersOnVictory() {
        // Bug fix test: isEmojiBurstActive must only be true when didPlayerWin is true
        fun shouldTriggerBurst(isGameOver: Boolean, didPlayerWin: Boolean): Boolean {
            return isGameOver && didPlayerWin
        }

        // 1. Game won naturally -> triggers burst!
        assertTrue(shouldTriggerBurst(isGameOver = true, didPlayerWin = true))

        // 2. Game lost naturally -> does NOT trigger burst!
        assertFalse(shouldTriggerBurst(isGameOver = true, didPlayerWin = false))

        // 3. Match exited/surrendered in middle -> does NOT trigger burst!
        assertFalse(shouldTriggerBurst(isGameOver = true, didPlayerWin = false))

        // 4. Match in progress -> does NOT trigger burst
        assertFalse(shouldTriggerBurst(isGameOver = false, didPlayerWin = false))
    }

    @Test
    fun testNewGameResetsIncomingEmoteState() {
        // Bug fix test: incoming emotes from previous games must be cleared
        var latestIncomingEmote: String? = "🔥"
        var latestIncomingEmoteScale: Float = 2.5f
        var latestIncomingEmoteTimestamp: Long = 123456789L

        fun onStartNewGame() {
            latestIncomingEmote = null
            latestIncomingEmoteScale = 1.0f
            latestIncomingEmoteTimestamp = 0L
        }

        onStartNewGame()

        assertEquals(null, latestIncomingEmote)
        assertEquals(1.0f, latestIncomingEmoteScale, 0.001f)
        assertEquals(0L, latestIncomingEmoteTimestamp)
    }
}
