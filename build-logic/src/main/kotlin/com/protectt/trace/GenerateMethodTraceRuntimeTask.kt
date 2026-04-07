package com.protectt.trace

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateMethodTraceRuntimeTask : DefaultTask() {
    @get:Input
    abstract val namespace: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val runtimeTemplates: MapProperty<String, String>

    @TaskAction
    fun generate() {
        val packageName = "${namespace.get()}.trace"
        val packagePath = packageName.replace('.', '/')
        val packageOutputDir = File(outputDir.get().asFile, packagePath)
        val templates = runtimeTemplates.get().toSortedMap()

        check(templates.isNotEmpty()) { "MethodTrace runtime template is missing" }

        if (packageOutputDir.exists()) {
            packageOutputDir.deleteRecursively()
        }

        templates.forEach { (templateName, templateSource) ->
            val outputFileName = resolveOutputFileName(templateName)
            val targetFile = File(packageOutputDir, outputFileName)
            targetFile.parentFile.mkdirs()
            targetFile.writeText(runtimeSource(templateSource, packageName))
        }
    }

    private fun resolveOutputFileName(templateName: String): String {
        return templateName.replace(".template", "")
    }

    private fun runtimeSource(templateSource: String, packageName: String): String {
        return templateSource.replace("__PACKAGE__", packageName)
    }
}
