package com.wrapper.sdk

import android.content.Context
import android.util.Log

internal object Watchdog {
    private const val TAG = "WrapperWatchdog"

    external fun nativeHeartbeat(): String

    fun start(context: Context) {
        runCatching {
            System.loadLibrary("wrappersdk")
        }.onFailure {
            Log.w(TAG, "Native watchdog unavailable: ${it.message}")
        }

        runCatching {
            val result = nativeHeartbeat()
            Log.d(TAG, "Watchdog heartbeat: $result (${context.packageName})")
        }
    }
}
