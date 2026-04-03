# Generate and Publish the Method Trace Plugin

This guide explains how to generate the Gradle plugin artifact (`com.protectt.methodtrace`) from this repository and publish it to the local Maven repository included in this project.

## Prerequisites

- JDK 17 installed and available on `PATH`
- Android/Gradle dependencies accessible from your network
- Run commands from the repository root

## 1) Understand where plugin metadata comes from

Plugin identity is defined in `build-logic/build.gradle.kts`:

- Plugin id: `com.protectt.methodtrace`
- Implementation class: `com.protectt.trace.MethodTracePlugin`
- Group: `com.protectt.trace`
- Version: `2.0.0`
- Publish target repository: `../repo`

## 2) Generate plugin artifacts

Build the plugin module:

```bash
bash ./gradlew :build-logic:assemble
```

This generates the plugin JAR and metadata under `build-logic/build/libs` and related build folders.

## 3) Publish plugin to the local repository in this repo

Publish all plugin publications to `repo/`:

```bash
bash ./gradlew :build-logic:publishAllPublicationsToLocalPluginRepoRepository
```

After publishing, artifacts are available under:

- `repo/com/protectt/trace/build-logic/...`
- `repo/com/protectt/methodtrace/com.protectt.methodtrace.gradle.plugin/...`

## 4) Use the generated plugin in another project

In consumer `settings.gradle.kts` (or `settings.gradle`), add the local Maven repository:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = uri("file:///ABSOLUTE_PATH_TO/asm-logger/repo"))
    }
}
```

Then apply plugin by version in your module:

```kotlin
plugins {
    id("com.protectt.methodtrace") version "2.0.0"
}
```

## 5) Fast local-development alternative (no publish)

This repository already uses a composite build in `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
}
```

With this, modules in this repo can apply `id("com.protectt.methodtrace")` directly without publishing first.

## 6) Validate plugin wiring

Run:

```bash
bash ./gradlew :sdk:assembleDebug
bash ./gradlew :app:assembleDebug
```

If the plugin is active, you should see generated/instrumented behavior and runtime output as described in `README.md`.

---

If your environment blocks Gradle dependency downloads (proxy/firewall), run these commands in a network environment that can access `services.gradle.org`, `google()`, and `mavenCentral()`.
