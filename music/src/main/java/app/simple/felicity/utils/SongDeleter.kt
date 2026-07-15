package app.simple.felicity.utils

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.LrcRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fork (automation): the delete flow that used to live inline in
 * {@code MediaFragment.deleteSong}, factored into a headless-callable helper so the
 * {@code DELETE_CURRENT} automation op (hand-off.md B.2) and the in-app song menu share
 * one implementation.
 *
 * Order of operations:
 * 1. Advance playback away from the track (queue removal auto-advances when it was the
 *    current song) so the player never tries to open a URI that is about to disappear.
 * 2. Delete the file — SAF [DocumentsContract.deleteDocument] first (audio is normally
 *    stored as a content:// document URI), then a direct filesystem delete via the
 *    MediaStore-resolved path, then a plain resolver delete as a last resort.
 * 3. Remove the Room row (and, optionally, the internally-stored lyric sidecars).
 */
object SongDeleter {

    private const val TAG = "SongDeleter"

    /**
     * Deletes [audio] end to end. Safe to call from any dispatcher; the queue
     * manipulation hops to the main thread internally.
     *
     * @param deleteLyrics Also remove the internally-stored LRC/TXT sidecar files.
     * @return `true` when the file was deleted (or already gone) and the database row removed.
     */
    suspend fun deleteSong(context: Context, audio: Audio, deleteLyrics: Boolean): Boolean {
        return try {
            // Step 1: Remove the song from the playback queue on the main thread so playback
            // never tries to open a URI we are about to delete.
            withContext(Dispatchers.Main) {
                val queueIndex = MediaPlaybackManager.getSongs().indexOfFirst { it.id == audio.id }
                when {
                    queueIndex != -1 -> MediaPlaybackManager.removeQueueItemSilently(queueIndex)
                    MediaPlaybackManager.getCurrentSong()?.id == audio.id -> MediaPlaybackManager.next()
                }
            }

            // Step 2: Delete the audio file itself.
            val deleted = withContext(Dispatchers.IO) { deleteFile(context, audio) }

            if (deleted) {
                // Step 3: Remove the row from the database.
                AudioDatabase.getInstance(context).audioDao()?.delete(audio)
                Log.d(TAG, "Song deleted successfully: ${audio.title}")

                if (deleteLyrics) {
                    // Clean up the internally-stored LRC/TXT sidecar files.
                    LrcRepository.deleteSidecarsStatic(context, audio.uri)
                }
            } else {
                Log.e(TAG, "Could not delete: ${audio.uri}")
            }

            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting song: ${e.message}", e)
            false
        }
    }

    /**
     * Deletes the underlying file. SAF first, then the resolved filesystem path,
     * then a plain [ContentResolver.delete]. A file that no longer exists counts
     * as deleted so the database row is still cleaned up.
     */
    private fun deleteFile(context: Context, audio: Audio): Boolean {
        val uri = audio.uri.toUri()

        // Audio is normally stored as a SAF content URI, so DocumentsContract is
        // the canonical path — exactly what MediaFragment.deleteSong always did.
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "DocumentsContract.deleteDocument failed: ${e.message}", e)
            }
        }

        // Direct filesystem delete via the MediaStore-resolved path (or a file:// uri).
        val path = audio.path?.takeIf { it.isNotEmpty() }
            ?: uri.takeIf { it.scheme == ContentResolver.SCHEME_FILE }?.path
        if (path != null) {
            try {
                val file = File(path)
                if (!file.exists() || file.delete()) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "File delete failed for $path: ${e.message}", e)
            }
        }

        // Last resort: MediaStore-style resolver delete.
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver delete failed: ${e.message}", e)
            false
        }
    }
}
