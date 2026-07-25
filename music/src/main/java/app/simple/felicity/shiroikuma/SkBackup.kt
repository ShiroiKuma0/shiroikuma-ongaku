package app.simple.felicity.shiroikuma

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import app.simple.felicity.R
import app.simple.felicity.manager.SharedPreferences.getSharedPreference
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Playlist
import app.simple.felicity.repository.models.PlaylistSongCrossRef
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
 */
object SkBackup {

    const val FORMAT = "shiroikuma-ongaku-export"
    const val VERSION = 1
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
    private val APP_EXCLUDE = setOf(
            "saf_granted_tree_uris", // SAF grants are permission-bound to this install
            "crash_timestamp", "crash_log")

    /**
     * Keys excluded from EVERY category. The automation shared secret must never travel in
     * a backup zip (保存復元 contract §2): a zip is copied around and restored on other
     * installs, and an imported token would silently break the pairing with 自由作業盤,
     * which keeps its own copy of it.
     */
    private val NEVER_EXPORT = setOf(AutomationPreferences.TOKEN)

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

    /** The selectable categories; [id] doubles as the JSON entry name inside the zip. */
    enum class Cat(val id: String, @field:StringRes val labelRes: Int) {
        // declaration order is dialog order
        UI("ui_theme", R.string.sk_eim_cat_ui),
        METEORS("meteors", R.string.sk_eim_cat_meteors),
        AUTOMATION("automation", R.string.sk_eim_cat_automation),
        APP("app_settings", R.string.sk_eim_cat_app),
        RATINGS("ratings", R.string.sk_eim_cat_ratings),
        PLAYLISTS("playlists", R.string.sk_eim_cat_playlists)
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
            Cat.AUTOMATION -> isAutomationKey(key)
            Cat.APP -> !isUiKey(key) && !isMeteorKey(key) && !isAutomationKey(key) && key !in APP_EXCLUDE
            else -> false // RATINGS / PLAYLISTS are not prefs-based
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
     */
    suspend fun export(context: Context, cats: List<Cat>, out: OutputStream, progress: Progress? = null) {
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
                val position = index + 1L
                progress?.onProgress(position, total, UNIT_CATEGORY,
                                     "$UNIT_CATEGORY $position/$total — " + context.getString(cat.labelRes))
                val content = when (cat) {
                    Cat.RATINGS -> exportRatings(context, progress)
                    Cat.PLAYLISTS -> exportPlaylists(context, progress)
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
    private fun exportRatings(context: Context, progress: Progress? = null): String {
        val dao = AudioDatabase.getInstance(context).audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val all = dao.getAllAudioListAll()
        val total = all.size.toLong()
        val a = JSONArray()
        var done = 0L
        all.forEach { audio ->
            if (audio.isFavorite && !audio.path.isNullOrBlank()) a.put(audio.path)
            done++
            progress?.onProgress(done, total, UNIT_SONG, "$UNIT_SONG $done/$total")
        }
        return JSONObject().put("favorites", a).toString(2)
    }

    /**
     * Every user-created playlist (M3U-scanned ones are skipped — they regenerate from
     * their files) with its metadata and member song paths in manual order.
     */
    private suspend fun exportPlaylists(context: Context, progress: Progress? = null): String {
        val dao = AudioDatabase.getInstance(context).playlistDao()
        val list = JSONArray()
        val own = dao.getAllPlaylists().first().filterNot { it.isM3UPlaylist }
        val total = own.size.toLong()
        var done = 0L
        for (playlist in own) {
            done++
            progress?.onProgress(done, total, UNIT_PLAYLIST, "$UNIT_PLAYLIST $done/$total")
            val songs = JSONArray()
            dao.getSongsInPlaylistOrdered(playlist.id).first().forEach { audio ->
                if (!audio.path.isNullOrBlank()) songs.put(audio.path)
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
    suspend fun import(context: Context, zip: ZipFile, cats: List<Cat>): String? {
        if (zip.getEntry("manifest.json") == null) return null

        val summary = StringBuilder()
        var any = false
        for (cat in cats) {
            val data = readEntry(zip, cat.id + ".json") ?: continue
            val line = when (cat) {
                Cat.RATINGS -> importRatings(context, data)
                Cat.PLAYLISTS -> importPlaylists(context, data)
                else -> context.getString(R.string.sk_eim_keys_applied, importPrefs(context, data, cat))
            }
            any = true
            if (summary.isNotEmpty()) summary.append('\n')
            summary.append(context.getString(cat.labelRes)).append(": ").append(line)
        }
        return if (any) summary.toString() else null
    }

    /** Per-key merge — never clears, so unrelated/device-local keys survive. Returns keys applied. */
    private fun importPrefs(context: Context, json: String, cat: Cat): Int {
        val obj = JSONObject(json)
        val ed = getSharedPreference(context).edit()
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
                    ed.putStringSet(k, set)
                }
                else -> continue
            }
            count++
        }
        ed.apply()
        return count
    }

    /**
     * Additive favorites restore: every exported path that matches a library song gets
     * {@code is_favorite = 1}; nothing is ever un-favorited.
     */
    private suspend fun importRatings(context: Context, json: String): String {
        val dao = AudioDatabase.getInstance(context).audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val paths = JSONObject(json).optJSONArray("favorites") ?: JSONArray()

        val exact = HashMap<String, Long>()
        val nfc = HashMap<String, Long>()
        dao.getAllAudioListAll().forEach { audio ->
            val p = audio.path ?: return@forEach
            exact.putIfAbsent(p, audio.id)
            nfc.putIfAbsent(Normalizer.normalize(p, Normalizer.Form.NFC), audio.id)
        }

        var favorited = 0
        var notFound = 0
        for (i in 0 until paths.length()) {
            val p = paths.optString(i)
            if (p.isNullOrBlank()) continue
            val id = exact[p] ?: nfc[Normalizer.normalize(p, Normalizer.Form.NFC)]
            if (id != null) {
                dao.setFavorite(id, true)
                favorited++
            } else {
                notFound++
            }
        }
        return context.getString(R.string.sk_eim_ratings_result, favorited, notFound)
    }

    /**
     * Merge-import of playlists: a playlist whose name already exists gets the missing
     * songs appended at its end; unknown names are created with the exported metadata.
     * Songs are matched by path; misses are counted, never invented.
     */
    private suspend fun importPlaylists(context: Context, json: String): String {
        val database = AudioDatabase.getInstance(context)
        val audioDao = database.audioDao()
            ?: throw IllegalStateException("Music library database is not available")
        val dao = database.playlistDao()

        val exact = HashMap<String, Long>() // path -> audio hash
        val nfc = HashMap<String, Long>()
        audioDao.getAllAudioListAll().forEach { audio ->
            val p = audio.path ?: return@forEach
            exact.putIfAbsent(p, audio.hash)
            nfc.putIfAbsent(Normalizer.normalize(p, Normalizer.Form.NFC), audio.hash)
        }

        val list = JSONObject(json).optJSONArray("playlists") ?: JSONArray()
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
            for (j in 0 until songs.length()) {
                val p = songs.optString(j)
                if (p.isNullOrBlank()) continue
                val hash = exact[p] ?: nfc[Normalizer.normalize(p, Normalizer.Form.NFC)]
                if (hash != null) hashes.add(hash) else songsMissing++
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
        return context.getString(R.string.sk_eim_playlists_result, created, merged, songsAdded, songsMissing)
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
