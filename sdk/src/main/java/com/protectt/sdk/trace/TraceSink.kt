package com.protectt.sdk.trace

import java.io.File
import java.io.RandomAccessFile

internal interface TraceSink {
    fun appendEvents(events: List<String>)
}

internal class JsonTraceSink(
    private val outputFileProvider: () -> File,
) : TraceSink {
    private val lock = Any()
    private var traceFileInitialized = false
    private var writtenEventCount: Long = 0L

    override fun appendEvents(events: List<String>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            val traceFile = outputFileProvider()
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
    }

    companion object {
        private const val TRACE_HEADER = "{\"traceEvents\":[\n"
        private const val TRACE_FOOTER = "\n]}"
        private const val COMMA_NEWLINE = ",\n"

        private val TRACE_HEADER_BYTES = TRACE_HEADER.toByteArray(Charsets.UTF_8)
        private val TRACE_FOOTER_BYTES = TRACE_FOOTER.toByteArray(Charsets.UTF_8)
        private val COMMA_NEWLINE_BYTES = COMMA_NEWLINE.toByteArray(Charsets.UTF_8)
    }
}
