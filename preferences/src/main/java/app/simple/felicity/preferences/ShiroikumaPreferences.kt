package app.simple.felicity.preferences

import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import app.simple.felicity.manager.SharedPreferences.getSharedPreferences

/**
 * Preference store for the 白い熊 音楽 UI customization layer (fork-only).
 *
 * Two-tier color model (same concept as the sister forks denwa/messeji):
 * four foundation slots (background / text / accent / border) with hard
 * black-yellow defaults, and derived slots that inherit from the foundation
 * unless an explicit override is stored. [UNSET] marks "no override".
 */
object ShiroikumaPreferences {

    const val UNSET = Int.MIN_VALUE

    const val UI_ENABLED = "sk_ui_enabled"

    // Foundation slots
    const val BACKGROUND = "sk_background"
    const val TEXT = "sk_text"
    const val ACCENT = "sk_accent"
    const val BORDER = "sk_border"

    // Surface slots (ViewGroupTheme)
    const val HIGHLIGHT = "sk_highlight"
    const val SELECTED = "sk_selected"
    const val DIVIDER = "sk_divider"
    const val SPOT = "sk_spot"

    // Text slots (TextViewTheme)
    const val TEXT_HEADER = "sk_text_header"
    const val TEXT_PRIMARY = "sk_text_primary"
    const val TEXT_SECONDARY = "sk_text_secondary"
    const val TEXT_TERTIARY = "sk_text_tertiary"
    const val TEXT_QUATERNARY = "sk_text_quaternary"

    /**
     * The dim gold explanatory text on a settings page is drawn in — the descriptions
     * under a label and the thin rule down an item's left edge. A settable role rather
     * than a constant, so it follows a changed text colour instead of fighting it.
     */
    const val SETTINGS_NOTE = "sk_settings_note"

    // Icon slots (IconTheme)
    const val ICON_REGULAR = "sk_icon_regular"
    const val ICON_SECONDARY = "sk_icon_secondary"
    const val ICON_DISABLED = "sk_icon_disabled"

    // Accent slots
    const val ACCENT_PRIMARY = "sk_accent_primary"
    const val ACCENT_SECONDARY = "sk_accent_secondary"
    const val SWITCH_OFF = "sk_switch_off"

    /**
     * The dim, opaque yellow an outlined [app.simple.felicity.shiroikuma.SkSwitch] wears when
     * it is off. Separate from [SWITCH_OFF], which fills the track of the stock switches and
     * would be far too loud at this brightness.
     */
    const val SWITCH_OFF_DIM = "sk_switch_off_dim"

    // Button roles — every pill reads these three; a button that deletes lends BUTTON_DELETE
    // to its frame and label.
    const val BUTTON_TEXT = "sk_button_text"
    const val BUTTON_FRAME = "sk_button_frame"
    const val BUTTON_GROUND = "sk_button_ground"
    const val BUTTON_DELETE = "sk_button_delete"

    // Dialog roles, for the fork's own dialogs and for every one of upstream's
    const val DIALOG_GROUND = "sk_dialog_ground"
    const val DIALOG_FRAME = "sk_dialog_frame"
    const val DIALOG_TITLE = "sk_dialog_title"
    const val DIALOG_TEXT = "sk_dialog_text"
    const val DIALOG_NOTE = "sk_dialog_note"

    // Switch roles — outline and dot, on and off. The off outline is SWITCH_OFF_DIM.
    const val SWITCH_ON_OUTLINE = "sk_switch_on_outline"
    const val SWITCH_ON_DOT = "sk_switch_on_dot"
    const val SWITCH_OFF_DOT = "sk_switch_off_dot"

    /** The tick inside a checked check box — a selection mark, never a switch. */
    const val CHECK_TICK = "sk_check_tick"

    // Menu roles
    const val MENU_LABEL = "sk_menu_label"
    const val MENU_ROW_FRAME = "sk_menu_row_frame"
    const val MENU_DIVIDER = "sk_menu_divider"
    const val MENU_ICON_CONNECT = "sk_menu_icon_connect"
    const val MENU_ICON_WRITE = "sk_menu_icon_write"
    const val MENU_ICON_PERSON = "sk_menu_icon_person"
    const val MENU_ICON_SHARE = "sk_menu_icon_share"
    const val MENU_ICON_VIEW = "sk_menu_icon_view"
    const val MENU_ICON_BLOCK = "sk_menu_icon_block"
    const val MENU_ICON_DELETE = "sk_menu_icon_delete"

    // Section icons of the UI page — seven colours, each with one meaning
    const val SECTION_BASE = "sk_section_base"
    const val SECTION_CONTROLS = "sk_section_controls"
    const val SECTION_WORDS = "sk_section_words"
    const val SECTION_FRAME = "sk_section_frame"
    const val SECTION_REACH = "sk_section_reach"
    const val SECTION_WARNING = "sk_section_warning"
    const val SECTION_FLASH = "sk_section_flash"

    // The UI page's chip index
    const val CHIP_TEXT = "sk_chip_text"
    const val CHIP_FRAME = "sk_chip_frame"

    // Player slots
    const val VISUALIZER = "sk_visualizer"

    // UI page arrangement: section order and which sections are open. Plain sk_ keys, so they
    // travel in the UI export with the colours.
    const val UI_SECTION_ORDER = "sk_ui_section_order"
    const val UI_SECTIONS_OPEN = "sk_ui_sections_open"

    // Player behavior
    const val KEEP_SCREEN_ON = "sk_keep_screen_on"

    // Sizes
    const val BORDER_WIDTH = "sk_border_width"
    const val TEXT_SCALE = "sk_text_scale"
    const val FONT_WEIGHT_DELTA = "sk_font_weight_delta"

    const val RECENT_COLORS = "sk_recent_colors"

    const val PALETTE_BLACK = 0xFF000000.toInt()
    const val PALETTE_YELLOW = 0xFFFFFF00.toInt()
    const val PALETTE_BLUE = 0xFF0000FF.toInt() // PowerAmp-style visualizer blue

    // Colours that carry meaning — the settled defaults of the menu and section icon roles.
    const val PALETTE_GREEN = 0xFF00A550.toInt()
    const val PALETTE_HEAVY_BLUE = 0xFF0066FF.toInt()
    const val PALETTE_LIGHT_BLUE = 0xFF00B0FF.toInt()
    const val PALETTE_ORANGE = 0xFFFF8000.toInt()
    const val PALETTE_ROSE = 0xFFFF3D7F.toInt()
    const val PALETTE_RED = 0xFFFF0000.toInt()

    const val DEFAULT_BORDER_WIDTH = 1F
    const val MAX_BORDER_WIDTH = 8F
    const val DEFAULT_TEXT_SCALE = 1F
    const val MIN_TEXT_SCALE = 0.5F
    const val MAX_TEXT_SCALE = 2F
    const val MAX_FONT_WEIGHT_DELTA = 300
    const val MAX_RECENT_COLORS = 8

    val colorKeys = listOf(
            BACKGROUND, TEXT, ACCENT, BORDER,
            HIGHLIGHT, SELECTED, DIVIDER, SPOT,
            TEXT_HEADER, TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY, TEXT_QUATERNARY,
            ICON_REGULAR, ICON_SECONDARY, ICON_DISABLED,
            ACCENT_PRIMARY, ACCENT_SECONDARY, SWITCH_OFF, SWITCH_OFF_DIM, VISUALIZER,
            SETTINGS_NOTE,
            BUTTON_TEXT, BUTTON_FRAME, BUTTON_GROUND, BUTTON_DELETE,
            DIALOG_GROUND, DIALOG_FRAME, DIALOG_TITLE, DIALOG_TEXT, DIALOG_NOTE,
            SWITCH_ON_OUTLINE, SWITCH_ON_DOT, SWITCH_OFF_DOT, CHECK_TICK,
            MENU_LABEL, MENU_ROW_FRAME, MENU_DIVIDER,
            MENU_ICON_CONNECT, MENU_ICON_WRITE, MENU_ICON_PERSON, MENU_ICON_SHARE,
            MENU_ICON_VIEW, MENU_ICON_BLOCK, MENU_ICON_DELETE,
            SECTION_BASE, SECTION_CONTROLS, SECTION_WORDS, SECTION_FRAME,
            SECTION_REACH, SECTION_WARNING, SECTION_FLASH,
            CHIP_TEXT, CHIP_FRAME)

    /** Removes every stored colour, so each role shows its default again. */
    fun resetAllColors() {
        getSharedPreferences().edit { colorKeys.forEach { remove(it) } }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getSectionOrder(): List<String> {
        return getSharedPreferences().getString(UI_SECTION_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
    }

    fun setSectionOrder(order: List<String>) {
        getSharedPreferences().edit { putString(UI_SECTION_ORDER, order.joinToString(",")) }
    }

    fun clearSectionOrder() {
        getSharedPreferences().edit { remove(UI_SECTION_ORDER) }
    }

    fun getOpenSections(): Set<String> {
        return getSharedPreferences().getString(UI_SECTIONS_OPEN, null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    fun setOpenSections(open: Set<String>) {
        getSharedPreferences().edit { putString(UI_SECTIONS_OPEN, open.joinToString(",")) }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun isEnabled(): Boolean {
        return getSharedPreferences().getBoolean(UI_ENABLED, true)
    }

    fun setEnabled(enabled: Boolean) {
        getSharedPreferences().edit { putBoolean(UI_ENABLED, enabled) }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Whether the screen is kept awake while music is playing and the app is in the foreground. */
    fun isKeepScreenOnEnabled(): Boolean {
        return getSharedPreferences().getBoolean(KEEP_SCREEN_ON, true)
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        getSharedPreferences().edit { putBoolean(KEEP_SCREEN_ON, enabled) }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getStoredColor(key: String): Int {
        return getSharedPreferences().getInt(key, UNSET)
    }

    fun setColor(key: String, color: Int) {
        getSharedPreferences().edit { putInt(key, color) }
    }

    fun clearColor(key: String) {
        getSharedPreferences().edit { remove(key) }
    }

    fun isOverridden(key: String): Boolean {
        return getStoredColor(key) != UNSET
    }

    /**
     * Effective color of a slot: the stored override if present, otherwise the
     * value derived from the foundation slots (which themselves default to the
     * black-yellow palette).
     */
    fun getEffectiveColor(key: String): Int {
        val stored = getStoredColor(key)
        if (stored != UNSET) return stored

        val background = getStoredColor(BACKGROUND).takeIf { it != UNSET } ?: PALETTE_BLACK
        val text = getStoredColor(TEXT).takeIf { it != UNSET } ?: PALETTE_YELLOW
        val accent = getStoredColor(ACCENT).takeIf { it != UNSET } ?: PALETTE_YELLOW

        return when (key) {
            BACKGROUND -> background
            TEXT -> text
            ACCENT -> accent
            BORDER -> accent
            HIGHLIGHT -> ColorUtils.blendARGB(background, text, 0.10F)
            SELECTED -> ColorUtils.blendARGB(background, text, 0.18F)
            DIVIDER -> withAlpha(text, 0x3C)
            SPOT -> withAlpha(text, 0x1E)
            TEXT_HEADER -> text
            TEXT_PRIMARY -> text
            TEXT_SECONDARY -> withAlpha(text, 0xC8)
            TEXT_TERTIARY -> withAlpha(text, 0x96)
            TEXT_QUATERNARY -> withAlpha(text, 0x64)
            // 0.784 of the way from the background to the text: #C8C800 on black-yellow.
            SETTINGS_NOTE -> ColorUtils.blendARGB(background, text, 0.784F)
            ICON_REGULAR -> text
            ICON_SECONDARY -> withAlpha(text, 0xAA)
            ICON_DISABLED -> withAlpha(text, 0x55)
            ACCENT_PRIMARY -> accent
            ACCENT_SECONDARY -> ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25F)
            SWITCH_OFF -> ColorUtils.blendARGB(background, text, 0.25F)
            // 0.55 of the way from the background to the text: #8C8C00 on black-yellow.
            SWITCH_OFF_DIM -> ColorUtils.blendARGB(background, text, 0.55F)
            VISUALIZER -> PALETTE_BLUE
            BUTTON_TEXT, BUTTON_FRAME -> accent
            BUTTON_GROUND -> background
            BUTTON_DELETE -> PALETTE_RED
            DIALOG_GROUND -> background
            DIALOG_FRAME -> accent
            DIALOG_TITLE, DIALOG_TEXT -> text
            DIALOG_NOTE -> getEffectiveColor(TEXT_SECONDARY)
            SWITCH_ON_OUTLINE, SWITCH_ON_DOT -> accent
            SWITCH_OFF_DOT -> getEffectiveColor(SWITCH_OFF_DIM)
            CHECK_TICK -> background
            MENU_LABEL -> text
            MENU_ROW_FRAME -> accent
            // 0.7 of the way from the background to the accent: #B3B300 on black-yellow.
            MENU_DIVIDER -> ColorUtils.blendARGB(background, accent, 0.7F)
            MENU_ICON_CONNECT -> PALETTE_GREEN
            MENU_ICON_WRITE -> PALETTE_HEAVY_BLUE
            MENU_ICON_PERSON -> PALETTE_LIGHT_BLUE
            MENU_ICON_SHARE -> PALETTE_ORANGE
            MENU_ICON_VIEW -> accent
            MENU_ICON_BLOCK -> PALETTE_ROSE
            MENU_ICON_DELETE -> PALETTE_RED
            SECTION_BASE -> accent
            SECTION_CONTROLS -> PALETTE_GREEN
            SECTION_WORDS -> PALETTE_LIGHT_BLUE
            SECTION_FRAME -> PALETTE_HEAVY_BLUE
            SECTION_REACH -> PALETTE_ORANGE
            SECTION_WARNING -> PALETTE_RED
            SECTION_FLASH -> PALETTE_ROSE
            CHIP_TEXT, CHIP_FRAME -> accent
            else -> text
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getBorderWidth(): Float {
        return getSharedPreferences().getFloat(BORDER_WIDTH, DEFAULT_BORDER_WIDTH)
    }

    fun setBorderWidth(width: Float) {
        getSharedPreferences().edit { putFloat(BORDER_WIDTH, width.coerceIn(0F, MAX_BORDER_WIDTH)) }
    }

    fun getTextScale(): Float {
        return getSharedPreferences().getFloat(TEXT_SCALE, DEFAULT_TEXT_SCALE)
    }

    fun setTextScale(scale: Float) {
        getSharedPreferences().edit { putFloat(TEXT_SCALE, scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)) }
    }

    fun getFontWeightDelta(): Int {
        return getSharedPreferences().getInt(FONT_WEIGHT_DELTA, 0)
    }

    fun setFontWeightDelta(delta: Int) {
        getSharedPreferences().edit { putInt(FONT_WEIGHT_DELTA, delta.coerceIn(-MAX_FONT_WEIGHT_DELTA, MAX_FONT_WEIGHT_DELTA)) }
    }

    /**
     * Writes the shared corner-radius preference without the 1F floor that
     * [AppearancePreferences.setCornerRadius] applies, so the border/corner
     * sliders can go all the way to zero.
     */
    fun setCornerRadiusExact(radius: Float) {
        getSharedPreferences().edit { putFloat(AppearancePreferences.APP_CORNER_RADIUS, radius.coerceIn(0F, AppearancePreferences.MAX_CORNER_RADIUS)) }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getRecentColors(): List<Int> {
        val raw = getSharedPreferences().getString(RECENT_COLORS, null) ?: return emptyList()
        return raw.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    }

    fun addRecentColor(color: Int) {
        val list = (listOf(color) + getRecentColors().filter { it != color }).take(MAX_RECENT_COLORS)
        getSharedPreferences().edit { putString(RECENT_COLORS, list.joinToString(",") { it.toLong().toString() }) }
    }
}
