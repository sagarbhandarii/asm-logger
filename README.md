# Android ASM Method Trace Sample

This project contains:

- `build-logic/`: a standalone Gradle plugin named `com.protectt.methodtrace`
- `sdk/`: a sample Android library instrumented by the plugin
- `app/`: a sample Android app that calls the SDK during startup

## What the plugin does

At build time, ASM injects calls to:

- `com.protectt.sdk.trace.MethodTraceRuntime.enter(methodId)` at method entry
- `com.protectt.sdk.trace.MethodTraceRuntime.exit(methodId, startNanos)` at every method exit

Only classes whose internal JVM names start with `includePackagePrefixes` are instrumented.

## How to run

1. Open the project in Android Studio Jellyfish or later.
2. Let Gradle sync.
3. Run `app` on a device or emulator.
4. Open Logcat and filter by `MethodTrace`.

You should see output like:

```text
com/protectt/sdk/ProtecttSdk#init(Landroid/content/Context;)V took 1900.0 ms [main/MAIN]
com/protectt/sdk/internal/RootChecker#check(Landroid/content/Context;)Z took 800.0 ms [main/MAIN]
com/protectt/sdk/internal/FridaDetector#scanPorts()Z took 350.0 ms [main/MAIN]
```

## Customization

Inside `sdk/build.gradle.kts`:

```kotlin
methodTrace {
    enabled = true
    includePackagePrefixes = listOf("com/protectt/sdk")
    excludeClassPrefixes = listOf(
        "com/protectt/sdk/trace/MethodTraceRuntime",
        "com/protectt/sdk/BuildConfig",
        "com/protectt/sdk/R",
        "com/protectt/sdk/R$"
    )
}
```

## Notes

- This project is structured for AGP `8.5.2` and Kotlin `1.9.24`.
- The plugin instruments the `sdk` module because that module applies `com.protectt.methodtrace`.
- Native methods are intentionally skipped because they do not have Java bytecode bodies. If you need JNI timing, add `ATrace_beginSection/ATrace_endSection` in C/C++.
