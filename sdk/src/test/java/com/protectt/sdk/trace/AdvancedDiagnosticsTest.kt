package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedDiagnosticsTest {
    @Test
    fun computesExplainableRiskFromExistingSignals() {
        val startup = StartupSummary(
            startupDurationMs = 2_200L,
            firstFrameLatencyMs = 2_200L,
            sdkInitDurationMs = 250L,
            sdkContributionPercent = 11.4,
            slowStartup = true,
            slowThresholdMs = 1_000L,
            markers = emptyList(),
        )
        val frameSummary = FrameSummarySnapshot(
            totalFrames = 100L,
            normalFrames = 80L,
            slowFrames = 15L,
            frozenFrames = 5L,
            maxFrameDurationNs = 1_000_000_000L,
            hotspots = emptyList(),
        )
        val methodSummary = MethodAggregateTracker.SummarySnapshot(
            generatedAtEpochMs = 1L,
            methods = listOf(
                MethodAggregateTracker.MethodSummary(
                    methodId = "A#doWork",
                    callCount = 10L,
                    totalNs = 3_000_000_000L,
                    maxNs = 2_000_000_000L,
                    minNs = 100L,
                    p50Ns = 100L,
                    p95Ns = 200L,
                    p99Ns = 300L,
                    selfTotalNs = 100L,
                    mainThreadTotalNs = 2_500_000_000L,
                    startupTotalNs = 100L,
                ),
            ),
        )
        val stalls = StallSummarySnapshot(
            totalStalls = 5L,
            warningCount = 2L,
            elevatedCount = 2L,
            criticalCount = 1L,
            maxDurationMs = 900L,
        )

        val result = AnrRiskScorer.compute(startup, frameSummary, methodSummary, stalls)

        assertTrue(result.score >= 70)
        assertEquals("high", result.level)
        assertEquals(4, result.factors.size)
        assertTrue(result.toJson().contains("\"factors\""))
        assertTrue(result.toJson().contains("\"main_thread_stalls\""))
    }

    @Test
    fun stallTrackerAggregatesSeverityBuckets() {
        val tracker = MainThreadStallTracker()
        tracker.record(MainThreadStallEvent(110L, MainThreadStallSeverity.WARNING, null))
        tracker.record(MainThreadStallEvent(300L, MainThreadStallSeverity.ELEVATED, null))
        tracker.record(MainThreadStallEvent(700L, MainThreadStallSeverity.CRITICAL, null))

        val snapshot = tracker.snapshot()
        assertEquals(3L, snapshot.totalStalls)
        assertEquals(1L, snapshot.warningCount)
        assertEquals(1L, snapshot.elevatedCount)
        assertEquals(1L, snapshot.criticalCount)
        assertEquals(700L, snapshot.maxDurationMs)
    }
}
