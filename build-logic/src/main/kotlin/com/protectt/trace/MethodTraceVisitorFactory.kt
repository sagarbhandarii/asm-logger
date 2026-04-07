package com.protectt.trace

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

abstract class MethodTraceVisitorFactory : AsmClassVisitorFactory<MethodTraceParameters> {
    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className.replace('.', '/')
        return MethodInstrumentationDecision.shouldInstrument(
            enabled = parameters.get().enabled.get(),
            activeProbeIds = parameters.get().activeProbeIds.get(),
            className = className,
            includePrefixes = parameters.get().includePackagePrefixes.get(),
            excludePrefixes = parameters.get().excludeClassPrefixes.get(),
            runtimeClassName = parameters.get().runtimeClassName.get(),
        )
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
