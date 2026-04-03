package com.protectt.trace

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
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

    @TaskAction
    fun generate() {
        val packageName = "${namespace.get()}.trace"
        val packagePath = packageName.replace('.', '/')
        val targetFile = File(outputDir.get().asFile, "$packagePath/MethodTraceRuntime.kt")
        targetFile.parentFile.mkdirs()
        targetFile.writeText(runtimeSource(packageName))
    }

    private fun runtimeSource(packageName: String): String {
        val template = javaClass.getResourceAsStream("/MethodTraceRuntime.template.kt")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("MethodTrace runtime template is missing")

        return template.replace("__PACKAGE__", packageName)
    }
}
