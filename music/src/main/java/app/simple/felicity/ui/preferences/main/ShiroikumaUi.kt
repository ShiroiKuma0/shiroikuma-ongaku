package app.simple.felicity.ui.preferences.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.FragmentShiroikumaUiBinding
import app.simple.felicity.databinding.HeaderPreferencesGenericBinding
import app.simple.felicity.databinding.ItemSkActionBinding
import app.simple.felicity.databinding.ItemSkColorBinding
import app.simple.felicity.databinding.ItemSkPreviewBinding
import app.simple.felicity.databinding.ItemSkSectionBinding
import app.simple.felicity.databinding.ItemSkSliderBinding
import app.simple.felicity.databinding.ItemSkSubgroupBinding
import app.simple.felicity.databinding.ItemSkSwitchBinding
import app.simple.felicity.databinding.ItemSkSwitchDescribedBinding
import app.simple.felicity.databinding.ItemSkValueBinding
import app.simple.felicity.decorations.constants.TypeFaceConstants
import app.simple.felicity.decorations.corners.LayoutBackground
import app.simple.felicity.decorations.seekbars.FelicitySeekbar
import app.simple.felicity.decorations.shiroikuma.SkPills
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.dialogs.shiroikuma.SkDialogChrome
import app.simple.felicity.dialogs.shiroikuma.SkFontPicker
import app.simple.felicity.dialogs.shiroikuma.SkPendingRestoreDialog
import app.simple.felicity.dialogs.shiroikuma.SkRgbaColorPicker
import app.simple.felicity.extensions.fragments.PreferenceFragment
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.preferences.MeteorPreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.shared.shiroikuma.SkDiag
import app.simple.felicity.shiroikuma.AlbumArtDownloader
import app.simple.felicity.shiroikuma.PowerAmpArtImporter
import app.simple.felicity.shiroikuma.PowerAmpRatingsImporter
import app.simple.felicity.shiroikuma.SkBackup
import app.simple.felicity.shiroikuma.SkFlowLayout
import app.simple.felicity.theme.managers.ShiroikumaTheme
import app.simple.felicity.theme.managers.ThemeManager
import app.simple.felicity.ui.panels.Preferences
import app.simple.felicity.utils.SkFlash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 白い熊 音楽 UI — the fork's own theming page, following the layout language of
 * the sister forks (denwa/messeji): bold underlined section headings, deeply
 * indented items per level, tight rows, live preview of every change.
 */
class ShiroikumaUi : PreferenceFragment() {

    // Named skBinding/skHeaderBinding rather than binding/headerBinding: upstream
    // 0.0.33_alpha moved those members into PreferenceFragment, typed for its own
    // FragmentPreferenceAppearanceBinding, so our page's own layout cannot override them.
    // The base's lifecycle code is inert here because this page leaves titleRes at 0.
    private lateinit var skBinding: FragmentShiroikumaUiBinding
    private lateinit var skHeaderBinding: HeaderPreferencesGenericBinding

    private val swatches = HashMap<String, View>()
    private val accentLines = ArrayList<View>()
    private val previewCards = ArrayList<View>()

    /**
     * Everything on the page that is painted in code rather than by the theme — pill frames,
     * accent labels, the dim-gold descriptions and their left rules. [TypeFaceTextView] repaints
     * itself from its own color style whenever the theme fires, so these run again after it.
     */
    private val restyle = ArrayList<() -> Unit>()
    private var fontValueView: TypeFaceTextView? = null
    private var tokenValueView: TypeFaceTextView? = null
    private var tokenRowView: View? = null
    private var regenerateRowView: View? = null
    private var allFilesValueView: TypeFaceTextView? = null

    // Export/Import (Kōjiki flow): panel + row state
    private var eximDialog: AlertDialog? = null
    private var eimDirectoryTv: TypeFaceTextView? = null
    private var eimStatusTv: TypeFaceTextView? = null
    private var eimRowStatusTv: TypeFaceTextView? = null
    private var pendingExportCats: List<SkBackup.Cat>? = null
    private var pendingImportCats: List<SkBackup.Cat>? = null

    /** SAF directory picker for the persisted export directory. */
    private val eimDirPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onEimDirPicked(uri)
        }
    }

    /** Save-as fallback used only while no export directory is configured. */
    private val eimSaveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            writePendingExportTo(uri)
        }
    }

    /** Import file picker (.zip export). */
    private val eimImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onEimFilePicked(uri)
        }
    }

    /** Document picker for the PowerAmp `.poweramp-backup` file (extension is custom, so mime is open). */
    private val powerAmpBackupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importPowerAmpRatings(uri)
        }
    }

    /** The Stored line under Diagnostics, refilled from a background count. */
    private var diagStoredTv: TypeFaceTextView? = null

    /**
     * Directory picker for the diagnostics export. Offered at “/sdcard/tmp” the first time, and
     * at whatever was picked last afterwards.
     */
    private val diagExportPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onDiagExportTreeChosen(uri)
        }
    }

    /** Document picker for the PowerAmp album-art export (also a `.poweramp-backup` ZIP). */
    private val powerAmpArtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importPowerAmpArt(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        skBinding = FragmentShiroikumaUiBinding.inflate(inflater, container, false)
        skHeaderBinding = HeaderPreferencesGenericBinding.inflate(inflater, container, false)
        return skBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        skHeaderBinding.title.text = getString(R.string.shiroikuma_ui)
        skHeaderBinding.icon.setImageResource(R.drawable.ic_settings)
        skBinding.header.setContentView(skHeaderBinding.root)

        buildRows()
        refreshShape()
    }

    override fun onResume() {
        super.onResume()
        // Opening the page queries the export directory for the latest export (Kōjiki flow).
        refreshEximRowStatus()
        // Coming back from the system grant page must show the new state, not the stale one.
        allFilesValueView?.setText(allFilesAccessLabel())
        refreshDiagStored()
    }

    override fun onPause() {
        super.onPause()
        // The keep-for-N-days window is applied on leaving the page as well as at start.
        val app = context?.applicationContext ?: return
        lifecycleScope.launch(Dispatchers.IO) { SkDiag.cleanup(app) }
    }

    // ------------------------------------------------------------------ the page: link, index, sections

    /**
     * One top-level section of the page. Icon and colour sit beside the title here, so the
     * heading and its chip in the index cannot drift apart. The colour is one of the seven
     * section-icon roles, chosen by what kind of thing the section is.
     */
    private class Sec(val key: String, @StringRes val title: Int, @DrawableRes val icon: Int,
                      val colorKey: String, val build: () -> Unit) {
        var header: ItemSkSectionBinding? = null
        var body: LinearLayout? = null
        var built = false
    }

    /** Where the row builders put their rows: the body of the section being built. */
    private lateinit var target: LinearLayout
    private var secs: List<Sec> = emptyList()
    private var chipFlow: SkFlowLayout? = null
    private var lastChipSwap: View? = null

    /**
     * The sections in their default order. No two neighbours share a colour; a stored order
     * that was dragged by hand is applied on top by [orderedSections] and never touched.
     */
    private fun sections(): List<Sec> = listOf(
        Sec("exim", R.string.sk_eim_section, R.drawable.ic_swap, ShiroikumaPreferences.SECTION_REACH) {
            addEximRow(indent = 1)
            addPendingRestoreRow(indent = 1)

            // Automation lives here rather than in a section of its own: the token, the
            // All-files grant and the 保存復元 receiver exist to drive exactly the export
            // above, headlessly, from 自由作業盤.
            addSubgroup(R.string.sk_section_automation, indent = 1)
            addDescribedSwitchRow(R.string.sk_automation_enable, R.string.sk_automation_enable_desc, indent = 2,
                                  icon = R.drawable.ic_link, role = ShiroikumaPreferences.MENU_ICON_CONNECT,
                                  isChecked = { AutomationPreferences.isEnabled() },
                                  onChecked = { AutomationPreferences.setEnabled(it) })
            // 保存復元 contract v2 §2: the master switch above ships ON and the token below is
            // opt-in, because a pasted secret cannot survive a wipe and the case this now serves is
            // 応用管理 restoring this app AND its data onto a clean phone. The data door checks the
            // caller's package, uid and signing certificate either way.
            addDescribedSwitchRow(R.string.sk_automation_require_token, R.string.sk_automation_require_token_desc, indent = 2,
                                  icon = R.drawable.ic_key, role = ShiroikumaPreferences.MENU_ICON_BLOCK,
                                  isChecked = { AutomationPreferences.isTokenRequired() },
                                  onChecked = {
                                      AutomationPreferences.setTokenRequired(it)
                                      refreshTokenRowVisibility()
                                  })
            addTokenRow(indent = 2)
            addRegenerateTokenRow(indent = 2)
            refreshTokenRowVisibility()
            addAllFilesAccessRow(indent = 2)
        },
        Sec("general", R.string.sk_section_general, R.drawable.ic_settings, ShiroikumaPreferences.SECTION_BASE) {
            addSwitchRow(R.string.sk_enable_theme, indent = 1,
                         icon = R.drawable.ic_palette, role = ShiroikumaPreferences.MENU_ICON_VIEW,
                         isChecked = { ShiroikumaPreferences.isEnabled() },
                         onChecked = { checked ->
                             ShiroikumaPreferences.setEnabled(checked)
                             refreshTheme()
                         })
        },
        Sec("buttons", R.string.sk_section_buttons, R.drawable.ic_ring_16dp, ShiroikumaPreferences.SECTION_CONTROLS) {
            addColorRow(R.string.sk_color_button_text, ShiroikumaPreferences.BUTTON_TEXT, indent = 1)
            addColorRow(R.string.sk_color_button_frame, ShiroikumaPreferences.BUTTON_FRAME, indent = 1)
            addColorRow(R.string.sk_color_button_ground, ShiroikumaPreferences.BUTTON_GROUND, indent = 1)
            addColorRow(R.string.sk_color_button_delete, ShiroikumaPreferences.BUTTON_DELETE, indent = 1)
        },
        Sec("foundation", R.string.sk_section_foundation, R.drawable.ic_palette, ShiroikumaPreferences.SECTION_BASE) {
            addPreviewCard(PreviewMode.COLORS)
            addColorRow(R.string.sk_color_background, ShiroikumaPreferences.BACKGROUND, indent = 1)
            addColorRow(R.string.sk_color_text, ShiroikumaPreferences.TEXT, indent = 1)
            addColorRow(R.string.sk_color_accent, ShiroikumaPreferences.ACCENT, indent = 1)
            addColorRow(R.string.sk_color_border, ShiroikumaPreferences.BORDER, indent = 1)
            addSubgroup(R.string.sk_group_accent, indent = 1)
            addColorRow(R.string.sk_color_accent_primary, ShiroikumaPreferences.ACCENT_PRIMARY, indent = 2)
            addColorRow(R.string.sk_color_accent_secondary, ShiroikumaPreferences.ACCENT_SECONDARY, indent = 2)
        },
        Sec("text", R.string.sk_section_text, R.drawable.ic_text_fields, ShiroikumaPreferences.SECTION_WORDS) {
            addColorRow(R.string.sk_color_text_header, ShiroikumaPreferences.TEXT_HEADER, indent = 1)
            addColorRow(R.string.sk_color_text_primary, ShiroikumaPreferences.TEXT_PRIMARY, indent = 1)
            addColorRow(R.string.sk_color_text_secondary, ShiroikumaPreferences.TEXT_SECONDARY, indent = 1)
            addColorRow(R.string.sk_color_text_tertiary, ShiroikumaPreferences.TEXT_TERTIARY, indent = 1)
            addColorRow(R.string.sk_color_text_quaternary, ShiroikumaPreferences.TEXT_QUATERNARY, indent = 1)
            addColorRow(R.string.sk_color_settings_note, ShiroikumaPreferences.SETTINGS_NOTE, indent = 1)
        },
        Sec("surfaces", R.string.sk_section_surfaces, R.drawable.ic_list, ShiroikumaPreferences.SECTION_FRAME) {
            addColorRow(R.string.sk_color_highlight, ShiroikumaPreferences.HIGHLIGHT, indent = 1)
            addColorRow(R.string.sk_color_selected, ShiroikumaPreferences.SELECTED, indent = 1)
            addColorRow(R.string.sk_color_divider, ShiroikumaPreferences.DIVIDER, indent = 1)
            addColorRow(R.string.sk_color_spot, ShiroikumaPreferences.SPOT, indent = 1)
        },
        Sec("switches", R.string.sk_section_switches, R.drawable.ic_power_off, ShiroikumaPreferences.SECTION_CONTROLS) {
            addColorRow(R.string.sk_color_switch_on_outline, ShiroikumaPreferences.SWITCH_ON_OUTLINE, indent = 1)
            addColorRow(R.string.sk_color_switch_on_dot, ShiroikumaPreferences.SWITCH_ON_DOT, indent = 1)
            addColorRow(R.string.sk_color_switch_off_dim, ShiroikumaPreferences.SWITCH_OFF_DIM, indent = 1)
            addColorRow(R.string.sk_color_switch_off_dot, ShiroikumaPreferences.SWITCH_OFF_DOT, indent = 1)
            addColorRow(R.string.sk_color_switch_off, ShiroikumaPreferences.SWITCH_OFF, indent = 1)
            addColorRow(R.string.sk_color_check_tick, ShiroikumaPreferences.CHECK_TICK, indent = 1)
        },
        Sec("dialogs", R.string.sk_section_dialogs, R.drawable.ic_info, ShiroikumaPreferences.SECTION_REACH) {
            addColorRow(R.string.sk_color_dialog_ground, ShiroikumaPreferences.DIALOG_GROUND, indent = 1)
            addColorRow(R.string.sk_color_dialog_frame, ShiroikumaPreferences.DIALOG_FRAME, indent = 1)
            addColorRow(R.string.sk_color_dialog_title, ShiroikumaPreferences.DIALOG_TITLE, indent = 1)
            addColorRow(R.string.sk_color_dialog_text, ShiroikumaPreferences.DIALOG_TEXT, indent = 1)
            addColorRow(R.string.sk_color_dialog_note, ShiroikumaPreferences.DIALOG_NOTE, indent = 1)
        },
        Sec("icons", R.string.sk_section_icons, R.drawable.ic_image, ShiroikumaPreferences.SECTION_BASE) {
            addColorRow(R.string.sk_color_icon_regular, ShiroikumaPreferences.ICON_REGULAR, indent = 1)
            addColorRow(R.string.sk_color_icon_secondary, ShiroikumaPreferences.ICON_SECONDARY, indent = 1)
            addColorRow(R.string.sk_color_icon_disabled, ShiroikumaPreferences.ICON_DISABLED, indent = 1)
        },
        Sec("menus", R.string.sk_section_menus, R.drawable.ic_menu, ShiroikumaPreferences.SECTION_REACH) {
            addColorRow(R.string.sk_color_menu_label, ShiroikumaPreferences.MENU_LABEL, indent = 1)
            addColorRow(R.string.sk_color_menu_row_frame, ShiroikumaPreferences.MENU_ROW_FRAME, indent = 1)
            addColorRow(R.string.sk_color_menu_divider, ShiroikumaPreferences.MENU_DIVIDER, indent = 1)
            addSubgroup(R.string.sk_group_menu_icons, indent = 1)
            addColorRow(R.string.sk_color_menu_icon_connect, ShiroikumaPreferences.MENU_ICON_CONNECT, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_write, ShiroikumaPreferences.MENU_ICON_WRITE, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_person, ShiroikumaPreferences.MENU_ICON_PERSON, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_share, ShiroikumaPreferences.MENU_ICON_SHARE, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_view, ShiroikumaPreferences.MENU_ICON_VIEW, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_block, ShiroikumaPreferences.MENU_ICON_BLOCK, indent = 2)
            addColorRow(R.string.sk_color_menu_icon_delete, ShiroikumaPreferences.MENU_ICON_DELETE, indent = 2)
        },
        Sec("typography", R.string.sk_section_typography, R.drawable.ic_alphabet, ShiroikumaPreferences.SECTION_WORDS) {
            addPreviewCard(PreviewMode.TYPOGRAPHY)
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
        },
        Sec("shape", R.string.sk_section_shape, R.drawable.ic_corner, ShiroikumaPreferences.SECTION_FRAME) {
            addPreviewCard(PreviewMode.SHAPE)
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
        },
        Sec("player", R.string.sk_section_player, R.drawable.ic_play, ShiroikumaPreferences.SECTION_BASE) {
            addColorRow(R.string.sk_color_visualizer, ShiroikumaPreferences.VISUALIZER, indent = 1)
            addSwitchRow(R.string.sk_keep_screen_on, indent = 1,
                         icon = R.drawable.ic_keep_screen_on, role = ShiroikumaPreferences.MENU_ICON_VIEW,
                         isChecked = { ShiroikumaPreferences.isKeepScreenOnEnabled() },
                         onChecked = { ShiroikumaPreferences.setKeepScreenOnEnabled(it) })
        },
        Sec("meteors", R.string.sk_section_meteors, R.drawable.ic_animation, ShiroikumaPreferences.SECTION_FLASH) {
            addSwitchRow(R.string.sk_meteor_enable, indent = 1,
                         icon = R.drawable.ic_animation, role = ShiroikumaPreferences.MENU_ICON_VIEW,
                         isChecked = { MeteorPreferences.isEnabled() },
                         onChecked = { MeteorPreferences.setEnabled(it) })
            addSwitchRow(R.string.sk_meteor_overlay, indent = 1,
                         icon = R.drawable.ic_animation, role = ShiroikumaPreferences.MENU_ICON_VIEW,
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
                         icon = R.drawable.ic_animation, role = ShiroikumaPreferences.MENU_ICON_VIEW,
                         isChecked = { MeteorPreferences.isReactive() },
                         onChecked = { MeteorPreferences.setReactive(it) })
            addSwitchRow(R.string.sk_meteor_reverse, indent = 1,
                         icon = R.drawable.ic_animation, role = ShiroikumaPreferences.MENU_ICON_VIEW,
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
        },
        Sec("library", R.string.sk_section_library, R.drawable.ic_library, ShiroikumaPreferences.SECTION_REACH) {
            addImportPowerAmpRow(indent = 1)
            addImportPowerAmpArtRow(indent = 1)
            addDownloadAlbumArtRow(indent = 1)
        },
        Sec("diag", R.string.sk_diag_section, R.drawable.ic_memory, ShiroikumaPreferences.SECTION_WARNING) {
            addNoteRow(getString(R.string.sk_diag_intro), indent = 1)

            addSubgroup(R.string.sk_diag_group_screens, indent = 1)
            addDiagSwitch(SkDiag.Group.SCREENS, indent = 2)
            addDiagSwitch(SkDiag.Group.MEMORY, indent = 2)

            addSubgroup(R.string.sk_diag_group_playback, indent = 1)
            addDiagSwitch(SkDiag.Group.PLAYBACK, indent = 2)

            addSubgroup(R.string.sk_diag_group_service, indent = 1)
            addDiagSwitch(SkDiag.Group.SERVICE, indent = 2)

            addSubgroup(R.string.sk_diag_group_library, indent = 1)
            addDiagSwitch(SkDiag.Group.LIBRARY, indent = 2)

            addSubgroup(R.string.sk_diag_group_automation, indent = 1)
            addDiagSwitch(SkDiag.Group.AUTOMATION, indent = 2)

            addSubgroup(R.string.sk_diag_group_recorded, indent = 1)
            diagStoredTv = addDescribedRow(R.string.sk_diag_stored, getString(R.string.sk_diag_counting), indent = 2)
            refreshDiagStored()
            addSliderRow(R.string.sk_diag_keep, indent = 2,
                         min = 0F, max = 60F, default = 0F,
                         current = { SkDiag.getKeepDays(requireContext()).toFloat() },
                         label = {
                             if (it.toInt() == 0) getString(R.string.sk_diag_keep_all)
                             else getString(R.string.sk_diag_keep_days, it.toInt())
                         },
                         onChange = { SkDiag.setKeepDays(requireContext(), it.toInt()) })
            addActionRow(R.string.sk_diag_export, indent = 2, main = true, opens = true,
                         icon = R.drawable.ic_folder_open_outline, role = ShiroikumaPreferences.MENU_ICON_SHARE,
                         description = getString(R.string.sk_diag_export_desc)) {
                launchDiagExport()
            }
            addActionRow(R.string.sk_diag_share, indent = 2, opens = true,
                         icon = R.drawable.ic_send, role = ShiroikumaPreferences.MENU_ICON_SHARE,
                         description = getString(R.string.sk_diag_share_desc)) {
                shareDiag()
            }
            addActionRow(R.string.sk_diag_purge, indent = 2, opens = false,
                         icon = R.drawable.ic_delete, role = ShiroikumaPreferences.MENU_ICON_DELETE, destructive = true,
                         description = getString(R.string.sk_diag_purge_desc)) {
                confirmDiagPurge()
            }
        },
        Sec("page", R.string.sk_section_page, R.drawable.ic_app_settings, ShiroikumaPreferences.SECTION_BASE) {
            addSubgroup(R.string.sk_group_section_icons, indent = 1)
            addColorRow(R.string.sk_color_section_base, ShiroikumaPreferences.SECTION_BASE, indent = 2)
            addColorRow(R.string.sk_color_section_controls, ShiroikumaPreferences.SECTION_CONTROLS, indent = 2)
            addColorRow(R.string.sk_color_section_words, ShiroikumaPreferences.SECTION_WORDS, indent = 2)
            addColorRow(R.string.sk_color_section_frame, ShiroikumaPreferences.SECTION_FRAME, indent = 2)
            addColorRow(R.string.sk_color_section_reach, ShiroikumaPreferences.SECTION_REACH, indent = 2)
            addColorRow(R.string.sk_color_section_warning, ShiroikumaPreferences.SECTION_WARNING, indent = 2)
            addColorRow(R.string.sk_color_section_flash, ShiroikumaPreferences.SECTION_FLASH, indent = 2)
            addSubgroup(R.string.sk_group_chips, indent = 1)
            addColorRow(R.string.sk_color_chip_text, ShiroikumaPreferences.CHIP_TEXT, indent = 2)
            addColorRow(R.string.sk_color_chip_frame, ShiroikumaPreferences.CHIP_FRAME, indent = 2)
        },
        Sec("maintenance", R.string.sk_section_maintenance, R.drawable.ic_reset, ShiroikumaPreferences.SECTION_WARNING) {
            addActionRow(R.string.sk_reset_section_order, indent = 1, opens = false,
                         description = getString(R.string.sk_reset_section_order_desc),
                         icon = R.drawable.ic_reset, role = ShiroikumaPreferences.MENU_ICON_CONNECT) {
                ShiroikumaPreferences.clearSectionOrder()
                SkFlash.show(requireContext(), R.string.sk_reset_section_order_done)
                skBinding.rowsContainer.post { buildRows() }
            }
            addActionRow(R.string.sk_reset_all_colors, indent = 1, opens = false,
                         description = getString(R.string.sk_reset_all_colors_desc),
                         icon = R.drawable.ic_palette, role = ShiroikumaPreferences.MENU_ICON_CONNECT) {
                confirmResetAllColors()
            }
        }
    )

    private fun orderedSections(): List<Sec> {
        val all = sections()
        val stored = ShiroikumaPreferences.getSectionOrder()
        if (stored.isEmpty()) return all
        val known = stored.mapNotNull { key -> all.firstOrNull { it.key == key } }
        // A section added by a later build keeps its default place at the end.
        return known + all.filter { it !in known }
    }

    private fun buildRows() {
        swatches.clear()
        accentLines.clear()
        previewCards.clear()
        restyle.clear()
        val root = skBinding.rowsContainer
        root.removeAllViews()
        target = root

        addSettingsLink()
        secs = orderedSections()
        addChipIndex()

        val open = ShiroikumaPreferences.getOpenSections()
        secs.forEach { sec ->
            addSectionHeader(sec)
            val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            root.addView(body, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sec.body = body
            if (sec.key in open) {
                buildSection(sec)
                body.visibility = View.VISIBLE
            } else {
                // A folded section's rows are built only when it is first opened.
                body.visibility = View.GONE
            }
            paintChevron(sec)
        }
    }

    private fun buildSection(sec: Sec) {
        val body = sec.body ?: return
        target = body
        sec.build()
        sec.built = true
        target = skBinding.rowsContainer
        refreshShape()
    }

    /** The first element of the page: the thick pill that opens the app's own settings. */
    private fun addSettingsLink() {
        addActionRow(R.string.sk_app_settings, indent = 0, main = true, opens = true,
                     description = getString(R.string.sk_app_settings_desc),
                     icon = R.drawable.ic_settings) {
            openFragment(Preferences.newInstance(), Preferences.TAG)
        }
    }

    private fun sectionColor(sec: Sec): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(sec.colorKey) else skAccentColor()

    /**
     * The index: a wrapping grid of small outlined chips, one per section, each as wide as its
     * icon, label and padding. A tap unfolds the section and scrolls to it; a long-press lifts
     * the chip into a drag, the chips reorder live under the finger, and the sections follow
     * on drop.
     */
    private fun addChipIndex() {
        val flow = SkFlowLayout(requireContext()).apply {
            setPadding(dp(4), dp(10), 0, dp(6))
        }
        secs.forEach { flow.addView(chip(it)) }
        flow.setOnDragListener { _, event ->
            val dragged = event.localState as? View ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val over = flow.indexAt(event.x, event.y)
                    val from = flow.indexOfChild(dragged)
                    val under = if (over >= 0) flow.getChildAt(over) else null
                    when {
                        under == null || under === dragged -> lastChipSwap = null
                        // Chips differ in width, so the chip just displaced can land back under
                        // the finger; it is not swapped again until the finger has left it.
                        under === lastChipSwap -> Unit
                        from >= 0 -> {
                            flow.removeView(dragged)
                            flow.addView(dragged, over.coerceAtMost(flow.childCount))
                            lastChipSwap = under
                        }
                    }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragged.alpha = 1F
                    lastChipSwap = null
                    val order = (0 until flow.childCount).mapNotNull { flow.getChildAt(it).tag as? String }
                    if (order != secs.map { it.key }) {
                        ShiroikumaPreferences.setSectionOrder(order)
                        flow.post { if (view != null) buildRows() }
                    }
                }
            }
            true
        }
        chipFlow = flow
        skBinding.rowsContainer.addView(flow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun chip(sec: Sec): View {
        val chip = TypeFaceTextView(requireContext()).apply {
            text = getString(sec.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13F)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(12), dp(5))
            tag = sec.key
        }
        val paint = {
            val frame = if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.CHIP_FRAME) else skAccentColor()
            chip.background = SkPills.pill(requireContext(), frame, false)
            chip.setTextColor(if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.CHIP_TEXT) else skAccentColor())
            chip.typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.REGULAR, requireContext())
            SkPills.labelIcon(chip, sec.icon, sectionColor(sec))
        }
        paint()
        restyle.add(paint)
        chip.setOnClickListener { jumpTo(sec) }
        chip.setOnLongClickListener { v ->
            v.startDragAndDrop(null, View.DragShadowBuilder(v), v, 0)
            v.alpha = 0.3F
            true
        }
        return chip
    }

    /**
     * Folding section heading: the 1 px line above it stays whether the section is folded or
     * open, the heading carries the section's icon in the section's colour, and a tap folds or
     * unfolds the rows.
     */
    private fun addSectionHeader(sec: Sec) {
        val row = ItemSkSectionBinding.inflate(layoutInflater, skBinding.rowsContainer, false)
        row.sectionTitle.text = getString(sec.title)
        registerAccentLine(row.sectionSpacer)
        registerAccentLine(row.sectionUnderline)
        val paint = {
            SkPills.labelIcon(row.sectionTitle, sec.icon, sectionColor(sec))
            row.sectionChevron.setTextColor(skAccentColor())
        }
        paint()
        restyle.add(paint)
        row.sectionRow.setOnClickListener { toggleSection(sec) }
        (row.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(6)
        sec.header = row
        skBinding.rowsContainer.addView(row.root)
    }

    private fun paintChevron(sec: Sec) {
        val open = sec.body?.visibility == View.VISIBLE
        sec.header?.sectionChevron?.text = if (open) "⌄" else "›"
    }

    private fun toggleSection(sec: Sec) {
        val body = sec.body ?: return
        val opening = body.visibility != View.VISIBLE
        if (opening && !sec.built) buildSection(sec)
        body.visibility = if (opening) View.VISIBLE else View.GONE
        paintChevron(sec)
        val open = ShiroikumaPreferences.getOpenSections().toMutableSet()
        if (opening) open.add(sec.key) else open.remove(sec.key)
        ShiroikumaPreferences.setOpenSections(open)
    }

    /** A tap on a chip: unfold the section and scroll to its heading. */
    private fun jumpTo(sec: Sec) {
        if (sec.body?.visibility != View.VISIBLE) toggleSection(sec)
        val header = sec.header?.root ?: return
        skBinding.scrollView.post {
            skBinding.scrollView.smoothScrollTo(0, header.top + skBinding.rowsContainer.top)
        }
    }

    /** Puts a row's icon at the start of its label: coloured by what the row does, sized by the label. */
    private fun rowIcon(label: android.widget.TextView, @DrawableRes icon: Int, roleKey: String) {
        val paint = {
            SkPills.labelIcon(label, icon,
                              if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(roleKey) else skAccentColor())
        }
        paint()
        restyle.add(paint)
    }

    private fun confirmResetAllColors() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.sk_reset_all_colors_title)
            .setMessage(R.string.sk_reset_all_colors_body)
            .setPositiveButton(R.string.sk_reset_all_colors_confirm) { _, _ ->
                ShiroikumaPreferences.resetAllColors()
                refreshTheme()
                SkFlash.show(requireContext(), R.string.sk_reset_all_colors_done)
            }
            .setNegativeButton(R.string.sk_diag_purge_cancel, null)
            .create()
        dialog.show()
        styleEximDialog(dialog)
    }

    // ------------------------------------------------------------------ Diagnostics

    private fun addDiagSwitch(group: SkDiag.Group, indent: Int) {
        addDescribedSwitchRow(group.titleRes, group.descRes, indent,
                              icon = R.drawable.ic_file_info, role = ShiroikumaPreferences.MENU_ICON_VIEW,
                              isChecked = { SkDiag.isOn(group) },
                              onChecked = { SkDiag.set(requireContext(), group, it) })
    }

    /** Counts what is on disk off the main thread — a day's file can be tens of megabytes. */
    private fun refreshDiagStored() {
        val app = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { SkDiag.summarize(app) }
            diagStoredTv?.text = describeDiagStored(summary)
        }
    }

    private fun describeDiagStored(summary: SkDiag.Summary): String {
        if (summary.count == 0) return getString(R.string.sk_diag_stored_none)
        val size = formatBytes(summary.bytes)
        return if (summary.firstDay == summary.lastDay) {
            getString(R.string.sk_diag_stored_day, summary.count, size, summary.lastDay)
        } else {
            getString(R.string.sk_diag_stored_span, summary.count, size, summary.firstDay, summary.lastDay)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun launchDiagExport() {
        val initial = SkDiag.getExportTree(requireContext())
                ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:tmp")
        runCatching { diagExportPicker.launch(initial) }
            .onFailure { diagExportPicker.launch(null) }
    }

    private fun onDiagExportTreeChosen(tree: Uri) {
        val app = requireContext().applicationContext
        SkDiag.setExportTree(app, tree)
        viewLifecycleOwner.lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) { SkDiag.export(app, tree) }
            if (copied > 0) {
                SkFlash.show(requireContext(), getString(R.string.sk_diag_export_done, copied))
            } else {
                SkFlash.show(requireContext(), R.string.sk_diag_export_failed)
            }
        }
    }

    private fun shareDiag() {
        val intent = SkDiag.shareIntent(requireContext())
        if (intent == null) {
            SkFlash.show(requireContext(), R.string.sk_diag_share_none)
            return
        }
        startActivity(Intent.createChooser(intent, getString(R.string.sk_diag_share)))
    }

    /** Says exactly what disappears before anything does. */
    private fun confirmDiagPurge() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { SkDiag.summarize(app) }
            if (summary.count == 0) {
                SkFlash.show(requireContext(), R.string.sk_diag_stored_none)
                return@launch
            }
            val span = if (summary.firstDay == summary.lastDay) summary.lastDay.orEmpty()
            else "${summary.firstDay} – ${summary.lastDay}"
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.sk_diag_purge_title)
                .setMessage(getString(R.string.sk_diag_purge_body, summary.count, formatBytes(summary.bytes), span))
                .setPositiveButton(R.string.sk_diag_purge_confirm) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val removed = withContext(Dispatchers.IO) { SkDiag.purge(app) }
                        SkFlash.show(requireContext(), getString(R.string.sk_diag_purge_done, removed))
                        refreshDiagStored()
                    }
                }
                .setNegativeButton(R.string.sk_diag_purge_cancel, null)
                .create()
            dialog.show()
            // Purging deletes: the confirming pill is red.
            styleEximDialog(dialog, destructive = true)
        }
    }

    // ------------------------------------------------------------------ row builders

    /** kxkb-style sub-heading: 17sp bold accent title with a thinner text-wide underline. */
    private fun addSubgroup(@StringRes titleRes: Int, indent: Int) {
        val row = ItemSkSubgroupBinding.inflate(layoutInflater, target, false)
        row.subgroupTitle.text = getString(titleRes)
        registerAccentLine(row.subgroupUnderline)
        (row.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(10)
        // Sub-heading sits one indent step deeper than its section title (20dp), rows go deeper still.
        row.root.setPaddingRelative(dp(20 + 18 * indent), row.root.paddingTop, row.root.paddingEnd, row.root.paddingBottom)
        target.addView(row.root)
    }

    /** Colors a heading underline / section spacer with the effective accent and tracks it for refresh. */
    private fun registerAccentLine(view: View) {
        view.setBackgroundColor(skAccentColor())
        accentLines.add(view)
    }

    /** What an in-section preview card demonstrates — each section shows only what it configures. */
    private enum class PreviewMode { COLORS, TYPOGRAPHY, SHAPE }

    /**
     * The live preview card, placed directly under a section heading so changes are
     * visible right where they are made (no scrolling back to the top of the page).
     */
    private fun addPreviewCard(mode: PreviewMode) {
        val card = ItemSkPreviewBinding.inflate(layoutInflater, target, false)
        when (mode) {
            PreviewMode.COLORS -> {
                // Everything visible: text tiers, divider, icon + accent line.
            }
            PreviewMode.TYPOGRAPHY -> {
                // Text tiers only — font, size scale and weight delta are what matter here.
                card.previewDivider.visibility = View.GONE
                card.previewIconRow.visibility = View.GONE
            }
            PreviewMode.SHAPE -> {
                // A minimal bordered box — the card surface itself previews radius and border.
                card.previewHeader.visibility = View.GONE
                card.previewSecondary.visibility = View.GONE
                card.previewTertiary.visibility = View.GONE
                card.previewDivider.visibility = View.GONE
                card.previewIconRow.visibility = View.GONE
            }
        }
        (card.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
            marginStart = dp(20)
        }
        previewCards.add(card.root)
        target.addView(card.root)
    }

    private fun addColorRow(@StringRes labelRes: Int, key: String, indent: Int) {
        val row = ItemSkColorBinding.inflate(layoutInflater, target, false)
        row.colorLabel.text = getString(labelRes)
        row.colorSwatch.background = swatchDrawable(ShiroikumaPreferences.getEffectiveColor(key))
        rowIcon(row.colorLabel, R.drawable.ic_palette, ShiroikumaPreferences.MENU_ICON_VIEW)
        swatches[key] = row.colorSwatch
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            openColorPicker(labelRes, key)
        }
        target.addView(row.root)
    }

    private fun addSliderRow(@StringRes labelRes: Int, indent: Int,
                             min: Float, max: Float, default: Float,
                             current: () -> Float,
                             label: (Float) -> String,
                             onChange: (Float) -> Unit) {
        val row = ItemSkSliderBinding.inflate(layoutInflater, target, false)
        row.sliderLabel.text = getString(labelRes)
        rowIcon(row.sliderLabel, R.drawable.ic_tune, ShiroikumaPreferences.MENU_ICON_VIEW)
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
        target.addView(row.root)
    }

    private fun addSwitchRow(@StringRes labelRes: Int, indent: Int,
                             @DrawableRes icon: Int = R.drawable.ic_power_off,
                             role: String = ShiroikumaPreferences.MENU_ICON_CONNECT,
                             isChecked: () -> Boolean,
                             onChecked: (Boolean) -> Unit) {
        val row = ItemSkSwitchBinding.inflate(layoutInflater, target, false)
        row.switchLabel.text = getString(labelRes)
        rowIcon(row.switchLabel, icon, role)
        row.switchToggle.isChecked = isChecked()
        row.switchToggle.setOnCheckedChangeListener { _, checked ->
            onChecked(checked)
        }
        row.root.setOnClickListener {
            row.switchToggle.toggle()
        }
        indentRow(row.root, indent)
        val paint = { row.switchToggle.refreshColors() }
        paint()
        restyle.add(paint)
        target.addView(row.root)
    }

    /**
     * A switch that carries an explanation: bold accent label, dim-gold description beneath it
     * and a thin dim-gold rule down the item's left edge.
     */
    private fun addDescribedSwitchRow(@StringRes labelRes: Int, @StringRes descRes: Int, indent: Int,
                                      @DrawableRes icon: Int = R.drawable.ic_power_off,
                                      role: String = ShiroikumaPreferences.MENU_ICON_CONNECT,
                                      isChecked: () -> Boolean,
                                      onChecked: (Boolean) -> Unit) {
        val row = ItemSkSwitchDescribedBinding.inflate(layoutInflater, target, false)
        row.switchLabel.text = getString(labelRes)
        rowIcon(row.switchLabel, icon, role)
        row.switchDesc.text = getString(descRes)
        row.switchToggle.isChecked = isChecked()
        row.switchToggle.setOnCheckedChangeListener { _, checked ->
            onChecked(checked)
        }
        row.root.setOnClickListener {
            row.switchToggle.toggle()
        }
        indentRow(row.root, indent)
        val paint = {
            row.switchLabel.setTextColor(skAccentColor())
            row.switchDesc.setTextColor(skNoteColor())
            row.switchRule.setBackgroundColor(skNoteColor())
            row.switchToggle.refreshColors()
        }
        paint()
        restyle.add(paint)
        target.addView(row.root)
    }


    /**
     * A described row with no control of its own — a label and its explanation. Used for facts
     * the page states rather than settings it offers.
     */
    private fun addDescribedRow(@StringRes labelRes: Int, description: CharSequence, indent: Int): TypeFaceTextView {
        val row = ItemSkSwitchDescribedBinding.inflate(layoutInflater, target, false)
        row.switchLabel.text = getString(labelRes)
        row.switchDesc.text = description
        rowIcon(row.switchLabel, R.drawable.ic_info, ShiroikumaPreferences.MENU_ICON_VIEW)
        row.switchToggle.visibility = View.GONE
        row.root.isClickable = false
        indentRow(row.root, indent)
        val paint = {
            row.switchLabel.setTextColor(skAccentColor())
            row.switchDesc.setTextColor(skNoteColor())
            row.switchRule.setBackgroundColor(skNoteColor())
        }
        paint()
        restyle.add(paint)
        target.addView(row.root)
        return row.switchDesc
    }

    /**
     * Something to press: an outlined pill (never filled) holding the label — a 3 dp frame and
     * bold for the main action of its section, 1.25 dp and regular otherwise — with a chevron
     * when it opens another screen or a picker, and the explanation beneath it.
     *
     * The pill hugs its label rather than spanning the line, and the pill alone is the touch
     * target: the explanation beneath is not clickable.
     */
    private fun addActionRow(@StringRes labelRes: Int, indent: Int,
                             main: Boolean = false,
                             opens: Boolean = true,
                             description: CharSequence? = null,
                             @DrawableRes icon: Int = R.drawable.ic_arrow_forward,
                             role: String = ShiroikumaPreferences.MENU_ICON_VIEW,
                             destructive: Boolean = false,
                             onClick: () -> Unit): ItemSkActionBinding {
        val row = ItemSkActionBinding.inflate(layoutInflater, target, false)
        row.actionLabel.text = getString(labelRes)
        row.actionChevron.visibility = if (opens) View.VISIBLE else View.GONE
        row.actionDesc.text = description ?: ""
        val described = description.isNullOrEmpty().not()
        row.actionDesc.visibility = if (described) View.VISIBLE else View.GONE
        // Kept in the layout when there is nothing to explain, so every pill on the page
        // still starts at the same place.
        row.actionRule.visibility = if (described) View.VISIBLE else View.INVISIBLE
        row.actionPill.setOnClickListener { onClick() }
        indentRow(row.root, indent)
        val paint = {
            row.actionPill.background = pillDrawable(main, destructive)
            row.actionLabel.typeface = TypeFace.getTypeFace(
                    AppearancePreferences.getAppFont(),
                    if (main) TypeFaceTextView.BOLD else TypeFaceTextView.REGULAR, requireContext())
            row.actionLabel.setTextColor(skButtonColor(ShiroikumaPreferences.BUTTON_TEXT, destructive))
            row.actionChevron.setTextColor(skButtonColor(ShiroikumaPreferences.BUTTON_TEXT, destructive))
            row.actionDesc.setTextColor(skNoteColor())
            row.actionRule.setBackgroundColor(skNoteColor())
        }
        paint()
        restyle.add(paint)
        // The icon sits inside the pill, right before its label.
        rowIcon(row.actionLabel, icon, role)
        target.addView(row.root)
        return row
    }

    /** A loose note belonging to no single item — dim gold, no rule. */
    private fun addNoteRow(note: CharSequence, indent: Int) {
        val view = TypeFaceTextView(requireContext()).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.REGULAR, context)
            setPaddingRelative(dp(38 + 18 * indent), dp(2), dp(16), dp(4))
            text = note
        }
        val paint = { view.setTextColor(skNoteColor()) }
        paint()
        restyle.add(paint)
        target.addView(view)
    }

    /**
     * The pill: fully round, the background showing through, an accent frame 3 dp thick for a
     * main action and 1.25 dp otherwise. Never filled.
     */
    private fun pillDrawable(main: Boolean, destructive: Boolean = false): RippleDrawable {
        val accent = skButtonColor(ShiroikumaPreferences.BUTTON_FRAME, destructive)
        val density = resources.displayMetrics.density
        val frame = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 50 * density // > half the height -> fully round
            setStroke(((if (main) 3F else 1.25F) * density).toInt().coerceAtLeast(1), accent)
        }
        return RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x4D000000), frame, null)
    }

    private fun addFontRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, target, false)
        row.valueLabel.text = getString(R.string.sk_font)
        rowIcon(row.valueLabel, R.drawable.ic_text_fields, ShiroikumaPreferences.MENU_ICON_WRITE)
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
        target.addView(row.root)
    }

    /**
     * The token and its Regenerate action are shown only while 「Use authorization token?」 is on
     * (保存復元 contract v2 §2). A secret sitting under an off switch invites 白い熊 to paste it
     * somewhere it will do nothing.
     */
    private fun refreshTokenRowVisibility() {
        val visibility = if (AutomationPreferences.isTokenRequired()) View.VISIBLE else View.GONE
        tokenRowView?.visibility = visibility
        regenerateRowView?.visibility = visibility
    }

    /** Shows the automation token; tapping the row copies it to the clipboard. */
    private fun addTokenRow(indent: Int) {
        val row = ItemSkValueBinding.inflate(layoutInflater, target, false)
        row.valueLabel.text = getString(R.string.sk_automation_token)
        rowIcon(row.valueLabel, R.drawable.ic_key, ShiroikumaPreferences.MENU_ICON_BLOCK)
        row.valueText.text = AutomationPreferences.getToken()
        tokenValueView = row.valueText
        tokenRowView = row.root
        indentRow(row.root, indent)
        row.root.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.sk_automation_token), AutomationPreferences.getToken()))
            SkFlash.show(requireContext(), R.string.sk_automation_token_copied)
        }
        target.addView(row.root)
    }

    /** Regenerates the automation token and refreshes the token row. */
    private fun addRegenerateTokenRow(indent: Int) {
        val row = addActionRow(R.string.sk_automation_regenerate, indent, opens = false,
                               icon = R.drawable.ic_refresh, role = ShiroikumaPreferences.MENU_ICON_CONNECT,
                               description = getString(R.string.sk_automation_regenerate_desc)) {
            val fresh = AutomationPreferences.regenerateToken()
            tokenValueView?.text = fresh
            SkFlash.show(requireContext(), R.string.sk_automation_token_regenerated)
        }
        regenerateRowView = row.root
    }

    /**
     * All-files access, which the 保存復元 contract needs for its `path` extra: without the
     * grant a headless export can only write into the configured export directory, so a
     * 自由作業盤 batch run would not collect our zip alongside the other apps'. Tapping
     * opens the system grant page; the state re-reads on every return to this page.
     */
    private fun addAllFilesAccessRow(indent: Int) {
        val row = addActionRow(R.string.sk_automation_allfiles, indent, opens = true,
                               icon = R.drawable.ic_folder_open_outline,
                               description = getString(allFilesAccessLabel())) {
            if (hasAllFilesAccess()) {
                SkFlash.show(requireContext(), R.string.sk_automation_allfiles_on)
            } else {
                openAllFilesAccessSettings()
            }
        }
        // The grant state is this row's explanation, re-read on every return to the page.
        allFilesValueView = row.actionDesc
    }

    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    @StringRes
    private fun allFilesAccessLabel(): Int {
        return if (hasAllFilesAccess()) R.string.sk_automation_allfiles_on else R.string.sk_automation_allfiles_off
    }

    /** The per-app grant page, falling back to the device-wide list where that is missing. */
    private fun openAllFilesAccessSettings() {
        val context = requireContext()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                 Uri.parse("package:${context.packageName}")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                SkFlash.show(context, R.string.sk_automation_allfiles_off, long = true)
            }
        }
    }

    /** Opens the system document picker for a PowerAmp backup; ratings become favorites. */
    private fun addImportPowerAmpRow(indent: Int) {
        addActionRow(R.string.sk_import_poweramp, indent, opens = true,
                     icon = R.drawable.ic_star_16dp, role = ShiroikumaPreferences.MENU_ICON_SHARE,
                     description = getString(R.string.sk_import_poweramp_desc)) {
            // The .poweramp-backup extension carries no registered mime type, so accept anything.
            powerAmpBackupPicker.launch(arrayOf("*/*"))
        }
    }

    /** Opens the system document picker for a PowerAmp album-art export; art gets embedded into files. */
    private fun addImportPowerAmpArtRow(indent: Int) {
        addActionRow(R.string.sk_import_poweramp_art, indent, opens = true,
                     icon = R.drawable.ic_image, role = ShiroikumaPreferences.MENU_ICON_SHARE,
                     description = getString(R.string.sk_import_poweramp_art_desc)) {
            // The .poweramp-backup extension carries no registered mime type, so accept anything.
            powerAmpArtPicker.launch(arrayOf("*/*"))
        }
    }

    /** Fully automatic — no picker: scans the library and fills artless albums from the Cover Art Archive. */
    private fun addDownloadAlbumArtRow(indent: Int) {
        // Fully automatic — nothing opens, so no chevron.
        addActionRow(R.string.sk_download_album_art, indent, opens = false,
                     icon = R.drawable.ic_cloud, role = ShiroikumaPreferences.MENU_ICON_CONNECT,
                     description = getString(R.string.sk_download_album_art_desc)) {
            downloadMissingAlbumArt()
        }
    }

    /**
     * The Export/Import entry: a row opening the category panel, with a status line
     * underneath mirroring the latest export found in the configured directory.
     */
    private fun addEximRow(indent: Int) {
        val row = addActionRow(R.string.sk_eim_row, indent, main = true, opens = true,
                               icon = R.drawable.ic_swap, role = ShiroikumaPreferences.MENU_ICON_SHARE) {
            showEximDialog()
        }
        // The status line is this row's explanation; it is filled in from a background probe.
        row.actionDesc.visibility = View.VISIBLE
        row.actionRule.visibility = View.VISIBLE
        eimRowStatusTv = row.actionDesc
    }

    /**
     * The restore's waiting room, shown only while something is waiting: the counts on the row,
     * the review sheet (`SkPendingRestoreDialog`) behind it.
     */
    private fun addPendingRestoreRow(indent: Int) {
        val row = addActionRow(R.string.sk_pending_row, indent, opens = true,
                               icon = R.drawable.ic_history, role = ShiroikumaPreferences.MENU_ICON_CONNECT) {
            SkPendingRestoreDialog.show(parentFragmentManager)
        }
        row.root.visibility = View.GONE
        row.actionDesc.visibility = View.VISIBLE
        row.actionRule.visibility = View.VISIBLE
        val status = row.actionDesc
        // Added after the row's own painter so the warning red survives a theme refresh.
        val warn = { status.setTextColor(skWarnColor()) }
        warn()
        restyle.add(warn)

        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { SkBackup.refreshPending(app) }
            SkBackup.pending.collect { pending ->
                val waiting = pending != null && !pending.isEmpty
                row.root.visibility = if (waiting) View.VISIBLE else View.GONE
                if (pending != null && waiting) status.text = getString(R.string.sk_pending_row_status, pending.favorites, pending.songs)
            }
        }
    }

    // ------------------------------------------------------------------ Export / Import (Kōjiki flow)

    /** Updates the on-page status line from a background probe of the export directory. */
    private fun refreshEximRowStatus() {
        val app = context?.applicationContext ?: return
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { SkBackup.lastExportStatus(app) }
            val warn = withContext(Dispatchers.IO) { SkBackup.latestExport(app) == null }
            eimRowStatusTv?.let { view ->
                view.text = status
                view.setTextColor(if (warn) skWarnColor() else skNoteColor())
                view.alpha = if (warn) 1F else 0.75F
            }
        }
    }

    /**
     * The Export/Import panel: description, the settable export directory (bordered box,
     * tap to choose), the latest-export status, select-all + one checkbox per category,
     * and an ArcaneChat-style pill button row — Cancel alone on the left, Import and
     * Export grouped on the right.
     */
    private fun showEximDialog() {
        val context = requireContext()
        val accent = skAccentColor()
        val textColor = skTextColor()
        val dim = (textColor and 0x00FFFFFF) or 0xC8000000.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }

        root.addView(TypeFaceTextView(context).apply {
            text = getString(R.string.sk_eim_title)
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        })

        root.addView(TypeFaceTextView(context).apply {
            text = getString(R.string.sk_eim_desc)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
        })

        // Export-directory box: caption + current directory, tap to (re)choose.
        val dirBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = eimBorder()
            setOnClickListener { eimDirPicker.launch(SkBackup.getDirUri(context)) }
        }
        dirBox.addView(TypeFaceTextView(context).apply {
            text = getString(R.string.sk_eim_dir_caption)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
        })
        eimDirectoryTv = TypeFaceTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
        }
        dirBox.addView(eimDirectoryTv)
        root.addView(dirBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        eimStatusTv = TypeFaceTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
        }
        root.addView(eimStatusTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })
        refreshEximDialogStatus()

        // Select-all + one checkbox per category, each seeded from the category's own
        // SkBackup.Cat.defaultOn — the same answer LIST_CATEGORIES gives 保存復元's picker,
        // so the in-app sheet and the automation one start from one statement, not two.
        val catBoxes = ArrayList<CheckBox>()
        val selectAll = eimCheckbox(getString(R.string.sk_eim_select_all), accent, bold = true,
                                    checked = SkBackup.Cat.entries.all { it.defaultOn })
        root.addView(selectAll)
        for (cat in SkBackup.Cat.entries) {
            val box = eimCheckbox(getString(cat.labelRes), accent, bold = false, checked = cat.defaultOn)
            box.tag = cat
            catBoxes.add(box)
            root.addView(box)
        }
        selectAll.setOnCheckedChangeListener { _, checked ->
            catBoxes.forEach { it.isChecked = checked }
        }

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton(R.string.sk_eim_export, null)
            .setNegativeButton(R.string.sk_eim_import, null)
            .setNeutralButton(android.R.string.cancel, null)
            .setOnDismissListener {
                eimDirectoryTv = null
                eimStatusTv = null
                eximDialog = null
            }
            .show()
        styleEximDialog(dialog)
        eximDialog = dialog
        // Export/Import must NOT auto-dismiss the panel: failures leave it open, and on
        // success the whole chain (info dialog -> panel -> UI page) closes via closeEximChain().
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            onEimExport(selectedCats(catBoxes))
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
            onEimImport(selectedCats(catBoxes))
        }
    }

    private fun eimCheckbox(label: String, accent: Int, bold: Boolean, checked: Boolean = true): CheckBox {
        return CheckBox(requireContext()).apply {
            text = label
            isChecked = checked
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(),
                                            if (bold) TypeFaceTextView.BOLD else TypeFaceTextView.MEDIUM, context)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dp(6), dp(6), 0, dp(6))
        }
    }

    /** The bordered inner box, same visual language as the dialog window border. */
    private fun eimBorder(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(skBackgroundColor())
            cornerRadius = AppearancePreferences.getCornerRadius().coerceAtMost(dp(10).toFloat())
            setStroke(dp(1).coerceAtLeast(1), skBorderColor())
        }
    }

    /**
     * Fork styling for the panel and its info dialogs: black window with the border-slot
     * (yellow) frame, and round pill buttons — black fill, accent stroke and text.
     */
    private fun styleEximDialog(dialog: AlertDialog, destructive: Boolean = false) {
        SkDialogChrome.style(dialog, destructive)
    }

    private fun selectedCats(boxes: List<CheckBox>): List<SkBackup.Cat> {
        return boxes.filter { it.isChecked }.map { it.tag as SkBackup.Cat }
    }

    /** Updates the directory-name and last-export lines inside the open panel. */
    private fun refreshEximDialogStatus() {
        val context = context ?: return
        val directoryView = eimDirectoryTv ?: return
        val statusView = eimStatusTv ?: return
        val dir = SkBackup.getExportDir(context)
        if (dir != null) {
            directoryView.text = dir.name ?: SkBackup.getDirUri(context)?.lastPathSegment ?: ""
            directoryView.setTextColor(skAccentColor())
        } else {
            directoryView.setText(R.string.sk_eim_dir_unset)
            directoryView.setTextColor(skWarnColor())
        }
        val app = context.applicationContext
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { SkBackup.lastExportStatus(app) }
            val warn = withContext(Dispatchers.IO) { SkBackup.latestExport(app) == null }
            statusView.text = status
            statusView.setTextColor(if (warn) skWarnColor() else skTextColor())
        }
    }

    private fun onEimDirPicked(uri: Uri) {
        val context = context ?: return
        try {
            context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (ignored: Exception) {
        }
        SkBackup.setDirUri(context, uri)
        refreshEximDialogStatus()
        refreshEximRowStatus()
    }

    private fun onEimExport(cats: List<SkBackup.Cat>) {
        val context = context ?: return
        if (cats.isEmpty()) {
            SkFlash.show(context, R.string.sk_eim_none_selected)
            return
        }
        val app = context.applicationContext
        if (SkBackup.getExportDir(app) == null) {
            // No directory configured — fall back to a save-as picker; export runs once the uri is known.
            pendingExportCats = cats
            eimSaveAs.launch(SkBackup.exportFileName())
            return
        }
        SkFlash.show(app, R.string.sk_eim_exporting)
        lifecycleScope.launch {
            val name = SkBackup.exportFileName()
            var file: DocumentFile? = null
            try {
                withContext(Dispatchers.IO) {
                    val dir = SkBackup.getExportDir(app) ?: throw IllegalStateException("export directory unavailable")
                    val created = dir.createFile("application/zip", name)
                        ?: throw IllegalStateException("could not create $name")
                    file = created
                    app.contentResolver.openOutputStream(created.uri)?.use { out ->
                        SkBackup.export(app, cats, out)
                    } ?: throw IllegalStateException("no output stream")
                }
                showEximExportDone(name)
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    try {
                        file?.delete() // don't leave a truncated export behind
                    } catch (ignored: Exception) {
                    }
                }
                SkFlash.show(app, getString(R.string.sk_eim_export_fail, e.message ?: e.javaClass.simpleName), long = true)
            }
        }
    }

    private fun writePendingExportTo(uri: Uri) {
        val context = context ?: return
        val cats = pendingExportCats ?: return
        pendingExportCats = null
        val app = context.applicationContext
        SkFlash.show(app, R.string.sk_eim_exporting)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        SkBackup.export(app, cats, out)
                    } ?: throw IllegalStateException("no output stream")
                }
                showEximExportDone(uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.sk_eim_section))
            } catch (e: Exception) {
                SkFlash.show(app, getString(R.string.sk_eim_export_fail, e.message ?: e.javaClass.simpleName), long = true)
            }
        }
    }

    private fun onEimImport(cats: List<SkBackup.Cat>) {
        val context = context ?: return
        if (cats.isEmpty()) {
            SkFlash.show(context, R.string.sk_eim_none_selected)
            return
        }
        pendingImportCats = cats
        eimImportPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun onEimFilePicked(uri: Uri) {
        val context = context ?: return
        val cats = pendingImportCats ?: return
        pendingImportCats = null
        val app = context.applicationContext
        SkFlash.show(app, R.string.sk_eim_importing)
        lifecycleScope.launch {
            var summary: String? = null
            var error: String? = null
            withContext(Dispatchers.IO) {
                // Copy to a temp file so the zip is random-access.
                val tmp = File.createTempFile("sk-eximport", ".zip", app.cacheDir)
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("no input stream")
                    ZipFile(tmp).use { zip ->
                        if (SkBackup.categoriesIn(zip).isEmpty()) {
                            error = app.getString(R.string.sk_eim_import_none)
                        } else {
                            summary = SkBackup.import(app, zip, cats)
                            if (summary == null) error = app.getString(R.string.sk_eim_import_none)
                        }
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                } finally {
                    tmp.delete()
                }
            }
            val result = summary
            if (result != null) {
                showEximImportDone(result)
            } else {
                SkFlash.show(app, getString(R.string.sk_eim_import_fail, error ?: ""), long = true)
            }
        }
    }

    /** Black-yellow OK dialog after a successful export; OK tears down the whole chain. */
    private fun showEximExportDone(name: String) {
        val context = context ?: return
        val dialog = AlertDialog.Builder(context)
            .setView(eimInfoBox(getString(R.string.sk_eim_export_done_title), getString(R.string.sk_eim_export_ok, name)))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> closeEximChain() }
            .show()
        styleEximDialog(dialog)
    }

    /**
     * Black-yellow info dialog after a successful import: "Restart now" restarts the app,
     * "Later" tears down the whole chain (info dialog -> panel -> UI page).
     */
    private fun showEximImportDone(summary: String) {
        val context = context ?: return
        val dialog = AlertDialog.Builder(context)
            .setView(eimInfoBox(getString(R.string.sk_eim_import_done_title), getString(R.string.sk_eim_import_done_body, summary)))
            .setCancelable(false)
            .setPositiveButton(R.string.sk_eim_restart_now) { _, _ -> restartApp() }
            .setNegativeButton(R.string.sk_eim_restart_later) { _, _ -> closeEximChain() }
            .show()
        styleEximDialog(dialog)
    }


    /** Title + body in fork colors for the info dialogs (the window frame comes from [styleEximDialog]). */
    private fun eimInfoBox(title: String, body: String): View {
        val context = requireContext()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        box.addView(TypeFaceTextView(context).apply {
            text = title
            setTextColor(SkDialogChrome.titleColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
        })
        box.addView(TypeFaceTextView(context).apply {
            text = body
            setTextColor(skTextColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
            setPadding(0, dp(10), 0, 0)
        })
        return ScrollView(context).apply { addView(box) }
    }

    /** Closes the info dialog's underlying chain: the Export/Import panel, then the UI page itself. */
    private fun closeEximChain() {
        val dialog = eximDialog
        eximDialog = null
        if (dialog != null) {
            try {
                dialog.dismiss()
            } catch (ignored: Exception) {
            }
        }
        popBackStack()
    }

    private fun restartApp() {
        val app = requireContext().applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch?.component != null) {
            app.startActivity(Intent.makeRestartActivityTask(launch.component))
        }
        Runtime.getRuntime().exit(0)
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
     * Runs the automatic album-art download off the main thread with a live
     * progress dialog (one tick per library album — covered albums flick past,
     * missing ones go through MusicBrainz + the Cover Art Archive) and reports
     * the outcome as a flash: "<n> albums missing art: <x> downloaded & embedded
     * (<y> files), <z> not found online, <w> failed".
     */
    private fun downloadMissingAlbumArt() {
        val appContext = requireContext().applicationContext
        val progress = ImportProgressDialog()
        lifecycleScope.launch {
            try {
                val result = AlbumArtDownloader.download(appContext) { done, total, label ->
                    progress.update(done, total, label)
                }
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_album_art_result,
                                       result.missingAlbums, result.downloaded, result.filesUpdated,
                                       result.notFound, result.failed),
                             long = true)
            } catch (e: Exception) {
                progress.dismiss()
                SkFlash.show(appContext,
                             getString(R.string.sk_album_art_failed, e.message ?: e.javaClass.simpleName),
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

    /** Rebuilds every preview card's background so corner radius and border update live. */
    private fun refreshShape() {
        previewCards.forEach { LayoutBackground.setBackground(it) }
    }

    private fun updateSwatches() {
        swatches.forEach { (key, view) ->
            view.background = swatchDrawable(ShiroikumaPreferences.getEffectiveColor(key))
        }
        accentLines.forEach { it.setBackgroundColor(skAccentColor()) }
        // After the theme has repainted every TypeFaceTextView from its own color style.
        restyle.forEach { it() }
    }

    // ------------------------------------------------------------------ fork colors

    private fun skBackgroundColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BACKGROUND)
        else ThemeManager.theme.viewGroupTheme.backgroundColor

    private fun skAccentColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT)
        else ThemeManager.accent.primaryAccentColor

    private fun skTextColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_PRIMARY)
        else ThemeManager.theme.textViewTheme.primaryTextColor

    /** The dim gold every explanation and left rule on this page is drawn in. */
    private fun skNoteColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.SETTINGS_NOTE)
        else ThemeManager.theme.textViewTheme.secondaryTextColor

    /** The red of a warning line and of anything that deletes — a settable role. */
    private fun skWarnColor(): Int = ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BUTTON_DELETE)

    /** A button role, or the delete red for a button that deletes. */
    private fun skButtonColor(key: String, destructive: Boolean): Int = when {
        destructive -> skWarnColor()
        ShiroikumaPreferences.isEnabled() -> ShiroikumaPreferences.getEffectiveColor(key)
        else -> skAccentColor()
    }

    private fun skBorderColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BORDER)
        else ThemeManager.accent.primaryAccentColor

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
            // kxkb indent cascade: with the container's 16dp, headings land at 36/54dp and
            // rows at 72/90dp from the screen edge (18dp per level).
            view.setPaddingRelative(view.paddingStart + dp(38 + 18 * indent), view.paddingTop, view.paddingEnd, view.paddingBottom)
        }
    }

    private fun swatchDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), skNoteColor())
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
