package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeRegistryTest {
    @Test
    fun `method probe is enabled by default`() {
        val extension = MethodTraceExtension()
        val config = ProbeSelectionConfig.fromExtension(extension)

        val active = ProbeRegistry().activeProbeIds(config)

        assertEquals(listOf(ProbeIds.METHOD_TIMING), active)
    }

    @Test
    fun `future probes are selected only when explicitly enabled`() {
        val extension = MethodTraceExtension().apply {
            networkProbeEnabled = true
            dbProbeEnabled = true
        }

        val active = ProbeRegistry().activeProbeIds(ProbeSelectionConfig.fromExtension(extension))

        assertEquals(listOf(ProbeIds.METHOD_TIMING, ProbeIds.NETWORK, ProbeIds.DATABASE), active)
    }

    @Test
    fun `plugin disabled disables all probes`() {
        val extension = MethodTraceExtension().apply {
            enabled = false
            networkProbeEnabled = true
            dbProbeEnabled = true
            coroutineProbeEnabled = true
        }

        val active = ProbeRegistry().activeProbeIds(ProbeSelectionConfig.fromExtension(extension))

        assertEquals(emptyList(), active)
    }
}
