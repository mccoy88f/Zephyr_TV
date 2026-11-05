package link.mccoy88f.zephyr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.ImageView

/**
 * An icon with a halo round its own shape, as the clock and Spotlight's text have theirs (see applyTheme): its
 * outline blurred in [halo]'s colour behind it, a little lower in dark themes, so it reads on any photo. None while
 * it has focus (its lit ring does that). The halo is worked out again only when the icon or its size changes.
 */
internal class HaloImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    private var color = 0
    private var radius = 0f
    private var dy = 0f
    private var shadow: Bitmap? = null
    private var shadowKey: String? = null
    private val offset = IntArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The halo: its [color] (0: none), how soft ([radius], px) and how much lower ([dy], px). */
    fun halo(color: Int, radius: Float, dy: Float) {
        this.color = color
        this.radius = radius
        this.dy = dy
        shadowKey = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (color != 0 && !isFocused && drawable != null && width > 0 && height > 0) {
            shadowFor()?.let { s ->
                paint.color = color
                canvas.drawBitmap(s, offset[0].toFloat(), offset[1] + dy, paint)
            }
        }
        super.onDraw(canvas)
    }

    /** The icon's shape as drawn (padding, scale), blurred: kept while the icon and the view's size stay. */
    private fun shadowFor(): Bitmap? {
        val d = drawable ?: return null
        val key = "${System.identityHashCode(d)}/${d.bounds}/${d.level}/$width/$height/$radius"
        if (key == shadowKey && shadow?.isRecycled == false) return shadow
        shadow?.recycle()
        shadow = runCatching {
            val shape = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(shape)
            super.onDraw(c) // the icon where it's drawn: only its shape counts (its alpha), not its colour
            val blur = Paint().apply { maskFilter = BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }
            shape.extractAlpha(blur, offset).also { shape.recycle() }
        }.getOrNull()
        shadowKey = key
        return shadow
    }
}
