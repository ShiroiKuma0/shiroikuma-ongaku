package app.simple.felicity.engine.broadcasts

import android.content.Context
import android.content.Intent
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle

/**
 * Fork (automation): the outgoing broadcast side of the automation contract
 * (hand-off.md B.1), consumed by the 自由作業盤 (OpenTasker fork) via dynamically
 * registered receivers.
 *
 * The ACTION strings and extra KEY NAMES are a frozen public contract — the extras
 * surface on the consumer side as `%INTENT_<KEY>` variables. Only the action strings
 * carry the `shiroikuma.ongaku.` prefix (hand-off.md E); the code namespace stays
 * `app.simple.felicity.*`.
 */
object AutomationBroadcasts {

    /** Emitted on every play/pause flip. */
    const val ACTION_STATUS_CHANGED = "shiroikuma.ongaku.STATUS_CHANGED"

    /** Emitted on every track transition AND after any favorite toggle. */
    const val ACTION_TRACK_CHANGED = "shiroikuma.ongaku.TRACK_CHANGED"

    const val EXTRA_PAUSED = "paused"
    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_FAVORITE = "favorite"
    const val EXTRA_PATH = "path"
    const val EXTRA_URI = "uri"

    /**
     * Sends [ACTION_STATUS_CHANGED] for [song]. No-op when there is no current song —
     * the consumer only cares about state while something is loaded.
     *
     * @param paused `!player.isPlaying` at the moment of the flip.
     */
    fun sendStatusChanged(context: Context, song: Audio?, paused: Boolean) {
        song ?: return
        context.sendBroadcast(Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_PAUSED, paused)
            putExtra(EXTRA_ID, song.id)
            putExtra(EXTRA_TITLE, song.getProperTitle())
            putExtra(EXTRA_ARTIST, song.getProperArtists())
            putExtra(EXTRA_FAVORITE, song.isFavorite)
        })
    }

    /**
     * Sends [ACTION_TRACK_CHANGED] for [song]. `path` is the real filesystem path
     * resolved from MediaStore ([Audio.getPath]); for SAF-indexed songs without a
     * populated path it is derived from the document id so it always reads like a
     * filesystem path (hand-off addendum #3). The `uri` extra keeps the raw SAF string.
     */
    fun sendTrackChanged(context: Context, song: Audio?, paused: Boolean) {
        song ?: return
        context.sendBroadcast(Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_PATH, song.path?.takeIf { it.isNotEmpty() } ?: humanReadablePath(song.uri))
            putExtra(EXTRA_URI, song.uri)
            putExtra(EXTRA_ID, song.id)
            putExtra(EXTRA_TITLE, song.getProperTitle())
            putExtra(EXTRA_ARTIST, song.getProperArtists())
            putExtra(EXTRA_FAVORITE, song.isFavorite)
            putExtra(EXTRA_PAUSED, paused)
        })
    }

    /**
     * Turns a SAF uri string into something that reads like a filesystem path:
     * the document id is already percent-decoded (e.g. "primary:〇/[277] 音楽/…"),
     * so `primary:` becomes `/sdcard/`. Non-document uris fall back to plain
     * percent-decoding — never the raw encoded blob.
     */
    private fun humanReadablePath(uri: String?): String? {
        uri ?: return null
        return runCatching {
            val documentId = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
            if (documentId.startsWith("primary:")) {
                "/sdcard/" + documentId.removePrefix("primary:")
            } else {
                documentId
            }
        }.getOrElse {
            android.net.Uri.decode(uri)
        }
    }
}
