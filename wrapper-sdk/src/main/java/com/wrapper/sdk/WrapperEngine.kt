package com.wrapper.sdk

import android.content.Context

internal object WrapperEngine {
    fun initialize(context: Context) {
        CoreSdkBridge.initialize(context)
        HookDetector.check()
        TamperDetector.check()
        Watchdog.start(context)
    }
}
