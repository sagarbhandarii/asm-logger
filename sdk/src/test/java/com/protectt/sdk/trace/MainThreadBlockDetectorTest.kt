package com.protectt.sdk.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MainThreadBlockDetectorTest {
    @Test
    fun schedulesHeartbeatAtConfiguredInterval() {
        val scheduler = FakeScheduler()
        val time = FakeTime()
        val detector = MainThreadBlockDetector(
            heartbeatIntervalMs = 40L,
            clockMs = { time.nowMs },
            scheduler = scheduler,
            postHeartbeat = { },
            activeMethodProvider = { null },
            onStall = { },
        )

        detector.start()

        assertEquals(40L, scheduler.initialDelayMs)
        assertEquals(40L, scheduler.periodMs)
    }

    @Test
    fun classifiesThresholdsCorrectly() {
        val thresholds = MainThreadStallThresholds(warningMs = 100L, elevatedMs = 250L, criticalMs = 500L)
        assertEquals(MainThreadStallSeverity.NONE, classify(100L, thresholds))
        assertEquals(MainThreadStallSeverity.WARNING, classify(101L, thresholds))
        assertEquals(MainThreadStallSeverity.ELEVATED, classify(251L, thresholds))
        assertEquals(MainThreadStallSeverity.CRITICAL, classify(501L, thresholds))
    }

    @Test
    fun emitsEventsAndCorrelatesWithActiveMethod() {
        val scheduler = FakeScheduler()
        val time = FakeTime()
        val postedHeartbeats = CopyOnWriteArrayList<Runnable>()
        val events = CopyOnWriteArrayList<MainThreadStallEvent>()
        val detector = MainThreadBlockDetector(
            heartbeatIntervalMs = 50L,
            clockMs = { time.nowMs },
            scheduler = scheduler,
            postHeartbeat = { postedHeartbeats.add(it) },
            activeMethodProvider = { "com/example/Foo#doWork()V" },
            onStall = { events.add(it) },
        )

        detector.start()
        scheduler.tick() // schedule heartbeat
        assertEquals(1, postedHeartbeats.size)

        time.advance(120L)
        scheduler.tick()
        assertEquals(1, events.size)
        assertEquals(MainThreadStallSeverity.WARNING, events[0].severity)
        assertEquals("com/example/Foo#doWork()V", events[0].activeMethodId)
    }

    @Test
    fun pauseResumeSuppressesBackgroundFalsePositives() {
        val scheduler = FakeScheduler()
        val time = FakeTime()
        val postedHeartbeats = CopyOnWriteArrayList<Runnable>()
        val events = CopyOnWriteArrayList<MainThreadStallEvent>()
        val detector = MainThreadBlockDetector(
            heartbeatIntervalMs = 50L,
            clockMs = { time.nowMs },
            scheduler = scheduler,
            postHeartbeat = { postedHeartbeats.add(it) },
            activeMethodProvider = { null },
            onStall = { events.add(it) },
        )

        detector.start()
        scheduler.tick()
        detector.pause()

        time.advance(1_000L)
        scheduler.tick()
        assertTrue(events.isEmpty())

        detector.resume()
        scheduler.tick()
        val heartbeat = postedHeartbeats.last()
        heartbeat.run()
        time.advance(120L)
        scheduler.tick()
        assertEquals(1, events.size)
    }

    private class FakeScheduler : RepeatingScheduler {
        var initialDelayMs: Long = -1L
        var periodMs: Long = -1L
        private var task: (() -> Unit)? = null
        private var canceled = false

        override fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit): RepeatingTask {
            this.initialDelayMs = initialDelayMs
            this.periodMs = periodMs
            this.task = task
            return object : RepeatingTask {
                override fun cancel() {
                    canceled = true
                }
            }
        }

        fun tick() {
            if (canceled) return
            task?.invoke()
        }
    }

    private class FakeTime(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }
}
