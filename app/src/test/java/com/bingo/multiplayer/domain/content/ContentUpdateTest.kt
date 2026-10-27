package com.bingo.multiplayer.domain.content

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentUpdateTest {
    private class Storage : ContentStorage {
        val values = mutableMapOf<String, ContentCache>()
        val folder = createTempDir(prefix = "bingo-content-")
        var rejectApply = false
        var rejectRollback = false
        override fun load(uid: String) = values[uid] ?: ContentCache()
        override fun save(uid: String, cache: ContentCache) {
            if (rejectApply && cache.active?.pack?.revision == 2L) error("Disk full")
            if (rejectRollback && values[uid]?.active?.pack?.revision == 2L && cache.active?.pack?.revision == 1L) error("Disk full")
            values[uid] = cache
        }
        override fun asset(uid: String, asset: ContentAsset) = File(folder, ContentPacks.audience(uid) + asset.sha256)
        override fun validateAsset(file: File, asset: ContentAsset) = ContentFiles.verified(file, asset)
        override fun cleanup(uid: String, cache: ContentCache) {}
    }
    private class Source : ContentSource {
        val bodies = mutableMapOf<String, String>()
        var failed = false
        var assetBytes = "fixture image".toByteArray()
        var downloads = 0
        var beforeDownload: suspend () -> Unit = {}
        override suspend fun manifest(path: String): String? { if (failed) error("Offline"); return bodies[path] }
        override suspend fun download(asset: ContentAsset, destination: File, progress: (Long) -> Unit) {
            downloads++; beforeDownload(); destination.writeBytes(assetBytes); progress(assetBytes.size.toLong())
        }
    }
    private fun pack(revision: Long = 1, audience: String = "ALL", title: String = "Online Match") = ContentPack(
        revision = revision, audience = audience, strings = mapOf("home.online.title" to title))
    private fun encode(pack: ContentPack) = ContentPacks.json.encodeToString(ContentPack.serializer(), pack)
    private fun Source.publish(pack: ContentPack) { bodies[ContentPacks.path(pack.audience)] = encode(pack) }

    @Test fun packRejectsExecutableFieldsUnknownKeysWrongAudienceAndIncompatibleBuild() {
        val p = pack()
        for (body in listOf(encode(p).dropLast(1) + ",\"script\":\"run()\"}",
            encode(p.copy(strings = mapOf("network.code" to "new rules"))), encode(p.copy(audience = "wrong")),
            encode(p.copy(minBuild = 75)))) {
            assertTrue(runCatching { ContentPacks.parse(body, "ALL", 74) }.isFailure)
        }
        assertEquals(p, ContentPacks.parse(encode(p), "ALL", 74).pack)
    }
    @Test fun homeValuesAreBoundedAndMissingValuesPreserveBuiltInDefaults() {
        assertTrue(runCatching { ContentPacks.parse(encode(pack().copy(homeStyle = ContentHomeStyle(verticalPaddingDp = 500))), "ALL", 74) }.isFailure)
        assertTrue(runCatching { ContentPacks.parse(encode(pack().copy(strings = mapOf("home.online.title" to "bad\ncopy"))), "ALL", 74) }.isFailure)
        val status = ContentUpdateStatus("bob", cache = ContentCache(active = ContentPacks.parse(encode(pack()), "ALL", 74)))
        assertEquals("Original", ContentPacks.text(status, "stuart", "home.online.title", "Original"))
        assertEquals("Original", ContentPacks.text(status, "bob", "home.ai.title", "Original"))
        assertEquals("Online Match", ContentPacks.text(status, "bob", "home.online.title", "Original"))
    }
    @Test fun assetsRequireImmutableTrustedImageUrlsAndBoundedSizes() {
        val hash = ContentPacks.hash("x".toByteArray())
        val asset = ContentAsset(ContentPacks.BASE + "assets/$hash.png", hash, 1)
        assertNotNull(ContentPacks.parse(encode(pack().copy(background = asset)), "ALL", 74))
        for (a in listOf(asset.copy(url = "https://evil.test/$hash.png"), asset.copy(url = ContentPacks.BASE + "assets/$hash.apk"),
            asset.copy(bytes = ContentPacks.MAX_ASSET_BYTES + 1), asset.copy(sha256 = "wrong"))) {
            assertTrue(runCatching { ContentPacks.parse(encode(pack().copy(background = a)), "ALL", 74) }.isFailure)
        }
    }
    @Test fun configurationAppliesOnHomeWithoutPretendingAnAssetDownloaded() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        assertEquals("Online Match", storage.load("bob").active!!.pack.strings["home.online.title"])
        assertEquals(0, source.downloads)
        assertNull(storage.load("bob").staged)
        storage.folder.deleteRecursively()
    }
    @Test fun backgroundDownloadStagesButCannotApplyUntilHomeAndSurvivesRestart() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        val lease = engine.enterHome("bob"); runCurrent(); lease.close(); engine.refresh("bob")
        assertNull(storage.load("bob").active); assertNotNull(storage.load("bob").staged)
        val restarted = ContentUpdateEngine(backgroundScope, storage, source, 74)
        restarted.enterHome("bob"); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertNotNull(storage.load("bob").active); assertNull(storage.load("bob").staged)
        storage.folder.deleteRecursively()
    }
    @Test fun leavingHomeDuringApplyingDefersActivation() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }; var lease: AutoCloseable? = null
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74, pause = { if (it == 500L) lease?.close() })
        lease = engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        assertNull(storage.load("bob").active); assertEquals(ContentStage.READY, engine.status.value.stage)
        storage.folder.deleteRecursively()
    }
    @Test fun targetedUserGetsTheirPackAndOtherAccountsGetPublicContent() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack(title = "Public")); publish(pack(audience = ContentPacks.audience("bob"), title = "Bob preview")) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        assertEquals("Bob preview", storage.load("bob").active!!.pack.strings["home.online.title"])
        engine.enterHome("stuart"); runCurrent(); engine.refresh("stuart")
        assertEquals("Public", engine.status.value.cache.active!!.pack.strings["home.online.title"])
        assertEquals("stuart", engine.status.value.uid)
        storage.folder.deleteRecursively()
    }
    @Test fun delayedOldAccountDownloadCannotExposeOrActivateInTheNewAccount() = runTest {
        val storage = Storage(); val source = Source(); val gate = CompletableDeferred<Unit>()
        val asset = ContentAsset(ContentPacks.BASE + "assets/${ContentPacks.hash(source.assetBytes)}.png", ContentPacks.hash(source.assetBytes), source.assetBytes.size.toLong())
        source.publish(pack(audience = ContentPacks.audience("bob")).copy(background = asset))
        source.beforeDownload = { gate.await() }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); val job = launch { engine.refresh("bob") }; runCurrent()
        engine.enterHome("stuart"); gate.complete(Unit); job.join(); runCurrent()
        assertEquals("stuart", engine.status.value.uid); assertNull(storage.load("bob").active)
        assertNull(engine.status.value.cache.active)
        storage.folder.deleteRecursively()
    }
    @Test fun failedOrCorruptDownloadPreservesActiveContent() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        val hash = ContentPacks.hash("expected".toByteArray())
        source.publish(pack(2).copy(background = ContentAsset(ContentPacks.BASE + "assets/$hash.png", hash, 8)))
        engine.refresh("bob")
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        assertEquals(ContentStage.ERROR, engine.status.value.stage)
        source.failed = true; engine.refresh("bob")
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
    @Test fun rollbackIsDurableAndTheSameBadRevisionCannotImmediatelyReapply() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob"); source.publish(pack(2)); engine.refresh("bob")
        engine.rollback("bob"); engine.refresh("bob")
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        assertEquals(ContentStage.ROLLED_BACK, engine.status.value.stage)
        val restarted = ContentUpdateEngine(backgroundScope, storage, source, 74)
        restarted.enterHome("bob"); runCurrent(); restarted.refresh("bob")
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        source.publish(pack(3)); restarted.refresh("bob")
        assertEquals(3L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
    @Test fun olderAndMutatedSameRevisionCannotUndoANewerPack() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack(2)) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        source.publish(pack(1)); engine.refresh("bob")
        source.publish(pack(2, title = "Mutated")); engine.refresh("bob")
        assertEquals("Online Match", storage.load("bob").active!!.pack.strings["home.online.title"])
        assertEquals(ContentStage.ERROR, engine.status.value.stage)
        storage.folder.deleteRecursively()
    }
    @Test fun aNewInheritanceCommandMovesTheTestUserBackToPublicContent() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack(title = "Public")); publish(pack(audience = ContentPacks.audience("bob"), title = "Test")) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        source.publish(ContentPack(revision = 2, audience = ContentPacks.audience("bob"), inheritGlobal = true))
        engine.refresh("bob")
        assertEquals("Public", storage.load("bob").active!!.pack.strings["home.online.title"])
        source.publish(pack(2, title = "Next public")); engine.refresh("bob")
        assertEquals("Next public", storage.load("bob").active!!.pack.strings["home.online.title"])
        storage.folder.deleteRecursively()
    }
    @Test fun disabledPackRestoresDefaultsAndLocalPausePreventsNewDownloads() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        source.publish(ContentPack(revision = 2, audience = "ALL", enabled = false)); engine.refresh("bob")
        assertFalse(storage.load("bob").active!!.pack.enabled)
        engine.automatic("bob", false); source.publish(pack(3)); engine.refresh("bob")
        assertEquals(2L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
    @Test fun atomicCommitFailureRetainsPreviousAppliedRevisionAndStagedRecovery() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        storage.rejectApply = true; source.publish(pack(2)); engine.refresh("bob")
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        assertEquals(2L, storage.load("bob").staged!!.pack.revision)
        storage.rejectApply = false; engine.enterHome("bob"); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(2L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
    @Test fun cancellingABackgroundTransferClearsBusyStateAndKeepsTheWorkingPack() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        val hash = ContentPacks.hash(source.assetBytes)
        source.publish(pack(2).copy(background = ContentAsset(ContentPacks.BASE + "assets/$hash.png", hash, source.assetBytes.size.toLong())))
        val gate = CompletableDeferred<Unit>(); source.beforeDownload = { gate.await() }
        val job = launch { engine.refresh("bob") }; runCurrent()
        assertEquals(ContentStage.DOWNLOADING, engine.status.value.stage)
        // Re-entering Home during the fetch keeps the already applied appearance.
        engine.enterHome("bob")
        assertEquals(1L, engine.status.value.cache.active!!.pack.revision)
        job.cancelAndJoin(); runCurrent()
        assertNotEquals(ContentStage.DOWNLOADING, engine.status.value.stage)
        assertNotEquals(ContentStage.APPLYING, engine.status.value.stage)
        assertEquals(1L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
    @Test fun failedRollbackReportsAnErrorWithoutLosingTheActiveRevision() = runTest {
        val storage = Storage(); val source = Source().apply { publish(pack()) }
        val engine = ContentUpdateEngine(backgroundScope, storage, source, 74)
        engine.enterHome("bob"); runCurrent(); engine.refresh("bob")
        source.publish(pack(2)); engine.refresh("bob"); storage.rejectRollback = true
        val restored = runCatching { engine.rollback("bob") }
        assertTrue("A failed local rollback must be reported, not silently swallowed by the app wrapper", restored.isSuccess)
        assertEquals(ContentStage.ERROR, engine.status.value.stage)
        assertEquals(2L, storage.load("bob").active!!.pack.revision)
        storage.folder.deleteRecursively()
    }
}
