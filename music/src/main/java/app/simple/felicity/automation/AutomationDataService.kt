package app.simple.felicity.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import app.simple.felicity.manager.SharedPreferences
import app.simple.felicity.shiroikuma.SkBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Where a data export or import actually runs (保存復元 contract §2a).
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored (応用管理, 2026-09-04).
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val importing = intent.getBooleanExtra(EXTRA_IMPORTING, false)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (!replied.compareAndSet(false, true)) return
            AutomationJobs.finish(jobId)
            Log.i(TAG, "job[$jobId] $result")
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(Intent(replyAction).apply {
                setPackage(replyPackage)
                // Without this a caller that has been backgrounded never hears the answer, and on
                // a clean phone the caller may not have been launched at all.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                putExtra(AutomationProvider.KEY_RESULT, result)
            })
        }

        // The descriptor leaves the handover map and we go foreground under ONE guard. A
        // foreground start reached through a binder call is a BACKGROUND start, which the
        // platform may refuse outright — and if it does, the caller's descriptor must still be
        // closed and the job dropped here. A leaked one holds the caller's file open, and a
        // caller cannot checksum or encrypt a file that is still open.
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        try {
            // The service can be the thing that spawned this process; SkBackup reads preferences.
            SharedPreferences.init(applicationContext)
            startForeground(NOTIFICATION_ID, notification(importing))
        } catch (t: Throwable) {
            Log.e(TAG, "job[$jobId] could not go foreground", t)
            runCatching { fd.close() }
            reply("ERROR:cannot go foreground: ${t.message ?: t.javaClass.simpleName}")
            return stop(startId)
        }

        scope.launch {
            try {
                fd.use { open ->
                    if (importing) {
                        runImport(open, ::reply)
                    } else {
                        runExport(jobId, open, intent.getStringExtra(AutomationProvider.KEY_ITEMS),
                                  progressAction, replyPackage, ::reply)
                    }
                }
            } catch (e: SkBackup.ExportCancelledException) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                Log.e(TAG, "job[$jobId] failed", t)
                reply("ERROR:${(t.message ?: t.javaClass.simpleName).lines().first().take(160)}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Straight into the caller's descriptor: the same [SkBackup.export] core the Export/Import
     * panel and [StateExportReceiver] call, never a second implementation.
     */
    private suspend fun runExport(jobId: String, fd: ParcelFileDescriptor, items: String?,
                                  progressAction: String?, replyPackage: String?,
                                  reply: (String) -> Unit) {
        val cats = resolve(items) ?: run {
            reply("ERROR:unknown category in items: $items")
            return
        }
        var written = 0L
        val progress = AutomationProgress.sink(this, progressAction, replyPackage, jobId)
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we
            // may not be able to see it at all — it can be an anonymous pipe or a descriptor into
            // a directory this app cannot list.
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                }
            }
            SkBackup.export(this, cats, counting, progress) { AutomationJobs.isCancelled(jobId) }
        }
        if (AutomationJobs.isCancelled(jobId)) {
            reply("ERROR:cancelled")
        } else {
            // The mandatory final message (§3), unthrottled.
            AutomationProgress.send(this, progressAction, replyPackage, jobId,
                                    cats.size.toLong(), cats.size.toLong(), SkBackup.UNIT_CATEGORY,
                                    "${SkBackup.UNIT_CATEGORY} ${cats.size}/${cats.size} — 完了")
            reply("OK:$written|${cats.size} categories")
        }
    }

    /**
     * Read the whole archive before touching anything.
     *
     * [SkBackup.import] reads a [ZipFile], which needs a seekable file — and the descriptor we are
     * handed may be an anonymous pipe. So it is spooled into the cache directory first, which is
     * the right shape here for a reason beyond convenience: a partial read that failed halfway
     * would otherwise import half an archive, and a half-restored app is worse than one that
     * refused. The spool is deleted in a `finally`, on every path.
     */
    private suspend fun runImport(fd: ParcelFileDescriptor, reply: (String) -> Unit) {
        val spool = File(cacheDir, "automation-import-${System.currentTimeMillis()}.zip")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { input.copyTo(it) }
            }
            if (spool.length() == 0L) {
                reply("ERROR:empty archive")
                return
            }
            ZipFile(spool).use { zip ->
                // Every category the archive actually carries, not every category we know about:
                // asking for one the archive lacks is how a restore ends up reporting success over
                // nothing.
                val present = SkBackup.categoriesIn(zip)
                if (present.isEmpty()) {
                    reply("ERROR:archive carries no categories")
                    return
                }
                val summary = SkBackup.import(this, zip, present)
                if (summary.isNullOrEmpty()) {
                    reply("ERROR:archive carries no categories")
                    return
                }
                // The caller force-stops us straight after this. That is deliberate and belongs on
                // its side: a running process writes its cached SharedPreferences back out at
                // orderly shutdown and silently undoes the import that just happened (応用管理 paid
                // for this one already).
                reply("OK:${summary.lines().size} restored")
            }
        } finally {
            spool.delete()
        }
    }

    /** Absent/empty items = our default set; an id we do not know is an error, never a silent skip. */
    private fun resolve(items: String?): List<SkBackup.Cat>? {
        if (items.isNullOrBlank()) return SkBackup.Cat.entries.filter { it.defaultOn }
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val known = SkBackup.Cat.entries.associateBy { it.id }
        if (wanted.any { it !in known }) return null
        return SkBackup.Cat.entries.filter { it.id in wanted.toSet() }
    }

    private fun notification(importing: Boolean): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutomationDataService"
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A [ParcelFileDescriptor] in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — the service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        fun start(context: Context, jobId: String, fd: ParcelFileDescriptor, importing: Boolean,
                  extras: Bundle?) {
            HANDOVER[jobId] = fd
            context.startForegroundService(Intent(context, AutomationDataService::class.java).apply {
                putExtra(EXTRA_JOB, jobId)
                putExtra(EXTRA_IMPORTING, importing)
                putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                putExtra(AutomationProvider.KEY_REPLY_ACTION,
                         extras?.getString(AutomationProvider.KEY_REPLY_ACTION))
                putExtra(AutomationProvider.KEY_REPLY_PACKAGE,
                         extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE))
                putExtra(AutomationProvider.KEY_PROGRESS_ACTION,
                         extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION))
            })
        }

        /**
         * Drop a descriptor whose service never started, so a refused foreground-service start
         * cannot leave one parked in [HANDOVER] for the life of the process. The provider closes
         * the descriptor itself on that path.
         */
        fun abandon(jobId: String) {
            HANDOVER.remove(jobId)
        }
    }
}
