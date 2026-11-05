package link.mccoy88f.zephyr

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

// What shows the downloads (see Downloads) outside their own page: the top bar's Download icon, after Search, with a
// red dot while one is going, and its menu (a window like the users' one); the blue download mark on the cards of what
// is downloaded, red-dotted too while it goes; the Download button's dot on a title's page. All of them follow the
// download manager live: asked once a second while a download goes, and once each time the home screen comes back.

/** Red, as a dot on an icon: a download going. */
private const val DOT_RED = 0xFFEF4444.toInt()

/** The download manager is asked here, so a slow answer never holds up the rows (io) or the network (netIo). */
private val dlIo = Executors.newSingleThreadExecutor()

/** The downloads and how each is, as last asked; what follows them, until its view goes away. */
private var lastEntries: Map<String, Downloads.Entry> = emptyMap()
private var lastStatuses: Map<String, DlStatus> = emptyMap()
private val watchers = java.util.WeakHashMap<View, (Map<String, Downloads.Entry>, Map<String, DlStatus>) -> Unit>()

/** [update] now with what's known, and again each time the downloads change, while [v] is on the screen. */
internal fun MainActivity.watchDownloads(v: View, update: (Map<String, Downloads.Entry>, Map<String, DlStatus>) -> Unit) {
    watchers[v] = update
    update(lastEntries, lastStatuses)
}

/**
 * Asks the download manager how the downloads are, shows it on everything that follows them, and asks again in a
 * second while one is going and the home screen is in front. Those removed in Android's own list are forgotten.
 */
internal fun MainActivity.refreshDownloads() {
    ui.removeCallbacks(downloadsTick)
    dlIo.execute {
        val all = Downloads.all(filesDir)
        val statuses = downloadStatuses(all)
        // Asked and not known any more: removed from Android's list of downloads (its file with it).
        val known = if (statuses == null) all else all.filterKeys { it in statuses }
        if (statuses != null) (all.keys - known.keys).forEach { Downloads.remove(filesDir, it) }
        ui.post {
            lastEntries = known
            lastStatuses = statuses ?: lastStatuses
            showDownloadsButton()
            watchers.entries.toList().forEach { (v, f) -> if (v.isAttachedToWindow) f(lastEntries, lastStatuses) }
            if (Screen.activity === this && lastStatuses.values.any { it.running }) ui.postDelayed(downloadsTick, 1000)
        }
    }
}

/** A red dot in the top right corner of [v] ([on]), or none; drawn over it, as the TV inputs' green one. */
internal fun MainActivity.downloadDot(v: View, on: Boolean) {
    val dot = (v.getTag(R.id.downloadsButton) as? Drawable) ?: GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DOT_RED) }
        .also { v.setTag(R.id.downloadsButton, it) }
    v.overlay.remove(dot)
    if (!on || v.visibility != View.VISIBLE) return
    if (v.width == 0) return v.post { if (v.width > 0) downloadDot(v, on) }.let { }
    val size = dp(9)
    dot.setBounds(v.width - size - dp(3), dp(3), v.width - dp(3), dp(3) + size)
    v.overlay.add(dot)
}

/** The top bar's Download icon: there while something is downloaded or downloading, red-dotted while one goes. */
private fun MainActivity.showDownloadsButton() {
    val b = findViewById<ImageView>(R.id.downloadsButton) ?: return
    b.visibility = if (lastEntries.isEmpty()) View.GONE else View.VISIBLE
    downloadDot(b, lastStatuses.values.any { it.running })
}

/** The blue download mark of a card, with a red dot while it goes. */
private fun MainActivity.downloadMark(running: Boolean): Drawable {
    val icon = getDrawable(R.drawable.ic_download)!!.mutate().apply { setTint(TICK_BLUE) }
    val layers: Array<Drawable> = if (!running) arrayOf(icon) else arrayOf(icon, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DOT_RED) })
    return LayerDrawable(layers).apply {
        if (running) setLayerInset(1, dp(8), 0, 0, dp(8))
        setBounds(0, 0, dp(14), dp(14))
    }
}

/**
 * A card's line under its name ([details]) ends with the blue download mark when what it shows is downloaded, as a ✓
 * says it was watched: a film, an episode (Continue Playing), or a series with any of its episodes.
 */
internal fun MainActivity.markDownloaded(details: TextView, ref: TitleRef) {
    watchDownloads(details) { entries, statuses ->
        val mine = entries.values.filter { it.id == ref.video || (ref.video == null && (it.id == ref.id || it.meta == ref.id)) }
        val start = details.compoundDrawablesRelative[0]
        if (mine.isEmpty()) return@watchDownloads details.setCompoundDrawablesRelative(start, null, null, null)
        details.setCompoundDrawablesRelative(start, null, downloadMark(mine.any { statuses[it.id]?.running == true }), null)
        details.compoundDrawablePadding = dp(6)
        details.visibility = View.VISIBLE
    }
}

// ---------- the menu ----------

/** What a download's row says: how far, waiting, failed, or done with its size (a blue ✓). */
private fun MainActivity.downloadValue(s: DlStatus?): CharSequence = when {
    s == null -> ""
    s.complete -> blueTicks("✓ " + sizeName(s.total))
    s.failed -> getString(R.string.download_failed_status)
    s.status == android.app.DownloadManager.STATUS_RUNNING -> if (s.total > 0) "${s.percent}% · ${sizeName(s.total)}" else sizeName(s.done)
    else -> getString(R.string.download_waiting)
}

/**
 * The downloads' menu, from the top bar's icon, under it as the users' one: a row for each film or episode (OK: its page,
 * to play or delete it; the bin: deleted at once), live; Delete all (pressed twice) and Close at the bottom.
 */
internal fun MainActivity.openDownloadsMenu(anchor: View) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(10), dp(6), dp(6))
        background = menuBackground()
        elevation = dp(8).toFloat()
        minimumWidth = dp(360)
    }
    val menu = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        elevation = dp(8).toFloat()
    }
    box.addView(label(getString(R.string.download), 15f).apply { setPadding(dp(14), 0, dp(14), dp(8)) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(object : ScrollView(this) {
        // No taller than about six rows: the rest scrolls.
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; addView(list) })
    val values = HashMap<String, TextView>()

    fun fill(entries: Map<String, Downloads.Entry>) {
        list.removeAllViews()
        values.clear()
        entries.values.sortedBy { it.name.lowercase() }.forEach { e ->
            val value = label("", 13f).apply { alpha = 0.7f; maxWidth = dp(150); setPadding(dp(12), 0, 0, 0) }
            values[e.id] = value
            val name = label(e.name, 14f).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
            }
            val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
            val row = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = bg
                isFocusable = true
                isClickable = true
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
                setOnClickListener { menu.dismiss(); openDownloaded(e) }
                setOnFocusChangeListener { _, f ->
                    bg.setColor(if (f) fg else 0)
                    name.setTextColor(if (f) onFg else fg)
                    name.isSelected = f
                    value.setTextColor(if (f) onFg else fg)
                }
            }
            val binBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val bin = ImageView(this).apply {
                id = View.generateViewId()
                setImageResource(R.drawable.ic_delete)
                setColorFilter(fg)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = binBg
                isFocusable = true
                isClickable = true
                contentDescription = getString(R.string.download_delete)
                setOnFocusChangeListener { _, f -> binBg.setColor(if (f) fg else 0); setColorFilter(if (f) onFg else fg) }
                setOnClickListener {
                    deleteDownload(e)
                    val left = lastEntries - e.id
                    if (left.isEmpty()) menu.dismiss() else { fill(left); list.getChildAt(0)?.requestFocus() }
                }
            }
            row.nextFocusRightId = bin.id
            bin.nextFocusLeftId = row.id
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(row, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(bin, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(4) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        entries.forEach { (id, _) -> values[id]?.text = downloadValue(lastStatuses[id]) }
    }
    fill(lastEntries)

    // Delete all asks first: its name changes to say so, a second OK deletes them all.
    var sure = false
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    lateinit var deleteAll: View
    deleteAll = settingsRow(getString(R.string.downloads_delete_all), "") {
        if (!sure) {
            sure = true
            ((deleteAll as LinearLayout).getChildAt(0) as TextView).text = getString(R.string.downloads_delete_all_sure)
            return@settingsRow
        }
        lastEntries.values.toList().forEach { deleteDownload(it) }
        menu.dismiss()
    }
    buttons.addView(deleteAll, LinearLayout.LayoutParams(0, dp(44), 1f))
    buttons.addView(settingsRow(getString(R.string.setup_close), "") { menu.dismiss() },
        LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
    box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    // Live: each row's value; a download come or gone, the rows again.
    watchDownloads(box) { entries, statuses ->
        if (entries.keys != values.keys) fill(entries)
        else entries.keys.forEach { id -> values[id]?.text = downloadValue(statuses[id]) }
    }
    refreshDownloads()

    box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val at = IntArray(2).also { anchor.getLocationInWindow(it) }
    val screen = window.decorView.width
    val x = (at[0] - dp(160)).coerceAtMost(screen - box.measuredWidth - dp(16)).coerceAtLeast(dp(16))
    menu.showAtLocation(anchor, Gravity.NO_GRAVITY, x, at[1] + anchor.height + dp(8))
    (list.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() ?: buttons.getChildAt(1).requestFocus()
}

/** [e] deleted: the download stopped (going) or its file removed (done), and forgotten. */
private fun MainActivity.deleteDownload(e: Downloads.Entry) {
    getSystemService(android.app.DownloadManager::class.java)?.let { runCatching { it.remove(e.dm) } }
    Downloads.remove(filesDir, e.id)
    lastEntries = lastEntries - e.id
    refreshDownloads()
}

/** A download's page: its film's, or its episode's; from there it plays or is deleted (its Download button). */
private fun MainActivity.openDownloaded(e: Downloads.Entry) {
    if (e.meta.isEmpty()) return toast(R.string.download_no_page)
    val ref = TitleRef(e.type, e.meta, e.title.ifEmpty { e.name }, e.poster, e.background, e.source, logo = e.logo)
    openTitle(ref, episode = e.id.takeIf { it != e.meta })
}
