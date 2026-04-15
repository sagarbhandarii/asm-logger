package com.wrapper.inject

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

abstract class WrapperClassVisitorFactory :
    AsmClassVisitorFactory<WrapperClassVisitorFactory.Params> {

    interface Params : InstrumentationParameters {
        @get:Input
        val appPackage: Property<String>

        @get:Input
        val excludedPackage: Property<String>
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val appPrefix = parameters.get().appPackage.get().replace('.', '/') + "/"
        val excludedPrefix = parameters.get().excludedPackage.get().replace('.', '/') + "/"
        val className = classData.className
        return className.startsWith(appPrefix) && !className.startsWith(excludedPrefix)
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return WrapperClassVisitor(
            api = instrumentationContext.apiVersion.get(),
            nextClassVisitor = nextClassVisitor
        )
    }
}
