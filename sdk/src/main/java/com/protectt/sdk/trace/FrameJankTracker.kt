package com.protectt.sdk.trace

internal enum class FrameBucket(val label: String) {
    NORMAL("normal"),
    SLOW("slow"),
    FROZEN("frozen"),
}

internal data class FrameThresholds(
    val slowFrameNs: Long = 16_666_667L,
    val frozenFrameNs: Long = 700_000_000L,
)

internal data class FrameHotspotSummary(
    val methodId: String?,
    val totalFrames: Long,
    val slowFrames: Long,
    val frozenFrames: Long,
    val totalDurationNs: Long,
    val startupOnlyDurationNs: Long,
)

internal data class FrameSummarySnapshot(
    val totalFrames: Long,
    val normalFrames: Long,
    val slowFrames: Long,
    val frozenFrames: Long,
    val maxFrameDurationNs: Long,
    val hotspots: List<FrameHotspotSummary>,
) {
    fun toJson(): String {
        return buildString(hotspots.size * 160 + 192) {
            append('{')
            append("\"totalFrames\":").append(totalFrames).append(',')
            append("\"normalFrames\":").append(normalFrames).append(',')
            append("\"slowFrames\":").append(slowFrames).append(',')
            append("\"frozenFrames\":").append(frozenFrames).append(',')
            append("\"maxFrameDurationNs\":").append(maxFrameDurationNs).append(',')
            append("\"hotspots\":[")
            hotspots.forEachIndexed { index, hotspot ->
                if (index > 0) append(',')
                append('{')
                append("\"methodId\":")
                if (hotspot.methodId == null) append("null") else append("\"").append(escapeJson(hotspot.methodId)).append("\"")
                append(',')
                append("\"totalFrames\":").append(hotspot.totalFrames).append(',')
                append("\"slowFrames\":").append(hotspot.slowFrames).append(',')
                append("\"frozenFrames\":").append(hotspot.frozenFrames).append(',')
                append("\"totalDurationNs\":").append(hotspot.totalDurationNs).append(',')
                append("\"startupOnlyDurationNs\":").append(hotspot.startupOnlyDurationNs)
                append('}')
            }
            append("]}")
        }
    }
}

internal fun classifyFrameBucket(durationNs: Long, thresholds: FrameThresholds = FrameThresholds()): FrameBucket {
    val bounded = durationNs.coerceAtLeast(0L)
    if (bounded >= thresholds.frozenFrameNs) return FrameBucket.FROZEN
    if (bounded >= thresholds.slowFrameNs) return FrameBucket.SLOW
    return FrameBucket.NORMAL
}

internal class FrameJankTracker(
    private val thresholds: FrameThresholds = FrameThresholds(),
) {
    private data class MutableHotspot(
        var totalFrames: Long = 0L,
        var slowFrames: Long = 0L,
        var frozenFrames: Long = 0L,
        var totalDurationNs: Long = 0L,
        var startupOnlyDurationNs: Long = 0L,
    )

    private val lock = Any()
    private val hotspotByMethod = LinkedHashMap<String?, MutableHotspot>()
    private var totalFrames: Long = 0L
    private var normalFrames: Long = 0L
    private var slowFrames: Long = 0L
    private var frozenFrames: Long = 0L
    private var maxFrameDurationNs: Long = 0L

    fun record(durationNs: Long, activeMethodId: String?, isStartupWindow: Boolean): FrameBucket {
        val boundedDuration = durationNs.coerceAtLeast(0L)
        val bucket = classifyFrameBucket(durationNs = boundedDuration, thresholds = thresholds)
        synchronized(lock) {
            totalFrames += 1
            maxFrameDurationNs = maxOf(maxFrameDurationNs, boundedDuration)
            when (bucket) {
                FrameBucket.NORMAL -> normalFrames += 1
                FrameBucket.SLOW -> slowFrames += 1
                FrameBucket.FROZEN -> frozenFrames += 1
            }

            if (bucket != FrameBucket.NORMAL) {
                val hotspot = hotspotByMethod.getOrPut(activeMethodId) { MutableHotspot() }
                hotspot.totalFrames += 1
                hotspot.totalDurationNs += boundedDuration
                if (isStartupWindow) {
                    hotspot.startupOnlyDurationNs += boundedDuration
                }
                when (bucket) {
                    FrameBucket.SLOW -> hotspot.slowFrames += 1
                    FrameBucket.FROZEN -> hotspot.frozenFrames += 1
                    FrameBucket.NORMAL -> Unit
                }
            }
        }
        return bucket
    }

    fun snapshot(): FrameSummarySnapshot {
        synchronized(lock) {
            val hotspots = hotspotByMethod.entries
                .map { (methodId, value) ->
                    FrameHotspotSummary(
                        methodId = methodId,
                        totalFrames = value.totalFrames,
                        slowFrames = value.slowFrames,
                        frozenFrames = value.frozenFrames,
                        totalDurationNs = value.totalDurationNs,
                        startupOnlyDurationNs = value.startupOnlyDurationNs,
                    )
                }
                .sortedWith(
                    compareByDescending<FrameHotspotSummary> { it.totalDurationNs }
                        .thenByDescending { it.frozenFrames }
                        .thenByDescending { it.slowFrames }
                )

            return FrameSummarySnapshot(
                totalFrames = totalFrames,
                normalFrames = normalFrames,
                slowFrames = slowFrames,
                frozenFrames = frozenFrames,
                maxFrameDurationNs = maxFrameDurationNs,
                hotspots = hotspots,
            )
        }
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
