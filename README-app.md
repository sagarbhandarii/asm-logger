# App-Level Integration Guide

Use this guide when applying `com.protectt.methodtrace` in an Android **application module**.

## What app-level integration enables

- Method tracing for app classes.
- Optional tracing for third-party dependencies (`InstrumentationScope.ALL` when supported).
- Runtime report retrieval through `fetchMethodTraceReport`.

## Step-by-step

1. Apply plugin in app module.
2. Set `android.namespace`.
3. Configure `methodTrace` extension.
4. Ensure runtime object exists under `<namespace>/trace/MethodTraceRuntime`.
5. Run app to execute startup/use-case path.
6. Inspect Logcat or pull periodic/background flush report.

## Kotlin DSL app example

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.protectt.methodtrace")
}

android {
    namespace = "com.example.app"
}

methodTrace {
    enabled = true
    includeThirdPartySdks = true
    reportApplicationId = "com.example.app"
    reportDevicePath = "files/methodtrace-report.txt"
    reportFetchWaitSeconds = 20
}
```

## Runtime initialization example

```kotlin
MethodTraceRuntime.enabled = true
MethodTraceRuntime.startupTracingOnly = true
MethodTraceRuntime.startupWindowMs = 20_000L
MethodTraceRuntime.useAppInternalFiles(this, fileName = "methodtrace-report.json")
MethodTraceRuntime.installLifecycleFlush(this, intervalSeconds = 5L)
```

## Inputs and outputs

**Inputs**
- Gradle extension values.
- Runtime flags (`enabled`, startup settings).

**Outputs**
- Aggregated flush lines in Logcat (`Method: X, Calls: N, Avg: Yms, Max: Zms`).
- Text report appended on each periodic/background flush.

## Common errors

- Missing namespace: build fails while resolving defaults.
- Wrong `reportApplicationId`: report pull fails via `run-as`.
- No data in report: no instrumented methods executed in traced window.

## Best practices

- Keep tracing window bounded (`startupTracingOnly=true`) for production-like profiling.
- Use include/exclude prefixes to limit overhead.
- Persist/report only in debug builds if needed.
