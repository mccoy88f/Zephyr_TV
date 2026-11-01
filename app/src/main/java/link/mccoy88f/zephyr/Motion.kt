package link.mccoy88f.zephyr

/**
 * Motion shared by the home screen and the Gallery, with no Android types so the numbers can be tested on the JVM.
 * The spring is a damped cosine: almost all of the move is in the first part, then a settle of about one percent.
 */

internal const val FOCUS_IN_MS = 460L
internal const val FOCUS_OUT_MS = 240L
internal const val GLINT_MS = 1150L
internal const val SAFE_CRASHES = 3
internal const val SAFE_WINDOW_MS = 5 * 60_000L

/** Calm drift: twice a second. A step of well under a pixel, so the eye reads it as continuous. */
internal const val DRIFT_MS = 500L

/** One slow out-and-back of the wallpaper, so the photo never jumps when the move repeats. */
internal const val DRIFT_SPAN_MS = 180_000L

internal fun spring(t: Float): Float =
    (1.0 - Math.exp(-6.0 * t) * Math.cos(4.2 * t)).toFloat()

/** Crash times still inside the window, in the order they were stored. Blank and junk entries are dropped. */
internal fun crashTimes(saved: String, since: Long): List<Long> =
    saved.split(',').mapNotNull { it.toLongOrNull() }.filter { it >= since }

/**
 * Where a picture of [width]×[height] is drawn after [elapsed] ms. It grows a little and drifts, then comes back,
 * and at [spanMs] × 2 it sits where it started. The rectangle always covers the view.
 */
internal data class Drift(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun kenBurns(width: Float, height: Float, elapsed: Long, spanMs: Long): Drift {
    val span = spanMs.coerceAtLeast(1L)
    val u = (elapsed.coerceAtLeast(0L) % (span * 2)).toFloat() / span
    val t = (if (u <= 1f) u else 2f - u).coerceIn(0f, 1f)
    val zoom = 1f + 0.08f * t
    val dw = width * zoom
    val dh = height * zoom
    val left = (width - dw) / 2f - width * 0.03f * t
    val top = (height - dh) / 2f - height * 0.02f * t
    return Drift(left, top, left + dw, top + dh)
}
