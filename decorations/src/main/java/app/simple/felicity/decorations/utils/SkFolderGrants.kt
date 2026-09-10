package app.simple.felicity.decorations.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.simple.felicity.preferences.SAFPreferences

/**
 * Storage Access Framework grants for the music folders the library is built on — held,
 * missing, and how to ask for one back.
 *
 * ## Why this exists (白い熊, 2026-09-10)
 *
 * The library is scanned from tree URIs the user picked once; [SAFPreferences] remembers *which*
 * folders, and the system remembers *that this installation may read them*. Only the first half
 * is data. A backup restores the folder list, the favourites and the playlists perfectly, and
 * not one byte of the permission — a persisted grant belongs to an installation, so a restored
 * copy on a wiped phone knows exactly which folder its music lives in and is not allowed to look.
 *
 * The repair is the grant, and it is exact: document ids are path-based, so picking the same
 * folder again yields a **byte-identical** tree URI and every row that pointed at it works again
 * with nothing rewritten. This object answers "which recorded folders does this install not hold",
 * hands the picker a place to open, and persists what comes back. Same model as the sister app
 * 書籍閲覧 (`WhiteBearFolderGrants`).
 */
object SkFolderGrants {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The tree URIs this installation still holds a persisted read permission for. */
    fun heldTrees(context: Context): List<Uri> = runCatching {
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { isTree(it) }
    }.getOrDefault(emptyList())

    /** The folders the library is built on — what the user picked, restored or not. */
    fun recordedTrees(): List<Uri> = SAFPreferences.getTreeUris()
        .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        .filter { it.authority != null }
        .distinct()

    /**
     * The held tree that contains [target], or null when nothing we hold does.
     *
     * Compared as document ids rather than as URI strings: `volume:relative/path`, matched on
     * whole path segments so that `〇/音楽` never counts as a parent of `〇/音楽別`.
     */
    fun treeCovering(context: Context, target: Uri): Uri? {
        val wanted = runCatching { DocumentsContract.getTreeDocumentId(target) }.getOrNull() ?: return null
        return heldTrees(context).firstOrNull { tree ->
            val held = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            held != null && tree.authority == target.authority && isAncestorId(held, wanted)
        }
    }

    fun isCovered(context: Context, target: Uri): Boolean = treeCovering(context, target) != null

    /** At least one recorded music folder is actually readable by this installation. */
    fun anyRecordedHeld(context: Context): Boolean = recordedTrees().any { isCovered(context, it) }

    /** Of the recorded folders, the ones this install no longer holds — in order, each once. */
    fun missing(context: Context): List<Uri> = recordedTrees().filterNot { isCovered(context, it) }

    /**
     * Where the picker should open — the folder we are missing, as a document URI.
     *
     * A hint, not a command: AOSP's picker honours `EXTRA_INITIAL_URI`, and one that ignores it
     * simply opens where it likes, which costs some navigating and nothing else. The grant that
     * comes back is exact either way.
     */
    fun initialUriFor(tree: Uri): Uri? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        // The picker wants a *document* URI to land on, not the tree URI itself.
        return runCatching { DocumentsContract.buildDocumentUri(tree.authority ?: EXTERNAL_STORAGE, id) }.getOrNull()
    }

    /**
     * Keep what the picker just handed back — persistably, so it survives the next launch — and
     * record it as a music folder. Idempotent: re-granting a folder already in the list is a no-op
     * for the list.
     */
    fun persist(context: Context, granted: Uri): Boolean {
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                    granted,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (taken) SAFPreferences.addTreeUri(granted.toString())
        return taken
    }

    /** Drop the recorded folders this install does not hold — the user has said they are gone. */
    fun forgetMissing(context: Context) {
        missing(context).forEach { SAFPreferences.removeTreeUri(it.toString()) }
    }

    /** `〇/音楽` — the readable half of a tree URI, for telling the user which folder. */
    fun displayPathOf(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val relative = id.substringAfter(':', missingDelimiterValue = id)
        return relative.ifEmpty { id }
    }

    private fun isTree(uri: Uri): Boolean =
        runCatching { DocumentsContract.isTreeUri(uri) }.getOrElse { uri.pathSegments.firstOrNull() == "tree" }

    /** True when [candidate] is [ancestor] itself or something beneath it, by whole segments. */
    private fun isAncestorId(ancestor: String, candidate: String): Boolean {
        if (ancestor == candidate) return true
        val volumeA = ancestor.substringBefore(':', missingDelimiterValue = "")
        val volumeB = candidate.substringBefore(':', missingDelimiterValue = "")
        if (volumeA != volumeB) return false
        val pathA = ancestor.substringAfter(':', missingDelimiterValue = "").trimEnd('/')
        val pathB = candidate.substringAfter(':', missingDelimiterValue = "")
        // An empty ancestor path is the volume root and covers everything on it.
        if (pathA.isEmpty()) return true
        return pathB == pathA || pathB.startsWith("$pathA/")
    }
}
