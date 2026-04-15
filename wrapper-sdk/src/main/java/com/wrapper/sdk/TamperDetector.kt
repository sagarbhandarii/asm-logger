package com.wrapper.sdk

internal object TamperDetector {
    fun check(): Boolean {
        return runCatching {
            val clazz = Class.forName("com.your.security.CoreSdk")
            val method = clazz.methods.firstOrNull { it.name == "isTampered" }
            method?.invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
