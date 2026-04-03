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
    private val runtimeClassName: String,
) : AdviceAdapter(api, mv, access, name, descriptor) {

    private var startVarIndex: Int = -1
    private var sampledVarIndex: Int = -1

    private val runtimeType = Type.getObjectType(runtimeClassName)
    private val samplingConfigClassName = "${runtimeClassName.substringBeforeLast('/')}/SamplingConfig"
    private val samplingConfigType = Type.getObjectType(samplingConfigClassName)
    private val threadLocalRandomType = Type.getObjectType("java/util/concurrent/ThreadLocalRandom")

    override fun onMethodEnter() {
        visitLdcInsn(methodId)
        invokeStatic(
            runtimeType,
            Method("enter", "(Ljava/lang/String;)J"),
        )
        startVarIndex = newLocal(Type.LONG_TYPE)
        storeLocal(startVarIndex, Type.LONG_TYPE)

        sampledVarIndex = newLocal(Type.BOOLEAN_TYPE)

        getStatic(samplingConfigType, "sampleRatePercent", Type.INT_TYPE)
        val notSampledLabel = newLabel()
        val sampledLabel = newLabel()
        ifZCmp(LE, notSampledLabel)

        getStatic(samplingConfigType, "sampleRatePercent", Type.INT_TYPE)
        push(100)
        ifICmp(GE, sampledLabel)

        invokeStatic(threadLocalRandomType, Method("current", "()Ljava/util/concurrent/ThreadLocalRandom;"))
        push(100)
        invokeVirtual(threadLocalRandomType, Method("nextInt", "(I)I"))
        getStatic(samplingConfigType, "sampleRatePercent", Type.INT_TYPE)
        ifICmp(LT, sampledLabel)

        mark(notSampledLabel)
        push(false)
        storeLocal(sampledVarIndex, Type.BOOLEAN_TYPE)
        val entryDoneLabel = newLabel()
        goTo(entryDoneLabel)

        mark(sampledLabel)
        push(true)
        storeLocal(sampledVarIndex, Type.BOOLEAN_TYPE)

        mark(entryDoneLabel)
    }

    override fun onMethodExit(opcode: Int) {
        if (opcode == Opcodes.ATHROW || opcode in Opcodes.IRETURN..Opcodes.RETURN) {
            val skipLogLabel = newLabel()
            val doLogLabel = newLabel()

            loadLocal(sampledVarIndex, Type.BOOLEAN_TYPE)
            ifZCmp(NE, doLogLabel)

            invokeStatic(Type.getObjectType("android/os/SystemClock"), Method("elapsedRealtimeNanos", "()J"))
            loadLocal(startVarIndex, Type.LONG_TYPE)
            math(SUB, Type.LONG_TYPE)
            push(1_000_000L)
            math(DIV, Type.LONG_TYPE)
            getStatic(samplingConfigType, "slowCallThresholdMs", Type.LONG_TYPE)
            val thresholdMissedLabel = newLabel()
            ifCmp(Type.LONG_TYPE, LE, thresholdMissedLabel)
            goTo(doLogLabel)

            mark(thresholdMissedLabel)
            goTo(skipLogLabel)

            mark(doLogLabel)
            visitLdcInsn(methodId)
            loadLocal(startVarIndex, Type.LONG_TYPE)
            invokeStatic(
                runtimeType,
                Method("exit", "(Ljava/lang/String;J)V"),
            )
            mark(skipLogLabel)
        }
    }
}
