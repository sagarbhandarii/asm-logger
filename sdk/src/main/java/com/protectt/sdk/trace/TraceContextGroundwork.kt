package com.protectt.sdk.trace

data class TraceSessionContext(
    val traceId: String,
    val sessionId: String,
    val transactionId: String?,
    val activeSpanId: String?,
) {
    fun encodeForHeader(): String {
        val transaction = transactionId ?: ""
        val span = activeSpanId ?: ""
        return listOf(traceId, sessionId, transaction, span).joinToString("|")
    }

    companion object {
        fun decodeFromHeader(raw: String?): TraceSessionContext? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size < 2) return null
            return TraceSessionContext(
                traceId = parts[0],
                sessionId = parts[1],
                transactionId = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                activeSpanId = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
