package com.wrapper.inject

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class WrapperInjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    WrapperClassVisitorFactory::class.java,
                    InstrumentationScope.PROJECT
                ) { params ->
                    params.appPackage.set(variant.namespace ?: project.group.toString())
                    params.excludedPackage.set("com.wrapper.sdk")
                }
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
    }
}
