package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import link.mccoy88f.zephyr.SettingsRows.TMDB_LIST_ROW

// Screens that hand a job to a phone (remote access): wallpapers, text (fromPhoneText), recent problems.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/**
 * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
 * wallpaper and show it at once. The server runs only while this panel is open.
 */
internal fun MainActivity.uploadWallpaper(forDark: Boolean) {
    val dir = wallpaperDir() ?: return
    val status = label(getString(R.string.upload_waiting), 14f)
    val page = PhonePages.file(phoneTexts(R.string.phone_wallpaper_title, R.string.phone_wallpaper_intro), getString(R.string.phone_choose_photo), "image/*")
    showPhoneLink(getString(R.string.add_from_phone_title), PhoneTask(page, 25L * 1024 * 1024) { file ->
        val saved = importWallpaper(file, dir) ?: return@PhoneTask 415 to getString(R.string.phone_not_a_photo)
        ui.post {
            setWallpaper(forDark, "file:${saved.name}")
            status.text = getString(R.string.upload_added_status, saved.nameWithoutExtension)
        }
        200 to getString(R.string.phone_wallpaper_added, saved.nameWithoutExtension)
    }, status, target = if (forDark) "wallpaper-dark" else "wallpaper-light") { pickWallpaper(forDark) }
}

internal fun MainActivity.phoneTexts(title: Int, intro: Int) = phoneTexts(getString(title), getString(intro))

/** The texts of a phone page: [title], [intro] and the button's [send], with this TV's name and theme. */
internal fun MainActivity.phoneTexts(title: String, intro: String, send: String = getString(R.string.phone_send)) = PhoneTexts(
    title, intro, send, getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter(), phoneTheme())

/** The TV's name as set in Android settings, else its model. */
internal fun MainActivity.tvName(): String =
    android.provider.Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

/** "Zephyr TV - TV del salotto". */
internal fun MainActivity.phoneFooter() = "${getString(R.string.app_name)} - ${tvName()}"

/**
 * A settings page that can hand [task] to a phone on the home network, for anything easier done on a phone than
 * with a remote (a photo, a long key). **Use remote access** shows one code for [target] (see Remote). [status]
 * is the line under that code. [more] rows come before Done (another way to do it); [back] leaves the page.
 */
internal fun MainActivity.showPhoneLink(
    title: String, task: PhoneTask, status: TextView,
    more: List<Triple<String, String, () -> Unit>> = emptyList(), target: String, back: () -> Unit,
) {
    if (handheld()) return doHere(title, task, status, more, back) // a phone or tablet: here, not on another phone
    showPage(title, listOf(page(getString(R.string.remote_use)) {
        presentRemote(title, target, task, back, status)
    }) + more + page(getString(R.string.dialog_done)) { back() }, back = back)
}

/**
 * Paste some text from a phone: the same QR page everywhere (a cookie, a key, an address). [got] runs on the UI
 * thread with the text. [refuse] may reject it on the server thread (the message, or null). [saved] is what the
 * phone and the status show when it is kept. [more] is usually **Type it with the remote**.
 */
internal fun MainActivity.fromPhoneText(
    title: String, intro: String, hint: String, waiting: String, got: (String) -> Unit, back: () -> Unit,
    more: List<Triple<String, String, () -> Unit>> = emptyList(), secret: Boolean = false, maxBytes: Long = 1024L * 1024,
    refuse: (String) -> String? = { if (it.isEmpty()) getString(R.string.nuvio_option_empty) else null },
    saved: (String) -> String = { getString(R.string.nuvio_option_saved) },
    target: String,
) {
    val status = label(waiting, 14f)
    val texts = phoneTexts(title, intro)
    showPhoneLink(title, PhoneTask(PhonePages.text(texts, hint, secret), maxBytes) { file ->
        val text = file.readText().trim()
        refuse(text)?.let { return@PhoneTask 400 to it }
        val ok = saved(text)
        ui.post {
            got(text)
            status.text = ok
        }
        200 to ok
    }, status, more, target, back)
}

/**
 * Shown instead of a QR code while WARP is on: the code would open a page on this TV, and a phone on the
 * home network cannot reach it.
 */
internal fun MainActivity.warpQrNotice() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(8), dp(12), dp(8), dp(16))
    addView(label(getString(R.string.warp_qr_blocked), 15f).apply {
        gravity = Gravity.CENTER
        maxLines = 8
        setLineSpacing(0f, 1.2f)
    })
}

/**
 * A QR code of [url] for the phone, [caption] under it (the address, to type instead), [intro] (what to do), and
 * [status] (how it's going), all in the middle: the top of a page for something done on a phone.
 */
internal fun MainActivity.qrBox(url: String, caption: String?, intro: String, status: TextView) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, dp(4), 0, dp(12))
    addView(ImageView(this@qrBox).apply { setImageBitmap(qrBitmap(url, dp(200))) })
    caption?.let {
        addView(label(it, 12f).apply {
            gravity = Gravity.CENTER
            maxLines = 4 // an address the phone can keep, not one line cut short
            ellipsize = null
            setPadding(0, dp(12), 0, dp(4))
        })
    }
    addView(label(intro, 12f).apply {
        gravity = Gravity.CENTER
        alpha = 0.6f
        maxLines = 4
        if (caption == null) setPadding(0, dp(12), 0, 0)
    })
    addView(status.apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(12), 0, 0) })
}

/** The last problems (newest first), to see what went wrong; readable on a phone too, where lines fit. */
internal fun MainActivity.showProblems() {
    val recent = Problems.recent()
    val rows = listOf(
        page(getString(R.string.problems_on_phone)) { problemsOnPhone() },
        page(getString(R.string.dialog_clear)) { Problems.clear(); showProblems() },
    ) + recent.map { page(it) {} }
    showPage(getString(R.string.setting_problems), rows) { renderSettings(problemsRow()) }
}

/** The recent problems as a page, read on a phone. Built when the page is asked for, so it stays current. */
internal fun MainActivity.problemsHtml(): String {
    val list = Problems.recent().ifEmpty { listOf(getString(R.string.problems_none)) }
    return PhonePages.view(phoneTexts(getString(R.string.setting_problems), ""), "<ol>${list.joinToString("") { "<li>${escHtml(it)}</li>" }}</ol>").html
}

internal fun MainActivity.problemsOnPhone() {
    showPhoneLink(getString(R.string.setting_problems), PhoneTask(problemsHtml(), 0) { 405 to "" }, label("", 12f), target = "problems") { showProblems() }
}

/** The TMDB key typed or pasted on a phone: checked with TMDB, then saved like the Save row does. */
internal fun MainActivity.tmdbKeyFromPhone() {
    var unchecked = false
    fromPhoneText(getString(R.string.setting_tmdb_api_key), getString(R.string.phone_tmdb_intro), getString(R.string.phone_tmdb_hint),
        getString(R.string.tmdb_phone_waiting), { saveTmdbKey(it) }, { configureCinemeta(tmdbRow(1)) }, secret = true, maxBytes = 2048,
        refuse = { key ->
            if (key.length < 16 || key.any { it.isWhitespace() }) getString(R.string.phone_tmdb_refused)
            else {
                val ok = TmdbClient.check(key)
                unchecked = ok == null
                if (ok == false) getString(R.string.phone_tmdb_refused) else null
            }
        },
        saved = { getString(if (unchecked) R.string.phone_tmdb_unchecked else R.string.phone_tmdb_saved) },
        target = "tmdb-key")
}

/** The guide's address pasted on a phone: saved like the Save row does, and fetched at once. */
internal fun MainActivity.epgFromPhone() {
    fromPhoneText(getString(R.string.setting_epg), getString(R.string.phone_epg_intro), getString(R.string.epg_hint),
        getString(R.string.epg_phone_waiting), { saveEpgUrl(it) }, { configureEpg() },
        refuse = { url ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) getString(R.string.phone_epg_bad) else null
        },
        saved = { getString(R.string.phone_epg_saved) }, target = "epg")
}

/** The TV's own address on the home network (IPv4: easy to read and type). */
internal fun MainActivity.lanAddress(): String? {
    val cm = getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
        ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}

/** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
internal fun MainActivity.qrBitmap(text: String, maxPx: Int): Bitmap {
    val m = Qr.encode(text)
    val n = m.size + 8
    val px = maxOf(1, maxPx / n)
    val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
    bmp.eraseColor(Color.WHITE)
    val canvas = Canvas(bmp)
    val paint = Paint().apply { color = Color.BLACK }
    for (r in m.indices) for (c in m.indices) if (m[r][c]) {
        canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
    }
    return bmp
}

/** Backup and restore: both done on the phone, which is where the file is kept. */
internal fun MainActivity.showBackup() {
    showPage(getString(R.string.setting_backup), listOf(
        page(getString(R.string.backup_save)) { backupToPhone() },
        page(getString(R.string.backup_restore)) { restoreFromPhone() },
    )) { renderSettings(backupRow()) }
}

/** A backup of this TV, made now, and the line that names the extensions too large to be in it (empty when none). */
private fun MainActivity.makeBackup(): Pair<String, String> {
    val json = Backup.export(prefs, filesDir, users().map { it.id })
    val names = Backup.skipped(json).map { Extensions.installed(filesDir, it)?.name ?: it }
    return json to if (names.isEmpty()) "" else getString(R.string.backup_skipped, names.joinToString(", "))
}

/** The phone page that saves [backup] (see makeBackup): its line on what is left out under the intro. */
private fun MainActivity.backupPage(backup: Pair<String, String>) = PhonePages.download(
    phoneTexts(getString(R.string.phone_backup_title),
        listOf(getString(R.string.phone_backup_intro), backup.second).filter { it.isNotEmpty() }.joinToString(" "),
        getString(R.string.phone_backup_download)),
    "zephyr-backup.json", backup.first)

/** The phone page that saves a backup of this TV, made now. Off the UI thread: it reads the extensions' files. */
internal fun MainActivity.backupPage() = backupPage(makeBackup())

/**
 * The backup to save on the phone. With a PIN set, the PIN first: the backup holds the add-ons and the sign-ins.
 * The backup is made off the UI thread (the extensions' packages can take a moment), then its code shows.
 */
internal fun MainActivity.backupToPhone(asked: Boolean = false) {
    if (pinSet() && !asked) return askPin({ showBackup() }) { backupToPhone(asked = true) }
    netIo.execute {
        val backup = runCatching { makeBackup() }.onFailure { warn("backup failed ($it)") }.getOrNull()
        ui.post {
            if (backup == null) return@post toast(R.string.backup_failed)
            val status = label(backup.second, 12f)
            showPhoneLink(getString(R.string.backup_save), PhoneTask(backupPage(backup), 0) { 405 to "" }, status, target = "backup-save") { showBackup() }
        }
    }
}

internal fun MainActivity.restoreFromPhone() {
    val status = label(getString(R.string.backup_waiting), 14f)
    val page = PhonePages.file(phoneTexts(R.string.phone_restore_title, R.string.phone_restore_intro), getString(R.string.phone_choose_file), ".json,application/json")
    // Up to a few MB: the users' watched titles and progress come with the settings.
    showPhoneLink(getString(R.string.backup_restore), PhoneTask(page, 16L * 1024 * 1024) { file ->
        restoreBackup(file.readText(), status)
    }, status, target = "backup-restore") { showBackup() }
}

/**
 * [text], a backup sent from a phone, put back: at once, or once a new PIN is chosen on the TV when the TV that
 * made it had a PIN (the PIN never goes in a backup). Runs on a server thread; the answer is for the phone.
 * [status] is the line on the TV's restore screen, when it is open.
 */
internal fun MainActivity.restoreBackup(text: String, status: TextView? = null): Pair<Int, String> {
    val needsPin = try {
        Backup.needsPin(text)
    } catch (e: Exception) {
        warn("restore failed ($e)")
        return 400 to getString(R.string.phone_restore_bad)
    }
    if (needsPin) {
        // After the phone has its answer: the PIN page takes the place of the restore screen and its server.
        ui.postDelayed({ restoreWithNewPin(text) }, 1500)
        return 200 to getString(R.string.phone_restore_pin)
    }
    val n = try {
        Backup.restore(prefs, text, filesDir)
    } catch (e: Exception) {
        warn("restore failed ($e)")
        return 400 to getString(R.string.phone_restore_bad)
    }
    ui.post { status?.text = getString(R.string.phone_restore_done, n) }
    restartAfterRestore(2000)
    return 200 to getString(R.string.phone_restore_done, n)
}

/** The home screen again with the restored settings, back on the backup row, after [delayMs]. */
private fun MainActivity.restartAfterRestore(delayMs: Long) = ui.postDelayed({
    prefs.edit().putInt(K.REOPEN_ROW, backupRow()).apply()
    recreate()
}, delayMs)

/** A new PIN chosen on the TV (or from the phone), then the backup in [text] put back with it. */
private fun MainActivity.restoreWithNewPin(text: String) {
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    choosePin(getString(R.string.restore_pin_title), { showBackup() }) { hash ->
        netIo.execute {
            val n = runCatching { Backup.restore(prefs, text, filesDir, hash) }
                .onFailure { warn("restore failed ($it)") }.getOrNull()
            ui.post {
                if (n == null) return@post toast(R.string.phone_restore_bad)
                android.widget.Toast.makeText(this, getString(R.string.phone_restore_done, n), android.widget.Toast.LENGTH_LONG).show()
                restartAfterRestore(1500)
            }
        }
    }
}

/** The user's public TMDB list, pasted on the phone (its link or number); checked by loading it, then shown. */
internal fun MainActivity.tmdbListFromPhone() {
    val key = prefs.getString(K.TMDB_API_KEY, null) ?: return configureTmdbApiKey()
    val status = label(getString(R.string.list_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.phone_list_title, R.string.phone_list_intro), getString(R.string.phone_list_hint))
    showPhoneLink(getString(R.string.setting_tmdb_list), PhoneTask(page, 2048) { file ->
        val id = TmdbClient.listId(file.readText()) ?: return@PhoneTask 400 to getString(R.string.phone_list_bad)
        val items = TmdbClient.fetchList(key, id, tmdbLang())
        if (items.isEmpty()) return@PhoneTask 400 to getString(R.string.phone_list_bad)
        ui.post {
            prefs.edit().putString(K.TMDB_LIST, id).putString(K.TMDB_SOURCE, "list").apply()
            tmdbTrending = emptyList()
            loadWallpaper()
            status.text = getString(R.string.phone_list_saved, items.size)
        }
        200 to getString(R.string.phone_list_saved, items.size)
    }, status, target = "tmdb-list") { renderSettings(TMDB_LIST_ROW) }
}

