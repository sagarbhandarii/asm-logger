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

    private fun runtimeSource(packageName: String): String = """
        package $packageName

        import android.os.SystemClock
        import java.io.File
        import java.util.concurrent.ConcurrentHashMap
        import java.util.concurrent.atomic.AtomicLong

        object MethodTraceRuntime {
            @Volatile
            var enabled: Boolean = true

            @Volatile
            var startupTracingOnly: Boolean = true

            @Volatile
            var startupWindowMs: Long = 15_000L

            private val processStartMs = SystemClock.elapsedRealtime()
            private val totals = ConcurrentHashMap<String, AtomicLong>()
            private val counts = ConcurrentHashMap<String, AtomicLong>()
            private val maxNs = ConcurrentHashMap<String, AtomicLong>()

            @JvmStatic
            fun enter(methodId: String): Long {
                if (!shouldTrace()) return 0L
                return SystemClock.elapsedRealtimeNanos()
            }

            @JvmStatic
            fun exit(methodId: String, startNanos: Long) {
                if (startNanos == 0L || !shouldTrace()) return

                val durationNs = SystemClock.elapsedRealtimeNanos() - startNanos
                totals.getOrPut(methodId) { AtomicLong() }.addAndGet(durationNs)
                counts.getOrPut(methodId) { AtomicLong() }.incrementAndGet()
                maxNs.getOrPut(methodId) { AtomicLong() }.accumulateAndGet(durationNs) { old, new ->
                    if (new > old) new else old
                }

                persistJsonReport()
            }

            @JvmStatic
            fun buildTopJson(limit: Int = 200): String {
                val rows = totals.keys.map { method ->
                    val total = totals[method]?.get() ?: 0L
                    val count = counts[method]?.get() ?: 0L
                    val max = maxNs[method]?.get() ?: 0L
                    Triple(method, total, Pair(count, max))
                }.sortedByDescending { it.second }
                    .take(limit)

                val methodsJson = rows.joinToString(separator = ",") { row ->
                    val method = row.first
                    val totalNs = row.second
                    val count = row.third.first
                    val maxNsValue = row.third.second
                    val avgNs = if (count == 0L) 0.0 else totalNs.toDouble() / count
                    val totalMs = totalNs / 1_000_000.0
                    val avgMs = avgNs / 1_000_000.0
                    val maxMs = maxNsValue / 1_000_000.0
                    "{" +
                        "\\\"methodId\\\":\\\"${'$'}{method.escapeJson()}\\\"," +
                        "\\\"count\\\":${'$'}count," +
                        "\\\"totalNs\\\":${'$'}totalNs," +
                        "\\\"totalMs\\\":${'$'}totalMs," +
                        "\\\"avgMs\\\":${'$'}avgMs," +
                        "\\\"maxNs\\\":${'$'}maxNsValue," +
                        "\\\"maxMs\\\":${'$'}maxMs" +
                        "}"
                }

                return "{" +
                    "\\\"generatedAtEpochMs\\\":${'$'}{System.currentTimeMillis()}," +
                    "\\\"startupTracingOnly\\\":${'$'}startupTracingOnly," +
                    "\\\"startupWindowMs\\\":${'$'}startupWindowMs," +
                    "\\\"methodCount\\\":${'$'}{rows.size}," +
                    "\\\"methods\\\":[${'$'}methodsJson]" +
                    "}"
            }

            private fun persistJsonReport() {
                runCatching {
                    val reportFile = resolveOutputFile()
                    reportFile.parentFile?.mkdirs()
                    reportFile.writeText(buildTopJson())
                }
            }

            private fun resolveOutputFile(): File {
                val configured = System.getProperty("method.trace.output.path")?.trim().orEmpty()
                if (configured.isNotEmpty()) return File(configured)

                val tempDir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
                val baseDir = if (tempDir.isNotEmpty()) File(tempDir) else File("/data/local/tmp")
                return File(baseDir, "methodtrace-report.json")
            }

            private fun shouldTrace(): Boolean {
                if (!enabled) return false
                if (!startupTracingOnly) return true
                return SystemClock.elapsedRealtime() - processStartMs <= startupWindowMs
            }

            private fun String.escapeJson(): String = buildString(length) {
                this@escapeJson.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '\"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
            }
        }
    """.trimIndent()
}
