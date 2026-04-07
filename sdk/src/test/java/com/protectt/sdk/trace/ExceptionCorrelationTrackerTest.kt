package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExceptionCorrelationTrackerTest {
    @Test
    fun uncaughtHandlerCapturesAndDelegatesToDownstream() {
        val tracker = ExceptionCorrelationTracker(nowEpochMs = { 123L })
        val delegated = AtomicBoolean(false)
        val capturedThread = AtomicReference<Thread>()
        val capturedThrowable = AtomicReference<Throwable>()

        val downstream = Thread.UncaughtExceptionHandler { _, _ ->
            delegated.set(true)
        }
        val handler = tracker.buildUncaughtExceptionHandler(downstream) { thread, throwable ->
            capturedThread.set(thread)
            capturedThrowable.set(throwable)
        }

        val thread = Thread.currentThread()
        val throwable = IllegalStateException("token=abc123")
        handler.uncaughtException(thread, throwable)

        assertTrue(delegated.get())
        assertEquals(thread, capturedThread.get())
        assertEquals(throwable, capturedThrowable.get())
    }

    @Test
    fun handledExceptionCaptureIncludesCorrelationSnapshot() {
        val tracker = ExceptionCorrelationTracker(nowEpochMs = { 999L })
        tracker.recordTraceEvent("{\"name\":\"A\"}")
        tracker.recordTraceEvent("{\"name\":\"B\"}")

        val event = tracker.captureHandledException(
            throwable = IllegalArgumentException("secret-password"),
            handledContext = "login_flow",
            redactSensitiveText = true,
            startupPhase = "application_on_create_end",
            activeMethodByThreadId = mapOf(1L to "com/example/Auth#doLogin()V"),
            runtimeState = RuntimeStateSnapshot(
                tracingEnabled = true,
                startupTracingOnly = true,
                startupActive = true,
                pendingEventCount = 4,
                activeSpanCount = 1,
                uptimeMs = 321L,
            ),
            thread = Thread.currentThread(),
        )

        assertEquals("handled", event.captureType)
        assertEquals("<redacted>", event.message)
        assertNotNull(event.messageHash)
        assertEquals("application_on_create_end", event.startupPhase)
        assertEquals("login_flow", event.handledContext)
        assertEquals(1, event.activeSpans.size)
        assertEquals(2, event.recentTraceSlice.size)
        assertEquals(999L, event.capturedAtEpochMs)
    }

    @Test
    fun uncaughtCaptureCanKeepRawMessageWhenRedactionDisabled() {
        val tracker = ExceptionCorrelationTracker(nowEpochMs = { 77L })

        val event = tracker.captureUncaughtException(
            throwable = RuntimeException("visible-message"),
            redactSensitiveText = false,
            startupPhase = null,
            activeMethodByThreadId = emptyMap(),
            runtimeState = RuntimeStateSnapshot(
                tracingEnabled = false,
                startupTracingOnly = false,
                startupActive = false,
                pendingEventCount = 0,
                activeSpanCount = 0,
                uptimeMs = 9L,
            ),
            thread = Thread.currentThread(),
        )

        assertEquals("uncaught", event.captureType)
        assertEquals("visible-message", event.message)
        assertTrue(event.messageHash?.isNotBlank() == true)
    }

    @Test
    fun exceptionSerializationProducesStructuredJson() {
        val tracker = ExceptionCorrelationTracker(nowEpochMs = { 44L })
        val event = tracker.captureHandledException(
            throwable = IllegalStateException("sensitive"),
            handledContext = "worker",
            redactSensitiveText = true,
            startupPhase = "sdk_init_end",
            activeMethodByThreadId = mapOf(2L to "com/example/Worker#run()V"),
            runtimeState = RuntimeStateSnapshot(
                tracingEnabled = true,
                startupTracingOnly = false,
                startupActive = true,
                pendingEventCount = 8,
                activeSpanCount = 1,
                uptimeMs = 500L,
            ),
            thread = Thread.currentThread(),
        )

        val json = exceptionsToJson(listOf(event))

        assertTrue(json.startsWith("["))
        assertTrue(json.contains("\"captureType\":\"handled\""))
        assertTrue(json.contains("\"throwableClass\":\"java.lang.IllegalStateException\""))
        assertTrue(json.contains("\"message\":\"<redacted>\""))
        assertTrue(json.contains("\"runtimeState\""))
        assertTrue(json.contains("\"activeSpans\""))
        assertTrue(json.contains("\"recentTraceSlice\""))
    }
}
