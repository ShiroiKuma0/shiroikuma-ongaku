package app.simple.felicity.dialogs.shiroikuma

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.DialogSkAlbumArtSearchBinding
import app.simple.felicity.decorations.ripple.DynamicRippleLinearLayout
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.metadata.MetadataWriter
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.models.MusicBrainzReleaseCandidate
import app.simple.felicity.repository.repositories.MusicBrainzRepository
import app.simple.felicity.theme.managers.ThemeManager
import app.simple.felicity.utils.SkFlash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fork (白い熊 音楽): the INTERACTIVE "Download album art" sheet — opened from the
 * song context menu (embeds into that one file) and from the album page menu
 * (embeds into every file of the album), including songs/albums that already
 * have art ([MetadataWriter.writeArtwork] replaces the existing front cover).
 *
 * Flow: the sheet opens seeded with the artist + album (both editable), fires
 * one MusicBrainz release search (up to [CANDIDATE_LIMIT] candidates, paced to
 * the 1 request/second policy) and resolves each candidate's Cover Art Archive
 * front-250 thumbnail sequentially off the main thread — candidates without a
 * cover are dropped as the results resolve. Tapping a thumbnail shows the
 * confirmation step: the front-500 preview plus "Embed into N file(s)?" with
 * Apply/Cancel. Only Apply embeds — per file: TagLib write, dateModified bump +
 * Room update (Glide's cache key changes with it) and one MediaScanner batch
 * scan at the end, exactly like [app.simple.felicity.shiroikuma.AlbumArtDownloader].
 * Per-file errors never crash the run; network errors surface as a [SkFlash].
 */
class SkAlbumArtSearch : ScopedBottomSheetFragment() {

    private lateinit var binding: DialogSkAlbumArtSearchBinding

    /**
     * The files the chosen cover is embedded into — one song from the song menu,
     * the whole album from the album page. Set by the caller right after
     * [newInstance]; deliberately NOT parceled (an album can be huge), so after
     * process death the sheet simply closes itself.
     */
    var targetSongs: List<Audio>? = null

    private val repository by lazy { MusicBrainzRepository(requireContext().applicationContext) }

    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var embedJob: Job? = null

    /** Every cache file this sheet created — cleaned up in [onDestroy]. */
    private val cacheFiles = mutableListOf<File>()

    /** Manual source 1: the system document picker (same pattern as SkFontPicker). */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showManualConfirmation(getString(R.string.sk_art_source_storage)) { decodeUriScaled(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogSkAlbumArtSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (targetSongs.isNullOrEmpty()) {
            // Recreated after process death — the embed target is gone; bow out.
            dismissAllowingStateLoss()
            return
        }

        binding.albumField.setText(requireArguments().getString(ARG_ALBUM).orEmpty())
        binding.artistField.setText(requireArguments().getString(ARG_ARTIST).orEmpty())

        binding.buttonSearch.setOnClickListener { startSearch() }
        binding.buttonPickImage.setOnClickListener { pickImageLauncher.launch(arrayOf("image/*")) }
        binding.buttonPasteImage.setOnClickListener { pasteImage() }
        binding.artistField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }

        startSearch()
    }

    // ------------------------------------------------------------------ step 1: search + candidate grid

    private fun startSearch() {
        searchJob?.cancel()
        previewJob?.cancel()
        showGroup(binding.searchGroup)
        binding.resultsContainer.removeAllViews()

        val albumQuery = binding.albumField.text?.toString()?.trim().orEmpty()
        val artistQuery = binding.artistField.text?.toString()?.trim().orEmpty()
        if (albumQuery.isEmpty()) {
            binding.searchStatus.text = getString(R.string.sk_art_no_results)
            return
        }
        binding.searchStatus.text = getString(R.string.sk_art_searching)

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // One MusicBrainz request — paced to the 1 req/s policy across searches.
                val candidates = withContext(Dispatchers.IO) {
                    val wait = lastMusicBrainzCall + MUSIC_BRAINZ_MIN_INTERVAL_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    lastMusicBrainzCall = System.currentTimeMillis()
                    repository.searchReleaseCandidates(albumQuery, artistQuery, CANDIDATE_LIMIT)
                }

                if (candidates.isEmpty()) {
                    binding.searchStatus.text = getString(R.string.sk_art_no_results)
                    return@launch
                }

                // Resolve the CAA front-250 thumbnails one by one (off-main);
                // candidates without a cover are dropped as the results come in.
                var shown = 0
                for (candidate in candidates) {
                    val thumbFile = cacheFile("thumb", candidate.releaseMbid)
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            if (repository.downloadCoverThumbnail(candidate.toCoverIds(), thumbFile) &&
                                    thumbFile.length() > 0L) {
                                BitmapFactory.decodeFile(thumbFile.absolutePath)
                            } else {
                                null // 404 — this release has no cover
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Thumbnail fetch failed for ${candidate.releaseMbid}", e)
                            null // network trouble on one thumbnail only drops that candidate
                        }
                    } ?: continue

                    addCandidateTile(candidate, bitmap, thumbFile)
                    shown++
                    binding.searchStatus.text = getString(R.string.sk_art_candidates, shown)
                }

                if (shown == 0) {
                    binding.searchStatus.text = getString(R.string.sk_art_no_results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover search failed", e)
                binding.searchStatus.text = getString(R.string.sk_art_no_results)
                SkFlash.show(requireContext(),
                             getString(R.string.sk_art_network_error, e.message ?: e.javaClass.simpleName),
                             long = true)
            }
        }
    }

    /** Adds one thumbnail tile (cover + title + year/country line) to the two-column grid. */
    private fun addCandidateTile(candidate: MusicBrainzReleaseCandidate, bitmap: Bitmap, thumbFile: File) {
        val row = currentGridRow()

        val tile = DynamicRippleLinearLayout(requireContext(), null).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val image = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            setImageBitmap(bitmap)
        }
        tile.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))

        val title = TypeFaceTextView(requireContext()).apply {
            text = candidate.title ?: getString(R.string.sk_none)
            textSize = 13F
            maxLines = 2
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(ThemeManager.theme.textViewTheme.primaryTextColor)
        }
        tile.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val subtitleText = detailLine(candidate)
        if (subtitleText.isNotEmpty()) {
            val subtitle = TypeFaceTextView(requireContext()).apply {
                text = subtitleText
                textSize = 11F
                maxLines = 1
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(ThemeManager.theme.textViewTheme.secondaryTextColor)
            }
            tile.addView(subtitle, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        tile.setOnClickListener { showConfirmation(candidate, thumbFile) }

        row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F))
    }

    /** Returns the last grid row if it still has room, otherwise starts a new one. */
    private fun currentGridRow(): LinearLayout {
        val last = binding.resultsContainer.children().lastOrNull() as? LinearLayout
        if (last != null && last.childCount < GRID_COLUMNS) return last
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        binding.resultsContainer.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** "2004 · US · Label" — whichever parts the search result carried. */
    private fun detailLine(candidate: MusicBrainzReleaseCandidate): String {
        return listOfNotNull(candidate.date?.take(4), candidate.country, candidate.label)
            .joinToString(" · ")
    }

    // ------------------------------------------------------------------ step 2: confirmation

    private fun showConfirmation(candidate: MusicBrainzReleaseCandidate, thumbFile: File) {
        previewJob?.cancel()
        showGroup(binding.confirmGroup)

        val songCount = targetSongs?.size ?: 0
        binding.confirmDetail.text = listOfNotNull(candidate.title, detailLine(candidate).takeIf { it.isNotEmpty() })
            .joinToString(" — ")
        binding.confirmPrompt.text = getString(R.string.sk_art_loading_preview)
        binding.buttonApply.visibility = View.INVISIBLE
        binding.buttonCancel.setOnClickListener {
            previewJob?.cancel()
            showGroup(binding.searchGroup)
        }

        // The thumbnail fills the frame instantly; the front-500 replaces it when ready.
        binding.preview.setImageBitmap(BitmapFactory.decodeFile(thumbFile.absolutePath))

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val fullFile = cacheFile("full", candidate.releaseMbid)
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    if (repository.downloadCoverArt(candidate.toCoverIds(), fullFile) && fullFile.length() > 0L) {
                        BitmapFactory.decodeFile(fullFile.absolutePath)
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Full cover fetch failed for ${candidate.releaseMbid}", e)
                    null
                }
            }

            if (bitmap == null) {
                SkFlash.show(requireContext(), R.string.sk_art_preview_failed, long = true)
                showGroup(binding.searchGroup)
                return@launch
            }

            binding.preview.setImageBitmap(bitmap)
            binding.confirmPrompt.text = getString(R.string.sk_art_embed_prompt, songCount)
            binding.buttonApply.visibility = View.VISIBLE
            binding.buttonApply.setOnClickListener { embed(fullFile) }
        }
    }

    // ------------------------------------------------------------------ step 2b: manual sources (storage picker + clipboard)

    /**
     * Manual source 2: the clipboard. Handles an image-uri clip (screenshots,
     * gallery "copy", file managers) and a text clip holding an image URL or a
     * filesystem path. Anything else flashes an error and stays on the search step.
     */
    private fun pasteImage() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val text = item?.text?.toString()?.trim()

        when {
            uri != null -> {
                showManualConfirmation(getString(R.string.sk_art_source_clipboard)) { decodeUriScaled(uri) }
            }
            !text.isNullOrEmpty() -> {
                showManualConfirmation(getString(R.string.sk_art_source_clipboard)) { decodeClipboardText(text) }
            }
            else -> {
                SkFlash.show(requireContext(), R.string.sk_art_clipboard_no_image, long = true)
            }
        }
    }

    /**
     * Shows the SAME confirmation step as a tapped search result, fed by [decode]
     * (run off-main). The decoded bitmap is transcoded to a JPEG-[JPEG_QUALITY]
     * cache file — [MetadataWriter.writeArtwork] then embeds that file exactly
     * like a downloaded cover. Undecodable data flashes [R.string.sk_art_image_invalid]
     * and returns to the search step.
     */
    private fun showManualConfirmation(sourceLabel: String, decode: () -> Bitmap?) {
        previewJob?.cancel()
        showGroup(binding.confirmGroup)

        binding.preview.setImageDrawable(null)
        binding.confirmDetail.text = sourceLabel
        binding.confirmPrompt.text = getString(R.string.sk_art_loading_preview)
        binding.buttonApply.visibility = View.INVISIBLE
        binding.buttonCancel.setOnClickListener {
            previewJob?.cancel()
            showGroup(binding.searchGroup)
        }

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val artFile = cacheFile("manual", System.currentTimeMillis().toString())
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val source = decode() ?: return@withContext null
                    // Transcode to JPEG 90 — writeArtwork embeds the file bytes as-is,
                    // so this normalizes PNG/WebP/HEIC picks (and strips alpha) once.
                    artFile.outputStream().use { out ->
                        if (!source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                            return@withContext null
                        }
                    }
                    source
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Manual image ($sourceLabel) failed", e)
                    null
                }
            }

            if (bitmap == null || artFile.length() == 0L) {
                SkFlash.show(requireContext(), R.string.sk_art_image_invalid, long = true)
                showGroup(binding.searchGroup)
                return@launch
            }

            binding.preview.setImageBitmap(bitmap)
            binding.confirmPrompt.text = getString(R.string.sk_art_embed_prompt, targetSongs?.size ?: 0)
            binding.buttonApply.visibility = View.VISIBLE
            binding.buttonApply.setOnClickListener { embed(artFile) }
        }
    }

    /** Resolves a clipboard TEXT clip: http(s) URL → download, uri/path → local decode. */
    private fun decodeClipboardText(text: String): Bitmap? {
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> {
                val download = cacheFile("clip", System.currentTimeMillis().toString())
                val connection = URL(text).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                    connection.inputStream.use { input ->
                        download.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }
                decodeFileScaled(download)
            }
            text.startsWith("content://") || text.startsWith("file://") -> {
                decodeUriScaled(text.toUri())
            }
            else -> {
                File(text).takeIf { it.isFile }?.let { decodeFileScaled(it) }
            }
        }
    }

    /** Decodes [uri] via the content resolver, downsampled to ~[MAX_DECODE_DIMENSION] px. */
    private fun decodeUriScaled(uri: Uri): Bitmap? {
        val resolver = requireContext().applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Decodes [file], downsampled to ~[MAX_DECODE_DIMENSION] px. */
    private fun decodeFileScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /** Power-of-two sample size keeping the longest side at ~[MAX_DECODE_DIMENSION] px. */
    private fun sampleSize(bounds: BitmapFactory.Options): Int {
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= MAX_DECODE_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    // ------------------------------------------------------------------ step 3: embed

    /**
     * Embeds [artFile] into every target file — [MetadataWriter.writeArtwork]
     * REPLACES any existing front cover, so this works for songs that already
     * have art. Mirrors AlbumArtDownloader's refresh bookkeeping per file
     * (dateModified bump + Room update) and batch-scans at the end, even when
     * the run is interrupted midway.
     */
    private fun embed(artFile: File) {
        if (embedJob != null) return
        val songs = targetSongs ?: return
        showGroup(binding.progressGroup)
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        val appContext = requireContext().applicationContext

        embedJob = viewLifecycleOwner.lifecycleScope.launch {
            var wrote = 0
            var failed = 0

            withContext(Dispatchers.IO) {
                val dao = AudioDatabase.getInstance(appContext).audioDao()
                val scanPaths = mutableListOf<String>()
                try {
                    for ((index, audio) in songs.withIndex()) {
                        withContext(Dispatchers.Main) {
                            binding.progressCounter.text = "${index + 1} / ${songs.size}"
                            binding.progressLabel.text = audio.title ?: audio.uri.orEmpty()
                        }
                        val uriString = audio.uri
                        if (uriString == null) {
                            failed++
                            continue
                        }
                        try {
                            if (MetadataWriter.writeArtwork(uriString.toUri(), artFile, appContext.contentResolver)) {
                                wrote++
                                // Same refresh as AlbumArtDownloader: dateModified bump in Room
                                // (Glide's ObjectKey(audio) changes with it) + rescan below.
                                audio.dateModified = System.currentTimeMillis() / 1000L
                                dao?.update(audio)
                                scanPaths.add(uriString)
                            } else {
                                failed++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed embedding art into $uriString", e)
                            failed++
                        }
                    }
                } finally {
                    if (scanPaths.isNotEmpty()) {
                        MediaScannerConnection.scanFile(appContext, scanPaths.toTypedArray(), null, null)
                    }
                }
            }

            SkFlash.show(appContext,
                         if (failed == 0) {
                             appContext.getString(R.string.sk_art_embed_result, wrote)
                         } else {
                             appContext.getString(R.string.sk_art_embed_result_failed, wrote, failed)
                         },
                         long = true)
            dismissAllowingStateLoss()
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Shows exactly one of the three step groups. */
    private fun showGroup(group: View) {
        binding.searchGroup.visibility = if (group === binding.searchGroup) View.VISIBLE else View.GONE
        binding.confirmGroup.visibility = if (group === binding.confirmGroup) View.VISIBLE else View.GONE
        binding.progressGroup.visibility = if (group === binding.progressGroup) View.VISIBLE else View.GONE
    }

    private fun cacheFile(kind: String, mbid: String): File {
        val file = File(requireContext().cacheDir, "sk-art-$kind-$mbid.jpg")
        cacheFiles.add(file)
        return file
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cacheFiles.forEach { it.delete() }
    }

    companion object {
        const val TAG = "SkAlbumArtSearch"

        private const val ARG_ALBUM = "album"
        private const val ARG_ARTIST = "artist"

        /** How many candidate releases one search asks MusicBrainz for. */
        private const val CANDIDATE_LIMIT = 8

        /** Thumbnails per grid row. */
        private const val GRID_COLUMNS = 2

        /** MusicBrainz allows 1 request/second — leave a little headroom. */
        private const val MUSIC_BRAINZ_MIN_INTERVAL_MS = 1100L

        /** JPEG quality for transcoding manually picked/pasted images. */
        private const val JPEG_QUALITY = 90

        /** Longest side manual decodes are downsampled to (embed-friendly, OOM-safe). */
        private const val MAX_DECODE_DIMENSION = 2048

        /** Last MusicBrainz call across sheet instances, for the rate gate. */
        @Volatile
        private var lastMusicBrainzCall = 0L

        fun newInstance(album: String, artist: String): SkAlbumArtSearch {
            val args = Bundle()
            args.putString(ARG_ALBUM, album)
            args.putString(ARG_ARTIST, artist)
            val fragment = SkAlbumArtSearch()
            fragment.arguments = args
            return fragment
        }
    }
}
