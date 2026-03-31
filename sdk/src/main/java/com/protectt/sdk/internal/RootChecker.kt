package com.protectt.sdk.internal

import android.content.Context
import android.os.SystemClock

object RootChecker {
    fun check(context: Context): Boolean {
        val a = checkSuBinary()
        val b = checkMagiskPackages(context)
        val c = checkDangerousProps()
        return a || b || c
    }

    private fun checkSuBinary(): Boolean {
        SystemClock.sleep(180)
        return false
    }

    private fun checkMagiskPackages(context: Context): Boolean {
        val pm = context.packageManager
        val knownPackages = listOf(
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "com.koushikdutta.superuser"
        )
        SystemClock.sleep(350)
        return knownPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun checkDangerousProps(): Boolean {
        SystemClock.sleep(260)
        return false
    }
}
