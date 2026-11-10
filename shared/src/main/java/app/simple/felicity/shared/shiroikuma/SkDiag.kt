package app.simple.felicity.shared.shiroikuma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.simple.felicity.shared.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics for 白い熊 音楽 — every log behind its own switch, all off by default, written into
 * the app's own diag directory as plain text.
 *
 * The 2026-11-10 leak hunt is why this exists: finding it meant reading `dumpsys meminfo` and
 * `dumpsys activity` over adb with the leaking process still alive, which only works if someone
 * is at the PC at the right moment. What those commands showed — screens that outlive their
 * teardown, the memory and GPU behind them, and the controllers holding them — the app can
 * record for itself, so the next one is answered from a file instead of from luck.
 *
 * Modelled on 白い熊 地図's `ChizuDiag`, with its two hard-won file rules:
 *
 *  - **Never `.log`.** EMUI's Phone Manager deletes every `*.log` under `Android/data` as junk in
 *    a nightly clean-up that runs while the phone charges. On 2026-11-04 it took a whole drive's
 *    GPS log with it. Everything here is `.txt`, and any `.log` left by an older build is renamed
 *    in on start.
 *  - **Reopen a file deleted under you.** A writer holding an open stream goes on writing into
 *    the unlinked inode, silently, so [SkDiagLog] checks the file still exists before each line.
 */
object SkDiag {

    private const val TAG = "SkDiag"

    private const val PREFS = "sk_diag"
    private const val DIR = "diag"

    /** Never `.log` — see the class comment. */
    const val PREFIX = "ongaku-diag_"
    const val EXT = ".txt"
    private const val DEAD_EXT = ".log"

    private const val KEY_EXPORT_TREE = "export_tree"
    private const val KEY_KEEP_DAYS = "keep_days"
    private const val KEY_MARKS = "marks"

    private const val CHANNEL = "sk_diag"
    private const val NOTIFICATION_ID = 0x0D1A6

    const val ACTION_MARK = "shiroikuma.ongaku.action.DIAG_MARK"
    const val ACTION_ALL_OFF = "shiroikuma.ongaku.action.DIAG_ALL_OFF"

    /** Every switch on the page, in the order it is shown. */
    enum class Group(val key: String, @StringRes val titleRes: Int, @StringRes val descRes: Int) {
        SCREENS("screens", R.string.sk_diag_screens, R.string.sk_diag_screens_desc),
        MEMORY("memory", R.string.sk_diag_memory, R.string.sk_diag_memory_desc),
        PLAYBACK("playback", R.string.sk_diag_playback, R.string.sk_diag_playback_desc),
        SERVICE("service", R.string.sk_diag_service, R.string.sk_diag_service_desc),
        LIBRARY("library", R.string.sk_diag_library, R.string.sk_diag_library_desc),
        AUTOMATION("automation", R.string.sk_diag_automation, R.string.sk_diag_automation_desc)
    }

    /**
     * Which groups are recording, one bit per ordinal. Volatile so [isOn] is a plain field read
     * from any thread — it sits in front of every log call in the app.
     */
    @Volatile
    private var enabledMask: Int = 0

    @Volatile
    private var app: Context? = null

    /**
     * Bumped by anything that renames, deletes or moves files underneath an open writer, so the
     * writer reopens instead of appending into a file that is no longer there.
     */
    @Volatile
    private var generation: Int = 0

    private val dayFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val stampFormat get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    // ------------------------------------------------------------------ state

    fun init(context: Context) {
        val application = context.applicationContext
        app = application
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var mask = 0
        for (group in Group.entries) {
            if (prefs.getBoolean(group.key, false)) mask = mask or (1 shl group.ordinal)
        }
        enabledMask = mask
        SkDiagLog.init(application)
        migrate(application)
        cleanup(application)
        updateNotification(application)
        SkDiagProbes.onEnabledChanged(application)
    }

    fun isOn(group: Group): Boolean = (enabledMask shr group.ordinal) and 1 == 1

    fun isAnyOn(): Boolean = enabledMask != 0

    fun generation(): Int = generation

    fun set(context: Context, group: Group, value: Boolean) {
        val application = context.applicationContext
        app = application
        application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(group.key, value).apply()
        enabledMask = if (value) {
            enabledMask or (1 shl group.ordinal)
        } else {
            enabledMask and (1 shl group.ordinal).inv()
        }
        // Written so a file always says what was being recorded, and by which build.
        if (value) {
            SkDiagLog.raw(group.key, "diag ${group.key} on, ${versionOf(application)}")
        } else {
            SkDiagLog.raw(group.key, "diag ${group.key} off")
        }
        updateNotification(application)
        SkDiagProbes.onEnabledChanged(application)
    }

    fun allOff(context: Context) {
        val application = context.applicationContext
        val editor = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (group in Group.entries) {
            if (isOn(group)) SkDiagLog.raw(group.key, "diag ${group.key} off")
            editor.putBoolean(group.key, false)
        }
        editor.apply()
        enabledMask = 0
        updateNotification(application)
        SkDiagProbes.onEnabledChanged(application)
    }

    private fun versionOf(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${context.packageName} ${info.versionName} (${info.longVersionCode})"
        }.getOrDefault(context.packageName)
    }

    // ------------------------------------------------------------------ storage

    /** `/sdcard/Android/data/shiroikuma.ongaku/files/diag/` — readable over adb, gone with the app. */
    fun dir(context: Context): File {
        val dir = File(context.applicationContext.getExternalFilesDir(null), DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun fileFor(context: Context, day: String): File = File(dir(context), "$PREFIX$day$EXT")

    fun isDiagFile(file: File): Boolean = file.name.startsWith(PREFIX) && file.name.endsWith(EXT)

    fun files(context: Context): List<File> {
        return dir(context).listFiles()?.filter { it.isFile && isDiagFile(it) }?.sortedBy { it.name }
                ?: emptyList()
    }

    fun dayOf(file: File): String = file.name.removePrefix(PREFIX).removeSuffix(EXT)

    /**
     * Rescues files an older build wrote as `*.log` — EMUI deletes those overnight — and anything
     * left lying in the files root. Prepends when the `.txt` for that day already exists, so a
     * rescue never loses what is already recorded.
     */
    private fun migrate(context: Context) {
        var moved = 0
        val roots = listOf(dir(context), context.getExternalFilesDir(null) ?: return)
        for (root in roots) {
            val stale = root.listFiles()?.filter {
                it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(DEAD_EXT)
            } ?: continue
            for (file in stale) {
                val day = file.name.removePrefix(PREFIX).removeSuffix(DEAD_EXT)
                if (moveInto(file, fileFor(context, day))) moved++
            }
        }
        // Diag files written straight into the files root by an older build.
        val loose = (context.getExternalFilesDir(null) ?: return).listFiles()?.filter {
            it.isFile && isDiagFile(it)
        } ?: emptyList()
        for (file in loose) {
            if (moveInto(file, fileFor(context, dayOf(file)))) moved++
        }
        if (moved > 0) {
            generation++
            Log.i(TAG, "migrated $moved diag file(s) to $EXT")
        }
    }

    /** Moves [from] onto [to], prepending if [to] already holds something. */
    private fun moveInto(from: File, to: File): Boolean {
        if (from == to) return false
        return runCatching {
            if (to.exists()) {
                val existing = to.readBytes()
                to.writeBytes(from.readBytes() + existing)
                from.delete()
            } else {
                if (!from.renameTo(to)) {
                    to.writeBytes(from.readBytes())
                    from.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ inventory

    class Summary(val count: Int, val bytes: Long, val firstDay: String?, val lastDay: String?)

    fun summarize(context: Context): Summary {
        val files = files(context)
        if (files.isEmpty()) return Summary(0, 0L, null, null)
        return Summary(files.size, files.sumOf { it.length() }, dayOf(files.first()), dayOf(files.last()))
    }

    // ------------------------------------------------------------------ retention

    /** 0 keeps everything, and is the default. */
    fun getKeepDays(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_KEEP_DAYS, 0)

    fun setKeepDays(context: Context, days: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_KEEP_DAYS, days).apply()
        cleanup(context)
    }

    /** Drops files older than the keep window. Runs on start and on leaving the page. */
    fun cleanup(context: Context): Int {
        val days = getKeepDays(context)
        if (days <= 0) return 0
        val cutoff = dayFormat.format(Date(System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L))
        var removed = 0
        for (file in files(context)) {
            if (dayOf(file) < cutoff && file.delete()) removed++
        }
        if (removed > 0) generation++
        return removed
    }

    fun purge(context: Context): Int {
        var removed = 0
        for (file in files(context)) {
            if (file.delete()) removed++
        }
        if (removed > 0) generation++
        return removed
    }

    // ------------------------------------------------------------------ marks

    /** A numbered line to find afterwards: "this is the moment it went wrong". */
    fun mark(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_MARKS, 0) + 1
        prefs.edit().putInt(KEY_MARKS, next).apply()
        SkDiagLog.raw("mark", "---- mark #$next ----")
        return next
    }

    // ------------------------------------------------------------------ export and share

    fun getExportTree(context: Context): Uri? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXPORT_TREE, null)?.let { Uri.parse(it) }

    fun setExportTree(context: Context, uri: Uri) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXPORT_TREE, uri.toString()).apply()
    }

    /**
     * Copies every diag file into a new `<stamp>_ongaku-diag` subdirectory of the picked tree,
     * so repeat exports never collide. Returns how many files landed.
     */
    fun export(context: Context, tree: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return 0
        val target = root.createDirectory("${stampFormat.format(Date())}_ongaku-diag") ?: return 0
        val resolver: ContentResolver = context.contentResolver
        var copied = 0
        for (file in files(context)) {
            val doc = target.createFile("text/plain", file.name) ?: continue
            runCatching {
                resolver.openOutputStream(doc.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                copied++
            }.onFailure { Log.w(TAG, "export of ${file.name} failed", it) }
        }
        return copied
    }

    /** Hands the files to another app through the FileProvider already declared in the manifest. */
    fun shareIntent(context: Context): Intent? {
        val files = files(context)
        if (files.isEmpty()) return null
        val authority = "${context.packageName}.provider"
        val uris = ArrayList<Uri>(files.size)
        for (file in files) {
            runCatching { uris.add(FileProvider.getUriForFile(context, authority, file)) }
                .onFailure { Log.w(TAG, "cannot share ${file.name}", it) }
        }
        if (uris.isEmpty()) return null
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ notification

    /** Ongoing while anything is recording, so it can never be left on unnoticed. */
    fun updateNotification(context: Context) {
        val application = context.applicationContext
        val manager = application.getSystemService(NotificationManager::class.java) ?: return
        if (!isAnyOn()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        manager.createNotificationChannel(NotificationChannel(
                CHANNEL,
                application.getString(R.string.sk_diag_notif_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })

        val recording = Group.entries.filter { isOn(it) }
            .joinToString(" · ") { application.getString(it.titleRes) }

        // Explicit, so neither action depends on an intent filter surviving the next
        // tightening of implicit broadcasts.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val mark = PendingIntent.getBroadcast(application, 1,
                Intent(application, SkDiagReceiver::class.java).setAction(ACTION_MARK), flags)
        val allOff = PendingIntent.getBroadcast(application, 2,
                Intent(application, SkDiagReceiver::class.java).setAction(ACTION_ALL_OFF), flags)

        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(application, CHANNEL)
                .setSmallIcon(R.drawable.ic_diag_note)
                .setContentTitle(application.getString(R.string.sk_diag_notif_title))
                .setContentText(recording)
                .setStyle(NotificationCompat.BigTextStyle().bigText(recording))
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, application.getString(R.string.sk_diag_notif_mark), mark)
                .addAction(0, application.getString(R.string.sk_diag_notif_alloff), allOff)
                .build())
    }
}
