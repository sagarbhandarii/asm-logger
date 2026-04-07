package com.protectt.sdk.trace

internal class CoroutineTraceTracker {
    data class Scope(
        val id: Long,
        val transaction: String,
        val parentSpanId: String?,
        val parentThreadId: Long,
        val lastThreadId: Long,
    )

    data class Transition(
        val name: String,
        val scope: Scope,
        val threadId: Long,
        val args: Map<String, Any?> = emptyMap(),
    )

    fun createScope(id: Long, transaction: String, parentSpanId: String?, threadId: Long): Scope {
        return Scope(
            id = id,
            transaction = transaction,
            parentSpanId = parentSpanId,
            parentThreadId = threadId,
            lastThreadId = threadId,
        )
    }

    fun onResume(scope: Scope, threadId: Long, dispatcher: String, restoredFrom: Long?): Pair<Scope, List<Transition>> {
        val transitions = mutableListOf<Transition>()
        if (scope.lastThreadId != threadId) {
            transitions += Transition(
                name = "dispatcher_switch",
                scope = scope,
                threadId = threadId,
                args = mapOf(
                    "fromThreadId" to scope.lastThreadId,
                    "toThreadId" to threadId,
                    "dispatcher" to dispatcher,
                ),
            )
        }
        val updated = scope.copy(lastThreadId = threadId)
        transitions += Transition(
            name = "coroutine_resume",
            scope = updated,
            threadId = threadId,
            args = mapOf("restoredFrom" to restoredFrom),
        )
        return updated to transitions
    }

    fun onSuspend(scope: Scope, threadId: Long): Transition {
        return Transition(
            name = "coroutine_suspend",
            scope = scope,
            threadId = threadId,
        )
    }

    fun onCompletion(scope: Scope, threadId: Long, cancelled: Boolean, error: Throwable?): Transition {
        return Transition(
            name = if (cancelled) "coroutine_cancelled" else "coroutine_completed",
            scope = scope,
            threadId = threadId,
            args = mapOf(
                "errorType" to error?.javaClass?.name,
                "errorMessage" to error?.message,
            ),
        )
    }
}

