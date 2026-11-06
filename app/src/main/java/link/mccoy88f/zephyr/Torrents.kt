package link.mccoy88f.zephyr

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

// Torrents: the add-ons' torrent streams (an info hash, not an address), played and downloaded by Zephyr Torrent, an
// APK of its own installed from Settings → Content → Torrent engine (see TorrentSettings). A torrent stream is kept as
// a magnet link in Stream.url (with so=, BEP 53, for its file), so it goes everywhere a stream goes as it is. Zephyr TV
// talks to the engine through its ContentProvider, which answers only apps signed with the same key. The player gets
// an http address on 127.0.0.1 from it, and reads the film as from any server.

internal object Torrents {
    /** The engine's package: the debug build's with Zephyr TV's debug build, so each talks to its own. */
    fun enginePackage(c: Context) = if (c.packageName.endsWith(".debug")) "$ENGINE.debug" else ENGINE
    private const val ENGINE = "link.mccoy88f.zephyr.torrent"

    private fun authority(c: Context) = enginePackage(c) + ".engine"

    /**
     * Whether torrent streams are offered, in this process: the engine installed (and not turned off). Read again by
     * [refresh] when the home screen comes back and when the player starts.
     */
    @Volatile var available = false
        private set

    fun refresh(c: Context): Boolean {
        app = c.applicationContext
        available = installed(c) != null
        if (available) warm(c) else drop(held)
        return available
    }

    /** This process's context, for [warm] from [offered]. */
    @Volatile private var app: Context? = null

    /** Why the engine didn't answer the last call ("not signed as Zephyr TV", "it stopped"), or null when it did. */
    @Volatile var lastError: String? = null
        private set

    /**
     * The engine's provider, held by this process while it runs: the engine's process then lives as long as Zephyr
     * TV's (Android ranks it as Zephyr TV), with its session on the network and ready to play. Unstable: an engine
     * that crashes doesn't take Zephyr TV with it.
     */
    @Volatile private var held: ContentProviderClient? = null
    private val warming = java.util.concurrent.atomic.AtomicBoolean()

    /** When the engine was last started by its activity (see [wake]): once every half a minute at most. */
    @Volatile private var woken = 0L

    /**
     * The engine started, off the main thread: its process, and its session joining the network (the DHT), so a
     * torrent finds its peers sooner. On opening Zephyr TV (see [refresh]) and when the streams found have torrents.
     */
    fun warm(c: Context? = app) {
        val ctx = c ?: return
        if (!available || !warming.compareAndSet(false, true)) return
        Thread({ try { call(ctx, "health") } finally { warming.set(false) } }, "torrent-warm").apply { isDaemon = true }.start()
    }

    /** The engine's provider, held (see [held]); null when Android doesn't let it start (or it isn't installed). */
    private fun client(c: Context): ContentProviderClient? = synchronized(this) {
        held ?: try {
            c.contentResolver.acquireUnstableContentProviderClient(authority(c))?.also { held = it }
                ?: run { lastError = "not reachable"; null }
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            null
        }
    }

    private fun drop(client: ContentProviderClient?) = synchronized(this) {
        if (client == null) return@synchronized
        if (held === client) held = null
        runCatching { client.close() }
    }

    /**
     * The engine's process started by its own activity, which shows nothing and closes at once: for the TVs that don't
     * let an app start another's process for its provider (some makers' "autostart" rules, an engine never opened).
     * Waits for it a few seconds, off the main thread. Whether it was asked to start.
     */
    private fun wake(c: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (woken != 0L && now - woken < 30_000) return false
        val i = android.content.Intent().setClassName(enginePackage(c), "link.mccoy88f.zephyr.torrent.StartActivity")
        if (c.packageManager.resolveActivity(i, 0) == null) return false // an engine older than its activity
        woken = now
        if (c !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { c.startActivity(i) }.onFailure { warn("torrent engine: not started ($it)") }.isSuccess
        if (ok && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) Thread.sleep(2500)
        return ok
    }

    /** The installed engine's version (code and name), or null when it isn't installed. */
    fun installed(c: Context): Pair<Int, String>? = runCatching {
        val info = c.packageManager.getPackageInfo(enginePackage(c), 0)
        @Suppress("DEPRECATION") info.versionCode to info.versionName.orEmpty()
    }.getOrNull()

    /** The engine version this Zephyr TV was built with, which its own release carries (see TorrentSettings). */
    fun bundledVersion(c: Context): Int = c.resources.getInteger(R.integer.torrent_version_code)

    /** Whether a stream is a torrent's. */
    fun isTorrent(url: String) = url.startsWith("magnet:", ignoreCase = true)

    /** [list] as it may be offered: the torrents left out while there is no engine. */
    fun offered(list: List<Stremio.Stream>) = if (available) list.also { l -> if (l.any { isTorrent(it.url) }) warm() }
        else list.filterNot { isTorrent(it.url) }

    /**
     * A stream's magnet link from an add-on's stream object: its "url" when that is one already, else its infoHash with
     * the trackers its "sources" name ("tracker:udp://…"; "dht:" ones need nothing, the engine asks the DHT anyway),
     * its file ("fileIdx") and the file's name (behaviorHints.filename) as its display name.
     */
    fun magnet(s: JSONObject): String? {
        s.optString("url").takeIf { !s.isNull("url") && isTorrent(it) }?.let { return it }
        val hash = s.optString("infoHash").takeIf { !s.isNull("infoHash") && Regex("[0-9a-fA-F]{40}").matches(it) }?.lowercase() ?: return null
        val sources = s.optJSONArray("sources")
        val trackers = (0 until (sources?.length() ?: 0)).mapNotNull { i ->
            sources!!.optString(i).takeIf { it.startsWith("tracker:") }?.removePrefix("tracker:")?.takeIf { it.isNotBlank() }
        }.distinct()
        val file = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx", -1).takeIf { it >= 0 } else null
        val name = s.optJSONObject("behaviorHints")?.let { h -> h.optString("filename").takeIf { !h.isNull("filename") && it.isNotBlank() } }
        val seeders = seeders(listOf("name", "title", "description").joinToString("\n") { k -> s.optString(k).takeIf { !s.isNull(k) }.orEmpty() })
        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            name?.let { append("&dn=").append(Stremio.enc(it)) }
            trackers.forEach { append("&tr=").append(Stremio.enc(it)) }
            file?.let { append("&so=").append(it) }
            // How many seeders the add-on says it has: Zephyr TV's own (zs=), which the engine leaves aside.
            seeders?.let { append("&zs=").append(it) }
        }
    }

    /** The seeders a stream's text says it has: "👤 42", or "Seeders: 42". */
    internal fun seeders(text: String): Int? =
        (Regex("\uD83D\uDC64\\s*(\\d+)").find(text) ?: Regex("(?i)\\bseed(?:er)?s?\\s*[:=]?\\s*(\\d+)").find(text))
            ?.groupValues?.get(1)?.toIntOrNull()

    /** Torrents with more seeders first (they start sooner and don't stall); other streams, and torrents saying none, as they are. */
    val bySeeders: Comparator<Stremio.Stream> = compareByDescending { seedersOf(it.url) ?: -1 }

    /** The seeders kept in a torrent stream's magnet link (see magnet), or null. */
    fun seedersOf(url: String): Int? = if (!isTorrent(url)) null else
        url.substringAfter('?').split('&').firstOrNull { it.startsWith("zs=") }?.removePrefix("zs=")?.toIntOrNull()

    /**
     * A call to the engine, through the provider this process holds; null when it isn't installed or doesn't answer
     * (or answers someone else: a SecurityException), with why in [lastError]. An engine that stopped since (killed,
     * crashed) is started again and asked again, once.
     */
    fun call(c: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? {
        for (attempt in 0..1) {
            val client = client(c)
            if (client == null) {
                // Not from the player (its status, each second): the engine's activity would pause what plays.
                if (attempt == 0 && c !is PlayerActivity && wake(c)) continue
                warn("torrent engine: $method failed (${lastError})")
                return null
            }
            try {
                return client.call(method, arg, extras).also { lastError = null }
            } catch (e: android.os.DeadObjectException) {
                drop(client) // the engine stopped: started again by the next try
                lastError = "stopped"
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                warn("torrent engine: $method failed ($e)")
                if (e is SecurityException || attempt == 1) return null
                drop(client)
            }
        }
        warn("torrent engine: $method failed (${lastError})")
        return null
    }

    /** The engine's settings as it keeps them, changed by [extras] first when there are any. */
    fun settings(c: Context, extras: Bundle? = null) = call(c, "settings", extras = extras)

    /** A download handed to the engine: [key] (the film's or episode's id), into Zephyr TV's folder [folder]. */
    fun download(c: Context, key: String, link: String, name: String, folder: String): Boolean {
        val ok = call(c, "download", extras = Bundle().apply {
            putString("key", key)
            putString("link", link)
            putString("name", name)
            putString("folder", folder)
        })?.getBoolean("ok") == true
        if (ok) keepDownloading(c)
        return ok
    }

    /**
     * The engine's download service started, from here: an app on screen may start another's service in the
     * foreground, the engine in the background may not (Android 12 and later).
     */
    fun keepDownloading(c: Context) = runCatching {
        val i = android.content.Intent().setClassName(enginePackage(c), "link.mccoy88f.zephyr.torrent.DownloadService")
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }.onFailure { warn("torrent engine: download service not started ($it)") }

    /** Where a download in the engine is at. */
    class Progress(val state: String, val done: Long, val total: Long, val file: String?, val rate: Int)

    /** Every download the engine has, by its key; null when the engine can't be asked. */
    fun downloads(c: Context): Map<String, Progress>? {
        val b = call(c, "downloads") ?: return null
        return b.getStringArray("keys").orEmpty().associateWith { k ->
            val d = b.getBundle(k) ?: Bundle()
            Progress(d.getString("state") ?: "waiting", d.getLong("done"), d.getLong("total"), d.getString("file"), d.getInt("rate"))
        }
    }

    fun cancel(c: Context, key: String) = call(c, "cancel", key)

    /** A downloaded file in the engine, read through its provider: content://<engine>.engine/file/<key>/<name>. */
    fun fileUri(c: Context, key: String, file: String): Uri = Uri.Builder().scheme("content").authority(authority(c))
        .appendPath("file").appendPath(key).appendPath(file).build()

    /**
     * One stream played through the engine, from the player's process. [client] holds the engine's provider while it
     * plays, which keeps the engine's process alive as long as the player's.
     */
    class Session(val token: String, val url: String, val name: String, val size: Long, private val client: ContentProviderClient?) {
        fun status(): Bundle? = runCatching { client?.call("status", token, null) }.getOrNull()
        fun stop() {
            runCatching { client?.call("stop", token, null) }
            runCatching { client?.close() }
        }
    }

    /**
     * Starts [link] in the engine and waits for its metadata (up to a minute); null when it isn't installed or the
     * torrent can't be had (no one has it), with what the engine said in [why]. Off the main thread.
     */
    fun start(c: Context, link: String, why: (String) -> Unit = {}): Session? {
        fun acquire() = runCatching { c.contentResolver.acquireUnstableContentProviderClient(authority(c)) }.getOrNull()
        val client = acquire() ?: (if (wake(c)) acquire() else null) ?: run {
            why("not reachable")
            return null
        }
        val b = runCatching { client.call("start", null, Bundle().apply { putString("link", link) }) }
            .onFailure { warn("torrent engine: start failed ($it)") }.getOrNull()
        val url = b?.getString("url")
        if (b == null || url == null) {
            b?.getString("error")?.let(why)
            runCatching { client.close() }
            return null
        }
        return Session(b.getString("token").orEmpty(), url, b.getString("name").orEmpty(), b.getLong("size"), client)
    }

    /** "12 peers · 2.4 MB/s" (and, playing, how much is ready ahead), from the engine's status. */
    fun statusLine(c: Context, b: Bundle): String {
        val speed = speedName(b.getInt("down").toLong())
        val line = c.getString(R.string.torrent_status, b.getInt("peers"), speed)
        val ahead = b.getLong("ahead")
        return if (ahead > 0) line + " · " + c.getString(R.string.torrent_ahead, sizeName(ahead).ifEmpty { "${ahead shr 10} KB" }) else line
    }

    /** A speed as people read it: 2.4 MB/s, 300 KB/s. */
    fun speedName(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB/s", bytes / (1L shl 20).toDouble())
        else -> "${bytes shr 10} KB/s"
    }
}
