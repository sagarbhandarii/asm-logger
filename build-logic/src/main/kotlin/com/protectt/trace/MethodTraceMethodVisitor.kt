package com.protectt.trace

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method

class MethodTraceMethodVisitor(
    api: Int,
    mv: MethodVisitor,
    access: Int,
    name: String,
    descriptor: String,
    private val methodId: String,
) : AdviceAdapter(api, mv, access, name, descriptor) {

    private var startVarIndex: Int = -1

    override fun onMethodEnter() {
        visitLdcInsn(methodId)
        invokeStatic(
            Type.getObjectType("ai/protectt/app/security/trace/MethodTraceRuntime"),
            Method("enter", "(Ljava/lang/String;)J"),
        )
        startVarIndex = newLocal(Type.LONG_TYPE)
        storeLocal(startVarIndex, Type.LONG_TYPE)
    }

    override fun onMethodExit(opcode: Int) {
        if (opcode == Opcodes.ATHROW || opcode in Opcodes.IRETURN..Opcodes.RETURN) {
            visitLdcInsn(methodId)
            loadLocal(startVarIndex, Type.LONG_TYPE)
            invokeStatic(
                Type.getObjectType("ai/protectt/app/security/trace/MethodTraceRuntime"),
                Method("exit", "(Ljava/lang/String;J)V"),
            )
        }
    }
}
