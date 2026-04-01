# Android ASM Method Trace Sample

This project contains:

- `build-logic/`: a standalone Gradle plugin named `com.protectt.methodtrace`
- `sdk/`: a sample Android library instrumented by the plugin
- `app/`: a sample Android app that calls the SDK during startup

## What the plugin does

At build time, ASM injects calls to:

- `<module-namespace>/trace/MethodTraceRuntime.enter(methodId)` at method entry
- `<module-namespace>/trace/MethodTraceRuntime.exit(methodId, startNanos)` at every method exit

By default, it instruments classes from the current module, and for app/test modules it can also instrument third-party dependencies.

## Zero-hardcode defaults

When the plugin is applied to a module, it now automatically derives:

- `runtimeClassName = "<android.namespace>/trace/MethodTraceRuntime"`
- `includeThirdPartySdks = true` (uses `InstrumentationScope.ALL` where AGP supports it; library modules fall back to project scope)
- `includePackagePrefixes = emptyList()` (treat as include all instrumentable classes)
- `excludeClassPrefixes = listOf(runtimeClassName, "<android.namespace>/BuildConfig", "<android.namespace>/R", "<android.namespace>/R$")`

So to integrate in another app/module, you only need to add a runtime object under that module namespace.

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
    includeThirdPartySdks = true
    // Optional: defaults are auto-generated from android.namespace
    runtimeClassName = "com/protectt/sdk/trace/MethodTraceRuntime"
    includePackagePrefixes = listOf("com/protectt/sdk", "com/vendor/sdk")
    excludeClassPrefixes = listOf(
        "com/protectt/sdk/trace/MethodTraceRuntime",
        "com/protectt/sdk/BuildConfig",
        "com/protectt/sdk/R",
        "com/protectt/sdk/R$"
    )
}
```


## Measure third-party SDK time (e.g., `AppProtecttInteractor`)

Yes — you can measure third-party SDK execution time in two ways:

1. **Automatic (preferred):** enable method tracing with `includeThirdPartySdks = true` in the module where the SDK is used.
2. **Manual wrapper timing:** wrap specific calls when you want exact checkpoints around SDK APIs.

Example manual wrapper in your `ApplicationClass`:

```kotlin
private inline fun <T> traceCall(name: String, block: () -> T): T {
    val start = android.os.SystemClock.elapsedRealtimeNanos()
    return try {
        block()
    } finally {
        val tookMs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        android.util.Log.d("SdkTimer", "$name took $tookMs ms")
    }
}

traceCall("AppProtecttInteractor.initAppProtectt") {
    AppProtecttInteractor(applicationContext).initAppProtectt(
        "SplashScreen",
        R.layout.alert_layout_logo,
        R.mipmap.ic_launcher,
        0,
        BuildConfig.BUILD_TYPE,
        2,
        "nonprod",
        ""
    )
}

traceCall("AppProtecttInteractor.getTrust") {
    AppProtecttInteractor.getTrust(applicationContext)
}
```

If callbacks are asynchronous, also log start/end timestamps in the callback so network/wait time is included.

## Generate a shareable plugin

1. Build and publish plugin to the local Maven repo folder (`repo/` in this project):

   ```bash
   ./gradlew :build-logic:publishAllPublicationsToLocalPluginRepoRepository
   ```

2. Share these with consumers:
   - Plugin id: `com.protectt.methodtrace`
   - Plugin version: `2.0.0`
   - Maven coordinates for marker plugin:
     `com.protectt.methodtrace:com.protectt.methodtrace.gradle.plugin:2.0.0`
   - Maven repository URL where you host artifacts (Nexus/Artifactory/GitHub Packages/local file repo copy).

## Host and use from a local Maven repository

1. Set plugin version in `build-logic/build.gradle.kts`:

   ```kotlin
   group = "com.protectt.trace"
   version = "2.0.0"
   ```

2. Publish plugin + marker artifacts into this project local repo folder (`repo/`):

   ```bash
   ./gradlew :build-logic:publishAllPublicationsToLocalPluginRepoRepository
   ```

3. In the consumer project `settings.gradle.kts`, point `pluginManagement.repositories` to the local folder:

   ```kotlin
   pluginManagement {
       repositories {
           google()
           mavenCentral()
           maven(url = uri("file:///ABSOLUTE_PATH_TO/asm-logger/repo"))
           gradlePluginPortal()
       }
   }
   ```

4. Apply plugin by id + version in the consumer module:

   ```kotlin
   plugins {
       id("com.protectt.methodtrace") version "2.0.0"
   }
   ```

## Integrate in another Android app/library

1. Add your plugin repository in `settings.gradle.kts`:

   ```kotlin
   pluginManagement {
       repositories {
           google()
           mavenCentral()
           maven("https://<your-plugin-repo>")
       }
   }
   ```

2. Apply plugin in the target module:

   ```kotlin
   plugins {
       id("com.android.library")
       id("org.jetbrains.kotlin.android")
       id("com.protectt.methodtrace") version "2.0.0"
   }
   ```

3. Create runtime class in target module namespace:
   - If module namespace is `com.client.security`, add:
     `src/main/java/com/client/security/trace/MethodTraceRuntime.kt`
   - Keep `@JvmStatic fun enter(methodId: String): Long` and
     `@JvmStatic fun exit(methodId: String, startNanos: Long)` signatures.

4. (Optional) Override defaults:

   ```kotlin
   methodTrace {
       enabled = true
       // Only if you want non-default paths:
       // runtimeClassName = "com/client/custom/trace/MethodTraceRuntime"
       // includePackagePrefixes = listOf("com/client/security")
       // excludeClassPrefixes = listOf("com/client/security/BuildConfig")
   }
   ```

## Notes

- This project is structured for AGP `8.5.2` and Kotlin `1.9.24`.
- The plugin instruments the `sdk` module because that module applies `com.protectt.methodtrace`.
- Native methods are intentionally skipped because they do not have Java bytecode bodies. If you need JNI timing, add `ATrace_beginSection/ATrace_endSection` in C/C++.
