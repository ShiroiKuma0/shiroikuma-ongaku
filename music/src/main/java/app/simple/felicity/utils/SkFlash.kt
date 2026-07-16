package app.simple.felicity.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * Fork (白い熊 音楽): themed replacement for [Toast.makeText] — a flash message
 * that follows the 白い熊 UI layer instead of the stock system toast chrome.
 *
 * Styling comes from [ShiroikumaPreferences] when the fork theme is enabled
 * (background / primary text / border colors, border width) and falls back to
 * the active [ThemeManager] theme otherwise. Corner radius and app font follow
 * the shared appearance preferences, so a flash always looks like the rest of
 * the app.
 *
 * Custom toast views are deprecated because they are ignored for backgrounded
 * apps on API 30+; every call site here fires from the foreground UI, where
 * they still render fine — hence the local `@Suppress("DEPRECATION")`.
 */
object SkFlash {

    /** Shows a themed flash with [text]; [long] maps to [Toast.LENGTH_LONG]. */
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMainThread(appContext, text, long)
        } else {
            // Toasts need a Looper thread; hop to main so background callers just work.
            Handler(Looper.getMainLooper()).post {
                showOnMainThread(appContext, text, long)
            }
        }
    }

    /** Shows a themed flash with the string resource [resId]. */
    fun show(context: Context, @StringRes resId: Int, long: Boolean = false) {
        show(context, context.getString(resId), long)
    }

    private fun showOnMainThread(context: Context, text: CharSequence, long: Boolean) {
        val toast = Toast(context)
        toast.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        @Suppress("DEPRECATION")
        toast.view = buildView(context, text)
        toast.show()
    }

    /** Builds the flash view: bordered rounded card, themed colors, app typeface. */
    private fun buildView(context: Context, text: CharSequence): TextView {
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

        val density = context.resources.displayMetrics.density

        val background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = AppearancePreferences.getCornerRadius()
            val borderWidth = ShiroikumaPreferences.getBorderWidth()
            if (borderWidth > 0F) {
                setStroke((borderWidth * density).toInt().coerceAtLeast(1), borderColor)
            }
        }

        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15F)
            typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(), TypeFaceTextView.MEDIUM, context)
            this.background = background
            setPadding((24 * density).toInt(), (14 * density).toInt(),
                       (24 * density).toInt(), (14 * density).toInt())
        }
    }
}
