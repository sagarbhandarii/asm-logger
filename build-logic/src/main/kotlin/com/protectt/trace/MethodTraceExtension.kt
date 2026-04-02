package com.protectt.trace

open class MethodTraceExtension {
    var enabled: Boolean = true
    /**
     * When true, instrument classes coming from external dependencies as well.
     * This is required to capture timings for third-party SDK methods.
     * Note: AGP does not support dependency instrumentation for Android library modules,
     * so library modules are automatically downgraded to project-only instrumentation.
     */
    var includeThirdPartySdks: Boolean = true

    /**
     * JVM internal class name (slash-separated) for runtime hooks.
     * Example: com/example/base/trace/MethodTraceRuntime
     */
    var runtimeClassName: String? = null

    /**
     * Package/class prefixes in JVM internal format.
     * Example package: com/example/base
     * Empty means include all instrumentable classes.
     */
    var includePackagePrefixes: List<String> = emptyList()

    /**
     * Class prefixes in JVM internal format to skip instrumentation.
     */
    var excludeClassPrefixes: List<String> = emptyList()

    var startupWindowMs: Long = 15_000L
    var logEachCall: Boolean = true
    var captureThreadName: Boolean = true

    /**
     * Package id used by `adb shell run-as <package>` for pulling runtime JSON.
     */
    var reportApplicationId: String = ""

    /**
     * Relative or absolute report file path inside app sandbox.
     * Typical value: /data/user/0/<package>/files/methodtrace-report.json
     */
    var reportDevicePath: String = "files/methodtrace-report.json"

    /**
     * Delay before pulling report from connected device.
     */
    var reportFetchWaitSeconds: Int = 60
}

