package com.protectt.trace

object MethodInstrumentationDecision {
    fun shouldInstrument(
        enabled: Boolean,
        activeProbeIds: List<String>,
        className: String,
        includePrefixes: List<String>,
        excludePrefixes: List<String>,
        runtimeClassName: String,
    ): Boolean {
        if (!enabled) return false
        if (!activeProbeIds.contains(ProbeIds.METHOD_TIMING)) return false
        if (className == runtimeClassName) return false

        val included = includePrefixes.isEmpty() || includePrefixes.any { prefix -> className.startsWith(prefix) }
        if (!included) return false

        val excluded = excludePrefixes.any { prefix -> className.startsWith(prefix) }
        return !excluded
    }
}
