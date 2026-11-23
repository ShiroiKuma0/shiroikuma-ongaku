package app.simple.felicity.decorations.shiroikuma

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ShiroikumaPreferences
import java.util.Locale

/**
 * Fork (白い熊 音楽 UI): the black-yellow pill, drawn in one place for every button and menu
 * row upstream builds from a [app.simple.felicity.decorations.ripple.DynamicRippleTextView] or
 * a popup row.
 *
 * - A button is an outlined pill that is never filled: 3 dp and bold for the main action of
 *   its block, 1.25 dp and regular otherwise; a button that deletes lends its red to frame
 *   and label.
 * - A menu row is the same pill spanning the menu's width, always 1.25 dp, with its icon
 *   coloured by what the row does.
 *
 * Every colour comes from a settable role in [ShiroikumaPreferences]. What a view does is told
 * from the name of its id (or of a popup row's title string), since upstream's layouts carry
 * no such flag and stay untouched.
 */
object SkPills {

    /** What a row does — decides the colour of its icon. */
    enum class Role(val key: String) {
        CONNECT(ShiroikumaPreferences.MENU_ICON_CONNECT),
        WRITE(ShiroikumaPreferences.MENU_ICON_WRITE),
        PERSON(ShiroikumaPreferences.MENU_ICON_PERSON),
        SHARE(ShiroikumaPreferences.MENU_ICON_SHARE),
        VIEW(ShiroikumaPreferences.MENU_ICON_VIEW),
        BLOCK(ShiroikumaPreferences.MENU_ICON_BLOCK),
        DELETE(ShiroikumaPreferences.MENU_ICON_DELETE)
    }

    @JvmStatic
    fun enabled(): Boolean = ShiroikumaPreferences.isEnabled()

    @JvmStatic
    fun color(key: String): Int = ShiroikumaPreferences.getEffectiveColor(key)

    @JvmStatic
    fun roleColor(role: Role): Int = color(role.key)

    // ------------------------------------------------------------------ what a name means

    private val deleteWords = arrayOf("delete", "remove", "purge", "clear_stats", "clear_all", "wipe")
    private val blockWords = arrayOf("skip", "clear_selection", "cancel", "close", "stop", "hide", "exclude", "never", "later", "block")
    private val shareWords = arrayOf("share", "send", "export", "copy", "paste", "add_to", "add_all", "queue", "playlist",
                                     "favorite", "selection", "import", "save", "bookmark")
    private val personWords = arrayOf("artist", "album", "composer", "genre", "people", "year", "folder")
    private val writeWords = arrayOf("edit", "lyrics", "rename", "label", "create", "metadata", "search", "pick", "input")
    private val connectWords = arrayOf("play", "shuffle", "start", "refresh", "rescan", "sync", "reset", "resume", "download",
                                       "apply", "replace", "insert", "till_this", "recently", "most_played")

    private val mainNames = setOf("sure", "save", "create", "apply", "ok", "yes", "done", "import_btn", "button_apply",
                                  "start_button", "start_app_now", "save_button", "purchase_now", "button_search")

    private val opensWords = arrayOf("info", "lyrics", "edit", "add_to_playlist", "bookmarks", "view_", "go_to", "open_",
                                     "share", "send", "pick", "menu", "download_album_art", "add_to_selection")

    /** Ids of text views that are tappable but are not buttons: labels, list entries, values. */
    private val notButtons = setOf("artist", "album", "language", "name", "preset_name", "bio_toggle",
                                   "count", "hours", "sort_style", "grid_size", "grid_type")

    @JvmStatic
    fun roleForName(name: String): Role {
        val n = name.lowercase(Locale.ROOT)
        return when {
            deleteWords.any { n.contains(it) } -> Role.DELETE
            blockWords.any { n.contains(it) } -> Role.BLOCK
            shareWords.any { n.contains(it) } -> Role.SHARE
            personWords.any { n.contains(it) } -> Role.PERSON
            writeWords.any { n.contains(it) } -> Role.WRITE
            connectWords.any { n.contains(it) } -> Role.CONNECT
            else -> Role.VIEW
        }
    }

    /**
     * The role of a row in a navigation or settings list. Such rows all open a screen, so the
     * colour says what the screen is about: sound and the library in the connect green, words
     * in the write blue, people and collections in the person blue, queues and playlists in
     * the share orange, skipped songs in the block rose; everything else stays in the accent.
     */
    @JvmStatic
    fun listRoleForName(name: String): Role {
        val n = name.lowercase(Locale.ROOT)
        return when {
            arrayOf("audio", "engine", "equalizer", "library", "scan", "song").any { n.contains(it) } -> Role.CONNECT
            arrayOf("language", "about", "accessib", "font", "typeface", "lyrics").any { n.contains(it) } -> Role.WRITE
            else -> roleForName(n)
        }
    }

    @JvmStatic
    fun opensSomething(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return opensWords.any { n.contains(it) }
    }

    @JvmStatic
    fun idName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------ drawables

    /**
     * The pill itself: fully round, the ground showing through unless the button-ground role
     * was given a colour, framed in [frame]. [insetH] / [insetV] keep neighbouring pills apart
     * without touching the view's own padding.
     */
    @JvmStatic
    fun pill(context: Context, frame: Int, main: Boolean, insetH: Float = 0F, insetV: Float = 0F): Drawable {
        val density = context.resources.displayMetrics.density
        val ground = if (ShiroikumaPreferences.isOverridden(ShiroikumaPreferences.BUTTON_GROUND)) {
            color(ShiroikumaPreferences.BUTTON_GROUND)
        } else {
            Color.TRANSPARENT
        }
        val shape = GradientDrawable().apply {
            setColor(ground)
            cornerRadius = 100 * density
            setStroke(((if (main) 3F else 1.25F) * density).toInt().coerceAtLeast(1), frame)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 100 * density
        }
        val ripple = RippleDrawable(ColorStateList.valueOf((frame and 0x00FFFFFF) or 0x4D000000), shape, mask)
        if (insetH == 0F && insetV == 0F) return ripple
        return InsetDrawable(ripple, (insetH * density).toInt(), (insetV * density).toInt(),
                             (insetH * density).toInt(), (insetV * density).toInt())
    }

    /** The › of a row that opens something, drawn as text so it follows the row's colour. */
    class Chevron(private val color: Int, private val sizePx: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@Chevron.color
            textSize = sizePx
            textAlign = Paint.Align.CENTER
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val fm = paint.fontMetrics
            canvas.drawText("›", b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2F, paint)
        }

        override fun getIntrinsicWidth(): Int = (sizePx * 0.6F).toInt()
        override fun getIntrinsicHeight(): Int = sizePx.toInt()
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            // The chevron keeps the row's own colour; an icon tint must not reach it.
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ------------------------------------------------------------------ upstream's text buttons

    /** Whether [view] is one of upstream's text buttons or menu rows, to be drawn as a pill. */
    @JvmStatic
    fun isButton(view: TextView): Boolean {
        if (!enabled()) return false
        return idName(view) !in notButtons
    }

    /** A full-width text button in a vertical column is a row of a menu sheet. */
    private fun isMenuRow(view: TextView): Boolean {
        val params = view.layoutParams ?: return false
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) return false
        val parent = view.parent as? LinearLayout ?: return true
        return parent.orientation == LinearLayout.VERTICAL
    }

    /** The colour of the compound icon of [view], or null to leave upstream's tint. */
    @JvmStatic
    fun iconTint(view: TextView): Int? {
        if (!enabled()) return null
        val name = idName(view)
        if (name.isEmpty() || name in notButtons) return null
        if (view !is app.simple.felicity.decorations.ripple.DynamicRippleTextView) return null
        return roleColor(roleForName(name))
    }

    /**
     * Draws [view] as a pill. Called whenever upstream would have set its plain ripple
     * background: when a click listener is attached, and again on every theme change.
     */
    @JvmStatic
    fun style(view: TextView) {
        val context = view.context
        val name = idName(view)
        val menuRow = isMenuRow(view)
        val deletes = view.tag == "sk_delete" || deleteWords.any { name.contains(it) }
        val main = !menuRow && name in mainNames

        val frame = when {
            deletes -> color(ShiroikumaPreferences.BUTTON_DELETE)
            menuRow -> color(ShiroikumaPreferences.MENU_ROW_FRAME)
            else -> color(ShiroikumaPreferences.BUTTON_FRAME)
        }
        val label = when {
            deletes -> color(ShiroikumaPreferences.BUTTON_DELETE)
            menuRow -> color(ShiroikumaPreferences.MENU_LABEL)
            else -> color(ShiroikumaPreferences.BUTTON_TEXT)
        }

        view.background = if (menuRow) pill(context, frame, false, insetH = 0F, insetV = 3F)
        else pill(context, frame, main, insetH = 4F, insetV = 5F)
        view.backgroundTintList = null
        view.setTextColor(label)
        view.typeface = TypeFace.getTypeFace(AppearancePreferences.getAppFont(),
                                             if (main) TypeFaceTextView.BOLD else TypeFaceTextView.REGULAR, context)
        view.alpha = if (view.isEnabled) 1F else 0.38F

        if (menuRow) {
            val density = context.resources.displayMetrics.density
            // The icon sits 14 dp inside the pill; the label keeps its distance from the frame.
            val side = (14 * density).toInt()
            if (view.paddingStart < side || view.paddingEnd < side) {
                view.setPaddingRelative(maxOf(view.paddingStart, side), view.paddingTop,
                                        maxOf(view.paddingEnd, side), view.paddingBottom)
            }
            val drawables = TextViewCompat.getCompoundDrawablesRelative(view)
            val wantsChevron = opensSomething(name)
            val hasChevron = drawables[2] is Chevron
            if (wantsChevron || hasChevron) {
                val chevron = if (wantsChevron) Chevron(label, view.textSize * 1.4F).apply {
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                } else null
                drawables[0]?.let { if (it.bounds.isEmpty) it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight) }
                view.setCompoundDrawablesRelative(drawables[0], drawables[1], chevron, drawables[3])
            }
        }
        iconTint(view)?.let { TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(it)) }
    }

    // ------------------------------------------------------------------ popup menus

    /** The name of a popup row: its title's string resource name, or nothing for a literal. */
    private fun titleName(context: Context, title: Any): String {
        if (title !is Int) return ""
        return try {
            context.resources.getResourceEntryName(title)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * A popup whose rows carry icons is an action menu and gets pills; one without any icon is
     * a pure choice list (sort orders, styles, alignments) and stays a plain column.
     */
    @JvmStatic
    fun isActionMenu(hasAnyIcon: Boolean): Boolean = enabled() && hasAnyIcon

    /** Draws a popup row as a full-width pill. Returns false when the row stays upstream's. */
    @JvmStatic
    fun menuRow(row: View, hasAnyIcon: Boolean, title: Any): Boolean {
        if (!isActionMenu(hasAnyIcon)) return false
        val deletes = roleForName(titleName(row.context, title)) == Role.DELETE
        val frame = if (deletes) color(ShiroikumaPreferences.BUTTON_DELETE) else color(ShiroikumaPreferences.MENU_ROW_FRAME)
        val density = row.resources.displayMetrics.density
        row.background = pill(row.context, frame, false, insetH = 0F, insetV = 3F)
        // The icon sits 14 dp inside the pill.
        row.setPaddingRelative((14 * density).toInt(), row.paddingTop, (14 * density).toInt(), row.paddingBottom)
        return true
    }

    @JvmStatic
    fun menuIconColor(context: Context, hasAnyIcon: Boolean, title: Any, fallback: Int): Int {
        if (!isActionMenu(hasAnyIcon)) return fallback
        return roleColor(roleForName(titleName(context, title)))
    }

    @JvmStatic
    fun menuLabelColor(context: Context, hasAnyIcon: Boolean, title: Any, fallback: Int): Int {
        if (!isActionMenu(hasAnyIcon)) return fallback
        return if (roleForName(titleName(context, title)) == Role.DELETE) color(ShiroikumaPreferences.BUTTON_DELETE)
        else color(ShiroikumaPreferences.MENU_LABEL)
    }

    /** Adds the › at the right end of a popup row that opens something. */
    @JvmStatic
    fun addMenuChevron(row: LinearLayout, hasAnyIcon: Boolean, title: Any) {
        if (!isActionMenu(hasAnyIcon)) return
        val name = titleName(row.context, title)
        if (!opensSomething(name)) return
        val density = row.resources.displayMetrics.density
        row.addView(View(row.context), LinearLayout.LayoutParams(0, 0, 1F))
        row.addView(TextView(row.context).apply {
            text = "›"
            textSize = 20F
            includeFontPadding = false
            setTextColor(menuLabelColor(context, hasAnyIcon, title, Color.TRANSPARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (10 * density).toInt()
        })
    }

    // ------------------------------------------------------------------ label icons

    /**
     * An icon that keeps the colour it was given. A text view applies its compound-drawable
     * tint to every drawable it holds, and upstream's text view sets one from its own tint
     * mode on every theme change; an icon coloured by what its row does must survive that.
     */
    class Icon(private val inner: Drawable, color: Int, private val size: Int) : Drawable() {
        init {
            inner.mutate()
            inner.setTint(color)
            inner.setBounds(0, 0, size, size)
        }

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top + (bounds.height() - size) / 2F)
            inner.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun getIntrinsicWidth(): Int = size
        override fun getIntrinsicHeight(): Int = size
        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun setTintList(tint: ColorStateList?) {
            // Deliberately ignored — see the class comment.
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            // Deliberately ignored — see the class comment.
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Icon size for a label: 1.25 × its text size, never under 18 dp. */
    @JvmStatic
    fun iconSizeFor(label: TextView): Int {
        val floor = 18 * label.resources.displayMetrics.density
        return maxOf(label.textSize * 1.25F, floor).toInt()
    }

    /**
     * Puts [iconRes] at the start of [label], in [color] and sized by the label's text. The
     * size is read again at every layout, so a font or size change that arrives later is
     * followed.
     */
    @JvmStatic
    fun labelIcon(label: TextView, iconRes: Int, color: Int) {
        fun apply(): Int {
            val size = iconSizeFor(label)
            val drawable = androidx.core.content.ContextCompat.getDrawable(label.context, iconRes) ?: return size
            val icon = Icon(drawable, color, size).apply { setBounds(0, 0, size, size) }
            val current = TextViewCompat.getCompoundDrawablesRelative(label)
            label.compoundDrawablePadding = size / 2
            label.setCompoundDrawablesRelative(icon, current[1], current[2], current[3])
            return size
        }

        var applied = apply()
        (label.getTag(app.simple.felicity.decoration.R.id.sk_label_icon_listener) as? View.OnLayoutChangeListener)
            ?.let { label.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (iconSizeFor(label) != applied) label.post { applied = apply() }
        }
        label.setTag(app.simple.felicity.decoration.R.id.sk_label_icon_listener, listener)
        label.addOnLayoutChangeListener(listener)
    }
}
