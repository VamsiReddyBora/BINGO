package com.bingo.multiplayer.domain.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testVersionComparison_HigherRemoteVersion() {
        assertTrue(AppUpdateManager.isNewerVersion("1.3", "1.2"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.3", "v1.2"))
        assertTrue(AppUpdateManager.isNewerVersion("1.3.0", "1.2.9"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0", "1.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("1.10", "1.9"))
    }

    @Test
    fun testVersionComparison_EqualOrLowerRemoteVersion() {
        assertFalse(AppUpdateManager.isNewerVersion("1.2", "1.2"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.2", "v1.2"))
        assertFalse(AppUpdateManager.isNewerVersion("1.1", "1.2"))
        assertFalse(AppUpdateManager.isNewerVersion("1.2", "1.3"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.9", "1.1.0"))
    }

    @Test
    fun testVersionComparison_HandlesPrefixesAndWhitespace() {
        assertTrue(AppUpdateManager.isNewerVersion("  v1.4  ", "1.3"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.3", "  1.3  "))
    }

    @Test
    fun testParseUpdatePayload_VersionJsonFormat() {
        val payload = """
            {
                "versionCode": 34,
                "versionName": "1.3.1",
                "tag_name": "v1.3.1",
                "downloadUrl": "https://vamsireddybora.github.io/BINGO/Bingo.apk",
                "releaseNotes": "Quick bug fix",
                "apkSize": 5497848
            }
        """.trimIndent()

        val info = AppUpdateManager.parseUpdatePayload(payload, "1.3")
        org.junit.Assert.assertNotNull(info)
        assertTrue(info!!.hasUpdate)
        org.junit.Assert.assertEquals("1.3.1", info.latestVersionName)
        org.junit.Assert.assertEquals("https://vamsireddybora.github.io/BINGO/Bingo.apk", info.downloadUrl)

        val sameVersionInfo = AppUpdateManager.parseUpdatePayload(payload, "1.3.1")
        org.junit.Assert.assertNotNull(sameVersionInfo)
        assertFalse(sameVersionInfo!!.hasUpdate)
    }

    @Test
    fun testParseUpdatePayload_GitHubReleaseFormat() {
        val payload = """
            {
                "tag_name": "v1.3.1",
                "body": "Release 1.3.1 notes",
                "assets": [
                    {
                        "name": "Bingo.apk",
                        "browser_download_url": "https://github.com/VamsiReddyBora/BINGO/releases/download/v1.3.1/Bingo.apk",
                        "size": 5500000
                    }
                ]
            }
        """.trimIndent()

        val info = AppUpdateManager.parseUpdatePayload(payload, "1.3")
        org.junit.Assert.assertNotNull(info)
        assertTrue(info!!.hasUpdate)
        org.junit.Assert.assertEquals("1.3.1", info.latestVersionName)
        org.junit.Assert.assertEquals("https://github.com/VamsiReddyBora/BINGO/releases/download/v1.3.1/Bingo.apk", info.downloadUrl)
    }
}
