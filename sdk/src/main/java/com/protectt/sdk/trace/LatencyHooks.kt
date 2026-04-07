package com.protectt.sdk.trace

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Response
import java.security.MessageDigest
import java.util.Locale

internal object HookRuntimeBridge {
    var networkEnabled: () -> Boolean = { MethodTraceRuntime.networkTimingHookEnabled }
    var dbEnabled: () -> Boolean = { MethodTraceRuntime.dbTimingHookEnabled }
    var correlation: () -> TraceCorrelationContext = { MethodTraceRuntime.currentCorrelationContext() }
    var emitNetwork: (NetworkTimingEvent) -> Unit = { MethodTraceRuntime.recordNetworkTiming(it) }
    var emitDb: (DbTimingEvent, String) -> Unit = { event, suffix -> MethodTraceRuntime.recordDbTiming(event, suffix) }
}

internal data class TraceCorrelationContext(
    val traceId: String,
    val activeSpanId: String?,
    val threadId: Long,
    val threadName: String,
)

internal data class NetworkTimingEvent(
    val requestStartNs: Long,
    val durationNs: Long,
    val host: String,
    val pathTemplate: String,
    val method: String,
    val responseCode: Int?,
    val protocol: String?,
    val failed: Boolean,
    val correlation: TraceCorrelationContext,
) {
    fun toArgsJson(): String = buildString(256) {
        append('{')
        append("\"kind\":\"network\",")
        append("\"host\":\"").append(host.escapeJson()).append("\",")
        append("\"pathTemplate\":\"").append(pathTemplate.escapeJson()).append("\",")
        append("\"method\":\"").append(method.escapeJson()).append("\",")
        append("\"responseCode\":")
        if (responseCode == null) append("null") else append(responseCode)
        append(',')
        append("\"protocol\":")
        if (protocol == null) append("null") else append('"').append(protocol.escapeJson()).append('"')
        append(',')
        append("\"failed\":").append(failed).append(',')
        append("\"traceId\":\"").append(correlation.traceId.escapeJson()).append("\",")
        append("\"activeSpanId\":")
        if (correlation.activeSpanId == null) append("null") else append('"').append(correlation.activeSpanId.escapeJson()).append('"')
        append(',')
        append("\"threadId\":").append(correlation.threadId).append(',')
        append("\"threadName\":\"").append(correlation.threadName.escapeJson()).append("\"")
        append('}')
    }
}

internal data class DbTimingEvent(
    val queryStartNs: Long,
    val durationNs: Long,
    val operation: String,
    val tableHint: String?,
    val statementFingerprint: String,
    val threadId: Long,
    val threadName: String,
    val correlation: TraceCorrelationContext,
) {
    fun toArgsJson(): String = buildString(256) {
        append('{')
        append("\"kind\":\"db\",")
        append("\"operation\":\"").append(operation.escapeJson()).append("\",")
        append("\"tableHint\":")
        if (tableHint == null) append("null") else append('"').append(tableHint.escapeJson()).append('"')
        append(',')
        append("\"statementFingerprint\":\"").append(statementFingerprint).append("\",")
        append("\"threadId\":").append(threadId).append(',')
        append("\"threadName\":\"").append(threadName.escapeJson()).append("\",")
        append("\"traceId\":\"").append(correlation.traceId.escapeJson()).append("\",")
        append("\"activeSpanId\":")
        if (correlation.activeSpanId == null) append("null") else append('"').append(correlation.activeSpanId.escapeJson()).append('"')
        append('}')
    }
}

class ProtecttOkHttpEventListenerFactory(
    private val redactPathSegments: Boolean = true,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        return ProtecttOkHttpEventListener(call, redactPathSegments)
    }
}

internal class ProtecttOkHttpEventListener(
    private val call: Call,
    private val redactPathSegments: Boolean,
) : EventListener() {
    private var requestStartNs: Long = 0L
    private var statusCode: Int? = null
    private var protocol: Protocol? = null

    override fun callStart(call: Call) {
        requestStartNs = System.nanoTime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        statusCode = response.code
        protocol = response.protocol
    }

    override fun callEnd(call: Call) {
        emit(failed = false)
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        emit(failed = true)
    }

    private fun emit(failed: Boolean) {
        if (!HookRuntimeBridge.networkEnabled()) return
        val startNs = requestStartNs.takeIf { it > 0L } ?: System.nanoTime()
        val endNs = System.nanoTime()
        val url = call.request().url
        val event = NetworkTimingEvent(
            requestStartNs = startNs,
            durationNs = (endNs - startNs).coerceAtLeast(0L),
            host = url.host,
            pathTemplate = safePathTemplate(url = url, redact = redactPathSegments),
            method = call.request().method,
            responseCode = statusCode,
            protocol = protocol?.toString(),
            failed = failed,
            correlation = HookRuntimeBridge.correlation(),
        )
        HookRuntimeBridge.emitNetwork(event)
    }
}

object DbTimingHooks {
    inline fun <T> timeQuery(sql: String, block: () -> T): T {
        if (!HookRuntimeBridge.dbEnabled()) return block()

        val startNs = System.nanoTime()
        var thrown: Throwable? = null
        return try {
            block()
        } catch (t: Throwable) {
            thrown = t
            throw t
        } finally {
            val durationNs = (System.nanoTime() - startNs).coerceAtLeast(0L)
            val operation = deriveSqlOperation(sql)
            val table = deriveSqlTableHint(sql)
            val normalized = normalizeSql(sql)
            val suffix = if (thrown == null) ":ok" else ":error"
            val event = DbTimingEvent(
                queryStartNs = startNs,
                durationNs = durationNs,
                operation = operation,
                tableHint = table,
                statementFingerprint = sha256Hex(normalized),
                threadId = Thread.currentThread().id,
                threadName = Thread.currentThread().name,
                correlation = HookRuntimeBridge.correlation(),
            )
            HookRuntimeBridge.emitDb(event, suffix)
        }
    }

    fun asRoomQueryCallback(): (String, List<Any?>) -> Unit {
        return { sql, _ ->
            if (HookRuntimeBridge.dbEnabled()) {
                val startNs = System.nanoTime()
                val event = DbTimingEvent(
                    queryStartNs = startNs,
                    durationNs = 0L,
                    operation = deriveSqlOperation(sql),
                    tableHint = deriveSqlTableHint(sql),
                    statementFingerprint = sha256Hex(normalizeSql(sql)),
                    threadId = Thread.currentThread().id,
                    threadName = Thread.currentThread().name,
                    correlation = HookRuntimeBridge.correlation(),
                )
                HookRuntimeBridge.emitDb(event, ":callback")
            }
        }
    }
}

internal fun safePathTemplate(url: HttpUrl, redact: Boolean): String {
    if (!redact) return "/" + url.pathSegments.joinToString("/")
    if (url.pathSegments.isEmpty()) return "/"
    return "/" + url.pathSegments.joinToString("/") { segment ->
        when {
            segment.isBlank() -> "_"
            segment.length > 48 -> ":id"
            segment.any { it.isDigit() } -> ":id"
            segment.count { it == '-' } >= 3 -> ":id"
            else -> segment.lowercase(Locale.US)
        }
    }
}

internal fun deriveSqlOperation(sql: String): String {
    val trimmed = sql.trimStart()
    if (trimmed.isBlank()) return "UNKNOWN"
    return trimmed.takeWhile { !it.isWhitespace() }
        .uppercase(Locale.US)
        .take(16)
        .ifBlank { "UNKNOWN" }
}

internal fun deriveSqlTableHint(sql: String): String? {
    val tokens = sql.trim().split(Regex("\\s+"))
    if (tokens.isEmpty()) return null
    val upperTokens = tokens.map { it.uppercase(Locale.US) }
    val keywordIndex = when {
        upperTokens.firstOrNull() == "SELECT" -> upperTokens.indexOf("FROM")
        upperTokens.firstOrNull() == "UPDATE" -> 0
        upperTokens.firstOrNull() == "INSERT" -> upperTokens.indexOf("INTO")
        upperTokens.firstOrNull() == "DELETE" -> upperTokens.indexOf("FROM")
        else -> -1
    }
    if (keywordIndex < 0) return null
    val tableTokenIndex = if (upperTokens.firstOrNull() == "UPDATE") 1 else keywordIndex + 1
    return tokens.getOrNull(tableTokenIndex)
        ?.trim(',', ';', '"', '\'', '`')
        ?.takeIf { it.isNotBlank() }
        ?.take(64)
}

internal fun normalizeSql(sql: String): String {
    return sql
        .replace(Regex("'([^']|\\\\')*'"), "?")
        .replace(Regex("\"([^\"]|\\\\\")*\""), "?")
        .replace(Regex("\\b\\d+\\b"), "?")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(512)
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    digest.forEach { byte ->
        out.append(((byte.toInt() ushr 4) and 0xF).toString(16))
        out.append((byte.toInt() and 0xF).toString(16))
    }
    return out.toString()
}

private fun String.escapeJson(): String {
    val escaped = StringBuilder(length + 8)
    forEach { ch ->
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
