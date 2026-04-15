package com.wrapper.inject

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

class WrapperMethodVisitor(
    api: Int,
    methodVisitor: MethodVisitor,
    access: Int,
    private val methodName: String,
    private val methodDescriptor: String,
    private val ownerClassName: String,
    private val superName: String
) : AdviceAdapter(api, methodVisitor, access, methodName, methodDescriptor) {

    override fun onMethodEnter() {
        if (!shouldInject()) return

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/wrapper/sdk/SecureWrapper",
            "ensureInit",
            "(Landroid/content/Context;)V",
            false
        )
    }

    private fun shouldInject(): Boolean {
        val isApplicationOnCreate =
            methodName == "onCreate" && methodDescriptor == "()V" &&
                (superName == "android/app/Application" || ownerClassName.endsWith("Application"))

        val isActivityOnCreate =
            methodName == "onCreate" && methodDescriptor == "(Landroid/os/Bundle;)V" &&
                (superName == "android/app/Activity" ||
                    superName == "androidx/activity/ComponentActivity" ||
                    superName == "androidx/appcompat/app/AppCompatActivity" ||
                    ownerClassName.endsWith("Activity"))

        return isApplicationOnCreate || isActivityOnCreate
    }
}
