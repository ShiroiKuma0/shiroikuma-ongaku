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

    // Icon slots (IconTheme)
    const val ICON_REGULAR = "sk_icon_regular"
    const val ICON_SECONDARY = "sk_icon_secondary"
    const val ICON_DISABLED = "sk_icon_disabled"

    // Accent slots
    const val ACCENT_PRIMARY = "sk_accent_primary"
    const val ACCENT_SECONDARY = "sk_accent_secondary"
    const val SWITCH_OFF = "sk_switch_off"

    // Player slots
    const val VISUALIZER = "sk_visualizer"

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
            ACCENT_PRIMARY, ACCENT_SECONDARY, SWITCH_OFF, VISUALIZER)

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
            ICON_REGULAR -> text
            ICON_SECONDARY -> withAlpha(text, 0xAA)
            ICON_DISABLED -> withAlpha(text, 0x55)
            ACCENT_PRIMARY -> accent
            ACCENT_SECONDARY -> ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25F)
            SWITCH_OFF -> ColorUtils.blendARGB(background, text, 0.25F)
            VISUALIZER -> PALETTE_BLUE
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
