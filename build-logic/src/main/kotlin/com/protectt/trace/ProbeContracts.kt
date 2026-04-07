package com.protectt.trace

object ProbeIds {
    const val METHOD_TIMING: String = "method"
    const val NETWORK: String = "network"
    const val DATABASE: String = "db"
    const val COROUTINE: String = "coroutine"
}

interface InstrumentationProbe {
    val id: String

    fun isEnabled(config: ProbeSelectionConfig): Boolean
}

data class ProbeSelectionConfig(
    val pluginEnabled: Boolean,
    val methodProbeEnabled: Boolean,
    val networkProbeEnabled: Boolean,
    val dbProbeEnabled: Boolean,
    val coroutineProbeEnabled: Boolean,
) {
    companion object {
        fun fromExtension(extension: MethodTraceExtension): ProbeSelectionConfig {
            return ProbeSelectionConfig(
                pluginEnabled = extension.enabled,
                methodProbeEnabled = extension.methodProbeEnabled,
                networkProbeEnabled = extension.networkProbeEnabled,
                dbProbeEnabled = extension.dbProbeEnabled,
                coroutineProbeEnabled = extension.coroutineProbeEnabled,
            )
        }
    }
}

object MethodProbe : InstrumentationProbe {
    override val id: String = ProbeIds.METHOD_TIMING

    override fun isEnabled(config: ProbeSelectionConfig): Boolean {
        return config.pluginEnabled && config.methodProbeEnabled
    }
}

object NetworkProbe : InstrumentationProbe {
    override val id: String = ProbeIds.NETWORK

    override fun isEnabled(config: ProbeSelectionConfig): Boolean {
        return config.pluginEnabled && config.networkProbeEnabled
    }
}

object DbProbe : InstrumentationProbe {
    override val id: String = ProbeIds.DATABASE

    override fun isEnabled(config: ProbeSelectionConfig): Boolean {
        return config.pluginEnabled && config.dbProbeEnabled
    }
}

object CoroutineProbe : InstrumentationProbe {
    override val id: String = ProbeIds.COROUTINE

    override fun isEnabled(config: ProbeSelectionConfig): Boolean {
        return config.pluginEnabled && config.coroutineProbeEnabled
    }
}

class ProbeRegistry(
    private val probes: List<InstrumentationProbe> = listOf(
        MethodProbe,
        NetworkProbe,
        DbProbe,
        CoroutineProbe,
    ),
) {
    fun activeProbeIds(config: ProbeSelectionConfig): List<String> {
        return probes
            .filter { it.isEnabled(config) }
            .map { it.id }
    }
}
