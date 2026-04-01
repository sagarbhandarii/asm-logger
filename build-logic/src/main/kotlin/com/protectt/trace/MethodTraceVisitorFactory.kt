package com.protectt.trace

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

abstract class MethodTraceVisitorFactory : AsmClassVisitorFactory<MethodTraceParameters> {
    override fun isInstrumentable(classData: ClassData): Boolean {
        if (!parameters.get().enabled.get()) return false

        val className = classData.className.replace('.', '/')
        val runtimeClassName = parameters.get().runtimeClassName.get()
        if (className == runtimeClassName) return false

        val includePrefixes = parameters.get().includePackagePrefixes.get()
        val included = includePrefixes.isEmpty() || includePrefixes.any { prefix -> className.startsWith(prefix) }
        if (!included) return false

        val excluded = parameters.get().excludeClassPrefixes.get().any { prefix ->
            className.startsWith(prefix)
        }
        return !excluded
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        return MethodTraceClassVisitor(
            api = instrumentationContext.apiVersion.get(),
            next = nextClassVisitor,
            runtimeClassName = parameters.get().runtimeClassName.get(),
        )
    }
}
