package link.mccoy88f.zephyr

import kotlin.math.abs
import kotlin.math.roundToInt

/** The screen's refresh rate for a film's frame rate (see PlayerActivity.matchFrameRate). */
internal object FrameRate {
    /** How long the film waits while the TV changes mode: the picture goes dark for a moment. */
    const val SWITCH_MS = 2_500L

    /** One of the screen's modes: Display.Mode's id, size and refresh rate. */
    class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /**
     * Of [modes], the one at [width]×[height] (the resolution stays) whose refresh rate is a whole multiple of [fps],
     * within 0.2% (24 Hz for 23.976): the smallest multiple first, then the nearest. Null when none is.
     */
    fun pick(width: Int, height: Int, modes: List<Mode>, fps: Float): Mode? {
        if (fps <= 1f) return null
        return modes.filter { it.width == width && it.height == height }.mapNotNull { m ->
            val k = (m.hz / fps).roundToInt()
            if (k < 1) return@mapNotNull null
            val error = abs(m.hz - k * fps) / (k * fps)
            if (error < 0.002f) Triple(m, k, error) else null
        }.minWithOrNull(compareBy<Triple<Mode, Int, Float>>({ it.second }, { it.third }))?.first
    }
}
