package com.bingo.multiplayer.presentation.admin

import android.app.Application
import com.bingo.multiplayer.domain.network.CloudLatencyApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudLatencyResponseTest {
    private fun response() = JSONObject("""{
      "schema":1,"source":"cloud-monitor","measurement":"monitor-to-emqx-mqtt-echo",
      "from":100000,"to":300000,"intervalMs":60000,"bucketMs":60000,"startedAt":100000,"lastRecordedAt":220000,
      "points":[
        {"at":120000,"averageMs":98,"peakMs":102,"successfulProbes":3,"failedProbes":0,"observations":1},
        {"at":180000,"averageMs":null,"peakMs":null,"successfulProbes":0,"failedProbes":3,"observations":1},
        {"at":220000,"averageMs":100,"peakMs":103,"successfulProbes":2,"failedProbes":1,"observations":1}
      ]} """)
    private fun rejected(value: JSONObject) {
        try { CloudLatencyApi.parse(value); fail("Invalid or fabricated history accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun realCloudAveragePeaksAndOutagesRemainDistinct() {
        val history = CloudLatencyApi.parse(response())
        assertEquals(3, history.points.size)
        assertEquals(98f, history.points[0].averageMs)
        assertEquals(102f, history.points[0].peakMs)
        assertNull(history.points[1].averageMs)
        assertEquals(3, history.points[1].failedProbes)
        assertEquals(2, history.points[2].successfulProbes)
    }
    @Test fun localOrSimulatedHistoryCannotMasqueradeAsMonitorData() {
        rejected(response().put("source", "phone"))
        rejected(response().put("measurement", "preview"))
        rejected(response().put("intervalMs", 100))
    }
    @Test fun malformedOrderPeaksOrProbeCountsAreRejected() {
        response().also { it.getJSONArray("points").getJSONObject(1).put("at", 110000); rejected(it) }
        response().also { it.getJSONArray("points").getJSONObject(0).put("peakMs", 2); rejected(it) }
        response().also { it.getJSONArray("points").getJSONObject(0).put("successfulProbes", 4); rejected(it) }
        response().also { it.getJSONArray("points").getJSONObject(1).put("averageMs", 0); rejected(it) }
    }
    @Test fun noRecordsMeansAnEmptyHistoryNotFabricatedSamples() {
        val json = response().put("points", org.json.JSONArray()).put("lastRecordedAt", JSONObject.NULL)
        val history = CloudLatencyApi.parse(json)
        assertTrue(history.points.isEmpty())
        assertNull(history.lastRecordedAt)
    }
}
