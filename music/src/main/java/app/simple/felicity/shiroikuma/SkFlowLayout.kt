package app.simple.felicity.shiroikuma

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Fork (白い熊 音楽 UI): a wrapping row layout for the UI page's chip index. Every child keeps
 * its own measured width — chips are exactly as wide as their icon, label and padding — and a
 * child that no longer fits starts the next line.
 */
class SkFlowLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private val gap = (6 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            x += child.measuredWidth + gap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y,
                         paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }

    /** Index of the child whose slot holds the point, or -1 when it is over none. */
    fun indexAt(px: Float, py: Float): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (px >= child.left - gap / 2F && px < child.right + gap / 2F &&
                    py >= child.top - gap / 2F && py < child.bottom + gap / 2F) {
                return i
            }
        }
        return -1
    }
}
