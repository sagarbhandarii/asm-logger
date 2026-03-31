package com.protectt.sdk

import android.content.Context
import com.protectt.sdk.internal.FileScanner
import com.protectt.sdk.internal.FridaDetector
import com.protectt.sdk.internal.KeyStoreWarmup
import com.protectt.sdk.internal.NativeLoader
import com.protectt.sdk.internal.RootChecker

object ProtecttSdk {
    fun init(context: Context) {
        NativeLoader.load(context)
        RootChecker.check(context)
        FridaDetector.scanPorts()
        FileScanner.scan(context)
        KeyStoreWarmup.prepare(context)
    }
}
