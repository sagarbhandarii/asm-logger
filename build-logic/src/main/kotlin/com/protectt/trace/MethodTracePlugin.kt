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
            val namespace = resolveAndroidNamespace(project)
            val runtimeClassName = extension.runtimeClassName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "$namespace/trace/MethodTraceRuntime"

            val includePrefixes = extension.includePackagePrefixes
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(namespace) }

            val excludePrefixes = extension.excludeClassPrefixes
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty {
                    listOf(
                        runtimeClassName,
                        "$namespace/BuildConfig",
                        "$namespace/R",
                        "$namespace/R$",
                    )
                }

            variant.instrumentation.transformClassesWith(
                MethodTraceVisitorFactory::class.java,
                InstrumentationScope.ALL,
            ) { params ->
                params.enabled.set(project.provider { extension.enabled })
                params.includePackagePrefixes.set(project.provider { includePrefixes })
                params.excludeClassPrefixes.set(project.provider { excludePrefixes })
                params.runtimeClassName.set(project.provider { runtimeClassName })
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }

    private fun resolveAndroidNamespace(project: Project): String {
        val androidExt = project.extensions.findByName("android")
            ?: error("Android extension not found. Apply Android plugin before com.protectt.methodtrace")

        val namespace = androidExt::class.java.methods
            .firstOrNull { it.name == "getNamespace" && it.parameterCount == 0 }
            ?.invoke(androidExt) as? String

        return namespace
            ?.replace('.', '/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Android namespace is required for method trace plugin defaults")
    }
}
