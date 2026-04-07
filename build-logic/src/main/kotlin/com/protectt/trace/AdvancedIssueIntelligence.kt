package com.protectt.trace

import groovy.json.JsonSlurper
import java.io.File

internal data class RegressionAnalysisResult(
    val payload: Map<String, Any?>,
    val regressionWeightsByMethod: Map<String, Double>,
)

internal data class TrendAnalysisResult(
    val payload: Map<String, Any?>,
    val frequencyTrendByMethod: Map<String, Double>,
)

internal fun loadHistoricalMethodTraceRoots(outputDir: File, maxFiles: Int = 20): List<Map<String, Any?>> {
    if (!outputDir.exists()) return emptyList()
    val files = outputDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("methodtrace-") && it.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
        .take(maxFiles)

    return files.mapNotNull { file ->
        val parsed = runCatching { JsonSlurper().parseText(file.readText()) }.getOrNull() ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        parsed as? Map<String, Any?>
    }
}

internal fun buildRegressionAnalysis(
    currentRoot: Map<String, Any?>,
    currentMethods: List<Map<String, Any?>>, 
    previousRoot: Map<String, Any?>?,
): RegressionAnalysisResult {
    if (previousRoot == null) {
        return RegressionAnalysisResult(
            payload = mapOf(
                "hasBaseline" to false,
                "summary" to "No previous report found; regression analysis skipped.",
                "increasedLatency" to emptyList<Map<String, Any?>>(),
                "newSlowMethods" to emptyList<Map<String, Any?>>(),
                "startupRegression" to null,
            ),
            regressionWeightsByMethod = emptyMap(),
        )
    }

    val previousMethods = extractSummaryMethods(previousRoot)
    val previousById = previousMethods.associateBy { it["methodId"]?.toString().orEmpty() }
    val increasedLatency = mutableListOf<Map<String, Any?>>()
    val newSlowMethods = mutableListOf<Map<String, Any?>>()
    val weights = linkedMapOf<String, Double>()

    currentMethods.forEach { method ->
        val methodId = method["methodId"]?.toString().orEmpty()
        if (methodId.isBlank()) return@forEach

        val prev = previousById[methodId]
        val currentP95 = metric(method, "p95Ns")
        val currentMax = metric(method, "maxNs")
        val currentTotal = metric(method, "totalNs")

        if (prev == null) {
            if (currentP95 >= 50_000_000L || currentMax >= 100_000_000L) {
                newSlowMethods += mapOf(
                    "methodId" to methodId,
                    "p95Ns" to currentP95,
                    "maxNs" to currentMax,
                    "totalNs" to currentTotal,
                    "reason" to "Method is newly observed and crosses slow-path threshold.",
                )
                weights[methodId] = (weights[methodId] ?: 0.0) + 40.0
            }
            return@forEach
        }

        val prevP95 = metric(prev, "p95Ns")
        val prevTotal = metric(prev, "totalNs")
        val p95DeltaPct = percentDelta(prevP95, currentP95)
        val totalDeltaPct = percentDelta(prevTotal, currentTotal)
        val worsened = (currentP95 - prevP95) >= 20_000_000L && p95DeltaPct >= 15.0

        if (worsened) {
            increasedLatency += mapOf(
                "methodId" to methodId,
                "previousP95Ns" to prevP95,
                "currentP95Ns" to currentP95,
                "p95DeltaPct" to round2(p95DeltaPct),
                "previousTotalNs" to prevTotal,
                "currentTotalNs" to currentTotal,
                "totalDeltaPct" to round2(totalDeltaPct),
                "reason" to "Tail latency increased vs previous run.",
            )
            weights[methodId] = (weights[methodId] ?: 0.0) + (p95DeltaPct.coerceAtMost(80.0) * 0.6)
        }
    }

    val currentStartup = metric(currentRoot["startup"], "startupDurationMs")
    val previousStartup = metric(previousRoot["startup"], "startupDurationMs")
    val startupDeltaPct = percentDelta(previousStartup, currentStartup)
    val startupRegression = if (previousStartup > 0L && currentStartup > previousStartup + 50L && startupDeltaPct >= 10.0) {
        mapOf(
            "previousStartupDurationMs" to previousStartup,
            "currentStartupDurationMs" to currentStartup,
            "deltaPct" to round2(startupDeltaPct),
            "status" to "worsened",
        )
    } else {
        mapOf(
            "previousStartupDurationMs" to previousStartup,
            "currentStartupDurationMs" to currentStartup,
            "deltaPct" to round2(startupDeltaPct),
            "status" to "stable_or_improved",
        )
    }

    return RegressionAnalysisResult(
        payload = linkedMapOf(
            "hasBaseline" to true,
            "summary" to "Compared with previous run baseline.",
            "increasedLatency" to increasedLatency.sortedByDescending { metric(it, "currentP95Ns") }.take(10),
            "newSlowMethods" to newSlowMethods.sortedByDescending { metric(it, "p95Ns") }.take(10),
            "startupRegression" to startupRegression,
        ),
        regressionWeightsByMethod = weights,
    )
}

internal fun buildTrendAnalysis(
    historyRoots: List<Map<String, Any?>>,
    currentMethods: List<Map<String, Any?>>,
): TrendAnalysisResult {
    if (historyRoots.isEmpty()) {
        return TrendAnalysisResult(
            payload = mapOf(
                "historyRuns" to 0,
                "summary" to "No historical data available for trend analysis.",
                "consistentHotspots" to emptyList<Map<String, Any?>>(),
            ),
            frequencyTrendByMethod = emptyMap(),
        )
    }

    val methodAppearanceCount = linkedMapOf<String, Int>()
    val methodCallSamples = linkedMapOf<String, MutableList<Long>>()

    historyRoots.forEach { root ->
        val methods = extractSummaryMethods(root)
        val topIds = methods
            .sortedByDescending { metric(it, "totalNs") }
            .take(10)
            .mapNotNull { it["methodId"]?.toString() }
            .toSet()

        topIds.forEach { methodId ->
            methodAppearanceCount[methodId] = (methodAppearanceCount[methodId] ?: 0) + 1
        }

        methods.forEach { method ->
            val methodId = method["methodId"]?.toString() ?: return@forEach
            val calls = metric(method, "callCount")
            methodCallSamples.getOrPut(methodId) { mutableListOf() }.add(calls)
        }
    }

    val currentById = currentMethods.associateBy { it["methodId"]?.toString().orEmpty() }
    val frequencyTrendByMethod = linkedMapOf<String, Double>()
    methodCallSamples.forEach { (methodId, samples) ->
        val previousAvg = samples.dropLast(1).average().takeIf { !it.isNaN() } ?: samples.average()
        val currentCalls = metric(currentById[methodId], "callCount").toDouble()
        if (previousAvg <= 0.0 || currentCalls <= previousAvg) return@forEach
        val growthPct = ((currentCalls - previousAvg) / previousAvg) * 100.0
        if (growthPct >= 20.0) {
            frequencyTrendByMethod[methodId] = round2(growthPct.coerceAtMost(120.0) * 0.12)
        }
    }

    val consistentHotspots = methodAppearanceCount.entries
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .take(10)
        .map { (methodId, appearances) ->
            mapOf(
                "methodId" to methodId,
                "appearances" to appearances,
                "historyRuns" to historyRoots.size,
                "consistencyRatio" to round2((appearances.toDouble() / historyRoots.size.toDouble()) * 100.0),
            )
        }

    return TrendAnalysisResult(
        payload = linkedMapOf(
            "historyRuns" to historyRoots.size,
            "summary" to "Trend analysis based on available historical methodtrace reports.",
            "consistentHotspots" to consistentHotspots,
            "frequencyTrend" to frequencyTrendByMethod.entries
                .sortedByDescending { it.value }
                .take(10)
                .map { (methodId, weight) -> mapOf("methodId" to methodId, "trendWeight" to weight) },
        ),
        frequencyTrendByMethod = frequencyTrendByMethod,
    )
}

private fun extractSummaryMethods(root: Map<String, Any?>): List<Map<String, Any?>> {
    return (root["methods"] as? List<*>)
        ?.mapNotNull { it as? Map<String, Any?> }
        ?.takeIf { methods -> methods.any { it.containsKey("p95Ns") } }
        ?: (root["methodSummaries"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> }
            .orEmpty()
}

private fun metric(container: Any?, key: String): Long {
    val map = container as? Map<*, *> ?: return 0L
    return (map[key] as? Number)?.toLong() ?: 0L
}

private fun percentDelta(previous: Long, current: Long): Double {
    if (previous <= 0L) return if (current > 0L) 100.0 else 0.0
    return ((current - previous).toDouble() / previous.toDouble()) * 100.0
}

private fun round2(value: Double): Double = (value * 100.0).toInt() / 100.0
