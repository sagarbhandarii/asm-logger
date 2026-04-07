package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HotspotReportEnhancerTest {
    @Test
    fun `report ranking orders hotspots by expected metrics`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "A#fast()V",
                "totalNs" to 100L,
                "p95Ns" to 30L,
                "p99Ns" to 50L,
                "maxNs" to 70L,
                "mainThreadTotalNs" to 10L,
                "startupTotalNs" to 5L,
            ),
            mutableMapOf<String, Any?>(
                "methodId" to "A#slow()V",
                "totalNs" to 300L,
                "p95Ns" to 250L,
                "p99Ns" to 280L,
                "maxNs" to 350L,
                "mainThreadTotalNs" to 200L,
                "startupTotalNs" to 150L,
            ),
        )
        val root = mapOf<String, Any?>()

        val result = buildHotspotEnhancement(root = root, summaryMethods = methods)

        assertEquals("A#slow()V", result.rankings["byTotalNs"]?.first()?.get("methodId"))
        assertEquals("A#slow()V", result.rankings["byP95"]?.first()?.get("methodId"))
        assertEquals("A#slow()V", result.rankings["byP99"]?.first()?.get("methodId"))
        assertEquals("A#slow()V", result.rankings["byMaxNs"]?.first()?.get("methodId"))
        assertEquals("A#slow()V", result.rankings["byMainThreadTotalNs"]?.first()?.get("methodId"))
        assertEquals("A#slow()V", result.rankings["byStartupTotalNs"]?.first()?.get("methodId"))
    }

    @Test
    fun `combined markdown output includes method and frame hotspots`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "A#slow()V",
                "totalNs" to 300L,
                "p95Ns" to 250L,
                "p99Ns" to 280L,
                "maxNs" to 350L,
                "mainThreadTotalNs" to 200L,
                "startupTotalNs" to 150L,
            ),
        )
        val root = mapOf<String, Any?>(
            "frames" to mapOf(
                "hotspots" to listOf(
                    mapOf(
                        "methodId" to "A#slow()V",
                        "totalDurationNs" to 123L,
                        "slowFrames" to 3L,
                        "frozenFrames" to 1L,
                    )
                )
            )
        )

        val result = buildHotspotEnhancement(root = root, summaryMethods = methods)

        assertTrue(result.markdownSummary.contains("Top by Total Time"))
        assertTrue(result.markdownSummary.contains("Top Frame/Jank Correlated Hotspots"))
        assertTrue(result.markdownSummary.contains("A#slow()V"))
    }
}
