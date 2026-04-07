package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueAnalyzerTest {
    @Test
    fun `detects phase1 categories and returns at most top 10`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Foo#slowMain()V",
                "callCount" to 30L,
                "totalNs" to 2_500_000_000L,
                "maxNs" to 700_000_000L,
                "p95Ns" to 300_000_000L,
                "mainThreadTotalNs" to 1_600_000_000L,
                "startupTotalNs" to 1_000_000_000L,
            ),
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Foo#chatty()V",
                "callCount" to 300L,
                "totalNs" to 1_000_000_000L,
                "maxNs" to 50_000_000L,
                "p95Ns" to 20_000_000L,
                "mainThreadTotalNs" to 200_000_000L,
                "startupTotalNs" to 100_000_000L,
            ),
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Boot#init()V",
                "callCount" to 8L,
                "totalNs" to 900_000_000L,
                "maxNs" to 180_000_000L,
                "p95Ns" to 130_000_000L,
                "mainThreadTotalNs" to 300_000_000L,
                "startupTotalNs" to 700_000_000L,
            ),
        )

        val root = mapOf<String, Any?>(
            "startup" to mapOf("startupDurationMs" to 2_000L),
            "traceEvents" to listOf(
                mapOf("name" to "main_thread_stall", "args" to mapOf("activeMethod" to "com/example/Foo#slowMain()V")),
                mapOf("name" to "main_thread_stall", "args" to mapOf("activeMethod" to "com/example/Foo#slowMain()V")),
            ),
        )

        val result = IssueAnalyzer(topN = 10).analyze(root = root, summaryMethods = methods)

        assertTrue(result.issues.isNotEmpty())
        assertTrue(result.issues.size <= 10)
        val categories = result.issues.map { it.category }.toSet()
        assertTrue(IssueCategory.SLOW_METHOD in categories)
        assertTrue(IssueCategory.HIGH_CUMULATIVE_COST in categories)
        assertTrue(IssueCategory.MAIN_THREAD_BLOCKING in categories)
        assertTrue(IssueCategory.STARTUP_BOTTLENECK in categories)
    }

    @Test
    fun `detects correlation based issue categories`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Foo#doWork()V",
                "callCount" to 25L,
                "totalNs" to 2_200_000_000L,
                "maxNs" to 500_000_000L,
                "p95Ns" to 220_000_000L,
                "mainThreadTotalNs" to 500_000_000L,
                "startupTotalNs" to 300_000_000L,
            ),
        )
        val root = mapOf<String, Any?>(
            "startup" to mapOf("startupDurationMs" to 2_000L),
            "exceptions" to listOf(
                mapOf("activeSpans" to listOf("1:com/example/Foo#doWork()V")),
                mapOf("activeSpans" to listOf("1:com/example/Foo#doWork()V")),
            ),
            "traceEvents" to listOf(
                mapOf(
                    "name" to "network:GET",
                    "dur" to 500_000,
                    "args" to mapOf(
                        "activeSpanId" to "com/example/Foo#doWork()V",
                        "host" to "api.example.com",
                        "method" to "GET",
                        "pathTemplate" to "/users/:id",
                        "failed" to false,
                    ),
                ),
                mapOf(
                    "name" to "network:GET",
                    "dur" to 300_000,
                    "args" to mapOf(
                        "activeSpanId" to "com/example/Foo#doWork()V",
                        "host" to "api.example.com",
                        "method" to "GET",
                        "pathTemplate" to "/users/:id",
                        "failed" to true,
                    ),
                ),
                mapOf(
                    "name" to "db:SELECT:ok",
                    "dur" to 220_000,
                    "args" to mapOf(
                        "activeSpanId" to "com/example/Foo#doWork()V",
                        "operation" to "SELECT",
                    ),
                ),
                mapOf(
                    "name" to "db:SELECT:ok",
                    "dur" to 180_000,
                    "args" to mapOf(
                        "activeSpanId" to "com/example/Foo#doWork()V",
                        "operation" to "SELECT",
                    ),
                ),
            ),
        )

        val result = IssueAnalyzer(topN = 10).analyze(root = root, summaryMethods = methods)
        val categories = result.issues.map { it.category }.toSet()

        assertTrue(IssueCategory.EXCEPTION_CORRELATED_SLOW_PATH in categories)
        assertTrue(IssueCategory.NETWORK_LATENCY_HOTSPOT in categories || IssueCategory.CORRELATED_PERFORMANCE_CLUSTER in categories)
        assertTrue(IssueCategory.DB_QUERY_BOTTLENECK in categories || IssueCategory.CORRELATED_PERFORMANCE_CLUSTER in categories)
    }

    @Test
    fun `merges correlated io and exception signals into cluster issue`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Foo#hot()V",
                "callCount" to 20L,
                "totalNs" to 2_000_000_000L,
                "maxNs" to 600_000_000L,
                "p95Ns" to 250_000_000L,
                "mainThreadTotalNs" to 600_000_000L,
                "startupTotalNs" to 200_000_000L,
            ),
        )
        val root = mapOf<String, Any?>(
            "exceptions" to listOf(
                mapOf("activeSpans" to listOf("1:com/example/Foo#hot()V")),
            ),
            "traceEvents" to listOf(
                mapOf("name" to "network:GET", "dur" to 300_000, "args" to mapOf("activeSpanId" to "com/example/Foo#hot()V", "host" to "api", "method" to "GET", "pathTemplate" to "/", "failed" to false)),
                mapOf("name" to "network:GET", "dur" to 260_000, "args" to mapOf("activeSpanId" to "com/example/Foo#hot()V", "host" to "api", "method" to "GET", "pathTemplate" to "/", "failed" to false)),
                mapOf("name" to "db:SELECT:ok", "dur" to 160_000, "args" to mapOf("activeSpanId" to "com/example/Foo#hot()V", "operation" to "SELECT")),
                mapOf("name" to "db:SELECT:ok", "dur" to 140_000, "args" to mapOf("activeSpanId" to "com/example/Foo#hot()V", "operation" to "SELECT")),
            ),
        )

        val result = IssueAnalyzer(topN = 10).analyze(root = root, summaryMethods = methods)
        assertTrue(result.issues.any { it.category == IssueCategory.CORRELATED_PERFORMANCE_CLUSTER })
    }

    @Test
    fun `deduplicates similar issues per category and method`() {
        val method = mutableMapOf<String, Any?>(
            "methodId" to "com/example/Foo#dup()V",
            "callCount" to 20L,
            "totalNs" to 2_000_000_000L,
            "maxNs" to 500_000_000L,
            "p95Ns" to 250_000_000L,
            "mainThreadTotalNs" to 1_200_000_000L,
            "startupTotalNs" to 900_000_000L,
        )
        val root = mapOf<String, Any?>(
            "startup" to mapOf("startupDurationMs" to 2_500L),
            "traceEvents" to listOf(
                mapOf("name" to "main_thread_stall", "args" to mapOf("activeMethod" to "com/example/Foo#dup()V")),
            ),
        )

        val result = IssueAnalyzer(topN = 10).analyze(root = root, summaryMethods = listOf(method))
        val keys = result.issues.map { "${it.category.value}:${it.affected.className}:${it.affected.method}:${it.title}" }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `output formats contain required fields including phase2 additions`() {
        val methods = listOf(
            mutableMapOf<String, Any?>(
                "methodId" to "com/example/Foo#slow()V",
                "callCount" to 10L,
                "totalNs" to 500_000_000L,
                "maxNs" to 200_000_000L,
                "p95Ns" to 120_000_000L,
                "mainThreadTotalNs" to 200_000_000L,
                "startupTotalNs" to 100_000_000L,
            ),
        )
        val root = mapOf<String, Any?>("startup" to mapOf("startupDurationMs" to 1_000L))
        val result = IssueAnalyzer(topN = 10).analyze(root = root, summaryMethods = methods)
        val json = result.toJson()
        val markdown = result.toMarkdown()

        assertTrue(json.contains("\"issueId\""))
        assertTrue(json.contains("\"title\""))
        assertTrue(json.contains("\"category\""))
        assertTrue(json.contains("\"severity\""))
        assertTrue(json.contains("\"summary\""))
        assertTrue(json.contains("\"probableRootCause\""))
        assertTrue(json.contains("\"recommendedFix\""))
        assertTrue(json.contains("\"relatedSignals\""))
        assertTrue(json.contains("\"evidence\""))
        assertTrue(json.contains("\"affected\""))
        assertTrue(json.contains("\"confidenceScore\""))
        assertTrue(markdown.contains("# Top 10 Issue Report"))
        assertTrue(markdown.contains("Probable root cause"))
    }
}
