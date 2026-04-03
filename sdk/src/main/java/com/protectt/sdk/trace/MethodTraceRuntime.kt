package com.protectt.sdk.trace

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    private data class TraceNode(
        var methodId: String,
        var startNs: Long = 0L,
        var durationNs: Long = 0L,
        val children: MutableList<TraceNode> = ArrayList(4),
    )

    private data class ThreadTraceState(
        val stack: ArrayDeque<TraceNode> = ArrayDeque(64),
        val nodePool: ArrayDeque<TraceNode> = ArrayDeque(128),
        val renderBuilder: StringBuilder = StringBuilder(2048),
        val releaseStack: ArrayDeque<TraceNode> = ArrayDeque(128),
    )

    private val threadState = ThreadLocal<ThreadTraceState>()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @JvmStatic
    fun enter(methodId: String): Long {
        if (!shouldTrace()) return 0L

        val state = getOrCreateThreadState()
        val startNs = SystemClock.elapsedRealtimeNanos()
        val node = acquireNode(state, methodId, startNs)

        state.stack.peekLast()?.children?.add(node)
        state.stack.addLast(node)
        return startNs
    }

    @JvmStatic
    fun exit(methodId: String, startNanos: Long) {
        if (startNanos == 0L) return

        val state = threadState.get() ?: return
        val node = state.stack.pollLast() ?: return
        node.durationNs = (SystemClock.elapsedRealtimeNanos() - node.startNs).coerceAtLeast(0L)

        if (state.stack.isNotEmpty()) {
            return
        }

        val treeOutput = renderTree(state, node)
        Log.d(TAG, treeOutput)
        persistTextReport(treeOutput)
        recycleTree(state, node)
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
        Log.d(TAG, "flushNow($reason) is a no-op in call-tree mode; trees are emitted at root exit")
    }

    private fun acquireNode(state: ThreadTraceState, methodId: String, startNs: Long): TraceNode {
        val node = state.nodePool.pollLast() ?: TraceNode(methodId)
        node.methodId = methodId
        node.startNs = startNs
        node.durationNs = 0L
        node.children.clear()
        return node
    }

    private data class RenderFrame(
        val node: TraceNode,
        val depth: Int,
        var nextChildIndex: Int = 0,
        var entered: Boolean = false,
    )

    private fun renderTree(state: ThreadTraceState, root: TraceNode): String {
        val sb = state.renderBuilder
        sb.setLength(0)
        sb.append("Thread ")
            .append(Thread.currentThread().name)
            .append(" (id=")
            .append(Thread.currentThread().id)
            .append(')')
            .append('\n')

        val dfsStack = ArrayDeque<RenderFrame>()
        dfsStack.addLast(RenderFrame(root, 0))

        while (dfsStack.isNotEmpty()) {
            val frame = dfsStack.peekLast()
            if (!frame.entered) {
                appendNodeLine(sb, frame.node, frame.depth)
                frame.entered = true
            }

            if (frame.nextChildIndex < frame.node.children.size) {
                val child = frame.node.children[frame.nextChildIndex++]
                dfsStack.addLast(RenderFrame(child, frame.depth + 1))
            } else {
                dfsStack.removeLast()
            }
        }

        return sb.toString()
    }

    private fun appendNodeLine(sb: StringBuilder, node: TraceNode, depth: Int) {
        repeat(depth) { sb.append("  ") }
        sb.append("- ")
            .append(formatMethodId(node.methodId))
            .append(" (")
            .append(String.format("%.2f", node.durationNs / 1_000_000.0))
            .append("ms)")
            .append('\n')
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

    private fun recycleTree(state: ThreadTraceState, root: TraceNode) {
        val release = state.releaseStack
        release.clear()
        release.addLast(root)

        while (release.isNotEmpty()) {
            val current = release.removeLast()
            current.children.forEach { child ->
                release.addLast(child)
            }
            current.children.clear()
            state.nodePool.addLast(current)
        }
    }

    private fun persistTextReport(tree: String) {
        runCatching {
            val file = resolveOutputFile()
            file.parentFile?.mkdirs()
            file.appendText(tree)
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

    private fun getOrCreateThreadState(): ThreadTraceState {
        threadState.get()?.let { return it }
        return ThreadTraceState().also { threadState.set(it) }
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
