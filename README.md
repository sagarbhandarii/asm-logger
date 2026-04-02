# Protectt Method Trace Plugin

`com.protectt.methodtrace` is an Android Gradle plugin that instruments bytecode with ASM to measure method execution time at runtime.

This repository includes:

- `build-logic/`: the Gradle plugin implementation.
- `sdk/`: a sample Android library instrumented with the plugin.
- `app/`: a sample Android app that calls `ProtecttSdk.init(...)` during startup.

## What the plugin does

For each instrumented method, the plugin injects:

- `MethodTraceRuntime.enter(methodId)` on method entry.
- `MethodTraceRuntime.exit(methodId, startNanos)` on method exit.

The runtime stores aggregated timing stats and writes `methodtrace-report.json`.

## Documentation map

Use the guide that matches your integration path:

- **General integration and architecture:** `README.md` (this file)
- **Kotlin DSL consumers (`build.gradle.kts`):** `README-kts.md`
- **Groovy DSL consumers (`build.gradle`):** `README-groovy.md`
- **Application-module integration details:** `README-app.md`
- **Library/module integration details:** `README-module.md`

## Quick start

1. Add/apply plugin in your Android module.
2. Ensure the module has `android.namespace` configured.
3. Provide `trace/MethodTraceRuntime` under the module namespace, or let plugin generate one automatically.
4. Build and run; inspect Logcat (`MethodTrace`) and/or JSON report.

## Public extension (`methodTrace { ... }`)

Available properties:

- `enabled: Boolean` (default `true`)
- `includeThirdPartySdks: Boolean` (default `true`)
- `runtimeClassName: String?` (default `<namespace>/trace/MethodTraceRuntime`)
- `includePackagePrefixes: List<String>` (default empty; see module docs)
- `excludeClassPrefixes: List<String>`
- `reportApplicationId: String` (used by `fetchMethodTraceReport` task)
- `reportDevicePath: String` (default `files/methodtrace-report.json`)
- `reportFetchWaitSeconds: Int` (default `60`)

Also exposed (runtime tuning values):

- `startupWindowMs: Long`
- `logEachCall: Boolean`
- `captureThreadName: Boolean`

## Build and validate locally

```bash
./gradlew :sdk:assembleDebug
./gradlew :app:assembleDebug
```

Plugin publication examples are covered in the DSL-specific guides.
