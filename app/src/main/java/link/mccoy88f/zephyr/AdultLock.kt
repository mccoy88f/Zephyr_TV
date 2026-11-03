package link.mccoy88f.zephyr

import android.widget.Toast

// The 18+ lock: optional, set in the users' settings. Once on, the adult add-ons (marked 18+ by the user, or saying so
// in their manifest) are left out of the apps row, their rows, the search and Spotlight's streams until the PIN
// unlocks them, for two hours or until locked again from the users' menu. Stremio's catalogs say nothing about a
// title's age (Cinemeta has no rating; Nuvio's ageRating is on a title's page only, and patchy), so it goes by add-on.
// Functions of MainActivity, kept here by topic; its state lives in the preferences, so it outlasts a restart.

private const val UNLOCK_MS = 2 * 3600_000L

/** Whether the 18+ lock is on (a PIN set for it). */
internal fun MainActivity.adultLockOn() = prefs.contains(K.ADULT_PIN)

/**
 * Whether 18+ content shows now: the lock off, or unlocked, not yet run out, and since the TV last started (an unlock
 * doesn't outlast turning the TV off: see AdultRelock).
 */
internal fun MainActivity.adultUnlocked() = adultShows(prefs)

/** [adultUnlocked] from the preferences alone, for the player (which isn't the home screen). */
internal fun adultShows(p: android.content.SharedPreferences) = !p.contains(K.ADULT_PIN) ||
    (System.currentTimeMillis() < p.getLong(K.ADULT_UNTIL, 0L) && kotlin.math.abs(bootTime() - p.getLong(K.ADULT_BOOT, 0L)) < BOOT_SLACK_MS)

/** When the TV last started, on the wall clock: the same while it runs, give or take a clock correction. */
private fun bootTime() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

/** A clock correction (network time) moves bootTime this much at most; a restart, far more. */
private const val BOOT_SLACK_MS = 60_000L

/**
 * Locks 18+ again when the TV goes to standby (its screen off) or shuts down: registered by the home screen while it
 * runs and by the Home button service, which runs all the time. A restart is told by adultUnlocked.
 */
internal class AdultRelock : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: android.content.Intent) {
        val p = c.getSharedPreferences(K.FILE, android.content.Context.MODE_PRIVATE)
        if (p.getLong(K.ADULT_UNTIL, 0L) != 0L) p.edit().putLong(K.ADULT_UNTIL, 0L).apply()
    }

    companion object {
        val FILTER = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SHUTDOWN)
        }
    }
}

/** Whether an unlock lasts until locked again rather than two hours. */
internal fun MainActivity.adultUntilLocked() = prefs.getBoolean(K.ADULT_FOREVER, false)

/** The add-ons that show now: the adult ones left out while the 18+ lock holds. For what's shown, never for saving. */
internal fun MainActivity.shownAddonUrls(): List<String> = (addonUrls().filter { addonOn(it) } + extensionAddonUrls()).let { on ->
    if (adultUnlocked()) on else on.filter { !isAdultAddon(it) }
}

/** The tile of Adult content: the adult add-ons' channels, films and series, when they have an app of their own. */
internal const val ADULT_PKG = STREMIO + "adult"

/** Where the adult add-ons show: one virtual app (Adult content), an app each, or with the others; kept by [key]. */
internal enum class AdultPlace(val key: String) { VIRTUAL("virtual"), APPS("apps"), MIXED("mixed") }

/** Where the adult add-ons show, as chosen; before there was a choice of three, an app of their own or not. */
internal fun MainActivity.adultPlace(): AdultPlace = prefs.getString(K.ADULT_PLACE, null)?.let { k -> AdultPlace.entries.firstOrNull { it.key == k } }
    ?: if (prefs.getBoolean(K.ADULT_APP, false)) AdultPlace.VIRTUAL else AdultPlace.MIXED

/** Whether the adult add-ons have one virtual app of their own (Adult content). */
internal fun MainActivity.adultApp() = adultPlace() == AdultPlace.VIRTUAL

/** Whether the add-on at [url] shows in Adult content, and so not in the other tiles. */
internal fun MainActivity.inAdultApp(url: String) = adultApp() && isAdultAddon(url)

/** Whether the add-on at [url] is an adult one with an app of its own (its channels, films and series in it). */
internal fun MainActivity.adultOwnApp(url: String) = adultPlace() == AdultPlace.APPS && isAdultAddon(url)

/**
 * Whether the add-on at [url] has an app of its own, its channels, films and series in it whatever the others do:
 * an adult one set so (adultOwnApp), or an extension with content of its own (extensionOwnApp).
 */
internal fun MainActivity.ownApp(url: String) = adultOwnApp(url) || extensionOwnApp(url)

/** Whether the add-on at [url] shows apart from the others: in Adult content, or in an app of its own. */
internal fun MainActivity.apart(url: String) = inAdultApp(url) || ownApp(url)

/** The add-on with an app of its own that [pkg] is, if it is one (see ownApp). */
internal fun MainActivity.ownAppUrl(pkg: String): String? =
    shownAddonUrls().firstOrNull { ownApp(it) && Stremio.saved(filesDir, it)?.addon?.let { a -> STREMIO + a.id } == pkg }

/** Adult content's tile, when it has an app and an adult add-on shows (unlocked, or no 18+ lock). */
internal fun MainActivity.adultTile(): List<MainActivity.App> =
    if (!adultApp() || shownAddonUrls().none { isAdultAddon(it) }) emptyList()
    else listOf(MainActivity.App(ADULT_PKG, getString(R.string.adult_app), android.content.Intent(), getDrawable(R.drawable.ic_adult)!!, picture = getDrawable(R.drawable.tile_adult)))

/** What the users' menu says of the lock: locked, or unlocked (until when). */
internal fun MainActivity.adultState(): String {
    if (!adultUnlocked()) return getString(R.string.adult_locked)
    val until = prefs.getLong(K.ADULT_UNTIL, 0L)
    return if (until == Long.MAX_VALUE) getString(R.string.adult_unlocked)
    else getString(R.string.adult_unlocked_until, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(until)))
}

/** The users' menu row: Unlock 18+ (PIN), or Lock 18+ again. */
internal fun MainActivity.adultMenuRow(back: () -> Unit) =
    page(getString(if (adultUnlocked()) R.string.adult_lock_now else R.string.adult_unlock), adultState()) {
        if (adultUnlocked()) {
            prefs.edit().putLong(K.ADULT_UNTIL, 0L).apply()
            closeLauncherSettings() // back to the home screen, focus on its first app (see adultChanged)
            adultChanged()
            Toast.makeText(this, getString(R.string.adult_locked_toast), Toast.LENGTH_SHORT).show()
        } else askAdultPin(back) {
            val until = if (adultUntilLocked()) Long.MAX_VALUE else System.currentTimeMillis() + UNLOCK_MS
            prefs.edit().putLong(K.ADULT_UNTIL, until).putLong(K.ADULT_BOOT, bootTime()).apply()
            adultChanged()
            Toast.makeText(this, adultState(), Toast.LENGTH_SHORT).show()
            closeLauncherSettings()
        }
    }

/** The users' settings rows: the lock on or off, and how long an unlock lasts. */
internal fun MainActivity.adultSettingsRows(back: () -> Unit): List<Triple<String, String, () -> Unit>> = listOfNotNull(
    page(getString(R.string.adult_lock), toggle(adultLockOn())) {
        if (adultLockOn()) askAdultPin(back) {
            prefs.edit().remove(K.ADULT_PIN).remove(K.ADULT_UNTIL).apply()
            adultChanged()
            back()
        } else chooseAdultPin(back)
    },
    if (!adultLockOn()) null else page(getString(R.string.adult_unlock_for),
        getString(if (adultUntilLocked()) R.string.adult_until_locked else R.string.adult_two_hours)) {
        prefs.edit().putBoolean(K.ADULT_FOREVER, !adultUntilLocked()).apply()
        back()
    },
)

/** A new PIN, typed twice; the lock on (locked) once they match. */
private fun MainActivity.chooseAdultPin(back: () -> Unit): Unit = pinPage(getString(R.string.adult_pin_choose), back) { first ->
    pinPage(getString(R.string.pin_enter_again_title), back) { again ->
        if (again != first) {
            Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_LONG).show()
            chooseAdultPin(back)
            return@pinPage null
        }
        prefs.edit().putString(K.ADULT_PIN, hashPin(first)).putLong(K.ADULT_UNTIL, 0L).apply()
        adultChanged()
        Toast.makeText(this, getString(R.string.adult_locked_toast), Toast.LENGTH_LONG).show()
        back()
        null
    }
    null
}

/** The 18+ PIN, then [then]; wrong ones a few times make it wait, as the layout lock does. */
private fun MainActivity.askAdultPin(back: () -> Unit, then: () -> Unit) {
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    pinPage(getString(R.string.adult_pin_enter), back) { pin ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < pinWaitUntil) return@pinPage getString(R.string.pin_too_many_tries_wait, (pinWaitUntil - now) / 1000 + 1)
        if (!pinMatches(prefs.getString(K.ADULT_PIN, null) ?: return@pinPage null.also { then() }, pin)) {
            if (++pinFails < MainActivity.PIN_TRIES) return@pinPage getString(R.string.pin_wrong)
            pinFails = 0
            pinWaitUntil = now + 30_000L
            return@pinPage getString(R.string.pin_too_many_tries_wait, 30)
        }
        pinFails = 0
        then()
        null
    }
}

/** Locked or unlocked: what shows the add-ons again (the apps row, its rows, the search). */
internal fun MainActivity.adultChanged() {
    adultShown = adultUnlocked()
    // Locked: the adult apps go from the row, focus to its first app (Continue Playing) once it's built again.
    if (!adultShown) focusFirstApp = true
    addonsChanged()
}

/** Back on the home screen: an unlock that ran out meanwhile hides the adult add-ons again. */
internal fun MainActivity.checkAdultLock() {
    if (adultShown != adultUnlocked()) adultChanged()
}
