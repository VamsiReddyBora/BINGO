package com.bingo.multiplayer.domain.network

import org.junit.Assert.*
import org.junit.Test

class Version102UpdateFeedTest {
    private val feed = """{"versionName":"1.0.2","versionCode":67,"tagName":"v1.0.2","downloadUrl":"https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0.2/Bingo-1.0.2.apk","apkSize":8000000}"""
    @Test fun previousReleaseReceivesTheVersion102Update() {
        val update = AppUpdateManager.parseUpdatePayload(feed, "1.0", 65)!!
        assertTrue(update.hasUpdate)
        assertEquals("1.0.2", update.latestVersionName)
        assertTrue(update.downloadUrl.endsWith("/v1.0.2/Bingo-1.0.2.apk"))
    }
    @Test fun version101ReceivesTheVersion102Update() {
        assertTrue(AppUpdateManager.parseUpdatePayload(feed, "1.0.1", 66)!!.hasUpdate)
    }
    @Test fun version102DoesNotPromptForItself() {
        assertFalse(AppUpdateManager.parseUpdatePayload(feed, "1.0.2", 67)!!.hasUpdate)
    }
    @Test fun theGitHubLatestReleaseFallbackAlsoDetectsVersion102() {
        val payload = """{"tag_name":"v1.0.2","name":"BINGO v1.0.2 — Build 67","assets":[{"name":"Bingo-1.0.2.apk","browser_download_url":"https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0.2/Bingo-1.0.2.apk","size":8000000}]}"""
        assertTrue(AppUpdateManager.parseUpdatePayload(payload, "1.0", 65)!!.hasUpdate)
    }
}
