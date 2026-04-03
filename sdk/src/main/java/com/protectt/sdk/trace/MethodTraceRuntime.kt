package com.protectt.sdk.trace

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MethodTraceRuntime {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var startupTracingOnly: Boolean = true

    @Volatile
    var startupWindowMs: Long = 15_000L

    @Volatile
    var logEachCall: Boolean = true

    @Volatile
    var captureThreadName: Boolean = true

    private val processStartMs = SystemClock.elapsedRealtime()
    private val totals = ConcurrentHashMap<String, AtomicLong>()
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val maxNs = ConcurrentHashMap<String, AtomicLong>()

    @JvmStatic
    fun enter(methodId: String): Long {
        if (!shouldTrace()) return 0L
        return SystemClock.elapsedRealtimeNanos()
    }

    @JvmStatic
    fun exit(methodId: String, startNanos: Long) {
        if (startNanos == 0L || !shouldTrace()) return

        val durationNs = SystemClock.elapsedRealtimeNanos() - startNanos
        totals.getOrPut(methodId) { AtomicLong() }.addAndGet(durationNs)
        counts.getOrPut(methodId) { AtomicLong() }.incrementAndGet()
        maxNs.getOrPut(methodId) { AtomicLong() }.accumulateAndGet(durationNs) { old, new ->
            if (new > old) new else old
        }

        persistJsonReport()

        if (logEachCall) {
            val threadInfo = if (captureThreadName) {
                val main = if (Looper.getMainLooper().thread == Thread.currentThread()) "MAIN" else "BG"
                " [${Thread.currentThread().name}/$main]"
            } else {
                ""
            }
            Log.d(TAG, "$methodId took ${durationNs / 1_000_000.0} ms$threadInfo")
        }
    }

    @JvmStatic
    fun dumpTop(limit: Int = 20) {
        val rows = totals.keys.map { method ->
            val total = totals[method]?.get() ?: 0L
            val count = counts[method]?.get() ?: 0L
            val max = maxNs[method]?.get() ?: 0L
            Triple(method, total, Pair(count, max))
        }.sortedByDescending { it.second }
            .take(limit)

        Log.d(TAG, "========== METHOD TRACE SUMMARY ==========")
        rows.forEachIndexed { index, row ->
            val count = row.third.first
            val max = row.third.second
            val avgMs = if (count == 0L) 0.0 else (row.second.toDouble() / count) / 1_000_000.0
            val totalMs = row.second / 1_000_000.0
            val maxMs = max / 1_000_000.0
            Log.d(TAG, "${index + 1}. ${row.first} count=$count totalMs=$totalMs avgMs=$avgMs maxMs=$maxMs")
        }
    }

    @JvmStatic
    fun dumpTopJson(limit: Int = 20) {
        Log.d(TAG, buildTopJson(limit))
    }

    @JvmStatic
    fun buildTopJson(limit: Int = 20): String {
        val rows = totals.keys.map { method ->
            val total = totals[method]?.get() ?: 0L
            val count = counts[method]?.get() ?: 0L
            val max = maxNs[method]?.get() ?: 0L
            Triple(method, total, Pair(count, max))
        }.sortedByDescending { it.second }
            .take(limit)

        val methodsJson = rows.joinToString(separator = ",") { row ->
            val method = row.first
            val totalNs = row.second
            val count = row.third.first
            val maxNs = row.third.second
            val avgNs = if (count == 0L) 0.0 else totalNs.toDouble() / count
            val totalMs = totalNs / 1_000_000.0
            val avgMs = avgNs / 1_000_000.0
            val maxMs = maxNs / 1_000_000.0
            "{" +
                "\"methodId\":\"${method.escapeJson()}\"," +
                "\"count\":$count," +
                "\"totalNs\":$totalNs," +
                "\"totalMs\":$totalMs," +
                "\"avgMs\":$avgMs," +
                "\"maxNs\":$maxNs," +
                "\"maxMs\":$maxMs" +
                "}"
        }

        return "{" +
            "\"tag\":\"$TAG\"," +
            "\"enabled\":$enabled," +
            "\"startupTracingOnly\":$startupTracingOnly," +
            "\"startupWindowMs\":$startupWindowMs," +
            "\"methodCount\":${rows.size}," +
            "\"methods\":[$methodsJson]" +
            "}"
    }


    private fun persistJsonReport() {
        runCatching {
            val file = resolveOutputFile()
            file.parentFile?.mkdirs()
            file.writeText(buildTopJson(limit = 200))
        }.onFailure {
            Log.w(TAG, "Failed to write JSON trace report: ${it.message}")
        }
    }

    private fun resolveOutputFile(): File {
        val configured = System.getProperty("method.trace.output.path")?.trim().orEmpty()
        if (configured.isNotEmpty()) return File(configured)

        resolveAppFilesDir()?.let { filesDir ->
            return File(filesDir, "methodtrace-report.json")
        }

        val tempDir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
        val baseDir = if (tempDir.isNotEmpty()) File(tempDir) else File("/data/local/tmp")
        return File(baseDir, "methodtrace-report.json")
    }

    private fun resolveAppFilesDir(): File? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) ?: return@runCatching null
            val filesDir = currentApplication::class.java
                .getMethod("getFilesDir")
                .invoke(currentApplication) as? File
            filesDir
        }.getOrNull()
    }

    private fun shouldTrace(): Boolean {
        if (!enabled) return false
        if (!startupTracingOnly) return true
        return SystemClock.elapsedRealtime() - processStartMs <= startupWindowMs
    }

    private fun String.escapeJson(): String = buildString(length) {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private const val TAG = "MethodTrace"
}

object SamplingConfig {
    @JvmField
    @Volatile
    var sampleRatePercent: Int = 10

    @JvmField
    @Volatile
    var slowCallThresholdMs: Long = 50L
}
