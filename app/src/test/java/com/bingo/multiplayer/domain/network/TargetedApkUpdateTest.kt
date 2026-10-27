package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.content.ContentPacks
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TargetedApkUpdateTest {
    private val sha = "a".repeat(64)
    private fun release(code: Int, version: String = "1.0.3", target: Boolean = false) =
        """{"versionName":"$version","versionCode":$code,"downloadUrl":"${ApkUpdateFeeds.BASE}${if (target) "app-updates/apks/Bingo-$version-build$code-$sha.apk" else "Bingo.apk"}","apkSize":1200,"sha256":"$sha"}"""
    private fun channel(uid: String = "bob-uid", code: Int = 76) =
        """{"schema":1,"revision":1,"audience":"${ContentPacks.audience(uid)}","enabled":true,"release":${release(code, target = true)}}"""
    private fun feeds(target: String? = channel(), global: String? = release(75)) = ApkUpdateFeeds { url ->
        when (url) { ApkUpdateFeeds.targetUrl("bob-uid") -> target; AppUpdateManager.UPDATE_ENDPOINTS.first() -> global; else -> null }
    }
    @Test fun bobGetsThePrivateOfferAndOtherAccountsOnlyGetThePublicBuild() = runTest {
        assertEquals(76L, feeds().resolve("bob-uid", "1.0.3", 75)!!.versionCode)
        val other = feeds().resolve("stuart-uid", "1.0.3", 74)!!
        assertEquals(75L, other.versionCode); assertEquals("ALL", other.audience)
    }
    @Test fun aNewerPublicBuildSupersedesAnOldSelectedAccountOffer() = runTest {
        assertEquals(78L, feeds(global = release(78)).resolve("bob-uid", "1.0.3", 75)!!.versionCode)
    }
    @Test fun withdrawingAnOfferReturnsTheAccountToPublicUpdates() = runTest {
        val disabled = """{"schema":1,"revision":2,"audience":"${ContentPacks.audience("bob-uid")}","enabled":false,"release":null}"""
        assertEquals("ALL", feeds(disabled).resolve("bob-uid", "1.0.3", 74)!!.audience)
    }
    @Test fun missingAndUnavailableTargetFeedsDoNotBreakPublicUpdates() = runTest {
        assertEquals(75L, feeds(null).resolve("bob-uid", "1.0.3", 74)!!.versionCode)
        val feed = ApkUpdateFeeds { url -> if (url.contains("app-updates")) error("network") else release(75) }
        assertEquals(75L, feed.resolve("bob-uid", "1.0.3", 74)!!.versionCode)
    }
    @Test fun wrongAudienceAndInvalidPrivatePackagesAreIgnored() = runTest {
        for (value in listOf(channel("stuart-uid"), channel().replace("\"schema\":1", "\"schema\":2"),
            channel().replace("app-updates/apks/", "untrusted/"), channel().replace(sha, "invalid"))) {
            assertEquals("ALL", feeds(value).resolve("bob-uid", "1.0.3", 74)!!.audience)
        }
    }
    @Test fun knownBuildNumbersNeverOfferAReinstallOrDowngradeEvenWithAHigherVersionLabel() = runTest {
        val result = feeds(target = channel(code = 74), global = release(75, "2.0")).resolve("bob-uid", "1.0.3", 75)!!
        assertFalse(result.hasUpdate)
    }
    @Test fun signedOutClientsNeverRequestAnAccountFeed() = runTest {
        val requested = mutableListOf<String>()
        val info = ApkUpdateFeeds { url -> requested += url; release(76) }.resolve("", "1.0.3", 75)!!
        assertEquals("ALL", info.audience); assertFalse(requested.any { "app-updates/users/" in it })
    }
    @Test fun anOfferResolvedForBobCannotBeDisplayedOrInstalledAfterSwitchingToStuart() = runTest {
        val bob = feeds().resolve("bob-uid", "1.0.3", 75)!!
        assertTrue(ApkUpdateFeeds.allowed(bob, "bob-uid")); assertFalse(ApkUpdateFeeds.allowed(bob, "stuart-uid"))
        assertFalse(ApkUpdateFeeds.allowed(bob, ""))
    }
    @Test fun privateDownloadsNeverFallBackToThePublicApk() = runTest {
        val bob = feeds().resolve("bob-uid", "1.0.3", 75)!!
        assertEquals(listOf(bob.downloadUrl), ApkUpdateFeeds.downloadCandidates(bob))
        assertTrue(ApkUpdateFeeds.downloadCandidates(bob.copy(audience = "ALL")).any { it.endsWith("/Bingo.apk") })
    }
    @Test fun quotedLegacyFallbackAndGithubReleaseMetadataStillWork() = runTest {
        val quoted = org.json.JSONObject.quote(release(76))
        val f = ApkUpdateFeeds { url -> if (url == AppUpdateManager.UPDATE_ENDPOINTS[2]) quoted else null }
        assertEquals(76L, f.resolve("", "1.0.3", 75)!!.versionCode)
        val github = """{"tag_name":"v1.0.4","assets":[{"name":"Bingo.apk","browser_download_url":"https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0.4/Bingo.apk","size":1200}]}"""
        val g = ApkUpdateFeeds { url -> if (url == AppUpdateManager.UPDATE_ENDPOINTS.last()) github else null }
        assertTrue(g.resolve("", "1.0.3", 75)!!.hasUpdate)
    }
    @Test fun aCompleteVerifiedCopyIsTheOnlySuccessfulDownload() {
        val bytes = ByteArray(1200) { 7 }; val file = Files.createTempFile("bingo-apk", ".apk").toFile()
        try { ApkUpdateFiles.copy(ByteArrayInputStream(bytes), file, 1200, ContentPacks.hash(bytes)); assertArrayEquals(bytes, file.readBytes()) }
        finally { file.delete() }
    }
    @Test fun incompleteAndCorruptCopiesAreDeleted() {
        for ((size, expectedHash) in listOf(1201L to sha, 1200L to sha)) {
            val file = Files.createTempFile("bingo-apk", ".apk").toFile()
            assertTrue(runCatching { ApkUpdateFiles.copy(ByteArrayInputStream(ByteArray(1200)), file, size, expectedHash) }.isFailure)
            assertFalse(file.exists())
        }
    }
    @Test fun changingAccountsDuringTheActualCopyDeletesPartialBytes() {
        val file = Files.createTempFile("bingo-apk", ".apk").toFile(); var current = true
        assertTrue(runCatching { ApkUpdateFiles.copy(ByteArrayInputStream(ByteArray(40000)), file, 40000, "",
            stillCurrent = { current }, progress = { current = false }) }.isFailure)
        assertFalse(file.exists())
    }
}
