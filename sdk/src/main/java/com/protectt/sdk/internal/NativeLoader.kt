package com.protectt.sdk.internal

import android.content.Context
import android.os.SystemClock

object NativeLoader {
    fun load(context: Context) {
        fakeWork(220)
        context.packageName.length
    }

    private fun fakeWork(ms: Long) {
        SystemClock.sleep(ms)
    }
}
