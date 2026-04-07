package com.protectt.trace

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

interface MethodTraceParameters : InstrumentationParameters {
    @get:Input
    val enabled: Property<Boolean>

    @get:Input
    val includePackagePrefixes: ListProperty<String>

    @get:Input
    val excludeClassPrefixes: ListProperty<String>

    @get:Input
    val runtimeClassName: Property<String>

    @get:Input
    val activeProbeIds: ListProperty<String>

    @get:Input
    val probeConfigValues: MapProperty<String, String>
}
