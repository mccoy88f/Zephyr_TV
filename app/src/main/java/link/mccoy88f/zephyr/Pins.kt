package link.mccoy88f.zephyr

import java.security.MessageDigest
import java.security.SecureRandom
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

// ---------- the one PIN ----------
// Zephyr TV has one PIN for everything: the layout lock, the 18+ lock and remote access ask for the same PIN.
// Each lock is a switch of its own (K.LOCK_ON, K.ADULT_ON). The PIN stays while something uses it, and goes when
// nothing does, so the next one is chosen anew. It never goes in a backup (see Backup).

/** The PIN, hashed (see hashPin), or null when none is set. */
internal fun pinStored(p: android.content.SharedPreferences): String? = p.getString(K.PIN, null)

internal fun MainActivity.pinSet() = prefs.contains(K.PIN)

/**
 * The older PINs, as one: the 18+ PIN first (remote access already used it), then the layout lock's, then the
 * older remote PIN. Each lock stays on as it was. [merged]: both locks had a PIN, so the layout lock now uses the
 * 18+ one, and the TV says so.
 */
internal class PinMigration(val pin: String?, val lockOn: Boolean, val adultOn: Boolean, val merged: Boolean)

internal fun migratedPins(adult: String?, lock: String?, remote: String?) =
    PinMigration(adult ?: lock ?: remote, lock != null, adult != null, adult != null && lock != null)

/** Moves the older PINs to the one PIN, once. True when the layout lock's PIN changed to the 18+ PIN. */
internal fun migratePins(p: android.content.SharedPreferences): Boolean {
    if (!p.contains(K.ADULT_PIN) && !p.contains(K.LOCK_PIN) && !p.contains(K.REMOTE_PIN)) return false
    val m = migratedPins(p.getString(K.ADULT_PIN, null), p.getString(K.LOCK_PIN, null), p.getString(K.REMOTE_PIN, null))
    val e = p.edit().remove(K.ADULT_PIN).remove(K.LOCK_PIN).remove(K.REMOTE_PIN)
    if (m.pin != null && !p.contains(K.PIN)) e.putString(K.PIN, m.pin)
    if (m.lockOn) e.putBoolean(K.LOCK_ON, true)
    if (m.adultOn) e.putBoolean(K.ADULT_ON, true)
    e.commit() // before anything reads the locks
    return m.merged
}

/** Nothing uses the PIN any more (both locks off, remote access off): it goes. */
internal fun MainActivity.dropUnusedPin() {
    if (!prefs.getBoolean(K.LOCK_ON, false) && !prefs.getBoolean(K.ADULT_ON, false) && !remoteOn() && pinSet())
        prefs.edit().remove(K.PIN).apply()
}

/** The PIN asked on the TV (or from the phone, see PhonePanel), then [then]. With no PIN set, [then] at once. */
internal fun MainActivity.askPin(back: (() -> Unit)?, then: () -> Unit) {
    pinPage(getString(R.string.pin_enter_title), back) { pin ->
        val stored = pinStored(prefs) ?: return@pinPage null.also { then() }
        pinMessage(pinGuard.check { pinMatches(stored, pin) })?.let { return@pinPage it }
        then()
        null
    }
}

/**
 * Something that needs the PIN turns on: the PIN is asked when one is set, so nobody turns a lock on with a PIN
 * they don't know. With none, a new one is chosen (twice), and the TV says that it is the PIN for everything.
 */
internal fun MainActivity.withPin(back: () -> Unit, then: () -> Unit) {
    if (pinSet()) return askPin(back, then)
    choosePin(getString(R.string.pin_choose_title), back) { hash ->
        prefs.edit().putString(K.PIN, hash).apply()
        toast(R.string.pin_one_toast)
        then()
    }
}

/** A new PIN in place of the old one: the old one first, then the new one twice. [back] leaves, and comes after. */
internal fun MainActivity.changePin(back: () -> Unit) {
    askPin(back) {
        choosePin(getString(R.string.pin_choose_title), back) { hash ->
            prefs.edit().putString(K.PIN, hash).apply()
            toast(R.string.pin_changed_toast)
            back()
        }
    }
}

/** The PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored
