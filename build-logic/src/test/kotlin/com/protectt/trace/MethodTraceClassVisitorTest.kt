package com.protectt.trace

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertTrue

class MethodTraceClassVisitorTest {
    @Test
    fun `method visitor still injects runtime enter and exit calls`() {
        val original = buildSampleClass()
        val transformed = transformClass(original)

        val reader = ClassReader(transformed)
        val owners = mutableListOf<String>()
        val names = mutableListOf<String>()

        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "target") return next

                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        owners += owner
                        names += name
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }, 0)

        assertTrue(owners.contains("com/example/trace/MethodTraceRuntime"))
        assertTrue(names.contains("enter"))
        assertTrue(names.contains("exit"))
    }

    private fun transformClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        val visitor = MethodTraceClassVisitor(
            api = Opcodes.ASM9,
            next = writer,
            runtimeClassName = "com/example/trace/MethodTraceRuntime",
        )
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    private fun buildSampleClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/Sample",
            null,
            "java/lang/Object",
            null,
        )

        val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        val target = writer.visitMethod(Opcodes.ACC_PUBLIC, "target", "()V", null, null)
        target.visitCode()
        target.visitInsn(Opcodes.RETURN)
        target.visitMaxs(0, 1)
        target.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }
}
