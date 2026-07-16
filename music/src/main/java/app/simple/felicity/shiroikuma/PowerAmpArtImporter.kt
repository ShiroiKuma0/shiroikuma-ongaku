package app.simple.felicity.shiroikuma

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.metadata.MetadataWriter
import app.simple.felicity.repository.metadata.TagLibBridge
import app.simple.felicity.repository.models.Audio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.zip.ZipFile

/**
 * Fork: imports album art from a PowerAmp album-art export (`.poweramp-backup`,
 * a ZIP with `album_art/<Artist> - <Album>.jpg` entries) and embeds each image
 * into the audio files of the matching library album — through the exact same
 * TagLib path the metadata editor uses ([MetadataWriter.writeArtwork], i.e.
 * ID3v2 APIC for mp3, covr for m4a, METADATA_BLOCK_PICTURE for FLAC/OGG, …).
 *
 * Matching strategy — PowerAmp sanitized the filenames (characters illegal in
 * filenames became `_`, e.g. `Director_s Cut`; long names were truncated), so:
 *  - the entry name is split on the FIRST " - " into artist and album (albums
 *    may themselves contain " - ");
 *  - BOTH sides are normalized with the same sanitizer (the `/\:*?"<>|'&` set
 *    becomes `_`), Unicode-NFC folded and lowercased before comparison, so it
 *    does not matter whether PowerAmp or we replaced a given character;
 *  - the filename artist is compared against both the track artist and the
 *    album artist;
 *  - truncated album names are accepted as a prefix match, but only when the
 *    prefix hits exactly one library album of that artist (ambiguity would
 *    risk stamping the wrong cover).
 *
 * The import is strictly additive: only songs whose files have NO embedded
 * art yet are written (existing art is probed via the same TagLib bridge,
 * with a MediaMetadataRetriever fallback). Per-file errors never abort the
 * run — they are counted and reported honestly.
 */
object PowerAmpArtImporter {

    private const val TAG = "PowerAmpArtImporter"
    private const val ART_DIR = "album_art/"

    /** Characters PowerAmp replaces with `_` in exported art filenames (observed superset). */
    private val SANITIZED_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\'', '&')

    /** Minimum album-key length for a truncation prefix match — guards against absurd hits. */
    private const val MIN_PREFIX_LENGTH = 4

    /**
     * Outcome of one import run: [artFiles] art entries found in the backup,
     * [updated] songs that received embedded art, [alreadyHadArt] songs skipped
     * because their files already carry art, [unmatchedAlbums] art entries with
     * no matching (artist, album) in the library, and [failed] songs whose
     * files could not be probed or written.
     */
    data class Result(
            val artFiles: Int,
            val updated: Int,
            val alreadyHadArt: Int,
            val unmatchedAlbums: Int,
            val failed: Int
    )

    /**
     * Runs the whole import off the main thread. Throws [IOException] with a
     * readable message when the file is not a usable PowerAmp art export.
     *
     * [onProgress] is invoked on the MAIN thread after every album-art entry
     * processed, with the entries done so far, the total entry count, and the
     * entry's "Artist - Album" name as the label.
     */
    suspend fun import(context: Context, backupUri: Uri,
                       onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val zipCache = File(appContext.cacheDir, "poweramp-art-import.zip")
        val artCache = File(appContext.cacheDir, "poweramp-art-current.jpg")

        try {
            copyBackupToCache(appContext, backupUri, zipCache)
            runImport(appContext, zipCache, artCache, onProgress)
        } finally {
            zipCache.delete()
            artCache.delete()
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

    private suspend fun runImport(context: Context, zipCache: File, artCache: File,
                                  onProgress: (done: Int, total: Int, label: String) -> Unit): Result {
        val library = AudioDatabase.getInstance(context).audioDao()?.getAllAudioList()
            ?: throw IOException("Music library database is not available")
        val dao = AudioDatabase.getInstance(context).audioDao()!!

        // artistKey -> albumKey -> songs of that (artist, album)
        val index = buildLibraryIndex(library)

        val archive = try {
            ZipFile(zipCache)
        } catch (e: Exception) {
            throw IOException("Not a valid PowerAmp backup (not a ZIP archive)", e)
        }

        var artFiles = 0
        var updated = 0
        var alreadyHadArt = 0
        var unmatchedAlbums = 0
        var failed = 0
        val processedIds = HashSet<Long>()
        val scanPaths = mutableListOf<String>()

        archive.use { zip ->
            // Collect the art entries up front so progress can report a real total.
            val artEntries = buildList {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(ART_DIR)) continue
                    val baseName = entry.name.removePrefix(ART_DIR).substringBeforeLast('.')
                    if (baseName.isBlank()) continue
                    add(entry to baseName)
                }
            }
            val total = artEntries.size
            withContext(Dispatchers.Main) { onProgress(0, total, "") }

            for ((done, artEntry) in artEntries.withIndex()) {
                val (entry, baseName) = artEntry
                artFiles++

                run entry@{
                    val songs = matchAlbum(index, baseName)
                    if (songs == null) {
                        unmatchedAlbums++
                        return@entry
                    }

                    // Pull the image out once per entry; every song of the album reuses it.
                    try {
                        zip.getInputStream(entry).use { stream ->
                            artCache.outputStream().use { stream.copyTo(it) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not extract ${entry.name}", e)
                        failed += songs.count { it.id !in processedIds }
                        return@entry
                    }

                    songs.forEach { audio ->
                        if (!processedIds.add(audio.id)) return@forEach
                        val uriString = audio.uri ?: run { failed++; return@forEach }
                        try {
                            when {
                                hasEmbeddedArt(context, uriString) -> alreadyHadArt++
                                MetadataWriter.writeArtwork(uriString.toUri(), artCache, context.contentResolver) -> {
                                    updated++
                                    // Mirror the metadata editor's refresh: bump dateModified in Room
                                    // (Glide's ObjectKey(audio) changes with it) and rescan the file.
                                    audio.dateModified = System.currentTimeMillis() / 1000L
                                    dao.update(audio)
                                    scanPaths.add(uriString)
                                }
                                else -> failed++ // format TagLib cannot write (e.g. webm) or write error
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed embedding art into $uriString", e)
                            failed++
                        }
                    }
                }

                withContext(Dispatchers.Main) { onProgress(done + 1, total, baseName) }
            }
        }

        if (scanPaths.isNotEmpty()) {
            // Same refresh the metadata editor triggers after a save.
            MediaScannerConnection.scanFile(context, scanPaths.toTypedArray(), null, null)
        }

        return Result(artFiles = artFiles, updated = updated, alreadyHadArt = alreadyHadArt,
                      unmatchedAlbums = unmatchedAlbums, failed = failed)
    }

    /** Indexes the library by sanitized artist (and album artist) then sanitized album. */
    private fun buildLibraryIndex(library: List<Audio>): HashMap<String, HashMap<String, MutableList<Audio>>> {
        val index = HashMap<String, HashMap<String, MutableList<Audio>>>()
        library.forEach { audio ->
            val albumKey = normalize(audio.album ?: return@forEach)
            if (albumKey.isBlank()) return@forEach
            sequenceOf(audio.artist, audio.albumArtist)
                .filterNotNull()
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { artistKey ->
                    index.getOrPut(artistKey) { HashMap() }
                        .getOrPut(albumKey) { mutableListOf() }
                        .add(audio)
                }
        }
        return index
    }

    /**
     * Resolves one art entry's base name (`<Artist> - <Album>`) to the songs of
     * the matching library album, or null when nothing matches. Exact album
     * match first, then a unique-prefix match for truncated names.
     */
    private fun matchAlbum(index: HashMap<String, HashMap<String, MutableList<Audio>>>,
                           baseName: String): List<Audio>? {
        val separator = baseName.indexOf(" - ")
        if (separator <= 0) return null

        val artistKey = normalize(baseName.substring(0, separator))
        val albumKey = normalize(baseName.substring(separator + 3))
        if (albumKey.isBlank()) return null

        val albums = index[artistKey] ?: return null
        albums[albumKey]?.let { return it }

        // Truncated filename: the album key must be a prefix of exactly one library album.
        if (albumKey.length >= MIN_PREFIX_LENGTH) {
            val prefixHits = albums.entries.filter { it.key.startsWith(albumKey) }
            if (prefixHits.size == 1) return prefixHits[0].value
        }

        return null
    }

    /**
     * Normalizes one side of the comparison: the PowerAmp sanitizer charset
     * becomes `_` (applied to both sides, so it is irrelevant which side still
     * carries the original character), Unicode is NFC-folded, case is dropped.
     */
    private fun normalize(value: String): String {
        val sanitized = buildString(value.length) {
            value.forEach { c -> append(if (c in SANITIZED_CHARS) '_' else c) }
        }
        return Normalizer.normalize(sanitized, Normalizer.Form.NFC).lowercase().trim()
    }

    /**
     * Probes the file for existing embedded art the same way the app reads it:
     * TagLib first, MediaMetadataRetriever as fallback. Throws when the file
     * cannot be opened at all (caller counts that as a failure).
     */
    private fun hasEmbeddedArt(context: Context, uriString: String): Boolean {
        val uri = uriString.toUri()

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (TagLibBridge.nativeExtractArtworkFromFd(pfd.fd) != null) {
                    return true
                }
                // TagLib read the file and found no picture — trust it.
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "TagLib art probe failed for $uriString, trying MediaMetadataRetriever", e)
        }

        // Fallback probe — also validates that the file is readable at all.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return retriever.embeddedPicture != null
        } finally {
            retriever.release()
        }
    }
}
