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
        // Legacy migration no longer exists. Even a slow/unavailable legacy HTTP service
        // must have no effect on a successful empty EMQX lookup. Historical case name retained.
        var httpCalls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            httpCalls++
            throw java.io.IOException("Legacy service unavailable")
        }.build()
        val previous = OnlineRoomRegistry.client
        OnlineRoomRegistry.client = client
        val result = try {
            MqttRoomMetadataTransport(EmqxWireFixture(), { "owned-test-host" }).read("QA4812")
        } finally { OnlineRoomRegistry.client = previous }
        assertEquals(0, httpCalls)
        assertTrue("Valid slow migration must remain a successful empty lookup", result.isSuccess)
        assertNull(result.getOrThrow())
    }
}
