package link.mccoy88f.zephyr

import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU

// Rows with a few options (a size, a quality, a clock's format), and rows with a line saying what they do. A row
// that changed its value at each press, with nothing saying it would, read as a label: its options are now a page
// of their own, each with ✓ on the one in use, and ◀ ▶ step through them on the row itself (‹ 2 GB ›).

/** A row's value that's one of its options: CHOICE + the option's name (see settingsRow). */
internal const val CHOICE = "\u0000pick:"

/** Each option row's step (◀ -1, ▶ +1), by its title: the page's Left and Right call it (see showPage). */
internal val choiceSteps = HashMap<String, (Int) -> Unit>()

/** A line under a row's title, by its title (see settingsRow): what a setting does, when its name alone doesn't say. */
internal val rowHints = HashMap<String, String>()

/** [row] with [hint] under its title. */
internal fun Triple<String, String, () -> Unit>.hinted(hint: String) = also { rowHints[it.first] = hint }

/**
 * A row for one of [options] (each a key and its name), [current] the one in use. OK opens them as a page, ✓ on
 * [current]; ◀ ▶ take the one before or after. [pick] keeps the one chosen, then [reopen] draws its page again.
 */
internal fun <T> MainActivity.choice(title: String, options: List<Pair<T, String>>, current: T, reopen: () -> Unit,
                                     pick: (T) -> Unit): Triple<String, String, () -> Unit> {
    val at = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    choiceSteps[title] = { step ->
        pick(options[(at + step).mod(options.size)].first)
        reopen()
    }
    return page(title, CHOICE + options.getOrNull(at)?.second.orEmpty()) {
        showPage(title, options.mapIndexed { i, (k, name) ->
            page(name, if (i == at) CHOSEN else "") {
                if (i != at) pick(k)
                reopen()
            }
        }, at, back = reopen)
    }
}

/** The option's name in a CHOICE value, or null for any other value. */
internal fun choiceName(value: String) = if (value.startsWith(CHOICE)) value.removePrefix(CHOICE) else null

// ---------- Update, in two presses ----------

/** An Update pressed once: what it found, by the item's key (the version there is, and whether it's newer). */
private val updateFound = HashMap<String, Pair<String, Boolean>>()

/**
 * An Update row in two presses: the first asks [remote] (off the main thread) which version there is, against
 * [current], and the row says it ("Update available: 1.2.0", or "Up to date: 1.1.0"); the second runs [update].
 * [redraw] draws the page again, on this row. A version that can't be read: said so, and nothing more.
 */
internal fun MainActivity.updateRow(key: String, current: String?, remote: () -> String?, redraw: () -> Unit,
                                    update: () -> Unit): Triple<String, String, () -> Unit> {
    updateFound[key]?.let { (version, newer) ->
        val title = getString(if (newer) R.string.update_available_version else R.string.update_current_version, version)
        return page(title, getString(R.string.update_press_again)) {
            updateFound.remove(key)
            update()
        }
    }
    return page(getString(R.string.addon_update), SUBMENU) {
        toast(R.string.addon_checking, long = false)
        netIo.execute {
            val v = runCatching(remote).getOrNull()
            ui.post {
                if (v == null) return@post toast(R.string.toast_addon_failed)
                // Newer, or only another one (a version that doesn't read as numbers): offered as an update.
                updateFound[key] = v to (current == null || v != current && (Extensions.newer(v, current) || !Extensions.newer(current, v)))
                redraw()
            }
        }
    }
}
