package app.simple.felicity.shiroikuma

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import androidx.core.net.toUri
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.metadata.MetadataWriter
import app.simple.felicity.repository.metadata.TagLibBridge
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.MusicBrainzRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Fork: downloads missing album art from the internet and embeds it into the
 * audio files — fully automatic, no file picking.
 *
 * The run walks every (artist, album) group of the library and probes each
 * file for embedded art through the same TagLib bridge the app reads covers
 * with (MediaMetadataRetriever as fallback). Only albums where NO file carries
 * embedded art count as "missing" — an album with even one covered file is
 * left alone.
 *
 * For each missing album the release MBID (and, when available, the
 * release-group MBID) is resolved through [MusicBrainzRepository]'s release
 * search — paced to MusicBrainz's 1 request/second policy with the app's
 * proper User-Agent — and the front cover is fetched from the Cover Art
 * Archive (`release/<mbid>/front-500`, falling back to
 * `release-group/<mbid>/front-500` on 404). The downloaded JPEG is embedded
 * into every file of the album via [MetadataWriter.writeArtwork] (ID3v2 APIC
 * for mp3, covr for m4a, METADATA_BLOCK_PICTURE for FLAC/OGG, …), with the
 * same refresh bookkeeping as the PowerAmp art importer: dateModified bump +
 * Room update (so Glide's cache key changes) and one MediaScanner batch scan
 * at the end.
 *
 * Per-album errors never abort the run — network failures are counted as
 * failed, genuine "MusicBrainz/CAA has nothing" as not found. Temp files are
 * always cleaned up.
 */
object AlbumArtDownloader {

    private const val TAG = "AlbumArtDownloader"

    /** MusicBrainz allows 1 request/second — leave a little headroom. */
    private const val MUSIC_BRAINZ_MIN_INTERVAL_MS = 1100L

    /**
     * Outcome of one run: [missingAlbums] albums with no embedded art anywhere,
     * of which [downloaded] got a cover downloaded and embedded (into
     * [filesUpdated] files in total), [notFound] have no cover online
     * (no MusicBrainz match or CAA 404), and [failed] hit network trouble or
     * could not be written at all.
     */
    data class Result(
            val missingAlbums: Int,
            val downloaded: Int,
            val filesUpdated: Int,
            val notFound: Int,
            val failed: Int
    )

    /**
     * Runs the whole download off the main thread. [onProgress] is invoked on
     * the MAIN thread after every album group processed (covered albums pass
     * through quickly; missing ones go through the network), with the album's
     * "Artist - Album" name as the label.
     */
    suspend fun download(context: Context,
                         onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val artCache = File(appContext.cacheDir, "ongaku-caa-cover.jpg")
        try {
            runDownload(appContext, artCache, onProgress)
        } finally {
            artCache.delete()
        }
    }

    private suspend fun runDownload(context: Context, artCache: File,
                                    onProgress: (done: Int, total: Int, label: String) -> Unit): Result {
        val dao = AudioDatabase.getInstance(context).audioDao()
            ?: throw IOException("Music library database is not available")
        val library = dao.getAllAudioList()

        val albums = groupAlbums(library)
        val repository = MusicBrainzRepository(context)

        var missingAlbums = 0
        var downloaded = 0
        var filesUpdated = 0
        var notFound = 0
        var failed = 0
        var lastMusicBrainzCall = 0L
        val scanPaths = mutableListOf<String>()

        val total = albums.size
        withContext(Dispatchers.Main) { onProgress(0, total, "") }

        for ((done, album) in albums.withIndex()) {
            run album@{
                // Skip albums where any file already carries embedded art.
                if (album.songs.any { song ->
                        song.uri?.let { uri ->
                            try {
                                hasEmbeddedArt(context, uri)
                            } catch (e: Exception) {
                                Log.w(TAG, "Art probe failed for $uri", e)
                                false // unreadable file — treat as artless; the write will tell
                            }
                        } == true
                    }) {
                    return@album
                }

                missingAlbums++

                // Resolve the release (and release-group) MBID — one MusicBrainz
                // request, paced to the 1 req/s policy.
                val ids = try {
                    val wait = lastMusicBrainzCall + MUSIC_BRAINZ_MIN_INTERVAL_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    lastMusicBrainzCall = System.currentTimeMillis()
                    repository.searchReleaseCoverIds(album.album, album.artist)
                } catch (e: Exception) {
                    Log.w(TAG, "MusicBrainz search failed for ${album.label}", e)
                    failed++
                    return@album
                }
                if (ids == null) {
                    notFound++
                    return@album
                }

                // Fetch the front cover from the Cover Art Archive into the temp file.
                val fetched = try {
                    repository.downloadCoverArt(ids, artCache)
                } catch (e: Exception) {
                    Log.w(TAG, "Cover download failed for ${album.label}", e)
                    failed++
                    return@album
                }
                if (!fetched || artCache.length() == 0L) {
                    notFound++
                    return@album
                }

                // Embed the cover into every file of the album (none has art —
                // that is exactly why the album is in this branch).
                var wrote = 0
                album.songs.forEach { audio ->
                    val uriString = audio.uri ?: return@forEach
                    try {
                        if (MetadataWriter.writeArtwork(uriString.toUri(), artCache, context.contentResolver)) {
                            wrote++
                            // Mirror the metadata editor's refresh: bump dateModified in Room
                            // (Glide's ObjectKey(audio) changes with it) and rescan the file.
                            audio.dateModified = System.currentTimeMillis() / 1000L
                            dao.update(audio)
                            scanPaths.add(uriString)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed embedding art into $uriString", e)
                    }
                }
                if (wrote > 0) {
                    downloaded++
                    filesUpdated += wrote
                } else {
                    failed++ // cover was there, but not a single file took it
                }
            }

            withContext(Dispatchers.Main) { onProgress(done + 1, total, album.label) }
        }

        if (scanPaths.isNotEmpty()) {
            // Same refresh the metadata editor triggers after a save.
            MediaScannerConnection.scanFile(context, scanPaths.toTypedArray(), null, null)
        }

        return Result(missingAlbums = missingAlbums, downloaded = downloaded,
                      filesUpdated = filesUpdated, notFound = notFound, failed = failed)
    }

    /** One (artist, album) group of the library, with a display label for progress. */
    private class AlbumGroup(val artist: String, val album: String, val songs: List<Audio>) {
        val label: String get() = "$artist - $album"
    }

    /**
     * Groups the library into (artist, album) pairs — the album artist wins over
     * the track artist when set, so multi-artist albums form a single group.
     * Albums with a blank name are skipped (nothing sensible to search for).
     */
    private fun groupAlbums(library: List<Audio>): List<AlbumGroup> {
        val groups = LinkedHashMap<String, MutableList<Audio>>()
        library.forEach { audio ->
            val album = audio.album?.trim().orEmpty()
            if (album.isEmpty()) return@forEach
            val artist = albumArtistOf(audio)
            val key = "${artist.lowercase(Locale.ROOT)} ${album.lowercase(Locale.ROOT)}"
            groups.getOrPut(key) { mutableListOf() }.add(audio)
        }
        return groups.values
            .map { songs ->
                val first = songs.first()
                AlbumGroup(albumArtistOf(first), first.album?.trim().orEmpty(), songs)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    /** The artist an album is filed under: album artist when set, else track artist. */
    private fun albumArtistOf(audio: Audio): String {
        return audio.albumArtist?.trim().takeUnless { it.isNullOrEmpty() }
            ?: audio.artist?.trim().orEmpty()
    }

    /**
     * Probes the file for existing embedded art the same way the app reads it:
     * TagLib first, MediaMetadataRetriever as fallback. Throws when the file
     * cannot be opened at all.
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
