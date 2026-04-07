# Feature Proposal: Method Trace → Debugging & Performance Analysis Framework

## 1) Current Capabilities (as implemented today)

### 1.1 Build-time plugin and instrumentation model
- A custom Gradle plugin (`com.protectt.methodtrace`) instruments bytecode through ASM using AGP’s instrumentation API.
- It injects `MethodTraceRuntime.enter(methodId)` at method entry and `MethodTraceRuntime.exit(methodId, startNanos)` at method exit.
- Instrumentation can run on:
  - Project classes only (`InstrumentationScope.PROJECT`) for library modules.
  - All classes including dependencies (`InstrumentationScope.ALL`) for app modules when enabled.
- The plugin supports include/exclude class prefix filters and runtime class override (`runtimeClassName`).
- If runtime source is missing, plugin auto-generates a `MethodTraceRuntime` source under the module namespace.

### 1.2 Runtime tracing behavior
- Runtime captures method durations from `elapsedRealtimeNanos` and writes Chrome/Perfetto trace-compatible JSON (`traceEvents`) with complete events (`ph: "X"`).
- Data is buffered in memory and flushed asynchronously to a trace file.
- Lifecycle-based flush is provided via `Application.ActivityLifecycleCallbacks`; periodic flush is supported.
- Startup-focused tracing is supported by `startupTracingOnly` and `startupWindowMs`.
- Main-thread slow calls are logged with warning/critical thresholds.

### 1.3 Current integration shape in repository
- SDK module applies the plugin and gets instrumented.
- App module initializes runtime and calls SDK initialization in `Application.onCreate`.
- A Gradle task (`fetchMethodTraceReport`) exists for adb pull/parsing/sorting of a JSON report, but current runtime primarily emits trace-event JSON (not rich aggregated diagnostics yet).

### 1.4 Gaps observed
- Method timing exists, but there is no standardized latency taxonomy (CPU vs lock wait vs I/O vs suspend/resume for coroutines).
- No explicit startup milestone markers (cold/warm/hot startup segments).
- No integrated ANR-risk signal model (main-looper stall trends, binder waits, lock-contention hints).
- No coroutine context propagation (trace continuity across dispatchers).
- No built-in network/DB hooks, frame-jank feed, GC/memory pressure correlation, or exception-performance correlation.
- No automatic hotspot summarizer (top offenders by p95/p99/self-time/inclusive-time) in runtime/tooling.
- Plugin architecture is currently single-purpose (method enter/exit) and should evolve to modular probes.

---

## 2) Proposed Feature Set (Prioritized)

## High Priority (production-friendly, immediate value)

### HP-1: Method timing v2 (inclusive + self-time + percentile summaries)
**Why useful**
- Current timing gives raw durations, but teams need ranked hotspots and tail-latency (p95/p99) to act quickly.

**How it works**
- Keep current complete events for Perfetto.
- Add lightweight in-memory aggregates per method:
  - callCount, totalNs, maxNs, minNs, p50/p95/p99 (approx via fixed histogram bins or DDSketch-like strategy).
  - self-time approximation by tracking nested depth per thread and subtracting child durations.
- Flush aggregate snapshots separately (`methodtrace-summary.json`).

**Where to implement**
- Runtime layer (`MethodTraceRuntime`) for aggregation logic.
- Plugin layer optional: flag to enable/disable self-time stack tracking.

**Risks / perf impact**
- Extra per-call overhead from per-thread stacks and histogram updates.
- Mitigation: sample rate, debug-only mode, and bounded histograms.

**Layer ownership**
- Runtime + plugin config.

---

### HP-2: Main-thread blocking detector (looper stall monitor)
**Why useful**
- Main-thread blocking is directly tied to jank and ANR risk; method-only traces can miss idle gaps/stalls.

**How it works**
- Add a watchdog:
  - `Handler(Looper.getMainLooper())` posts heartbeat tokens.
  - Background thread checks missed heartbeats and records stall events (e.g., >100ms, >250ms, >500ms).
- Correlate stalls with currently active top-of-stack method (if available) and emit dedicated trace events/counters.

**Where to implement**
- Runtime module (app + SDK runtime common code).
- Optional app integration for process-wide enablement from `Application`.

**Risks / perf impact**
- Small overhead from periodic main-thread posts.
- False positives on background/foreground transitions unless lifecycle-aware.

**Layer ownership**
- App runtime integration + shared runtime core.

---

### HP-3: Startup profiler (phase markers + slow-start detector)
**Why useful**
- Startup regressions are high-impact and often involve both app and SDK initialization.

**How it works**
- Add explicit startup markers:
  - Process start, `Application.onCreate`, first activity created, first frame drawn, SDK init start/end.
- Compute startup KPIs (TTID-like proxy, first-frame latency, SDK share of startup cost).
- Auto-flag when thresholds are crossed and include top contributors during startup window.

**Where to implement**
- App layer: lifecycle/frame callbacks.
- SDK layer: API entry/exit markers in `ProtecttSdk.init` and key internal tasks.
- Runtime: centralized metric collection/serialization.

**Risks / perf impact**
- Minimal if callback-based and bounded buffering.

**Layer ownership**
- App + SDK + runtime.

---

### HP-4: Exception/performance correlation
**Why useful**
- Slow paths and failures often co-occur; correlation shortens RCA time.

**How it works**
- Install optional uncaught-exception hook + explicit API for handled exceptions.
- On exception capture:
  - current thread, recent method timeline slice, startup phase, memory snapshot.
- Emit synthetic trace event linking exception type/message hash to nearby spans.

**Where to implement**
- Runtime layer primarily.
- SDK layer for safe wrapper APIs around sensitive operations.

**Risks / perf impact**
- Privacy/security concerns (exception messages may contain PII).
- Mitigation: hash/sanitize fields; configurable redaction.

**Layer ownership**
- Runtime + SDK API wrappers.

---

### HP-5: Plugin modularization for future probes
**Why useful**
- Current single ASM visitor limits extensibility; modular probes reduce risk and speed future additions.

**How it works**
- Introduce probe contracts:
  - `MethodProbe`, `NetworkProbe`, `DbProbe`, `CoroutineProbe`, etc.
- Plugin extension gets module-level toggles and thresholds per probe.
- Keep method timing probe as baseline module.

**Where to implement**
- Build-time plugin architecture (`build-logic`).

**Risks / perf impact**
- Complexity in transform pipeline and compatibility tests.
- Mitigation: versioned probe API + strict defaults off for advanced probes.

**Layer ownership**
- Plugin/build-time instrumentation.

---

## Medium Priority (high value, moderate complexity)

### MP-1: Coroutine tracing (context propagation + suspend boundaries)
**Why useful**
- SDK and app code often use coroutines; current traces lose continuity across dispatcher switches.

**How it works**
- Add optional `ThreadContextElement` carrying trace context ID.
- Record events on coroutine resume/suspend and dispatcher switch.
- Group method spans into logical async transactions.

**Where to implement**
- Runtime layer with optional kotlin-coroutines integration artifact.
- Plugin optionally instruments known suspend state-machine patterns conservatively.

**Risks / perf impact**
- Incorrect context propagation can create noisy traces.
- Mitigation: opt-in + strict tests for cancellation/exception paths.

**Layer ownership**
- Runtime + optional build-time hooks.

---

### MP-2: Network timing hooks
**Why useful**
- Network is a major latency source; method traces alone cannot reveal DNS/connect/TLS/server breakdown.

**How it works**
- Provide OkHttp interceptor module (opt-in): request start/end, DNS/connect/TLS/TTFB/bytes.
- Emit span events with request metadata (host/path template/status, redacted query/body).
- Correlate with active method or transaction ID.

**Where to implement**
- SDK/app runtime integration library (not ASM first).

**Risks / perf impact**
- Potential sensitive data leakage.
- Mitigation: strict redaction defaults + allowlist keys.

**Layer ownership**
- App + SDK runtime module.

---

### MP-3: DB/query timing hooks
**Why useful**
- Slow SQLite/Room queries commonly cause jank and startup delay.

**How it works**
- Optional wrappers/interceptors for Room `QueryCallback` or SQLite APIs.
- Capture SQL operation class, table hints, duration, thread, and row-count estimate.

**Where to implement**
- App layer integrations and optional SDK module hooks.

**Risks / perf impact**
- SQL text logging can expose PII.
- Mitigation: obfuscate literals, keep operation signatures only.

**Layer ownership**
- App + SDK runtime integration.

---

### MP-4: Frame rendering/jank analysis
**Why useful**
- Directly ties runtime behavior to user-perceived smoothness.

**How it works**
- API 24+: use `FrameMetricsAggregator`/`Choreographer` callbacks.
- Emit frame buckets (normal, slow, frozen) and correlate with concurrent method spans.

**Where to implement**
- App layer runtime integration (UI process only).

**Risks / perf impact**
- Minimal callback overhead; API/version variability.

**Layer ownership**
- App layer + runtime.

---

### MP-5: Hotspot auto-detection and recommendation engine
**Why useful**
- Raw traces are hard to triage; automated ranking accelerates debugging.

**How it works**
- Offline report processor computes:
  - top methods by total, p95, max, main-thread-only cost, startup-only cost.
  - regression flags vs baseline snapshots.
- Emit markdown/JSON summaries for CI artifacts.

**Where to implement**
- Build-time/report task side (`FetchMethodTraceReportTask` successor) + optional standalone CLI.

**Risks / perf impact**
- Mostly post-processing cost, low runtime impact.

**Layer ownership**
- Plugin/reporting tooling.

---

## Advanced / Future Improvements

### AP-1: ANR-risk scoring model
**Why useful**
- Provides proactive signal before real ANRs appear in production.

**How it works**
- Weighted score from:
  - main-thread stall frequency/severity,
  - long input handling spans,
  - binder/lock wait proxies,
  - frame freeze rate.
- Score emitted per session/startup.

**Where to implement**
- Runtime analytics module + report aggregator.

**Risks / perf impact**
- Risk of misleading score if heuristics are weak.
- Mitigation: explainable sub-signals + confidence band.

**Layer ownership**
- Runtime + analytics/report layer.

---

### AP-2: Perfetto SDK/TrackEvent native integration
**Why useful**
- Richer traces with counters, async tracks, process/thread metadata, and native interop.

**How it works**
- Optionally route events to Perfetto SDK in-app, not only JSON file export.
- Preserve current JSON as fallback.

**Where to implement**
- Runtime optional backend abstraction (`TraceSink`).

**Risks / perf impact**
- Dependency and binary-size overhead.

**Layer ownership**
- Runtime backend module.

---

### AP-3: Cross-process / IPC trace stitching
**Why useful**
- Modern apps use services/processes; single-process traces hide end-to-end delays.

**How it works**
- Propagate transaction IDs via binder extras/intents where possible.
- Merge traces in post-processing.

**Where to implement**
- App/SDK integration points + report tooling.

**Risks / perf impact**
- Engineering complexity and partial coverage.

**Layer ownership**
- App + SDK + tooling.

---

### AP-4: Dynamic runtime control plane
**Why useful**
- Enables turning probes on/off remotely in debug/internal builds without app restart.

**How it works**
- Config provider (local file, debug menu, or remote config) updates sampling and thresholds.
- Runtime applies atomic config updates.

**Where to implement**
- Runtime config manager + app debug UI integration.

**Risks / perf impact**
- Misconfiguration risk.
- Mitigation: safe defaults, guardrails, environment gating.

**Layer ownership**
- App + runtime.

---

## 3) Architecture Suggestions

## 3.1 Separate instrumentation from telemetry backends
Introduce a small internal architecture:
- **Probe layer**: emits typed events (`MethodSpan`, `MainThreadStall`, `NetworkSpan`, `DbSpan`, `FrameMetric`, `ExceptionEvent`).
- **Context layer**: session/startup/transaction/thread-coroutine correlation IDs.
- **Sink layer**: JSON trace sink, summary sink, Logcat sink, future Perfetto sink.

Benefits:
- Maintains current method instrumentation while adding non-ASM probes cleanly.
- Allows debug-heavy and production-safe profiles.

## 3.2 Unified configuration model
Expand extension/runtime config into profiles:
- `profile = OFF | STARTUP_ONLY | DEBUG_FULL | CI_BENCH`
- `sampling.methodPercent`, `sampling.networkPercent`, etc.
- `thresholds.mainThreadWarnMs`, `thresholds.mainThreadCriticalMs`, `thresholds.startupMs`.

Map Gradle extension defaults to runtime config generation where possible.

## 3.3 Data model versioning
- Add `schemaVersion`, `sessionId`, `appVersion`, `buildType`, `deviceInfo` in output files.
- Keep backward compatibility via parser adapters.

## 3.4 Plugin extensibility contract
In `build-logic`, define internal probe SPI:
- `shouldInstrument(classData)`
- `createVisitor(...)`
- `runtimeRequirements()`

Method probe remains default; others can be opt-in.

---

## 4) Incremental Implementation Roadmap

## Phase 0 (Stabilization + observability baseline)
1. Normalize runtime output contract (trace file + summary file with version header).
2. Add aggregate method stats (count/total/max/p95) with bounded memory.
3. Enhance fetch/report task to consume new summary format.

**Exit criteria**
- Stable summary produced for every run.
- Top hotspots automatically listed.

## Phase 1 (UI/main-thread and startup intelligence)
1. Add main-thread heartbeat stall detector.
2. Add startup milestone APIs and auto instrumentation points.
3. Produce startup report section with top contributors.

**Exit criteria**
- Detect and report startup regressions + long main-thread stalls reliably.

## Phase 2 (Correlation signals)
1. Exception correlation module.
2. Memory/GC correlation snapshots (triggered around stalls/slow spans).
3. Frame metrics integration and jank mapping.

**Exit criteria**
- For a given freeze/crash, report includes likely performance contributors.

## Phase 3 (I/O probes)
1. Network interceptor module.
2. DB timing hooks.
3. Transaction-level correlation IDs across method/network/db spans.

**Exit criteria**
- End-to-end flow timelines available for business-critical journeys.

## Phase 4 (Advanced ecosystem)
1. Coroutine continuity and async transaction tracing.
2. ANR-risk scoring model.
3. Optional Perfetto SDK sink and cross-process stitching.

**Exit criteria**
- Framework supports deeper production diagnostics with controlled overhead.

---

## 5) Recommended Implementation Order

1. **Method timing v2 summaries** (HP-1)  
2. **Main-thread stall detector** (HP-2)  
3. **Startup profiler** (HP-3)  
4. **Plugin modularization groundwork** (HP-5)  
5. **Exception correlation** (HP-4)  
6. **Frame/jank + hotspot report enhancements** (MP-4 + MP-5)  
7. **Network + DB hooks** (MP-2 + MP-3)  
8. **Coroutine tracing** (MP-1)  
9. **ANR scoring + Perfetto backend/cross-process** (AP group)

This order balances immediate usefulness, low migration risk, and production safety.

---

## 6) Practical Guardrails for Production-friendliness

- Keep heavy probes opt-in and default to startup-focused or sampled mode.
- Redact by default (network URLs, SQL literals, exception messages).
- Use bounded queues/histograms and fail-open behavior (drop events, never block UI).
- Separate debug verbosity from production telemetry.
- Add per-feature overhead tests (microbench + startup benchmarks) before enabling by default.

---

## 7) Immediate Next Step (when implementation starts)

Start with **HP-1** by adding a new summary writer and method percentile aggregator in runtime, then extend `fetchMethodTraceReport` to parse and rank by p95 and total main-thread time. This gives fast, actionable value without changing app/SDK APIs broadly.
