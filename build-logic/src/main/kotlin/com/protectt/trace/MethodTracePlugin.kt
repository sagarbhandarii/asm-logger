package com.protectt.trace

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class MethodTracePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "methodTrace",
            MethodTraceExtension::class.java
        )

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error("com.protectt.methodtrace must be applied after an Android plugin")

        androidComponents.onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                MethodTraceVisitorFactory::class.java,
                InstrumentationScope.PROJECT,
            ) { params ->
                params.enabled.set(project.provider { extension.enabled })
                params.includePackagePrefixes.set(project.provider { extension.includePackagePrefixes })
                params.excludeClassPrefixes.set(project.provider { extension.excludeClassPrefixes })
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }
}
