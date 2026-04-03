package com.example.app

import android.app.Application
import com.protectt.sdk.ProtecttSdk
import com.protectt.sdk.trace.MethodTraceRuntime

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MethodTraceRuntime.enabled = true
        MethodTraceRuntime.startupTracingOnly = true
        MethodTraceRuntime.startupWindowMs = 20_000L
        MethodTraceRuntime.installLifecycleFlush(this, intervalSeconds = 5L)

        ProtecttSdk.init(this)
    }
}
