package com.protectt.sdk.internal

import android.content.Context
import android.os.SystemClock

object KeyStoreWarmup {
    fun prepare(context: Context) {
        openStore()
        generateKeyAlias(context.packageName)
    }

    private fun openStore() {
        SystemClock.sleep(160)
    }

    private fun generateKeyAlias(packageName: String) {
        packageName.length
        SystemClock.sleep(240)
    }
}
