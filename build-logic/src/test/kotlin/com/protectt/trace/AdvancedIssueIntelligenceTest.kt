package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvancedIssueIntelligenceTest {
    @Test
    fun `regression analysis flags increased latency and new slow methods`() {
        val previousRoot = mapOf<String, Any?>(
            "startup" to mapOf("startupDurationMs" to 1_000L),
            "methods" to listOf(
                mapOf(
                    "methodId" to "A#hot()V",
                    "p95Ns" to 80_000_000L,
                    "maxNs" to 120_000_000L,
                    "totalNs" to 500_000_000L,
                ),
            ),
        )
        val currentRoot = mapOf<String, Any?>(
            "startup" to mapOf("startupDurationMs" to 1_250L),
        )
        val currentMethods = listOf<Map<String, Any?>>(
            mapOf(
                "methodId" to "A#hot()V",
                "p95Ns" to 120_000_000L,
                "maxNs" to 180_000_000L,
                "totalNs" to 700_000_000L,
            ),
            mapOf(
                "methodId" to "B#newSlow()V",
                "p95Ns" to 90_000_000L,
                "maxNs" to 160_000_000L,
                "totalNs" to 300_000_000L,
            ),
        )

        val result = buildRegressionAnalysis(currentRoot, currentMethods, previousRoot)

        val increased = result.payload["increasedLatency"] as List<*>
        val newSlow = result.payload["newSlowMethods"] as List<*>
        val startup = result.payload["startupRegression"] as Map<*, *>

        assertEquals(1, increased.size)
        assertEquals(1, newSlow.size)
        assertEquals("worsened", startup["status"])
        assertTrue((result.regressionWeightsByMethod["A#hot()V"] ?: 0.0) > 0.0)
    }

    @Test
    fun `regression analysis remains backward compatible without baseline`() {
        val currentRoot = mapOf<String, Any?>("startup" to mapOf("startupDurationMs" to 900L))
        val currentMethods = listOf<Map<String, Any?>>(mapOf("methodId" to "A#x()V", "p95Ns" to 10L))

        val result = buildRegressionAnalysis(currentRoot, currentMethods, previousRoot = null)

        assertEquals(false, result.payload["hasBaseline"])
        assertTrue((result.payload["increasedLatency"] as List<*>).isEmpty())
        assertTrue(result.regressionWeightsByMethod.isEmpty())
    }

    @Test
    fun `trend analysis highlights consistent hotspots and frequency growth`() {
        val h1 = mapOf<String, Any?>(
            "methods" to listOf(
                mapOf("methodId" to "A#hot()V", "totalNs" to 900L, "callCount" to 8L),
                mapOf("methodId" to "B#warm()V", "totalNs" to 600L, "callCount" to 3L),
            )
        )
        val h2 = mapOf<String, Any?>(
            "methods" to listOf(
                mapOf("methodId" to "A#hot()V", "totalNs" to 950L, "callCount" to 10L),
                mapOf("methodId" to "C#cold()V", "totalNs" to 200L, "callCount" to 1L),
            )
        )
        val currentMethods = listOf<Map<String, Any?>>(
            mapOf("methodId" to "A#hot()V", "totalNs" to 1100L, "callCount" to 20L),
        )

        val result = buildTrendAnalysis(listOf(h1, h2), currentMethods)

        val hotspots = result.payload["consistentHotspots"] as List<*>
        assertTrue(hotspots.isNotEmpty())
        assertTrue((result.frequencyTrendByMethod["A#hot()V"] ?: 0.0) > 0.0)
    }
}
