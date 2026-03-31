package com.protectt.trace

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class MethodTraceClassVisitor(
    api: Int,
    next: ClassVisitor,
    private val runtimeClassName: String,
) : ClassVisitor(api, next) {

    private var className: String = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        className = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

        val shouldSkip =
            name == "<init>" ||
                name == "<clinit>" ||
                (access and Opcodes.ACC_ABSTRACT) != 0 ||
                (access and Opcodes.ACC_NATIVE) != 0 ||
                name.startsWith("access$") ||
                isLikelyTrivialGetterSetter(name, descriptor)

        if (shouldSkip) return mv

        val methodId = "$className#$name$descriptor"
        return MethodTraceMethodVisitor(
            api = api,
            mv = mv,
            access = access,
            name = name,
            descriptor = descriptor,
            methodId = methodId,
            runtimeClassName = runtimeClassName,
        )
    }

    private fun isLikelyTrivialGetterSetter(name: String, descriptor: String): Boolean {
        val getter = (name.startsWith("get") && descriptor.startsWith("()")) ||
            (name.startsWith("is") && descriptor == "()Z")
        val setter = name.startsWith("set") && descriptor.endsWith(")V")
        return getter || setter
    }
}
