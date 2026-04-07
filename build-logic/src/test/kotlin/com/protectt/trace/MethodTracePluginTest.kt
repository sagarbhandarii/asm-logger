package com.protectt.trace

import kotlin.test.Test
import kotlin.test.assertContains

class MethodTracePluginTest {
    @Test
    fun `default excludes cover generated runtime package`() {
        val plugin = MethodTracePlugin()
        val extension = MethodTraceExtension()
        val method = MethodTracePlugin::class.java.getDeclaredMethod(
            "resolveExcludePrefixes",
            String::class.java,
            String::class.java,
            MethodTraceExtension::class.java,
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val excludes = method.invoke(
            plugin,
            "com/example/app",
            "com/example/app/trace/MethodTraceRuntime",
            extension,
        ) as List<String>

        assertContains(excludes, "com/example/app/trace/")
    }
}
