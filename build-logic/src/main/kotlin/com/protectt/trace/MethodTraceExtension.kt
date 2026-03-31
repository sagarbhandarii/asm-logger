package com.protectt.trace

open class MethodTraceExtension {
    var enabled: Boolean = true

    var includePackagePrefixes: List<String> = listOf("ai/protectt/app/security")
    var excludeClassPrefixes: List<String> = listOf(
        "ai/protectt/app/security/trace/MethodTraceRuntime",
        "ai/protectt/app/security/BuildConfig",
        "ai/protectt/app/security/R",
        "ai/protectt/app/security/R$"
    )
    var startupWindowMs: Long = 15_000L
    var logEachCall: Boolean = true
    var captureThreadName: Boolean = true
}
