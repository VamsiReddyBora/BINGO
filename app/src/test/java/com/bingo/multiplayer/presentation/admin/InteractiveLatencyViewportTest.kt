package com.bingo.multiplayer.presentation.admin

import com.bingo.multiplayer.domain.network.CLOUD_HISTORY_RETENTION_MS
import com.bingo.multiplayer.domain.network.CloudLatencyPoint
import org.junit.Assert.*
import org.junit.Test

class InteractiveLatencyViewportTest {
    private val now = 1_800_000_000_000L
    private fun point(at: Long, average: Float? = 100f, peak: Float? = average, observations: Int = 1) =
        CloudLatencyPoint(at, average, peak, if (average == null) 0 else observations * 3,
            if (average == null) observations * 3 else 0, observations)
    private fun hour() = defaultLatencyViewport(now).copy(start = now - 3_600_000)

    @Test fun resetIsExactlyOneMinuteAndZeroToTwoHundredFiftyMilliseconds() {
        val view = defaultLatencyViewport(now)
        assertEquals(60_000L, view.duration)
        assertEquals(0f, view.minimum); assertEquals(250f, view.maximum)
        assertTrue(view.following); assertTrue(view.autoScale)
        assertEquals(listOf(0f, 50f, 100f, 150f, 200f, 250f), view.axis.ticks)
    }
    @Test fun minuteAxisHasEverySecondAndReadableTenSecondLabels() {
        val view = defaultLatencyViewport(now)
        val ticks = latencySecondTicks(view)
        assertEquals(61, ticks.size)
        assertTrue(ticks.zipWithNext().all { (a,b) -> b.at - a.at == 1000L })
        assertEquals(view.start, ticks.first().at); assertEquals(view.end, ticks.last().at)
        assertEquals(listOf("0s", "10s", "20s", "30s", "40s", "50s", "60s"), ticks.mapNotNull { it.label })
        assertTrue(latencySecondTicks(hour()).isEmpty())
    }
    @Test fun horizontalZoomKeepsTheTimeCentreAndDoesNotMoveTheLatencyAxis() {
        val view = hour().copy(minimum = 90f, maximum = 110f, autoScale = false)
        val zoom = transformLatencyViewport(view, now, zoomX = 2f)
        assertEquals(view.duration / 2, zoom.duration)
        assertEquals(view.start + view.duration / 2, zoom.start + zoom.duration / 2)
        assertEquals(view.minimum, zoom.minimum); assertEquals(view.maximum, zoom.maximum)
        assertFalse(zoom.following)
    }
    @Test fun verticalZoomKeepsTimeAndCentresOnTheActualTrace() {
        val view = hour()
        val points = listOf(point(now - 120_000, 70f, 9000f))
        val zoom = transformLatencyViewport(view, now + 20_000, zoomY = 2f, points = points)
        assertEquals(view.start, zoom.start); assertEquals(view.end, zoom.end)
        assertTrue(zoom.maximum - zoom.minimum < 250f)
        assertEquals(70f, (zoom.maximum + zoom.minimum) / 2, 12.5f)
        assertTrue(70f in zoom.minimum..zoom.maximum)
        assertFalse(zoom.autoScale); assertTrue(zoom.following)
    }
    @Test fun twoMillisecondChangeRemainsVisibleAndRefreshKeepsTheChosenZoomCentred() {
        val view = hour().copy(minimum = 80f, maximum = 120f)
        val zoom = transformLatencyViewport(view, now, zoomY = 2f, points = listOf(point(now, 100f)))
        assertEquals(20f, zoom.maximum - zoom.minimum, .001f)
        assertEquals(12f, 120f * 2 / (zoom.maximum - zoom.minimum), .001f)
        val refreshed = fitLatencyValues(zoom, listOf(point(now, 6000f, 6824f)))
        assertTrue(6000f in refreshed.minimum..refreshed.maximum)
        assertEquals(20f, refreshed.maximum - refreshed.minimum, .001f)
    }
    @Test fun smallPinchDeltasAccumulateInsteadOfGettingStuckAtRoundedScaleSteps() {
        var view = hour()
        val points = listOf(point(now, 100f))
        repeat(110) { view = transformLatencyViewport(view, now, zoomY = 1.05f, points = points) }
        assertEquals(2f, view.maximum - view.minimum, .001f)
        assertTrue(100f in view.minimum..view.maximum)
        repeat(110) { view = transformLatencyViewport(view, now, zoomY = 1 / 1.05f, points = points) }
        assertTrue(view.maximum - view.minimum > 250f)
    }
    @Test fun panningStopsAtRecordingStartAndLatestEdge() {
        val view = hour()
        val recordedFrom = now - 7_200_000
        val past = transformLatencyViewport(view, now, panX = 2f, recordedFrom = recordedFrom)
        assertEquals(recordedFrom, past.start); assertFalse(past.following)
        val latest = transformLatencyViewport(past, now, panX = -10f, recordedFrom = recordedFrom)
        assertEquals(now, latest.end); assertTrue(latest.following)
    }
    @Test fun zoomOutCannotHideTheDataInThirtyDaysOfUnrecordedTime() {
        val recordedFrom = now - 7_200_000
        val zoom = transformLatencyViewport(hour(), now, zoomX = .001f, recordedFrom = recordedFrom)
        assertEquals(recordedFrom, zoom.start); assertEquals(now, zoom.end)
        assertEquals(7_200_000L, zoom.duration)
        val fresh = boundLatencyTime(now - 3_600_000, 3_600_000, now, now - 1000)
        assertEquals(now - 60_000 to now, fresh)
    }
    @Test fun unknownRecordingStartStillHasFiniteRetentionAndLatencyLimits() {
        var view = hour()
        repeat(30) { view = transformLatencyViewport(view, now, zoomX = 10f, zoomY = 10f) }
        assertEquals(60_000L, view.duration)
        assertEquals(2f, view.maximum - view.minimum, .001f)
        repeat(30) { view = transformLatencyViewport(view, now, zoomX = .1f, zoomY = .1f) }
        assertEquals(CLOUD_HISTORY_RETENTION_MS, view.duration)
        assertEquals(120_000f, view.maximum - view.minimum, .1f)
        assertTrue(view.minimum >= 0f); assertTrue(view.maximum.isFinite())
        assertTrue(view.axis.ticks.size in 2..12)
    }
    @Test fun invalidScaleInputCannotCorruptTheViewport() {
        val view = defaultLatencyViewport(now)
        assertEquals(view, transformLatencyViewport(view, now, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(view, transformLatencyViewport(view, now, -1f, 0f))
    }
    @Test fun tapChoosesAnActualObservationAndNeverAConnectingLineValue() {
        val view = hour()
        val a = point(view.start + 120_000, 98f, 105f)
        val b = point(view.start + 600_000, 102f, 109f)
        assertSame(b, nearestLatencyPoint(listOf(a,b), view, .15f))
        assertSame(a, nearestLatencyPoint(listOf(a,b), view, 0f))
        assertNull(nearestLatencyPoint(listOf(point(view.start - 1)), view, .5f))
    }
    @Test fun failedChecksRemainUnavailableAndAggregatesKeepRealPeaks() {
        val view = hour()
        val outage = point(view.start + 120_000, null, null)
        val aggregate = point(view.end - 120_000, 100.33f, 501f, 30)
        assertNull(nearestLatencyPoint(listOf(outage,aggregate), view, 0f)!!.averageMs)
        val selected = nearestLatencyPoint(listOf(outage,aggregate), view, 1f)!!
        assertEquals(100.33f, selected.averageMs!!, .001f); assertEquals(501f, selected.peakMs!!, .001f)
        assertEquals(30, selected.observations)
    }
    @Test fun autoScaleKeepsResetRangeForNormalValuesAndIgnoresUnplottedPeaks() {
        val view = defaultLatencyViewport(now)
        assertEquals(view, fitLatencyValues(view, listOf(point(now, 70f, 9000f))))
        assertEquals(view, fitLatencyValues(view, listOf(point(now, null, null))))
    }
    @Test fun autoScaleExpandsToCleanBoundsForHighActualReadings() {
        val view = defaultLatencyViewport(now)
        val fitted = fitLatencyValues(view, listOf(point(now, 6824f)))
        assertEquals(0f, fitted.minimum); assertTrue(fitted.maximum > 6824f)
        assertTrue(fitted.axis.ticks.size in 3..11)
        assertTrue(fitted.axis.ticks.all { it % fitted.axis.step == 0f })
    }
    @Test fun manualScaleExpandsToKeepTheWholeVisibleTraceInsideThePlot() {
        val view = hour().copy(minimum = 68f, maximum = 70f, autoScale = false)
        val fitted = fitLatencyValues(view, listOf(point(now - 60_000, 70f), point(now, 400f)))
        assertTrue(70f in fitted.minimum..fitted.maximum); assertTrue(400f in fitted.minimum..fitted.maximum)
        assertTrue(fitted.axis.ticks.all { it in fitted.minimum..fitted.maximum })
    }
    @Test fun continuousTraceJoinsRecordedValuesAcrossFailuresAndMissingChecks() {
        val view = hour()
        val a = point(view.start + 120_000, 98f)
        val failed = point(view.start + 180_000, null, null)
        val b = point(view.end - 120_000, 102f)
        assertEquals(listOf(a,b), latencyTracePoints(view,listOf(a,failed,b)))
        assertSame(failed, nearestLatencyPoint(listOf(a,failed,b),view,180_000f/view.duration))
    }
    @Test fun connectingSegmentsCanEnterAndLeaveTheViewportWithoutInventingDots() {
        val view = defaultLatencyViewport(now)
        val before = point(view.start - 60_000, 98f)
        val after = point(view.end + 60_000, 102f)
        assertEquals(listOf(before,after),latencyTracePoints(view,listOf(before,after)))
        assertNull(nearestLatencyPoint(listOf(before,after),view,.5f))
        assertTrue(latencyTracePoints(view,listOf(before)).isEmpty())
        assertTrue(latencyTracePoints(view,listOf(after)).isEmpty())
    }
    @Test fun fetchOverscanRemainsInsideCloudRetentionAndNeverRequestsFutureHistory() {
        val view = defaultLatencyViewport(now)
        val range = latencyFetchRange(view,now)
        assertEquals(view.start - 180_000,range.first); assertEquals(now,range.second)
        val whole = view.copy(start = now - CLOUD_HISTORY_RETENTION_MS,end=now)
        assertEquals(whole.start to whole.end,latencyFetchRange(whole,now))
    }
}
