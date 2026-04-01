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

        val namespace = resolveAndroidNamespace(project)
        ensureRuntimeObject(project, namespace)

        androidComponents.onVariants { variant ->
            val runtimeClassName = extension.runtimeClassName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "$namespace/trace/MethodTraceRuntime"
            val isLibraryModule = project.plugins.hasPlugin("com.android.library")
            val allowDependencyInstrumentation = extension.includeThirdPartySdks && !isLibraryModule
            if (extension.includeThirdPartySdks && isLibraryModule) {
                project.logger.warn(
                    "methodTrace.includeThirdPartySdks=true is not supported for Android library modules. " +
                        "Falling back to project-only instrumentation for ${project.path}:${variant.name}."
                )
            }

            val includePrefixes = extension.includePackagePrefixes
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty {
                    if (extension.includeThirdPartySdks) {
                        emptyList()
                    } else {
                        listOf(namespace)
                    }
                }

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
                if (allowDependencyInstrumentation) InstrumentationScope.ALL else InstrumentationScope.PROJECT,
            ) { params ->
                params.enabled.set(project.provider { extension.enabled })
                params.includePackagePrefixes.set(project.provider { includePrefixes })
                params.excludeClassPrefixes.set(project.provider { excludePrefixes })
                params.runtimeClassName.set(project.provider { runtimeClassName })
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }


    private fun ensureRuntimeObject(project: Project, namespacePath: String) {
        if (runtimeSourceExists(project, namespacePath)) return

        val androidExt = project.extensions.findByName("android")
            ?: return

        val namespaceDot = namespacePath.replace('/', '.')
        val outputDirProvider = project.layout.buildDirectory.dir("generated/source/methodtrace/runtime")
        val generateTask = project.tasks.register(
            "generateMethodTraceRuntime",
            GenerateMethodTraceRuntimeTask::class.java
        )
        generateTask.configure { task ->
            task.namespace.set(namespaceDot)
            task.outputDir.set(outputDirProvider)
        }

        val sourceSets = androidExt::class.java.methods
            .firstOrNull { it.name == "getSourceSets" && it.parameterCount == 0 }
            ?.invoke(androidExt)
            ?: return

        val iterator = (sourceSets as? Iterable<*>)?.iterator() ?: return
        while (iterator.hasNext()) {
            val sourceSet = iterator.next() ?: continue
            val getName = sourceSet::class.java.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            val name = getName?.invoke(sourceSet) as? String ?: continue
            if (name != "main") continue

            val javaBlock = sourceSet::class.java.methods
                .firstOrNull { it.name == "getJava" && it.parameterCount == 0 }
                ?.invoke(sourceSet)
                ?: continue

            javaBlock::class.java.methods
                .firstOrNull { it.name == "srcDir" && it.parameterCount == 1 }
                ?.invoke(javaBlock, outputDirProvider)

            project.tasks.matching { it.name == "preBuild" }.configureEach {
                it.dependsOn(generateTask)
            }
            break
        }
    }

    private fun runtimeSourceExists(project: Project, namespacePath: String): Boolean {
        val runtimeRel = namespacePath + "/trace/MethodTraceRuntime"
        val roots = listOf("src/main/java", "src/main/kotlin")
        val extensions = listOf("kt", "java")
        return roots.any { root ->
            extensions.any { ext ->
                project.file("$root/$runtimeRel.$ext").exists()
            }
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
