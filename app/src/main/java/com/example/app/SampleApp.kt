package com.example.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.protectt.sdk.ProtecttSdk
import com.protectt.sdk.trace.MethodTraceRuntime

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MethodTraceRuntime.enabled = true
        MethodTraceRuntime.startupTracingOnly = true
        MethodTraceRuntime.startupWindowMs = 20_000L
        MethodTraceRuntime.logEachCall = true
        MethodTraceRuntime.captureThreadName = true

        ProtecttSdk.init(this)

        Handler(Looper.getMainLooper()).postDelayed({
            MethodTraceRuntime.dumpTop(20)
        }, 3_000L)
    }
}
