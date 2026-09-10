package app.simple.felicity.dialogs.shiroikuma

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import app.simple.felicity.R
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.decorations.utils.SkFolderGrants
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.repository.services.AudioDatabaseService
import app.simple.felicity.theme.managers.ThemeManager
import app.simple.felicity.utils.SkFlash

/**
 * 「This copy no longer holds access to a music folder it used to」 — asked once, at launch,
 * whenever the recorded folder list names a folder this installation cannot read.
 *
 * ## Why at launch, and not when the scan finds nothing (白い熊, 2026-09-10)
 *
 * A restore brings back the folder list, the favourites and the playlists, and none of the
 * permission — a Storage Access Framework grant belongs to an installation. Without this the
 * restored app opened on an empty home screen, said nothing, and the only evidence that anything
 * was wrong was a library with no songs in it. The repair is exact: re-picking the same folder
 * yields a byte-identical tree URI, the scan runs, and whatever the restore left waiting
 * (`SkBackup.applyPending`) attaches itself to the rows the scan creates.
 *
 * Three answers: choose the folder (the picker opens *at* the missing folder), forget it (the
 * folder leaves the list — 白い熊 has said it is gone), or not now (asked again next launch; a
 * folder declined today may be wanted back tomorrow, and the cost is one glance).
 */
class SkFolderGrantDialog : DialogFragment() {

    private var body: TypeFaceTextView? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { granted: Uri? ->
        val context = context ?: return@registerForActivityResult
        if (granted != null && SkFolderGrants.persist(context, granted)) {
            SkFlash.show(context, R.string.sk_grant_regranted)
            AudioDatabaseService.startScan(context.applicationContext)
        }
        // Re-ask either way: a cancelled pick leaves the list as it was, and a successful one may
        // have covered more than the folder it was opened for.
        refresh()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = AlertDialog.Builder(context)
            .setView(infoBox())
            .setPositiveButton(R.string.sk_grant_choose, null)
            .setNeutralButton(R.string.sk_grant_forget, null)
            .setNegativeButton(R.string.sk_grant_not_now) { _, _ -> dismissAllowingStateLoss() }
            .create()
        dialog.setOnShowListener {
            style(dialog)
            // Wired here rather than in the builder so a tap never auto-dismisses: the dialog
            // stays up while the picker is out and closes only once nothing is missing.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val next = SkFolderGrants.missing(context).firstOrNull() ?: run { dismissAllowingStateLoss(); return@setOnClickListener }
                runCatching { picker.launch(SkFolderGrants.initialUriFor(next)) }
                    .onFailure { SkFlash.show(context, it.message ?: it.javaClass.simpleName) }
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                SkFolderGrants.forgetMissing(context)
                dismissAllowingStateLoss()
            }
            refresh()
        }
        return dialog
    }

    /** Redraw the list of missing folders; close when there is nothing left to ask for. */
    private fun refresh() {
        val context = context ?: return
        val missing = SkFolderGrants.missing(context)
        if (missing.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        body?.text = buildString {
            append(getString(R.string.sk_grant_body))
            append("\n")
            missing.forEach { append("\n• ").append(SkFolderGrants.displayPathOf(it)) }
        }
    }

    // ---------------------------------------------------------------- fork dialog chrome

    /** Title + body in fork colors (the window frame comes from [style]) — the Export/Import look. */
    private fun infoBox(): ScrollView {
        val context = requireContext()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        box.addView(TypeFaceTextView(context).apply {
            text = getString(R.string.sk_grant_title)
            setTextColor(accentColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
        })
        body = TypeFaceTextView(context).apply {
            setTextColor(textColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(body)
        return ScrollView(context).apply { addView(box) }
    }

    private fun style(dialog: AlertDialog) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val accent = accentColor()

        dialog.window?.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
            setColor(backgroundColor())
            cornerRadius = AppearancePreferences.getCornerRadius()
            setStroke((2 * density).toInt(), borderColor())
        }, (16 * density).toInt()))

        for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            val pill = GradientDrawable().apply {
                setColor(backgroundColor())
                cornerRadius = 50 * density // > half the height -> a pill
                setStroke((1.5F * density).toInt(), accent)
            }
            button.background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), pill, null)
            button.setTextColor(accent)
            button.transformationMethod = null // no all-caps
            button.typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
            button.setPadding(dp(20), dp(6), dp(20), dp(6))
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = dp(8)
                button.layoutParams = params
            }
        }
    }

    private fun backgroundColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BACKGROUND)
        else ThemeManager.theme.viewGroupTheme.backgroundColor

    private fun accentColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT)
        else ThemeManager.accent.primaryAccentColor

    private fun textColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_PRIMARY)
        else ThemeManager.theme.textViewTheme.primaryTextColor

    private fun borderColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BORDER)
        else ThemeManager.accent.primaryAccentColor

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "SkFolderGrantDialog"

        /** Show the gate if a recorded folder is not held; a no-op when nothing is missing or it is already up. */
        fun showIfNeeded(manager: FragmentManager, context: Context) {
            if (manager.findFragmentByTag(TAG) != null) return
            if (SkFolderGrants.missing(context).isEmpty()) return
            SkFolderGrantDialog().show(manager, TAG)
        }
    }
}
