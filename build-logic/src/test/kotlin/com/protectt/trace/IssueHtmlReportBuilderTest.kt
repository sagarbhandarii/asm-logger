package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertTrue

class IssueHtmlReportBuilderTest {
    @Test
    fun `builds lightweight html with issue cards and sections`() {
        val issue = Issue(
            issueId = "id1",
            title = "Slow method tail latency",
            category = IssueCategory.SLOW_METHOD,
            severity = IssueSeverity.HIGH,
            summary = "Method has high p95",
            probableRootCause = "Blocking call",
            recommendedFix = "1) move off main thread",
            relatedSignals = emptyList(),
            evidence = emptyMap(),
            affected = AffectedTarget("com.example", "com.example.Foo", "doWork()V"),
            confidenceScore = 0.82,
            score = 123.0,
        )

        val html = buildIssueHtmlReport(
            issues = listOf(issue),
            regression = mapOf(
                "increasedLatency" to listOf(mapOf("methodId" to "com/example/Foo#doWork()V")),
                "newSlowMethods" to emptyList<Map<String, Any?>>(),
                "startupRegression" to mapOf("status" to "worsened"),
            ),
            trends = mapOf(
                "historyRuns" to 3,
                "consistentHotspots" to listOf(mapOf("methodId" to "com/example/Foo#doWork()V", "appearances" to 3)),
            ),
        )

        assertTrue(html.contains("<!doctype html>"))
        assertTrue(html.contains("Issue Cards"))
        assertTrue(html.contains("Regression Summary"))
        assertTrue(html.contains("Trend Snapshot"))
        assertTrue(html.contains("class=\"card high\""))
    }
}
