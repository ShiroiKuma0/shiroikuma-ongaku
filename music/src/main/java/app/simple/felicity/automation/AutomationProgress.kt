package app.simple.felicity.automation

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.simple.felicity.R
import app.simple.felicity.shiroikuma.SkBackup
import java.util.concurrent.atomic.AtomicLong

/**
 * The one progress sender **both** automation doors use (保存復元 contract §3).
 *
 * There is deliberately a single implementation: the §1 receiver and the §2a data door report the
 * same thing to the same reader, and two copies of a watchdog drift — the one that drifts always
 * being the one nobody is looking at. What differs between the doors is only the correlation id,
 * so that is the parameter.
 *
 * 白い熊's explicit requirement: **real numbers, never a percentage.** [text] is the ready-made
 * numbers-first line the notification shows; [current]/[total]/[unit] are what a caller computes
 * with. A caller that passes no `progress_action` gets nothing, so all of this is purely additive.
 */
object AutomationProgress {

    /** At most one progress broadcast every half second (contract §3). */
    private const val INTERVAL_MS = 500L

    /**
     * A [SkBackup.Progress] sink that drops everything arriving less than [INTERVAL_MS] after the
     * last one it let through. Completion is sent separately, through [send], and unthrottled.
     */
    fun sink(context: Context, progressAction: String?, replyPackage: String?,
             correlationId: String): SkBackup.Progress {
        if (progressAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) {
            return SkBackup.Progress { _, _, _, _ -> }
        }
        val last = AtomicLong(0L)
        return SkBackup.Progress { current, total, unit, text ->
            val now = SystemClock.elapsedRealtime()
            val previous = last.get()
            if (now - previous >= INTERVAL_MS && last.compareAndSet(previous, now)) {
                send(context, progressAction, replyPackage, correlationId, current, total, unit, text)
            }
        }
    }

    /**
     * One progress broadcast, unthrottled — also the heartbeat: 自由作業盤 treats every one as
     * proof we are still alive and fails an app that goes quiet.
     */
    fun send(context: Context, progressAction: String?, replyPackage: String?, correlationId: String,
             current: Long, total: Long, unit: String, text: String) {
        if (progressAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
        context.sendBroadcast(Intent(progressAction).apply {
            setPackage(replyPackage)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // Both ids carry the same value on purpose, so one progress reader serves the §1
            // receiver (which correlates on reply_id) and the §2a data door (on job_id).
            putExtra("reply_id", correlationId)
            putExtra("job_id", correlationId)
            putExtra("app", context.getString(R.string.app_name))
            putExtra("text", text)
            putExtra("current", current)
            putExtra("total", total)
            putExtra("unit", unit)
        })
    }
}
