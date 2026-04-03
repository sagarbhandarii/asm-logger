package com.protectt.sdk.trace

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MethodTraceRuntime {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var startupTracingOnly: Boolean = true

    @Volatile
    var startupWindowMs: Long = 15_000L

    @Volatile
    var flushIntervalSeconds: Long = 5L

    private val processStartMs = SystemClock.elapsedRealtime()

    private data class MethodStats(
        var totalNs: Long = 0L,
        var calls: Long = 0L,
        var maxNs: Long = 0L,
    )

    private data class ThreadBucket(
        val lock: Any = Any(),
        val byMethod: MutableMap<String, MethodStats> = HashMap(),
    )

    private val threadBuckets = ConcurrentHashMap<Long, ThreadBucket>()
    private val localBucket = ThreadLocal<ThreadBucket>()

    private val flushInProgress = AtomicBoolean(false)
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @JvmStatic
    fun enter(methodId: String): Long {
        if (!shouldTrace()) return 0L
        return SystemClock.elapsedRealtimeNanos()
    }

    @JvmStatic
    fun exit(methodId: String, startNanos: Long) {
        if (startNanos == 0L || !shouldTrace()) return

        val durationNs = SystemClock.elapsedRealtimeNanos() - startNanos
        val bucket = getOrCreateThreadBucket()

        synchronized(bucket.lock) {
            val stats = bucket.byMethod.getOrPut(methodId) { MethodStats() }
            stats.calls += 1L
            stats.totalNs += durationNs
            if (durationNs > stats.maxNs) {
                stats.maxNs = durationNs
            }
        }
    }

    @JvmStatic
    fun installLifecycleFlush(application: Application, intervalSeconds: Long = flushIntervalSeconds) {
        flushIntervalSeconds = intervalSeconds.coerceAtLeast(1L)
        startPeriodicFlush()

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                startedCount += 1
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount -= 1
                if (startedCount <= 0) {
                    startedCount = 0
                    flushNow("app_background")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    @JvmStatic
    fun startPeriodicFlush() {
        val existing = scheduler
        if (existing != null && !existing.isShutdown) return

        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "method-trace-flush").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(
                { flushNow("periodic") },
                flushIntervalSeconds,
                flushIntervalSeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    @JvmStatic
    fun stopPeriodicFlush() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    @JvmStatic
    fun flushNow(reason: String = "manual", limit: Int = 200) {
        if (!flushInProgress.compareAndSet(false, true)) return

        try {
            val merged = collectAndClearBuckets()
            if (merged.isEmpty()) return

            val lines = merged.entries
                .sortedByDescending { it.value.calls }
                .take(limit)
                .map { (methodId, stats) ->
                    val avgMs = if (stats.calls == 0L) 0.0 else (stats.totalNs.toDouble() / stats.calls) / 1_000_000.0
                    val maxMs = stats.maxNs / 1_000_000.0
                    "Method: $methodId, Calls: ${stats.calls}, Avg: ${"%.2f".format(avgMs)}ms, Max: ${"%.2f".format(maxMs)}ms"
                }

            lines.forEach { Log.d(TAG, "[$reason] $it") }
            persistTextReport(lines, reason)
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun collectAndClearBuckets(): Map<String, MethodStats> {
        val aggregated = HashMap<String, MethodStats>()

        threadBuckets.values.forEach { bucket ->
            val snapshot = synchronized(bucket.lock) {
                if (bucket.byMethod.isEmpty()) return@forEach null
                val copy = HashMap(bucket.byMethod)
                bucket.byMethod.clear()
                copy
            } ?: return@forEach

            snapshot.forEach { (methodId, stats) ->
                val merged = aggregated.getOrPut(methodId) { MethodStats() }
                merged.calls += stats.calls
                merged.totalNs += stats.totalNs
                if (stats.maxNs > merged.maxNs) {
                    merged.maxNs = stats.maxNs
                }
            }
        }

        return aggregated
    }

    private fun getOrCreateThreadBucket(): ThreadBucket {
        localBucket.get()?.let { return it }

        val created = ThreadBucket()
        val threadId = Thread.currentThread().id
        val existing = threadBuckets.putIfAbsent(threadId, created)
        val bucket = existing ?: created
        localBucket.set(bucket)
        return bucket
    }

    private fun persistTextReport(lines: List<String>, reason: String) {
        runCatching {
            val file = resolveOutputFile()
            file.parentFile?.mkdirs()
            val payload = buildString {
                append("reason=")
                append(reason)
                append('\n')
                lines.forEach {
                    append(it)
                    append('\n')
                }
            }
            file.appendText(payload)
        }.onFailure {
            Log.w(TAG, "Failed to write trace report: ${it.message}")
        }
    }

    private fun resolveOutputFile(): File {
        val configured = System.getProperty("method.trace.output.path")?.trim().orEmpty()
        if (configured.isNotEmpty()) return File(configured)

        resolveAppFilesDir()?.let { filesDir ->
            return File(filesDir, "methodtrace-report.txt")
        }

        val tempDir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
        val baseDir = if (tempDir.isNotEmpty()) File(tempDir) else File("/data/local/tmp")
        return File(baseDir, "methodtrace-report.txt")
    }

    private fun resolveAppFilesDir(): File? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) ?: return@runCatching null
            currentApplication::class.java
                .getMethod("getFilesDir")
                .invoke(currentApplication) as? File
        }.getOrNull()
    }

    private fun shouldTrace(): Boolean {
        if (!enabled) return false
        if (!startupTracingOnly) return true
        return SystemClock.elapsedRealtime() - processStartMs <= startupWindowMs
    }

    private const val TAG = "MethodTrace"
}

object SamplingConfig {
    @JvmField
    @Volatile
    var sampleRatePercent: Int = 100

    @JvmField
    @Volatile
    var slowCallThresholdMs: Long = 0L
}
