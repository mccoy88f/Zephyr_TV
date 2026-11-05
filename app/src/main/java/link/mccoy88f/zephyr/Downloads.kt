package link.mccoy88f.zephyr

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import link.mccoy88f.zephyr.SettingsRows.DOWNLOAD_FOLDER_ROW
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Download: a film or an episode saved on the TV, from the Download button among the apps on its page (see serviceBar).
// Android's download manager does the work, in the background and through restarts, into the folder chosen in
// Settings → Content → Download folder. Once saved, the file is the first of the title's sources: Watch plays it,
// and the streams' list shows it on top. Not for live TV, channels, events or radio: a live stream has no end to
// save. Functions of MainActivity, kept here by topic.

/** The films and episodes downloaded, by their id (the film's, or the episode's), in downloads.json. */
internal object Downloads {
    /** [dm]: the download manager's id for it; [name]: what it's called; [file]: its file's name, extension and all. */
    internal class Entry(val id: String, val dm: Long, val name: String, val file: String)

    private fun file(dir: File) = File(dir, "downloads.json")

    @Synchronized
    fun all(dir: File): Map<String, Entry> = runCatching {
        val a = JSONArray(file(dir).readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Entry(it.getString("id"), it.getLong("dm"), it.optString("name"), it.optString("file").ifEmpty { it.optString("name") + ".mkv" }) }
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun put(dir: File, e: Entry) = write(dir, all(dir) + (e.id to e))

    @Synchronized
    fun remove(dir: File, id: String) = write(dir, all(dir) - id)

    private fun write(dir: File, entries: Map<String, Entry>) {
        val a = JSONArray()
        entries.values.forEach { a.put(JSONObject().put("id", it.id).put("dm", it.dm).put("name", it.name).put("file", it.file)) }
        runCatching { file(dir).writeText(a.toString()) }
    }
}

/** Live TV, channels, events and radio have no Download: what they play doesn't end. */
internal fun downloadableType(type: String) = type.lowercase() !in setOf("tv", "channel", "live", "event", "events", "radio") &&
    collectorOf(type) !in setOf("tv", "events", "radio")

/**
 * A stream that's one file on the web: not a playlist of pieces (HLS, DASH), not another app's link, not one with keys
 * or its sound apart. Those the download manager can't save as one file to play.
 */
internal fun downloadableStream(s: Stremio.Stream): Boolean {
    if (s.app != null || s.audio != null || s.clearkey.isNotEmpty()) return false
    if (!s.url.startsWith("http://", true) && !s.url.startsWith("https://", true)) return false
    val path = s.url.substringBefore('?').substringBefore('#').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", "/manifest").none { path.endsWith(it) }
}

// ---------- where they go ----------

/** The folder in Download and Movies the files go in. */
private const val SUB = "Zephyr TV"

/** A folder downloads can go to: [key] as kept in K.DOWNLOAD_FOLDER, [name] as shown, [path] where to find the files. */
internal class DownloadFolder(val key: String, val name: String, val path: String)

/**
 * The folders to choose from: Zephyr TV's own (gone if the app is removed), the TV's Download and Movies folders (seen
 * by file managers and players), and Zephyr TV's folder on each drive or card plugged in (the only place on one that
 * Android lets an app save to without asking).
 */
internal fun MainActivity.downloadFolders(): List<DownloadFolder> {
    val list = ArrayList<DownloadFolder>()
    getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { list += DownloadFolder("app", getString(R.string.download_folder_app), it.path) }
    val root = Environment.getExternalStorageDirectory().path
    list += DownloadFolder("downloads", getString(R.string.download_folder_downloads), "$root/${Environment.DIRECTORY_DOWNLOADS}/$SUB")
    list += DownloadFolder("movies", getString(R.string.download_folder_movies), "$root/${Environment.DIRECTORY_MOVIES}/$SUB")
    val volumes = getSystemService(StorageManager::class.java)
    getExternalFilesDirs(Environment.DIRECTORY_MOVIES).drop(1).filterNotNull()
        .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        .forEach { d ->
            val label = runCatching { volumes?.getStorageVolume(d)?.getDescription(this) }.getOrNull() ?: d.path
            list += DownloadFolder("vol:${d.path}", getString(R.string.download_folder_drive, label), d.path)
        }
    return list
}

/** The folder chosen; the first (Zephyr TV's own) when none was, or the drive chosen isn't plugged in. */
internal fun MainActivity.downloadFolder(): DownloadFolder? {
    val all = downloadFolders()
    val key = prefs.getString(K.DOWNLOAD_FOLDER, "app")
    return all.firstOrNull { it.key == key } ?: all.firstOrNull()
}

/** Android 9 and older ask before an app saves into the shared Download and Movies folders. */
private fun MainActivity.needsStorageAccess(key: String) = Build.VERSION.SDK_INT < 29 && (key == "downloads" || key == "movies") &&
    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

/** Settings → Content → Download folder: where the files go, the path of each so they can be found. */
internal fun MainActivity.pickDownloadFolder() {
    val now = downloadFolder()
    val intro = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(8))
        addView(label(getString(R.string.download_folder_intro, now?.path.orEmpty()), 13f).apply {
            maxLines = 6
            setLineSpacing(0f, 1.15f)
        })
    }
    val folders = downloadFolders()
    val rows = folders.map { f ->
        page(f.name, if (f.key == now?.key) MainActivity.CHOSEN else "") {
            fun choose() {
                prefs.edit().putString(K.DOWNLOAD_FOLDER, f.key).apply()
                pickDownloadFolder()
            }
            if (!needsStorageAccess(f.key)) return@page choose()
            afterStorageAccess = { choose() }
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), MainActivity.STORAGE_REQUEST)
        }
    }
    showPage(getString(R.string.setting_download_folder), rows, focus = folders.indexOfFirst { it.key == now?.key }.coerceAtLeast(0),
        top = intro, back = { renderSettings(DOWNLOAD_FOLDER_ROW) })
}

// ---------- the files ----------

/** Where a download is at, as Android's download manager says: one of DownloadManager.STATUS_*. */
private class DownloadState(val status: Int, val done: Long, val total: Long)

private fun MainActivity.downloadState(e: Downloads.Entry): DownloadState? {
    val dm = getSystemService(DownloadManager::class.java) ?: return null
    return runCatching {
        dm.query(DownloadManager.Query().setFilterById(e.dm))?.use { c ->
            if (!c.moveToFirst()) return@use null
            DownloadState(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }.getOrNull()
}

/** The downloads that are complete, by the film's or episode's id: one question to the download manager for them all. */
internal fun MainActivity.localDownloads(): Map<String, Downloads.Entry> {
    val all = Downloads.all(filesDir)
    if (all.isEmpty()) return emptyMap()
    val dm = getSystemService(DownloadManager::class.java) ?: return emptyMap()
    val done = HashSet<Long>()
    runCatching {
        dm.query(DownloadManager.Query().setFilterById(*all.values.map { it.dm }.toLongArray()).setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { c ->
            val i = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) done += c.getLong(i)
        }
    }
    return all.filterValues { it.dm in done }
}

/** The downloaded file of [id] (a film's or an episode's), when it's complete. */
internal fun MainActivity.localDownload(id: String): Downloads.Entry? = localDownloads()[id]

/** Whether the file is still there (not deleted from a file manager): the download manager opens it. */
private fun MainActivity.localFileThere(e: Downloads.Entry) =
    runCatching { getSystemService(DownloadManager::class.java)!!.openDownloadedFile(e.dm).close() }.isSuccess

/** The downloaded file as a stream, first of the title's sources: the player reads it through DownloadFiles. */
internal fun MainActivity.localStream(e: Downloads.Entry) =
    Stremio.Stream(DownloadFiles.uri(this, e.dm, e.file).toString(), e.file, emptyMap(), source = getString(R.string.download_local))

/** "Film (2024)", or "Series S1E2": the download's name in Android's list and its file's. */
private fun MainActivity.downloadName(meta: Stremio.TitleMeta, episode: Stremio.Episode?) = when (episode) {
    null -> listOfNotNull(meta.title.name, meta.title.year?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" ")
    else -> getString(R.string.player_episode, meta.title.name, episode.season, episode.episode)
}

// ---------- the button and its page ----------

/**
 * The Download button of a film's or episode's page, first among the apps; null where there's none (live TV,
 * channels, events, radio; no source to download from and nothing downloaded). [back]: the page, drawn again.
 */
internal fun MainActivity.downloadButton(ref: TitleRef, meta: Stremio.TitleMeta, episode: Stremio.Episode?, back: () -> Unit): BarButton? {
    val t = meta.title
    if (!downloadableType(t.type) || !downloadableType(ref.type)) return null
    val id = episode?.id ?: t.id
    if (streamSources(t.type, id).isEmpty() && Downloads.all(filesDir)[id] == null) return null
    val icon = getDrawable(R.drawable.ic_download) ?: return null
    return BarButton(icon, getString(R.string.download)) { downloadPage(ref, meta, episode, back) }
}

/**
 * A page over a title's page (Download, Share): leaving it by Back draws the title's page again ([back]); leaving it
 * any other way does what leaving the title's page would.
 */
internal fun MainActivity.overTitlePage(title: String, rows: List<Triple<String, String, () -> Unit>>, back: () -> Unit) {
    val savedPageExit = pageExit
    pageExit = null
    showPage(title, rows, back = {
        pageExit = savedPageExit
        back()
    })
    pageExit = savedPageExit
    findViewById<TextView>(R.id.settingsTitle).visibility = View.VISIBLE
    (0 until settingsList.childCount).map { settingsList.getChildAt(it) }.firstOrNull { it.isFocusable }?.let { first ->
        detailDescView?.id?.let { left -> first.nextFocusLeftId = left }
        wireLeftColumn(first.id)
    }
}

/**
 * Download, from its button: nothing downloaded yet, the streams that are files to pick one from; on its way, how far
 * (OK asks again) and Cancel; done, Play and Delete; failed, Try again and Delete.
 */
internal fun MainActivity.downloadPage(ref: TitleRef, meta: Stremio.TitleMeta, episode: Stremio.Episode?, back: () -> Unit) {
    val id = episode?.id ?: meta.title.id
    val name = downloadName(meta, episode)
    val entry = Downloads.all(filesDir)[id]
    val state = entry?.let { downloadState(it) }
    // Removed in Android's own list of downloads: as if never downloaded.
    if (entry != null && state == null) Downloads.remove(filesDir, id)
    fun pick() = pickStream(ref, meta, episode, title = getString(R.string.download_pick_title), accept = ::downloadableStream,
        none = getString(R.string.download_none), picked = { s -> startDownload(id, name, s, back) }, back = back)
    if (entry == null || state == null) return pick()
    val dm = getSystemService(DownloadManager::class.java)
    fun delete() {
        dm?.let { runCatching { it.remove(entry.dm) } } // its file too
        Downloads.remove(filesDir, id)
        back()
    }
    val again = { downloadPage(ref, meta, episode, back) }
    val rows = when (state.status) {
        DownloadManager.STATUS_SUCCESSFUL -> listOf(
            page(getString(R.string.download_play), sizeName(state.total)) {
                if (!localFileThere(entry)) return@page toast(R.string.download_missing)
                pageReturn = again
                playTitle(ref, meta, episode, chosen = localStream(entry))
            },
            page(getString(R.string.download_where), downloadFolder()?.name.orEmpty()) { },
            page(getString(R.string.download_delete)) { delete() },
        )
        DownloadManager.STATUS_FAILED -> listOf(
            page(getString(R.string.download_failed_status)) { },
            page(getString(R.string.download_retry)) {
                dm?.let { runCatching { it.remove(entry.dm) } }
                Downloads.remove(filesDir, id)
                pick()
            },
            page(getString(R.string.download_delete)) { delete() },
        )
        else -> listOf(
            // Waiting (for the network, or its turn) or on its way: OK on it asks again how far it is.
            page(if (state.status == DownloadManager.STATUS_RUNNING) getString(R.string.download_running) else getString(R.string.download_waiting),
                if (state.total > 0) "${state.done * 100 / state.total}% · ${sizeName(state.total)}" else sizeName(state.done)) { again() },
            page(getString(R.string.download_cancel)) { delete() },
        )
    }
    overTitlePage(getString(R.string.download) + " · " + name, rows, back)
}

/**
 * Share, on a phone or tablet, when the title or episode is downloaded: its link (as Share always did, [link]) or its
 * file. Without a file, the link at once.
 */
internal fun MainActivity.shareTitleOrFile(id: String, back: () -> Unit, link: () -> Unit) {
    val e = localDownload(id)?.takeIf { localFileThere(it) } ?: return link()
    overTitlePage(getString(R.string.share), listOf(
        page(getString(R.string.share_link)) { link() },
        page(getString(R.string.share_file), e.file) { shareDownload(e) },
    ), back)
}

/** The downloaded file handed to another app, which may read it (that one file) through DownloadFiles. */
private fun MainActivity.shareDownload(e: Downloads.Entry) {
    val uri = DownloadFiles.uri(this, e.dm, e.file)
    val send = Intent(Intent.ACTION_SEND).setType(DownloadFiles.mimeOf(e.file))
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, e.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(e.name, uri) // the permission goes with the clip
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.share))) }
}

/**
 * The stream picked handed to Android's download manager, with the headers it plays with, into the folder chosen. Its
 * address is asked first: a playlist of pieces (HLS, DASH) behind an address that doesn't say so isn't downloaded.
 */
private fun MainActivity.startDownload(id: String, name: String, s: Stremio.Stream, back: () -> Unit) {
    toast(R.string.download_checking, long = false)
    netIo.execute {
        val format = runCatching { Stremio.probe(s.url, s.headers) }.getOrNull()?.format
        ui.post {
            if (format == "hls" || format == "dash") return@post toast(R.string.download_not_file)
            val dm = getSystemService(DownloadManager::class.java) ?: return@post toast(R.string.download_failed)
            val path = s.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_EXTENSIONS } ?: if (format == "ts" || format == "m2ts") "ts" else "mkv"
            val file = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "video" } + "." + ext
            // Without the access Android 9 asks for (taken back since), Zephyr TV's own folder.
            val folder = downloadFolder()?.takeIf { !needsStorageAccess(it.key) }?.key ?: "app"
            val at = runCatching {
                dm.enqueue(DownloadManager.Request(Uri.parse(s.url)).apply {
                    s.headers.forEach { (k, v) -> runCatching { addRequestHeader(k, v) } }
                    setTitle(name)
                    setDescription(getString(R.string.app_name))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    when {
                        folder == "downloads" || folder == "movies" -> {
                            val type = if (folder == "downloads") Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_MOVIES
                            // Its folder made first where the app may (Android 9 and older); from 10 the download manager does.
                            @Suppress("DEPRECATION")
                            runCatching { File(Environment.getExternalStoragePublicDirectory(type), SUB).mkdirs() }
                            setDestinationInExternalPublicDir(type, "$SUB/$file")
                        }
                        folder.startsWith("vol:") -> setDestinationUri(Uri.fromFile(File(folder.removePrefix("vol:"), file).also { it.parentFile?.mkdirs() }))
                        else -> setDestinationInExternalFilesDir(this@startDownload, Environment.DIRECTORY_MOVIES, file)
                    }
                })
            }.getOrElse {
                warn("download didn't start ($it)")
                return@post toast(R.string.download_failed)
            }
            Downloads.put(filesDir, Downloads.Entry(id, at, name, file))
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_LONG).show()
            back()
        }
    }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "m4v", "mov", "avi", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

/** A size as people read it: 1.4 GB, 700 MB. */
private fun sizeName(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}
