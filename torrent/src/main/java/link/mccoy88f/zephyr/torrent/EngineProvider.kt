package link.mccoy88f.zephyr.torrent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * How Zephyr TV talks to the engine: call() with a method and a Bundle, answered at once (or, for "start", once the
 * torrent's metadata came), and the downloaded files read through openFile. Exported, but only to apps signed with
 * this one's key: Zephyr TV. While Zephyr TV's player holds it (a ContentProviderClient), Android keeps this process
 * as alive as the player.
 */
class EngineProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Engine.use(context ?: return false)
        // The session on the network at once (the DHT takes a while to join), then the downloads left going.
        Engine.warm()
        Thread({ runCatching { Engine.resumeAll() }.onFailure { Engine.log("downloads not resumed: $it") } }, "torrent-resume")
            .apply { isDaemon = true }.start()
        return true
    }

    /**
     * Only Zephyr TV, which is signed with the same key, may ask anything: told by the caller's user id, as some
     * Androids don't pass on the calling package's name.
     */
    private fun check() {
        val c = context ?: throw SecurityException("no context")
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && c.packageManager.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("not signed as Zephyr TV")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check()
        val out = Bundle()
        when (method) {
            "version" -> {
                val info = context!!.packageManager.getPackageInfo(context!!.packageName, 0)
                @Suppress("DEPRECATION") out.putInt("code", info.versionCode)
                out.putString("name", info.versionName)
            }
            "settings" -> {
                Engine.setSettings(extras?.takeIf { it.containsKey("cache_mb") }?.getInt("cache_mb"),
                    extras?.takeIf { it.containsKey("keep_cache") }?.getBoolean("keep_cache"),
                    extras?.takeIf { it.containsKey("seed") }?.getBoolean("seed"),
                    extras?.takeIf { it.containsKey("upload_kb") }?.getInt("upload_kb"))
                out.putInt("cache_mb", Engine.cacheLimitMb())
                out.putBoolean("keep_cache", Engine.keepCache())
                out.putBoolean("seed", Engine.seeding())
                out.putInt("upload_kb", Engine.uploadKb())
            }
            "start" -> {
                val link = extras?.getString("link") ?: arg ?: return out.apply { putString("error", "no link") }
                Engine.start(link).onSuccess { p ->
                    out.putString("url", Engine.url(p))
                    out.putString("token", p.token)
                    out.putString("name", p.name)
                    out.putLong("size", p.size)
                }.onFailure { out.putString("error", it.message ?: it.toString()) }
            }
            "status" -> {
                val p = Engine.current(arg)
                if (p == null) out.putString("state", "none") else {
                    p.asked = System.currentTimeMillis()
                    val (peers, seeds, down, up) = Engine.peers(p).toList()
                    out.putString("state", if (p.size > 0) "ready" else "metadata")
                    out.putInt("peers", peers)
                    out.putInt("seeds", seeds)
                    out.putInt("down", down)
                    out.putInt("up", up)
                    if (p.size > 0) {
                        out.putLong("size", p.size)
                        out.putLong("done", p.done())
                        out.putLong("ahead", p.ahead())
                    }
                    // What stops it, when something does: the drive full, or a file libtorrent can't write.
                    Engine.problem(p)?.let { out.putString("problem", it) }
                }
            }
            "stop" -> Engine.stopPlay(arg)
            // The streams' cache: what it takes and what's free on its drive; "clear_cache" empties it first.
            "cache", "clear_cache" -> {
                if (method == "clear_cache") Engine.clearCache()
                val (used, free) = Engine.cacheUse()
                out.putLong("used", used)
                out.putLong("free", free)
            }
            // Whether the engine is on the network: the DHT nodes it knows (starting its session, the first time).
            "health" -> out.putLong("nodes", Engine.dhtNodes())
            "download" -> {
                val e = extras ?: return out
                out.putBoolean("ok", Engine.download(e.getString("key") ?: return out, e.getString("link") ?: return out,
                    e.getString("name").orEmpty(), e.getString("folder") ?: "app"))
            }
            "downloads" -> {
                runCatching { Engine.tick() } // finished ones seen at once, also when the download service couldn't start
                val all = Engine.downloads()
                out.putStringArray("keys", all.map { it.key }.toTypedArray())
                for (d in all) out.putBundle(d.key, Bundle().apply {
                    putString("state", when {
                        d.done -> "done"
                        d.failed -> "failed"
                        d.total > 0 -> "going"
                        else -> "waiting" // its metadata still to come
                    })
                    putLong("done", Engine.progress(d))
                    putLong("total", d.total)
                    putString("file", d.path?.substringAfterLast('/'))
                    putString("path", d.path)
                    putInt("rate", Engine.rate(d))
                })
            }
            "cancel" -> Engine.cancel(arg ?: return out)
            else -> throw IllegalArgumentException("unknown method $method")
        }
        return out
    }

    /** content://<engine>.engine/file/<key>/<name>: download [key]'s file, read-only. */
    private fun file(uri: Uri) = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "file" }?.get(1)?.let { Engine.downloadFile(it) }
        ?: throw FileNotFoundException("no such download")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check()
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = Engine.mimeOf(uri.lastPathSegment.orEmpty())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check()
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
