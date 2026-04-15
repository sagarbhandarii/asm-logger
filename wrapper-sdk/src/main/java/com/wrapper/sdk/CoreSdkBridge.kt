package com.wrapper.sdk

import android.content.Context

internal object CoreSdkBridge {
    fun initialize(context: Context) {
        runCatching {
            val coreSdkClass = Class.forName("com.your.security.CoreSdk")
            val initializeMethod = coreSdkClass.methods.firstOrNull { method ->
                method.name == "initialize" && method.parameterTypes.size == 1
            }
            initializeMethod?.invoke(null, context)
        }
    }
}
