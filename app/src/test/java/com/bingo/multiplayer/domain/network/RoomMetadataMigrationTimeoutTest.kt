package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RoomMetadataMigrationTimeoutTest {
    @Test
    fun validSlowLegacyLookupIsNotMisclassifiedAsMetadataOutage() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            // A cold legacy lookup can exceed the old native six-second deadline.
            Thread.sleep(6_500L)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Valid slow discovery")
                .body("{\"payload\":null,\"revision\":0,\"closed\":false}".toResponseBody()).build()
        }.build()
        val result = HttpRoomMetadataTransport(client, "https://controlled.invalid") { "unused" }.read("QA4812")
        assertTrue("Valid slow migration must remain a successful empty lookup", result.isSuccess)
        assertNull(result.getOrThrow())
    }
}
