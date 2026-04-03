package __PACKAGE__

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
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
    private val traceStartNs = SystemClock.elapsedRealtimeNanos()

    private const val TAG = "MethodTrace"
    private const val MAIN_WARN_THRESHOLD_MS = 100L
    private const val MAIN_CRITICAL_THRESHOLD_MS = 300L
    private const val MAIN_WARN_THRESHOLD_NS = MAIN_WARN_THRESHOLD_MS * 1_000_000L

    private const val INITIAL_BUFFER_CAPACITY = 2_048
    private const val MAX_BUFFERED_EVENTS = 4_096
    private const val DEFAULT_FLUSH_BATCH_SIZE = 1_024

    private const val DEFAULT_OUTPUT_PATH = "/sdcard/method_trace.json"

    private const val TRACE_HEADER = "{\"traceEvents\":[\n"
    private const val TRACE_FOOTER = "\n]}"
    private const val COMMA_NEWLINE = ",\n"

    private val TRACE_HEADER_BYTES = TRACE_HEADER.toByteArray(Charsets.UTF_8)
    private val TRACE_FOOTER_BYTES = TRACE_FOOTER.toByteArray(Charsets.UTF_8)
    private val COMMA_NEWLINE_BYTES = COMMA_NEWLINE.toByteArray(Charsets.UTF_8)

    private data class ThreadTraceState(
        val jsonBuilder: StringBuilder = StringBuilder(256),
    )

    private val threadState = ThreadLocal<ThreadTraceState>()
    private val bufferLock = Any()
    private val pendingEvents = ArrayDeque<String>(INITIAL_BUFFER_CAPACITY)

    private val asyncWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "method-trace-writer").apply { isDaemon = true }
    }
    private val asyncFlushQueued = AtomicBoolean(false)

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var traceFileInitialized = false

    @Volatile
    private var writtenEventCount: Long = 0L

    @JvmStatic
    fun enter(methodId: String): Long {
        if (!shouldTrace()) return 0L
        return SystemClock.elapsedRealtimeNanos()
    }

    @JvmStatic
    fun exit(methodId: String, startNanos: Long) {
        if (startNanos == 0L) return

        val endNs = SystemClock.elapsedRealtimeNanos()
        val durationNs = (endNs - startNanos).coerceAtLeast(0L)
        maybeLogMainThreadSlowCall(methodId, durationNs)

        val tsUs = (startNanos - traceStartNs).coerceAtLeast(0L) / 1_000L
        val durUs = durationNs / 1_000L
        val threadId = Thread.currentThread().id
        val eventJson = buildEventJson(methodId, tsUs, durUs, threadId)

        val shouldScheduleFlush = synchronized(bufferLock) {
            pendingEvents.addLast(eventJson)
            pendingEvents.size >= MAX_BUFFERED_EVENTS
        }

        if (shouldScheduleFlush) {
            queueAsyncFlush()
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
    fun flushNow(reason: String = "manual", limit: Int = DEFAULT_FLUSH_BATCH_SIZE) {
        val batch = drainEvents(limit.coerceAtLeast(1))
        if (batch.isEmpty()) return

        runCatching {
            appendEventsToTraceFile(batch)
        }.onFailure {
            Log.w(TAG, "Failed to flush trace events ($reason): ${it.message}")
        }
    }

    private fun queueAsyncFlush() {
        if (!asyncFlushQueued.compareAndSet(false, true)) return
        asyncWriter.execute {
            try {
                while (true) {
                    flushNow("buffer_full", MAX_BUFFERED_EVENTS)
                    val hasMore = synchronized(bufferLock) { pendingEvents.isNotEmpty() }
                    if (!hasMore) break
                }
            } finally {
                asyncFlushQueued.set(false)
                val hasMore = synchronized(bufferLock) { pendingEvents.isNotEmpty() }
                if (hasMore) queueAsyncFlush()
            }
        }
    }

    private fun drainEvents(limit: Int): List<String> {
        return synchronized(bufferLock) {
            if (pendingEvents.isEmpty()) return emptyList()
            val count = minOf(limit, pendingEvents.size)
            buildList(count) {
                repeat(count) {
                    val event = pendingEvents.removeFirstOrNull() ?: return@repeat
                    add(event)
                }
            }
        }
    }

    private fun appendEventsToTraceFile(events: List<String>) {
        val traceFile = resolveOutputFile()
        traceFile.parentFile?.mkdirs()

        RandomAccessFile(traceFile, "rw").use { raf ->
            if (!traceFileInitialized) {
                raf.setLength(0)
                raf.write(TRACE_HEADER_BYTES)
                raf.write(TRACE_FOOTER_BYTES)
                traceFileInitialized = true
                writtenEventCount = 0L
            }

            val insertionOffset = (raf.length() - TRACE_FOOTER_BYTES.size).coerceAtLeast(TRACE_HEADER_BYTES.size.toLong())
            raf.seek(insertionOffset)

            if (writtenEventCount > 0) {
                raf.write(COMMA_NEWLINE_BYTES)
            }

            raf.write(events.joinToString(separator = ",\n").toByteArray(Charsets.UTF_8))
            raf.write(TRACE_FOOTER_BYTES)
            writtenEventCount += events.size
        }
    }

    private fun buildEventJson(methodId: String, tsUs: Long, durUs: Long, threadId: Long): String {
        val state = getOrCreateThreadState()
        val sb = state.jsonBuilder
        sb.setLength(0)

        sb.append('{')
            .append("\"name\":\"")
            .append(escapeJson(formatMethodId(methodId)))
            .append("\",")
            .append("\"ph\":\"X\",")
            .append("\"ts\":")
            .append(tsUs)
            .append(',')
            .append("\"dur\":")
            .append(durUs)
            .append(',')
            .append("\"pid\":0,")
            .append("\"tid\":")
            .append(threadId)
            .append('}')

        return sb.toString()
    }

    private fun resolveOutputFile(): File {
        return File(DEFAULT_OUTPUT_PATH)
    }

    private fun escapeJson(value: String): String {
        val escaped = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> escaped.append("\\\\")
                '"' -> escaped.append("\\\"")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> escaped.append(ch)
            }
        }
        return escaped.toString()
    }

    private fun formatMethodId(methodId: String): String {
        val hashIndex = methodId.indexOf('#')
        if (hashIndex <= 0) return methodId

        val owner = methodId.substring(0, hashIndex).replace('/', '.')
        val methodAndDesc = methodId.substring(hashIndex + 1)
        val parenIndex = methodAndDesc.indexOf('(')
        val methodName = if (parenIndex >= 0) methodAndDesc.substring(0, parenIndex) else methodAndDesc
        return "$owner.$methodName"
    }

    private fun maybeLogMainThreadSlowCall(methodId: String, durationNs: Long) {
        if (durationNs <= MAIN_WARN_THRESHOLD_NS) return
        if (!isMainThread()) return

        val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNs)
        if (durationMs > MAIN_CRITICAL_THRESHOLD_MS) {
            Log.e(TAG, "[MAIN][CRITICAL] Method ${formatMethodId(methodId)} took ${durationMs}ms")
            return
        }
        Log.w(TAG, "[MAIN][WARN] Method ${formatMethodId(methodId)} took ${durationMs}ms")
    }

    private fun isMainThread(): Boolean {
        return Looper.getMainLooper().thread === Thread.currentThread()
    }

    private fun getOrCreateThreadState(): ThreadTraceState {
        threadState.get()?.let { return it }
        return ThreadTraceState().also { threadState.set(it) }
    }

    private fun shouldTrace(): Boolean {
        if (!enabled) return false
        if (!startupTracingOnly) return true
        return SystemClock.elapsedRealtime() - processStartMs <= startupWindowMs
    }

}

object SamplingConfig {
    @JvmField
    @Volatile
    var sampleRatePercent: Int = 100

    @JvmField
    @Volatile
    var slowCallThresholdMs: Long = 0L
}
