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

        val candidatePaths = buildRemotePathCandidates(pkg = pkg, configuredPath = remote)
        var fetchedRawOutput: String? = null
        val failures = mutableListOf<String>()
        var resolvedRemotePath: String? = null
        for (candidate in candidatePaths) {
            val attempt = fetchFromDevice(pkg = pkg, remotePath = candidate)
            if (attempt.exitValue == 0) {
                fetchedRawOutput = attempt.output
                resolvedRemotePath = candidate
                break
            }
            val error = attempt.error.ifBlank { "Unknown adb error" }
            failures += "path=$candidate error=$error"
        }

        if (fetchedRawOutput == null) {
            val failureSummary = failures.joinToString(separator = " | ")
            throw GradleException(
                "Failed to fetch MethodTrace report from device. " +
                    "package=$pkg attemptedPaths=${candidatePaths.joinToString()} details=$failureSummary"
            )
        }

        if (candidatePaths.size > 1 && resolvedRemotePath != null && resolvedRemotePath != remote) {
            logger.lifecycle(
                "[methodTrace] Fetched report using fallback path \"$resolvedRemotePath\" " +
                    "(configured \"$remote\")"
            )
        }

        val rawOutput = fetchedRawOutput ?: throw GradleException("Fetched report unexpectedly missing for package=$pkg")
        if (rawOutput.isBlank()) {
            val pathHint = resolvedRemotePath ?: remote
            throw GradleException("Fetched report is empty for package=$pkg path=$pathHint")
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
        val issuesJsonFile = outDir.resolve("top10-issues.json")
        val issuesMdFile = outDir.resolve("top10-issues.md")
        val issuesHtmlFile = outDir.resolve("top10-issues.html")

        if (summaryMethods.isNotEmpty()) {
            val historicalRoots = loadHistoricalMethodTraceRoots(outDir, maxFiles = 20)
            val previousRoot = historicalRoots.firstOrNull()
            val regression = buildRegressionAnalysis(
                currentRoot = root,
                currentMethods = summaryMethods,
                previousRoot = previousRoot,
            )
            val trend = buildTrendAnalysis(
                historyRoots = historicalRoots.reversed() + listOf(root),
                currentMethods = summaryMethods,
            )

            val enhancement = buildHotspotEnhancement(root = root, summaryMethods = summaryMethods)
            root["rankings"] = enhancement.rankings
            root["regressions"] = regression.payload
            root["trends"] = trend.payload
            mdFile.writeText(enhancement.markdownSummary)

            val issues = IssueAnalyzer(topN = 10).analyze(
                root = root,
                summaryMethods = summaryMethods,
                signals = IssueAnalysisSignals(
                    regressionWeightsByMethod = regression.regressionWeightsByMethod,
                    frequencyTrendByMethod = trend.frequencyTrendByMethod,
                ),
            )
            root["topIssues"] = issues.issues.map { it.toMap() }
            issuesJsonFile.writeText(issues.toJson())
            issuesMdFile.writeText(issues.toMarkdown())
            issuesHtmlFile.writeText(
                buildIssueHtmlReport(
                    issues = issues.issues,
                    regression = regression.payload,
                    trends = trend.payload,
                ),
            )
        }

        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)))

        logger.lifecycle("[methodTrace] Saved sorted report: ${outFile.absolutePath}")
        if (mdFile.exists()) {
            logger.lifecycle("[methodTrace] Saved hotspot markdown summary: ${mdFile.absolutePath}")
        }
        if (issuesJsonFile.exists()) {
            logger.lifecycle("[methodTrace] Saved Top 10 issues JSON: ${issuesJsonFile.absolutePath}")
        }
        if (issuesMdFile.exists()) {
            logger.lifecycle("[methodTrace] Saved Top 10 issues markdown: ${issuesMdFile.absolutePath}")
        }
        if (issuesHtmlFile.exists()) {
            logger.lifecycle("[methodTrace] Saved Top 10 issues HTML: ${issuesHtmlFile.absolutePath}")
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

    private fun buildRemotePathCandidates(pkg: String, configuredPath: String): List<String> {
        val path = configuredPath.trim()
        val candidates = linkedSetOf(path)
        val normalized = path.removePrefix("./").trimStart('/')
        val fileName = normalized.substringAfterLast('/')

        if (!normalized.contains("/")) {
            candidates += "files/$normalized"
        }

        if (normalized.startsWith("files/")) {
            candidates += normalized.removePrefix("files/")
        }

        if (fileName.isNotBlank()) {
            candidates += "files/$fileName"
            candidates += "/data/data/$pkg/files/$fileName"
            candidates += "/data/user/0/$pkg/files/$fileName"
            candidates += "/data/user_de/0/$pkg/files/$fileName"
        }

        if (normalized.isNotBlank()) {
            candidates += normalized
            candidates += "/$normalized"
        }

        return candidates.toList()
    }

    private fun fetchFromDevice(pkg: String, remotePath: String): FetchAttempt {
        val stdOut = ByteArrayOutputStream()
        val stdErr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("adb", "shell", "run-as", pkg, "cat", remotePath)
            standardOutput = stdOut
            errorOutput = stdErr
            isIgnoreExitValue = true
        }
        return FetchAttempt(
            exitValue = result.exitValue,
            output = stdOut.toString(),
            error = stdErr.toString(),
        )
    }

    private data class FetchAttempt(
        val exitValue: Int,
        val output: String,
        val error: String,
    )

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
