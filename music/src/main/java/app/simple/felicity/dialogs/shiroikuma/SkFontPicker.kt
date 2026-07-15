package app.simple.felicity.dialogs.shiroikuma

import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import app.simple.felicity.R
import app.simple.felicity.databinding.DialogSkFontPickerBinding
import app.simple.felicity.decorations.constants.TypeFaceConstants
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.theme.managers.ThemeManager
import java.io.File

/**
 * 白い熊 音楽 UI: font picker that renders every choice in its own glyphs —
 * the bundled Felicity fonts plus any user-imported .ttf/.otf files — and an
 * "Add font…" row that imports external fonts via the system document picker.
 *
 * Selection applies immediately (the app behind the sheet re-renders live).
 */
class SkFontPicker : ScopedBottomSheetFragment() {

    private lateinit var binding: DialogSkFontPickerBinding

    var onFontChanged: (() -> Unit)? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFont(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogSkFontPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildList()
    }

    private fun buildList() {
        binding.fontList.removeAllViews()
        val current = AppearancePreferences.getAppFont()

        addRow(getString(R.string.sk_system_default_font),
               Typeface.create(null as Typeface?, 400, false),
               selected = current == TypeFaceConstants.AUTO) {
            selectFont(TypeFaceConstants.AUTO)
        }

        TypeFace.list.forEach { model ->
            if (model.name != TypeFaceConstants.AUTO) {
                addRow(model.typefaceName,
                       TypeFace.getTypeFace(model.name, TypeFaceTextView.REGULAR, requireContext()),
                       selected = current == model.name) {
                    selectFont(model.name)
                }
            }
        }

        TypeFace.listExternalFonts(requireContext()).forEach { file ->
            val key = TypeFace.EXTERNAL_PREFIX + file.name
            addRow(file.name,
                   TypeFace.getTypeFace(key, TypeFaceTextView.REGULAR, requireContext()),
                   selected = current == key) {
                selectFont(key)
            }
        }

        addRow(getString(R.string.sk_add_font), Typeface.DEFAULT_BOLD, selected = false, accent = true) {
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun addRow(label: String, typeface: Typeface, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
        // Plain AppCompatTextView on purpose: TypeFaceTextView would override the
        // per-row typeface with the app font as soon as the preference changes.
        val row = AppCompatTextView(requireContext())
        row.text = if (selected) "●  $label" else label
        row.typeface = typeface
        row.textSize = 17F
        val paddingV = (8 * resources.displayMetrics.density).toInt()
        val paddingH = (4 * resources.displayMetrics.density).toInt()
        row.setPadding(paddingH, paddingV, paddingH, paddingV)
        row.setTextColor(
                when {
                    accent || selected -> ThemeManager.accent.primaryAccentColor
                    else -> ThemeManager.theme.textViewTheme.primaryTextColor
                })
        row.setOnClickListener { onClick() }
        binding.fontList.addView(row)
    }

    private fun selectFont(key: String) {
        AppearancePreferences.setAppFont(key)
        onFontChanged?.invoke()
        buildList()
    }

    private fun importFont(uri: android.net.Uri) {
        val resolver = requireContext().contentResolver

        var name: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }

        val fileName = name
        if (fileName == null || fileName.substringAfterLast('.', "").lowercase() !in arrayOf("ttf", "otf")) {
            Toast.makeText(requireContext(), R.string.sk_font_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val target = File(TypeFace.getExternalFontsDir(requireContext()), fileName)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open $uri")

            TypeFace.evictExternalFont(fileName)
            selectFont(TypeFace.EXTERNAL_PREFIX + fileName)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.sk_font_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        /* no-op */
    }

    companion object {
        const val TAG = "SkFontPicker"

        fun newInstance(): SkFontPicker {
            val args = Bundle()
            val fragment = SkFontPicker()
            fragment.arguments = args
            return fragment
        }
    }
}
