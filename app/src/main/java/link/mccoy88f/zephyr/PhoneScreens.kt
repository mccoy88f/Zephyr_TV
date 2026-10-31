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
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.BACKUP_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.PROBLEMS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.TMDB_LIST_ROW
import java.io.IOException

// Screens that hand a job to a phone over a QR code (PhoneLink): wallpapers, the TMDB key, recent problems.
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
    }, status) { pickWallpaper(forDark) }
}

internal fun MainActivity.phoneTexts(title: Int, intro: Int) = PhoneTexts(getString(title), getString(intro),
    getString(R.string.phone_send), getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())

/** "Zephyr TV - TV del salotto": the TV's name as set in Android settings, else its model. */
internal fun MainActivity.phoneFooter(): String {
    val name = android.provider.Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    return "Zephyr TV - $name"
}

/**
 * A settings page with a QR code that opens [task] on a phone on the same Wi-Fi, for anything easier done on a
 * phone than with a remote (a photo, a long key). The link works while the page is open; [status] shows under
 * the code, for the task to report what arrived. [more] rows come before Done (another way to do it); [back]
 * leaves the page.
 */
internal fun MainActivity.showPhoneLink(
    title: String, task: PhoneTask, status: TextView,
    more: List<Triple<String, String, () -> Unit>> = emptyList(), back: () -> Unit,
) {
    if (handheld()) return doHere(title, task, status, more, back) // a phone or tablet: here, not on another phone
    val ip = lanAddress() ?: return Toast.makeText(this, getString(R.string.toast_connect_network), Toast.LENGTH_LONG).show()
    val link = try {
        PhoneLink(cacheDir, task)
    } catch (e: IOException) {
        return Toast.makeText(this, getString(R.string.toast_upload_server_failed), Toast.LENGTH_LONG).show()
    }
    upload = link
    val url = link.url(ip)
    val box = qrBox(url, url, getString(R.string.upload_qr_instructions), status)
    showPage(title, more + page(getString(R.string.dialog_done)) { back() }, top = box, back = back)
    pageExit = {
        link.stop()
        if (upload === link) upload = null
    }
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
    caption?.let { addView(label(it, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }) }
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
    showPage(getString(R.string.setting_problems), rows) { renderSettings(PROBLEMS_ROW) }
}

internal fun MainActivity.problemsOnPhone() {
    val esc = { t: String -> t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
    val list = Problems.recent().ifEmpty { listOf(getString(R.string.problems_none)) }
    val page = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Zephyr TV</title><style>body{font:14px ui-monospace,monospace;margin:0;padding:20px;background:#f7f8fa;color:#111214}
        @media (prefers-color-scheme:dark){body{background:#09090b;color:#f5f5f7}} li{margin:0 0 10px;word-break:break-word}</style></head>
        <body><h1 style="font:600 20px system-ui,sans-serif">${esc(getString(R.string.setting_problems))}</h1><ol>${list.joinToString("") { "<li>${esc(it)}</li>" }}</ol>
        <footer style="margin-top:32px;font:13px system-ui,sans-serif;opacity:.5;text-align:center">${esc(phoneFooter())}</footer></body></html>"""
    showPhoneLink(getString(R.string.setting_problems), PhoneTask(page, 0) { 405 to "" }, label("", 12f)) { showProblems() }
}

/** The TMDB key typed or pasted on a phone: checked with TMDB, then saved like the Save row does. */
internal fun MainActivity.tmdbKeyFromPhone() {
    val status = label(getString(R.string.tmdb_phone_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.phone_tmdb_title, R.string.phone_tmdb_intro), getString(R.string.phone_tmdb_hint))
    showPhoneLink(getString(R.string.setting_tmdb_api_key), PhoneTask(page, 2048) { file ->
        val key = file.readText().trim()
        if (key.length < 16 || key.any { it.isWhitespace() }) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
        val ok = TmdbClient.check(key)
        if (ok == false) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
        ui.post {
            saveTmdbKey(key)
            status.text = getString(R.string.phone_tmdb_saved)
        }
        200 to getString(if (ok == true) R.string.phone_tmdb_saved else R.string.phone_tmdb_unchecked)
    }, status) { configureCinemeta(tmdbRow(1)) }
}

/** The guide's address pasted on a phone: saved like the Save row does, and fetched at once. */
internal fun MainActivity.epgFromPhone() {
    val status = label(getString(R.string.epg_phone_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.phone_epg_title, R.string.phone_epg_intro), getString(R.string.epg_hint))
    showPhoneLink(getString(R.string.setting_epg), PhoneTask(page, 1024L * 1024) { file ->
        val url = file.readText().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@PhoneTask 400 to getString(R.string.phone_epg_bad)
        ui.post {
            saveEpgUrl(url)
            status.text = getString(R.string.phone_epg_saved)
        }
        200 to getString(R.string.phone_epg_saved)
    }, status) { configureEpg() }
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
    )) { renderSettings(BACKUP_ROW) }
}

internal fun MainActivity.backupToPhone() {
    val page = PhonePages.download(getString(R.string.phone_backup_title), getString(R.string.phone_backup_intro),
        getString(R.string.phone_backup_download), "zephyr-backup.json", Backup.export(prefs, filesDir, users().map { it.id }), phoneFooter())
    showPhoneLink(getString(R.string.backup_save), PhoneTask(page, 0) { 405 to "" }, label("", 12f)) { showBackup() }
}

internal fun MainActivity.restoreFromPhone() {
    val status = label(getString(R.string.backup_waiting), 14f)
    val page = PhonePages.file(phoneTexts(R.string.phone_restore_title, R.string.phone_restore_intro), getString(R.string.phone_choose_file), ".json,application/json")
    // Up to a few MB: the users' watched titles and progress come with the settings.
    showPhoneLink(getString(R.string.backup_restore), PhoneTask(page, 16L * 1024 * 1024) { file ->
        val n = try {
            Backup.restore(prefs, file.readText(), filesDir)
        } catch (e: Exception) {
            warn("restore failed ($e)")
            return@PhoneTask 400 to getString(R.string.phone_restore_bad)
        }
        // Restart the home screen with the restored settings once the phone has its answer, back on this row.
        ui.post { status.text = getString(R.string.phone_restore_done, n) }
        ui.postDelayed({
            prefs.edit().putInt(K.REOPEN_ROW, BACKUP_ROW).apply()
            recreate()
        }, 2000)
        200 to getString(R.string.phone_restore_done, n)
    }, status) { showBackup() }
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
    }, status) { renderSettings(TMDB_LIST_ROW) }
}

