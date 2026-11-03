package link.mccoy88f.zephyr

import android.content.Context
import android.widget.Toast

/** What a PIN check found: the right PIN, a wrong one, or a wait of [Wait.seconds] before the next try. */
internal sealed class PinCheck {
    object Right : PinCheck()
    object Wrong : PinCheck()
    class Wait(val seconds: Int) : PinCheck()
}

/**
 * Wrong PINs in a row. After [tries] wrong ones, the next try waits. Each wait is twice the one before it
 * (30 s, 1 min, 2 min…, at most [maxWaitMs]). The right PIN starts again from the first wait.
 * [now] is a clock in milliseconds that only goes forward. The layout lock, the 18+ lock and remote access
 * each count with one of these. Safe to call from any thread.
 */
internal class PinGuard(
    private val tries: Int,
    private val firstWaitMs: Long = 30_000L,
    private val maxWaitMs: Long = 15 * 60_000L,
    private val now: () -> Long,
) {
    private var fails = 0
    private var waits = 0
    private var until = 0L

    /** [isRight] runs only when no wait is on. */
    @Synchronized
    fun check(isRight: () -> Boolean): PinCheck {
        val t = now()
        if (t < until) return PinCheck.Wait(((until - t + 999) / 1000).toInt())
        if (isRight()) {
            fails = 0
            waits = 0
            return PinCheck.Right
        }
        if (++fails < tries) return PinCheck.Wrong
        fails = 0
        val wait = (firstWaitMs shl waits.coerceAtMost(16)).coerceAtMost(maxWaitMs)
        waits++
        until = t + wait
        return PinCheck.Wait(((wait + 999) / 1000).toInt())
    }
}

/** The line the PIN page shows for [check]. Null for the right PIN. */
internal fun Context.pinMessage(check: PinCheck): String? = when (check) {
    PinCheck.Right -> null
    PinCheck.Wrong -> getString(R.string.pin_wrong)
    is PinCheck.Wait -> getString(R.string.pin_too_many_tries_wait, check.seconds)
}

/** A short message at the bottom of the screen. */
internal fun Context.toast(text: Int, long: Boolean = true) =
    Toast.makeText(this, getString(text), if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
