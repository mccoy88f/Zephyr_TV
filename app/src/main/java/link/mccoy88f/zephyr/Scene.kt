package link.mccoy88f.zephyr

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView

/**
 * The home photograph, drifting, and the Glass panes that sample a tiny blurred copy of it.
 * The blur is made once, at 1/22, when the photo arrives. A pane costs one draw. The drift is redrawn once a
 * second (a step of under a pixel). Through a crossfade the panes follow every third frame: two dozen of them
 * on every frame is what makes a fade stutter on a small TV. While idle, the tick stops so the main thread rests.
 */

private const val BLUR_DIV = 22
private const val FADE_MS = 800L

internal fun blurPhoto(src: Bitmap): Bitmap {
    val w = (src.width / BLUR_DIV).coerceAtLeast(1)
    val h = (src.height / BLUR_DIV).coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(src, w, h, true)
    val tiny = if (scaled !== src && scaled.isMutable) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (scaled !== src) scaled.recycle() }
    boxBlur(tiny)
    return tiny
}

/** Two passes of a small box, in place. The bitmap is already a few dozen pixels on a side. */
private fun boxBlur(bmp: Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val src = IntArray(w * h)
    val dst = IntArray(w * h)
    bmp.getPixels(src, 0, w, 0, 0, w, h)
    for (pass in 0 until 2) {
        val from = if (pass == 0) src else dst
        val to = if (pass == 0) dst else src
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (ky in -1..1) {
                val yy = (y + ky).coerceIn(0, h - 1)
                for (kx in -1..1) {
                    val c = from[yy * w + (x + kx).coerceIn(0, w - 1)]
                    a += c ushr 24 and 0xFF
                    r += c ushr 16 and 0xFF
                    g += c ushr 8 and 0xFF
                    b += c and 0xFF
                    n++
                }
            }
            to[y * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
        }
    }
    bmp.setPixels(src, 0, w, 0, 0, w, h)
}

/**
 * One colour filter: the photo drained to [saturation], then each tint laid over in order. Folding the layers
 * into the filter means a pane is drawn in one pass.
 */
internal fun glassFilter(saturation: Float, vararg layers: Int): ColorMatrixColorFilter {
    var k = 1f
    var r = 0f
    var g = 0f
    var b = 0f
    for (c in layers) {
        val a = Color.alpha(c) / 255f
        r = r * (1f - a) + Color.red(c) * a
        g = g * (1f - a) + Color.green(c) * a
        b = b * (1f - a) + Color.blue(c) * a
        k *= 1f - a
    }
    val m = ColorMatrix()
    m.setSaturation(saturation)
    m.postConcat(ColorMatrix(floatArrayOf(
        k, 0f, 0f, 0f, r,
        0f, k, 0f, 0f, g,
        0f, 0f, k, 0f, b,
        0f, 0f, 0f, 1f, 0f,
    )))
    return ColorMatrixColorFilter(m)
}

private fun tint(rgb: Int, alpha: Float) = (rgb and 0x00FFFFFF) or ((255 * alpha).toInt().coerceIn(0, 255) shl 24)

/**
 * A band of light that crosses a pane once. The gradient is built once and moved.
 * It waits [GLINT_WAIT_MS] after focus arrives, and [cancel] drops it if focus has already moved on.
 */
internal class GlintDrawable(private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var pos = -1f
    private var running: ValueAnimator? = null
    private var ticket = 0

    /** Start after a short rest, so a fast pass along a row does not light every icon. */
    fun play() {
        if (running?.isRunning == true) return
        val view = callback as? View ?: return
        val mine = ++ticket
        view.postDelayed({
            if (mine == ticket) start(mine)
        }, GLINT_WAIT_MS)
    }

    /** Focus left before the light began, or while it was crossing: nothing stays lit behind. */
    fun cancel() {
        ticket++
        running?.cancel()
        running = null
        if (pos >= 0f) {
            pos = -1f
            invalidateSelf()
        }
    }

    private fun start(mine: Int) {
        if (mine != ticket || running?.isRunning == true) return
        if (bounds.width() == 0) {
            val view = callback as? View ?: return
            view.post { if (mine == ticket && bounds.width() > 0) start(mine) }
            return
        }
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = GLINT_MS
        anim.interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        anim.addUpdateListener {
            if (mine != ticket) return@addUpdateListener
            pos = if (it.animatedFraction >= 1f) -1f else it.animatedValue as Float
            invalidateSelf()
        }
        running = anim
        anim.start()
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        shader = LinearGradient(0f, 0f, w * 0.42f, h, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0.4f, 0.5f, 0.6f), Shader.TileMode.CLAMP)
        paint.shader = shader
    }

    override fun draw(canvas: Canvas) {
        val sh = shader ?: return
        if (pos < 0f) return
        val b = bounds
        val w = b.width().toFloat()
        matrix.setTranslate(b.left - w * 1.1f + pos * w * 2.2f, b.top.toFloat())
        sh.setLocalMatrix(matrix)
        canvas.drawRoundRect(RectF(b), radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A Glass pane: the blurred photograph under this view, or the theme's smoked tint when there is no photo
 * (a plain wallpaper, or All apps, whose sheet hides the photo).
 */
internal class GlassSlice(private val host: MainActivity, private val radius: Float) : Drawable() {
    private val fallback = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, host.cardRest).apply {
        cornerRadius = radius
    }
    var lit = false
        set(value) {
            field = value
            fallback.colors = if (value) host.cardLit else host.cardRest
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view == null || overAllApps(view) || !host.scene.drawBlur(canvas, RectF(bounds), radius, view, lit)) {
            fallback.bounds = bounds
            fallback.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun overAllApps(v: View): Boolean {
    var p = v.parent
    while (p is View) {
        if (p.id == R.id.allApps) return true
        p = p.parent
    }
    return false
}

/** The photograph behind the home screen, and the blurred copy the Glass panes sample. */
internal class Scene(private val host: MainActivity) {
    var generation = 0
        private set

    private var view: ImageView? = null
    private var current: Bitmap? = null
    private var currentBlur: Bitmap? = null
    private var previous: Bitmap? = null
    private var previousBlur: Bitmap? = null
    private var fadeStart = 0L
    private var moveStart = 0L
    private var frame = 0

    /** The rows' veil (see RowsVeil): drawn from this photo, so redrawn as it drifts. */
    var veil: View? = null
    private val veilPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veilMask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val blurM = Matrix()
    private var curShader: BitmapShader? = null
    private var prevShader: BitmapShader? = null
    private var curOf: Bitmap? = null
    private var prevOf: Bitmap? = null
    private val restFilter = glassFilter(0.4f, tint(0xFF101215.toInt(), 0.40f), tint(0xFFFFFDF9.toInt(), 0.11f))
    private val litFilter = glassFilter(0.4f, tint(0xFF101215.toInt(), 0.24f), tint(0xFFFFFDF9.toInt(), 0.32f))

    private val plate = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val w = bounds.width()
            val h = bounds.height()
            if (w == 0 || h == 0) return
            drawSharp(canvas, w, h)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!host.sceneAlive || current == null) return
            val now = android.os.SystemClock.uptimeMillis()
            val fading = fading(now)
            val drift = host.wallpaperDrift()
            // Empty room, or drift off and not mid-fade: freeze so the UI thread rests.
            if (!fading && (!drift || android.os.SystemClock.elapsedRealtime() - host.lastInput > SCENE_IDLE_MS)) {
                if (!fading && previous != null) dropPrevious()
                plate.invalidateSelf()
                return
            }
            // The previous photo is dropped before this frame is drawn, so a draw never sees a recycled bitmap.
            if (!fading && previous != null) dropPrevious()
            frame++
            plate.invalidateSelf()
            if (host.spotlightFolded || ((veil as? RowsVeil)?.folded ?: 0f) > 0f) veil?.invalidate() // the veil's photo drifts as the sharp one
            // Glass panes sample the drift: every tick while fading, else every 2 s (cheap enough to track Ken Burns).
            if (host.glass && (fading && frame % 3 == 0 || !fading && frame % 2 == 0)) {
                invalidatePanes(host.findViewById(R.id.root))
            }
            host.ui.postDelayed(this, if (fading) 33L else DRIFT_MS)
        }
    }

    fun bump(): Int {
        generation += 1
        return generation
    }

    fun show(wallpaper: ImageView, bmp: Bitmap, blur: Bitmap) {
        view = wallpaper
        wallpaper.scaleType = ImageView.ScaleType.FIT_XY
        wallpaper.setImageDrawable(plate)
        val dropS = previous
        val dropB = previousBlur
        previous = current
        previousBlur = currentBlur
        current = bmp
        currentBlur = blur
        fadeStart = if (previous == null) 0L else android.os.SystemClock.uptimeMillis()
        moveStart = fadeStart.takeIf { it != 0L } ?: android.os.SystemClock.uptimeMillis()
        if (dropS != null && dropS !== previous && dropS !== current) dropS.recycle()
        if (dropB != null && dropB !== previousBlur && dropB !== currentBlur) dropB.recycle()
        plate.invalidateSelf()
        arm()
    }

    fun clear() {
        host.ui.removeCallbacks(tick)
        view?.setImageDrawable(null)
        view = null
        dropPrevious()
        current?.recycle()
        currentBlur?.recycle()
        current = null
        currentBlur = null
        curShader = null
        prevShader = null
        curOf = null
        prevOf = null
    }

    fun arm() {
        host.ui.removeCallbacks(tick)
        if (current != null && host.sceneAlive) host.ui.post(tick)
    }

    fun stop() {
        host.ui.removeCallbacks(tick)
    }

    /** The blurred photo filling [r], as seen from [from]. False when there is nothing to sample. */
    fun drawBlur(canvas: Canvas, r: RectF, radius: Float, from: View, lit: Boolean): Boolean {
        val sharp = current ?: return false
        val blur = currentBlur ?: return false
        val wp = view ?: return false
        if (wp.width == 0 || wp.height == 0) return false
        val now = android.os.SystemClock.uptimeMillis()
        val fade = fadeAt(now)
        val loc = IntArray(2)
        val base = IntArray(2)
        from.getLocationInWindow(loc)
        wp.getLocationInWindow(base)
        val ox = (loc[0] - base[0]).toFloat()
        val oy = (loc[1] - base[1]).toFloat()
        shade.colorFilter = if (lit) litFilter else restFilter
        val prevB = previousBlur
        val prevS = previous
        if (prevB != null && prevS != null && fade < 1f) {
            val layer = canvas.saveLayer(r, null)
            blit(canvas, r, radius, prevS, prevB, ox, oy, wp.width, wp.height, now, 255, false)
            blit(canvas, r, radius, sharp, blur, ox, oy, wp.width, wp.height, now, (fade * 255).toInt(), true)
            canvas.restoreToCount(layer)
        } else {
            blit(canvas, r, radius, sharp, blur, ox, oy, wp.width, wp.height, now, 255, true)
        }
        return true
    }

    /**
     * The blurred photo, placed and drifting as the sharp one behind it ([w] by [h], the screen), from [top] down: it
     * fades in over [fade] pixels, so the photo stays sharp above and turns soft under. False with nothing to draw.
     */
    fun drawVeil(canvas: Canvas, w: Int, h: Int, top: Float, fade: Float): Boolean {
        val sharp = current ?: return false
        val blur = currentBlur ?: return false
        if (sharp.isRecycled || blur.isRecycled || w == 0 || h == 0) return false
        val layer = canvas.saveLayer(0f, top, w.toFloat(), h.toFloat(), null)
        place(sharp, w, h, android.os.SystemClock.uptimeMillis())
        blurM.set(m)
        blurM.preScale(sharp.width / blur.width.toFloat(), sharp.height / blur.height.toFloat())
        val shader = shaderFor(blur, true)
        shader.setLocalMatrix(blurM)
        veilPaint.shader = shader
        canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), veilPaint)
        veilMask.shader = LinearGradient(0f, top, 0f, top + fade, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), veilMask)
        canvas.restoreToCount(layer)
        return true
    }

    private fun drawSharp(canvas: Canvas, w: Int, h: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val fade = fadeAt(now)
        val prev = previous
        if (prev != null && fade < 1f && !prev.isRecycled) {
            place(prev, w, h, now)
            paint.alpha = 255
            canvas.drawBitmap(prev, m, paint)
        }
        val cur = current ?: return
        if (cur.isRecycled) return
        place(cur, w, h, now)
        paint.alpha = if (prev != null && fade < 1f) (fade * 255).toInt() else 255
        canvas.drawBitmap(cur, m, paint)
    }

    private fun blit(canvas: Canvas, r: RectF, radius: Float, sharp: Bitmap, blur: Bitmap, ox: Float, oy: Float,
                      viewW: Int, viewH: Int, now: Long, alpha: Int, currentOne: Boolean) {
        if (blur.isRecycled || sharp.isRecycled) return
        val shader = shaderFor(blur, currentOne)
        place(sharp, viewW, viewH, now)
        blurM.set(m)
        blurM.preScale(sharp.width / blur.width.toFloat(), sharp.height / blur.height.toFloat())
        blurM.postTranslate(-ox, -oy)
        shader.setLocalMatrix(blurM)
        shade.shader = shader
        shade.alpha = alpha
        canvas.drawRoundRect(r, radius, radius, shade)
    }

    private fun shaderFor(blur: Bitmap, currentOne: Boolean): BitmapShader {
        if (currentOne) {
            if (curOf !== blur || curShader == null) {
                curShader = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                curOf = blur
            }
            return curShader!!
        }
        if (prevOf !== blur || prevShader == null) {
            prevShader = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            prevOf = blur
        }
        return prevShader!!
    }

    /** Centre-crop, then (when drift is on) a slow zoom and pan that stays inside the spare margin. */
    private fun place(bmp: Bitmap, viewW: Int, viewH: Int, now: Long) {
        val base = maxOf(viewW / bmp.width.toFloat(), viewH / bmp.height.toFloat())
        if (!host.wallpaperDrift()) {
            m.setScale(base, base)
            m.postTranslate((viewW - bmp.width * base) / 2f, (viewH - bmp.height * base) / 2f)
            return
        }
        val span = DRIFT_SPAN_MS
        val u = ((now - moveStart).coerceAtLeast(0L) % (span * 2)).toFloat() / span
        val t = (if (u <= 1f) u else 2f - u).coerceIn(0f, 1f)
        val zoom = 1f + 0.12f * t
        val s = base * zoom
        val extraX = (bmp.width * s - viewW) / 2f
        val extraY = (bmp.height * s - viewH) / 2f
        m.setScale(s, s)
        m.postTranslate((viewW - bmp.width * s) / 2f + extraX * 0.35f * t, (viewH - bmp.height * s) / 2f + extraY * 0.25f * t)
    }

    private fun fading(now: Long) = previous != null && fadeStart != 0L && now - fadeStart < FADE_MS

    private fun fadeAt(now: Long): Float {
        if (previous == null || fadeStart == 0L) return 1f
        return ((now - fadeStart).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    private fun dropPrevious() {
        previous?.recycle()
        previousBlur?.recycle()
        previous = null
        previousBlur = null
        prevShader = null
        prevOf = null
    }

    private fun invalidatePanes(v: View?) {
        if (v == null) return
        val top = (v.background as? android.graphics.drawable.LayerDrawable)?.getDrawable(0)
        if (top is GlassSlice) top.invalidateSelf()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidatePanes(v.getChildAt(i))
    }
}
