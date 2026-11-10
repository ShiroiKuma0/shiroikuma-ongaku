package app.simple.felicity.shared.shiroikuma

import android.app.Activity
import android.content.Context
import android.os.Debug
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two probes the 2026-11-10 leak hunt actually needed, moved into the app so the next one
 * does not depend on catching the process alive with adb attached.
 *
 * **Screens** — every Activity that is destroyed is held by a weak reference and looked at again
 * a few seconds later, after a forced collection. If it is still reachable it was not really
 * destroyed, and the line says `RETAINED`. This is LeakCanary's core trick without the library,
 * which matters because it has to work in the signed release build.
 *
 * **Memory** — a periodic sample of what `dumpsys meminfo` prints for the process: total PSS and
 * the Java, native and graphics splits. Graphics is the one that gave the leak away — 137 MB of
 * GPU memory held by album art behind screens that should have been gone.
 */
object SkDiagProbes {

    /** Long enough for a teardown to finish and the queue to drain, short enough to be useful. */
    private const val SETTLE_MS = 5_000L
    private const val SAMPLE_SECONDS = 60L

    private class Watch(val label: String, val at: Long, activity: Activity) {
        /** Weak on purpose: holding the Activity here would be the very leak we are looking for. */
        val ref = WeakReference(activity)
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sk-diag-probe").apply { isDaemon = true }
    }

    private val watched = ArrayList<Watch>()
    private val live = AtomicInteger()
    private val controllers = AtomicInteger()

    private var memoryTask: ScheduledFuture<*>? = null

    // ------------------------------------------------------------------ screens

    fun activityCreated(activity: Activity) {
        val count = live.incrementAndGet()
        SkDiagLog.log(SkDiag.Group.SCREENS) {
            "create ${labelOf(activity)} live=$count retained=${retainedCount()}"
        }
    }

    fun activityDestroyed(activity: Activity, changingConfigurations: Boolean) {
        val count = live.decrementAndGet().coerceAtLeast(0)
        if (!SkDiag.isOn(SkDiag.Group.SCREENS)) return

        val label = labelOf(activity)
        SkDiagLog.log(SkDiag.Group.SCREENS) {
            "destroy $label changingConfigurations=$changingConfigurations live=$count retained=${retainedCount()}"
        }

        // The watch holds only a weak reference and a string, so scheduling it cannot be what
        // keeps the screen alive.
        val watch = Watch(label, System.currentTimeMillis(), activity)
        synchronized(watched) { watched.add(watch) }
        runCatching { scheduler.schedule({ settle(watch) }, SETTLE_MS, TimeUnit.MILLISECONDS) }
    }

    private fun settle(watch: Watch) {
        forceCollection()
        val age = System.currentTimeMillis() - watch.at
        if (watch.ref.get() == null) {
            synchronized(watched) { watched.remove(watch) }
            SkDiagLog.log(SkDiag.Group.SCREENS) { "collected ${watch.label} after ${age} ms" }
        } else {
            SkDiagLog.log(SkDiag.Group.SCREENS) {
                "RETAINED ${watch.label} — still reachable ${age} ms after destroy, " +
                        "retained=${retainedCount()} controllers=${controllers.get()}"
            }
        }
    }

    /** Counts the destroyed screens still reachable, dropping the ones that have since gone. */
    fun retainedCount(): Int = synchronized(watched) {
        watched.removeAll { it.ref.get() == null }
        watched.size
    }

    private fun forceCollection() {
        Runtime.getRuntime().gc()
        runCatching { Thread.sleep(100) }
        System.runFinalization()
        Runtime.getRuntime().gc()
    }

    private fun labelOf(activity: Activity): String =
        "${activity.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(activity))}"

    // ------------------------------------------------------------------ media controllers

    /**
     * How many media controllers the process holds. One is correct; the leak that prompted all
     * this showed seven, one per screen ever created.
     */
    fun controllerOpened(): Int = controllers.incrementAndGet()

    fun controllerClosed(): Int = controllers.decrementAndGet().coerceAtLeast(0)

    fun controllerCount(): Int = controllers.get()

    // ------------------------------------------------------------------ memory

    /** Starts or stops the periodic sample as the switches change. */
    @Synchronized
    fun onEnabledChanged(context: Context) {
        val application = context.applicationContext
        val wanted = SkDiag.isOn(SkDiag.Group.MEMORY)
        if (wanted && memoryTask == null) {
            memoryTask = runCatching {
                scheduler.scheduleWithFixedDelay({
                    SkDiagLog.log(SkDiag.Group.MEMORY) { memoryLine(application) }
                }, 0L, SAMPLE_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
        } else if (!wanted) {
            memoryTask?.cancel(false)
            memoryTask = null
        }
    }

    /** The same figures `dumpsys meminfo` prints for this process, read from inside it. */
    fun memoryLine(context: Context): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        fun megabytes(key: String): String {
            val kilobytes = info.getMemoryStat(key)?.toLongOrNull() ?: return "?"
            return "${kilobytes / 1024} MB"
        }

        return "pss=${megabytes("summary.total-pss")} " +
                "java=${megabytes("summary.java-heap")} " +
                "native=${megabytes("summary.native-heap")} " +
                "graphics=${megabytes("summary.graphics")} " +
                "code=${megabytes("summary.code")} " +
                "stack=${megabytes("summary.stack")} " +
                "swap=${megabytes("summary.total-swap")} " +
                "screens=${live.get()} retained=${retainedCount()} controllers=${controllers.get()}"
    }
}
