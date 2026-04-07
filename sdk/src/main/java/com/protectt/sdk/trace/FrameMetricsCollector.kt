package com.protectt.sdk.trace

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentHashMap

internal class FrameMetricsCollector(
    private val onFrameDurationNs: (Long) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerByWindow = ConcurrentHashMap<Window, Window.OnFrameMetricsAvailableListener>()

    fun onActivityResumed(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window ?: return
        if (listenerByWindow.containsKey(window)) return

        val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val durationNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (durationNs > 0L) {
                onFrameDurationNs(durationNs)
            }
        }
        listenerByWindow[window] = listener
        window.addOnFrameMetricsAvailableListener(listener, mainHandler)
    }

    fun onActivityPaused(activity: Activity) {
        val window = activity.window ?: return
        val listener = listenerByWindow.remove(window) ?: return
        window.removeOnFrameMetricsAvailableListener(listener)
    }

    fun stop() {
        listenerByWindow.entries.forEach { (window, listener) ->
            window.removeOnFrameMetricsAvailableListener(listener)
        }
        listenerByWindow.clear()
    }
}
