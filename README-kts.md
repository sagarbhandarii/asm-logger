# Kotlin DSL Integration (`build.gradle.kts`)

This guide shows how to integrate `com.protectt.methodtrace` in Kotlin DSL projects.

## 1) Requirements

- Gradle compatible with AGP 8.5.x.
- Android Gradle Plugin `8.5.2`.
- Kotlin Android plugin `1.9.24`.
- Java 17 toolchain.
- Android module with `android.namespace` set.

## 2) Declare plugin repository

In `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // If consuming a published local repo:
        // maven(url = uri("file:///ABSOLUTE_PATH_TO/asm-logger/repo"))
    }
}
```

## 3) Apply plugin

In the target module `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library") // or com.android.application
    id("org.jetbrains.kotlin.android")
    id("com.protectt.methodtrace")
}
```

If consuming published artifacts instead of composite build:

```kotlin
plugins {
    id("com.protectt.methodtrace") version "2.0.0"
}
```

## 4) Configure plugin

```kotlin
methodTrace {
    enabled = true
    includeThirdPartySdks = true

    // Optional overrides:
    // runtimeClassName = "com/example/sdk/trace/MethodTraceRuntime"
    // includePackagePrefixes = listOf("com/example/sdk")
    // excludeClassPrefixes = listOf("com/example/sdk/BuildConfig", "com/example/sdk/R", "com/example/sdk/R$")

    // For fetchMethodTraceReport task:
    // reportApplicationId = "com.example.app"
    // reportDevicePath = "files/methodtrace-report.json"
    // reportFetchWaitSeconds = 30
}
```

## 5) Runtime initialization

The runtime object must provide:

```kotlin
@JvmStatic fun enter(methodId: String): Long
@JvmStatic fun exit(methodId: String, startNanos: Long)
```

If missing, the plugin can generate `MethodTraceRuntime.kt` under:

`build/generated/source/methodtrace/runtime/<namespace>/trace/`

## 6) Usage example (app startup)

```kotlin
MethodTraceRuntime.enabled = true
MethodTraceRuntime.startupTracingOnly = true
MethodTraceRuntime.startupWindowMs = 20_000L
ProtecttSdk.init(this)
```

## 7) Outputs

- Logcat tag: `MethodTrace`.
- JSON report file: `files/methodtrace-report.json` (default).

Example payload format: see `integration-steps.json`.

## 8) Fetch report from device

```bash
./gradlew :<module>:fetchMethodTraceReport
```

This task uses:

- `adb shell run-as <applicationId> cat <reportDevicePath>`
- Writes `methodtrace-<timestamp>.json` to project root.

## 9) Common issues

- **`Android namespace is required...`**
  - Set `android { namespace = "..." }`.
- **`must be applied after an Android plugin`**
  - Ensure `com.android.application`/`com.android.library` is applied first.
- **Empty report**
  - Verify runtime executed instrumented methods during app run.
