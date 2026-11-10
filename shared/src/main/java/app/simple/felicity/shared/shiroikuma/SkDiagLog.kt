package app.simple.felicity.shared.shiroikuma

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The writer behind [SkDiag]: one plain-text file per day, appended from a single daemon thread
 * so no caller ever waits on the disk.
 *
 * Call it through [log], which takes the line as a lambda — nothing is formatted, concatenated
 * or allocated while a group is switched off, which is most of the time.
 */
object SkDiagLog {

    private const val TAG = "SkDiagLog"

    /** A runaway logger must not be able to fill the phone. */
    private const val MAX_BYTES_PER_DAY = 64L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sk-diag-log").apply { isDaemon = true }
    }

    // Touched only on the executor thread, where SimpleDateFormat's lack of thread safety is moot.
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private var out: BufferedWriter? = null
    private var outDay: String? = null
    private var outFile: File? = null
    private var outGeneration: Int = -1
    private var written: Long = 0L

    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun isOn(group: SkDiag.Group): Boolean = SkDiag.isOn(group)

    /**
     * Records one line for [group], if that group is recording. The line is built only when it
     * will actually be written.
     */
    inline fun log(group: SkDiag.Group, line: () -> String) {
        if (SkDiag.isOn(group)) submit(group.key, line())
    }

    /** Writes a line regardless of any switch — for the on/off markers and [SkDiag.mark]. */
    fun raw(tag: String, line: String) = submit(tag, line)

    @PublishedApi
    internal fun submit(tag: String, line: String) {
        val context = app ?: return
        val at = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        runCatching { executor.execute { append(context, tag, thread, at, line) } }
    }

    private fun append(context: Context, tag: String, thread: String, at: Long, line: String) {
        try {
            val day = dayFormat.format(Date(at))
            val generation = SkDiag.generation()
            val file = SkDiag.fileFor(context, day)

            // Reopen on a new day, after anything moved or deleted files (generation), and —
            // the one that actually bites — when the file we hold open is gone from the
            // directory. EMUI's clean-up unlinks it and every later line would vanish into the
            // unlinked inode without this check.
            if (out == null || day != outDay || generation != outGeneration || outFile?.exists() != true) {
                closeQuietly()
                SkDiag.dir(context)
                written = if (file.exists()) file.length() else 0L
                out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
                outDay = day
                outFile = file
                outGeneration = generation
            }

            if (written >= MAX_BYTES_PER_DAY) return

            val text = "${timeFormat.format(Date(at))} [$thread] $tag: $line\n"
            out?.apply {
                write(text)
                flush()
            }
            written += text.length
        } catch (e: Exception) {
            Log.w(TAG, "could not write a diag line", e)
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        runCatching { out?.close() }
        out = null
        outFile = null
        outDay = null
    }
}
