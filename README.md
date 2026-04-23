# Protectt Method Trace Plugin (`com.protectt.methodtrace`)

This project provides an ASM-based Android Gradle plugin that injects method-entry/method-exit hooks into app bytecode and records runtime timing data as JSON.

- **Plugin implementation:** `build-logic/`
- **Runtime implementation (rich SDK runtime):** `sdk/src/main/java/com/protectt/sdk/trace/`
- **Sample app using runtime:** `app/`

---

## 1) Internal implementation with ASM (bytecode transformation flow)

### 1.1 Registration in AGP instrumentation pipeline

`MethodTracePlugin` registers a class transformer for every Android variant:

```kotlin
variant.instrumentation.transformClassesWith(
    MethodTraceVisitorFactory::class.java,
    if (allowDependencyInstrumentation) InstrumentationScope.ALL else InstrumentationScope.PROJECT,
) { params ->
    params.enabled.set(project.provider { extension.enabled })
    params.includePackagePrefixes.set(project.provider { includePrefixes })
    params.excludeClassPrefixes.set(project.provider { excludePrefixes })
    params.runtimeClassName.set(project.provider { runtimeClassName })
    params.activeProbeIds.set(project.provider { activeProbeIds })
}
```

Key points:
- `InstrumentationScope.ALL` is used for app modules when `includeThirdPartySdks=true`.
- Library modules are downgraded to project-only instrumentation.
- ASM frame mode is `COPY_FRAMES` to keep bytecode verification stable.

### 1.2 Instrumentation eligibility filtering

`MethodTraceVisitorFactory.isInstrumentable(...)` delegates to `MethodInstrumentationDecision.shouldInstrument(...)`:

- skip if plugin `enabled=false`
- skip if method probe is not active (`ProbeIds.METHOD_TIMING` missing)
- skip runtime class itself (`className == runtimeClassName`)
- apply include prefix filter (`includePackagePrefixes`)
- apply exclude prefix filter (`excludeClassPrefixes`)

### 1.3 Class/method visitor logic

`MethodTraceClassVisitor` builds method IDs as:

```text
<classInternalName>#<methodName><descriptor>
```

Example:

```text
com/example/app/MainActivity#onCreate(Landroid/os/Bundle;)V
```

The class visitor intentionally **skips**:
- constructors `<init>`
- static initializers `<clinit>`
- `abstract` methods
- `native` methods
- compiler accessor bridges like `access$...`
- likely trivial getter/setter methods (`getX()`, `isX()`, `setX(...)` heuristics)

### 1.4 Bytecode injected by `MethodTraceMethodVisitor`

Uses `AdviceAdapter` hooks:

- `onMethodEnter()` injects:
  1) `LDC methodId`
  2) `INVOKESTATIC <runtime>.enter(Ljava/lang/String;)J`
  3) stores returned `long` into a new local variable

- `onMethodExit(opcode)` injects (for normal returns and throw):
  1) `LDC methodId`
  2) load start-nanos local
  3) `INVOKESTATIC <runtime>.exit(Ljava/lang/String;J)V`

So each instrumented method has a balanced `enter/exit` pair even on exceptional exits (`ATHROW`).

---

## 2) How runtime logs are captured (Logcat, JSON file, custom logger/sink)

Runtime capture is implemented in `MethodTraceRuntime`.

### 2.1 Logcat capture

Main-thread slow calls are logged automatically:
- warning above `100ms`
- critical above `300ms`

Example emitted log tags/messages:
- `MethodTrace` + `[MAIN][WARN] Method ... took ...ms`
- `MethodTrace` + `[MAIN][CRITICAL] Method ... took ...ms`

Main-thread stall detector events are also logged with severity buckets.

### 2.2 JSON trace file capture

Events are buffered in memory (`pendingEvents`) and flushed asynchronously to file using `TraceSink`.

Default sink is `JsonTraceSink`, which writes Chrome trace-like JSON format:

```json
{"traceEvents":[ ... ]}
```

You can control output file location by:

```kotlin
MethodTraceRuntime.setOutputFilePath("/absolute/path/methodtrace-report.json")
```

or app-scoped internal files:

```kotlin
MethodTraceRuntime.useAppInternalFiles(application, "methodtrace-report.json")
```

### 2.3 Custom logger/sink

You can replace default JSON file sink with your own `TraceSink` implementation:

```kotlin
class MySink : TraceSink {
    override fun appendEvents(events: List<String>) {
        // send to backend / custom file / structured logger
    }
}

MethodTraceRuntime.setTraceSink(MySink())
```

This is the extension point for non-file logging pipelines.

---

## 3) Exact JSON-report generation flow

There are **two outputs** at runtime, then optional Gradle-side post-processing.

### 3.1 Runtime event collection (where data is collected)

At method exit:
1. `exit(methodId, startNanos)` computes duration
2. updates aggregate stats (`MethodAggregateTracker.record(...)`)
3. converts call into event JSON (`buildEventJson(...)`)
4. enqueues event string into `pendingEvents`

Also, runtime may add integration events (frame jank, stalls, coroutine transitions, network/db timing hooks when enabled).

### 3.2 Data structures used

Core runtime structures:

- `ThreadLocal<ThreadTraceState>`
  - `jsonBuilder: StringBuilder`
  - `callStack: ArrayDeque<CallFrame>`
- `CallFrame(methodId, startNs, childDurationNs)` for self-time calculation
- `pendingEvents: ArrayDeque<String>` (global event queue, lock-protected)
- `MethodAggregateTracker` for method summaries and percentile estimation
- `ConcurrentHashMap<Long, String> activeMethodByThreadId` for correlation/stall attribution

### 3.3 When JSON is written (runtime vs post-processing)

#### Runtime write path

- Event JSON (`traceEvents`) is written **during runtime** in async batches:
  - periodic (`startPeriodicFlush`)
  - buffer-full trigger
  - app background transition
  - manual `flushNow(...)`

- Summary JSON (`methodtrace-summary.json`) is written **during runtime** when aggregate data is dirty (`summaryDirty`) and async flush runs.

#### Post-processing write path (Gradle task)

`fetchMethodTraceReport` runs on host machine and:
1. waits configurable seconds
2. uses `adb shell run-as <package> cat <path>`
3. parses JSON
4. sorts methods by `totalNs`
5. adds rankings/regressions/trends/top issues
6. writes local files like `methodtrace-<timestamp>.json`, markdown, and issue reports

So JSON is both runtime-generated and optionally enhanced after pull.

---

## 4) Sample JSON output and field-by-field explanation

### 4.1 Runtime trace-events file (`method_trace.json` / configured path)

```json
{
  "traceEvents": [
    {
      "name": "com.example.app.MainActivity.onCreate",
      "ph": "X",
      "ts": 12450,
      "dur": 412,
      "pid": 0,
      "tid": 1
    }
  ]
}
```

Fields:
- `name`: formatted method identity (`owner.method` from internal methodId)
- `ph`: event phase (`"X"` = complete event)
- `ts`: start timestamp (microseconds since runtime trace start)
- `dur`: duration (microseconds)
- `pid`: process id placeholder (`0` in this runtime)
- `tid`: Java thread id

### 4.2 Runtime summary file (`methodtrace-summary.json`)

```json
{
  "generatedAtEpochMs": 1713870000000,
  "traceContext": {"traceId": "a1b2c3d4e5f60789", "sessionId": "0123456789abcdef"},
  "methods": [
    {
      "methodId": "com/example/app/MainActivity#onCreate(Landroid/os/Bundle;)V",
      "callCount": 3,
      "totalNs": 9123456,
      "maxNs": 4123456,
      "minNs": 2012345,
      "p50Ns": 2987654,
      "p95Ns": 4123456,
      "p99Ns": 4123456,
      "selfTotalNs": 7023456,
      "mainThreadTotalNs": 9123456,
      "startupTotalNs": 8123456
    }
  ]
}
```

Main method fields:
- `methodId`: raw ASM identity with descriptor
- `callCount`: number of completed invocations
- `totalNs`: sum of durations
- `maxNs` / `minNs`: extrema
- `p50Ns` / `p95Ns` / `p99Ns`: percentile estimates
- `selfTotalNs`: total exclusive time (child durations removed)
- `mainThreadTotalNs`: portion executed on main thread
- `startupTotalNs`: portion inside startup window

---

## 5) Thread handling and performance considerations

### Thread model

- Per-thread state via `ThreadLocal` avoids cross-thread contention for call-stack and JSON builder.
- Shared queue (`pendingEvents`) is synchronized only during enqueue/dequeue.
- File writes happen on dedicated single-thread executor (`method-trace-writer`).
- Periodic flushing uses separate scheduled executor (`method-trace-flush`).

### Performance controls

- Startup-only mode: `startupTracingOnly=true` limits tracing window.
- Queue thresholds prevent unbounded memory growth (`MAX_BUFFERED_EVENTS`).
- Batch flushes reduce per-event IO overhead.
- Getter/setter and trivial method skipping reduces instrumentation noise.
- Potential overhead hotspots:
  - very high call-rate methods still incur entry/exit cost
  - synchronized queue pressure under extreme throughput
  - frequent summary flushes if app churns heavily

---

## 6) Injected bytecode example (before vs after)

### Source method

```kotlin
fun work(x: Int): Int {
    return x * 2
}
```

### Conceptual bytecode before

```text
ILOAD 1
ICONST_2
IMUL
IRETURN
```

### Conceptual bytecode after instrumentation

```text
LDC "com/example/MyClass#work(I)I"
INVOKESTATIC com/example/trace/MethodTraceRuntime.enter (Ljava/lang/String;)J
LSTORE 2

ILOAD 1
ICONST_2
IMUL

LDC "com/example/MyClass#work(I)I"
LLOAD 2
INVOKESTATIC com/example/trace/MethodTraceRuntime.exit (Ljava/lang/String;J)V
IRETURN
```

For exception paths, equivalent `exit(...)` call is injected before `ATHROW` as well.

---

## 7) Enable / disable logging and instrumentation

### Build-time (plugin extension)

```kotlin
methodTrace {
    enabled = true
    includeThirdPartySdks = true
    includePackagePrefixes = listOf("com/example")
    excludeClassPrefixes = listOf("com/example/generated/")
}
```

Disable all instrumentation:

```kotlin
methodTrace {
    enabled = false
}
```

### Runtime toggles

```kotlin
MethodTraceRuntime.enabled = true
MethodTraceRuntime.startupTracingOnly = true
MethodTraceRuntime.startupWindowMs = 15_000L
MethodTraceRuntime.flushIntervalSeconds = 5L
```

Disable runtime tracing without rebuilding:

```kotlin
MethodTraceRuntime.enabled = false
```

---

## 8) Limitations and edge cases

1. **Skipped methods by design**
   - constructors/static init/native/abstract/accessors/trivial getters-setters are not instrumented.

2. **Library module third-party instrumentation**
   - AGP scope limitation: dependency instrumentation is not supported for Android library modules; plugin warns and falls back.

3. **Method identity granularity**
   - ID is owner + name + descriptor; obfuscation can reduce readability unless mapping is retained.

4. **Runtime class exclusion is mandatory**
   - runtime package/class must stay excluded to prevent recursion.

5. **Asynchronous flush timing**
   - abrupt process death can lose in-memory buffered events not yet flushed.

6. **File path / permissions variance**
   - output path differs by configured sink and app context (internal vs external files dir fallback).

7. **Sampling/percentile approximation behavior**
   - percentile stats are estimated from bounded samples in `MethodAggregateTracker`, not exact full-history quantiles.

8. **Potential overhead in very hot code paths**
   - even lightweight probes can be non-trivial for extremely high-frequency methods.

---

## Key internal classes reference

### Plugin/instrumentation side
- `MethodTracePlugin`
- `MethodTraceVisitorFactory`
- `MethodTraceClassVisitor`
- `MethodTraceMethodVisitor`
- `MethodInstrumentationDecision`
- `GenerateMethodTraceRuntimeTask`
- `FetchMethodTraceReportTask`

### Runtime side
- `MethodTraceRuntime`
- `TraceSink` / `JsonTraceSink`
- `MethodAggregateTracker`
- `MainThreadBlockDetector`, `FrameMetricsCollector`, `CoroutineTraceTracker` (advanced signals)

---

## Minimal integration snippet

```kotlin
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MethodTraceRuntime.useAppInternalFiles(this, "methodtrace-report.json")
        MethodTraceRuntime.installLifecycleFlush(this)
    }
}
```

Then pull/enhance report locally:

```bash
./gradlew :app:fetchMethodTraceReport
```
