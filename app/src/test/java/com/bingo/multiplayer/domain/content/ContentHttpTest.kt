package com.bingo.multiplayer.domain.content

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Actual production HTTP adapter, bounded file copy and checksum; only the HTTP response is substituted. */
class ContentHttpTest {
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XcAAAAASUVORK5CYII=")
    private fun source(bytes: ByteArray, code: Int = 200) = HttpContentSource(OkHttpClient.Builder()
        .followRedirects(false).addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").header("Content-Type", "image/png").body(bytes.toResponseBody()).build() }.build())
    @Test fun realCopyVerifiesTheImageAndLeavesNoPartialFile() = runBlocking {
        val hash = ContentPacks.hash(png)
        val asset = ContentAsset(ContentPacks.BASE + "assets/$hash.png", hash, png.size.toLong())
        val dir = createTempDir(prefix = "content-http-"); val destination = File(dir, "$hash.png")
        var read = 0L; source(png).download(asset, destination) { read = it }
        assertEquals(asset.bytes, read); assertTrue(ContentFiles.verified(destination, asset))
        assertFalse(File(destination.path + ".part").exists()); dir.deleteRecursively()
        Unit
    }
    @Test fun partialCorruptAndRedirectedResponsesNeverBecomeAnAppliedAsset() = runBlocking {
        val hash = ContentPacks.hash(png); val asset = ContentAsset(ContentPacks.BASE + "assets/$hash.png", hash, png.size.toLong())
        val dir = createTempDir(prefix = "content-http-bad-"); val destination = File(dir, "$hash.png")
        for (bad in listOf(source(png.copyOf(png.size - 1)), source(png.copyOf().also { it[10] = 1 }), source(png, 302))) {
            assertTrue(runCatching { bad.download(asset, destination) {} }.isFailure)
            assertFalse(destination.exists()); assertFalse(File(destination.path + ".part").exists())
        }
        dir.deleteRecursively(); Unit
    }
    @Test fun actualManifestReadDistinguishesMissingFromFailedOrOversizedSources() = runBlocking {
        assertNull(source(png, 404).manifest("index.json"))
        assertTrue(runCatching { source(png, 503).manifest("index.json") }.isFailure)
        assertTrue(runCatching { source(ByteArray(ContentPacks.MAX_MANIFEST_BYTES + 1)).manifest("index.json") }.isFailure)
        assertTrue(runCatching { source(png).manifest("../other.json") }.isFailure)
    }
    @Test fun androidAcceptsTheSameManifestFixtureAsThePublisher() {
        val fixture = listOf(File("../admin_service/test/content-contract.json"), File("admin_service/test/content-contract.json")).first { it.exists() }
        val pack = ContentPacks.parse(fixture.readText(), "ALL", 74)
        assertEquals(12, pack.pack.homeStyle.verticalPaddingDp)
        assertEquals("Host or join a room with friends.", pack.pack.strings["home.online.description"])
    }
}
