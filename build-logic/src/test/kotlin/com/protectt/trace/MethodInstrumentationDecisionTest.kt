package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodInstrumentationDecisionTest {
    @Test
    fun `includes matching class when method probe is active`() {
        val result = MethodInstrumentationDecision.shouldInstrument(
            enabled = true,
            activeProbeIds = listOf(ProbeIds.METHOD_TIMING),
            className = "com/example/Foo",
            includePrefixes = listOf("com/example"),
            excludePrefixes = listOf("com/example/internal"),
            runtimeClassName = "com/example/trace/MethodTraceRuntime",
        )

        assertTrue(result)
    }

    @Test
    fun `excludes runtime class and excluded prefixes`() {
        assertFalse(
            MethodInstrumentationDecision.shouldInstrument(
                enabled = true,
                activeProbeIds = listOf(ProbeIds.METHOD_TIMING),
                className = "com/example/trace/MethodTraceRuntime",
                includePrefixes = emptyList(),
                excludePrefixes = emptyList(),
                runtimeClassName = "com/example/trace/MethodTraceRuntime",
            )
        )

        assertFalse(
            MethodInstrumentationDecision.shouldInstrument(
                enabled = true,
                activeProbeIds = listOf(ProbeIds.METHOD_TIMING),
                className = "com/example/internal/Hidden",
                includePrefixes = emptyList(),
                excludePrefixes = listOf("com/example/internal"),
                runtimeClassName = "com/example/trace/MethodTraceRuntime",
            )
        )
    }

    @Test
    fun `does not instrument when method probe is inactive`() {
        val result = MethodInstrumentationDecision.shouldInstrument(
            enabled = true,
            activeProbeIds = listOf(ProbeIds.NETWORK),
            className = "com/example/Foo",
            includePrefixes = emptyList(),
            excludePrefixes = emptyList(),
            runtimeClassName = "com/example/trace/MethodTraceRuntime",
        )

        assertFalse(result)
    }
}
