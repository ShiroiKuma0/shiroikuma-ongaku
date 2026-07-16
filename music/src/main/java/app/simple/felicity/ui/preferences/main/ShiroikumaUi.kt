package app.simple.felicity.ui.preferences.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
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
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.preferences.MeteorPreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.shiroikuma.PowerAmpArtImporter
import app.simple.felicity.shiroikuma.PowerAmpRatingsImporter
import app.simple.felicity.theme.managers.ShiroikumaTheme
import app.simple.felicity.theme.managers.ThemeManager
import app.simple.felicity.utils.SkFlash
import kotlinx.coroutines.launch
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
    private var tokenValueView: TypeFaceTextView? = null

    private val indentStep: Int
        get() = dp(28)

    /** Document picker for the PowerAmp `.poweramp-backup` file (extension is custom, so mime is open). */
    private val powerAmpBackupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importPowerAmpRatings(uri)
        }
    }

    /** Document picker for the PowerAmp album-art export (also a `.poweramp-backup` ZIP). */
    private val powerAmpArtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importPowerAmpArt(uri)
        }
    }

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

        // ------------------------------------------------ 音楽端灯 (edge meteors)
        addSection(R.string.sk_section_meteors)
        addSwitchRow(R.string.sk_meteor_enable, indent = 1,
                     isChecked = { MeteorPreferences.isEnabled() },
                     onChecked = { MeteorPreferences.setEnabled(it) })
        addSwitchRow(R.string.sk_meteor_overlay, indent = 1,
                     isChecked = { MeteorPreferences.isOverlayEnabled() },
                     onChecked = { checked ->
                         MeteorPreferences.setOverlayEnabled(checked)
                         // The overlay window needs SYSTEM_ALERT_WINDOW — steer to the system
                         // grant page when toggled on without it; the service skips silently
                         // until the permission is actually granted.
                         if (checked && !Settings.canDrawOverlays(requireContext())) {
                             startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                  Uri.parse("package:${requireContext().packageName}")))
                         }
                     })
        addSwitchRow(R.string.sk_meteor_reactive, indent = 1,
                     isChecked = { MeteorPreferences.isReactive() },
                     onChecked = { MeteorPreferences.setReactive(it) })
        addSwitchRow(R.string.sk_meteor_reverse, indent = 1,
                     isChecked = { MeteorPreferences.isReversed() },
                     onChecked = { MeteorPreferences.setReversed(it) })

        addSubgroup(R.string.sk_meteor_group_ribbons, indent = 1)
        addSliderRow(R.string.sk_meteor_count, indent = 2,
                     min = 1F, max = 60F, default = 18F,
                     current = { MeteorPreferences.getCount().toFloat() },
                     label = { it.toInt().toString() },
                     onChange = { MeteorPreferences.setCount(it.toInt()) })
        addSliderRow(R.string.sk_meteor_spawn_ms, indent = 2,
                     min = 10F, max = 500F, default = 60F,
                     current = { MeteorPreferences.getSpawnMs() },
                     label = { "${it.toInt()} ms" },
                     onChange = { MeteorPreferences.setSpawnMs(it) })
        addSliderRow(R.string.sk_meteor_period_s, indent = 2,
                     min = 1F, max = 30F, default = 5F,
                     current = { MeteorPreferences.getPeriodS() },
                     label = { String.format(Locale.ROOT, "%.1f s", it) },
                     onChange = { MeteorPreferences.setPeriodS(it) })
        addSliderRow(R.string.sk_meteor_min_len, indent = 2,
                     min = 0.01F, max = 0.5F, default = 0.05F,
                     current = { MeteorPreferences.getMinLen() },
                     label = { String.format(Locale.ROOT, "%.2f", it) },
                     onChange = { MeteorPreferences.setMinLen(it) })
        addSliderRow(R.string.sk_meteor_max_len, indent = 2,
                     min = 0.01F, max = 0.5F, default = 0.14F,
                     current = { MeteorPreferences.getMaxLen() },
                     label = { String.format(Locale.ROOT, "%.2f", it) },
                     onChange = { MeteorPreferences.setMaxLen(it) })

        addSubgroup(R.string.sk_meteor_group_band, indent = 1)
        addSliderRow(R.string.sk_meteor_padding, indent = 2,
                     min = 0F, max = 30F, default = 5F,
                     current = { MeteorPreferences.getPadding() },
                     label = { "${it.toInt()} px" },
                     onChange = { MeteorPreferences.setPadding(it) })
        addSliderRow(R.string.sk_meteor_radius, indent = 2,
                     min = 0F, max = 80F, default = 32F,
                     current = { MeteorPreferences.getRadius() },
                     label = { "${it.toInt()} px" },
                     onChange = { MeteorPreferences.setRadius(it) })
        addSliderRow(R.string.sk_meteor_corner_mask, indent = 2,
                     min = 0F, max = 60F, default = 18F,
                     current = { MeteorPreferences.getCornerMask() },
                     label = { "${it.toInt()} px" },
                     onChange = { MeteorPreferences.setCornerMask(it) })
        addSliderRow(R.string.sk_meteor_glow, indent = 2,
                     min = 0F, max = 40F, default = 12F,
                     current = { MeteorPreferences.getGlow() },
                     label = { "${it.toInt()} px" },
                     onChange = { MeteorPreferences.setGlow(it) })
        addSliderRow(R.string.sk_meteor_glow_layers, indent = 2,
                     min = 0F, max = 6F, default = 2F,
                     current = { MeteorPreferences.getGlowLayers().toFloat() },
                     label = { it.toInt().toString() },
                     onChange = { MeteorPreferences.setGlowLayers(it.toInt()) })
        addSliderRow(R.string.sk_meteor_glow_spread, indent = 2,
                     min = 0.5F, max = 5F, default = 2F,
                     current = { MeteorPreferences.getGlowSpread() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setGlowSpread(it) })
        addSliderRow(R.string.sk_meteor_glow_strength, indent = 2,
                     min = 0F, max = 3F, default = 1F,
                     current = { MeteorPreferences.getGlowStrength() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setGlowStrength(it) })
        addSliderRow(R.string.sk_meteor_head_glow, indent = 2,
                     min = 0F, max = 4F, default = 1.5F,
                     current = { MeteorPreferences.getHeadGlow() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setHeadGlow(it) })
        addSliderRow(R.string.sk_meteor_twinkle, indent = 2,
                     min = 0F, max = 1F, default = 0.35F,
                     current = { MeteorPreferences.getTwinkle() },
                     label = { String.format(Locale.ROOT, "%.2f", it) },
                     onChange = { MeteorPreferences.setTwinkle(it) })
        addSliderRow(R.string.sk_meteor_hue_drift, indent = 2,
                     min = 0F, max = 90F, default = 12F,
                     current = { MeteorPreferences.getHueDrift() },
                     label = { "${it.toInt()} °/s" },
                     onChange = { MeteorPreferences.setHueDrift(it) })
        addSliderRow(R.string.sk_meteor_max_fps, indent = 2,
                     min = 0F, max = 120F, default = 45F,
                     current = { MeteorPreferences.getMaxFps() },
                     label = { if (it.toInt() == 0) "vsync" else it.toInt().toString() },
                     onChange = { MeteorPreferences.setMaxFps(it) })

        addSubgroup(R.string.sk_meteor_group_reaction, indent = 1)
        addSliderRow(R.string.sk_meteor_react_gain, indent = 2,
                     min = 0F, max = 3F, default = 1F,
                     current = { MeteorPreferences.getReactGain() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setReactGain(it) })
        addSliderRow(R.string.sk_meteor_react_pulse, indent = 2,
                     min = 0F, max = 2F, default = 0.6F,
                     current = { MeteorPreferences.getReactPulse() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setReactPulse(it) })
        addSliderRow(R.string.sk_meteor_react_kick, indent = 2,
                     min = 0F, max = 3F, default = 1F,
                     current = { MeteorPreferences.getReactKick() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setReactKick(it) })
        addSliderRow(R.string.sk_meteor_react_sharp, indent = 2,
                     min = 1F, max = 12F, default = 5F,
                     current = { MeteorPreferences.getReactSharp() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setReactSharp(it) })
        addSliderRow(R.string.sk_meteor_speed_min, indent = 2,
                     min = 0F, max = 3F, default = 0F,
                     current = { MeteorPreferences.getSpeedMin() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setSpeedMin(it) })
        addSliderRow(R.string.sk_meteor_speed_max, indent = 2,
                     min = 0F, max = 3F, default = 1.5F,
                     current = { MeteorPreferences.getSpeedMax() },
                     label = { String.format(Locale.ROOT, "%.1f", it) },
                     onChange = { MeteorPreferences.setSpeedMax(it) })
        addSliderRow(R.string.sk_meteor_speed_change_s, indent = 2,
                     min = 1F, max = 20F, default = 5F,
                     current = { MeteorPreferences.getSpeedChangeS() },
                     label = { String.format(Locale.ROOT, "%.1f s", it) },
                     onChange = { MeteorPreferences.setSpeedChangeS(it) })

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

        // ------------------------------------------------ Automation (hand-off.md C)
        addSection(R.string.sk_section_automation)
        addSwitchRow(R.string.sk_automation_enable, indent = 1,
                     isChecked = { AutomationPreferences.isEnabled() },
                     onChecked = { AutomationPreferences.setEnabled(it) })
        addTokenRow(indent = 1)
        addRegenerateTokenRow(indent = 1)

        // ------------------------------------------------ Library
        addSection(R.string.sk_section_library)
        addImportPowerAmpRow(indent = 1)
        addImportPowerAmpArtRow(indent = 1)
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

    /** Shows the automation token; tapping the row copies it to the clipboard. */
    private fun addTokenRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.valueLabel.text = getString(R.string.sk_automation_token)
        row.valueText.text = AutomationPreferences.getToken()
        tokenValueView = row.valueText
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.sk_automation_token), AutomationPreferences.getToken()))
            SkFlash.show(requireContext(), R.string.sk_automation_token_copied)
        }
        binding.rowsContainer.addView(row.root)
    }

    /** Regenerates the automation token and refreshes the token row. */
    private fun addRegenerateTokenRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.valueLabel.text = getString(R.string.sk_automation_regenerate)
        row.valueText.text = ""
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            val fresh = AutomationPreferences.regenerateToken()
            tokenValueView?.text = fresh
            SkFlash.show(requireContext(), R.string.sk_automation_token_regenerated)
        }
        binding.rowsContainer.addView(row.root)
    }

    /** Opens the system document picker for a PowerAmp backup; ratings become favorites. */
    private fun addImportPowerAmpRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.valueLabel.text = getString(R.string.sk_import_poweramp)
        row.valueText.text = ""
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            // The .poweramp-backup extension carries no registered mime type, so accept anything.
            powerAmpBackupPicker.launch(arrayOf("*/*"))
        }
        binding.rowsContainer.addView(row.root)
    }

    /** Opens the system document picker for a PowerAmp album-art export; art gets embedded into files. */
    private fun addImportPowerAmpArtRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, binding.rowsContainer, false)
        row.valueLabel.text = getString(R.string.sk_import_poweramp_art)
        row.valueText.text = ""
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            // The .poweramp-backup extension carries no registered mime type, so accept anything.
            powerAmpArtPicker.launch(arrayOf("*/*"))
        }
        binding.rowsContainer.addView(row.root)
    }

    // ------------------------------------------------------------------ actions

    /**
     * Runs the PowerAmp ratings import off the main thread with a live progress
     * dialog and reports the outcome as a flash: "<rated> rated tracks: <n>
     * favorited, <m> not found". Import is additive — existing favorites are
     * never cleared.
     */
    private fun importPowerAmpRatings(uri: Uri) {
        val appContext = requireContext().applicationContext
        val progress = ImportProgressDialog()
        lifecycleScope.launch {
            try {
                val result = PowerAmpRatingsImporter.import(appContext, uri) { done, total, label ->
                    progress.update(done, total, label)
                }
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_poweramp_result,
                                       result.rated, result.favorited, result.notFound),
                             long = true)
            } catch (e: Exception) {
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_poweramp_failed, e.message ?: e.javaClass.simpleName),
                             long = true)
            }
        }
    }

    /**
     * Runs the PowerAmp album-art import off the main thread with a live progress
     * dialog and reports the outcome as a flash: "<n> art files: <x> songs updated,
     * <y> already had art, <z> albums not matched". Only songs without embedded
     * art are touched.
     */
    private fun importPowerAmpArt(uri: Uri) {
        val appContext = requireContext().applicationContext
        val progress = ImportProgressDialog()
        lifecycleScope.launch {
            try {
                val result = PowerAmpArtImporter.import(appContext, uri) { done, total, label ->
                    progress.update(done, total, label)
                }
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_poweramp_art_result,
                                       result.artFiles, result.updated, result.alreadyHadArt,
                                       result.unmatchedAlbums, result.failed),
                             long = true)
            } catch (e: Exception) {
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_poweramp_art_failed, e.message ?: e.javaClass.simpleName),
                             long = true)
            }
        }
    }

    /**
     * Small themed progress card shown while an import coroutine runs — the same
     * color/shape recipe as [SkFlash]: fork background / primary-text / border
     * colors (black card, yellow text and border by default), corner radius and
     * border width from the prefs, app typeface. Two lines: a bold "n / total"
     * counter and a smaller current-item label underneath.
     *
     * Back or an outside tap merely hides the card — the import coroutine keeps
     * running to completion and still reports its result flash; [update] on a
     * hidden dialog is harmless.
     */
    private inner class ImportProgressDialog {

        private val counterView: TypeFaceTextView
        private val labelView: TypeFaceTextView
        private val dialog: AlertDialog

        init {
            val context = requireContext()
            val density = resources.displayMetrics.density

            val backgroundColor: Int
            val textColor: Int
            val borderColor: Int
            if (ShiroikumaPreferences.isEnabled()) {
                backgroundColor = ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BACKGROUND)
                textColor = ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_PRIMARY)
                borderColor = ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BORDER)
            } else {
                backgroundColor = ThemeManager.theme.viewGroupTheme.backgroundColor
                textColor = ThemeManager.theme.textViewTheme.primaryTextColor
                borderColor = ThemeManager.accent.primaryAccentColor
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = AppearancePreferences.getCornerRadius()
                    val borderWidth = ShiroikumaPreferences.getBorderWidth()
                    if (borderWidth > 0F) {
                        setStroke((borderWidth * density).toInt().coerceAtLeast(1), borderColor)
                    }
                }
                setPadding(dp(28), dp(20), dp(28), dp(20))
            }

            counterView = TypeFaceTextView(context).apply {
                text = "…"
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
                typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
            }

            labelView = TypeFaceTextView(context).apply {
                text = ""
                setTextColor(textColor)
                alpha = 0.75F
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12F)
                typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }

            card.addView(counterView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // A fixed label width keeps the card from resizing on every update.
            card.addView(labelView, LinearLayout.LayoutParams(
                    dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

            dialog = AlertDialog.Builder(context)
                .setView(card)
                .setCancelable(true) // back/outside-tap only hides the card; the import runs on
                .create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.show()
        }

        /** Called on the main thread by the importers' onProgress callback. */
        fun update(done: Int, total: Int, label: String) {
            counterView.text = "$done / $total"
            if (label.isNotBlank()) {
                labelView.text = label
            }
        }

        fun dismiss() {
            dialog.dismiss()
        }
    }

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
