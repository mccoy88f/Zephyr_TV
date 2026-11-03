package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGuardTest {
    private var clock = 0L
    private val guard = PinGuard(tries = 3) { clock }

    private fun wrong() = guard.check { false }
    private fun right() = guard.check { true }

    @Test fun theRightPinPasses() {
        assertSame(PinCheck.Right, right())
    }

    @Test fun wrongPinsWaitAfterTheLastTry() {
        assertSame(PinCheck.Wrong, wrong())
        assertSame(PinCheck.Wrong, wrong())
        assertEquals(30, (wrong() as PinCheck.Wait).seconds)
        clock += 10_000
        val again = guard.check { error("the PIN isn't checked during a wait") }
        assertEquals(20, (again as PinCheck.Wait).seconds)
    }

    @Test fun eachWaitIsTwiceTheOneBefore() {
        repeat(2) { wrong() }
        assertEquals(30, (wrong() as PinCheck.Wait).seconds)
        clock += 30_000
        repeat(2) { wrong() }
        assertEquals(60, (wrong() as PinCheck.Wait).seconds)
        clock += 60_000
        repeat(2) { wrong() }
        assertEquals(120, (wrong() as PinCheck.Wait).seconds)
    }

    @Test fun theWaitStopsAtItsLongest() {
        repeat(20) {
            repeat(2) { wrong() }
            val w = wrong() as PinCheck.Wait
            assertTrue(w.seconds <= 15 * 60)
            clock += w.seconds * 1000L
        }
    }

    @Test fun theRightPinStartsAgain() {
        repeat(2) { wrong() }
        assertEquals(30, (wrong() as PinCheck.Wait).seconds)
        clock += 30_000
        assertSame(PinCheck.Right, right())
        repeat(2) { wrong() }
        assertEquals(30, (wrong() as PinCheck.Wait).seconds)
    }
}
