package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MethodAggregateTrackerTest {
    @Test
    fun recordsAggregateCountAndTotals() {
        val tracker = MethodAggregateTracker(percentileSampleSize = 64, rng = Random(1L))

        tracker.record("A#m()V", durationNs = 10, selfNs = 7, isMainThread = false)
        tracker.record("A#m()V", durationNs = 20, selfNs = 18, isMainThread = true)

        val method = tracker.snapshot(nowMs = 123L).methods.single { it.methodId == "A#m()V" }
        assertEquals(2L, method.callCount)
        assertEquals(30L, method.totalNs)
        assertEquals(25L, method.selfTotalNs)
        assertEquals(20L, method.mainThreadTotalNs)
    }

    @Test
    fun recordsMinAndMaxDuration() {
        val tracker = MethodAggregateTracker(percentileSampleSize = 64, rng = Random(1L))

        tracker.record("A#m()V", durationNs = 90, selfNs = 90, isMainThread = false)
        tracker.record("A#m()V", durationNs = 20, selfNs = 20, isMainThread = false)
        tracker.record("A#m()V", durationNs = 50, selfNs = 50, isMainThread = false)

        val method = tracker.snapshot(nowMs = 123L).methods.single { it.methodId == "A#m()V" }
        assertEquals(20L, method.minNs)
        assertEquals(90L, method.maxNs)
    }

    @Test
    fun percentileEstimationStaysInExpectedRange() {
        val tracker = MethodAggregateTracker(percentileSampleSize = 32, rng = Random(7L))
        for (value in 1L..100L) {
            tracker.record("A#m()V", durationNs = value, selfNs = value, isMainThread = false)
        }

        val method = tracker.snapshot(nowMs = 123L).methods.single { it.methodId == "A#m()V" }
        assertTrue(method.p50Ns in 25L..75L)
        assertTrue(method.p95Ns >= method.p50Ns)
        assertTrue(method.p99Ns >= method.p95Ns)
    }

    @Test
    fun nestedCallSelfTimeIsApproximatedBySubtractingChildDuration() {
        assertEquals(50L, approximateSelfNs(durationNs = 100L, childDurationNs = 50L))
        assertEquals(0L, approximateSelfNs(durationNs = 20L, childDurationNs = 35L))
    }

    @Test
    fun summarySnapshotJsonFormatContainsExpectedFields() {
        val tracker = MethodAggregateTracker(percentileSampleSize = 64, rng = Random(1L))
        tracker.record("A#m()V", durationNs = 42, selfNs = 40, isMainThread = true)

        val json = tracker.snapshot(nowMs = 999L).toJson()

        assertTrue(json.contains("\"generatedAtEpochMs\":999"))
        assertTrue(json.contains("\"methodId\":\"A#m()V\""))
        assertTrue(json.contains("\"callCount\":1"))
        assertTrue(json.contains("\"p95Ns\""))
        assertTrue(json.contains("\"mainThreadTotalNs\":42"))
    }
}
