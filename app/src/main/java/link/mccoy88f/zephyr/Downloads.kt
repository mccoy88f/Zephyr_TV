package link.mccoy88f.zephyr

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Download: a film or an episode saved on the TV, from the Download button among the apps on its page (see serviceBar).
// Android's download manager does the work, in the background and through restarts; the file goes in the app's own
// Movies folder, and plays in Zephyr TV's player from the same button. Not for live TV, channels, events or radio:
// a live stream has no end to save. Functions of MainActivity, kept here by topic.

/** The films and episodes downloaded, by their id (the film's, or the episode's), in downloads.json. */
internal object Downloads {
    internal class Entry(val id: String, val dm: Long, val name: String)

    private fun file(dir: File) = File(dir, "downloads.json")

    @Synchronized
    fun all(dir: File): Map<String, Entry> = runCatching {
        val a = JSONArray(file(dir).readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Entry(it.getString("id"), it.getLong("dm"), it.optString("name")) }
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun put(dir: File, e: Entry) = write(dir, all(dir) + (e.id to e))

    @Synchronized
    fun remove(dir: File, id: String) = write(dir, all(dir) - id)

    private fun write(dir: File, entries: Map<String, Entry>) {
        val a = JSONArray()
        entries.values.forEach { a.put(JSONObject().put("id", it.id).put("dm", it.dm).put("name", it.name)) }
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

/** Where a download is at, as Android's download manager says: one of DownloadManager.STATUS_*, and its file. */
private class DownloadState(val status: Int, val done: Long, val total: Long, val file: String?)

private fun MainActivity.downloadState(e: Downloads.Entry): DownloadState? {
    val dm = getSystemService(DownloadManager::class.java) ?: return null
    return runCatching {
        dm.query(DownloadManager.Query().setFilterById(e.dm))?.use { c ->
            if (!c.moveToFirst()) return@use null
            DownloadState(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
            )
        }
    }.getOrNull()
}

/** "Film (2024)", or "Series S1E2": the download's name in Android's list and its file's. */
private fun MainActivity.downloadName(meta: Stremio.TitleMeta, episode: Stremio.Episode?) = when (episode) {
    null -> listOfNotNull(meta.title.name, meta.title.year?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" ")
    else -> getString(R.string.player_episode, meta.title.name, episode.season, episode.episode)
}

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
 * Download, from its button: nothing downloaded yet, the streams that are files to pick one from; on its way, how far
 * (OK asks again) and Cancel; done, Play and Delete; failed, Try again and Delete.
 */
internal fun MainActivity.downloadPage(ref: TitleRef, meta: Stremio.TitleMeta, episode: Stremio.Episode?, back: () -> Unit) {
    val id = episode?.id ?: meta.title.id
    val name = downloadName(meta, episode)
    val entry = Downloads.all(filesDir)[id]
    val state = entry?.let { downloadState(it) }
    // Removed in Android's own list of downloads (or its file gone): as if never downloaded.
    if (entry != null && state == null) Downloads.remove(filesDir, id)
    fun pick() = pickStream(ref, meta, episode, back, title = getString(R.string.download_pick_title),
        accept = ::downloadableStream, none = getString(R.string.download_none)) { s -> startDownload(id, name, s, back) }
    if (entry == null || state == null) return pick()
    fun delete() {
        getSystemService(DownloadManager::class.java)?.let { dm -> runCatching { dm.remove(entry.dm) } } // its file too
        Downloads.remove(filesDir, id)
        back()
    }
    val again = { downloadPage(ref, meta, episode, back) }
    val rows = when (state.status) {
        DownloadManager.STATUS_SUCCESSFUL -> listOf(
            page(getString(R.string.download_play), sizeName(state.total)) {
                val uri = state.file ?: return@page toast(R.string.download_missing)
                if (Uri.parse(uri).path?.let { File(it).exists() } != true) return@page toast(R.string.download_missing)
                pageReturn = again
                playTitle(ref, meta, episode, chosen = Stremio.Stream(uri, name, emptyMap(), source = getString(R.string.download)))
            },
            page(getString(R.string.download_delete)) { delete() },
        )
        DownloadManager.STATUS_FAILED -> listOf(
            page(getString(R.string.download_failed_status)) { },
            page(getString(R.string.download_retry)) {
                getSystemService(DownloadManager::class.java)?.let { dm -> runCatching { dm.remove(entry.dm) } }
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
    val title = getString(R.string.download) + " · " + name
    val savedPageExit = pageExit
    pageExit = null
    showPage(title, rows, back = {
        pageExit = savedPageExit
        back()
    })
    pageExit = savedPageExit // leaving it for anything else restores the title's page as leaving that would
    findViewById<TextView>(R.id.settingsTitle).visibility = View.VISIBLE
    (0 until settingsList.childCount).map { settingsList.getChildAt(it) }.firstOrNull { it.isFocusable }?.let { first ->
        detailDescView?.id?.let { left -> first.nextFocusLeftId = left }
        wireLeftColumn(first.id)
    }
}

/**
 * The stream picked handed to Android's download manager, with the headers it plays with, into the app's own Movies
 * folder. Its address is asked first: a playlist of pieces (HLS, DASH) behind an address that doesn't say so isn't
 * downloaded.
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
            val at = runCatching {
                dm.enqueue(DownloadManager.Request(Uri.parse(s.url)).apply {
                    s.headers.forEach { (k, v) -> runCatching { addRequestHeader(k, v) } }
                    setTitle(name)
                    setDescription(getString(R.string.app_name))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalFilesDir(this@startDownload, Environment.DIRECTORY_MOVIES, file)
                })
            }.getOrElse {
                warn("download didn't start ($it)")
                return@post toast(R.string.download_failed)
            }
            Downloads.put(filesDir, Downloads.Entry(id, at, name))
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
