package com.protectt.sdk.internal

import android.os.SystemClock

object FridaDetector {
    fun scanPorts(): Boolean {
        val result = probePort(27042) || probePort(27043)
        SystemClock.sleep(140)
        return result
    }

    private fun probePort(port: Int): Boolean {
        SystemClock.sleep(if (port == 27042) 120 else 90)
        return false
    }
}
