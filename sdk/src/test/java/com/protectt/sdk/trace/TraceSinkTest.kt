package com.protectt.sdk.trace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TraceSinkTest {
    @Test
    fun jsonSinkAppendsTraceEventsInExistingFormat() {
        val tempFile = File.createTempFile("trace-sink", ".json")
        tempFile.deleteOnExit()
        val sink = JsonTraceSink { tempFile }

        sink.appendEvents(listOf("{\"name\":\"one\"}"))
        sink.appendEvents(listOf("{\"name\":\"two\"}"))

        val content = tempFile.readText()
        assertTrue(content.contains("\"traceEvents\""))
        assertTrue(content.contains("\"name\":\"one\""))
        assertTrue(content.contains("\"name\":\"two\""))
    }
}
