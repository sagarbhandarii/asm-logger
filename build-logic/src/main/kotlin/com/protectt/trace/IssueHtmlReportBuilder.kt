package com.protectt.trace

internal fun buildIssueHtmlReport(
    issues: List<Issue>,
    regression: Map<String, Any?>?,
    trends: Map<String, Any?>?,
): String {
    val cards = if (issues.isEmpty()) {
        "<p class=\"muted\">No issues detected.</p>"
    } else {
        issues.mapIndexed { index, issue ->
            val severityClass = issue.severity.value
            """
            <article class="card $severityClass">
              <div class="card-header">
                <span class="rank">#${index + 1}</span>
                <h3>${escape(issue.title)}</h3>
                <span class="badge">${escape(issue.severity.value.uppercase())}</span>
              </div>
              <p><strong>Category:</strong> ${escape(issue.category.value)}</p>
              <p><strong>Affected:</strong> <code>${escape(issue.affected.className)}#${escape(issue.affected.method)}</code></p>
              <p>${escape(issue.summary)}</p>
              <p><strong>Root cause:</strong> ${escape(issue.probableRootCause)}</p>
              <p><strong>Recommendations:</strong><br/>${escape(issue.recommendedFix).replace("\n", "<br/>")}</p>
              <div class="meter-wrap">
                <span>Confidence ${issue.confidenceScore}</span>
                <div class="meter"><div class="fill" style="width:${(issue.confidenceScore * 100).toInt().coerceIn(0, 100)}%"></div></div>
              </div>
            </article>
            """.trimIndent()
        }.joinToString("\n")
    }

    val regressionHtml = buildRegressionSection(regression)
    val trendHtml = buildTrendSection(trends)

    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>Top 10 Issue Report</title>
        <style>
          body { font-family: Arial, sans-serif; margin: 24px; background:#0f172a; color:#e2e8f0; }
          h1, h2, h3 { margin: 0 0 8px; }
          .muted { color:#94a3b8; }
          .grid { display:grid; gap:12px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
          .card { border:1px solid #334155; border-left: 6px solid #64748b; border-radius:8px; padding:12px; background:#111827; }
          .card.low { border-left-color:#22c55e; }
          .card.medium { border-left-color:#f59e0b; }
          .card.high { border-left-color:#f97316; }
          .card.critical { border-left-color:#ef4444; }
          .card-header { display:flex; align-items:center; gap:8px; }
          .rank { font-weight:700; color:#93c5fd; }
          .badge { margin-left:auto; font-size:12px; padding:3px 8px; border-radius:999px; background:#1e293b; }
          .meter-wrap { margin-top:8px; }
          .meter { height:8px; background:#1f2937; border-radius:999px; overflow:hidden; }
          .fill { height:100%; background:linear-gradient(90deg,#22d3ee,#3b82f6); }
          code { background:#1f2937; padding:1px 4px; border-radius:4px; }
          section { margin-bottom:20px; }
          ul { margin: 6px 0 0 20px; }
        </style>
      </head>
      <body>
        <h1>Top 10 Issue Report</h1>
        <p class="muted">Advanced Issue Intelligence and Reporting UX</p>
        $regressionHtml
        $trendHtml
        <section>
          <h2>Issue Cards</h2>
          <div class="grid">$cards</div>
        </section>
      </body>
    </html>
    """.trimIndent()
}

private fun buildRegressionSection(regression: Map<String, Any?>?): String {
    if (regression == null) return ""
    val increased = (regression["increasedLatency"] as? List<*>)?.size ?: 0
    val newSlow = (regression["newSlowMethods"] as? List<*>)?.size ?: 0
    val startup = regression["startupRegression"] as? Map<*, *>
    val startupStatus = startup?.get("status")?.toString() ?: "unknown"
    return """
    <section>
      <h2>Regression Summary</h2>
      <ul>
        <li>Increased latency methods: <strong>$increased</strong></li>
        <li>New slow methods: <strong>$newSlow</strong></li>
        <li>Startup status: <strong>${escape(startupStatus)}</strong></li>
      </ul>
    </section>
    """.trimIndent()
}

private fun buildTrendSection(trends: Map<String, Any?>?): String {
    if (trends == null) return ""
    val historyRuns = (trends["historyRuns"] as? Number)?.toInt() ?: 0
    val hotspots = (trends["consistentHotspots"] as? List<*>)?.take(5).orEmpty()
    val items = if (hotspots.isEmpty()) {
        "<li>No consistent hotspots yet.</li>"
    } else {
        hotspots.joinToString("\n") { raw ->
            val row = raw as? Map<*, *> ?: return@joinToString ""
            val method = row["methodId"]?.toString() ?: "unknown"
            val appearances = row["appearances"]?.toString() ?: "0"
            "<li><code>${escape(method)}</code> appeared in <strong>${escape(appearances)}</strong> runs</li>"
        }
    }
    return """
    <section>
      <h2>Trend Snapshot</h2>
      <p class="muted">History runs analyzed: $historyRuns</p>
      <ul>$items</ul>
    </section>
    """.trimIndent()
}

private fun escape(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
