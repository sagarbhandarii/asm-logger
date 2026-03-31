package com.protectt.sdk.internal

import android.content.Context
import android.os.SystemClock

object FileScanner {
    fun scan(context: Context): Int {
        val dirs = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir)
        var count = 0
        dirs.forEach { dir ->
            count += scanDir(dir.name)
        }
        return count
    }

    private fun scanDir(name: String): Int {
        SystemClock.sleep(130)
        return name.length
    }
}
