package app.simple.felicity.ui.preferences.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import androidx.annotation.StringRes
import app.simple.felicity.R
import app.simple.felicity.databinding.FragmentShiroikumaUiBinding
import app.simple.felicity.databinding.HeaderPreferencesGenericBinding
import app.simple.felicity.databinding.ItemSkColorBinding
import app.simple.felicity.databinding.ItemSkSectionBinding
import app.simple.felicity.databinding.ItemSkSliderBinding
import app.simple.felicity.databinding.ItemSkSubgroupBinding
import app.simple.felicity.databinding.ItemSkSwitchBinding
import app.simple.felicity.databinding.ItemSkValueBinding
import app.simple.felicity.decorations.constants.TypeFaceConstants
import app.simple.felicity.decorations.corners.LayoutBackground
import app.simple.felicity.decorations.seekbars.FelicitySeekbar
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.dialogs.shiroikuma.SkFontPicker
import app.simple.felicity.dialogs.shiroikuma.SkRgbaColorPicker
import app.simple.felicity.extensions.fragments.PreferenceFragment
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.theme.managers.ShiroikumaTheme
import java.util.Locale

/**
 * 白い熊 音楽 UI — the fork's own theming page, following the layout language of
 * the sister forks (denwa/messeji): bold underlined section headings, deeply
 * indented items per level, tight rows, live preview of every change.
 */
class ShiroikumaUi : PreferenceFragment() {

    private lateinit var binding: FragmentShiroikumaUiBinding
    private lateinit var headerBinding: HeaderPreferencesGenericBinding

    private val swatches = HashMap<String, View>()
    private var fontValueView: TypeFaceTextView? = null

    private val indentStep: Int
        get() = dp(28)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentShiroikumaUiBinding.inflate(inflater, container, false)
        headerBinding = HeaderPreferencesGenericBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerBinding.title.text = getString(R.string.shiroikuma_ui)
        headerBinding.icon.setImageResource(R.drawable.ic_settings)
        binding.header.setContentView(headerBinding.root)

        buildRows()
        refreshShape()
    }

    private fun buildRows() {
        swatches.clear()
        binding.rowsContainer.removeAllViews()

        // ------------------------------------------------ General
        addSection(R.string.sk_section_general)
        addSwitchRow(R.string.sk_enable_theme, indent = 1,
                     isChecked = { ShiroikumaPreferences.isEnabled() },
                     onChecked = { checked ->
                         ShiroikumaPreferences.setEnabled(checked)
                         refreshTheme()
                     })

        // ------------------------------------------------ Colors
        addSection(R.string.sk_section_colors)

        addSubgroup(R.string.sk_group_foundation, indent = 1)
        addColorRow(R.string.sk_color_background, ShiroikumaPreferences.BACKGROUND, indent = 2)
        addColorRow(R.string.sk_color_text, ShiroikumaPreferences.TEXT, indent = 2)
        addColorRow(R.string.sk_color_accent, ShiroikumaPreferences.ACCENT, indent = 2)
        addColorRow(R.string.sk_color_border, ShiroikumaPreferences.BORDER, indent = 2)

        addSubgroup(R.string.sk_group_surfaces, indent = 1)
        addColorRow(R.string.sk_color_highlight, ShiroikumaPreferences.HIGHLIGHT, indent = 2)
        addColorRow(R.string.sk_color_selected, ShiroikumaPreferences.SELECTED, indent = 2)
        addColorRow(R.string.sk_color_divider, ShiroikumaPreferences.DIVIDER, indent = 2)
        addColorRow(R.string.sk_color_spot, ShiroikumaPreferences.SPOT, indent = 2)

        addSubgroup(R.string.sk_group_text, indent = 1)
        addColorRow(R.string.sk_color_text_header, ShiroikumaPreferences.TEXT_HEADER, indent = 2)
        addColorRow(R.string.sk_color_text_primary, ShiroikumaPreferences.TEXT_PRIMARY, indent = 2)
        addColorRow(R.string.sk_color_text_secondary, ShiroikumaPreferences.TEXT_SECONDARY, indent = 2)
        addColorRow(R.string.sk_color_text_tertiary, ShiroikumaPreferences.TEXT_TERTIARY, indent = 2)
        addColorRow(R.string.sk_color_text_quaternary, ShiroikumaPreferences.TEXT_QUATERNARY, indent = 2)

        addSubgroup(R.string.sk_group_icons, indent = 1)
        addColorRow(R.string.sk_color_icon_regular, ShiroikumaPreferences.ICON_REGULAR, indent = 2)
        addColorRow(R.string.sk_color_icon_secondary, ShiroikumaPreferences.ICON_SECONDARY, indent = 2)
        addColorRow(R.string.sk_color_icon_disabled, ShiroikumaPreferences.ICON_DISABLED, indent = 2)

        addSubgroup(R.string.sk_group_accent, indent = 1)
        addColorRow(R.string.sk_color_accent_primary, ShiroikumaPreferences.ACCENT_PRIMARY, indent = 2)
        addColorRow(R.string.sk_color_accent_secondary, ShiroikumaPreferences.ACCENT_SECONDARY, indent = 2)
        addColorRow(R.string.sk_color_switch_off, ShiroikumaPreferences.SWITCH_OFF, indent = 2)

        // ------------------------------------------------ Player
        addSection(R.string.sk_section_player)
        addColorRow(R.string.sk_color_visualizer, ShiroikumaPreferences.VISUALIZER, indent = 1)

        // ------------------------------------------------ Typography
        addSection(R.string.sk_section_typography)
        addFontRow(indent = 1)
        addSliderRow(R.string.sk_text_size, indent = 1,
                     min = 50F, max = 200F, default = 100F,
                     current = { ShiroikumaPreferences.getTextScale() * 100F },
                     label = { "${it.toInt()} %" },
                     onChange = { ShiroikumaPreferences.setTextScale(it / 100F) })
        addSliderRow(R.string.sk_font_weight, indent = 1,
                     min = -300F, max = 300F, default = 0F,
                     current = { ShiroikumaPreferences.getFontWeightDelta().toFloat() },
                     label = { String.format(Locale.ROOT, "%+d", it.toInt()) },
                     onChange = { ShiroikumaPreferences.setFontWeightDelta(it.toInt()) })

        // ------------------------------------------------ Shape & spacing
        addSection(R.string.sk_section_shape)
        addSliderRow(R.string.sk_corner_radius, indent = 1,
                     min = 0F, max = AppearancePreferences.MAX_CORNER_RADIUS, default = AppearancePreferences.DEFAULT_CORNER_RADIUS,
                     current = { AppearancePreferences.getCornerRadius() },
                     label = { it.toInt().toString() },
                     onChange = {
                         ShiroikumaPreferences.setCornerRadiusExact(it)
                         refreshShape()
                     })
        addSliderRow(R.string.sk_border_width, indent = 1,
                     min = 0F, max = ShiroikumaPreferences.MAX_BORDER_WIDTH, default = ShiroikumaPreferences.DEFAULT_BORDER_WIDTH,
                     current = { ShiroikumaPreferences.getBorderWidth() },
                     label = { if (it == 0F) getString(R.string.sk_none) else String.format(Locale.ROOT, "%.1f dp", it) },
                     onChange = {
                         ShiroikumaPreferences.setBorderWidth(it)
                         refreshShape()
                     })
        addSliderRow(R.string.sk_list_spacing, indent = 1,
                     min = 0F, max = AppearancePreferences.MAX_SPACING, default = AppearancePreferences.DEFAULT_SPACING,
                     current = { AppearancePreferences.getListSpacing() },
                     label = { it.toInt().toString() },
                     onChange = { AppearancePreferences.setListSpacing(it) })
    }

    // ------------------------------------------------------------------ row builders

    private fun addSection(@StringRes titleRes: Int) {
        val row = ItemSkSectionBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.sectionTitle.text = getString(titleRes)
        (row.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(24)
        binding.rowsContainer.addView(row.root)
    }

    private fun addSubgroup(@StringRes titleRes: Int, indent: Int) {
        val row = ItemSkSubgroupBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.subgroupTitle.text = getString(titleRes)
        (row.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(8)
        indentRow(row.root, indent)
        binding.rowsContainer.addView(row.root)
    }

    private fun addColorRow(@StringRes labelRes: Int, key: String, indent: Int) {
        val row = ItemSkColorBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.colorLabel.text = getString(labelRes)
        row.colorSwatch.background = swatchDrawable(ShiroikumaPreferences.getEffectiveColor(key))
        swatches[key] = row.colorSwatch
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            openColorPicker(labelRes, key)
        }
        binding.rowsContainer.addView(row.root)
    }

    private fun addSliderRow(@StringRes labelRes: Int, indent: Int,
                             min: Float, max: Float, default: Float,
                             current: () -> Float,
                             label: (Float) -> String,
                             onChange: (Float) -> Unit) {
        val row = ItemSkSliderBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.sliderLabel.text = getString(labelRes)
        row.sliderValue.text = label(current())
        row.sliderSeekbar.setRange(min, max)
        row.sliderSeekbar.setDefaultProgress(default)
        row.sliderSeekbar.setProgress(current())
        row.sliderSeekbar.setOnSeekChangeListener(object : FelicitySeekbar.OnSeekChangeListener {
            override fun onProgressChanged(seekbar: FelicitySeekbar, progress: Float, fromUser: Boolean) {
                if (fromUser) {
                    onChange(progress)
                    row.sliderValue.text = label(progress)
                }
            }
        })
        indentRow(row.root, indent)
        binding.rowsContainer.addView(row.root)
    }

    private fun addSwitchRow(@StringRes labelRes: Int, indent: Int,
                             isChecked: () -> Boolean,
                             onChecked: (Boolean) -> Unit) {
        val row = ItemSkSwitchBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.switchLabel.text = getString(labelRes)
        row.switchToggle.isChecked = isChecked()
        row.switchToggle.setOnCheckedChangeListener { _, checked ->
            onChecked(checked)
        }
        row.root.setOnClickListener {
            row.switchToggle.toggle()
        }
        indentRow(row.root, indent)
        binding.rowsContainer.addView(row.root)
    }

    private fun addFontRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.valueLabel.text = getString(R.string.sk_font)
        row.valueText.text = fontDisplayName(AppearancePreferences.getAppFont())
        fontValueView = row.valueText
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            val picker = SkFontPicker.newInstance()
            picker.onFontChanged = {
                fontValueView?.text = fontDisplayName(AppearancePreferences.getAppFont())
            }
            picker.show(childFragmentManager, SkFontPicker.TAG)
        }
        binding.rowsContainer.addView(row.root)
    }

    // ------------------------------------------------------------------ actions

    private fun openColorPicker(@StringRes labelRes: Int, key: String) {
        val picker = SkRgbaColorPicker.newInstance(getString(labelRes), ShiroikumaPreferences.getEffectiveColor(key))
        picker.onColorPicked = { color ->
            if (color == null) {
                ShiroikumaPreferences.clearColor(key)
            } else {
                ShiroikumaPreferences.setColor(key, color)
            }
            refreshTheme()
        }
        picker.show(childFragmentManager, SkRgbaColorPicker.TAG)
    }

    /** Re-fires the theme so every registered view repaints, then updates the page's own bits. */
    private fun refreshTheme() {
        ShiroikumaTheme.refresh(resources)
        refreshShape()
        updateSwatches()
    }

    /** Rebuilds the preview card background so corner radius and border update live. */
    private fun refreshShape() {
        LayoutBackground.setBackground(binding.previewCard)
    }

    private fun updateSwatches() {
        swatches.forEach { (key, view) ->
            view.background = swatchDrawable(ShiroikumaPreferences.getEffectiveColor(key))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun fontDisplayName(key: String): String {
        return when {
            key == TypeFaceConstants.AUTO -> getString(R.string.sk_system_default_font)
            key.startsWith(TypeFace.EXTERNAL_PREFIX) -> key.removePrefix(TypeFace.EXTERNAL_PREFIX)
            else -> TypeFace.list.firstOrNull { it.name == key }?.typefaceName ?: key
        }
    }

    private fun indentRow(view: View, indent: Int) {
        if (indent > 0) {
            view.setPaddingRelative(view.paddingStart + indent * indentStep, view.paddingTop, view.paddingEnd, view.paddingBottom)
        }
    }

    private fun swatchDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), 0x66888888)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override val wantsMiniPlayerVisible: Boolean
        get() = false

    companion object {
        fun newInstance(): ShiroikumaUi {
            val args = Bundle()
            val fragment = ShiroikumaUi()
            fragment.arguments = args
            return fragment
        }

        const val TAG = "ShiroikumaUi"
    }
}
