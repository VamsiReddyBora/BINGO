package com.bingo.multiplayer.presentation.admin

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AdminTelemetrySamplingTest {
    @Test fun samplesAtSelectedCadenceEvenWhenPingIsUnchanged() = runTest {
        val emitted = mutableListOf<List<AdminLatencySample>>()
        val job = backgroundScope.launch {
            sampleAdminLatency(AdminLatencyHistory(), 250L, 8, { 125L }) { emitted.add(it) }
        }
        runCurrent()
        assertEquals(1, emitted.size)
        advanceTimeBy(249L); runCurrent()
        assertEquals(1, emitted.size)
        advanceTimeBy(1L); runCurrent()
        assertEquals(listOf(125f, 125f), emitted.last().map { it.ms })
        advanceTimeBy(500L); runCurrent()
        assertEquals(4, emitted.size)
        job.cancel(); runCurrent()
        advanceTimeBy(1000L); runCurrent()
        assertEquals("Cancelled sampler must stop emitting", 4, emitted.size)
    }

    @Test fun sampleUsesLatestPingAndMarksMissingMeasurementsAsGaps() = runTest {
        var ping = 140L
        var last = emptyList<AdminLatencySample>()
        backgroundScope.launch { sampleAdminLatency(AdminLatencyHistory(), 100L, 8, { ping }) { last = it } }
        runCurrent()
        ping = 700L; advanceTimeBy(100L); runCurrent()
        ping = 0L; advanceTimeBy(100L); runCurrent()
        assertEquals(listOf(140f, 700f, null), last.map { it.ms })
    }

    @Test fun historyIsBoundedAndPreservesRealValuesAboveOldEightyMillisecondLimit() {
        val history = AdminLatencyHistory()
        repeat(20) { history.sample(it.toLong() + 100, 16) }
        val reduced = history.sample(999, 8)
        assertEquals(8, reduced.size)
        assertEquals(999f, reduced.last().ms)
        assertEquals(0.5f, adminLatencyFraction(100f, 200f))
        assertEquals(1f, adminLatencyFraction(999f, 200f))
    }

    @Test fun topicColumnChangesRetainAllFiveCategoriesAndTheirOrder() {
        val partitions = (1..5).map { AdminTopicPartition("", "Topic $it", "topic/$it", it) }
        for (columns in 1..3) {
            val rows = adminTopicRows(partitions, columns)
            assertEquals(partitions, rows.flatten())
            assertTrue(rows.all { it.size <= columns })
        }
        assertEquals(0f, adminTopicShare(4, 0))
        assertEquals(0.25f, adminTopicShare(5, 20))
        assertEquals(1f, adminTopicShare(25, 20))
    }
}
