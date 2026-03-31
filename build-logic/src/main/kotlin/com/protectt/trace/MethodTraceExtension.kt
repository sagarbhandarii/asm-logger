package com.protectt.trace

open class MethodTraceExtension {
    var enabled: Boolean = true

    /**
     * JVM internal class name (slash-separated) for runtime hooks.
     * Example: com/example/base/trace/MethodTraceRuntime
     */
    var runtimeClassName: String? = null

    /**
     * Package/class prefixes in JVM internal format.
     * Example package: com/example/base
     */
    var includePackagePrefixes: List<String> = emptyList()

    /**
     * Class prefixes in JVM internal format to skip instrumentation.
     */
    var excludeClassPrefixes: List<String> = emptyList()

    var startupWindowMs: Long = 15_000L
    var logEachCall: Boolean = true
    var captureThreadName: Boolean = true
}
