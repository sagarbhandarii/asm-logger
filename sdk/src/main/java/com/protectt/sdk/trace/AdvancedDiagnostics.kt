package com.protectt.sdk.trace

import kotlin.math.roundToInt

internal data class StallSummarySnapshot(
    val totalStalls: Long,
    val warningCount: Long,
    val elevatedCount: Long,
    val criticalCount: Long,
    val maxDurationMs: Long,
) {
    fun toJson(): String {
        return """
            {"totalStalls":$totalStalls,"warningCount":$warningCount,"elevatedCount":$elevatedCount,"criticalCount":$criticalCount,"maxDurationMs":$maxDurationMs}
        """.trimIndent()
    }
}

internal class MainThreadStallTracker {
    private val lock = Any()
    private var totalStalls: Long = 0L
    private var warningCount: Long = 0L
    private var elevatedCount: Long = 0L
    private var criticalCount: Long = 0L
    private var maxDurationMs: Long = 0L

    fun record(event: MainThreadStallEvent) {
        if (event.severity == MainThreadStallSeverity.NONE) return
        synchronized(lock) {
            totalStalls += 1
            maxDurationMs = maxOf(maxDurationMs, event.durationMs)
            when (event.severity) {
                MainThreadStallSeverity.WARNING -> warningCount += 1
                MainThreadStallSeverity.ELEVATED -> elevatedCount += 1
                MainThreadStallSeverity.CRITICAL -> criticalCount += 1
                MainThreadStallSeverity.NONE -> Unit
            }
        }
    }

    fun snapshot(): StallSummarySnapshot {
        synchronized(lock) {
            return StallSummarySnapshot(
                totalStalls = totalStalls,
                warningCount = warningCount,
                elevatedCount = elevatedCount,
                criticalCount = criticalCount,
                maxDurationMs = maxDurationMs,
            )
        }
    }
}

internal data class AnrRiskFactor(
    val key: String,
    val value: Double,
    val contribution: Int,
    val explanation: String,
)

internal data class AnrRiskSnapshot(
    val score: Int,
    val level: String,
    val factors: List<AnrRiskFactor>,
) {
    fun toJson(): String {
        return buildString(factors.size * 96 + 64) {
            append('{')
            append("\"score\":").append(score).append(',')
            append("\"level\":\"").append(level).append("\",")
            append("\"factors\":[")
            factors.forEachIndexed { index, factor ->
                if (index > 0) append(',')
                append('{')
                append("\"key\":\"").append(factor.key).append("\",")
                append("\"value\":").append(factor.value).append(',')
                append("\"contribution\":").append(factor.contribution).append(',')
                append("\"explanation\":\"").append(factor.explanation).append("\"")
                append('}')
            }
            append("]}")
        }
    }
}

internal object AnrRiskScorer {
    fun compute(
        startup: StartupSummary,
        frameSummary: FrameSummarySnapshot,
        methodSummary: MethodAggregateTracker.SummarySnapshot,
        stallSummary: StallSummarySnapshot,
    ): AnrRiskSnapshot {
        val factors = mutableListOf<AnrRiskFactor>()

        val stallPoints = (stallSummary.warningCount * 4L) + (stallSummary.elevatedCount * 10L) + (stallSummary.criticalCount * 20L)
        factors += AnrRiskFactor(
            key = "main_thread_stalls",
            value = stallSummary.totalStalls.toDouble(),
            contribution = stallPoints.coerceAtMost(35L).toInt(),
            explanation = "Weighted by warning/elevated/critical stall counts",
        )

        val framePointsRaw = (frameSummary.slowFrames * 2L) + (frameSummary.frozenFrames * 12L)
        factors += AnrRiskFactor(
            key = "janky_frames",
            value = (frameSummary.slowFrames + frameSummary.frozenFrames).toDouble(),
            contribution = framePointsRaw.coerceAtMost(30L).toInt(),
            explanation = "Frozen frames are weighted heavier than slow frames",
        )

        val startupPenalty = if (startup.slowStartup) {
            ((startup.startupDurationMs - startup.slowThresholdMs).coerceAtLeast(0L) / 100L + 8L).coerceAtMost(20L).toInt()
        } else {
            0
        }
        factors += AnrRiskFactor(
            key = "startup_latency",
            value = startup.startupDurationMs.toDouble(),
            contribution = startupPenalty,
            explanation = "Penalty applies only when startup exceeds configured threshold",
        )

        val mainSpanNs = methodSummary.methods.maxOfOrNull { it.mainThreadTotalNs } ?: 0L
        val mainSpanMs = mainSpanNs / 1_000_000.0
        val mainSpanPenalty = when {
            mainSpanMs >= 2000 -> 15
            mainSpanMs >= 1000 -> 10
            mainSpanMs >= 500 -> 5
            else -> 0
        }
        factors += AnrRiskFactor(
            key = "main_thread_span",
            value = mainSpanMs,
            contribution = mainSpanPenalty,
            explanation = "Based on max per-method aggregated main-thread time",
        )

        val totalScore = factors.sumOf { it.contribution }.coerceIn(0, 100)
        return AnrRiskSnapshot(
            score = totalScore,
            level = when {
                totalScore >= 70 -> "high"
                totalScore >= 35 -> "medium"
                else -> "low"
            },
            factors = factors.map { it.copy(value = ((it.value * 100.0).roundToInt()) / 100.0) },
        )
    }
}
