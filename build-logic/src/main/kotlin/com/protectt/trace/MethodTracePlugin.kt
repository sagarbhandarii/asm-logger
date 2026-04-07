package com.protectt.trace

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.nio.charset.StandardCharsets

class MethodTracePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("methodTrace", MethodTraceExtension::class.java)
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error("com.protectt.methodtrace must be applied after an Android plugin")

        registerFetchReportTask(project, extension)

        project.afterEvaluate {
            val namespace = resolveAndroidNamespace(project)
            ensureRuntimeObject(project, namespace)
        }

        androidComponents.onVariants { variant ->
            val namespace = resolveAndroidNamespace(project)
            val runtimeClassName = resolveRuntimeClassName(namespace, extension)
            val isLibraryModule = project.plugins.hasPlugin("com.android.library")
            val allowDependencyInstrumentation = extension.includeThirdPartySdks && !isLibraryModule
            val activeProbeIds = ProbeRegistry().activeProbeIds(ProbeSelectionConfig.fromExtension(extension))

            if (extension.includeThirdPartySdks && isLibraryModule) {
                project.logger.warn(
                    "methodTrace.includeThirdPartySdks=true is not supported for Android library modules. " +
                        "Falling back to project-only instrumentation for ${project.path}:${variant.name}."
                )
            }

            val includePrefixes = resolveIncludePrefixes(namespace, extension)
            val excludePrefixes = resolveExcludePrefixes(namespace, runtimeClassName, extension)

            variant.instrumentation.transformClassesWith(
                MethodTraceVisitorFactory::class.java,
                if (allowDependencyInstrumentation) InstrumentationScope.ALL else InstrumentationScope.PROJECT,
            ) { params ->
                params.enabled.set(project.provider { extension.enabled })
                params.includePackagePrefixes.set(project.provider { includePrefixes })
                params.excludeClassPrefixes.set(project.provider { excludePrefixes })
                params.runtimeClassName.set(project.provider { runtimeClassName })
                params.activeProbeIds.set(project.provider { activeProbeIds })
                params.probeConfigValues.set(project.provider { resolveProbeConfig(extension) })
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }

    private fun resolveProbeConfig(extension: MethodTraceExtension): Map<String, String> {
        val core = mapOf(
            "method.startupWindowMs" to extension.startupWindowMs.toString(),
            "method.logEachCall" to extension.logEachCall.toString(),
            "method.captureThreadName" to extension.captureThreadName.toString(),
        )
        return core + extension.probeConfigs
    }

    private fun registerFetchReportTask(project: Project, extension: MethodTraceExtension) {
        val fetchReportTask = project.tasks.register("fetchMethodTraceReport", FetchMethodTraceReportTask::class.java)
        fetchReportTask.configure {
            applicationId.set(project.provider { extension.reportApplicationId })
            deviceReportPath.set(project.provider { extension.reportDevicePath })
            waitSeconds.set(project.provider { extension.reportFetchWaitSeconds })
            outputDir.set(project.rootProject.layout.projectDirectory.dir("."))
        }
    }

    private fun resolveRuntimeClassName(namespace: String, extension: MethodTraceExtension): String {
        return extension.runtimeClassName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "$namespace/trace/MethodTraceRuntime"
    }

    private fun resolveIncludePrefixes(namespace: String, extension: MethodTraceExtension): List<String> {
        return extension.includePackagePrefixes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty {
                if (extension.includeThirdPartySdks) {
                    emptyList()
                } else {
                    listOf(namespace)
                }
            }
    }

    private fun resolveExcludePrefixes(
        namespace: String,
        runtimeClassName: String,
        extension: MethodTraceExtension,
    ): List<String> {
        val runtimePackagePrefix = runtimeClassName.substringBeforeLast('/', missingDelimiterValue = runtimeClassName)
        return extension.excludeClassPrefixes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty {
                listOf(
                    runtimeClassName,
                    "$runtimePackagePrefix/",
                    "$namespace/BuildConfig",
                    "$namespace/R",
                    "$namespace/R$",
                )
            }
    }

    private fun ensureRuntimeObject(project: Project, namespacePath: String) {
        if (runtimeSourceExists(project, namespacePath)) return

        val androidExt = project.extensions.findByName("android") ?: return

        val namespaceDot = namespacePath.replace('/', '.')
        val outputDirProvider = project.layout.buildDirectory.dir("generated/source/methodtrace/runtime")
        val templateSources = loadRuntimeTemplates()
        val generateTask = project.tasks.register("generateMethodTraceRuntime", GenerateMethodTraceRuntimeTask::class.java)
        generateTask.configure {
            namespace.set(namespaceDot)
            outputDir.set(outputDirProvider)
            runtimeTemplates.set(templateSources)
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
                dependsOn(generateTask)
            }
            break
        }
    }

    private fun loadRuntimeTemplates(): Map<String, String> {
        val indexResource = "MethodTraceRuntime.templates"
        val indexLines = javaClass.classLoader.getResourceAsStream(indexResource)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { reader ->
                reader.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
            ?: error("Runtime template index resource '$indexResource' is missing")

        return indexLines.associateWith { templateName ->
            javaClass.classLoader.getResourceAsStream(templateName)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Runtime template resource '$templateName' is missing")
        }
    }

    private fun runtimeSourceExists(project: Project, namespacePath: String): Boolean {
        val runtimeRel = "$namespacePath/trace/MethodTraceRuntime"
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
