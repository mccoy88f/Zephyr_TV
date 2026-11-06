package link.mccoy88f.zephyr

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView

/**
 * One line of [text] too long for its place, moved sideways on its own: a pause, to its end, a pause, back.
 * TextView's marquee starts over whenever its line is laid out again at another width, which a value changing
 * beside it (a percentage, a speed, each second) did all the time: this one starts over only when its text no
 * longer takes the same room. [on]: whether it moves (always, or while its row has the focus).
 */
internal class Ticker(context: Context, val text: TextView, on: Boolean = true) : FrameLayout(context) {
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

    /** Where the text is at [t] ms into a round: a pause, to its end, a pause, back. */
    private fun offset(t: Long, move: Long): Float = when {
        t < PAUSE_MS -> 0f
        t < PAUSE_MS + move -> -overflow * (t - PAUSE_MS).toFloat() / move
        t < 2 * PAUSE_MS + move -> -overflow.toFloat()
        else -> -overflow * (1f - (t - 2 * PAUSE_MS - move).toFloat() / move)
    }

    private var started = 0L

    // Moved at each frame by hand, not by an animator: a TV with its animations turned off (Developer options, or
    // Accessibility) runs animators straight to their end, and the text wouldn't move.
    private val step = object : Runnable {
        override fun run() {
            if (!on || overflow <= 0 || !isAttachedToWindow) return
            val move = (overflow / (40 * resources.displayMetrics.density) * 1000).toLong().coerceAtLeast(800)
            text.translationX = offset((android.os.SystemClock.uptimeMillis() - started) % (2 * (PAUSE_MS + move)), move)
            postOnAnimation(this)
        }
    }

    private fun restart() {
        removeCallbacks(step)
        text.translationX = 0f
        if (!on || overflow <= 0 || !isAttachedToWindow) return
        started = android.os.SystemClock.uptimeMillis()
        postOnAnimation(step)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(step)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PAUSE_MS = 1500L
    }
}
