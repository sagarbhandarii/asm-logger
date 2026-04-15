package com.wrapper.sdk

import android.content.Context

object SecureWrapper {
    @Volatile
    private var initialized: Boolean = false

    @JvmStatic
    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            WrapperEngine.initialize(context.applicationContext)
            initialized = true
        }
    }
}
