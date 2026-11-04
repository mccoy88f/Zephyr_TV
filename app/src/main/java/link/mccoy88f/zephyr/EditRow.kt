package link.mccoy88f.zephyr

import android.view.KeyEvent
import android.view.View
import link.mccoy88f.zephyr.MainActivity.Companion.EDIT_KEYS
import link.mccoy88f.zephyr.SettingsRows.ROW_APPS_ROW

// Editing the apps row: moving a tile with the arrows, and choosing which apps the row shows.

internal fun MainActivity.appTileCount() = appRow.childCount

internal fun MainActivity.startEdit(grab: View?, once: Boolean = false) {
    editing = true
    moveOnce = once
    appsTitle.text = if (once) getString(R.string.moving_app_instructions) else getString(R.string.editing_row_instructions)
    appsTitle.visibility = View.VISIBLE
    (grab ?: appRow.getChildAt(0)).requestFocus()
    markGrabbed(grab)
}

internal fun MainActivity.markGrabbed(v: View?) {
    grabbed?.alpha = 1f
    grabbed = v
    v?.alpha = 0.6f
}

internal fun MainActivity.endEdit() {
    markGrabbed(null)
    editing = false
    moveOnce = false
    appsTitle.visibility = View.GONE
    val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
    saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
    currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
}

/** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
internal fun MainActivity.editKey(e: KeyEvent, confirm: Boolean): Boolean {
    if (e.keyCode == KeyEvent.KEYCODE_BACK) {
        if (e.action == KeyEvent.ACTION_UP) endEdit()
        return true
    }
    val v = currentFocus ?: return false
    val i = appRow.indexOfChild(v)
    if (confirm) {
        if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
            if (moveOnce) endEdit() else markGrabbed(if (grabbed === v) null else v)
        }
        return true
    }
    if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
    when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
            if (j !in 0 until appTileCount()) return true
            if (grabbed === v) {
                // Swap layout params too, so the first tile keeps no leading margin.
                val other = appRow.getChildAt(j)
                val lp = v.layoutParams
                v.layoutParams = other.layoutParams
                other.layoutParams = lp
                appRow.removeViewAt(i)
                appRow.addView(v, j)
                showNames()
                v.requestFocus()
                return true
            }
            return false // not holding anything: normal focus movement within the row
        }
    }
    return false
}

/** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
internal fun MainActivity.rowHiddenSet() = prefs.getStringSet(K.ROW_HIDDEN, emptySet())!!

internal fun MainActivity.pickRowApps() {
    val list = sortedApps().filter { !it.offline || it.pkg in pinnedInputs() }
    toggles(getString(R.string.setting_apps_in_row), list.map { it.label to it.pkg }, K.ROW_HIDDEN, getString(R.string.state_shown), getString(R.string.state_hidden)) {
        renderApps()
        renderSettings(ROW_APPS_ROW)
    }
}

/**
 * A page of on/off rows saved as the set of keys that are off; [done] runs on Back. With two rows or more, the
 * first turns them all on (Select all), or all off once they all are (Deselect all).
 */
internal fun MainActivity.toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0, done: () -> Unit) {
    val offSet = prefs.getStringSet(pref, emptySet())!!
    val keys = items.map { it.second }
    val all = if (items.size < 2) emptyList() else listOf(
        if (keys.any { it in offSet }) page(getString(R.string.toggles_all_on)) {
            prefs.edit().putStringSet(pref, offSet - keys.toSet()).apply()
            toggles(title, items, pref, on, off, 0, done)
        } else page(getString(R.string.toggles_all_off)) {
            prefs.edit().putStringSet(pref, offSet + keys).apply()
            toggles(title, items, pref, on, off, 0, done)
        }
    )
    showPage(title, all + items.mapIndexed { i, (name, key) ->
        page(name, if (key in offSet) off else on) {
            prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
            toggles(title, items, pref, on, off, i + all.size, done)
        }
    }, focus, back = done)
}

internal fun MainActivity.saveOrder(pkgs: List<String>) {
    prefs.edit().putString(K.ORDER, pkgs.joinToString(",")).putString(K.SORT, "manual").apply()
}
