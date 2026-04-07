package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupProfilerTest {
    @Test
    fun capturesStartupPhasesAndSortsMilestonesByElapsedTime() {
        val profiler = StartupProfiler(
            processStartMs = 1_000L,
            startupWindowMsProvider = { 500L },
        )

        profiler.mark(StartupPhases.APPLICATION_ON_CREATE_END, nowMs = 1_080L)
        profiler.mark(StartupPhases.APPLICATION_ON_CREATE_START, nowMs = 1_020L)
        profiler.mark(StartupPhases.FIRST_ACTIVITY_CREATED, nowMs = 1_130L)
        profiler.mark(StartupPhases.FIRST_FRAME_PROXY, nowMs = 1_170L)

        val markers = profiler.summary().markers
        assertEquals(StartupPhases.PROCESS_START_PROXY, markers[0].name)
        assertEquals(StartupPhases.APPLICATION_ON_CREATE_START, markers[1].name)
        assertEquals(StartupPhases.APPLICATION_ON_CREATE_END, markers[2].name)
        assertEquals(StartupPhases.FIRST_ACTIVITY_CREATED, markers[3].name)
        assertEquals(StartupPhases.FIRST_FRAME_PROXY, markers[4].name)
    }

    @Test
    fun computesStartupMetricsAndSdkContribution() {
        val profiler = StartupProfiler(
            processStartMs = 10_000L,
            startupWindowMsProvider = { 1_000L },
        )

        profiler.mark(StartupPhases.APPLICATION_ON_CREATE_START, nowMs = 10_020L)
        profiler.mark(StartupPhases.SDK_INIT_START, nowMs = 10_040L)
        profiler.mark(StartupPhases.SDK_INIT_END, nowMs = 10_090L)
        profiler.mark(StartupPhases.FIRST_FRAME_PROXY, nowMs = 10_200L)

        val summary = profiler.summary()
        assertEquals(200L, summary.startupDurationMs)
        assertEquals(200L, summary.firstFrameLatencyMs)
        assertEquals(50L, summary.sdkInitDurationMs)
        assertEquals(25.0, summary.sdkContributionPercent, 0.001)
    }

    @Test
    fun flagsSlowStartupUsingStartupWindowThreshold() {
        val profiler = StartupProfiler(
            processStartMs = 0L,
            startupWindowMsProvider = { 100L },
        )

        profiler.mark(StartupPhases.FIRST_FRAME_PROXY, nowMs = 180L)

        val summary = profiler.summary()
        assertTrue(summary.slowStartup)
        assertEquals(100L, summary.slowThresholdMs)
    }

    @Test
    fun startupSummaryJsonContainsStructuredReportFields() {
        val profiler = StartupProfiler(
            processStartMs = 5_000L,
            startupWindowMsProvider = { 400L },
        )

        profiler.mark(StartupPhases.APPLICATION_ON_CREATE_START, nowMs = 5_010L)
        profiler.mark(StartupPhases.SDK_INIT_START, nowMs = 5_020L)
        profiler.mark(StartupPhases.SDK_INIT_END, nowMs = 5_060L)
        profiler.mark(StartupPhases.FIRST_FRAME_PROXY, nowMs = 5_300L)

        val json = profiler.summary().toJson()

        assertTrue(json.contains("\"startupDurationMs\":300"))
        assertTrue(json.contains("\"firstFrameLatencyMs\":300"))
        assertTrue(json.contains("\"sdkInitDurationMs\":40"))
        assertTrue(json.contains("\"slowStartup\":false"))
        assertTrue(json.contains("\"phase\"" ).not())
        assertTrue(json.contains("\"markers\""))
        assertTrue(json.contains("\"name\":\"first_frame_proxy\""))
    }
}
