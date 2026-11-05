package com.bingo.multiplayer.presentation.admin

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class AdminPreciseLatencyGraphTest {
    @Test fun detailGivesATwoMillisecondChangeTwelveDpOfVerticalSeparation() {
        val axis = adminLatencyAxis(listOf(98f, 100f, 102f), true)
        assertEquals(90f, axis.minimum)
        assertEquals(110f, axis.maximum)
        assertEquals(2f, axis.step)
        assertEquals(11, axis.ticks.size)
        assertEquals(12f, adminLatencyY(98f, axis, 120f) - adminLatencyY(100f, axis, 120f), 0.001f)
    }
    @Test fun overviewContainsRealPeaksWhileDetailDoesNotClampThemIntoFalseFlatValues() {
        val detail = adminLatencyAxis(listOf(98f, 99f, 100f, 101f, 6000f), true)
        assertEquals(90f, detail.minimum)
        assertTrue(adminLatencyY(6000f, detail, 120f) < 0)
        val overview = adminLatencyAxis(listOf(98f, 6000f, 6824f), false)
        assertEquals(0f, overview.minimum)
        assertTrue(overview.maximum >= 6824f)
        assertTrue(overview.ticks.size in 3..11)
    }
    @Test fun missingDataAndLowPingHaveFiniteNonnegativeAxes() {
        for (values in listOf(emptyList(), listOf(Float.NaN, Float.POSITIVE_INFINITY), listOf(1f, 2f))) {
            for (detail in listOf(true, false)) {
                val axis = adminLatencyAxis(values, detail)
                assertTrue(axis.minimum >= 0)
                assertTrue(axis.maximum.isFinite() && axis.maximum > axis.minimum)
                assertTrue(axis.ticks.all { it.isFinite() })
            }
        }
    }
    @Test fun samplerPreservesRawTwoMillisecondChangesWithoutSmoothingOrRoundingThemAway() = runTest {
        var input = 100L
        var samples = emptyList<AdminLatencySample>()
        backgroundScope.launch {
            sampleAdminLatency(AdminLatencyHistory(), 1000, 61, { input }, now = { testScheduler.currentTime }) { samples = it }
        }
        runCurrent(); input = 102; advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(100f, 102f), samples.map { it.ms })
    }

    @Test fun sixtySecondWindowUsesRealTimesAndDropsSamplesAcrossAppSleep() = runTest {
        var samples = emptyList<AdminLatencySample>()
        val job = backgroundScope.launch {
            sampleAdminLatency(AdminLatencyHistory(), 1000, 61, { 98 }, now = { testScheduler.currentTime }) { samples = it }
        }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(61, samples.size)
        assertEquals(60_000L, samples.last().at - samples.first().at)
        job.cancel(); runCurrent()
        val window = AdminLatencyHistory()
        window.sample(100, 61, 0)
        val resumed = window.sample(102, 61, 120_000)
        assertEquals(1, resumed.size)
        assertEquals(120_000L, resumed.single().at)
    }
}
