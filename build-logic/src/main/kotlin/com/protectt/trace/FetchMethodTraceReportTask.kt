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
        doNotTrackState("Writes reports to a user-configurable directory that may contain unreadable files.")
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

        val rawOutput = stdOut.toString()
        if (rawOutput.isBlank()) {
            throw GradleException("Fetched report is empty for package=$pkg path=$remote")
        }

        val root = parseReportRoot(rawOutput)
        val methods = (root["methods"] as? List<*>)
            ?.mapNotNull { it as? MutableMap<String, Any?> }
            .orEmpty()
        if (methods.isNotEmpty()) {
            root["methods"] = methods.sortedByDescending { (it["totalNs"] as? Number)?.toLong() ?: 0L }
        }

        val summaryMethods = (root["methods"] as? List<*>)
            ?.mapNotNull { it as? MutableMap<String, Any?> }
            .orEmpty()
            .takeIf { list -> list.any { it.containsKey("p95Ns") } }
            ?: (root["methodSummaries"] as? List<*>)
                ?.mapNotNull { it as? MutableMap<String, Any?> }
                .orEmpty()

        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        val outFile = outDir.resolve("methodtrace-$ts.json")
        val mdFile = outDir.resolve("methodtrace-$ts.md")

        if (summaryMethods.isNotEmpty()) {
            val enhancement = buildHotspotEnhancement(root = root, summaryMethods = summaryMethods)
            root["rankings"] = enhancement.rankings
            mdFile.writeText(enhancement.markdownSummary)
        }

        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)))

        logger.lifecycle("[methodTrace] Saved sorted report: ${outFile.absolutePath}")
        if (mdFile.exists()) {
            logger.lifecycle("[methodTrace] Saved hotspot markdown summary: ${mdFile.absolutePath}")
        }
    }

    private fun parseReportRoot(rawOutput: String): MutableMap<String, Any?> {
        val trimmed = rawOutput.trim()
        val candidates = linkedSetOf(trimmed)

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidates.add(trimmed.substring(firstBrace, lastBrace + 1))
        }

        if (trimmed.startsWith("{\\") || trimmed.startsWith("[\\")) {
            candidates.add(unescapeLikelyJson(trimmed))
        }

        for (candidate in candidates) {
            val parsed = runCatching { JsonSlurper().parseText(candidate) }.getOrNull() ?: continue
            when (parsed) {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    return parsed as MutableMap<String, Any?>
                }
                is String -> {
                    val parsedInner = runCatching { JsonSlurper().parseText(parsed) }.getOrNull()
                    if (parsedInner is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        return parsedInner as MutableMap<String, Any?>
                    }
                }
            }
        }

        val preview = trimmed.take(160).replace("\n", "\\n")
        throw GradleException(
            "Failed to parse MethodTrace JSON from device output. " +
                "Preview=\"$preview\""
        )
    }

    private fun unescapeLikelyJson(input: String): String {
        val decoded = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '\\' && index + 1 < input.length) {
                when (val next = input[index + 1]) {
                    '\\' -> {
                        decoded.append('\\')
                        index += 2
                    }
                    '"' -> {
                        decoded.append('"')
                        index += 2
                    }
                    'n' -> {
                        decoded.append('\n')
                        index += 2
                    }
                    'r' -> {
                        decoded.append('\r')
                        index += 2
                    }
                    't' -> {
                        decoded.append('\t')
                        index += 2
                    }
                    else -> {
                        decoded.append(current)
                        decoded.append(next)
                        index += 2
                    }
                }
            } else {
                decoded.append(current)
                index++
            }
        }
        return decoded.toString()
    }
}
