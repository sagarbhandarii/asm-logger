package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineTraceTrackerTest {
    private val tracker = CoroutineTraceTracker()

    @Test
    fun contextPropagationAcrossSuspendResumeRetainsSameScopeId() {
        val scope = tracker.createScope(
            id = 7L,
            transaction = "txn",
            parentSpanId = "A#m()V",
            threadId = 11L,
        )

        val suspendTransition = tracker.onSuspend(scope, threadId = 11L)
        val (resumed, transitions) = tracker.onResume(
            scope = scope,
            threadId = 11L,
            dispatcher = "main",
            restoredFrom = null,
        )

        assertEquals("coroutine_suspend", suspendTransition.name)
        assertEquals(7L, suspendTransition.scope.id)
        assertEquals(7L, resumed.id)
        assertEquals("coroutine_resume", transitions.last().name)
    }

    @Test
    fun dispatcherSwitchContinuityEmitsSwitchThenResume() {
        val scope = tracker.createScope(
            id = 8L,
            transaction = "txn",
            parentSpanId = null,
            threadId = 11L,
        )

        val (resumed, transitions) = tracker.onResume(
            scope = scope,
            threadId = 42L,
            dispatcher = "DefaultDispatcher-worker-1",
            restoredFrom = 99L,
        )

        assertEquals(2, transitions.size)
        assertEquals("dispatcher_switch", transitions[0].name)
        assertEquals(11L, transitions[0].args["fromThreadId"])
        assertEquals(42L, transitions[0].args["toThreadId"])
        assertEquals("coroutine_resume", transitions[1].name)
        assertEquals(42L, resumed.lastThreadId)
    }

    @Test
    fun cancellationAndExceptionPathAreCaptured() {
        val scope = tracker.createScope(
            id = 9L,
            transaction = "txn",
            parentSpanId = "B#n()V",
            threadId = 1L,
        )
        val cancel = tracker.onCompletion(
            scope = scope,
            threadId = 1L,
            cancelled = true,
            error = IllegalStateException("boom"),
        )

        assertEquals("coroutine_cancelled", cancel.name)
        assertEquals("java.lang.IllegalStateException", cancel.args["errorType"])
        assertEquals("boom", cancel.args["errorMessage"])
    }

    @Test
    fun completionWithoutErrorKeepsSerializableArgs() {
        val scope = tracker.createScope(
            id = 10L,
            transaction = "txn",
            parentSpanId = null,
            threadId = 5L,
        )
        val done = tracker.onCompletion(
            scope = scope,
            threadId = 5L,
            cancelled = false,
            error = null,
        )

        assertEquals("coroutine_completed", done.name)
        assertTrue(done.args.containsKey("errorType"))
        assertNull(done.args["errorType"])
        assertNull(done.args["errorMessage"])
    }
}

