package com.protectt.sdk.trace

internal object StartupPhases {
    const val PROCESS_START_PROXY = "process_start_proxy"
    const val APPLICATION_ON_CREATE_START = "application_on_create_start"
    const val APPLICATION_ON_CREATE_END = "application_on_create_end"
    const val FIRST_ACTIVITY_CREATED = "first_activity_created"
    const val FIRST_FRAME_PROXY = "first_frame_proxy"
    const val SDK_INIT_START = "sdk_init_start"
    const val SDK_INIT_END = "sdk_init_end"
}

internal data class StartupSummary(
    val startupDurationMs: Long,
    val firstFrameLatencyMs: Long,
    val sdkInitDurationMs: Long,
    val sdkContributionPercent: Double,
    val slowStartup: Boolean,
    val slowThresholdMs: Long,
    val markers: List<StartupMarker>,
) {
    fun toJson(): String {
        return buildString(markers.size * 64 + 160) {
            append('{')
            append("\"startupDurationMs\":").append(startupDurationMs).append(',')
            append("\"firstFrameLatencyMs\":").append(firstFrameLatencyMs).append(',')
            append("\"sdkInitDurationMs\":").append(sdkInitDurationMs).append(',')
            append("\"sdkContributionPercent\":").append(sdkContributionPercent).append(',')
            append("\"slowStartup\":").append(slowStartup).append(',')
            append("\"slowThresholdMs\":").append(slowThresholdMs).append(',')
            append("\"markers\":[")
            markers.forEachIndexed { index, marker ->
                if (index > 0) append(',')
                append('{')
                append("\"name\":\"").append(escapeJson(marker.name)).append("\",")
                append("\"elapsedMs\":").append(marker.elapsedMs)
                append('}')
            }
            append("]}")
        }
    }
}

internal data class StartupMarker(
    val name: String,
    val elapsedMs: Long,
)

internal class StartupProfiler(
    private val processStartMs: Long,
    private val startupWindowMsProvider: () -> Long,
) {
    private val lock = Any()
    private val markersByName = LinkedHashMap<String, Long>()

    init {
        mark(StartupPhases.PROCESS_START_PROXY, processStartMs)
    }

    fun mark(name: String, nowMs: Long): StartupMarker? {
        val boundedNow = nowMs.coerceAtLeast(processStartMs)
        synchronized(lock) {
            if (markersByName.containsKey(name)) return null
            markersByName[name] = boundedNow
            return StartupMarker(name = name, elapsedMs = boundedNow - processStartMs)
        }
    }

    fun summary(): StartupSummary {
        synchronized(lock) {
            val ordered = markersByName
                .map { (name, ts) -> StartupMarker(name = name, elapsedMs = (ts - processStartMs).coerceAtLeast(0L)) }
                .sortedBy { it.elapsedMs }

            val firstFrameLatencyMs = markerElapsedMs(StartupPhases.FIRST_FRAME_PROXY)
                ?: markerElapsedMs(StartupPhases.FIRST_ACTIVITY_CREATED)
                ?: markerElapsedMs(StartupPhases.APPLICATION_ON_CREATE_END)
                ?: markerElapsedMs(StartupPhases.APPLICATION_ON_CREATE_START)
                ?: 0L

            val startupDurationMs = firstFrameLatencyMs
            val sdkInitStart = markerElapsedMs(StartupPhases.SDK_INIT_START)
            val sdkInitEnd = markerElapsedMs(StartupPhases.SDK_INIT_END)
            val sdkInitDurationMs = if (sdkInitStart != null && sdkInitEnd != null && sdkInitEnd >= sdkInitStart) {
                sdkInitEnd - sdkInitStart
            } else {
                0L
            }

            val sdkContributionPercent = if (startupDurationMs > 0L) {
                ((sdkInitDurationMs.toDouble() / startupDurationMs.toDouble()) * 100.0).coerceAtLeast(0.0)
            } else {
                0.0
            }

            val slowThresholdMs = startupWindowMsProvider().coerceAtLeast(1L)
            return StartupSummary(
                startupDurationMs = startupDurationMs,
                firstFrameLatencyMs = firstFrameLatencyMs,
                sdkInitDurationMs = sdkInitDurationMs,
                sdkContributionPercent = sdkContributionPercent,
                slowStartup = startupDurationMs > slowThresholdMs,
                slowThresholdMs = slowThresholdMs,
                markers = ordered,
            )
        }
    }

    private fun markerElapsedMs(name: String): Long? {
        return markersByName[name]?.let { (it - processStartMs).coerceAtLeast(0L) }
    }
}

private fun escapeJson(value: String): String {
    val escaped = StringBuilder(value.length + 8)
    value.forEach { ch ->
        when (ch) {
            '\\' -> escaped.append("\\\\")
            '"' -> escaped.append("\\\"")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            else -> escaped.append(ch)
        }
    }
    return escaped.toString()
}
