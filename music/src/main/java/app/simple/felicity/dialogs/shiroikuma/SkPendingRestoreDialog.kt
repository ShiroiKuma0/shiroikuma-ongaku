package app.simple.felicity.dialogs.shiroikuma

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.shiroikuma.SkBackup
import app.simple.felicity.utils.SkFlash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The restore's waiting room — every favourite and playlist song a restore could not attach,
 * shown with what the library has under the same file name, so 白い熊 decides each one.
 *
 * ## Why a person decides (白い熊, 2026-09-10)
 *
 * After a full scan on the new phone five favourites still waited, files present and scanned.
 * The automatic pass now matches on the document id and finds them, but the residue of any
 * restore is by definition the cases the rules could not settle: a file renamed, moved, or
 * re-tagged. Guessing there would put the wrong song in a playlist silently; a list with
 * 「Use」 and 「Discard」 next to each candidate costs one glance and never invents a match.
 *
 * Opened from the status pill above the mini player and from the UI page's Export/Import section.
 */
class SkPendingRestoreDialog : DialogFragment() {

    private lateinit var list: LinearLayout
    private var useAll: View? = null

    /** The one-candidate suggestions on screen, for 「Use all suggestions」. */
    private val unique = ArrayList<Pair<SkBackup.PendingItem, Audio>>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dp = { v: Int -> SkDialogChrome.dp(context, v) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        box.addView(SkDialogChrome.text(context, getString(R.string.sk_pending_title), 18F, TypeFaceTextView.BOLD, SkDialogChrome.accentColor()))
        box.addView(SkDialogChrome.text(context, getString(R.string.sk_pending_body), 13F).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)

        val dialog = AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.sk_pending_retry, null)
            .setNeutralButton(R.string.sk_pending_use_all, null)
            .setNegativeButton(R.string.sk_pending_close) { _, _ -> dismissAllowingStateLoss() }
            .create()
        dialog.setOnShowListener {
            SkDialogChrome.style(dialog)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { retry() }
            useAll = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.also { button ->
                button.setOnClickListener { useAllSuggestions() }
            }
            reload()
        }
        return dialog
    }

    /** Re-read the files and the library, rebuild the rows; close when nothing is left. */
    private fun reload() {
        val context = context ?: return
        val app = context.applicationContext
        lifecycleScope.launch {
            val (items, byName) = withContext(Dispatchers.IO) {
                val items = SkBackup.pendingItems(app)
                val audios = AudioDatabase.getInstance(app).audioDao()?.getAllAudioListAll().orEmpty()
                items to SkBackup.indexByFileName(audios)
            }
            if (!isAdded) return@launch
            if (items.isEmpty()) {
                SkFlash.show(app, R.string.sk_pending_none)
                dismissAllowingStateLoss()
                return@launch
            }
            render(items, byName)
        }
    }

    private fun render(items: List<SkBackup.PendingItem>, byName: Map<String, List<Audio>>) {
        val context = requireContext()
        val dp = { v: Int -> SkDialogChrome.dp(context, v) }
        list.removeAllViews()
        unique.clear()

        var heading: String? = null
        items.forEach { item ->
            val group = if (item.cat == SkBackup.Cat.RATINGS) getString(R.string.sk_eim_cat_ratings)
            else getString(R.string.sk_pending_playlist, item.playlist)
            if (group != heading) {
                heading = group
                list.addView(SkDialogChrome.text(context, group, 15F, TypeFaceTextView.BOLD, SkDialogChrome.accentColor()).apply {
                    setPadding(0, dp(10), 0, dp(4))
                })
            }
            val candidates = SkBackup.suggestions(item, byName)
            if (candidates.size == 1) unique.add(item to candidates[0])
            list.addView(row(item, candidates))
        }
        // The blunt instrument, at the end of the list where it is read last.
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, dp(4))
            addView(SkDialogChrome.pill(context, getString(R.string.sk_pending_discard_all)) { discardAll() })
        })
        useAll?.visibility = if (unique.isEmpty()) View.GONE else View.VISIBLE
    }

    /** One waiting song: its file name and folder, the library's answer, and the two buttons. */
    private fun row(item: SkBackup.PendingItem, candidates: List<Audio>): View {
        val context = requireContext()
        val dp = { v: Int -> SkDialogChrome.dp(context, v) }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(SkDialogChrome.backgroundColor())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), SkDialogChrome.borderColor())
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        card.addView(SkDialogChrome.text(context, item.song.fileName, 14F, TypeFaceTextView.BOLD))
        card.addView(SkDialogChrome.text(context, item.song.display.substringBeforeLast('/', ""), 11F,
                                         TypeFaceTextView.REGULAR, SkDialogChrome.secondaryTextColor()))

        val answer = when (candidates.size) {
            0 -> getString(R.string.sk_pending_no_match)
            1 -> getString(R.string.sk_pending_one_match, describe(candidates[0]))
            else -> getString(R.string.sk_pending_many_matches, candidates.size)
        }
        card.addView(SkDialogChrome.text(context, answer, 12F, TypeFaceTextView.MEDIUM,
                                         if (candidates.isEmpty()) SkDialogChrome.secondaryTextColor() else SkDialogChrome.accentColor()).apply {
            setPadding(0, dp(6), 0, dp(2))
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        when (candidates.size) {
            1 -> buttons.addView(SkDialogChrome.pill(context, getString(R.string.sk_pending_use)) { accept(item, candidates[0]) })
            0 -> Unit
            else -> buttons.addView(SkDialogChrome.pill(context, getString(R.string.sk_pending_choose)) { choose(item, candidates) })
        }
        buttons.addView(SkDialogChrome.pill(context, getString(R.string.sk_pending_discard)) { discard(item) }.apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)).let { lp ->
                lp.marginStart = dp(8)
                layoutParams = lp
            }
        })
        card.addView(buttons)
        return card
    }

    private fun describe(audio: Audio): String {
        val title = audio.title?.takeIf { it.isNotBlank() } ?: audio.name.orEmpty()
        val artist = audio.artist?.takeIf { it.isNotBlank() }
        val where = audio.path?.takeIf { it.isNotBlank() } ?: SkBackup.docIdOf(audio.uri)?.let { SkBackup.pathFromDoc(it) } ?: ""
        return buildString {
            append(title)
            if (artist != null) append(" — ").append(artist)
            if (where.isNotEmpty()) append('\n').append(where)
        }
    }

    /** Several rows share the file name: a plain list to pick from, or nothing. */
    private fun choose(item: SkBackup.PendingItem, candidates: List<Audio>) {
        val context = context ?: return
        val dialog = AlertDialog.Builder(context)
            .setItems(candidates.map { describe(it) }.toTypedArray()) { _, which -> accept(item, candidates[which]) }
            .setNegativeButton(R.string.sk_cancel, null)
            .create()
        dialog.setOnShowListener { SkDialogChrome.style(dialog) }
        dialog.show()
    }

    private fun accept(item: SkBackup.PendingItem, audio: Audio) {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SkBackup.accept(app, item, audio) }
            reload()
        }
    }

    private fun discard(item: SkBackup.PendingItem) {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SkBackup.discard(app, listOf(item)) }
            reload()
        }
    }

    private fun discardAll() {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SkBackup.discardAll(app) }
            reload()
        }
    }

    private fun useAllSuggestions() {
        val app = context?.applicationContext ?: return
        val batch = unique.toList()
        if (batch.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { batch.forEach { (item, audio) -> SkBackup.accept(app, item, audio) } }
            SkFlash.show(app, getString(R.string.sk_pending_used_n, batch.size))
            reload()
        }
    }

    /** Run the automatic pass again, now — the same one the end of a scan runs. */
    private fun retry() {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SkBackup.applyPending(app) }
            reload()
        }
    }

    companion object {
        const val TAG = "SkPendingRestoreDialog"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            SkPendingRestoreDialog().show(manager, TAG)
        }
    }
}
