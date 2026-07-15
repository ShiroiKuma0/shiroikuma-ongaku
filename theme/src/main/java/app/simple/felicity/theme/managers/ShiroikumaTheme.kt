package app.simple.felicity.theme.managers

import android.content.res.Resources
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.theme.models.Accent
import app.simple.felicity.theme.models.IconTheme
import app.simple.felicity.theme.models.SwitchTheme
import app.simple.felicity.theme.models.TextViewTheme
import app.simple.felicity.theme.models.Theme
import app.simple.felicity.theme.models.ViewGroupTheme

/**
 * 白い熊 音楽 UI (fork-only): injects the user's color overrides into every
 * theme the app activates. Hooked into [ThemeManager]'s setters so the
 * overrides are applied after the stock theme's constructor has populated the
 * sub-themes but before any [app.simple.felicity.theme.interfaces.ThemeChangedListener]
 * is notified.
 */
object ShiroikumaTheme {

    const val ACCENT_IDENTIFIER = "shiroikuma"

    /** Last accent chosen by the stock pipeline, kept so disabling the overrides restores it. */
    private var stockAccent: Accent? = null

    fun apply(theme: Theme) {
        if (!ShiroikumaPreferences.isEnabled()) return

        theme.textViewTheme = TextViewTheme(
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_HEADER),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_PRIMARY),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_SECONDARY),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_TERTIARY),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_QUATERNARY))

        theme.viewGroupTheme = ViewGroupTheme(
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BACKGROUND),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.HIGHLIGHT),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.SELECTED),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.DIVIDER),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.SPOT))

        theme.iconTheme = IconTheme(
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ICON_REGULAR),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ICON_SECONDARY),
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ICON_DISABLED))

        theme.switchTheme = SwitchTheme(
                ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.SWITCH_OFF))
    }

    fun interceptAccent(value: Accent): Accent {
        if (value.identifier != ACCENT_IDENTIFIER) {
            stockAccent = value
        }

        return if (ShiroikumaPreferences.isEnabled()) {
            Accent(
                    ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT_PRIMARY),
                    ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT_SECONDARY),
                    ACCENT_IDENTIFIER)
        } else {
            if (value.identifier == ACCENT_IDENTIFIER) stockAccent ?: value else value
        }
    }

    /**
     * Re-derives the stock theme for the current preferences and re-fires both
     * setters so every registered listener repaints with the current override
     * set. Called by the 白い熊 音楽 UI page after each change.
     */
    fun refresh(resources: Resources) {
        ThemeUtils.setAppTheme(resources)
        ThemeManager.accent = ThemeManager.accent
    }
}
