package link.mccoy88f.zephyr

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import link.mccoy88f.zephyr.MainActivity.App
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.INPUTS_ON
import link.mccoy88f.zephyr.MainActivity.Prog

// Holding OK on a tile or a card: its menu (move, hide, info, uninstall...), and the HDMI inputs' menu.

internal fun MainActivity.fireHold(v: View) {
    if (v.isInTouchMode) v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    holdFired = true
    v.cancelLongPress()
    v.isPressed = false
    onHold(v)
}

/** What holding OK does depends on what's focused. */
internal fun MainActivity.onHold(v: View) {
    val tag = v.tag as? String
    if (tag == "search-mic") return run { closeLauncherSettings(); assistant() } // hold the microphone: the TV's voice assistant
    if (tag == "search-clear") return
    @Suppress("UNCHECKED_CAST")
    (v.getTag(R.id.settingsList) as? () -> Unit)?.let { return it() } // a page's row with a hold of its own (an episode's: watched)
    when {
        v.id == R.id.searchButton -> assistant() // the TV's own voice search, for what TMDB doesn't know
        tag == SEARCH_PKG -> endSearch()
        allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
            val edit = prefs.edit().putStringSet(K.ROW_HIDDEN, rowHiddenSet() - tag)
            if (tag.startsWith(HDMI)) edit.putStringSet(K.INPUTS_PINNED, pinnedInputs() + tag)
            edit.apply()
            saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
            closeAllApps()
            appRow.getChildAt(0)?.requestFocus() // show the app just pinned
        }
        v.parent === appRow -> appMenu(v)
        (v.getTag(R.id.watchRow) as? Prog)?.let { it.channelPage != null || (it.vod != null && watchApp == SEARCH_PKG) } == true -> {} // a search result: OK shows all it has
        v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
    }
}

/** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
internal fun MainActivity.appMenu(v: View) {
    val pkg = v.tag as String
    if (locked()) return holdMenu(v, listOf(
        getString(R.string.menu_open) to { v.performClick(); Unit },
        getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
    ))
    holdMenu(v, listOfNotNull(
        getString(R.string.menu_move) to { startEdit(grab = v, once = true) },
        getString(R.string.menu_remove_from_row) to {
            val i = appRow.indexOfChild(v)
            val offline = apps.any { it.pkg == pkg && it.offline }
            prefs.edit().putStringSet(K.ROW_HIDDEN, if (offline) rowHiddenSet() else rowHiddenSet() + pkg)
                .putStringSet(K.INPUTS_PINNED, pinnedInputs() - pkg).apply()
            renderApps()
            appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
            toast(R.string.removed_from_row_toast, long = false)
        },
        sectionCache?.takeIf { it.first == pkg && it.second.isNotEmpty() }?.let { (_, sections) ->
            getString(R.string.menu_sections) to { pickSections(pkg, sections) }
        },
        // Zephyr TV's own apps only (Films, Series, TV channels, an add-on's): their rows are named from the add-ons'
        // catalogs, an app's own are named by the app.
        sectionCache?.takeIf { it.first == pkg && it.second.isNotEmpty() && pkg.startsWith(STREMIO) }?.let { (_, sections) ->
            getString(R.string.menu_rename_sections) to { renameSections(pkg, sections) }
        },
        if (pkg.startsWith(HDMI) || pkg.startsWith(STREMIO)) null else getString(R.string.menu_app_info) to {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        },
    ))
}

/** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
internal fun MainActivity.watchMenu(v: View, p: Prog) {
    if (p.playing) return holdMenu(v, listOfNotNull(
        getString(R.string.menu_close_playing) to { closePlaying(p) },
        getString(R.string.menu_open) to { v.performClick(); Unit },
    ))
    holdMenu(v, listOfNotNull(
        p.channelId?.let { id -> getString(R.string.menu_details) to { showChannelDetails(id) } },
        p.channelId?.takeIf { !locked() }?.let { id ->
            getString(R.string.menu_move) to { startChannelCardMove(v, id) }
        },
        p.addon?.let { c -> getString(R.string.menu_details) to { showAddonChannelDetails(c); Unit } },
        if (locked()) null else getString(R.string.menu_remove_from_cw) to { hideWatch(p) },
        if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
        p.pkg?.takeIf { !it.startsWith(STREMIO) }?.let { pkg ->
            getString(R.string.menu_open_app) to {
                (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                    ?.let { startActivity(it) }
                Unit
            }
        },
    ))
}

/** Background for popup menus (inputs, app hold menu) matching the current theme, with smoked glass in Glass theme. */
internal fun MainActivity.menuBackground(): Drawable = when {
    glass -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9202028.toInt(), 0xE6121218.toInt())).apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }
    dark -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF18181D.toInt(), 0xFF121216.toInt())).apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), 0x1AFFFFFF)
    }
    else -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2F3F7.toInt())).apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), 0x1A000000)
    }
}

/** A small Zephyr TV-styled menu just above [anchor]; Back closes it. */
internal fun MainActivity.holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = menuBackground()
        elevation = dp(8).toFloat()
    }
    val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(0))
        elevation = dp(8).toFloat()
    }
    items.forEachIndexed { i, (name, action) ->
        list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
            if (i > 0) topMargin = dp(2)
        })
    }
    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val at = IntArray(2).also { anchor.getLocationInWindow(it) }
    menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], maxOf(dp(8), at[1] - list.measuredHeight - dp(8)))
    list.getChildAt(0).requestFocus()
}

/** Top-bar TV Inputs popup menu listing all HDMI and passthrough inputs. */
internal fun MainActivity.openInputsMenu(anchor: View) {
    val inputs = hdmiInputs()
    if (inputs.isEmpty()) {
        toast(R.string.toast_no_inputs, long = false)
        return
    }
    val on = connectedInputs()
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = menuBackground()
        elevation = dp(8).toFloat()
    }
    val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(0))
        elevation = dp(8).toFloat()
    }
    inputs.distinctBy { it.pkg }.forEachIndexed { i, src ->
        val active = !src.offline
        val row = settingsRow(src.label, "") {
            menu.dismiss()
            startActivity(src.launch)
        } as LinearLayout
        val iconView = ImageView(this).apply {
            setImageDrawable(src.icon)
            setColorFilter(fg)
        }
        row.addView(iconView, 0, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        if (active) row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INPUTS_ON) } },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(8) })
        val prevFocus = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, focused ->
            prevFocus?.onFocusChange(v, focused)
            iconView.setColorFilter(if (focused) onFg else fg)
        }
        list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
            if (i > 0) topMargin = dp(2)
        })
    }
    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val at = IntArray(2).also { anchor.getLocationInWindow(it) }
    // Display directly underneath the top bar icon
    menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0] - dp(100), at[1] + anchor.height + dp(8))
    list.getChildAt(0)?.requestFocus()
}
