package app.simple.felicity.shiroikuma

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import app.simple.felicity.R
import app.simple.felicity.manager.SharedPreferences.getSharedPreference
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.loader.AudioDatabaseLoader
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.models.Playlist
import app.simple.felicity.repository.models.PlaylistSongCrossRef
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Export/Import engine for every settable item in the app, mirroring the model of
 * the sister forks (Kōjiki / ArcaneChat): a zip of one JSON file per category plus
 * a manifest, written to a user-chosen SAF directory.
 *
 * Preference categories round-trip every SharedPreferences key with a type tag
 * ({@code {"t":..,"v":..}}); import is a per-key MERGE (never a clear), so
 * unknown/missing keys are left alone — old exports load into new app versions and
 * vice versa. Ratings and playlists are data categories read from / written to the
 * Room library database, matched by file path, strictly additively.
 *
 * ## The clean phone (白い熊, 2026-09-10)
 *
 * A restore onto a wiped phone runs against an *empty* library — 応用管理 imports straight after
 * the install, and the scan that fills the database cannot run before the music folder has been
 * granted again. Nothing here needs the folder: the preferences land as they are, the folder
 * list itself travels so the app can ask for that grant back (see `SkFolderGrants`), and every
 * favourite or playlist song that finds no row is **kept waiting** in [pendingDir] rather than
 * counted as lost. [applyPending] re-runs those leftovers after each library scan and drains what
 * matched, so the restore completes itself the moment the folder is re-granted and scanned.
 */
object SkBackup {

    const val FORMAT = "shiroikuma-ongaku-export"

    /**
     * 2 (2026-09-10): favourites and playlist songs are objects `{"doc","path"}` — the SAF
     * document id first, the filesystem path second. Version 1 wrote bare path strings; both
     * are read. See [Song] for why the path alone was not enough.
     */
    const val VERSION = 2
    /**
     * The family-wide backup filename stem: the app's english dash-separated name, with the
     * datetime appended and no version. Doubles as the prefix [latestExport] scans for — it
     * still matches the older `<stem>-<version>-export_<datetime>.zip` names, so exports
     * written before the rename are not lost from the "last export" probe.
     */
    const val EXPORT_PREFIX = "shiroikuma-ongaku"

    /** Device-local prefs holding the export-directory URI; deliberately never exported. */
    private const val EXIMPORT_PREFS = "shiroikuma_eximport"
    private const val KEY_DIR_URI = "dir_uri"

    /** Keys that must not travel between installs (device/session-local state). */
    private val APP_EXCLUDE = setOf("crash_timestamp", "crash_log")

    /**
     * The music-folder list (`SAFPreferences`). It **does** travel — it is the record of where the
     * library lives, which is exactly what a restored copy needs in order to ask for that folder's
     * grant back — but it is merged as a union on import, never replaced: the phone being restored
     * onto may already have a folder of its own, and a list is not a setting.
     */
    private const val KEY_SAF_TREE_URIS = "saf_granted_tree_uris"

    private const val TAG = "SkBackup"

    /** Where leftovers of a data import wait for the next library scan. */
    private const val PENDING_DIR = "shiroikuma_pending_restore"

    /** One import or pending pass at a time — a scan finishing mid-import must queue, not race. */
    private val pendingLock = Mutex()

    /**
     * Keys excluded from EVERY category — **the whole automation gate is device-local**
     * (保存復元 contract §2).
     *
     * Most sister apps keep these three in a separate prefs file that is simply never in the
     * export; ours live in the app's ordinary preferences, so this set is the only thing
     * keeping them on this phone. All three must stay here:
     *
     * - [AutomationPreferences.TOKEN] — a zip is copied around and restored on other installs,
     *   and an imported token would silently break the pairing with 自由作業盤, which keeps its
     *   own copy of it.
     * - [AutomationPreferences.REQUIRE_TOKEN] — restored onto another device it would demand a
     *   token that device's caller has never been given, failing every batch run there.
     * - [AutomationPreferences.ENABLED] — restored as `false` it would silently close the app
     *   off on a phone 白い熊 has just set up, which is precisely the clean-phone case v2 exists
     *   to serve.
     *
     * Consequently [Cat.AUTOMATION] now exports an **empty** object. Its id is kept anyway so a
     * caller holding a saved `items` list is never answered `ERROR:unknown category in items`.
     */
    private val NEVER_EXPORT = setOf(
            AutomationPreferences.ENABLED,
            AutomationPreferences.REQUIRE_TOKEN,
            AutomationPreferences.TOKEN)

    /** Progress units for the headless export — real counts, never a percentage. */
    const val UNIT_CATEGORY = "区分"
    const val UNIT_SONG = "楽曲"
    const val UNIT_PLAYLIST = "プレイリスト"

    /**
     * Progress sink for a long export: [current]/[total] are real counts of [unit], and
     * [text] is the ready-made numbers-first display line (e.g. `楽曲 1234/8942`). The
     * sink is called freely — throttling is the caller's job.
     */
    fun interface Progress {
        fun onProgress(current: Long, total: Long, unit: String, text: String)
    }

    /**
     * The selectable categories; [id] doubles as the JSON entry name inside the zip.
     *
     * [defaultOn] is this app's own statement of whether the item **starts ticked** in a
     * picker — our Export/Import panel and 保存復元's item editor both seed from it, so
     * neither has to guess (保存復元 contract §`LIST_CATEGORIES`, fourth field). It is `true`
     * for everything here: nothing this app exports is large, derived *and* re-creatable.
     * A future category that is (a downloaded-media cache, a regenerable thumbnail set)
     * declares `false` and both pickers follow.
     */
    enum class Cat(val id: String, @field:StringRes val labelRes: Int, val defaultOn: Boolean = true) {
        // declaration order is dialog order
        UI("ui_theme", R.string.sk_eim_cat_ui),
        METEORS("meteors", R.string.sk_eim_cat_meteors),
        AUTOMATION("automation", R.string.sk_eim_cat_automation),
        APP("app_settings", R.string.sk_eim_cat_app),
        RATINGS("ratings", R.string.sk_eim_cat_ratings),
        PLAYLISTS("playlists", R.string.sk_eim_cat_playlists)
    }

    /**
     * A caller-owned stop signal, polled by [export] at every entry boundary — never a thread
     * interrupt, so a `write()` in flight always completes and the zip unwinds cleanly.
     */
    fun interface Cancellation {
        fun isCancelled(): Boolean
    }

    /** Thrown out of [export] when its [Cancellation] turned true; the caller deletes the partial file. */
    class ExportCancelledException : Exception("cancelled")

    private fun Cancellation?.stopHere() {
        if (this?.isCancelled() == true) throw ExportCancelledException()
    }

    // --- category membership (preference categories only) -----------------------------------

    private fun isMeteorKey(k: String) = k.startsWith("sk_meteor_")

    private fun isAutomationKey(k: String) = k.startsWith("sk_automation_")

    private fun isUiKey(k: String): Boolean {
        if (isMeteorKey(k) || isAutomationKey(k)) return false
        return k.startsWith("sk_") ||
                k == AppearancePreferences.APP_CORNER_RADIUS ||
                k == AppearancePreferences.LIST_SPACING ||
                k == AppearancePreferences.APP_FONT
    }

    private fun inCategory(cat: Cat, key: String): Boolean {
        if (key in NEVER_EXPORT) return false
        return when (cat) {
            Cat.UI -> isUiKey(key)
            Cat.METEORS -> isMeteorKey(key)
            // Fail closed: every `sk_automation_` key is device-local (see NEVER_EXPORT), and a
            // key added to AutomationPreferences later must not start travelling just because
            // nobody remembered to name it there.
            Cat.AUTOMATION -> false
            Cat.APP -> !isUiKey(key) && !isMeteorKey(key) && !isAutomationKey(key) && key !in APP_EXCLUDE
            else -> false // RATINGS / PLAYLISTS are not prefs-based
        }
    }

    // --- song identity ------------------------------------------------------------------------

    /**
     * How a favourite or a playlist member is written down, and found again.
     *
     * [doc] is the SAF document id of the scanned file — `primary:〇/[277] 音楽/…/x.mp3` — the
     * scanner's own key, derived from the path alone, so it is byte-identical on any phone that
     * holds the same files under the same folder. [path] is upstream's `path` column, which is
     * **approximate**: it is filled after the scan by matching (title, artist, album) against
     * MediaStore, so two files with the same tags share one path and a file MediaStore describes
     * differently has none. On 白い熊's new phone five favourites out of 341 sat unmatched on
     * path alone while their files were present and scanned (2026-09-10) — hence the document id
     * first and the path only as a fallback, and a path is also *converted* to a document id
     * before matching so version-1 archives resolve the same way.
     */
    class Song(val doc: String?, val path: String?) {

        /** What to show a person: the path, or the path the document id stands for. */
        val display: String get() = path ?: doc?.let { pathFromDoc(it) } ?: doc.orEmpty()

        val fileName: String get() = display.substringAfterLast('/')

        fun toJson(): Any = JSONObject().apply {
            put("doc", doc ?: JSONObject.NULL)
            put("path", path ?: JSONObject.NULL)
        }

        companion object {
            /** A version-2 object or a version-1 bare path; null for anything empty. */
            fun fromJson(value: Any?): Song? = when (value) {
                is JSONObject -> Song(value.optString("doc").takeIf { it.isNotBlank() && !value.isNull("doc") },
                                      value.optString("path").takeIf { it.isNotBlank() && !value.isNull("path") })
                    .takeIf { it.doc != null || it.path != null }
                is String -> value.takeIf { it.isNotBlank() }?.let { Song(docFromPath(it), it) }
                else -> null
            }

            fun of(audio: Audio): Song? {
                val doc = docIdOf(audio.uri)
                val path = audio.path?.takeIf { it.isNotBlank() }
                return if (doc == null && path == null) null else Song(doc, path)
            }
        }
    }

    /** `content://…/document/primary%3A…` → `primary:…`; null for anything that is not one. */
    fun docIdOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrNull()
    }

    /** `/storage/emulated/0/x` → `primary:x`, `/storage/1234-5678/x` → `1234-5678:x`. */
    fun docFromPath(path: String): String? {
        val primary = "/storage/emulated/0/"
        if (path.startsWith(primary)) return "primary:" + path.removePrefix(primary)
        val m = Regex("^/storage/([^/]+)/(.*)$").find(path) ?: return null
        return m.groupValues[1] + ":" + m.groupValues[2]
    }

    /** The inverse of [docFromPath]. */
    fun pathFromDoc(doc: String): String? {
        val volume = doc.substringBefore(':', missingDelimiterValue = "")
        if (volume.isEmpty()) return null
        val rel = doc.substringAfter(':')
        return if (volume == "primary") "/storage/emulated/0/$rel" else "/storage/$volume/$rel"
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /**
     * The library, keyed every way a [Song] can name a row: document id (exact, NFC), then path
     * (exact, NFC), then the path re-expressed as a document id. [value] picks what a hit yields —
     * the row id for favourites, the hash for playlist membership.
     */
    class LibraryIndex(audios: List<Audio>, value: (Audio) -> Long) {
        private val byDoc = HashMap<String, Long>()
        private val byDocNfc = HashMap<String, Long>()
        private val byPath = HashMap<String, Long>()
        private val byPathNfc = HashMap<String, Long>()

        init {
            audios.forEach { audio ->
                val v = value(audio)
                docIdOf(audio.uri)?.let {
                    byDoc.putIfAbsent(it, v)
                    byDocNfc.putIfAbsent(nfc(it), v)
                }
                audio.path?.takeIf { it.isNotBlank() }?.let {
                    byPath.putIfAbsent(it, v)
                    byPathNfc.putIfAbsent(nfc(it), v)
                }
            }
        }

        fun find(song: Song): Long? {
            song.doc?.let { d -> (byDoc[d] ?: byDocNfc[nfc(d)])?.let { return it } }
            song.path?.let { p ->
                (byPath[p] ?: byPathNfc[nfc(p)])?.let { return it }
                docFromPath(p)?.let { d -> (byDoc[d] ?: byDocNfc[nfc(d)])?.let { return it } }
            }
            return null
        }
    }

    // --- export ------------------------------------------------------------------------------

    /** e.g. `shiroikuma-ongaku_2026-07-25_18-58-23.zip` — name, datetime, nothing else. */
    fun exportFileName(): String {
        return EXPORT_PREFIX + "_" +
                SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"
    }

    /**
     * Streams the export zip for the given categories to [out]. The caller owns the
     * stream and should delete the target file if this throws midway.
     *
     * [progress] is optional and exists so the same core can be driven headlessly by
     * [app.simple.felicity.automation.StateExportReceiver] — the UI panel and the
     * receiver are two thin callers of this one function, never two implementations.
     *
     * [cancelled] is polled between entries; when it turns true this throws
     * [ExportCancelledException] and the caller deletes what it had written so far.
     */
    suspend fun export(context: Context, cats: List<Cat>, out: OutputStream,
                       progress: Progress? = null, cancelled: Cancellation? = null) {
        ZipOutputStream(out).use { zip ->
            val catIds = JSONArray()
            cats.forEach { catIds.put(it.id) }
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", context.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", catIds)
            writeEntry(zip, "manifest.json", manifest.toString(2))

            val total = cats.size.toLong()
            for ((index, cat) in cats.withIndex()) {
                cancelled.stopHere()
                val position = index + 1L
                progress?.onProgress(position, total, UNIT_CATEGORY,
                                     "$UNIT_CATEGORY $position/$total — " + context.getString(cat.labelRes))
                val content = when (cat) {
                    Cat.RATINGS -> exportRatings(context, progress, cancelled)
                    Cat.PLAYLISTS -> exportPlaylists(context, progress, cancelled)
                    else -> exportPrefs(context, cat)
                }
                writeEntry(zip, cat.id + ".json", content)
            }
        }
    }

    /** Every matching key with a type tag: {"t":"b|i|l|f|s|ss","v":...}. */
    private fun exportPrefs(context: Context, cat: Cat): String {
        val obj = JSONObject()
        for ((k, v) in getSharedPreference(context).all) {
            if (!inCategory(cat, k)) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> {
                    val a = JSONArray()
                    v.forEach { a.put(it.toString()) }
                    e.put("t", "ss").put("v", a)
                }
                else -> continue
            }
            obj.put(k, e)
        }
        return obj.toString(2)
    }

    /** All favorited songs by their real file paths. */
    private fun exportRatings(context: Context, progress: Progress? = null,
                              cancelled: Cancellation? = null): String {
        val dao = AudioDatabase.getInstance(context).audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val all = dao.getAllAudioListAll()
        val total = all.size.toLong()
        val a = JSONArray()
        var done = 0L
        all.forEach { audio ->
            cancelled.stopHere()
            if (audio.isFavorite) Song.of(audio)?.let { a.put(it.toJson()) }
            done++
            progress?.onProgress(done, total, UNIT_SONG, "$UNIT_SONG $done/$total")
        }
        return JSONObject().put("favorites", a).toString(2)
    }

    /**
     * Every user-created playlist (M3U-scanned ones are skipped — they regenerate from
     * their files) with its metadata and member song paths in manual order.
     */
    private suspend fun exportPlaylists(context: Context, progress: Progress? = null,
                                        cancelled: Cancellation? = null): String {
        val dao = AudioDatabase.getInstance(context).playlistDao()
        val list = JSONArray()
        val own = dao.getAllPlaylists().first().filterNot { it.isM3UPlaylist }
        val total = own.size.toLong()
        var done = 0L
        for (playlist in own) {
            cancelled.stopHere()
            done++
            progress?.onProgress(done, total, UNIT_PLAYLIST, "$UNIT_PLAYLIST $done/$total")
            val songs = JSONArray()
            dao.getSongsInPlaylistOrdered(playlist.id).first().forEach { audio ->
                Song.of(audio)?.let { songs.put(it.toJson()) }
            }
            list.put(JSONObject()
                         .put("name", playlist.name)
                         .put("description", playlist.description ?: JSONObject.NULL)
                         .put("pinned", playlist.isPinned)
                         .put("shuffled", playlist.isShuffled)
                         .put("sortOrder", playlist.sortOrder)
                         .put("sortStyle", playlist.sortStyle)
                         .put("songs", songs))
        }
        return JSONObject().put("playlists", list).toString(2)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // --- inspect -----------------------------------------------------------------------------

    /** The categories present in an export zip; empty if it isn't one of ours. */
    fun categoriesIn(zip: ZipFile): List<Cat> {
        return try {
            val manifest = readEntry(zip, "manifest.json") ?: return emptyList()
            if (JSONObject(manifest).optString("format") != FORMAT) return emptyList()
            Cat.entries.filter { zip.getEntry(it.id + ".json") != null }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- import ------------------------------------------------------------------------------

    /**
     * Applies the selected categories from an export zip; categories missing from the
     * zip are skipped. Returns a human-readable per-category summary, or null when the
     * file carried none of the selected categories.
     */
    suspend fun import(context: Context, zip: ZipFile, cats: List<Cat>): String? = pendingLock.withLock {
        if (zip.getEntry("manifest.json") == null) return@withLock null

        val summary = StringBuilder()
        var any = false
        for (cat in cats) {
            val data = readEntry(zip, cat.id + ".json") ?: continue
            val line = when (cat) {
                Cat.RATINGS -> {
                    val r = importRatings(context, data)
                    mergePending(context, Cat.RATINGS, r.leftover)
                    context.getString(R.string.sk_eim_ratings_result, r.favorited, r.notFound) + deferredNote(context, r.notFound)
                }
                Cat.PLAYLISTS -> {
                    val r = importPlaylists(context, data)
                    mergePending(context, Cat.PLAYLISTS, r.leftover)
                    context.getString(R.string.sk_eim_playlists_result, r.created, r.merged, r.songsAdded, r.songsMissing) +
                            deferredNote(context, r.songsMissing)
                }
                else -> context.getString(R.string.sk_eim_keys_applied, importPrefs(context, data, cat))
            }
            any = true
            if (summary.isNotEmpty()) summary.append('\n')
            summary.append(context.getString(cat.labelRes)).append(": ").append(line)
        }
        if (any) summary.toString() else null
    }

    private fun deferredNote(context: Context, missing: Int): String =
        if (missing > 0) " — " + context.getString(R.string.sk_eim_deferred, missing) else ""

    /**
     * Per-key merge — never clears, so unrelated/device-local keys survive. Returns keys applied.
     *
     * Written with `commit()`, not `apply()`: 応用管理 force-stops this process the instant it
     * hears OK, and an asynchronous write has no orderly shutdown left to land in. The write is
     * on disk before this returns, so it is on disk before the reply goes out.
     */
    private fun importPrefs(context: Context, json: String, cat: Cat): Int {
        val obj = JSONObject(json)
        val prefs = getSharedPreference(context)
        val ed = prefs.edit()
        var count = 0
        for (k in obj.keys()) {
            if (!inCategory(cat, k)) continue // don't let one category's file smuggle another's keys
            val e = obj.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "ss" -> {
                    val a = e.optJSONArray("v")
                    val set = HashSet<String>()
                    if (a != null) for (i in 0 until a.length()) set.add(a.optString(i))
                    // The folder list is a union: keep what this phone already has, add what
                    // the backup remembers. A folder is a record, not a setting to overwrite.
                    if (k == KEY_SAF_TREE_URIS) prefs.getStringSet(k, null)?.let { set.addAll(it) }
                    ed.putStringSet(k, set)
                }
                else -> continue
            }
            count++
        }
        ed.commit()
        return count
    }

    private class RatingsResult(val favorited: Int, val notFound: Int, val leftover: JSONObject?)

    private class PlaylistsResult(val created: Int, val merged: Int, val songsAdded: Int, val songsMissing: Int,
                                  val leftover: JSONObject?)

    /**
     * Additive favorites restore: every exported path that matches a library song gets
     * {@code is_favorite = 1}; nothing is ever un-favorited. Paths that match nothing come back
     * as [RatingsResult.leftover] in the export's own shape, ready to be tried again.
     */
    private suspend fun importRatings(context: Context, json: String): RatingsResult {
        val dao = AudioDatabase.getInstance(context).audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val entries = JSONObject(json).optJSONArray("favorites") ?: JSONArray()
        val index = LibraryIndex(dao.getAllAudioListAll()) { it.id }

        var favorited = 0
        var notFound = 0
        val leftover = JSONArray()
        for (i in 0 until entries.length()) {
            val song = Song.fromJson(entries.opt(i)) ?: continue
            val id = index.find(song)
            if (id != null) {
                dao.setFavorite(id, true)
                favorited++
            } else {
                notFound++
                leftover.put(song.toJson())
            }
        }
        return RatingsResult(favorited, notFound,
                             if (leftover.length() > 0) JSONObject().put("favorites", leftover) else null)
    }

    /**
     * Merge-import of playlists: a playlist whose name already exists gets the missing
     * songs appended at its end; unknown names are created with the exported metadata.
     * Songs are matched by path; misses are counted, never invented — and handed back as
     * [PlaylistsResult.leftover]: the same playlist entries, each carrying only the songs
     * that found no row, so a later pass appends exactly those and nothing twice.
     */
    private suspend fun importPlaylists(context: Context, json: String): PlaylistsResult {
        val database = AudioDatabase.getInstance(context)
        val audioDao = database.audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val dao = database.playlistDao()

        val index = LibraryIndex(audioDao.getAllAudioListAll()) { it.hash }

        val list = JSONObject(json).optJSONArray("playlists") ?: JSONArray()
        val leftoverList = JSONArray()
        var created = 0
        var merged = 0
        var songsAdded = 0
        var songsMissing = 0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue

            val hashes = LinkedHashSet<Long>()
            val songs = item.optJSONArray("songs") ?: JSONArray()
            val missingSongs = JSONArray()
            for (j in 0 until songs.length()) {
                val song = Song.fromJson(songs.opt(j)) ?: continue
                val hash = index.find(song)
                if (hash != null) {
                    hashes.add(hash)
                } else {
                    songsMissing++
                    missingSongs.put(song.toJson())
                }
            }
            if (missingSongs.length() > 0) {
                // Same entry, same metadata, only the songs still owed — the pass that finally
                // finds them merges by this name and appends them at the end.
                leftoverList.put(JSONObject(item.toString()).put("songs", missingSongs))
            }

            val existing = dao.getPlaylistByName(name)
            val playlistId: Long
            val toAdd: List<Long>
            if (existing != null) {
                playlistId = existing.id
                toAdd = hashes.filter { !dao.isSongInPlaylist(playlistId, it) }
                if (toAdd.isNotEmpty()) merged++
            } else {
                playlistId = dao.insertPlaylist(Playlist(
                        name = name,
                        description = item.optString("description").takeIf { it.isNotBlank() && !item.isNull("description") },
                        isPinned = item.optBoolean("pinned", false),
                        isShuffled = item.optBoolean("shuffled", false),
                        sortOrder = item.optInt("sortOrder", -1),
                        sortStyle = item.optInt("sortStyle", 0)))
                toAdd = hashes.toList()
                created++
            }
            if (toAdd.isNotEmpty()) {
                val startPos = dao.getMaxPosition(playlistId) + 1
                dao.addSongsToPlaylist(toAdd.mapIndexed { index, hash ->
                    PlaylistSongCrossRef(playlistId = playlistId, audioHash = hash, position = startPos + index)
                })
                dao.touchModified(playlistId, System.currentTimeMillis())
                songsAdded += toAdd.size
            }
        }
        return PlaylistsResult(created, merged, songsAdded, songsMissing,
                               if (leftoverList.length() > 0) JSONObject().put("playlists", leftoverList) else null)
    }

    // --- deferred data restore (the clean phone) ---------------------------------------------

    /** Wire [applyPending] to the end of every library scan. Called once, from the application. */
    @JvmStatic
    fun installScanHook(context: Context) {
        val app = context.applicationContext
        AudioDatabaseLoader.onLibraryScanned = { applyPending(app) }
    }

    /** What is waiting (or, for [applied], what a pass just attached): counts, never paths. */
    data class Pending(val favorites: Int = 0, val playlists: Int = 0, val songs: Int = 0) {
        val isEmpty: Boolean get() = favorites == 0 && playlists == 0 && songs == 0
    }

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The waiting restore, for the activity's status pill; null until [refreshPending] has read the files. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _applied = MutableSharedFlow<Pending>(extraBufferCapacity = 4)

    /** One event per pass of [applyPending] that attached something — the activity flashes it. */
    val applied: SharedFlow<Pending> = _applied.asSharedFlow()

    /** Re-read the pending files and publish their counts. Cheap; call from any thread. */
    fun refreshPending(context: Context) {
        var favorites = 0
        var playlists = 0
        var songs = 0
        runCatching {
            pendingFile(context, Cat.RATINGS).takeIf { it.exists() }?.readText()?.let {
                favorites = JSONObject(it).optJSONArray("favorites")?.length() ?: 0
            }
        }
        runCatching {
            pendingFile(context, Cat.PLAYLISTS).takeIf { it.exists() }?.readText()?.let {
                val list = JSONObject(it).optJSONArray("playlists") ?: JSONArray()
                playlists = list.length()
                for (i in 0 until list.length()) songs += list.optJSONObject(i)?.optJSONArray("songs")?.length() ?: 0
            }
        }
        _pending.value = Pending(favorites, playlists, songs)
    }

    private fun pendingDir(context: Context) = File(context.filesDir, PENDING_DIR)

    private fun pendingFile(context: Context, cat: Cat) = File(pendingDir(context), cat.id + ".json")

    /**
     * Fold an import's leftover into what is already waiting: favourites as a set union,
     * playlists merged by name with their owed songs appended once. A second restore before the
     * scan therefore adds to the first instead of replacing it.
     */
    private fun mergePending(context: Context, cat: Cat, leftover: JSONObject?) {
        val file = pendingFile(context, cat)
        val existing = runCatching { file.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull()
        val merged = when {
            leftover == null -> existing
            existing == null -> leftover
            cat == Cat.RATINGS -> {
                val byKey = LinkedHashMap<String, Song>()
                listOf(existing, leftover).forEach { o ->
                    val a = o.optJSONArray("favorites") ?: JSONArray()
                    for (i in 0 until a.length()) Song.fromJson(a.opt(i))?.let { byKey.putIfAbsent(it.display, it) }
                }
                JSONObject().put("favorites", JSONArray(byKey.values.map { it.toJson() }))
            }
            else -> {
                val byName = LinkedHashMap<String, JSONObject>()
                listOf(existing, leftover).forEach { o ->
                    val a = o.optJSONArray("playlists") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val item = a.optJSONObject(i) ?: continue
                        val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                        val prior = byName[name]
                        if (prior == null) {
                            byName[name] = JSONObject(item.toString())
                        } else {
                            val songs = prior.optJSONArray("songs") ?: JSONArray().also { prior.put("songs", it) }
                            val have = HashSet<String>().apply {
                                for (j in 0 until songs.length()) Song.fromJson(songs.opt(j))?.let { add(it.display) }
                            }
                            val more = item.optJSONArray("songs") ?: JSONArray()
                            for (j in 0 until more.length()) {
                                val song = Song.fromJson(more.opt(j)) ?: continue
                                if (have.add(song.display)) songs.put(song.toJson())
                            }
                        }
                    }
                }
                JSONObject().put("playlists", JSONArray(byName.values.toList()))
            }
        }
        writePending(file, merged)
        refreshPending(context)
    }

    private fun writePending(file: File, content: JSONObject?) {
        if (content == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        // Written beside and renamed over: a process killed mid-write leaves the previous
        // pending file whole rather than a truncated one that parses as nothing.
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.writeText(content.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(content.toString(2))
            tmp.delete()
        }
    }

    /**
     * Try the waiting favourites and playlist songs against the library as it now stands —
     * called by the scanner once every row has its path (`AudioDatabaseLoader.onLibraryScanned`).
     * What matches is applied and dropped from the file; what still matches nothing stays for
     * the next scan. Never re-applies a match: a song favourited by the first pass and
     * un-favourited by the user since is not in the file any more.
     */
    suspend fun applyPending(context: Context) {
        pendingLock.withLock { applyPendingLocked(context) }
    }

    private suspend fun applyPendingLocked(context: Context) {
        var attached = Pending()
        for (cat in listOf(Cat.RATINGS, Cat.PLAYLISTS)) {
            val file = pendingFile(context, cat)
            if (!file.exists()) continue
            val json = runCatching { file.readText() }.getOrNull() ?: continue
            try {
                val leftover = when (cat) {
                    Cat.RATINGS -> importRatings(context, json).also {
                        Log.i(TAG, "pending ratings: ${it.favorited} favorited, ${it.notFound} still waiting")
                        attached = attached.copy(favorites = attached.favorites + it.favorited)
                    }.leftover
                    else -> importPlaylists(context, json).also {
                        Log.i(TAG, "pending playlists: ${it.created} created, ${it.merged} merged, " +
                                "${it.songsAdded} songs added, ${it.songsMissing} still waiting")
                        attached = attached.copy(playlists = attached.playlists + it.created + it.merged,
                                                 songs = attached.songs + it.songsAdded)
                    }.leftover
                }
                writePending(file, leftover)
            } catch (e: Exception) {
                Log.e(TAG, "pending ${cat.id} could not be applied; left for the next scan", e)
            }
        }
        refreshPending(context)
        if (!attached.isEmpty) _applied.tryEmit(attached)
    }

    // --- the waiting items, for review ----------------------------------------------------------

    /** One waiting entry as the review sheet shows it; [playlist] is null for a favourite. */
    class PendingItem(val cat: Cat, val playlist: String?, val song: Song, internal val meta: JSONObject?) {
        /** Stable identity across a rewrite of the file. */
        val key: String get() = cat.id + "|" + playlist.orEmpty() + "|" + song.display
    }

    /** Everything waiting, favourites first, then playlist by playlist, in file order. */
    fun pendingItems(context: Context): List<PendingItem> {
        val out = ArrayList<PendingItem>()
        runCatching {
            pendingFile(context, Cat.RATINGS).takeIf { it.exists() }?.readText()?.let {
                val a = JSONObject(it).optJSONArray("favorites") ?: JSONArray()
                for (i in 0 until a.length()) Song.fromJson(a.opt(i))?.let { s -> out.add(PendingItem(Cat.RATINGS, null, s, null)) }
            }
        }
        runCatching {
            pendingFile(context, Cat.PLAYLISTS).takeIf { it.exists() }?.readText()?.let {
                val list = JSONObject(it).optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optString("name").takeIf { n -> n.isNotBlank() } ?: continue
                    val meta = JSONObject(item.toString()).apply { remove("songs") }
                    val songs = item.optJSONArray("songs") ?: JSONArray()
                    for (j in 0 until songs.length()) {
                        Song.fromJson(songs.opt(j))?.let { s -> out.add(PendingItem(Cat.PLAYLISTS, name, s, meta)) }
                    }
                }
            }
        }
        return out
    }

    /**
     * Library rows that could be [item]'s song: the same file name anywhere in the library,
     * compared case-insensitively and NFC-normalised. A person decides; nothing here guesses.
     */
    fun suggestions(item: PendingItem, byFileName: Map<String, List<Audio>>): List<Audio> =
        byFileName[fileKey(item.song.fileName)].orEmpty()

    /** Index the library by file name once, for [suggestions]. */
    fun indexByFileName(audios: List<Audio>): Map<String, List<Audio>> {
        val map = HashMap<String, MutableList<Audio>>()
        audios.forEach { audio ->
            val name = audio.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: docIdOf(audio.uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: audio.name?.takeIf { it.isNotBlank() }
                ?: return@forEach
            map.getOrPut(fileKey(name)) { ArrayList() }.add(audio)
        }
        return map
    }

    private fun fileKey(name: String) = nfc(name).lowercase(Locale.ROOT)

    /** Apply [item] to [audio] — favourite it, or append it to the playlist — and drop it from the file. */
    suspend fun accept(context: Context, item: PendingItem, audio: Audio) = pendingLock.withLock {
        val database = AudioDatabase.getInstance(context)
        when (item.cat) {
            Cat.RATINGS -> database.audioDao()?.setFavorite(audio.id, true)
            else -> {
                val dao = database.playlistDao()
                val name = item.playlist ?: return@withLock
                val meta = item.meta ?: JSONObject()
                val playlistId = dao.getPlaylistByName(name)?.id ?: dao.insertPlaylist(Playlist(
                        name = name,
                        description = meta.optString("description").takeIf { it.isNotBlank() && !meta.isNull("description") },
                        isPinned = meta.optBoolean("pinned", false),
                        isShuffled = meta.optBoolean("shuffled", false),
                        sortOrder = meta.optInt("sortOrder", -1),
                        sortStyle = meta.optInt("sortStyle", 0)))
                if (!dao.isSongInPlaylist(playlistId, audio.hash)) {
                    dao.addSongsToPlaylist(listOf(PlaylistSongCrossRef(playlistId = playlistId, audioHash = audio.hash,
                                                                       position = dao.getMaxPosition(playlistId) + 1)))
                    dao.touchModified(playlistId, System.currentTimeMillis())
                }
            }
        }
        removeLocked(context, setOf(item.key))
    }

    /** Drop [items] from the file without applying them — 白い熊 has said they are gone. */
    suspend fun discard(context: Context, items: Collection<PendingItem>) = pendingLock.withLock {
        removeLocked(context, items.map { it.key }.toSet())
    }

    suspend fun discardAll(context: Context) = pendingLock.withLock {
        pendingFile(context, Cat.RATINGS).delete()
        pendingFile(context, Cat.PLAYLISTS).delete()
        refreshPending(context)
    }

    private fun removeLocked(context: Context, keys: Set<String>) {
        val remaining = pendingItems(context).filterNot { it.key in keys }
        val favorites = JSONArray()
        remaining.filter { it.cat == Cat.RATINGS }.forEach { favorites.put(it.song.toJson()) }
        writePending(pendingFile(context, Cat.RATINGS),
                     if (favorites.length() > 0) JSONObject().put("favorites", favorites) else null)

        val playlists = LinkedHashMap<String, JSONObject>()
        remaining.filter { it.cat == Cat.PLAYLISTS }.forEach { item ->
            val name = item.playlist ?: return@forEach
            val entry = playlists.getOrPut(name) {
                JSONObject((item.meta ?: JSONObject()).toString()).put("name", name).put("songs", JSONArray())
            }
            entry.getJSONArray("songs").put(item.song.toJson())
        }
        writePending(pendingFile(context, Cat.PLAYLISTS),
                     if (playlists.isNotEmpty()) JSONObject().put("playlists", JSONArray(playlists.values.toList())) else null)
        refreshPending(context)
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        val bos = ByteArrayOutputStream()
        zip.getInputStream(entry).use { it.copyTo(bos) }
        return bos.toString("UTF-8")
    }

    // --- export directory + latest-export probe ----------------------------------------------

    private fun eximportPrefs(context: Context) =
        context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)

    fun getDirUri(context: Context): Uri? {
        val raw = eximportPrefs(context).getString(KEY_DIR_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun setDirUri(context: Context, uri: Uri) {
        eximportPrefs(context).edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun getExportDir(context: Context): DocumentFile? {
        val uri = getDirUri(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory }
        } catch (e: Exception) {
            null
        }
    }

    /** The newest export zip in the configured directory, or null. */
    fun latestExport(context: Context): DocumentFile? {
        val dir = getExportDir(context) ?: return null
        return try {
            dir.listFiles()
                .filter { it.isFile }
                .filter { (it.name ?: "").startsWith(EXPORT_PREFIX) && (it.name ?: "").endsWith(".zip") }
                .maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            null
        }
    }

    /** The "last export" status line — never blocks on user interaction. */
    fun lastExportStatus(context: Context): String {
        if (getExportDir(context) == null) return context.getString(R.string.sk_eim_warn_nodir)
        val newest = latestExport(context) ?: return context.getString(R.string.sk_eim_warn_none)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified()))
        return context.getString(R.string.sk_eim_last_export, ts)
    }
}
