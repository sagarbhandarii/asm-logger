package com.wrapper.inject

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class WrapperClassVisitor(
    api: Int,
    nextClassVisitor: ClassVisitor
) : ClassVisitor(api, nextClassVisitor) {

    private var className: String = ""
    private var superName: String = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.className = name
        this.superName = superName.orEmpty()
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return WrapperMethodVisitor(
            api = api,
            methodVisitor = delegate,
            access = access,
            methodName = name,
            methodDescriptor = descriptor,
            ownerClassName = className,
            superName = superName
        )
    }
}
