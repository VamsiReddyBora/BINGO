package com.bingo.multiplayer.presentation.admin

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdminPermanentLayoutTest {
    private fun approved() = JSONObject(javaClass.getResourceAsStream("/admin-approved-build134.json")!!
        .bufferedReader().use { it.readText() })

    private fun assertApprovedLayout() {
        val expected = approved()
        assertEquals(87, expected.length())
        for (key in expected.keys()) {
            val field = AdminDashboardLayout.javaClass.getDeclaredField(key).apply { isAccessible = true }
            when (val value = field.get(AdminDashboardLayout)) {
                is Float -> assertEquals(key, expected.getDouble(key).toFloat(), value)
                is Int -> assertEquals(key, expected.getInt(key), value)
                is Boolean -> assertEquals(key, expected.getBoolean(key), value)
                is String -> assertEquals(key, expected.getString(key), value)
                else -> fail("Unexpected final value type for $key")
            }
        }
    }

    @Test fun allEightySevenValuesExactlyMatchTheFinalOwnerConfiguration() {
        assertApprovedLayout()
    }

    @Test fun oldTuningPreferencesCannotOverrideThePermanentLayout() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("bingo_admin_ui_adjustments_v1", Context.MODE_PRIVATE)
        val stale = approved().put("graphSampleIntervalMs", 5000).put("graphHistorySamples", 128)
            .put("graphLineColorHex", "#000000").put("topicColumns", 1)
            .put("waveformHeightDp", 180).put("showSpeedometer", false).toString()
        prefs.edit().putString("admin_ui_config_json", stale).commit()
        assertApprovedLayout()
        assertEquals("Finalisation must not erase historical preferences", stale, prefs.getString("admin_ui_config_json", null))
    }

    @Test fun finalGraphRunsAtOneSecondWithSixtyOneSamples() = runTest {
        var samples = emptyList<AdminLatencySample>()
        var emissions = 0
        backgroundScope.launch {
            sampleAdminLatency(AdminLatencyHistory(), AdminDashboardLayout.graphSampleIntervalMs.toLong(),
                AdminDashboardLayout.graphHistorySamples, { 115L }) { samples = it; emissions++ }
        }
        runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(1, emissions)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, emissions)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(61, samples.size)
        assertTrue(samples.all { it.ms == 115f })
    }
}
