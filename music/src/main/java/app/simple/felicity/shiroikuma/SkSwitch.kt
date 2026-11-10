package app.simple.felicity.shiroikuma

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Checkable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import app.simple.felicity.preferences.ShiroikumaPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * The fork's switch (白い熊, 2026-11-10): an outlined pill — never filled — with a solid dot that
 * sits on the right when on and on the left when off.
 *
 * Upstream's `FelicitySwitch` paints a filled track and a hollow **white** ring, and white is the
 * one colour the black-yellow theme has no place for. The shape here is 白い熊 自由作業盤's, with
 * two corrections 白い熊 asked for: the on state is outlined rather than a filled yellow slab, and
 * the off state is the same outline in a dimmer, opaque yellow rather than a faded bright one.
 *
 * Both colours are settable roles, so neither is a constant: on is the accent, off is
 * [ShiroikumaPreferences.SWITCH_OFF_DIM].
 */
class SkSwitch @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Checkable {

    fun interface OnCheckedChangeListener {
        fun onCheckedChanged(view: SkSwitch, isChecked: Boolean)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val track = RectF()

    private var checkedState = false

    /** 0 is off, 1 is on. The dot's travel and the colour both follow it. */
    private var progress = 0F
    private var animator: ValueAnimator? = null
    private var listener: OnCheckedChangeListener? = null

    @ColorInt
    private var onColor = 0

    @ColorInt
    private var offColor = 0

    init {
        refreshColors()
    }

    /** Re-reads both colours. The settings page calls this after a theme change. */
    fun refreshColors() {
        val forkTheme = ShiroikumaPreferences.isEnabled()
        onColor = if (forkTheme) {
            ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.ACCENT)
        } else {
            ThemeManager.accent.primaryAccentColor
        }
        offColor = if (forkTheme) {
            ShiroikumaPreferences.getEffectiveColor(ShiroikumaPreferences.SWITCH_OFF_DIM)
        } else {
            ThemeManager.theme.switchTheme.switchOffColor
        }
        invalidate()
    }

    // ------------------------------------------------------------------ Checkable

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) {
        if (checkedState == checked) return
        checkedState = checked
        animateTo(if (checked) 1F else 0F)
        listener?.onCheckedChanged(this, checked)
    }

    override fun toggle() {
        isChecked = !checkedState
    }

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        this.listener = listener
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ drawing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
                resolveSize(dp(TRACK_WIDTH_DP) + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(dp(TRACK_HEIGHT_DP) + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(STROKE_DP).toFloat().coerceAtLeast(1F)
        val color = ColorUtils.blendARGB(offColor, onColor, progress)

        val height = (height - paddingTop - paddingBottom).toFloat()
        val width = (width - paddingLeft - paddingRight).toFloat()
        track.set(paddingLeft + stroke / 2F,
                  paddingTop + stroke / 2F,
                  paddingLeft + width - stroke / 2F,
                  paddingTop + height - stroke / 2F)

        trackPaint.color = color
        trackPaint.strokeWidth = stroke
        canvas.drawRoundRect(track, track.height() / 2F, track.height() / 2F, trackPaint)

        // The dot travels between the two ends, inset from the frame by the same gap all round.
        val radius = height * DOT_FRACTION / 2F
        val inset = stroke / 2F + (track.height() / 2F - radius)
        val left = track.left + inset + radius
        val right = track.right - inset - radius
        thumbPaint.color = color
        canvas.drawCircle(left + (right - left) * progress, track.centerY(), radius, thumbPaint)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TRACK_WIDTH_DP = 52F
        private const val TRACK_HEIGHT_DP = 28F
        private const val STROKE_DP = 2F

        /** The dot, as a share of the track's height. */
        private const val DOT_FRACTION = 0.46F

        private const val ANIMATION_MS = 180L
    }
}
