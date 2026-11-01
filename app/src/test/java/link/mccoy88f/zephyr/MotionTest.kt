package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun springStartsAtRestAndSettles() {
        assertEquals(0f, spring(0f), 0.001f)
        assertEquals(1f, spring(1f), 0.02f)
        assertTrue(spring(0.5f) > 0.9f)
    }

    @Test fun crashesOutsideTheWindowAreForgotten() {
        val now = 1_000_000L
        val saved = "${now - SAFE_WINDOW_MS - 1},${now - 1_000},${now - 2_000},nope"
        assertEquals(listOf(now - 1_000, now - 2_000), crashTimes(saved, now - SAFE_WINDOW_MS))
    }

    @Test fun driftCoversTheScreenAndReturns() {
        val start = kenBurns(1920f, 1080f, 0L, DRIFT_SPAN_MS)
        assertEquals(0f, start.left, 0.5f)
        assertEquals(1920f, start.right, 0.5f)
        val mid = kenBurns(1920f, 1080f, DRIFT_SPAN_MS, DRIFT_SPAN_MS)
        assertTrue(mid.left < 0f && mid.right > 1920f)
        assertTrue(mid.top < 0f && mid.bottom > 1080f)
        val back = kenBurns(1920f, 1080f, DRIFT_SPAN_MS * 2, DRIFT_SPAN_MS)
        assertEquals(start.left, back.left, 0.5f)
        assertEquals(start.top, back.top, 0.5f)
    }
}
