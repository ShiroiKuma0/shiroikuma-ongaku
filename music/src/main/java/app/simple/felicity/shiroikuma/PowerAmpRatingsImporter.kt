package app.simple.felicity.shiroikuma

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.AudioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.zip.ZipFile

/**
 * Fork: imports ratings from a PowerAmp `.poweramp-backup` export and marks every
 * rated track (rating >= 1) as a favorite in the library.
 *
 * The backup file is a ZIP whose `lists-export` entry is a SQLite database with a
 * `tracks(path, rating, ...)` table. Paths look like `primary/<relative path>` —
 * a storage volume name followed by the path relative to that volume's root.
 *
 * Matching strategy: both sides are normalized to a `<volume>/<relative path>` key —
 *  - PowerAmp `path` is already in that shape;
 *  - a library song's SAF uri decodes (via [DocumentsContract.getDocumentId]) to a
 *    document id like `primary:〇/music/song.mp3` whose first `:` becomes `/`;
 *  - a library song's real filesystem path maps `/storage/emulated/0/REST` and
 *    `/sdcard/REST` to `primary/REST`, and `/storage/XXXX-XXXX/REST` to `XXXX-XXXX/REST`.
 * Exact string comparison first; songs that miss are retried with both sides
 * Unicode-NFC normalized.
 *
 * The import is strictly additive: matched songs get `is_favorite = 1` through
 * [AudioRepository.setFavorite] (the same layer the player service's favorite toggle
 * uses); nothing is ever un-favorited.
 */
object PowerAmpRatingsImporter {

    private const val LISTS_EXPORT_ENTRY = "lists-export"

    /** Progress callback granularity: one report per this many tracks. */
    private const val PROGRESS_STEP = 20

    /**
     * Outcome of one import run: [rated] rows found in the backup,
     * [favorited] of them matched to library songs (and marked favorite),
     * [notFound] with no matching song in the library.
     */
    data class Result(val rated: Int, val favorited: Int, val notFound: Int)

    /**
     * Runs the whole import off the main thread. Throws [IOException] with a
     * readable message when the file is not a usable PowerAmp backup.
     *
     * [onProgress] is invoked on the MAIN thread every [PROGRESS_STEP] tracks
     * during the matching and favorite-writing phases, with the tracks done so
     * far, the phase's total, and the current track filename (or a phase word)
     * as the label.
     */
    suspend fun import(context: Context, backupUri: Uri,
                       onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val zipFile = File(appContext.cacheDir, "poweramp-import.zip")
        val dbFile = File(appContext.cacheDir, "poweramp-lists-export.db")

        try {
            copyBackupToCache(appContext, backupUri, zipFile)
            extractListsExport(zipFile, dbFile)
            val ratedPaths = readRatedPaths(dbFile)
            matchAndFavorite(appContext, ratedPaths, onProgress)
        } finally {
            zipFile.delete()
            dbFile.delete()
            // openDatabase may leave journal side-files behind; sweep them too.
            File(dbFile.absolutePath + "-journal").delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }

    /** Streams the picked document into [target] inside our cache directory. */
    private fun copyBackupToCache(context: Context, backupUri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(backupUri)
            ?: throw IOException("Could not open the selected file")
        input.use { stream ->
            target.outputStream().use { stream.copyTo(it) }
        }
    }

    /** Pulls the `lists-export` SQLite database out of the backup ZIP into [target]. */
    private fun extractListsExport(zip: File, target: File) {
        val zipFile = try {
            ZipFile(zip)
        } catch (e: Exception) {
            throw IOException("Not a valid PowerAmp backup (not a ZIP archive)", e)
        }
        zipFile.use { archive ->
            val entry = archive.getEntry(LISTS_EXPORT_ENTRY)
                ?: throw IOException("Not a PowerAmp backup (no lists-export entry)")
            archive.getInputStream(entry).use { stream ->
                target.outputStream().use { stream.copyTo(it) }
            }
        }
    }

    /** Reads every rated (rating >= 1) track path from the exported database. */
    private fun readRatedPaths(dbFile: File): List<String> {
        val paths = mutableListOf<String>()
        try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT path FROM tracks WHERE rating >= 1", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { paths.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Could not read the ratings database inside the backup", e)
        }
        return paths
    }

    /**
     * Matches the PowerAmp paths against the library and favorites every hit.
     * Reads the full (unfiltered) library so short or small files still match;
     * writes go through [AudioRepository.setFavorite] one id at a time.
     */
    private suspend fun matchAndFavorite(context: Context, ratedPaths: List<String>,
                                         onProgress: (done: Int, total: Int, label: String) -> Unit): Result {
        withContext(Dispatchers.Main) { onProgress(0, ratedPaths.size, "matching…") }

        val library = AudioDatabase.getInstance(context).audioDao()?.getAllAudioList()
            ?: throw IOException("Music library database is not available")

        // volume/rest key → song id; first entry wins so duplicates stay deterministic.
        val exact = HashMap<String, Long>(library.size * 2)
        val nfc = HashMap<String, Long>(library.size * 2)
        library.forEach { audio ->
            libraryKeysOf(audio).forEach { key ->
                exact.putIfAbsent(key, audio.id)
                nfc.putIfAbsent(Normalizer.normalize(key, Normalizer.Form.NFC), audio.id)
            }
        }

        val matchedIds = LinkedHashSet<Long>()
        var favorited = 0
        var notFound = 0
        ratedPaths.forEachIndexed { position, paPath ->
            val id = exact[paPath]
                ?: nfc[Normalizer.normalize(paPath, Normalizer.Form.NFC)]
            if (id != null) {
                favorited++
                matchedIds.add(id)
            } else {
                notFound++
            }
            if ((position + 1) % PROGRESS_STEP == 0 || position == ratedPaths.lastIndex) {
                withContext(Dispatchers.Main) {
                    onProgress(position + 1, ratedPaths.size, paPath.substringAfterLast('/'))
                }
            }
        }

        val repository = AudioRepository(context)
        matchedIds.forEachIndexed { position, id ->
            repository.setFavorite(id, true)
            if ((position + 1) % PROGRESS_STEP == 0 || position == matchedIds.size - 1) {
                withContext(Dispatchers.Main) {
                    onProgress(position + 1, matchedIds.size, "writing favorites…")
                }
            }
        }

        return Result(rated = ratedPaths.size, favorited = favorited, notFound = notFound)
    }

    /**
     * Every `<volume>/<relative path>` key this library song can be known by:
     * one derived from its SAF document id, one from its real filesystem path.
     */
    private fun libraryKeysOf(audio: Audio): List<String> {
        val keys = mutableListOf<String>()

        // SAF uri → document id "primary:REST" → "primary/REST".
        // getDocumentId percent-decodes, so the result is plain text.
        audio.uri?.let { uriString ->
            try {
                val docId = DocumentsContract.getDocumentId(uriString.toUri())
                val colon = docId.indexOf(':')
                if (colon > 0 && colon < docId.length - 1) {
                    keys.add(docId.substring(0, colon) + "/" + docId.substring(colon + 1))
                }
            } catch (_: Exception) {
                // Not a document uri — nothing to derive.
            }
        }

        // Real filesystem path → "primary/REST" (or "<uuid>/REST" for removable volumes).
        audio.path?.let { path ->
            when {
                path.startsWith("/storage/emulated/0/") ->
                    keys.add("primary/" + path.removePrefix("/storage/emulated/0/"))
                path.startsWith("/sdcard/") ->
                    keys.add("primary/" + path.removePrefix("/sdcard/"))
                path.startsWith("/storage/") ->
                    keys.add(path.removePrefix("/storage/"))
            }
        }

        return keys
    }
}
