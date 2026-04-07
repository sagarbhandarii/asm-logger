package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TraceContextGroundworkTest {
    @Test
    fun encodesAndDecodesSessionContextForPropagation() {
        val context = TraceSessionContext(
            traceId = "trace-a",
            sessionId = "session-b",
            transactionId = "txn-c",
            activeSpanId = "span-d",
        )

        val decoded = TraceSessionContext.decodeFromHeader(context.encodeForHeader())

        assertNotNull(decoded)
        assertEquals("trace-a", decoded?.traceId)
        assertEquals("session-b", decoded?.sessionId)
        assertEquals("txn-c", decoded?.transactionId)
        assertEquals("span-d", decoded?.activeSpanId)
    }

    @Test
    fun returnsNullForInvalidHeader() {
        assertNull(TraceSessionContext.decodeFromHeader("invalid"))
    }
}
