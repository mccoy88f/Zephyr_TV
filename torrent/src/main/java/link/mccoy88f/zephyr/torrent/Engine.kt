package link.mccoy88f.zephyr.torrent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.UUID

/**
 * libtorrent's session, once per process, and what it does for Zephyr TV: one stream at a time ([Play], served by
 * [StreamServer]) and whole downloads ([Download], kept in downloads.json and resumed when the engine starts again).
 * Zephyr TV asks for both through [EngineProvider].
 */
internal object Engine {
    private const val TAG = "ZephyrTorrent"
    private const val METADATA_MS = 60_000L // a torrent whose metadata doesn't come in this long: no one has it
    private const val STALL_MS = 90_000L // a read that waits this long for its piece gives up (the player then moves on)
    private const val IDLE_MS = 120_000L // a stream no one asked about for this long is stopped (its player went away)
    private const val AHEAD_BYTES = 48L shl 20 // asked for first, ahead of where the player reads
    private const val END_BYTES = 4L shl 20 // the file's end, asked for at once: an MP4's index, an MKV's cues

    private lateinit var app: Context
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("engine", Context.MODE_PRIVATE) }
    private val session: SessionManager by lazy { start() }
    private val server: StreamServer by lazy { StreamServer { token -> play?.takeIf { it.token == token } } }

    @Volatile private var play: Play? = null
    private val downloads = LinkedHashMap<String, Download>()

    fun use(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        // Whatever the last run left in the streams' cache (killed while playing) goes, unless the user keeps it.
        if (!keepCache()) streamsDir().deleteRecursively()
        loadDownloads()
        // A stream whose player went away without stopping it: let go of after a while (see checkIdle).
        Thread({ while (true) { Thread.sleep(30_000); runCatching { checkIdle() } } }, "torrent-idle").apply { isDaemon = true }.start()
    }

    // ---------- settings ----------

    // 4 GB: a 1080p film fits whole, so it downloads on from where it plays instead of only around it.
    fun cacheLimitMb() = prefs.getInt("cache_mb", 4096)
    fun keepCache() = prefs.getBoolean("keep_cache", false)
    // Never shared on: the TV sends only while it downloads, and then little (see uploadLimit). The old setting stays unread.
    fun seeding() = false

    fun setSettings(cacheMb: Int?, keep: Boolean?, seed: Boolean?) {
        prefs.edit().apply {
            cacheMb?.let { putInt("cache_mb", it.coerceIn(256, 64 * 1024)) }
            keep?.let { putBoolean("keep_cache", it) }
            seed?.let { putBoolean("seed", it) }
        }.commit() // on disk at once: a process killed right after keeps them
        if (seed == false) synchronized(downloads) { downloads.values.filter { it.done }.forEach { d -> d.handle?.let { remove(it, false) }; d.handle = null } }
        if (seed != null) applyUpload()
    }

    /**
     * How much the TV sends: only while it downloads (a stream playing, a download going), 10 KB/s, as BitTorrent's
     * peers send more to those who send them something. Otherwise 1 KB/s (libtorrent's 0 is no limit), with nothing
     * to send anyway: a stream's torrent goes when it stops, a finished download's too (no seeding).
     */
    private fun uploadLimit() = if (play != null || downloading()) 10 * 1024 else 1024

    @Volatile private var upload = -1

    private fun applyUpload() {
        val limit = uploadLimit()
        if (limit == upload) return
        runCatching { session.applySettings(SettingsPack().uploadRateLimit(limit)); upload = limit }.onFailure { log("upload limit not set: $it") }
    }

    private fun start(): SessionManager {
        val sp = SettingsPack.defaultSettings()
        // A TV's few hundred megabytes: fewer peers and a smaller queue than a computer's client keeps.
        sp.connectionsLimit(120)
        sp.activeDownloads(4)
        sp.activeSeeds(if (seeding()) 4 else 0)
        sp.uploadRateLimit(1024) // see uploadLimit: set as the first stream or download starts
        sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        // A stream downloads only around where it plays (a film larger than the cache): each time those pieces are all
        // here, libtorrent takes the torrent as finished, and by default then drops every seed it was connected to.
        // The next pieces asked for then waited for new peers, minutes: the speed went to nothing and the player gave up.
        sp.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        val s = SessionManager(false)
        // On a 32-bit TV, files read and written as files: mapping a film of several gigabytes into memory, as
        // libtorrent does by default, can't fit in its address space.
        val params = SessionParams(sp)
        if (!android.os.Process.is64Bit()) params.setPosixDiskIO()
        s.start(params)
        return s
    }

    /** The DHT nodes the session knows: none while it's still joining the network (or can't reach it). */
    fun dhtNodes(): Long = runCatching { session.dhtNodes() }.onFailure { log("session not started: $it") }.getOrDefault(0L)

    /** The session started in the background, if it isn't yet: it joins the network before a torrent is asked for. */
    fun warm() {
        Thread({ dhtNodes() }, "torrent-warm").apply { isDaemon = true }.start()
    }

    private fun streamsDir() = File(app.cacheDir, "streams")

    /** The torrent of [m], added if it isn't yet, its files saved under [dir]. */
    private fun handle(m: Magnet, link: String, dir: File): TorrentHandle? {
        val hash = Sha1Hash.parseHex(m.hash)
        session.find(hash)?.takeIf { it.isValid }?.let { return it }
        dir.mkdirs()
        session.download(link, dir, torrent_flags_t())
        return session.find(hash)?.takeIf { it.isValid }
    }

    /** Its metadata, waited for (from the peers, for a magnet link); null when none came in time or [stop] says so. */
    private fun metadata(h: TorrentHandle, stop: () -> Boolean = { false }): TorrentInfo? {
        val until = System.currentTimeMillis() + METADATA_MS
        while (System.currentTimeMillis() < until && !stop()) {
            if (h.isValid && h.status().hasMetadata()) return h.torrentFile()
            Thread.sleep(200)
        }
        return null
    }

    /** The file to take: [wanted] when there is one at that index, else the largest (the film, not its sample). */
    private fun pick(ti: TorrentInfo, wanted: Int?): Int {
        val fs = ti.files()
        if (wanted != null && wanted in 0 until fs.numFiles() && !fs.padFileAt(wanted)) return wanted
        return (0 until fs.numFiles()).filter { !fs.padFileAt(it) }.maxByOrNull { fs.fileSize(it) } ?: 0
    }

    private fun remove(h: TorrentHandle, files: Boolean) = runCatching {
        if (files) session.remove(h, SessionHandle.DELETE_FILES) else session.remove(h)
    }

    // ---------- streaming ----------

    /**
     * The stream of [link]'s file, started (the one before stopped): its address, file name and size; null with the
     * reason when its metadata doesn't come. Blocks while the peers are found: off the main thread.
     */
    fun start(link: String): Result<Play> {
        val m = Magnet.parse(link) ?: return Result.failure(IllegalArgumentException("not a magnet link"))
        stopPlay()
        // Downloading already (Download): its file, as far as it got, and the download goes on after. One just asked
        // for (a stream kept as a download) is waited for a moment, till it has its torrent: the stream mustn't own it.
        fun kept() = synchronized(downloads) { downloads.values.firstOrNull { it.hash == m.hash && !it.failed && (!it.done || it.handle != null) } }
        var download = kept()
        val until = System.currentTimeMillis() + 10_000
        while (download != null && download.handle?.isValid != true && System.currentTimeMillis() < until) {
            Thread.sleep(100)
            download = kept()
        }
        download = download?.takeIf { it.handle?.isValid == true }
        val dir = File(streamsDir(), m.hash)
        val h = download?.handle ?: handle(m, link, dir) ?: return Result.failure(IllegalStateException("not added"))
        // Out of the session's queue: a stream "finished" (see start) isn't paused as a seed when seeding is off.
        if (download == null) runCatching {
            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
            h.resume()
        }.onFailure { log("stream left in the queue: $it") }
        // Not the stream's to remove when a download has the same torrent (its handle not there yet, even).
        val p = Play(UUID.randomUUID().toString().replace("-", ""), m.hash, h, owned = download == null && kept() == null, dir = dir)
        play = p
        applyUpload()
        val ti = metadata(h) { play !== p } ?: run {
            if (play === p) stopPlay()
            return Result.failure(IllegalStateException("no metadata"))
        }
        if (play !== p) return Result.failure(IllegalStateException("stopped"))
        p.ready(ti, download?.index?.takeIf { it >= 0 } ?: pick(ti, m.file))
        return Result.success(p)
    }

    fun stopPlay(token: String? = null) {
        val p = play ?: return
        if (token != null && p.token != token) return
        play = null
        p.stopped = true
        applyUpload()
        if (p.owned) {
            remove(p.handle, files = !keepCache())
            if (keepCache()) trimCache()
            // Its folder too, once libtorrent has let go of its files (it deletes them in the background).
            else Thread({ Thread.sleep(3000); if (play?.hash != p.hash) p.dir.deleteRecursively() }, "torrent-clean").apply { isDaemon = true }.start()
        } else runCatching { p.handle.clearPieceDeadlines() }
    }

    fun current(token: String?): Play? = play?.takeIf { token == null || it.token == token }

    fun url(p: Play) = "http://127.0.0.1:${server.port}/${p.token}/${Uri.encode(p.name)}"

    /** The streams' cache kept (Keep the cache on) down to its limit: the oldest torrents' folders go first. */
    private fun trimCache() {
        val limit = cacheLimitMb().toLong() shl 20
        val dirs = streamsDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { size(it) }
        for (d in dirs) {
            if (total <= limit) break
            if (d.name == play?.hash) continue
            total -= size(d)
            d.deleteRecursively()
        }
    }

    /** The space [f] takes on the drive: its blocks, not its length (libtorrent's files are sparse while they fill). */
    private fun size(f: File): Long = if (f.isFile) runCatching { android.system.Os.stat(f.path).st_blocks * 512 }.getOrDefault(f.length())
        else f.listFiles()?.sumOf { size(it) } ?: 0L

    /** Free space below this, while a stream plays: other torrents' cache goes, and the player is told. */
    private const val RESERVE = 300L shl 20

    /** The streams' cache: what it takes on the drive, and what's free there. */
    fun cacheUse(): Pair<Long, Long> {
        val dir = streamsDir().apply { mkdirs() }
        return size(dir) to dir.usableSpace
    }

    /** The streams' cache emptied, but for the stream playing. What it takes after. */
    fun clearCache(): Long {
        streamsDir().listFiles()?.forEach { d -> if (d.name != play?.hash) d.deleteRecursively() }
        return cacheUse().first
    }

    /**
     * What stops [p] from downloading, or null: the drive full ("no_space"), or libtorrent's error on the torrent
     * (a file it can't write). The drive full: the other torrents' cache goes first, and the torrent goes on.
     */
    fun problem(p: Play): String? {
        val free = runCatching { p.dir.usableSpace.takeIf { it > 0 } ?: streamsDir().usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < RESERVE) {
            streamsDir().listFiles()?.forEach { d -> if (d.name != p.hash) d.deleteRecursively() }
            if (streamsDir().usableSpace < RESERVE) return "no_space"
        }
        val error = runCatching { p.handle.status().errorCode().takeIf { it.isError }?.message }.getOrNull() ?: return null
        // Once there's room again, the torrent may go on: its error cleared, started again.
        if (free >= RESERVE) runCatching { p.handle.swig().clear_error(); p.handle.resume() }
        return error
    }

    /** Stops a stream whose player went away without saying so (killed, or crashed): no status asked for too long. */
    fun checkIdle() {
        val p = play ?: return
        if (System.currentTimeMillis() - p.asked > IDLE_MS && !p.serving()) stopPlay()
    }

    /**
     * One stream: the torrent's file [index] read by the player. Pieces from where it reads up to AHEAD_BYTES on are
     * asked for first, each by a deadline; when the file is larger than the cache's limit, only those are downloaded,
     * so the cache holds what was played and what's just ahead, never the whole film unasked.
     */
    class Play(val token: String, val hash: String, val handle: TorrentHandle, val owned: Boolean, val dir: File) {
        @Volatile var stopped = false
        @Volatile var asked = System.currentTimeMillis()
        @Volatile private var reader = 0L
        private val readers = java.util.concurrent.atomic.AtomicInteger()
        private var ti: TorrentInfo? = null
        var index = 0; private set
        var name = ""; private set
        var size = 0L; private set
        var mime = "video/*"; private set
        private var offset = 0L
        private var pieceLen = 1
        private var firstPiece = 0
        private var lastPiece = 0
        private var window = -1 // the piece the deadlines were last set from
        private var whole = true // the whole file downloaded (it fits in the cache), or only around where it plays

        fun ready(info: TorrentInfo, idx: Int) {
            ti = info
            index = idx
            val fs = info.files()
            name = fs.fileName(idx)
            size = fs.fileSize(idx)
            offset = fs.fileOffset(idx)
            pieceLen = info.pieceLength()
            firstPiece = (offset / pieceLen).toInt()
            lastPiece = ((offset + size - 1).coerceAtLeast(offset) / pieceLen).toInt()
            mime = mimeOf(name)
            // Whole when it fits the cache and the drive (a margin left): else only around where it plays.
            val free = runCatching { dir.usableSpace }.getOrDefault(0L)
            whole = size <= (cacheLimitMb().toLong() shl 20) && (free == 0L || size + RESERVE < free)
            if (owned) {
                handle.prioritizeFiles(Array(fs.numFiles()) { if (it == idx) (if (whole) Priority.DEFAULT else Priority.IGNORE) else Priority.IGNORE })
            }
            // Its start and its end at once: the player reads both before the first picture.
            deadlines(firstPiece)
            val endFrom = ((offset + size - END_BYTES).coerceAtLeast(offset) / pieceLen).toInt()
            for (i in endFrom..lastPiece) want(i, 0)
        }

        fun file(): File = File(handle.savePath(), ti!!.files().filePath(index))

        private fun pieceOf(pos: Long) = ((offset + pos) / pieceLen).toInt().coerceIn(firstPiece, lastPiece)

        /** Where the piece holding [pos] ends, in the file's bytes. */
        fun pieceEnd(pos: Long): Long = minOf(size, (pieceOf(pos) + 1).toLong() * pieceLen - offset)

        fun reading(pos: Long) {
            reader = pos
        }

        fun serving() = readers.get() > 0

        private fun want(i: Int, ms: Int) {
            if (handle.havePiece(i)) return
            if (!whole || !owned) handle.piecePriority(i, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(i, ms)
        }

        /** The deadlines from piece [from] on: the next ones soonest. A jump elsewhere (a seek) clears the old ones. */
        @Synchronized private fun deadlines(from: Int) {
            if (from == window) return
            val ahead = (AHEAD_BYTES / pieceLen).toInt().coerceIn(4, 256)
            if (window < 0 || from < window || from > window + ahead) runCatching { handle.clearPieceDeadlines() }
            window = from
            for (i in from..minOf(lastPiece, from + ahead)) want(i, (i - from) * 150)
        }

        /** Waits for the piece holding [pos]; false when the stream stopped, or nothing came for too long. */
        fun waitFor(pos: Long): Boolean {
            val p = pieceOf(pos)
            readers.incrementAndGet()
            try {
                deadlines(p)
                if (handle.havePiece(p)) return true
                val until = System.currentTimeMillis() + STALL_MS
                while (!stopped && System.currentTimeMillis() < until) {
                    Thread.sleep(40)
                    if (handle.havePiece(p)) return true
                }
                return false
            } finally {
                readers.decrementAndGet()
            }
        }

        /** The bytes there from where the player reads on, without a gap: what it can play without waiting. */
        fun ahead(): Long {
            val from = pieceOf(reader)
            var i = from
            while (i <= lastPiece && i - from < 4096 && handle.havePiece(i)) i++
            return ((i.toLong() * pieceLen - offset).coerceAtMost(size) - reader).coerceAtLeast(0)
        }

        /** The file's bytes downloaded so far. */
        fun done(): Long = runCatching { handle.fileProgress()[index] }.getOrDefault(0L)
    }

    // ---------- downloads ----------

    /**
     * A whole download into one of Zephyr TV's download folders ([folder], as Zephyr TV keeps it: "app",
     * "downloads", "movies" or "vol:<its path>"), named [name] plus the file's extension. [key]: Zephyr TV's id for it
     * (the film's or episode's).
     */
    class Download(val key: String, val link: String, val hash: String, val name: String, val folder: String,
                   var index: Int = -1, var path: String? = null, var total: Long = 0, var done: Boolean = false,
                   var failed: Boolean = false) {
        @Volatile var handle: TorrentHandle? = null
        @Volatile var going = false // being added again (see resume)
        @Volatile var moving = false // finished, on its way to its shared folder (see publish)
        fun json(): JSONObject = JSONObject().put("key", key).put("link", link).put("hash", hash).put("name", name).put("folder", folder)
            .put("index", index).put("path", path ?: JSONObject.NULL).put("total", total).put("done", done).put("failed", failed)
    }

    private fun downloadsFile() = File(app.filesDir, "downloads.json")

    private fun loadDownloads() = synchronized(downloads) {
        runCatching {
            val a = org.json.JSONArray(downloadsFile().readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val d = Download(o.getString("key"), o.getString("link"), o.getString("hash"), o.getString("name"), o.getString("folder"),
                    o.optInt("index", -1), o.optString("path").takeIf { !o.isNull("path") && it.isNotEmpty() }, o.optLong("total"),
                    o.optBoolean("done"), o.optBoolean("failed"))
                downloads[d.key] = d
            }
        }
    }

    private fun saveDownloads() = synchronized(downloads) {
        val a = org.json.JSONArray()
        downloads.values.forEach { a.put(it.json()) }
        runCatching { downloadsFile().writeText(a.toString()) }
    }

    /**
     * Where a download in Zephyr TV's folder [key] ("app", "downloads", "movies" or "vol:<its path>") is made: the
     * engine's own folder, on the drive the key names. Android lets it write anything there, libtorrent's part files
     * too, which the shared Movies folder turns away (it takes only pictures, sound and video).
     */
    private fun workDir(key: String): File {
        val own = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        val dir = if (!key.startsWith("vol:")) own else key.removePrefix("vol:").substringBefore("/Android/data/").let { vol ->
            app.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull().firstOrNull { it.path.startsWith("$vol/") }
        } ?: own
        return dir.takeIf { it.mkdirs() || it.isDirectory }?.takeIf { it.canWrite() } ?: own
    }

    /**
     * Where a finished download goes when it isn't the engine's own folder: the shared Download or Movies folder, as
     * Zephyr TV's own downloads (Android 11 and later let an app put its files there; before, they stay in the engine's).
     */
    private fun publicDir(key: String): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        val root = Environment.getExternalStorageDirectory().path
        val dir = when (key) {
            "downloads" -> File("$root/${Environment.DIRECTORY_DOWNLOADS}/Zephyr TV")
            "movies" -> File("$root/${Environment.DIRECTORY_MOVIES}/Zephyr TV")
            else -> return null
        }
        return dir.takeIf { it.mkdirs() || it.isDirectory }
    }

    /** A finished download moved to its shared folder (see publicDir); where it was made when that can't be done. */
    private fun publish(d: Download, target: File) {
        d.moving = true
        Thread({
            Thread.sleep(2000) // libtorrent lets go of the file
            val src = d.path?.let { File(it) }
            if (src != null && src.exists()) {
                val dst = File(target, uniqueName(target, src.name))
                val moved = src.renameTo(dst) || runCatching {
                    src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    dst.length() == src.length()
                }.getOrElse { dst.delete(); false }
                if (moved) {
                    if (src.exists()) src.delete()
                    d.path = dst.path
                } else log("download kept in the engine's folder: it couldn't go to ${target.path}")
            }
            d.done = true
            d.moving = false
            saveDownloads()
        }, "torrent-publish").apply { isDaemon = true }.start()
    }

    fun download(key: String, link: String, name: String, folderKey: String): Boolean {
        val m = Magnet.parse(link) ?: return false
        val d = Download(key, link, m.hash, name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "video" },
            folderKey, index = m.file ?: -1)
        synchronized(downloads) {
            // The same torrent asked again (a kept stream played again): it goes on as it is.
            downloads[key]?.takeIf { it.hash == d.hash && !it.failed }?.let { return true }
            downloads[key]?.let { old -> old.handle?.let { remove(it, files = true) } }
            downloads[key] = d
        }
        saveDownloads()
        resume(d)
        DownloadService.update(app)
        applyUpload()
        return true
    }

    /** A download going (again): added, its metadata waited for, its file alone wanted and named as Zephyr TV calls it. */
    private fun resume(d: Download) {
        if (d.going) return
        d.going = true
        Thread({
            // A failure here (the session that can't start) mustn't take the engine's process, and every call, with it.
            try { resumeNow(d) } catch (e: Throwable) { log("download ${d.key} not resumed: $e") } finally { d.going = false }
        }, "torrent-download").apply { isDaemon = true }.start()
    }

    private fun resumeNow(d: Download) {
        val dir = workDir(d.folder)
        val again = d.path?.let { File(it).exists() } == true // part of it saved before the engine stopped
        val h = handle(Magnet.parse(d.link) ?: return, d.link, dir) ?: run { d.failed = true; saveDownloads(); return }
        d.handle = h
        val ti = metadata(h) { synchronized(downloads) { downloads[d.key] !== d } }
        if (synchronized(downloads) { downloads[d.key] !== d }) return
        if (ti == null) {
            d.failed = true
            remove(h, files = !again)
            d.handle = null
            saveDownloads()
            return DownloadService.update(app)
        }
        val fs = ti.files()
        val idx = pick(ti, d.index.takeIf { it >= 0 })
        d.index = idx
        d.total = fs.fileSize(idx)
        val ext = fs.fileName(idx).substringAfterLast('.', "mkv").lowercase().take(5)
        // Straight in the folder, as "Film (2024).mkv": not in the torrent's own folders.
        val target = d.path?.let { File(it).name } ?: uniqueName(dir, "${d.name}.$ext")
        if (fs.filePath(idx) != target) runCatching { h.renameFile(idx, target) }
        d.path = File(dir, target).path
        h.prioritizeFiles(Array(fs.numFiles()) { if (it == idx) Priority.DEFAULT else Priority.IGNORE })
        // What was saved before is under its new name, which libtorrent only looks at when asked to check again.
        if (again) {
            Thread.sleep(1000)
            runCatching { h.forceRecheck() }
        }
        saveDownloads()
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        return (2..99).map { "$base ($it).$ext" }.firstOrNull { !File(dir, it).exists() } ?: name
    }

    /** Each download's progress looked at (from DownloadService): finished ones let go of, unless seeding is on. */
    fun tick(): Boolean {
        checkIdle()
        var going = false
        synchronized(downloads) {
            for (d in downloads.values) {
                if (d.done || d.failed) continue
                if (d.moving) {
                    going = true
                    continue
                }
                val h = d.handle
                if (h == null) {
                    resume(d)
                    going = true
                    continue
                }
                going = true
                if (d.total <= 0 || d.index < 0 || !h.isValid) continue
                val got = runCatching { h.fileProgress()[d.index] }.getOrDefault(0L)
                // Finished while it plays: moved to its folder once the player lets go of it.
                if (got >= d.total && play?.hash == d.hash) continue
                if (got >= d.total) {
                    runCatching { h.flushCache() }
                    // Shared on (seeding): the file stays where libtorrent has it, to be read from.
                    val target = if (seeding()) null else publicDir(d.folder)
                    if (!seeding()) {
                        remove(h, false)
                        d.handle = null
                    }
                    if (target == null) d.done = true else publish(d, target)
                }
            }
        }
        saveDownloads()
        applyUpload()
        return going
    }

    /** Whether any download still has work to do (DownloadService stays in the foreground while there is). */
    fun downloading() = synchronized(downloads) { downloads.values.any { !it.done && !it.failed } }

    /** Downloads not finished when the engine last stopped, going again (the engine was killed, or the TV restarted). */
    fun resumeAll() {
        val todo = synchronized(downloads) { downloads.values.filter { !it.done && !it.failed && it.handle == null } }
        todo.forEach { resume(it) }
        if (todo.isNotEmpty()) DownloadService.update(app)
    }

    fun downloads(): List<Download> = synchronized(downloads) { downloads.values.toList() }

    fun progress(d: Download): Long = when {
        d.done || d.moving -> d.total
        else -> d.handle?.takeIf { it.isValid && d.index >= 0 }?.let { h -> runCatching { h.fileProgress()[d.index] }.getOrNull() } ?: 0L
    }

    fun rate(d: Download): Int = d.handle?.takeIf { it.isValid }?.let { runCatching { it.status().downloadPayloadRate() }.getOrNull() } ?: 0

    /** A download cancelled or deleted: its torrent let go of, its file deleted. */
    fun cancel(key: String) {
        val d = synchronized(downloads) { downloads.remove(key) } ?: return
        d.handle?.let { remove(it, files = true) }
        d.path?.let { File(it).delete() }
        saveDownloads()
        DownloadService.update(app)
    }

    fun downloadFile(key: String): File? = synchronized(downloads) { downloads[key] }?.path?.let { File(it) }?.takeIf { it.exists() }

    /** A stream's session, for its status: peers, seeds, speeds. */
    fun peers(p: Play): IntArray = runCatching { p.handle.status().let { intArrayOf(it.numPeers(), it.numSeeds(), it.downloadPayloadRate(), it.uploadPayloadRate()) } }
        .getOrDefault(intArrayOf(0, 0, 0, 0))

    internal fun mimeOf(file: String): String = when (file.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    private object Uri {
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    internal fun log(text: String) = Log.i(TAG, text)
}
