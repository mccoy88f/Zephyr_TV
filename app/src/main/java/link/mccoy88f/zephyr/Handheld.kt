package link.mccoy88f.zephyr

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.InputType
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

// Phones and tablets: what a TV hands to a phone over a QR code (PhoneLink), done on the device itself, with its own
// keyboard, file picker and Save dialog. Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** A phone or a tablet: a touch screen, and not a TV (Android TV and Google TV say leanback). */
internal fun MainActivity.handheld(): Boolean = !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
    packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

/**
 * A page's rows on a phone: those that offer to do on a phone what the page itself does with its keyboard go, and
 * those that send a photo or a list from a phone say what they do here.
 */
internal fun MainActivity.handheldRows(rows: List<Triple<String, String, () -> Unit>>): List<Triple<String, String, () -> Unit>> {
    if (!handheld()) return rows
    val gone = listOf(R.string.from_phone, R.string.from_phone_type, R.string.problems_on_phone)
        .map { getString(it) }.toSet()
    val renamed = mapOf(getString(R.string.add_from_phone) to getString(R.string.add_photo), getString(R.string.list_from_phone) to getString(R.string.here_type))
    return rows.filter { it.first !in gone }.map { r -> renamed[r.first]?.let { Triple(it, r.second, r.third) } ?: r }
}

/**
 * [task], which a TV hands to a phone, done here: some text, a form, an add-on (with its 18+ box) typed with the
 * device's keyboard; a file from its file picker; a file saved with its Save dialog. [status] shows how it went;
 * [more] rows and [back] as on the phone page (see showPhoneLink).
 */
internal fun MainActivity.doHere(title: String, task: PhoneTask, status: TextView, more: List<Triple<String, String, () -> Unit>>, back: () -> Unit) {
    val intro = task.page.texts?.intro
    when (val l = task.page.local) {
        is PhoneLocal.Text -> fieldsHere(title, intro, listOf(Triple("", l.hint, "" to l.secret)), status, more, back) { v -> sendHere(task, v[0], status) }
        is PhoneLocal.Form -> fieldsHere(title, intro, l.fields, status, more, back) { v ->
            val o = JSONObject()
            l.fields.forEachIndexed { i, f -> if (v[i].isNotBlank()) o.put(f.first, v[i]) }
            sendHere(task, o.toString(), status)
        }
        is PhoneLocal.Addon -> addonHere(title, intro, l, task, status, more, back, adult = false)
        is PhoneLocal.File -> pageHere(title, intro, status, listOf(page(getString(R.string.here_choose_file)) {
            pick(l.accept) { uri -> uri?.let { fileHere(task, it, status) } }
        }) + more, back)
        is PhoneLocal.Download -> pageHere(title, intro, status, listOf(page(getString(R.string.here_save_file)) {
            save(l.fileName) { uri ->
                if (uri == null) return@save
                val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(l.content.toByteArray()) } != null }.getOrDefault(false)
                status.text = getString(if (ok) R.string.here_saved else R.string.toast_addon_failed)
            }
        }) + more, back)
        PhoneLocal.View -> back() // only to read on a phone: its page here shows it already
    }
}

/** A page with [intro] and [status] at the top, then [rows] and Done. */
private fun MainActivity.pageHere(title: String, intro: String?, status: TextView, rows: List<Triple<String, String, () -> Unit>>, back: () -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(12))
        intro?.let { addView(label(it, 13f).apply { alpha = 0.7f; maxLines = 6; setLineSpacing(0f, 1.15f) }) }
        (status.parent as? android.view.ViewGroup)?.removeView(status)
        addView(status.apply { maxLines = 3; setPadding(0, dp(10), 0, 0) })
    }
    showPage(title, rows + page(getString(R.string.dialog_done)) { back() }, top = box, back = back)
}

/** A box per field ([fields]: key, label, placeholder and secret), then Save: [send] gets what's in them, in order. */
private fun MainActivity.fieldsHere(
    title: String, intro: String?, fields: List<Triple<String, String, Pair<String, Boolean>>>, status: TextView,
    more: List<Triple<String, String, () -> Unit>>, back: () -> Unit, extra: List<Triple<String, String, () -> Unit>> = emptyList(),
    values: List<String> = emptyList(), send: (List<String>) -> Unit,
): List<EditText> {
    val inputs = fields.mapIndexed { i, (_, label, more2) ->
        urlField(values.getOrNull(i).orEmpty(), more2.first.ifBlank { label }).apply {
            if (more2.second) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024 * 1024))
        }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        fields.forEachIndexed { i, f ->
            if (fields.size > 1) addView(label(f.second, 13f).apply { setPadding(dp(4), if (i > 0) dp(10) else 0, dp(4), dp(6)) })
            addView(inputs[i])
        }
        intro?.let { addView(label(it, 12f).apply { alpha = 0.6f; maxLines = 5; setPadding(dp(4), dp(10), dp(4), 0) }) }
        (status.parent as? android.view.ViewGroup)?.removeView(status) // the page again (the 18+ box changed)
        addView(status.apply { maxLines = 3; setPadding(dp(4), dp(10), dp(4), 0) })
    }
    val rows = listOf(page(getString(R.string.dialog_save)) { send(inputs.map { it.text?.toString()?.trim().orEmpty() }) }) + extra + more +
        page(getString(R.string.dialog_done)) { back() }
    showPage(title, rows, top = box, back = back)
    inputs.firstOrNull()?.requestFocus()
    return inputs
}

/** An add-on's address and its 18+ box, sent as the phone page sends them (the address, then "adult" or nothing). */
private fun MainActivity.addonHere(title: String, intro: String?, l: PhoneLocal.Addon, task: PhoneTask, status: TextView,
                                   more: List<Triple<String, String, () -> Unit>>, back: () -> Unit, adult: Boolean, typed: String = "") {
    lateinit var inputs: List<EditText>
    val toggle = page(l.adultLabel, toggle(adult)) { addonHere(title, intro, l, task, status, more, back, !adult, inputs[0].text?.toString().orEmpty()) }
    inputs = fieldsHere(title, intro, listOf(Triple("", l.hint, "" to false)), status, more, back, listOf(toggle), listOf(typed)) { v ->
        sendHere(task, v[0] + "\n" + (if (adult) "adult" else ""), status)
    }
}

/** [text] to [task], as if a phone had sent it, off the UI thread: what it says goes in [status]. */
private fun MainActivity.sendHere(task: PhoneTask, text: String, status: TextView) {
    if (text.isBlank()) return
    status.text = task.page.texts?.sending ?: getString(R.string.phone_sending)
    Thread {
        val f = File.createTempFile("here", ".part", cacheDir)
        val (_, said) = try {
            f.writeText(text)
            task.onSend(f)
        } catch (e: Exception) {
            warn("on the device: $e")
            400 to getString(R.string.toast_addon_failed)
        } finally {
            f.delete()
        }
        ui.post { status.text = said; if (said.isNotBlank()) Toast.makeText(this, said, Toast.LENGTH_SHORT).show() }
    }.start()
}

/** The file at [uri] to [task], copied as a phone would upload it (up to its size), off the UI thread. */
private fun MainActivity.fileHere(task: PhoneTask, uri: Uri, status: TextView) {
    status.text = task.page.texts?.sending ?: getString(R.string.phone_sending)
    Thread {
        val f = File.createTempFile("here", ".part", cacheDir)
        val (_, said) = try {
            val n = contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: 0L
            if (n <= 0 || (task.maxBytes > 0 && n > task.maxBytes)) getString(R.string.here_too_big).let { 413 to it }
            else task.onSend(f)
        } catch (e: Exception) {
            warn("file on the device: $e")
            400 to getString(R.string.toast_addon_failed)
        } finally {
            f.delete()
        }
        ui.post { status.text = said; if (said.isNotBlank()) Toast.makeText(this, said, Toast.LENGTH_SHORT).show() }
    }.start()
}

// The types of a file input's accept (".json,application/json", or all images) as MIME types.
private fun types(accept: String): Array<String> = accept.split(',').map { it.trim() }.mapNotNull { a ->
    if ('/' in a) a else MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.removePrefix("."))
}.distinct().ifEmpty { listOf("*/*") }.toTypedArray()

/** The device's file picker, for a file of [accept] types; [done] gets it, or null. */
internal fun MainActivity.pick(accept: String, done: (Uri?) -> Unit) {
    val t = types(accept)
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType(t.singleOrNull() ?: "*/*").apply { if (t.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, t) }
    startForFile(intent, done)
}

/** The device's Save dialog, for a file named [name]; [done] gets where, or null. */
private fun MainActivity.save(name: String, done: (Uri?) -> Unit) {
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream"
    startForFile(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name), done)
}

private fun MainActivity.startForFile(intent: Intent, done: (Uri?) -> Unit) {
    fileDone = done
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, MainActivity.FILE_REQUEST)
    } catch (e: Exception) {
        fileDone = null
        Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
    }
}

/** Back from the file picker or the Save dialog (see onActivityResult). */
internal fun MainActivity.fileResult(result: Int, data: Intent?) {
    val done = fileDone ?: return
    fileDone = null
    done(if (result == Activity.RESULT_OK) data?.data else null)
}

/**
 * The side panel on a phone or tablet: ← at its top left goes to the page before, ✕ at its top right closes it, and so
 * does a tap on the home screen beside it. Added once; [showPanel] calls it each time the panel opens, and each time
 * the two buttons take the theme: panes of glass in Glass, dark circles otherwise.
 */
internal fun MainActivity.panelClose() {
    if (!handheld()) return
    val scrim = settingsScrim as android.view.ViewGroup
    val made = scrim.findViewWithTag<android.widget.ImageView>(PANEL_CLOSE)?.let { it to scrim.findViewWithTag<android.widget.ImageView>(PANEL_BACK) }
    val (x, left) = made ?: run {
        val panel = findViewById<android.view.View>(R.id.settingsPanel)
        panel.isClickable = true // a tap on the panel's own space stays in it
        settingsScrim.setOnClickListener { closeLauncherSettings() }
        // Icons, not the characters ✕ and ←: a font puts those off the middle of the circle.
        fun button(tag: String, icon: Int, about: Int, tap: () -> Unit) = android.widget.ImageView(this).apply {
            this.tag = tag
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(about)
            elevation = dp(12).toFloat()
            setOnClickListener { tap() }
        }
        val size = dp(36)
        val x = button(PANEL_CLOSE, R.drawable.ic_panel_close, R.string.setup_close) { closeLauncherSettings() }
        // At the panel's top right, which is the screen's: the panel is on the right.
        scrim.addView(x, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
        // At its top left, ←: the page before, as Back (and the panel closed from its first page).
        @Suppress("DEPRECATION")
        val left = button(PANEL_BACK, R.drawable.ic_panel_back, R.string.back) { onBackPressed() }
        scrim.addView(left, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = panel.layoutParams.width - size - dp(8) // the panel is as wide as its layout says
        })
        x to left
    }
    for (b in listOfNotNull(x, left)) {
        if (glass) glassPane(b, dp(18)) // a circle: half its size
        else {
            b.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt()) // readable on the panel and on a title's picture alike
            }
            b.foreground = null
        }
    }
}

/** The ← button at the top left of a panel [width] wide (null: the side panel's own), as a whole page needs it. */
internal fun MainActivity.panelButtonsAt(width: Int?) {
    val left = (settingsScrim as android.view.ViewGroup).findViewWithTag<android.view.View>(PANEL_BACK) ?: return
    val lp = left.layoutParams as android.widget.FrameLayout.LayoutParams
    lp.marginEnd = (width ?: dp(380)) - lp.width - dp(8) // dp(380): the side panel's width (activity_main.xml)
    left.layoutParams = lp
}

private const val PANEL_CLOSE = "panel-close"
private const val PANEL_BACK = "panel-back"

/**
 * A phone or tablet: its status bar (time, network, notifications) hidden over the home screen, unless switched on in
 * Appearance; a swipe down from the top shows it for a moment. On a TV there's none.
 */
internal fun MainActivity.applyStatusBar() {
    if (!handheld()) return
    val shown = prefs.getBoolean(K.STATUS_BAR, false)
    if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c ->
        c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (shown) c.show(android.view.WindowInsets.Type.statusBars()) else c.hide(android.view.WindowInsets.Type.statusBars())
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (shown) 0
            else android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

/** Cards across a row: four, as on a TV; more on a tablet, as wide as they are on a TV. */
internal fun MainActivity.cardsAcross(): Int {
    val c = resources.configuration
    return if (handheld() && c.smallestScreenWidthDp >= 600) (c.screenWidthDp / 190).coerceIn(4, 6) else 4
}

/** Apps that showed no rows under them: on a phone they open with one tap, as there's nothing to select them for. */
internal fun MainActivity.rowless(pkg: String?): Boolean =
    pkg != null && (pkg.startsWith(MainActivity.HDMI) || pkg in prefs.getStringSet(K.ROWLESS, emptySet())!!)

/** What [pkg]'s rows turned out to be: kept, and its tile's tap set to match (see refreshApps). */
internal fun MainActivity.rowsKnown(pkg: String?, has: Boolean) {
    // Zephyr TV's own apps (Films, an add-on's) always get selected: rows they lack now (offline) come later.
    if (pkg == null || pkg == CW_PKG || pkg == SEARCH_PKG || pkg.startsWith(STREMIO)) return
    val set = prefs.getStringSet(K.ROWLESS, emptySet())!!
    if ((pkg in set) == !has) return
    prefs.edit().putStringSet(K.ROWLESS, if (has) set - pkg else set + pkg).apply()
    (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == pkg }?.isFocusableInTouchMode = has
}
