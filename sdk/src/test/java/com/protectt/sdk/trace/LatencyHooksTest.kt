package com.protectt.sdk.trace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LatencyHooksTest {
    @After
    fun tearDown() {
        HookRuntimeBridge.networkEnabled = { false }
        HookRuntimeBridge.dbEnabled = { false }
        HookRuntimeBridge.correlation = { TraceCorrelationContext("test", null, 0L, "test") }
        HookRuntimeBridge.emitNetwork = {}
        HookRuntimeBridge.emitDb = { _, _ -> }
    }

    @Test
    fun networkEventSerializationIncludesCorrelationAndRedactedPath() {
        val event = NetworkTimingEvent(
            requestStartNs = 100L,
            durationNs = 3_000_000L,
            host = "api.example.com",
            pathTemplate = safePathTemplate("https://api.example.com/users/123e4567-e89b-12d3-a456-426614174000/orders/9".toHttpUrl(), redact = true),
            method = "GET",
            responseCode = 200,
            protocol = "h2",
            failed = false,
            correlation = TraceCorrelationContext(
                traceId = "trace-1",
                activeSpanId = "Auth#doLogin",
                threadId = 7L,
                threadName = "main",
            ),
        )

        val json = event.toArgsJson()

        assertTrue(json.contains("\"kind\":\"network\""))
        assertTrue(json.contains("\"host\":\"api.example.com\""))
        assertTrue(json.contains("\"pathTemplate\":\"/users/:id/orders/:id\""))
        assertTrue(json.contains("\"activeSpanId\":\"Auth#doLogin\""))
    }

    @Test
    fun okHttpEventListenerEmitsTimingWhenEnabled() {
        val captured = AtomicReference<NetworkTimingEvent>()
        HookRuntimeBridge.networkEnabled = { true }
        HookRuntimeBridge.correlation = {
            TraceCorrelationContext("trace-2", "span-2", 12L, "io")
        }
        HookRuntimeBridge.emitNetwork = { captured.set(it) }

        val request = Request.Builder().url("https://example.com/private/123/profile").get().build()
        val call = FakeCall(request)
        val listener = ProtecttOkHttpEventListener(call, redactPathSegments = true)
        listener.callStart(call)
        listener.responseHeadersEnd(call, response(request, 201))
        listener.callEnd(call)

        val event = captured.get()
        assertNotNull(event)
        assertEquals("example.com", event.host)
        assertEquals("/private/:id/profile", event.pathTemplate)
        assertEquals(201, event.responseCode)
        assertEquals("trace-2", event.correlation.traceId)
    }

    @Test
    fun dbTimingHookCapturesOperationTableAndFingerprint() {
        val captured = AtomicReference<DbTimingEvent>()
        HookRuntimeBridge.dbEnabled = { true }
        HookRuntimeBridge.correlation = { TraceCorrelationContext("trace-db", "span-db", 99L, "worker") }
        HookRuntimeBridge.emitDb = { event, _ -> captured.set(event) }

        DbTimingHooks.timeQuery("SELECT * FROM users WHERE email='alice@example.com' AND age=42") {
            "ok"
        }

        val event = captured.get()
        assertNotNull(event)
        assertEquals("SELECT", event.operation)
        assertEquals("users", event.tableHint)
        assertEquals(64, event.statementFingerprint.length)
        assertEquals("trace-db", event.correlation.traceId)
    }

    @Test
    fun sqlHelpersRedactAndDeriveSafely() {
        val normalized = normalizeSql("INSERT INTO payments(card,last4,amount) VALUES('4111111111111111',1234,9999)")
        assertTrue(normalized.contains("VALUES(?,?,?)"))
        assertEquals("INSERT", deriveSqlOperation(" insert into payments values(1)"))
        assertEquals("payments", deriveSqlTableHint("INSERT INTO payments (id) VALUES (1)"))
        assertNull(deriveSqlTableHint("PRAGMA journal_mode=WAL"))
    }

    @Test
    fun dbEventSerializationProducesStructuredOutput() {
        val event = DbTimingEvent(
            queryStartNs = 10L,
            durationNs = 20L,
            operation = "UPDATE",
            tableHint = "users",
            statementFingerprint = "abc",
            threadId = 1L,
            threadName = "main",
            correlation = TraceCorrelationContext("trace-x", "span-y", 1L, "main"),
        )

        val json = event.toArgsJson()

        assertTrue(json.contains("\"kind\":\"db\""))
        assertTrue(json.contains("\"operation\":\"UPDATE\""))
        assertTrue(json.contains("\"tableHint\":\"users\""))
        assertTrue(json.contains("\"activeSpanId\":\"span-y\""))
    }

    private fun response(request: Request, code: Int): Response {
        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .message("OK")
            .code(code)
            .build()
    }

    private class FakeCall(private val request: Request) : Call {
        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) = Unit
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(request)
    }
}
