package link.mccoy88f.zephyr

import android.view.ViewConfiguration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import link.mccoy88f.zephyr.MainActivity.Prog

// Moving with the arrows: the home screen's rows, the panel and its pages, the details' two columns, and the phone's remote, with one set of rules for all of them.

/** Focus from the phone's remote. A finger's screen ignores a focus that is not allowed in touch mode. */
internal fun View.steer(): Boolean {
    if (isInTouchMode) {
        isFocusableInTouchMode = true
        // A scroll view takes the finger's focus and the row behind it never lights.
        var p = parent
        while (p is View) {
            if (p is android.widget.ScrollView || p is android.widget.HorizontalScrollView) {
                p.isFocusable = false
                p.isFocusableInTouchMode = false
            }
            p = p.parent
        }
    }
    if (requestFocus()) return true
    if (requestFocusFromTouch()) return true
    return false
}

/** Brings [v] into the panel's list. A row past the bottom would take focus and stay unseen. */
internal fun MainActivity.reveal(v: View) {
    v.requestRectangleOnScreen(android.graphics.Rect(0, 0, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1)), false)
}

/** The first focusable view in [v], itself when it can take focus. */
internal fun MainActivity.focusableIn(v: View): View? {
    if (v.visibility != View.VISIBLE) return null
    if (v.isFocusable) return v
    if (v is ViewGroup) {
        for (i in 0 until v.childCount) focusableIn(v.getChildAt(i))?.let { return it }
    }
    return null
}

/** One focusable target for each row of the panel, in the order they are shown. */
internal fun MainActivity.panelRows(): List<View> =
    (0 until settingsList.childCount).mapNotNull { focusableIn(settingsList.getChildAt(it)) }

/** Where [v] sits in [panelRows], or null when the focus is not in that list. */
internal fun MainActivity.panelIndex(v: View?): Int? {
    if (v == null) return null
    val rows = panelRows()
    val i = rows.indexOfFirst { it === v || isDescendantOf(v, it) }
    return i.takeIf { it >= 0 }
}

/** The first focusable item, when a remote key arrives and nothing has focus. True when one took it. */
internal fun MainActivity.seedRemoteFocus(): Boolean {
    val start = when {
        settingsScrim.visibility == View.VISIBLE ->
            (0 until settingsList.childCount).firstNotNullOfOrNull { settingsList.getChildAt(it).takeIf { v -> v.isFocusable && v.visibility == View.VISIBLE } }
        allApps.visibility == View.VISIBLE -> allAppsGrid.getChildAt(0)
        else -> appRow.getChildAt(0) ?: findViewById(R.id.usersButton)
    } ?: return false
    return start.steer()
}

/** An arrow from the phone. With nothing focused, start on the first item and take the step in the same press. */
internal fun MainActivity.remoteArrow(key: Int) {
    if (settingsScrim.visibility == View.VISIBLE) {
        settingsKey(key)
        return
    }
    if (currentFocus == null) seedRemoteFocus()
    val v = currentFocus ?: return
    if (allApps.visibility == View.VISIBLE) allAppsStep(key) else homeNav(v, key)?.steer()
}

/** OK from the phone: activate what is focused. With nothing focused, only land on the first item. */
internal fun MainActivity.remoteOk() {
    val v = currentFocus
    if (v == null) {
        seedRemoteFocus()
        return
    }
    v.performClick()
}

/** Left column and right column of a details page, when that page is open. */
internal fun MainActivity.detailColumns(): Pair<Boolean, Boolean> {
    val leftScroll = detailLeftScroll ?: return false to false
    val inLeft = currentFocus === detailDescView || isDescendantOf(currentFocus, leftScroll)
    val inRight = isDescendantOf(currentFocus, findViewById(R.id.settingsPanel))
    return inLeft to inRight
}

/**
 * One arrow inside the panel. True when the panel took it. Left and right always stay in the panel.
 * The same function serves the TV remote and the phone.
 */
internal fun MainActivity.settingsKey(key: Int): Boolean {
    val inside = currentFocus?.let { isDescendantOf(it, settingsScrim) } == true
    if (!inside) {
        val first = panelRows().firstOrNull() ?: return false
        if (!first.steer()) return false
        reveal(first)
        return true
    }
    val focused = currentFocus
    if (focused is EditText && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
        val pos = focused.selectionStart.coerceAtLeast(0)
        val right = key == KeyEvent.KEYCODE_DPAD_RIGHT
        // At the start (Left) or the end (Right) of the words: on to the box's neighbour there, when it has one in the
        // panel and it's shown (the search's microphone and cross); else the cursor stays.
        if (if (right) pos >= focused.length() else pos == 0) {
            val id = if (right) focused.nextFocusRightId else focused.nextFocusLeftId
            val next = id.takeIf { it != View.NO_ID }?.let { findViewById<View>(it) }
            if (next != null && next.isShown && isDescendantOf(next, settingsScrim) && next.steer()) return true
            return true
        }
        focused.setSelection((pos + if (right) 1 else -1).coerceIn(0, focused.length()))
        return true
    }
    if (focused?.tag == "pin-digits" && offerKey(focused, key)) return true
    val leftScroll = detailLeftScroll
    val (inLeftCol, inRightCol) = detailColumns()
    val firstRightRow = if (inLeftCol || inRightCol) (0 until settingsList.childCount).map { settingsList.getChildAt(it) }.firstOrNull { it.isFocusable } else null
    val leftTitleView = leftScroll?.findViewWithTag<View>("leftTitle")?.takeIf { it.visibility == View.VISIBLE && it.isFocusable }
    val leftTop = if (inLeftCol || inRightCol) (leftTitleView ?: detailDescView) else null
    if (inLeftCol) {
        when (key) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val nextCard = currentFocus?.takeIf { it in detailLastLeftFocusables && it.inHorizontalScroll() }
                    ?.horizontalNeighbor(true)?.takeIf { it !== currentFocus && isDescendantOf(it, leftScroll!!) }
                if (nextCard != null && nextCard.steer()) return true
                if (firstRightRow != null && firstRightRow.steer()) return true
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val inCards = currentFocus in detailLastLeftFocusables
                if (inCards || (detailLastLeftFocusables.isEmpty() && currentFocus === detailDescView)) {
                    if (firstRightRow != null && firstRightRow.steer()) return true
                    return true
                }
                if (currentFocus === leftTitleView) {
                    if (detailDescView != null && detailDescView!!.steer()) return true
                } else if (currentFocus === detailDescView && detailLastLeftFocusables.isNotEmpty()) {
                    if (detailLastLeftFocusables[0].steer()) return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentFocus in detailLastLeftFocusables) {
                    if (detailDescView != null && detailDescView!!.steer()) return true
                } else if (currentFocus === detailDescView) {
                    leftScroll?.smoothScrollTo(0, 0)
                    if (leftTitleView != null && leftTitleView.steer()) return true
                    return true
                } else if (currentFocus === leftTitleView) {
                    leftScroll?.smoothScrollTo(0, 0)
                    return true
                }
            }
        }
    } else if (inRightCol && key == KeyEvent.KEYCODE_DPAD_LEFT) {
        leftScroll?.smoothScrollTo(0, 0)
        if (leftTop != null && leftTop.steer()) return true
        return true
    }
    if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
        val down = key == KeyEvent.KEYCODE_DPAD_DOWN
        val id = if (down) currentFocus?.nextFocusDownId else currentFocus?.nextFocusUpId
        if (id != null && id != View.NO_ID && id != currentFocus?.id) {
            val target = findViewById<View>(id)
            if (target != null && isDescendantOf(target, settingsScrim) && target.steer()) {
                reveal(target)
                return true
            }
        }
        if (panelStep(down)) return true
        if (!down && currentFocus != null && currentFocus === detailDescView) {
            var p = currentFocus?.parent
            while (p != null && p !is android.widget.ScrollView) p = p.parent
            (p as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
            return true
        }
        return false
    }
    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
        settingsSide(key)
        return true
    }
    return false
}

/** Up or down one row of the panel. True when the list took the key, including a stay at either end. */
internal fun MainActivity.panelStep(down: Boolean): Boolean {
    val rows = panelRows()
    val at = panelIndex(currentFocus) ?: return false
    val next = rows[remoteRowIndex(at, rows.size, down)]
    if (next === currentFocus) return true
    if (!next.steer()) return false
    reveal(next)
    return true
}

/**
 * The view's own key, for a PIN box or a text field. True when that view took it.
 * A plain row takes none, and the panel then moves.
 */
internal fun MainActivity.offerKey(v: View, key: Int): Boolean {
    val t = SystemClock.uptimeMillis()
    val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
    if (!v.dispatchKeyEvent(down)) return false
    val up = KeyEvent(t, t + 30, KeyEvent.ACTION_UP, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
    v.dispatchKeyEvent(up)
    return true
}

/** Left or right in a panel: the link the page set, else the next item in the horizontal row, else the page's own step. */
internal fun MainActivity.settingsSide(key: Int) {
    val v = currentFocus ?: return
    val forward = key == KeyEvent.KEYCODE_DPAD_RIGHT
    val id = if (forward) v.nextFocusRightId else v.nextFocusLeftId
    if (id != View.NO_ID && id != v.id) {
        val target = findViewById<View>(id)
        if (target != null && target !== v && target.steer()) return
    }
    val beside = v.horizontalNeighbor(forward)
    if (beside != null && beside !== v && beside.steer()) return
    if (!v.inHorizontalScroll()) pageSides?.invoke(if (forward) 1 else -1)
}

internal fun MainActivity.allAppsStep(key: Int) {
    val items = (0 until allAppsGrid.childCount).map { allAppsGrid.getChildAt(it) }.filter { it.isFocusable && it.visibility == View.VISIBLE }
    val at = items.indexOf(currentFocus).takeIf { it >= 0 }
    items.getOrNull(remoteGridIndex(at, items.size, 6, key))?.steer()
}

/** The next focusable child, or this view when it is not in the row or the row is at its end. */
internal fun ViewGroup.rowStep(from: View, forward: Boolean): View {
    val items = (0 until childCount).map { getChildAt(it) }.filter { it.isFocusable && it.visibility == View.VISIBLE }
    val i = items.indexOf(from)
    if (i < 0) return from
    return items[remoteRowIndex(i, items.size, forward)]
}

internal fun View.inHorizontalScroll(): Boolean {
    var p = parent
    while (p != null) {
        if (p is android.widget.HorizontalScrollView) return true
        p = p.parent
    }
    return false
}

/** The next focusable item in the same horizontal scroller, already chosen by the page when it set a link. */
internal fun View.horizontalNeighbor(forward: Boolean): View? {
    var p = parent
    while (p != null && p !is android.widget.HorizontalScrollView) p = p.parent
    val scroller = p as? android.widget.HorizontalScrollView ?: return null
    val row = scroller.getChildAt(0) as? ViewGroup ?: return null
    val items = (0 until row.childCount).map { row.getChildAt(it) }
    val start = this
    val i = items.indexOfFirst { item ->
        var node: View? = start
        while (node != null && node !== row) {
            if (node === item) return@indexOfFirst true
            node = node.parent as? View
        }
        false
    }
    if (i < 0) return null
    val dest = items[remoteRowIndex(i, items.size, forward)]
    if (dest.isFocusable) return dest
    return if (dest is ViewGroup) (0 until dest.childCount).map { dest.getChildAt(it) }.firstOrNull { it.isFocusable } else null
}

/**
 * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
 * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
 * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
 * let Android handle the key.
 */
internal fun MainActivity.homeNav(v: View, key: Int): View? {
    fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
    val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
    val cards = if (watchScroll.visibility != View.VISIBLE) null // Down from the apps: the first section
        else alive(lastCard)?.takeIf { it.parent?.parent === watchRow } ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
    val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
    val spotVis = spotlightContainer.visibility == View.VISIBLE
    return when {
        v.parent === wifi.parent -> when (key) { // top bar
            KeyEvent.KEYCODE_DPAD_DOWN -> if (spotVis) spotlightInfoBox else apps ?: cards ?: v
            KeyEvent.KEYCODE_DPAD_UP -> v
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> (v.parent as ViewGroup).rowStep(v, key == KeyEvent.KEYCODE_DPAD_RIGHT)
            else -> null
        }
        v === spotlightInfoBox -> when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> spotlightAction(-1, 1) ?: apps ?: cards ?: v
            KeyEvent.KEYCODE_DPAD_UP -> top
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cycleSpotlight(1)
                v
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                cycleSpotlight(-1)
                v
            }
            // OK opens a cut-off overview in full (OK again closes it); otherwise it goes on to the buttons.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (toggleOverview()) v else spotlightAction(-1, 1) ?: v
            else -> null
        }
        v.parent === spotlightActions -> {
            val idx = spotlightActions.indexOfChild(v)
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_UP -> spotlightInfoBox
                KeyEvent.KEYCODE_DPAD_LEFT -> spotlightAction(idx, -1) ?: v
                KeyEvent.KEYCODE_DPAD_RIGHT -> spotlightAction(idx, 1) ?: v
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    v.performClick()
                    v
                }
                else -> null
            }
        }
        v.parent === appRow -> when (key) {
            // Up climbs Spotlight from the bottom: its buttons (shown only while it has focus), the title, the top bar.
            KeyEvent.KEYCODE_DPAD_UP -> if (spotVis) spotlightAction(-1, 1)?.also { spotlightActions.visibility = View.VISIBLE } ?: spotlightInfoBox else top
            KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> appRow.rowStep(v, key == KeyEvent.KEYCODE_DPAD_RIGHT)
            else -> null
        }
        v.getTag(R.id.watchRow) is Prog -> { // a card: Up and Down go between sections, to the nearest card
            val row = v.parent.parent as ViewGroup
            val rows = cardRows()
            val r = rows.indexOf(row)
            val i = row.indexOfChild(v.parent as View)
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> if (r <= 0) apps ?: top else nearestCard(rows[r - 1], v) ?: v
                KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(r + 1)?.let { nearestCard(it, v) } ?: v
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val ni = remoteRowIndex(i, row.childCount, key == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ((row.getChildAt(ni) as? ViewGroup)?.getChildAt(0))?.takeIf { it.isFocusable } ?: v
                }
                else -> null
            }
        }
        else -> null
    }
}

/** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
internal fun MainActivity.homeFocusable(on: Boolean) {
    findViewById<ViewGroup>(R.id.content).descendantFocusability =
        if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
}

/** A key on the home screen: true when Zephyr TV took it; false lets Android handle it (see dispatchKeyEvent). */
internal fun MainActivity.handleKey(e: KeyEvent): Boolean {
    noteInput()
    val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
        e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    if (confirm) {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            holdFired = false
            holdView = currentFocus
            ui.removeCallbacks(holdTick)
            ui.postDelayed(holdTick, ViewConfiguration.getLongPressTimeout().toLong())
        } else if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
            return true
        } else if (e.action == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(holdTick)
            if (holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
    }
    // Channel reorder (settings list ▲▼, or home strip ◀▶) before panel/home navigation.
    if (channelOrderGrabId != null && channelOrderListKey(e, confirm)) return true
    if (movingChannelId != null && channelCardMoveKey(e, confirm)) return true
    // The settings panel is a vertical list: don't let Left/Right leak focus to the home screen behind it.
    if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox) {
        val arrow = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val (inLeftCol, inRightCol) = detailColumns()
        if (arrow && e.action == KeyEvent.ACTION_DOWN && settingsKey(e.keyCode)) return true
        if (arrow && e.action == KeyEvent.ACTION_UP && (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || inLeftCol || (inRightCol && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT))) return true
    }
    if (editing && editKey(e, confirm)) return true
    if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
        currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.steer(); return true }
    }
    return false
}
