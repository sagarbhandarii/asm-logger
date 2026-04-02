package com.protectt.trace

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.gradle.process.ExecOperations

abstract class FetchMethodTraceReportTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val deviceReportPath: Property<String>

    @get:Input
    abstract val waitSeconds: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "method trace"
        description = "Waits, pulls MethodTrace JSON from device, sorts methods by totalNs, and writes local report."
    }

    @TaskAction
    fun fetch() {
        val waitMs = waitSeconds.get().coerceAtLeast(0) * 1_000L
        if (waitMs > 0) {
            logger.lifecycle("[methodTrace] Waiting ${waitSeconds.get()} seconds before pulling report...")
            Thread.sleep(waitMs)
        }

        val pkg = applicationId.get().trim()
        val remote = deviceReportPath.get().trim()
        if (pkg.isEmpty()) throw GradleException("methodTrace.reportApplicationId is required")
        if (remote.isEmpty()) throw GradleException("methodTrace.reportDevicePath is required")

        val stdOut = ByteArrayOutputStream()
        val stdErr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("adb", "shell", "run-as", pkg, "cat", remote)
            standardOutput = stdOut
            errorOutput = stdErr
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            val err = stdErr.toString().ifBlank { "Unknown adb error" }
            throw GradleException(
                "Failed to fetch MethodTrace report from device. " +
                    "package=$pkg path=$remote error=$err"
            )
        }

        val jsonText = stdOut.toString().trim()
        if (jsonText.isEmpty()) {
            throw GradleException("Fetched report is empty for package=$pkg path=$remote")
        }

        val parsed = JsonSlurper().parseText(jsonText)
        if (parsed !is MutableMap<*, *>) {
            throw GradleException("Unexpected JSON shape. Expected object at root.")
        }

        @Suppress("UNCHECKED_CAST")
        val root = parsed as MutableMap<String, Any?>
        val methods = (root["methods"] as? List<*>)
            ?.mapNotNull { it as? MutableMap<String, Any?> }
            .orEmpty()
            .sortedByDescending { (it["totalNs"] as? Number)?.toLong() ?: 0L }

        root["methods"] = methods

        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        val outFile = outDir.resolve("methodtrace-$ts.json")
        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)))

        logger.lifecycle("[methodTrace] Saved sorted report: ${outFile.absolutePath}")
    }
}
