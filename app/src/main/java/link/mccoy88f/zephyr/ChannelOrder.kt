package link.mccoy88f.zephyr

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.Toast
import link.mccoy88f.zephyr.SettingsRows.CHANNEL_ORDER_ROW

/** Saved channel `_id`s in display order. Empty = TV / LCN order. */
internal fun MainActivity.channelOrderIds(): List<Long> {
    val raw = prefs.getString(K.CHANNEL_ORDER, null) ?: return emptyList()
    return runCatching {
        val a = org.json.JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            when (val v = a.get(i)) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

internal fun MainActivity.saveChannelOrder(ids: List<Long>) {
    prefs.edit().putString(K.CHANNEL_ORDER, org.json.JSONArray(ids).toString()).apply()
    watchKey = null
}

internal fun MainActivity.clearChannelOrder() {
    prefs.edit().remove(K.CHANNEL_ORDER).apply()
    watchKey = null
}

/** Seed a full custom order from the current LCN list (needed before the first move). */
internal fun MainActivity.ensureChannelOrderSeeded() {
    if (channelOrderIds().isNotEmpty()) return
    saveChannelOrder(tunerChannels().sortedBy { channelKey(it.number) }.map { it.id })
}

/**
 * Channel number on cards / search. Default on in LCN order; off once a custom order exists
 * (unless the user turns the setting on).
 */
internal fun MainActivity.channelNumbersOn(): Boolean =
    prefs.getBoolean(K.CHANNEL_NUMBERS, channelOrderIds().isEmpty())

internal fun MainActivity.channelNumberBadge(number: String): String? {
    if (!channelNumbersOn()) return null
    return number.trimStart('0').ifEmpty { number }
}

/** Tuner channels in the order Zephyr TV should show: the user's, else by number (LCN). */
internal fun MainActivity.orderedTunerChannels(all: List<TunerChannel> = tunerChannels()): List<TunerChannel> {
    val order = channelOrderIds()
    if (order.isEmpty()) return all.sortedBy { channelKey(it.number) }
    val byId = all.associateBy { it.id }
    val seen = LinkedHashSet<Long>()
    val out = ArrayList<TunerChannel>(all.size)
    for (id in order) {
        val ch = byId[id] ?: continue
        if (seen.add(id)) out += ch
    }
    for (ch in all.sortedBy { channelKey(it.number) }) {
        if (seen.add(ch.id)) out += ch
    }
    return out
}

/** Swap [id] by [by] steps in the saved order. Returns the id, or null if it couldn't move. */
internal fun MainActivity.shiftChannelOrder(id: Long, by: Int): Long? {
    ensureChannelOrderSeeded()
    val ids = orderedTunerChannels().map { it.id }.toMutableList()
    val i = ids.indexOf(id)
    val j = i + by
    if (i < 0 || j !in ids.indices) return null
    val tmp = ids[i]
    ids[i] = ids[j]
    ids[j] = tmp
    saveChannelOrder(ids)
    return id
}

// —— Settings list (vertical ▲ ▼) ——

/** Non-null while a channel is picked up in the settings reorder list. */
internal var channelOrderGrabId: Long? = null

internal fun MainActivity.showChannelOrderPage(focusId: Long? = null) {
    val all = tunerChannels()
    if (all.isEmpty()) {
        channelOrderGrabId = null
        showPage(getString(R.string.setting_channel_order), listOf(
            page(getString(R.string.channel_order_empty)) {},
        ), back = { renderSettings(CHANNEL_ORDER_ROW) })
        return
    }
    val ordered = orderedTunerChannels(all)
    val grab = channelOrderGrabId
    val title = if (grab != null) getString(R.string.moving_channel_list_instructions)
    else getString(R.string.setting_channel_order)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    if (grab == null && channelOrderIds().isNotEmpty()) {
        rows += page(getString(R.string.channel_order_reset)) {
            clearChannelOrder()
            showChannelOrderPage()
        }
    }
    ordered.forEachIndexed { i, ch ->
        val num = channelNumberBadge(ch.number)
        val label = if (num != null) "$num  ${ch.name}" else ch.name
        val mark = when {
            grab == ch.id -> "▲▼"
            grab != null -> ""
            else -> "${i + 1}"
        }
        rows += page(label, mark) {
            when {
                grab == null -> {
                    ensureChannelOrderSeeded()
                    channelOrderGrabId = ch.id
                    showChannelOrderPage(ch.id)
                }
                grab == ch.id -> {
                    channelOrderGrabId = null
                    showChannelOrderPage(ch.id)
                }
                else -> {
                    channelOrderGrabId = ch.id
                    showChannelOrderPage(ch.id)
                }
            }
        }
    }
    val focus = when {
        focusId != null -> ordered.indexOfFirst { it.id == focusId }.takeIf { it >= 0 }
            ?.let { it + if (grab == null && channelOrderIds().isNotEmpty()) 1 else 0 }
        else -> null
    } ?: 0
    showPage(title, rows, focus.coerceAtLeast(0), back = {
        if (channelOrderGrabId != null) {
            val id = channelOrderGrabId
            channelOrderGrabId = null
            showChannelOrderPage(id)
        } else renderSettings(CHANNEL_ORDER_ROW)
    })
    // Dim the grabbed row so it reads as picked up in the list.
    if (grab != null) {
        val first = channelOrderListFirst() ?: return
        val at = ordered.indexOfFirst { it.id == grab }
        if (at >= 0) settingsList.getChildAt(first + at)?.alpha = 0.55f
    }
}

/** Index of the first channel row in [settingsList] (skips a header if any). */
private fun MainActivity.channelOrderListFirst(): Int? =
    (0 until settingsList.childCount).firstOrNull { settingsList.getChildAt(it).hasOnClickListeners() }

/**
 * Swap the grabbed channel one step in the settings list without rebuilding the page (key-repeat stays snappy).
 */
private fun MainActivity.moveGrabbedChannelRow(by: Int): Boolean {
    val grab = channelOrderGrabId ?: return false
    if (shiftChannelOrder(grab, by) == null) return false
    val first = channelOrderListFirst() ?: return true
    val ids = orderedTunerChannels().map { it.id }
    // After the swap, [grab] sits at its new index.
    val j = ids.indexOf(grab)
    val i = j - by
    if (i < 0 || j < 0) return true
    val row = settingsList.getChildAt(first + i) ?: return true
    settingsList.removeViewAt(first + i)
    settingsList.addView(row, first + j)
    row.alpha = 0.55f
    // Clear dim on the neighbour that moved the other way.
    settingsList.getChildAt(first + i)?.alpha = 1f
    row.requestFocus()
    row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width.coerceAtLeast(1), row.height.coerceAtLeast(1)), false)
    return true
}

/**
 * While a channel is grabbed in the settings list: ▲ ▼ move it in place, OK / Back drop it.
 * True when the key was handled.
 */
internal fun MainActivity.channelOrderListKey(e: KeyEvent, confirm: Boolean): Boolean {
    val grab = channelOrderGrabId ?: return false
    if (settingsScrim.visibility != View.VISIBLE) return false
    if (e.keyCode == KeyEvent.KEYCODE_BACK) {
        if (e.action == KeyEvent.ACTION_UP) {
            channelOrderGrabId = null
            showChannelOrderPage(grab)
        }
        return true
    }
    if (confirm) {
        if (e.action == KeyEvent.ACTION_UP) {
            channelOrderGrabId = null
            showChannelOrderPage(grab)
        }
        return true
    }
    if (e.action != KeyEvent.ACTION_DOWN) {
        return e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }
    when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        KeyEvent.KEYCODE_DPAD_UP -> return moveGrabbedChannelRow(-1).let { true }
        KeyEvent.KEYCODE_DPAD_DOWN -> return moveGrabbedChannelRow(+1).let { true }
    }
    return false
}

// —— Home channel strip (horizontal ◀ ▶) ——

/** Non-null while moving a channel card on the home rows. */
internal var movingChannelId: Long? = null

/** After a channel-row reload, focus this `_id` and keep it grabbed. */
internal var pendingChannelFocusId: Long? = null

internal fun MainActivity.startChannelCardMove(v: View, id: Long) {
    ensureChannelOrderSeeded()
    movingChannelId = id
    pendingChannelFocusId = null
    appsTitle.text = getString(R.string.moving_channel_instructions)
    appsTitle.visibility = View.VISIBLE
    markGrabbed(v)
    v.requestFocus()
}

internal fun MainActivity.endChannelCardMove() {
    movingChannelId = null
    pendingChannelFocusId = null
    markGrabbed(null)
    appsTitle.visibility = View.GONE
}

/**
 * Horizontal move on the home channel cards: ◀ ▶ shift order and refresh the row, OK / Back place.
 */
internal fun MainActivity.channelCardMoveKey(e: KeyEvent, confirm: Boolean): Boolean {
    val id = movingChannelId ?: return false
    if (e.keyCode == KeyEvent.KEYCODE_BACK) {
        if (e.action == KeyEvent.ACTION_UP) endChannelCardMove()
        return true
    }
    if (confirm) {
        if (e.action == KeyEvent.ACTION_UP) endChannelCardMove()
        return true
    }
    if (e.action != KeyEvent.ACTION_DOWN) {
        return e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
    }
    when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            if (shiftChannelOrder(id, -1) != null) reloadChannelsForMove(id)
            return true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (shiftChannelOrder(id, +1) != null) reloadChannelsForMove(id)
            return true
        }
    }
    return false
}

private fun MainActivity.reloadChannelsForMove(id: Long) {
    pendingChannelFocusId = id
    val pkg = watchApp
    if (pkg != null) {
        watchKey = null
        queryAppChannels(pkg)
    } else {
        Toast.makeText(this, getString(R.string.setting_channel_order), Toast.LENGTH_SHORT).show()
        endChannelCardMove()
    }
}

/** After [showSections], land on the channel being moved (build cards that far if needed). */
internal fun MainActivity.focusPendingChannelCard() {
    val id = pendingChannelFocusId ?: return
    if (movingChannelId == null) {
        pendingChannelFocusId = null
        return
    }
    for (row in cardRows()) {
        val cards = row.getTag(R.id.watchScroll) as? RowCards ?: continue
        val at = cards.items.indexOfFirst { it.channelId == id }
        if (at < 0) continue
        while (cards.built <= at && cards.built < cards.items.size) moreCards(row, now = true)
        val col = row.getChildAt(at) as? ViewGroup ?: continue
        val card = col.getChildAt(0) ?: continue
        card.requestFocus()
        markGrabbed(card)
        (row.parent as? HorizontalScrollView)?.let { sv ->
            sv.post {
                val x = col.left - (sv.width - col.width) / 2
                sv.smoothScrollTo(x.coerceAtLeast(0), 0)
            }
        }
        pendingChannelFocusId = null
        return
    }
}
