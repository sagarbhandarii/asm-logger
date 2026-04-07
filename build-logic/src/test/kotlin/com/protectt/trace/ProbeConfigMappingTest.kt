package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeConfigMappingTest {
    @Test
    fun `extension probe config defaults are available`() {
        val extension = MethodTraceExtension()

        assertEquals(true, extension.methodProbeEnabled)
        assertEquals(false, extension.networkProbeEnabled)
        assertEquals(false, extension.dbProbeEnabled)
        assertEquals(false, extension.coroutineProbeEnabled)
    }

    @Test
    fun `probe selection config mirrors extension toggles`() {
        val extension = MethodTraceExtension().apply {
            enabled = true
            methodProbeEnabled = true
            networkProbeEnabled = true
            dbProbeEnabled = false
            coroutineProbeEnabled = true
        }

        val config = ProbeSelectionConfig.fromExtension(extension)

        assertEquals(true, config.pluginEnabled)
        assertEquals(true, config.methodProbeEnabled)
        assertEquals(true, config.networkProbeEnabled)
        assertEquals(false, config.dbProbeEnabled)
        assertEquals(true, config.coroutineProbeEnabled)
    }
}
