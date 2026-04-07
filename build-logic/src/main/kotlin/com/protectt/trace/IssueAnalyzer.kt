package com.protectt.trace

import groovy.json.JsonOutput
import java.security.MessageDigest
import kotlin.math.log10

internal enum class IssueCategory(val value: String) {
    SLOW_METHOD("slow_method"),
    HIGH_CUMULATIVE_COST("high_cumulative_cost"),
    MAIN_THREAD_BLOCKING("main_thread_blocking"),
    STARTUP_BOTTLENECK("startup_bottleneck"),
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
                appendLine("- **Evidence:** `${issue.evidence}`")
                appendLine()
            }
        }
    }
}

private data class DetectionContext(
    val root: Map<String, Any?>,
    val methods: List<Map<String, Any?>>,
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
    )

    fun analyze(root: Map<String, Any?>, summaryMethods: List<Map<String, Any?>>): IssueAnalysisResult {
        val context = DetectionContext(root = root, methods = summaryMethods)
        val raw = detectors.flatMap { it.detect(context) }
        val deduped = raw
            .groupBy { "${it.category.value}:${it.affected.className}:${it.affected.method}" }
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
            val score = (p95Ns / 1_000_000.0 * 1.2) + (maxNs / 1_000_000.0 * 0.8) + frequencyScore(method)
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
            val score = (totalNs / 1_000_000.0) + frequencyScore(method) + (metric(method, "mainThreadTotalNs") / 1_000_000.0 * 0.2)
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
            val score = (mainThreadNs / 1_000_000.0 * 1.4) + (stallHits * 40.0) + frequencyScore(method)
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
            val score = (startupNs / 1_000_000.0 * 1.3) + (contribution * 300.0) + frequencyScore(method)
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

private fun parseMainThreadStallCounts(root: Map<String, Any?>): Map<String, Long> {
    val counts = linkedMapOf<String, Long>()
    val events = (root["traceEvents"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
    events.forEach { event ->
        val name = event["name"]?.toString() ?: return@forEach
        if (name != "main_thread_stall") return@forEach
        val args = event["args"] as? Map<*, *> ?: return@forEach
        val method = args["activeMethod"]?.toString() ?: return@forEach
        counts[method] = (counts[method] ?: 0L) + 1L
    }
    return counts
}

private fun buildIssue(
    title: String,
    category: IssueCategory,
    severity: IssueSeverity,
    summary: String,
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

private fun frequencyScore(method: Map<String, Any?>): Double {
    val callCount = metric(method, "callCount").coerceAtLeast(1L).toDouble()
    return log10(callCount + 1.0) * 15.0
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
