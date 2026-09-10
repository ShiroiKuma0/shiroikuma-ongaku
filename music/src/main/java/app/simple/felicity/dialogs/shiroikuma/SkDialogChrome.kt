package app.simple.felicity.dialogs.shiroikuma

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * The fork's dialog look — black-yellow bordered window, pill buttons, app typeface — for the
 * dialogs that live outside the UI page (which carries its own copy of the same recipe).
 */
object SkDialogChrome {

    fun backgroundColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BACKGROUND)
        else ThemeManager.theme.viewGroupTheme.backgroundColor

    fun accentColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT)
        else ThemeManager.accent.primaryAccentColor

    fun textColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_PRIMARY)
        else ThemeManager.theme.textViewTheme.primaryTextColor

    fun secondaryTextColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.TEXT_SECONDARY)
        else ThemeManager.theme.textViewTheme.secondaryTextColor

    fun borderColor(): Int =
        if (ShiroikumaPreferences.isEnabled()) ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.BORDER)
        else ThemeManager.accent.primaryAccentColor

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** A themed text view: [sp] size, [style] weight, [color] or the primary text colour. */
    fun text(context: Context, text: CharSequence, sp: Float, style: Int = TypeFaceTextView.MEDIUM,
             color: Int = textColor()): TypeFaceTextView = TypeFaceTextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), style, context)
    }

    /** A pill button in the accent colour, the shape the UI page's Export/Import buttons have. */
    fun pill(context: Context, label: CharSequence, onClick: () -> Unit): Button {
        val density = context.resources.displayMetrics.density
        val accent = accentColor()
        return Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            stylePill(this, accent, density, context)
            setOnClickListener { onClick() }
        }
    }

    /** Frame the window and restyle whatever buttons the builder created; call once shown. */
    fun style(dialog: AlertDialog) {
        val context = dialog.context
        val density = context.resources.displayMetrics.density
        val accent = accentColor()

        dialog.window?.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
            setColor(backgroundColor())
            cornerRadius = AppearancePreferences.getCornerRadius()
            setStroke((2 * density).toInt(), borderColor())
        }, (16 * density).toInt()))

        for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            stylePill(button, accent, density, context)
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = dp(context, 8)
                button.layoutParams = params
            }
        }
    }

    private fun stylePill(button: Button, accent: Int, density: Float, context: Context) {
        val pill = GradientDrawable().apply {
            setColor(backgroundColor())
            cornerRadius = 50 * density // > half the height -> a pill
            setStroke((1.5F * density).toInt(), accent)
        }
        button.background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), pill, null)
        button.setTextColor(accent)
        button.transformationMethod = null // no all-caps
        button.typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.BOLD, context)
        button.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
        button.minHeight = 0
        button.minimumHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
    }
}
