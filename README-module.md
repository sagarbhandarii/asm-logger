# Module-Level Integration Guide

Use this guide when integrating `com.protectt.methodtrace` into Android **library modules** (SDK/internal modules).

## Library-module behavior

- Instrumentation scope is project classes.
- If `includeThirdPartySdks=true`, plugin logs a warning and falls back to project-only scope.

## Kotlin DSL module example

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.protectt.methodtrace")
}

android {
    namespace = "com.protectt.sdk"
}

methodTrace {
    enabled = true
    includeThirdPartySdks = true // warning + fallback in library modules

    // Typical optional tuning:
    // includePackagePrefixes = listOf("com/protectt/sdk")
    // excludeClassPrefixes = listOf("com/protectt/sdk/trace/MethodTraceRuntime")
}
```

## Runtime class contract

Place `MethodTraceRuntime` in:

`src/main/java/<namespace-path>/trace/MethodTraceRuntime.kt`

Required API:

```kotlin
@JvmStatic fun enter(methodId: String): Long
@JvmStatic fun exit(methodId: String, startNanos: Long)
```

If absent, plugin generates it into build directory and attaches it to the `main` source set.

## Consumer integration point

When used from an app module, any library calls (for example `ProtecttSdk.init(context)`) execute instrumented library code and emit trace data.

## Compatibility notes

- Designed for AGP 8.5.x instrumentation API.
- Requires JVM target 17 in this repository setup.

## Troubleshooting checklist

- Verify plugin applied after Android plugin.
- Verify namespace-to-runtime path matches exactly.
- Validate BuildConfig/R are excluded if using custom exclude list.
