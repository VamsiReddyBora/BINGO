package com.bingo.multiplayer.domain.network

import org.junit.Assert.*
import org.junit.Test

class Version101UpdateFeedTest {
    private val feed = """{"versionName":"1.0.1","versionCode":66,"tagName":"v1.0.1","downloadUrl":"https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0.1/Bingo-1.0.1.apk","apkSize":8000000}"""
    @Test fun previousReleaseReceivesTheVersion101Update() {
        val update = AppUpdateManager.parseUpdatePayload(feed, "1.0", 65)!!
        assertTrue(update.hasUpdate)
        assertEquals("1.0.1", update.latestVersionName)
        assertTrue(update.downloadUrl.endsWith("/v1.0.1/Bingo-1.0.1.apk"))
    }
    @Test fun version101DoesNotPromptForItself() {
        assertFalse(AppUpdateManager.parseUpdatePayload(feed, "1.0.1", 66)!!.hasUpdate)
    }
    @Test fun theGitHubLatestReleaseFallbackAlsoDetectsVersion101() {
        val payload = """{"tag_name":"v1.0.1","name":"BINGO v1.0.1 — Build 66","assets":[{"name":"Bingo-1.0.1.apk","browser_download_url":"https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0.1/Bingo-1.0.1.apk","size":8000000}]}"""
        assertTrue(AppUpdateManager.parseUpdatePayload(payload, "1.0", 65)!!.hasUpdate)
    }
}
