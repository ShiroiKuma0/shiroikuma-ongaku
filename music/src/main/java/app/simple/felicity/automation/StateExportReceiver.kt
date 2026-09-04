package app.simple.felicity.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.simple.felicity.manager.SharedPreferences
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.shiroikuma.SkBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Fork (保存復元): the state-export automation contract, driven by 白い熊 自由作業盤's
 * 「保存復元」 project — one run backs up every sister app, each app exporting itself
 * headlessly and replying with the written path and size.
 *
 * Three exported, token-gated actions:
 * - [ACTION_EXPORT_STATE] runs the app's normal category-zip export ([SkBackup.export] —
 *   the very same core the Export/Import panel calls, never a second implementation) with
 *   no Activity and no user interaction, then replies with path|bytes|size|count.
 * - [ACTION_LIST_CATEGORIES] answers instantly with the selectable categories, so the
 *   caller can render a checkbox picker without knowing anything about this app.
 * - [ACTION_CANCEL_EXPORT] stops a running export at the next entry boundary, deletes the
 *   partial zip and lets that export answer `ERROR:cancelled` for itself. It is
 *   fire-and-forget: it never replies, and it is a silent no-op at any other time.
 *
 * **The reply is a fresh broadcast and nothing else.** EMUI will not reliably carry a live
 * Binder into another app's manifest receiver, so no `ResultReceiver`/`PendingIntent`/
 * `Messenger` may appear in the reply, and the ordered-broadcast result channel is severed
 * between third-party apps — [setResultData] is still set when the broadcast is ordered
 * (correct AOSP behaviour, and it makes `adb shell am broadcast` print the answer) but it
 * is never the only reply. [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] matters: without it a
 * backgrounded caller never hears us. Verified on 白い熊's Mate XT, 2026-07-23.
 *
 * Exactly one terminal reply is ever sent per request, guarded by an [AtomicBoolean] so an
 * async success and a synchronous error can never both fire.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val app = context.applicationContext
        // A broadcast can spawn the process; the preference singletons must exist before
        // the token gate reads them.
        SharedPreferences.init(app)

        val ordered = isOrderedBroadcast
        when (intent.action) {
            ACTION_LIST_CATEGORIES -> {
                val result = listCategories(app, intent)
                if (ordered) setResultData(result)
                reply(app, intent, result)
            }
            ACTION_EXPORT_STATE -> exportState(app, intent, goAsync(), ordered)
            ACTION_CANCEL_EXPORT -> cancelExport(intent)
            else -> Log.w(TAG, "ignoring unknown action ${intent.action}")
        }
    }

    // ------------------------------------------------------------------ LIST_CATEGORIES

    /**
     * `OK:` followed by one `id<TAB>label<TAB>parent<TAB>on|off` line per category. The ids
     * are exactly the ones accepted in the `items` extra and are the same stable ids used as
     * the zip entry names. The list is flat — no category of ours has separately selectable
     * parts — so the third (parent-id) field is always empty, but it is still sent, because
     * the fourth field is positional: [SkBackup.Cat.defaultOn] is this app *stating* whether
     * an item starts ticked instead of leaving the caller's picker to assume it.
     */
    private fun listCategories(app: Context, intent: Intent): String {
        authorize(intent)?.let { return it }
        return "OK:" + SkBackup.Cat.entries.joinToString("\n") { cat ->
            val default = if (cat.defaultOn) "on" else "off"
            "${cat.id}\t${app.getString(cat.labelRes)}\t\t$default"
        }
    }

    // ------------------------------------------------------------------ EXPORT_STATE

    private fun exportState(app: Context, intent: Intent, pending: PendingResult, ordered: Boolean) {
        val finished = AtomicBoolean(false)
        val run = Run(intent.getStringExtra(KEY_REPLY_ID).orEmpty())

        /** The single terminal path: reply once, release the broadcast once. */
        fun terminate(result: String) {
            if (!finished.compareAndSet(false, true)) return
            // Past the point of cancelling: a CANCEL_EXPORT from here on is the silent no-op.
            running.compareAndSet(run, null)
            try {
                if (ordered) pending.setResultData(result)
                reply(app, intent, result)
            } finally {
                try {
                    pending.finish()
                } catch (e: Exception) {
                    Log.w(TAG, "finish() failed: ${e.message}")
                }
            }
        }

        authorize(intent)?.let {
            terminate(it)
            return
        }

        // --- categories -------------------------------------------------------------
        val itemsRaw = intent.getStringExtra(KEY_ITEMS)?.trim()
        val ids = itemsRaw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val known = SkBackup.Cat.entries.associateBy { it.id }
        if (ids.any { it !in known }) {
            // The contract echoes the items value verbatim so the caller sees what it sent.
            terminate("ERROR:unknown category in items: $itemsRaw")
            return
        }
        // Absent/empty = our default set — exactly the categories we answer LIST_CATEGORIES
        // with as `on`; otherwise keep the enum's own (dialog) order.
        val cats = if (ids.isEmpty()) SkBackup.Cat.entries.filter { it.defaultOn }
        else SkBackup.Cat.entries.filter { it.id in ids.toSet() }

        // --- destination ------------------------------------------------------------
        // Precedence: `path` extra → the app's configured export directory → no-directory.
        // Writing to an arbitrary absolute path needs All-Files-Access; without it we may
        // only ignore `path` when a SAF directory is configured (contract §1).
        val pathExtra = intent.getStringExtra(KEY_PATH)?.trim()
        val safDir = SkBackup.getExportDir(app)
        val directDir = if (!pathExtra.isNullOrEmpty() && hasAllFilesAccess()) File(pathExtra) else null
        if (directDir == null && !pathExtra.isNullOrEmpty() && safDir == null) {
            terminate("ERROR:no-storage-access")
            return
        }
        if (directDir == null && safDir == null) {
            terminate("ERROR:no-directory")
            return
        }
        if (directDir == null && !pathExtra.isNullOrEmpty()) {
            Log.w(TAG, "no All-Files-Access — ignoring path '$pathExtra', writing to the configured directory")
        }

        val name = SkBackup.exportFileName()
        val progress = throttledProgress(app, intent)

        // Cancellable from now on. Two exports at once are forbidden by the contract, so one
        // slot is enough — and a CANCEL_EXPORT carrying no reply_id is unambiguous.
        running.getAndSet(run)?.let { Log.w(TAG, "an export for reply[${it.replyId}] was still registered") }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val written = when {
                    directDir != null -> writeToFile(app, directDir, name, cats, progress, run)
                    safDir != null -> writeToSaf(app, safDir, name, cats, progress, run)
                    else -> throw IllegalStateException("no-directory") // guarded above
                }
                sendProgress(app, intent, cats.size.toLong(), cats.size.toLong(), SkBackup.UNIT_CATEGORY,
                             "${SkBackup.UNIT_CATEGORY} ${cats.size}/${cats.size} — 完了")
                terminate("OK:${written.path}|${written.bytes}|${humanSize(written.bytes)}|${cats.size} categories")
            } catch (e: SkBackup.ExportCancelledException) {
                // The partial zip is already gone (deleted by the writer's own catch), so the
                // directory is exactly as the run found it. Still answer: it is what proves
                // the run ended rather than carrying on unseen.
                Log.i(TAG, "headless export cancelled")
                terminate("ERROR:cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "headless export failed", e)
                terminate("ERROR:${(e.message ?: e.javaClass.simpleName).lines().first().take(160)}")
            }
        }
    }

    // ------------------------------------------------------------------ CANCEL_EXPORT

    /**
     * Fire-and-forget: this action never replies — not even `OK:` — because the export it
     * stops answers for itself with `ERROR:cancelled` through the original request's reply
     * channel, guarded by that request's own [AtomicBoolean]. Arriving when nothing is
     * running, or after the export already finished, it is a **silent no-op**; that is what
     * makes it safe to send at any time, including a moment too late.
     *
     * It is routed through this exported receiver rather than a service on purpose: the
     * export runs on a `goAsync()` [PendingResult] here, but even where it did not, a
     * third-party app cannot reach a correctly non-exported service (保存復元 contract).
     * There is no foreground service and no wakelock to release on this path.
     */
    private fun cancelExport(intent: Intent) {
        if (authorize(intent) != null) return // same gate as the others, but silent — no reply
        val run = running.get() ?: return // nothing in flight
        val wanted = intent.getStringExtra(KEY_REPLY_ID)?.trim()
        if (!wanted.isNullOrEmpty() && wanted != run.replyId) {
            Log.i(TAG, "cancel for reply[$wanted] does not match the running export reply[${run.replyId}] — ignored")
            return
        }
        Log.i(TAG, "cancel requested for reply[${run.replyId}]")
        run.cancel()
    }

    /**
     * One running headless export, and its stop switch. The flag is polled by
     * [SkBackup.export] between entries — never a thread interrupt, so a `write()` already in
     * flight finishes and the zip unwinds at a clean boundary.
     */
    private class Run(val replyId: String) : SkBackup.Cancellation {

        @Volatile
        private var cancelled = false

        override fun isCancelled() = cancelled

        fun cancel() {
            cancelled = true
        }
    }

    /** Where the zip landed and how big it really is — the caller cannot stat the file. */
    private class Written(val path: String, val bytes: Long)

    /** Plain-file write, used only while the app actually holds All-Files-Access. */
    private suspend fun writeToFile(app: Context, dir: File, name: String, cats: List<SkBackup.Cat>,
                                    progress: SkBackup.Progress, cancelled: SkBackup.Cancellation): Written {
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create directory ${dir.absolutePath}")
        if (!dir.isDirectory) throw IllegalStateException("not a directory: ${dir.absolutePath}")
        val file = File(dir, name)
        try {
            val counter = CountingOutputStream(FileOutputStream(file))
            counter.use { SkBackup.export(app, cats, it, progress, cancelled) }
            return Written(file.absolutePath, file.length().takeIf { it > 0L } ?: counter.count)
        } catch (e: Exception) {
            // Every failure, cancellation included, leaves the directory as it found it.
            file.delete() // never leave a truncated export behind
            throw e
        }
    }

    /** SAF write into the directory configured on the Export/Import panel. */
    private suspend fun writeToSaf(app: Context, dir: DocumentFile, name: String, cats: List<SkBackup.Cat>,
                                   progress: SkBackup.Progress, cancelled: SkBackup.Cancellation): Written {
        val created = dir.createFile("application/zip", name)
            ?: throw IllegalStateException("could not create $name")
        try {
            val counter = CountingOutputStream(app.contentResolver.openOutputStream(created.uri)
                                                       ?: throw IllegalStateException("no output stream"))
            counter.use { SkBackup.export(app, cats, it, progress, cancelled) }
            return Written(humanPath(created.uri), created.length().takeIf { it > 0L } ?: counter.count)
        } catch (e: Exception) {
            try {
                created.delete()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ gate

    /**
     * Null when the request may proceed, otherwise the `ERROR:` line to reply with.
     *
     * The whole gate lives in [AutomationPreferences.refuse] — one function for every entry
     * point, so "disabled" and "bad token" cannot drift apart between the receiver, the data
     * door and the AUTOMATION activity. Since v2 the switch ships ON and the token is opt-in:
     * **a `token` extra sent to this app while it is not asking for one is ignored, never
     * refused** (保存復元 contract §2).
     */
    private fun authorize(intent: Intent): String? =
        AutomationPreferences.refuse(intent.getStringExtra(KEY_TOKEN))

    // ------------------------------------------------------------------ reply / progress

    /**
     * The reply: a fresh broadcast, package-targeted, with the correlation id echoed back
     * verbatim (never interpreted). Also logged, so the acceptance checks can be watched
     * in logcat without a second app.
     */
    private fun reply(app: Context, intent: Intent, result: String) {
        val replyId = intent.getStringExtra(KEY_REPLY_ID).orEmpty()
        Log.i(TAG, "reply[$replyId] $result")
        val action = intent.getStringExtra(KEY_REPLY_ACTION)
        val pkg = intent.getStringExtra(KEY_REPLY_PACKAGE)
        if (action.isNullOrEmpty() || pkg.isNullOrEmpty()) {
            Log.w(TAG, "no reply_action/reply_package — nowhere to reply to")
            return
        }
        app.sendBroadcast(Intent(action).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(KEY_REPLY_ID, replyId)
            putExtra("result", result)
        })
    }

    /**
     * The throttled progress sink, from the sender [AutomationDataService] also uses — one
     * implementation of §3 for both doors, parameterised on the correlation id (here the
     * request's `reply_id`), because two copies of the same watchdog drift.
     */
    private fun throttledProgress(app: Context, intent: Intent): SkBackup.Progress =
        AutomationProgress.sink(app, intent.getStringExtra(KEY_PROGRESS_ACTION),
                                intent.getStringExtra(KEY_REPLY_PACKAGE),
                                intent.getStringExtra(KEY_REPLY_ID).orEmpty())

    /** The unthrottled one, used for the mandatory final message at completion. */
    private fun sendProgress(app: Context, intent: Intent, current: Long, total: Long, unit: String, text: String) =
        AutomationProgress.send(app, intent.getStringExtra(KEY_PROGRESS_ACTION),
                                intent.getStringExtra(KEY_REPLY_PACKAGE),
                                intent.getStringExtra(KEY_REPLY_ID).orEmpty(),
                                current, total, unit, text)

    // ------------------------------------------------------------------ helpers

    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    /** Turns a SAF document uri into the filesystem path 白い熊 actually recognizes. */
    private fun humanPath(uri: Uri): String {
        return runCatching {
            val documentId = DocumentsContract.getDocumentId(uri)
            if (documentId.startsWith("primary:")) {
                "/storage/emulated/0/" + documentId.removePrefix("primary:")
            } else {
                documentId
            }
        }.getOrElse { Uri.decode(uri.toString()) }
    }

    /** Display size only — `<bytes>` stays the authoritative number in the reply. */
    private fun humanSize(bytes: Long): String {
        val k = 1024.0
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / k)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (k * k))
            else -> String.format(Locale.ROOT, "%.2f GB", bytes / (k * k * k))
        }
    }

    /** Counts what actually went through, so the reply has a byte figure even if the file cannot be stat'ed. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {

        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }

    companion object {
        private const val TAG = "StateExportReceiver"

        /**
         * Public contract (保存復元) — `<pkg>.action.*`, where `<pkg>` is our applicationId
         * `shiroikuma.ongaku`, so it can never collide with upstream Felicity installed
         * side-by-side. The code namespace stays `app.simple.felicity.*`. Keep these two
         * strings identical to the manifest's intent-filter.
         */
        const val ACTION_EXPORT_STATE = "shiroikuma.ongaku.action.EXPORT_STATE"
        const val ACTION_LIST_CATEGORIES = "shiroikuma.ongaku.action.LIST_CATEGORIES"
        const val ACTION_CANCEL_EXPORT = "shiroikuma.ongaku.action.CANCEL_EXPORT"

        /**
         * The headless export in flight, or null — process-wide, because a receiver instance
         * lives only for its own broadcast and the cancel arrives in a different one.
         */
        private val running = AtomicReference<Run?>(null)

        private const val KEY_TOKEN = "token"
        private const val KEY_PATH = "path"
        private const val KEY_ITEMS = "items"
        private const val KEY_PROGRESS_ACTION = "progress_action"
        private const val KEY_REPLY_ACTION = "reply_action"
        private const val KEY_REPLY_PACKAGE = "reply_package"
        private const val KEY_REPLY_ID = "reply_id"
    }
}
