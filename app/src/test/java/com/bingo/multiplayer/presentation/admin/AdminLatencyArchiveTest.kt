package com.bingo.multiplayer.presentation.admin

import android.app.Application
import android.content.Context
import com.bingo.multiplayer.domain.network.LatencyArchive
import com.bingo.multiplayer.domain.network.LATENCY_RETENTION_MS
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdminLatencyArchiveTest {
    @Test fun measurementsPersistAcrossReopenAndRetainPeaksWithoutInventingMissingIntervals() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("latency-test.db")
        val now = System.currentTimeMillis() / 10_000 * 10_000
        LatencyArchive(context, "latency-test.db").use { db ->
            db.record(now - 50_000, 100, now)
            db.record(now - 49_000, 700, now)
            db.record(now - 10_000, 90, now)
            db.record(now - 20_000, 0, now)
        }
        LatencyArchive(context, "latency-test.db").use { db ->
            val points = db.read(now - 60_000, now, now)
            assertEquals(2, points.size)
            assertEquals(700f, points[0].ms)
            assertEquals(90f, points[1].ms)
            assertEquals(40_000L, points[1].at - points[0].at)
        }
        context.deleteDatabase("latency-test.db")
    }

    @Test fun retentionAndRenderingQueriesAreBoundedAndUnknownHistoryIsEmpty() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("latency-bounds.db")
        val now = System.currentTimeMillis() / 10_000 * 10_000
        LatencyArchive(context, "latency-bounds.db").use { db ->
            assertTrue(db.read(now - 3_600_000, now, now).isEmpty())
            db.record(now - LATENCY_RETENTION_MS - 1, 500, now)
            repeat(1000) { db.record(now - it * 10_000, 1L + it, now) }
            val points = db.read(now - 86_400_000, now, now)
            assertTrue(points.size <= 241)
            assertEquals(1000f, points.maxOf { it.ms })
            assertTrue(db.read(now - LATENCY_RETENTION_MS, now, now).none { it.at < now - LATENCY_RETENTION_MS })
        }
        context.deleteDatabase("latency-bounds.db")
    }
}
