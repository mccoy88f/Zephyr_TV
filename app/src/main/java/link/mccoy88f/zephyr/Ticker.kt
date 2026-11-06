package link.mccoy88f.zephyr

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * One line of [text] too long for its place, moved sideways by an animation of its own: to its end, a pause, back.
 * TextView's marquee starts over whenever its line is laid out again at another width, which a value changing
 * beside it (a percentage, a speed, each second) did all the time: this one starts over only when its text no
 * longer takes the same room. [on]: whether it moves (always, or while its row has the focus).
 */
internal class Ticker(context: Context, val text: TextView, on: Boolean = true) : FrameLayout(context) {
    private var anim: ValueAnimator? = null
    private var overflow = 0

    var on: Boolean = on
        set(v) {
            if (field == v) return
            field = v
            restart()
        }

    init {
        clipChildren = true
        text.isSingleLine = true
        text.ellipsize = null
        text.setHorizontallyScrolling(true)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The text as wide as it is; this view as wide as its place lets it be.
        text.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, LayoutParams.WRAP_CONTENT))
        setMeasuredDimension(resolveSize(text.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(text.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        text.layout(paddingLeft, paddingTop, paddingLeft + text.measuredWidth, paddingTop + text.measuredHeight)
        val over = text.measuredWidth - (width - paddingLeft - paddingRight)
        if (over != overflow) {
            overflow = over
            restart()
        }
    }

    private fun restart() {
        anim?.cancel()
        anim = null
        text.translationX = 0f
        if (!on || overflow <= 0 || !isAttachedToWindow) return
        val to = -overflow.toFloat()
        // A pause at each end (a seventh of the time), at about 40 dp a second in between.
        val move = PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X,
            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.15f, 0f), Keyframe.ofFloat(0.85f, to), Keyframe.ofFloat(1f, to))
        val seconds = overflow / (40 * resources.displayMetrics.density)
        anim = ObjectAnimator.ofPropertyValuesHolder(text, move).apply {
            duration = ((seconds / 0.7f) * 1000).toLong().coerceAtLeast(2500)
            interpolator = LinearInterpolator()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }
}
