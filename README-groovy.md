# Groovy DSL Integration (`build.gradle`)

This guide shows how to integrate `com.protectt.methodtrace` in Groovy DSL projects.

## 1) Requirements

- Android Gradle Plugin 8.5.x
- Kotlin Android plugin 1.9.x (if Kotlin modules)
- Java 17
- Android module namespace configured

## 2) Configure plugin repository

In `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // For local Maven plugin distribution:
        // maven { url uri('file:///ABSOLUTE_PATH_TO/asm-logger/repo') }
    }
}
```

## 3) Apply plugin

In module `build.gradle`:

```groovy
plugins {
    id 'com.android.library' // or com.android.application
    id 'org.jetbrains.kotlin.android'
    id 'com.protectt.methodtrace'
}
```

Using published version:

```groovy
plugins {
    id 'com.protectt.methodtrace' version '2.0.0'
}
```

## 4) Configure extension

```groovy
methodTrace {
    enabled = true
    includeThirdPartySdks = true

    // Optional
    // runtimeClassName = 'com/example/sdk/trace/MethodTraceRuntime'
    // includePackagePrefixes = ['com/example/sdk']
    // excludeClassPrefixes = ['com/example/sdk/BuildConfig', 'com/example/sdk/R', 'com/example/sdk/R$']

    // Optional (report pull task)
    // reportApplicationId = 'com.example.app'
    // reportDevicePath = 'files/methodtrace-report.json'
    // reportFetchWaitSeconds = 30
}
```

## 5) App/runtime behavior

Instrumentation injects:

- `enter(methodId)` at method entry
- `exit(methodId, startNanos)` at exits

Runtime emits:

- Logcat timing lines (`MethodTrace`)
- JSON report file (`methodtrace-report.json`)

## 6) Fetch JSON from device

```bash
./gradlew :<module>:fetchMethodTraceReport
```

## 7) Troubleshooting

- Ensure `adb` is installed and a device/emulator is connected.
- `run-as` requires debuggable app and matching `reportApplicationId`.
- If library module sets `includeThirdPartySdks=true`, plugin falls back to project scope.
