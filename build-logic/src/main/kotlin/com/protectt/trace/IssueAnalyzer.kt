package com.protectt.trace

import groovy.json.JsonOutput
import java.security.MessageDigest
import kotlin.math.log10

internal enum class IssueCategory(val value: String) {
    SLOW_METHOD("slow_method"),
    HIGH_CUMULATIVE_COST("high_cumulative_cost"),
    MAIN_THREAD_BLOCKING("main_thread_blocking"),
    STARTUP_BOTTLENECK("startup_bottleneck"),
    EXCEPTION_CORRELATED_SLOW_PATH("exception_correlated_slow_path"),
    NETWORK_LATENCY_HOTSPOT("network_latency_hotspot"),
    DB_QUERY_BOTTLENECK("db_query_bottleneck"),
    CORRELATED_PERFORMANCE_CLUSTER("correlated_performance_cluster"),
}

internal enum class IssueSeverity(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical"),
}

internal data class AffectedTarget(
    val component: String,
    val className: String,
    val method: String,
)

internal data class Issue(
    val issueId: String,
    val title: String,
    val category: IssueCategory,
    val severity: IssueSeverity,
    val summary: String,
    val probableRootCause: String,
    val recommendedFix: String,
    val relatedSignals: List<Map<String, Any?>>,
    val evidence: Map<String, Any?>,
    val affected: AffectedTarget,
    val confidenceScore: Double,
    val score: Double,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "issueId" to issueId,
            "title" to title,
            "category" to category.value,
            "severity" to severity.value,
            "summary" to summary,
            "probableRootCause" to probableRootCause,
            "recommendedFix" to recommendedFix,
            "relatedSignals" to relatedSignals,
            "evidence" to evidence,
            "affected" to mapOf(
                "component" to affected.component,
                "class" to affected.className,
                "method" to affected.method,
            ),
            "confidenceScore" to confidenceScore,
        )
    }
}

internal data class IssueAnalysisResult(
    val issues: List<Issue>,
) {
    fun toJson(): String {
        val payload = linkedMapOf(
            "generatedAtEpochMs" to System.currentTimeMillis(),
            "totalIssues" to issues.size,
            "issues" to issues.map { it.toMap() },
        )
        return JsonOutput.prettyPrint(JsonOutput.toJson(payload))
    }

    fun toMarkdown(): String {
        return buildString {
            appendLine("# Top 10 Issue Report")
            appendLine()
            if (issues.isEmpty()) {
                appendLine("- No issues detected.")
                return@buildString
            }
            issues.forEachIndexed { index, issue ->
                appendLine("## ${index + 1}. ${issue.title}")
                appendLine("- **Category:** ${issue.category.value}")
                appendLine("- **Severity:** ${issue.severity.value}")
                appendLine("- **Confidence:** ${issue.confidenceScore}")
                appendLine("- **Affected:** `${issue.affected.className}#${issue.affected.method}`")
                appendLine("- **Summary:** ${issue.summary}")
                appendLine("- **Probable root cause:** ${issue.probableRootCause}")
                appendLine("- **Recommended fix:** ${issue.recommendedFix}")
                if (issue.relatedSignals.isNotEmpty()) {
                    appendLine("- **Related signals:** `${issue.relatedSignals}`")
                }
                appendLine("- **Evidence:** `${issue.evidence}`")
                appendLine()
            }
        }
    }
}

private data class DetectionContext(
    val root: Map<String, Any?>,
    val methods: List<Map<String, Any?>>,
    val regressionWeightsByMethod: Map<String, Double>,
    val frequencyTrendByMethod: Map<String, Double>,
)

internal data class IssueAnalysisSignals(
    val regressionWeightsByMethod: Map<String, Double> = emptyMap(),
    val frequencyTrendByMethod: Map<String, Double> = emptyMap(),
)

private interface IssueDetector {
    fun detect(context: DetectionContext): List<Issue>
}

internal class IssueAnalyzer(
    private val topN: Int = 10,
) {
    private val detectors: List<IssueDetector> = listOf(
        SlowMethodDetector(),
        HighCumulativeCostDetector(),
        MainThreadBlockingDetector(),
        StartupBottleneckDetector(),
        ExceptionCorrelatedSlowPathDetector(),
        NetworkLatencyHotspotDetector(),
        DbQueryBottleneckDetector(),
    )

    fun analyze(
        root: Map<String, Any?>,
        summaryMethods: List<Map<String, Any?>>,
        signals: IssueAnalysisSignals = IssueAnalysisSignals(),
    ): IssueAnalysisResult {
        val context = DetectionContext(
            root = root,
            methods = summaryMethods,
            regressionWeightsByMethod = signals.regressionWeightsByMethod,
            frequencyTrendByMethod = signals.frequencyTrendByMethod,
        )
        val raw = detectors.flatMap { it.detect(context) }
        val merged = mergeCorrelatedIssues(raw)
        val deduped = merged
            .groupBy { "${it.category.value}:${it.affected.className}:${it.affected.method}:${it.title}" }
            .map { (_, issues) -> issues.maxByOrNull { it.score }!! }
            .sortedWith(compareByDescending<Issue> { it.score }.thenByDescending { it.confidenceScore })
            .take(topN)
        return IssueAnalysisResult(issues = deduped)
    }
}

private class SlowMethodDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        return context.methods.mapNotNull { method ->
            val p95Ns = metric(method, "p95Ns")
            val maxNs = metric(method, "maxNs")
            if (p95Ns < 50_000_000L && maxNs < 100_000_000L) return@mapNotNull null

            val affected = parseAffected(method["methodId"]?.toString())
            val score = (p95Ns / 1_000_000.0 * 1.2) + (maxNs / 1_000_000.0 * 0.8) + frequencyScore(context, method) + regressionBoost(context, methodId = method["methodId"]?.toString())
            val severity = when {
                maxNs >= 1_000_000_000L || p95Ns >= 500_000_000L -> IssueSeverity.CRITICAL
                maxNs >= 300_000_000L || p95Ns >= 200_000_000L -> IssueSeverity.HIGH
                maxNs >= 150_000_000L || p95Ns >= 100_000_000L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            buildIssue(
                title = "Slow method tail latency",
                category = IssueCategory.SLOW_METHOD,
                severity = severity,
                summary = "Method shows elevated tail latency (p95/max).",
                probableRootCause = "Method logic is latency-heavy in the tail path.",
                recommendedFix = "1) Move blocking work off the critical path.\n2) Cache or memoize expensive results.\n3) Reduce allocations in the tail path.",
                relatedSignals = emptyList(),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(p95Ns > 0L, maxNs > 0L, metric(method, "callCount") > 3L)),
                evidence = linkedMapOf(
                    "methodId" to (method["methodId"] ?: "unknown"),
                    "p95Ns" to p95Ns,
                    "maxNs" to maxNs,
                    "callCount" to metric(method, "callCount"),
                    "totalNs" to metric(method, "totalNs"),
                ),
            )
        }
    }
}

private class HighCumulativeCostDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        return context.methods.mapNotNull { method ->
            val totalNs = metric(method, "totalNs")
            val calls = metric(method, "callCount")
            if (totalNs < 200_000_000L || calls < 5L) return@mapNotNull null

            val affected = parseAffected(method["methodId"]?.toString())
            val score =
                (totalNs / 1_000_000.0) +
                    frequencyScore(context, method) +
                    (metric(method, "mainThreadTotalNs") / 1_000_000.0 * 0.2) +
                    regressionBoost(context, methodId = method["methodId"]?.toString())
            val severity = when {
                totalNs >= 5_000_000_000L -> IssueSeverity.CRITICAL
                totalNs >= 2_000_000_000L -> IssueSeverity.HIGH
                totalNs >= 800_000_000L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            buildIssue(
                title = "High cumulative method cost",
                category = IssueCategory.HIGH_CUMULATIVE_COST,
                severity = severity,
                summary = "Method contributes high total runtime cost across calls.",
                probableRootCause = "Frequently invoked path with expensive operations.",
                recommendedFix = "1) Reduce invocation frequency on hot paths.\n2) Memoize stable computations.\n3) Split heavy logic into incremental/background work.",
                relatedSignals = emptyList(),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(totalNs > 0L, calls > 10L)),
                evidence = linkedMapOf(
                    "methodId" to (method["methodId"] ?: "unknown"),
                    "totalNs" to totalNs,
                    "callCount" to calls,
                    "avgNs" to if (calls > 0L) totalNs / calls else 0L,
                    "mainThreadTotalNs" to metric(method, "mainThreadTotalNs"),
                ),
            )
        }
    }
}

private class MainThreadBlockingDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        val stallCountsByMethod = parseMainThreadStallCounts(context.root)
        return context.methods.mapNotNull { method ->
            val methodId = method["methodId"]?.toString().orEmpty()
            val mainThreadNs = metric(method, "mainThreadTotalNs")
            val stallHits = stallCountsByMethod[methodId] ?: 0L
            if (mainThreadNs < 120_000_000L && stallHits == 0L) return@mapNotNull null

            val affected = parseAffected(method["methodId"]?.toString())
            val score =
                (mainThreadNs / 1_000_000.0 * 1.4) +
                    (stallHits * 40.0) +
                    frequencyScore(context, method) +
                    regressionBoost(context, methodId = methodId)
            val severity = when {
                stallHits >= 3L || mainThreadNs >= 2_000_000_000L -> IssueSeverity.CRITICAL
                stallHits >= 2L || mainThreadNs >= 1_000_000_000L -> IssueSeverity.HIGH
                stallHits >= 1L || mainThreadNs >= 400_000_000L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            buildIssue(
                title = "Main-thread blocking hotspot",
                category = IssueCategory.MAIN_THREAD_BLOCKING,
                severity = severity,
                summary = "Method is associated with main-thread cost and/or stall events.",
                probableRootCause = "Long-running work is executing on the main thread.",
                recommendedFix = "1) Move CPU/I/O work to background dispatchers.\n2) Keep main-thread handlers under frame budget.\n3) Break long tasks into smaller chunks.",
                relatedSignals = listOf(mapOf("kind" to "main_thread_stall", "hits" to stallHits)),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(mainThreadNs > 0L, stallHits > 0L, metric(method, "callCount") > 1L)),
                evidence = linkedMapOf(
                    "methodId" to (method["methodId"] ?: "unknown"),
                    "mainThreadTotalNs" to mainThreadNs,
                    "stallEventHits" to stallHits,
                    "callCount" to metric(method, "callCount"),
                ),
            )
        }
    }
}

private class StartupBottleneckDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        val startupDurationMs = metric(context.root["startup"], "startupDurationMs")
        return context.methods.mapNotNull { method ->
            val startupNs = metric(method, "startupTotalNs")
            if (startupNs < 80_000_000L) return@mapNotNull null

            val startupDurationNs = startupDurationMs * 1_000_000L
            val contribution = if (startupDurationNs > 0L) startupNs.toDouble() / startupDurationNs.toDouble() else 0.0
            val affected = parseAffected(method["methodId"]?.toString())
            val score =
                (startupNs / 1_000_000.0 * 1.3) +
                    (contribution * 300.0) +
                    frequencyScore(context, method) +
                    regressionBoost(context, methodId = method["methodId"]?.toString())
            val severity = when {
                contribution >= 0.40 || startupNs >= 1_500_000_000L -> IssueSeverity.CRITICAL
                contribution >= 0.25 || startupNs >= 800_000_000L -> IssueSeverity.HIGH
                contribution >= 0.10 || startupNs >= 250_000_000L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            buildIssue(
                title = "Startup bottleneck",
                category = IssueCategory.STARTUP_BOTTLENECK,
                severity = severity,
                summary = "Method contributes significantly during startup window.",
                probableRootCause = "Startup path is doing heavy synchronous work.",
                recommendedFix = "1) Defer non-critical initialization.\n2) Parallelize independent startup tasks.\n3) Gate expensive setup behind first-use.",
                relatedSignals = emptyList(),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(startupNs > 0L, startupDurationMs > 0L, contribution > 0.05)),
                evidence = linkedMapOf(
                    "methodId" to (method["methodId"] ?: "unknown"),
                    "startupTotalNs" to startupNs,
                    "startupDurationMs" to startupDurationMs,
                    "startupContributionRatio" to round(contribution),
                ),
            )
        }
    }
}

private class ExceptionCorrelatedSlowPathDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        val exceptionCountsByMethod = parseExceptionMethodCounts(context.root)
        if (exceptionCountsByMethod.isEmpty()) return emptyList()

        return context.methods.mapNotNull { method ->
            val methodId = method["methodId"]?.toString().orEmpty()
            val exceptionHits = exceptionCountsByMethod[methodId] ?: 0L
            if (exceptionHits <= 0L) return@mapNotNull null

            val p95Ns = metric(method, "p95Ns")
            val maxNs = metric(method, "maxNs")
            if (p95Ns < 40_000_000L && maxNs < 80_000_000L) return@mapNotNull null

            val affected = parseAffected(methodId)
            val score =
                (p95Ns / 1_000_000.0 * 1.1) +
                    (maxNs / 1_000_000.0 * 0.7) +
                    (exceptionHits * 55.0) +
                    frequencyScore(context, method) +
                    regressionBoost(context, methodId = methodId)
            val severity = when {
                exceptionHits >= 3L && (p95Ns >= 250_000_000L || maxNs >= 500_000_000L) -> IssueSeverity.CRITICAL
                exceptionHits >= 2L || p95Ns >= 140_000_000L || maxNs >= 300_000_000L -> IssueSeverity.HIGH
                exceptionHits >= 1L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            buildIssue(
                title = "Exception-correlated slow path",
                category = IssueCategory.EXCEPTION_CORRELATED_SLOW_PATH,
                severity = severity,
                summary = "Slow method appears in exception correlation spans.",
                probableRootCause = "Method is both latency-heavy and failure-prone in the same execution paths.",
                recommendedFix = "1) Harden error handling and retries.\n2) Optimize I/O and compute sections.\n3) Add fallback paths to prevent repeated failures.",
                relatedSignals = listOf(
                    mapOf("kind" to "exception", "count" to exceptionHits),
                    mapOf("kind" to "method_latency", "p95Ns" to p95Ns, "maxNs" to maxNs),
                ),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(exceptionHits > 0L, p95Ns > 0L, maxNs > 0L)),
                evidence = linkedMapOf(
                    "methodId" to methodId,
                    "exceptionHits" to exceptionHits,
                    "p95Ns" to p95Ns,
                    "maxNs" to maxNs,
                    "callCount" to metric(method, "callCount"),
                    "mergeKey" to methodId,
                ),
            )
        }
    }
}

private class NetworkLatencyHotspotDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        val networkByMethod = parseNetworkLatencyByMethod(context.root)
        return context.methods.mapNotNull { method ->
            val methodId = method["methodId"]?.toString().orEmpty()
            val bucket = networkByMethod[methodId] ?: return@mapNotNull null
            if (bucket.calls < 2L) return@mapNotNull null
            val avgNs = bucket.totalDurationNs / bucket.calls
            if (bucket.maxDurationNs < 80_000_000L && avgNs < 40_000_000L) return@mapNotNull null

            val affected = parseAffected(methodId)
            val score =
                (bucket.maxDurationNs / 1_000_000.0 * 0.9) +
                    (avgNs / 1_000_000.0 * 0.8) +
                    (bucket.calls * 6.0) +
                    (bucket.failedCalls * 20.0) +
                    regressionBoost(context, methodId = methodId)
            val severity = when {
                bucket.maxDurationNs >= 900_000_000L || bucket.failedCalls >= 4L -> IssueSeverity.CRITICAL
                bucket.maxDurationNs >= 400_000_000L || bucket.failedCalls >= 2L -> IssueSeverity.HIGH
                bucket.maxDurationNs >= 200_000_000L || avgNs >= 80_000_000L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            val topEndpoints = bucket.endpointCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { (endpoint, count) -> mapOf("endpoint" to endpoint, "hits" to count) }

            buildIssue(
                title = "Network latency hotspot",
                category = IssueCategory.NETWORK_LATENCY_HOTSPOT,
                severity = severity,
                summary = "Method frequently overlaps with slow network calls.",
                probableRootCause = "API latency and/or retries are inflating end-to-end method latency.",
                recommendedFix = "1) Reduce payload size and chatty calls.\n2) Add caching/batching where possible.\n3) Prioritize optimization of the top slow endpoints.",
                relatedSignals = listOf(
                    mapOf("kind" to "network", "calls" to bucket.calls, "failedCalls" to bucket.failedCalls),
                    mapOf("kind" to "network_endpoints", "top" to topEndpoints),
                ),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(bucket.calls > 2L, bucket.maxDurationNs > 0L, methodId.isNotBlank())),
                evidence = linkedMapOf(
                    "methodId" to methodId,
                    "networkCalls" to bucket.calls,
                    "networkFailedCalls" to bucket.failedCalls,
                    "networkTotalDurationNs" to bucket.totalDurationNs,
                    "networkMaxDurationNs" to bucket.maxDurationNs,
                    "networkAvgDurationNs" to avgNs,
                    "mergeKey" to methodId,
                ),
            )
        }
    }
}

private class DbQueryBottleneckDetector : IssueDetector {
    override fun detect(context: DetectionContext): List<Issue> {
        val dbByMethod = parseDbLatencyByMethod(context.root)
        return context.methods.mapNotNull { method ->
            val methodId = method["methodId"]?.toString().orEmpty()
            val bucket = dbByMethod[methodId] ?: return@mapNotNull null
            if (bucket.calls < 2L) return@mapNotNull null

            val avgNs = bucket.totalDurationNs / bucket.calls
            if (bucket.maxDurationNs < 60_000_000L && (avgNs < 30_000_000L || bucket.calls < 4L)) return@mapNotNull null

            val affected = parseAffected(methodId)
            val score =
                (bucket.maxDurationNs / 1_000_000.0 * 1.0) +
                    (avgNs / 1_000_000.0 * 0.8) +
                    (bucket.calls * 7.0) +
                    (bucket.expensiveHits * 18.0) +
                    regressionBoost(context, methodId = methodId)
            val severity = when {
                bucket.maxDurationNs >= 700_000_000L || bucket.expensiveHits >= 5L -> IssueSeverity.CRITICAL
                bucket.maxDurationNs >= 300_000_000L || bucket.expensiveHits >= 3L -> IssueSeverity.HIGH
                bucket.maxDurationNs >= 150_000_000L || bucket.calls >= 8L -> IssueSeverity.MEDIUM
                else -> IssueSeverity.LOW
            }

            val topOps = bucket.operationCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { (op, count) -> mapOf("operation" to op, "hits" to count) }

            buildIssue(
                title = "DB/query bottleneck",
                category = IssueCategory.DB_QUERY_BOTTLENECK,
                severity = severity,
                summary = "Method is associated with slow or high-frequency DB work.",
                probableRootCause = "Database query execution dominates method runtime.",
                recommendedFix = "1) Add/verify indexes on filter and join keys.\n2) Reduce query frequency and duplicate work.\n3) Optimize query plans for top expensive operations.",
                relatedSignals = listOf(
                    mapOf("kind" to "db", "calls" to bucket.calls, "expensiveHits" to bucket.expensiveHits),
                    mapOf("kind" to "db_operations", "top" to topOps),
                ),
                affected = affected,
                score = score,
                confidence = confidenceFromSignals(listOf(bucket.calls > 2L, bucket.maxDurationNs > 0L, methodId.isNotBlank())),
                evidence = linkedMapOf(
                    "methodId" to methodId,
                    "dbCalls" to bucket.calls,
                    "dbTotalDurationNs" to bucket.totalDurationNs,
                    "dbMaxDurationNs" to bucket.maxDurationNs,
                    "dbAvgDurationNs" to avgNs,
                    "expensiveQueryHits" to bucket.expensiveHits,
                    "mergeKey" to methodId,
                ),
            )
        }
    }
}

private data class NetworkAggregate(
    val calls: Long = 0L,
    val failedCalls: Long = 0L,
    val totalDurationNs: Long = 0L,
    val maxDurationNs: Long = 0L,
    val endpointCounts: Map<String, Long> = emptyMap(),
)

private data class DbAggregate(
    val calls: Long = 0L,
    val expensiveHits: Long = 0L,
    val totalDurationNs: Long = 0L,
    val maxDurationNs: Long = 0L,
    val operationCounts: Map<String, Long> = emptyMap(),
)

private fun parseMainThreadStallCounts(root: Map<String, Any?>): Map<String, Long> {
    val counts = linkedMapOf<String, Long>()
    parseTraceEvents(root).forEach { event ->
        val name = event["name"]?.toString() ?: return@forEach
        if (name != "main_thread_stall") return@forEach
        val args = event["args"] as? Map<*, *> ?: return@forEach
        val method = args["activeMethod"]?.toString() ?: return@forEach
        counts[method] = (counts[method] ?: 0L) + 1L
    }
    return counts
}

private fun parseExceptionMethodCounts(root: Map<String, Any?>): Map<String, Long> {
    val counts = linkedMapOf<String, Long>()
    val exceptions = (root["exceptions"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
    exceptions.forEach { event ->
        val activeSpans = event["activeSpans"] as? List<*> ?: return@forEach
        activeSpans.forEach { span ->
            val raw = span?.toString() ?: return@forEach
            val methodId = raw.substringAfter(':', missingDelimiterValue = "")
            if (methodId.isBlank()) return@forEach
            counts[methodId] = (counts[methodId] ?: 0L) + 1L
        }
    }
    return counts
}

private fun parseNetworkLatencyByMethod(root: Map<String, Any?>): Map<String, NetworkAggregate> {
    val totals = linkedMapOf<String, MutableNetworkAggregate>()
    parseTraceEvents(root).forEach { event ->
        val name = event["name"]?.toString() ?: return@forEach
        if (!name.startsWith("network:")) return@forEach
        val args = event["args"] as? Map<*, *> ?: return@forEach
        val methodId = args["activeSpanId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
        val durationNs = eventDurationNs(event)
        val endpoint = listOf(
            args["host"]?.toString() ?: "unknown",
            args["method"]?.toString() ?: "UNKNOWN",
            args["pathTemplate"]?.toString() ?: "/",
        ).joinToString(" ")
        val failed = args["failed"] as? Boolean ?: false

        val aggregate = totals.getOrPut(methodId) { MutableNetworkAggregate() }
        aggregate.calls += 1
        aggregate.totalDurationNs += durationNs
        aggregate.maxDurationNs = maxOf(aggregate.maxDurationNs, durationNs)
        if (failed) aggregate.failedCalls += 1
        aggregate.endpointCounts[endpoint] = (aggregate.endpointCounts[endpoint] ?: 0L) + 1L
    }

    return totals.mapValues { (_, value) ->
        NetworkAggregate(
            calls = value.calls,
            failedCalls = value.failedCalls,
            totalDurationNs = value.totalDurationNs,
            maxDurationNs = value.maxDurationNs,
            endpointCounts = value.endpointCounts,
        )
    }
}

private fun parseDbLatencyByMethod(root: Map<String, Any?>): Map<String, DbAggregate> {
    val totals = linkedMapOf<String, MutableDbAggregate>()
    parseTraceEvents(root).forEach { event ->
        val name = event["name"]?.toString() ?: return@forEach
        if (!name.startsWith("db:")) return@forEach
        val args = event["args"] as? Map<*, *> ?: return@forEach
        val methodId = args["activeSpanId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
        val durationNs = eventDurationNs(event)
        val operation = args["operation"]?.toString() ?: "UNKNOWN"

        val aggregate = totals.getOrPut(methodId) { MutableDbAggregate() }
        aggregate.calls += 1
        aggregate.totalDurationNs += durationNs
        aggregate.maxDurationNs = maxOf(aggregate.maxDurationNs, durationNs)
        if (durationNs >= 75_000_000L) aggregate.expensiveHits += 1
        aggregate.operationCounts[operation] = (aggregate.operationCounts[operation] ?: 0L) + 1L
    }

    return totals.mapValues { (_, value) ->
        DbAggregate(
            calls = value.calls,
            expensiveHits = value.expensiveHits,
            totalDurationNs = value.totalDurationNs,
            maxDurationNs = value.maxDurationNs,
            operationCounts = value.operationCounts,
        )
    }
}

private fun parseTraceEvents(root: Map<String, Any?>): List<Map<*, *>> {
    return (root["traceEvents"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
}

private fun eventDurationNs(event: Map<*, *>): Long {
    val durUs = (event["dur"] as? Number)?.toLong() ?: 0L
    return (durUs * 1_000L).coerceAtLeast(0L)
}

private class MutableNetworkAggregate {
    var calls: Long = 0L
    var failedCalls: Long = 0L
    var totalDurationNs: Long = 0L
    var maxDurationNs: Long = 0L
    val endpointCounts: MutableMap<String, Long> = linkedMapOf()
}

private class MutableDbAggregate {
    var calls: Long = 0L
    var expensiveHits: Long = 0L
    var totalDurationNs: Long = 0L
    var maxDurationNs: Long = 0L
    val operationCounts: MutableMap<String, Long> = linkedMapOf()
}

private fun mergeCorrelatedIssues(raw: List<Issue>): List<Issue> {
    val grouped = raw.groupBy { issue ->
        issue.evidence["mergeKey"]?.toString()?.takeIf { it.isNotBlank() }
            ?: "${issue.affected.className}#${issue.affected.method}"
    }

    val merged = mutableListOf<Issue>()
    grouped.forEach { (_, issues) ->
        val correlated = issues.filter {
            it.category == IssueCategory.EXCEPTION_CORRELATED_SLOW_PATH ||
                it.category == IssueCategory.NETWORK_LATENCY_HOTSPOT ||
                it.category == IssueCategory.DB_QUERY_BOTTLENECK
        }
        if (correlated.size < 2) {
            merged.addAll(issues)
            return@forEach
        }

        val primary = correlated.maxByOrNull { it.score }!!
        val combinedScore = correlated.sumOf { it.score } * 0.55
        val combinedConfidence = round(correlated.map { it.confidenceScore }.average())
        val topSeverity = correlated.maxByOrNull { it.severity.ordinal }?.severity ?: primary.severity
        val signalKinds = correlated.map { it.category.value }

        val cluster = buildIssue(
            title = "Correlated I/O and exception hotspot",
            category = IssueCategory.CORRELATED_PERFORMANCE_CLUSTER,
            severity = topSeverity,
            summary = "Multiple correlated signals point to the same problematic execution path.",
            probableRootCause = "A shared method path is impacted by I/O latency and reliability issues.",
            recommendedFix = "Prioritize this path for end-to-end optimization: network, DB, and exception hardening.",
            relatedSignals = correlated.flatMap { it.relatedSignals } + listOf(mapOf("kind" to "correlated_categories", "values" to signalKinds)),
            affected = primary.affected,
            evidence = linkedMapOf(
                "mergedIssueIds" to correlated.map { it.issueId },
                "signalCategories" to signalKinds,
                "mergeKey" to (primary.evidence["mergeKey"] ?: "unknown"),
            ),
            confidence = combinedConfidence,
            score = combinedScore,
        )
        merged.add(cluster)
        merged.addAll(issues.filterNot { it in correlated })
    }
    return merged
}

private fun buildIssue(
    title: String,
    category: IssueCategory,
    severity: IssueSeverity,
    summary: String,
    probableRootCause: String,
    recommendedFix: String,
    relatedSignals: List<Map<String, Any?>>,
    affected: AffectedTarget,
    evidence: Map<String, Any?>,
    confidence: Double,
    score: Double,
): Issue {
    val issueId = digestHex("${category.value}|${affected.className}|${affected.method}|$title")
    return Issue(
        issueId = issueId,
        title = title,
        category = category,
        severity = severity,
        summary = summary,
        probableRootCause = probableRootCause,
        recommendedFix = recommendedFix,
        relatedSignals = relatedSignals,
        evidence = evidence,
        affected = affected,
        confidenceScore = confidence,
        score = score,
    )
}

private fun parseAffected(rawMethodId: String?): AffectedTarget {
    val methodId = rawMethodId?.takeIf { it.isNotBlank() } ?: "unknown#unknown"
    val classPart = methodId.substringBefore('#', "unknown").replace('/', '.')
    val methodPart = methodId.substringAfter('#', "unknown")
    val component = classPart.substringBeforeLast('.', classPart)
    return AffectedTarget(
        component = component,
        className = classPart,
        method = methodPart,
    )
}

private fun metric(container: Any?, key: String): Long {
    val map = container as? Map<*, *> ?: return 0L
    return (map[key] as? Number)?.toLong() ?: 0L
}

private fun frequencyScore(context: DetectionContext, method: Map<String, Any?>): Double {
    val callCount = metric(method, "callCount").coerceAtLeast(1L).toDouble()
    val base = log10(callCount + 1.0) * 15.0
    val methodId = method["methodId"]?.toString()
    val trend = context.frequencyTrendByMethod[methodId] ?: 0.0
    return base + trend
}

private fun regressionBoost(context: DetectionContext, methodId: String?): Double {
    return context.regressionWeightsByMethod[methodId] ?: 0.0
}

private fun confidenceFromSignals(signals: List<Boolean>): Double {
    val ratio = if (signals.isEmpty()) 0.0 else signals.count { it }.toDouble() / signals.size.toDouble()
    return round(0.45 + ratio * 0.5)
}

private fun round(value: Double): Double = (value * 100.0).toInt() / 100.0

private fun digestHex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
}
