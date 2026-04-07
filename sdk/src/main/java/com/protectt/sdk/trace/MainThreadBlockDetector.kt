package com.protectt.sdk.trace

internal data class MainThreadStallThresholds(
    val warningMs: Long = 100L,
    val elevatedMs: Long = 250L,
    val criticalMs: Long = 500L,
)

internal enum class MainThreadStallSeverity(val label: String, val rank: Int) {
    NONE("none", 0),
    WARNING("warning", 1),
    ELEVATED("elevated", 2),
    CRITICAL("critical", 3),
}

internal data class MainThreadStallEvent(
    val durationMs: Long,
    val severity: MainThreadStallSeverity,
    val activeMethodId: String?,
)

internal interface RepeatingTask {
    fun cancel()
}

internal interface RepeatingScheduler {
    fun scheduleAtFixedRate(
        initialDelayMs: Long,
        periodMs: Long,
        task: () -> Unit,
    ): RepeatingTask
}

internal class MainThreadBlockDetector(
    private val heartbeatIntervalMs: Long = 50L,
    private val thresholds: MainThreadStallThresholds = MainThreadStallThresholds(),
    private val clockMs: () -> Long,
    private val scheduler: RepeatingScheduler,
    private val postHeartbeat: (Runnable) -> Unit,
    private val activeMethodProvider: () -> String?,
    private val onStall: (MainThreadStallEvent) -> Unit,
) {
    private val lock = Any()

    private var running = false
    private var paused = false
    private var heartbeatPending = false
    private var heartbeatSentAtMs = 0L
    private var maxReportedSeverity = MainThreadStallSeverity.NONE
    private var repeatingTask: RepeatingTask? = null

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            paused = false
            resetForFreshWindow(nowMs = clockMs())
            repeatingTask = scheduler.scheduleAtFixedRate(
                initialDelayMs = heartbeatIntervalMs,
                periodMs = heartbeatIntervalMs,
            ) { watchdogTick() }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            paused = false
            heartbeatPending = false
            maxReportedSeverity = MainThreadStallSeverity.NONE
            repeatingTask?.cancel()
            repeatingTask = null
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!running) return
            paused = true
            resetForFreshWindow(nowMs = clockMs())
        }
    }

    fun resume() {
        synchronized(lock) {
            if (!running) return
            paused = false
            resetForFreshWindow(nowMs = clockMs())
        }
    }

    private fun resetForFreshWindow(nowMs: Long) {
        heartbeatPending = false
        heartbeatSentAtMs = nowMs
        maxReportedSeverity = MainThreadStallSeverity.NONE
    }

    private fun watchdogTick() {
        val shouldPostHeartbeat = synchronized(lock) {
            if (!running || paused) return

            if (!heartbeatPending) {
                heartbeatPending = true
                heartbeatSentAtMs = clockMs()
                true
            } else {
                maybeReportStallLocked(nowMs = clockMs())
                false
            }
        }

        if (shouldPostHeartbeat) {
            postHeartbeat(Runnable { onHeartbeatAck() })
        }
    }

    private fun onHeartbeatAck() {
        synchronized(lock) {
            if (!running || paused) return
            resetForFreshWindow(nowMs = clockMs())
        }
    }

    private fun maybeReportStallLocked(nowMs: Long) {
        val durationMs = (nowMs - heartbeatSentAtMs).coerceAtLeast(0L)
        val severity = classify(durationMs, thresholds)
        if (severity.rank <= maxReportedSeverity.rank) return
        if (severity == MainThreadStallSeverity.NONE) return

        maxReportedSeverity = severity
        onStall(
            MainThreadStallEvent(
                durationMs = durationMs,
                severity = severity,
                activeMethodId = activeMethodProvider(),
            ),
        )
    }
}

internal fun classify(durationMs: Long, thresholds: MainThreadStallThresholds): MainThreadStallSeverity {
    if (durationMs > thresholds.criticalMs) return MainThreadStallSeverity.CRITICAL
    if (durationMs > thresholds.elevatedMs) return MainThreadStallSeverity.ELEVATED
    if (durationMs > thresholds.warningMs) return MainThreadStallSeverity.WARNING
    return MainThreadStallSeverity.NONE
}
