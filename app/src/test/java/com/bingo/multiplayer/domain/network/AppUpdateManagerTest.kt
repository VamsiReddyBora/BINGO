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
}
