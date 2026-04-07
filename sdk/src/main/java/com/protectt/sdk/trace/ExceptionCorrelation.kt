package com.protectt.sdk.trace

import java.security.MessageDigest
import java.util.ArrayDeque

internal data class RuntimeStateSnapshot(
    val tracingEnabled: Boolean,
    val startupTracingOnly: Boolean,
    val startupActive: Boolean,
    val pendingEventCount: Int,
    val activeSpanCount: Int,
    val uptimeMs: Long,
)

internal data class CorrelatedException(
    val captureType: String,
    val throwableClass: String,
    val message: String?,
    val messageHash: String?,
    val threadId: Long,
    val threadName: String,
    val startupPhase: String?,
    val capturedAtEpochMs: Long,
    val handledContext: String?,
    val activeSpans: List<String>,
    val recentTraceSlice: List<String>,
    val runtimeState: RuntimeStateSnapshot,
) {
    fun toJson(): String {
        return buildString(256 + activeSpans.size * 48 + recentTraceSlice.size * 64) {
            append('{')
            append("\"captureType\":\"").append(escapeJson(captureType)).append("\",")
            append("\"throwableClass\":\"").append(escapeJson(throwableClass)).append("\",")
            append("\"message\":")
            if (message == null) append("null") else append("\"").append(escapeJson(message)).append("\"")
            append(',')
            append("\"messageHash\":")
            if (messageHash == null) append("null") else append("\"").append(messageHash).append("\"")
            append(',')
            append("\"thread\":{")
            append("\"id\":").append(threadId).append(',')
            append("\"name\":\"").append(escapeJson(threadName)).append("\"},")
            append("\"startupPhase\":")
            if (startupPhase == null) append("null") else append("\"").append(escapeJson(startupPhase)).append("\"")
            append(',')
            append("\"capturedAtEpochMs\":").append(capturedAtEpochMs).append(',')
            append("\"handledContext\":")
            if (handledContext == null) append("null") else append("\"").append(escapeJson(handledContext)).append("\"")
            append(',')
            append("\"activeSpans\":[")
            activeSpans.forEachIndexed { index, span ->
                if (index > 0) append(',')
                append('"').append(escapeJson(span)).append('"')
            }
            append("],")
            append("\"recentTraceSlice\":[")
            recentTraceSlice.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append('"').append(escapeJson(event)).append('"')
            }
            append("],")
            append("\"runtimeState\":{")
            append("\"tracingEnabled\":").append(runtimeState.tracingEnabled).append(',')
            append("\"startupTracingOnly\":").append(runtimeState.startupTracingOnly).append(',')
            append("\"startupActive\":").append(runtimeState.startupActive).append(',')
            append("\"pendingEventCount\":").append(runtimeState.pendingEventCount).append(',')
            append("\"activeSpanCount\":").append(runtimeState.activeSpanCount).append(',')
            append("\"uptimeMs\":").append(runtimeState.uptimeMs)
            append("}}")
        }
    }
}

internal class ExceptionCorrelationTracker(
    private val nowEpochMs: () -> Long,
    private val maxRetained: Int = 64,
    private val recentTraceLimit: Int = 16,
) {
    private val lock = Any()
    private val exceptions = ArrayDeque<CorrelatedException>(maxRetained)
    private val recentEvents = ArrayDeque<String>(maxRetained * 2)

    fun captureHandledException(
        throwable: Throwable,
        handledContext: String?,
        redactSensitiveText: Boolean,
        startupPhase: String?,
        activeMethodByThreadId: Map<Long, String>,
        runtimeState: RuntimeStateSnapshot,
        thread: Thread = Thread.currentThread(),
    ): CorrelatedException {
        return capture(
            captureType = "handled",
            throwable = throwable,
            handledContext = handledContext,
            redactSensitiveText = redactSensitiveText,
            startupPhase = startupPhase,
            activeMethodByThreadId = activeMethodByThreadId,
            runtimeState = runtimeState,
            thread = thread,
        )
    }

    fun captureUncaughtException(
        throwable: Throwable,
        redactSensitiveText: Boolean,
        startupPhase: String?,
        activeMethodByThreadId: Map<Long, String>,
        runtimeState: RuntimeStateSnapshot,
        thread: Thread,
    ): CorrelatedException {
        return capture(
            captureType = "uncaught",
            throwable = throwable,
            handledContext = null,
            redactSensitiveText = redactSensitiveText,
            startupPhase = startupPhase,
            activeMethodByThreadId = activeMethodByThreadId,
            runtimeState = runtimeState,
            thread = thread,
        )
    }

    fun recordTraceEvent(eventJson: String) {
        synchronized(lock) {
            if (recentEvents.size >= maxRetained * 2) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(eventJson.take(512))
        }
    }

    fun snapshot(): List<CorrelatedException> = synchronized(lock) { exceptions.toList() }

    fun buildUncaughtExceptionHandler(
        downstream: Thread.UncaughtExceptionHandler?,
        capture: (thread: Thread, throwable: Throwable) -> Unit,
    ): Thread.UncaughtExceptionHandler {
        return Thread.UncaughtExceptionHandler { thread, throwable ->
            try {
                capture(thread, throwable)
            } catch (_: Throwable) {
                // fail-safe: exception capture must never block app crash flow
            } finally {
                downstream?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun capture(
        captureType: String,
        throwable: Throwable,
        handledContext: String?,
        redactSensitiveText: Boolean,
        startupPhase: String?,
        activeMethodByThreadId: Map<Long, String>,
        runtimeState: RuntimeStateSnapshot,
        thread: Thread,
    ): CorrelatedException {
        val originalMessage = throwable.message
        val redactedMessage = if (redactSensitiveText && !originalMessage.isNullOrBlank()) {
            "<redacted>"
        } else {
            originalMessage?.take(256)
        }
        val messageHash = originalMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { sha256Hex(it) }

        val event = CorrelatedException(
            captureType = captureType,
            throwableClass = throwable::class.java.name,
            message = redactedMessage,
            messageHash = messageHash,
            threadId = thread.id,
            threadName = thread.name,
            startupPhase = startupPhase,
            capturedAtEpochMs = nowEpochMs(),
            handledContext = handledContext?.take(128),
            activeSpans = activeMethodByThreadId.entries
                .sortedBy { it.key }
                .map { (threadId, methodId) -> "$threadId:$methodId" }
                .take(16),
            recentTraceSlice = synchronized(lock) { recentEvents.takeLast(recentTraceLimit) },
            runtimeState = runtimeState,
        )

        synchronized(lock) {
            if (exceptions.size >= maxRetained) {
                exceptions.removeFirst()
            }
            exceptions.addLast(event)
        }
        return event
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            out.append(((byte.toInt() ushr 4) and 0xF).toString(16))
            out.append((byte.toInt() and 0xF).toString(16))
        }
        return out.toString()
    }
}

internal fun exceptionsToJson(exceptions: List<CorrelatedException>): String {
    return buildString(exceptions.size * 192 + 16) {
        append('[')
        exceptions.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append(event.toJson())
        }
        append(']')
    }
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
