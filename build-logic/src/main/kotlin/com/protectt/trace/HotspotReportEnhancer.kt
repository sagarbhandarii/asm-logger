package com.protectt.trace

internal data class HotspotEnhancementResult(
    val rankings: Map<String, List<Map<String, Any?>>>,
    val markdownSummary: String,
)

internal fun buildHotspotEnhancement(
    root: Map<String, Any?>,
    summaryMethods: List<Map<String, Any?>>,
): HotspotEnhancementResult {
    val rankings = linkedMapOf<String, List<Map<String, Any?>>>(
        "byTotalNs" to summaryMethods.sortedByMetricDescending("totalNs"),
        "byP95" to summaryMethods.sortedByMetricDescending("p95Ns"),
        "byP99" to summaryMethods.sortedByMetricDescending("p99Ns"),
        "byMaxNs" to summaryMethods.sortedByMetricDescending("maxNs"),
        "byMainThreadTotalNs" to summaryMethods.sortedByMetricDescending("mainThreadTotalNs"),
        "byStartupTotalNs" to summaryMethods.sortedByMetricDescending("startupTotalNs"),
    )

    val frameSummary = root["frames"] as? Map<*, *>
    val frameHotspots = (frameSummary?.get("hotspots") as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()
    val topTotal = rankings["byTotalNs"].orEmpty().take(5)
    val topMain = rankings["byMainThreadTotalNs"].orEmpty().take(5)
    val topStartup = rankings["byStartupTotalNs"].orEmpty().take(5)
    val topFrame = frameHotspots
        .sortedByDescending { metric(it, "totalDurationNs") }
        .take(5)

    val markdown = buildString {
        appendLine("# Method Trace Hotspot Summary")
        appendLine()
        appendLine("## Top by Total Time")
        append(renderMethodRows(topTotal, "totalNs"))
        appendLine()
        appendLine("## Top by Main Thread Cost")
        append(renderMethodRows(topMain, "mainThreadTotalNs"))
        appendLine()
        appendLine("## Top by Startup-only Cost")
        append(renderMethodRows(topStartup, "startupTotalNs"))
        if (topFrame.isNotEmpty()) {
            appendLine()
            appendLine("## Top Frame/Jank Correlated Hotspots")
            append(renderFrameRows(topFrame))
        }
    }

    return HotspotEnhancementResult(rankings = rankings, markdownSummary = markdown)
}

private fun renderMethodRows(rows: List<Map<String, Any?>>, primaryMetric: String): String {
    if (rows.isEmpty()) return "- No data.\n"
    return buildString {
        rows.forEach { row ->
            append("- `")
            append(row["methodId"] ?: "unknown")
            append("` ")
            append(primaryMetric)
            append('=')
            append(metric(row, primaryMetric))
            append(" p95=")
            append(metric(row, "p95Ns"))
            append(" p99=")
            append(metric(row, "p99Ns"))
            append(" max=")
            append(metric(row, "maxNs"))
            append('\n')
        }
    }
}

private fun renderFrameRows(rows: List<Map<String, Any?>>): String {
    return buildString {
        rows.forEach { row ->
            append("- `")
            append(row["methodId"] ?: "unknown")
            append("` totalDurationNs=")
            append(metric(row, "totalDurationNs"))
            append(" slowFrames=")
            append(metric(row, "slowFrames"))
            append(" frozenFrames=")
            append(metric(row, "frozenFrames"))
            append('\n')
        }
    }
}

private fun List<Map<String, Any?>>.sortedByMetricDescending(metric: String): List<Map<String, Any?>> {
    return sortedByDescending { row -> metric(row, metric) }
}

private fun metric(row: Map<String, Any?>, key: String): Long {
    return (row[key] as? Number)?.toLong() ?: 0L
}
