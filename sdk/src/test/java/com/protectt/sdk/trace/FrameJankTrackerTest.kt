package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameJankTrackerTest {
    @Test
    fun classifiesFrameDurationsIntoNormalSlowFrozen() {
        assertEquals(FrameBucket.NORMAL, classifyFrameBucket(10_000_000L))
        assertEquals(FrameBucket.SLOW, classifyFrameBucket(20_000_000L))
        assertEquals(FrameBucket.FROZEN, classifyFrameBucket(700_000_000L))
    }

    @Test
    fun correlatesJankFramesWithActiveMethodAndStartupCost() {
        val tracker = FrameJankTracker()
        tracker.record(durationNs = 8_000_000L, activeMethodId = "A#fast()V", isStartupWindow = true)
        tracker.record(durationNs = 40_000_000L, activeMethodId = "A#slow()V", isStartupWindow = true)
        tracker.record(durationNs = 800_000_000L, activeMethodId = "A#slow()V", isStartupWindow = false)

        val snapshot = tracker.snapshot()
        assertEquals(3L, snapshot.totalFrames)
        assertEquals(1L, snapshot.normalFrames)
        assertEquals(1L, snapshot.slowFrames)
        assertEquals(1L, snapshot.frozenFrames)
        assertEquals(800_000_000L, snapshot.maxFrameDurationNs)
        assertEquals("A#slow()V", snapshot.hotspots.first().methodId)
        assertEquals(840_000_000L, snapshot.hotspots.first().totalDurationNs)
        assertEquals(40_000_000L, snapshot.hotspots.first().startupOnlyDurationNs)
    }

    @Test
    fun snapshotJsonContainsFrameAndHotspotFields() {
        val tracker = FrameJankTracker()
        tracker.record(durationNs = 40_000_000L, activeMethodId = "A#slow()V", isStartupWindow = true)
        val json = tracker.snapshot().toJson()

        assertTrue(json.contains("\"slowFrames\":1"))
        assertTrue(json.contains("\"methodId\":\"A#slow()V\""))
        assertTrue(json.contains("\"startupOnlyDurationNs\":40000000"))
    }
}
