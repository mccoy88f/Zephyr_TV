package link.mccoy88f.zephyr

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The rows under Spotlight: apps, then a row per section of the focused app's recommendations. Only the apps and
 * the first section count towards where it sits, so the apps row stays put however many sections the app has;
 * the rest (the last child) hang below, past the bottom edge, until focus goes down and the page slides up to
 * them. They stay inside its bounds (a negative bottom margin takes their height back), because views outside
 * their parent's bounds aren't reliably drawn.
 */
class BottomSection(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private fun isHandheld(): Boolean {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is MainActivity) return ctx.handheld()
            ctx = ctx.baseContext
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Children at their full height: the parent would squeeze the overflow.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val more = if (childCount > 0) getChildAt(childCount - 1) else null
        val hanging = if (more == null || more.visibility == GONE || isHandheld()) 0 else more.measuredHeight
        // Read by the parent after this measure, so its spacer only makes room for the rest.
        (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = -hanging
    }
}
