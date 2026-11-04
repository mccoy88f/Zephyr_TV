package link.mccoy88f.zephyr

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import link.mccoy88f.zephyr.MainActivity.App
import link.mccoy88f.zephyr.MainActivity.Companion.APPS
import link.mccoy88f.zephyr.MainActivity.Companion.DEFAULT_ORDER
import link.mccoy88f.zephyr.MainActivity.Companion.GRID_GAP
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.ROW_ALIGN
import link.mccoy88f.zephyr.MainActivity.Companion.ROW_TILES
import link.mccoy88f.zephyr.MainActivity.Companion.TV_SETTINGS
import link.mccoy88f.zephyr.MainActivity.Prog

// The apps row: the apps found, in the user's order or by use, laid out with their names; and opening one.

/** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
internal fun MainActivity.refreshApps() {
    appsDirty = false
    io.execute {
        val list = try {
            loadApps()
        } catch (e: Exception) {
            return@execute
        }
        ui.post {
            apps = list
            APPS = list
            renderApps()
            spotlightApps = null
            renderSpotlight()
            updateInputsDot()
            if (focusFirstApp && settingsScrim.visibility != View.VISIBLE) appRow.getChildAt(0)?.requestFocus()
            focusFirstApp = false
        }
    }
}

internal fun MainActivity.loadApps(): List<App> {
    val pm = packageManager
    val installed = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        .map { it.activityInfo }
        .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
        .distinctBy { it.packageName }
        .map { a ->
            App(
                a.packageName,
                a.loadLabel(pm).toString(),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                a.loadIcon(pm),
            )
        }
    val hdmi = when {
        inputsMode() == "apps" -> hdmiInputs()
        inputsInApps() -> hdmiInputs().filter { !it.offline }
        else -> emptyList()
    }
    return installed + hdmi + addonApps()
}

internal fun MainActivity.sortedApps(): List<App> {
    // Streaming apps first by default, then alphabetical; manual order or usage overrides.
    val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .thenBy { it.label.lowercase() }
    if (prefs.getString(K.SORT, "manual") == "usage") {
        return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
    }
    val order = prefs.getString(K.ORDER, "")!!.split(',')
    // A console not yet placed by hand goes first, so it's visible without scrolling.
    return apps.sortedWith(compareBy<App> {
        order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
    }.then(byDefault))
}

/**
 * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
 * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
 */
internal fun MainActivity.rowAlign() = prefs.getString(K.ROW_ALIGN, "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

/**
 * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
 * centred, the scroller would push its first tiles out of reach.
 */
internal fun MainActivity.alignRow() {
    val content = appRow.parent as View
    (content.layoutParams as FrameLayout.LayoutParams).gravity =
        if (appTileCount() > ROW_TILES) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
    content.requestLayout()
}

internal fun MainActivity.renderApps() {
    val focusedTag = currentFocus?.tag
    // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
    // And the first time, wherever Android put it (Spotlight's title): the home screen starts on the Play queue.
    val takeFocus = currentFocus == null || currentFocus?.parent === appRow ||
        (appRow.childCount == 0 && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE)
    val gap = dp(GRID_GAP)
    val width = (resources.displayMetrics.widthPixels - dp(96) - gap * (ROW_TILES - 1)) / ROW_TILES
    appRow.removeAllViews()
    val hidden = rowHiddenSet()
    // Fixed first tile: "Continue Watching" hub
    val cwDrawable = getDrawable(R.drawable.ic_continue_watching) ?: getDrawable(R.drawable.ic_apps)!!
    // Tinted with the theme's foreground like the other marks: a white disc vanishes on the light theme's white focused card.
    val cwTile = tile(null, cwDrawable, mono = true, CW_PKG) {
        watchTitle.text = getString(R.string.continue_watching)
        tmdbLastFocusApp = null
        queryAppChannels(null)
    }.apply {
        contentDescription = getString(R.string.play_queue)
    }

    val searchTile = searchQuery?.let { q ->
        tile(null, getDrawable(R.drawable.ic_search)!!, mono = true, SEARCH_PKG) { openSearch(lastSearchWord) }.apply {
            contentDescription = getString(R.string.search_results_tile, q)
        }
    }
    val pinned = pinnedInputs()
    val appTiles = sortedApps().filter { inRow(it.pkg, it.offline, hidden, pinned) }.map { appTile(it, labeled = false) }
    val tiles = listOfNotNull(cwTile, searchTile) + appTiles
    tiles.forEachIndexed { i, t ->
        t.id = View.generateViewId()
        appRow.addView(t, LinearLayout.LayoutParams(width, dp(76)).apply { if (i > 0) marginStart = gap })
        // A finger: the first tap selects the app, its rows showing under it, as focus does on a TV; the next opens
        // it. An app known to have no rows opens with the first (see rowsKnown).
        t.isFocusableInTouchMode = !rowless(t.tag as? String)
        val own = t.onFocusChangeListener
        t.setOnFocusChangeListener { v, focused ->
            own?.onFocusChange(v, focused)
            if (focused && v.isInTouchMode && handheld()) v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (focused) {
                lastApp = v
                val pkg = v.tag as? String
                if (pkg == CW_PKG) {
                    watchTitle.text = getString(R.string.continue_watching)
                    tmdbLastFocusApp = null
                    showLoading(pkg)
                    ui.removeCallbacks(appChannelDebounce)
                    ui.postDelayed(appChannelDebounce, 200)
                } else if (pkg == SEARCH_PKG) {
                    tmdbLastFocusApp = pkg
                    ui.removeCallbacks(appChannelDebounce)
                    showSearchResults()
                } else if (pkg != null && !pkg.startsWith(HDMI)) {
                    tmdbLastFocusApp = pkg
                    showLoading(pkg)
                    ui.removeCallbacks(appChannelDebounce)
                    ui.postDelayed(appChannelDebounce, 300)
                } else {
                    tmdbLastFocusApp = pkg
                    ui.removeCallbacks(appChannelDebounce)
                    hideWatchRow()
                }
            }
            showRowLabel(v, focused)
        }
    }
    showNames()
    alignRow()
    if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
    if (!prefs.getBoolean(K.TIP_MOVE, false)) {
        prefs.edit().putBoolean(K.TIP_MOVE, true).apply()
        toast(R.string.tip_rearrange_row)
    }
}

/** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
internal fun MainActivity.rowNames() = prefs.getString(K.NAMES, "focus")!!

/** Each app's own shipped icon instead of the mono mark, in the row and All apps. */
internal fun MainActivity.colourIcons() = prefs.getBoolean(K.COLOUR_ICONS, false)

/** "App names: Always": a name under every tile, same width and gap as the tiles, so they line up. */
internal fun MainActivity.showNames() {
    val row = findViewById<LinearLayout>(R.id.nameRow)
    val always = rowNames() == "always"
    row.visibility = if (always) View.VISIBLE else View.GONE
    findViewById<View>(R.id.rowLabel).visibility = if (always) View.GONE else View.VISIBLE
    row.removeAllViews()
    if (!always) return
    for (i in 0 until appRow.childCount) {
        val t = appRow.getChildAt(i)
        row.addView(label(t.contentDescription?.toString().orEmpty(), 13f).apply {
            gravity = Gravity.CENTER
            alpha = if (dark) 0.85f else 1f
        }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
    }
}

/** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
internal fun MainActivity.showRowLabel(v: View, focused: Boolean) {
    val label = findViewById<TextView>(R.id.rowLabel)
    val inWatchRow = currentFocus?.getTag(R.id.watchRow) is Prog
    if (focused && !editing && rowNames() == "focus") {
        label.text = v.contentDescription
        // Posted: on first render focus lands before layout, when every width is still 0.
        v.post { label.translationX = v.left + v.width / 2f - label.width / 2f }
        label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        // Reset app tiles alpha
        for (i in 0 until appRow.childCount) appRow.getChildAt(i).alpha = 1f
    } else if (inWatchRow && !editing) {
        // Keep the selected app name visible, and dim the non-selected app tiles
        val activeTile = lastApp?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        if (activeTile != null && rowNames() == "focus") {
            label.text = activeTile.contentDescription
            activeTile.post { label.translationX = activeTile.left + activeTile.width / 2f - label.width / 2f }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        }
        for (i in 0 until appRow.childCount) {
            val child = appRow.getChildAt(i)
            child.animate().alpha(if (child === activeTile) 1f else 0.45f).setDuration(160).start()
        }
    } else {
        ui.post {
            if (currentFocus?.parent !== appRow && currentFocus?.getTag(R.id.watchRow) !is Prog || editing) {
                label.animate().alpha(0f).setDuration(120).start()
                for (i in 0 until appRow.childCount) appRow.getChildAt(i).animate().alpha(1f).setDuration(160).start()
            }
        }
    }
}

/** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */

internal fun MainActivity.launch(a: App) {
    prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
    if (prefs.getString(K.SORT, "manual") == "usage") appsDirty = true
    try {
        startActivity(a.launch)
    } catch (e: Exception) {
        packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
    }
}

// ---------- all apps ----------
