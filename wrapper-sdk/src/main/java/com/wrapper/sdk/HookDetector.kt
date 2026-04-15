package com.wrapper.sdk

internal object HookDetector {
    fun check(): Boolean {
        return runCatching {
            val clazz = Class.forName("com.your.security.CoreSdk")
            val method = clazz.methods.firstOrNull { it.name == "isHookDetected" }
            method?.invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
