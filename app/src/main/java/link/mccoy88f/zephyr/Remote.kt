package link.mccoy88f.zephyr

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Remote access: one page on this TV, for a phone on the home network. A PIN (set on the TV) opens it. The address
// stays the same, so it can be saved. The page shows the settings that are open and can change any of them. A code
// on a single setting opens that setting; saving it closes the screen. WARP hides the codes: the phone cannot
// reach the TV while Zephyr TV's traffic goes through the tunnel.

private const val PORT = 8765
private const val SESSION_MS = 12 * 3600_000L
private val ID = Regex("[A-Za-z0-9_-]{1,64}")

/** An id safe to put in the address. */
internal fun remoteId(raw: String) =
    raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.joinToString("").take(48).ifEmpty { "x" }

/** The part of [path] after /[token], or null when the path is not this TV's. */
internal fun remoteRest(path: String, token: String): String? {
    val prefix = "/$token"
    if (path != prefix && !path.startsWith("$prefix/")) return null
    return path.removePrefix(prefix).trimStart('/')
}

/** A phone on the home network: loopback, link-local, or a private range. Not an address from the internet. */
internal fun isPrivateLan(addr: InetAddress): Boolean {
    val a = addr.address.let { b ->
        if (b.size == 16 && b.copyOfRange(0, 10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte())
            InetAddress.getByAddress(b.copyOfRange(12, 16))
        else addr
    }
    if (a.isLoopbackAddress || a.isLinkLocalAddress) return true
    val b = a.address
    if (b.size == 4) {
        val x = b[0].toInt() and 255
        val y = b[1].toInt() and 255
        return x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168)
    }
    return b.size == 16 && (b[0].toInt() and 254) == 0xfc
}

internal object Remote {
    @Volatile var home: MainActivity? = null

    private var socket: ServerSocket? = null
    private var workers = Executors.newFixedThreadPool(4)
    @Volatile var port: Int? = null
    @Volatile private var cachedToken: String? = null

    private val sessions = ConcurrentHashMap<String, Long>()
    private val held = ConcurrentHashMap<String, PhoneTask>()
    private val done = ConcurrentHashMap<String, () -> Unit>()
    @Volatile var active: String? = null

    private var pinFails = 0
    private var pinWait = 0L

    /** Remote access is on, and its page is served. A restart keeps serving it. */
    fun attach(a: MainActivity) {
        home = a
        if (a.remoteOn()) listen()
    }

    /** The home screen went away. The page keeps its port across a recreate; [stop] is turning the setting off. */
    fun detach(a: MainActivity) {
        if (home === a) home = null
    }

    fun stop() {
        try {
            socket?.close()
        } catch (e: IOException) {
        }
        socket = null
        port = null
        workers.shutdownNow()
        workers = Executors.newFixedThreadPool(4)
        sessions.clear()
        held.clear()
        done.clear()
        active = null
    }

    /** The saved address, with its secret path. Null until the page is being served. */
    fun url(ip: String): String? {
        val p = port ?: return null
        val t = token() ?: return null
        return "http://$ip:$p/$t/"
    }

    /** [task] answers [id] while this screen is open. [onDone] runs on the TV after a save (close the screen). */
    fun hold(id: String, task: PhoneTask, onDone: () -> Unit) {
        held[id] = task
        done[id] = onDone
        active = id
    }

    fun release(id: String) {
        held.remove(id)
        done.remove(id)
        if (active == id) active = null
    }

    private fun listen() {
        if (socket?.isClosed == false) return
        val a = home ?: return
        cachedToken = tokenOf(a)
        val chosen = openSocket() ?: return
        socket = chosen
        port = chosen.localPort
        val pool = workers
        Thread({ serve(pool) }, "remote-access").start()
    }

    /** Port 8765 when it's free, so a saved address keeps working. Any free port when it isn't. */
    private fun openSocket(): ServerSocket? {
        fun bind(port: Int) = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(port))
        }
        return try {
            bind(PORT)
        } catch (e: IOException) {
            try {
                bind(0)
            } catch (e2: IOException) {
                warn("remote access didn't start ($e2)")
                null
            }
        }
    }

    private fun token(): String? = home?.let { tokenOf(it) } ?: cachedToken

    private fun tokenOf(a: MainActivity): String {
        a.prefs.getString(K.REMOTE_TOKEN, null)?.let { return it }
        val t = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        a.prefs.edit().putString(K.REMOTE_TOKEN, t).apply()
        return t
    }

    private fun serve(pool: java.util.concurrent.ExecutorService) {
        val server = socket ?: return
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            try {
                pool.execute {
                    try {
                        s.use { handle(it) }
                    } catch (e: Exception) {
                        warn("remote request failed ($e)")
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                s.close()
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        if (!isPrivateLan(s.inetAddress)) return reply(out, 403, "text/plain; charset=utf-8", "This page is only for your home network.")
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .take(100)
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val method = request.substringBefore(' ')
        val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?')
        val token = token() ?: return reply(out, 503, "text/plain; charset=utf-8", "Zephyr TV is starting.")
        val rest = remoteRest(path, token) ?: return reply(out, 404, "text/plain; charset=utf-8", "Not found.")
        val a = home
        if (a == null || !a.remoteOn()) return reply(out, 503, "text/plain; charset=utf-8", "Remote access is off.")
        val authed = authed(headers)
        when {
            rest == "api/pin" && method == "POST" -> pin(a, out, readBody(input, headers, 32))
            !authed && rest.startsWith("api/") -> reply(out, 401, "text/plain; charset=utf-8", a.getString(R.string.remote_pin_enter))
            rest == "api/page" && method == "GET" -> reply(out, 200, "application/json; charset=utf-8", onMain { remoteSnapshot().toString() })
            rest == "api/press" && method == "POST" -> {
                press(readBody(input, headers, 16))
                reply(out, 200, "text/plain; charset=utf-8", "")
            }
            rest == "api/back" && method == "POST" -> { onMain { goBack() }; reply(out, 200, "text/plain; charset=utf-8", "") }
            rest == "api/open" && method == "POST" -> { onMain { openFromPhone() }; reply(out, 200, "text/plain; charset=utf-8", "") }
            rest == "api/text" && method == "POST" -> text(out, readBody(input, headers, 1024L * 1024))
            rest.isEmpty() && method == "GET" -> reply(out, 200, "text/html; charset=utf-8", if (authed) a.menuHtml() else a.pinHtml())
            rest.startsWith("go/") && method == "GET" -> goGet(a, out, rest.removePrefix("go/"), authed)
            rest.startsWith("go/") && method == "POST" -> goPost(a, out, input, headers, rest.removePrefix("go/"), authed)
            else -> reply(out, 404, "text/plain; charset=utf-8", "Not found.")
        }
    }

    private fun pin(a: MainActivity, out: OutputStream, body: String?) {
        val pin = body?.trim().orEmpty()
        val error = checkPin(a, pin)
        if (error != null) return reply(out, 401, "text/plain; charset=utf-8", error)
        val z = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        sessions[z] = System.currentTimeMillis() + SESSION_MS
        val token = token().orEmpty()
        reply(out, 200, "text/plain; charset=utf-8", "", "Set-Cookie: z=$z; HttpOnly; SameSite=Strict; Path=/$token\r\n")
    }

    /** Null when the PIN is right. */
    private fun checkPin(a: MainActivity, pin: String): String? {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now < pinWait) return a.getString(R.string.pin_too_many_tries_wait, ((pinWait - now) / 1000 + 1).toInt())
            val stored = a.prefs.getString(K.REMOTE_PIN, null) ?: return a.getString(R.string.remote_not_open)
            if (pin.length != 4 || pin.any { !it.isDigit() } || !pinMatches(stored, pin)) {
                if (++pinFails >= MainActivity.PIN_TRIES) {
                    pinFails = 0
                    pinWait = now + 30_000
                    return a.getString(R.string.pin_too_many_tries_wait, 30)
                }
                return a.getString(R.string.pin_wrong)
            }
            pinFails = 0
            return null
        }
    }

    private fun authed(headers: Map<String, String>): Boolean {
        val raw = headers["cookie"] ?: return false
        val z = raw.split(';').map { it.trim() }.firstOrNull { it.startsWith("z=") }?.removePrefix("z=") ?: return false
        val exp = sessions[z] ?: return false
        if (System.currentTimeMillis() > exp) {
            sessions.remove(z)
            return false
        }
        return true
    }

    private fun press(body: String?) {
        val i = body?.trim()?.toIntOrNull() ?: return
        try {
            onMain {
                if (settingsScrim.visibility != View.VISIBLE) return@onMain
                remoteRows.getOrNull(i)?.third?.invoke()
            }
        } catch (e: Exception) {
            warn("remote press ($e)")
        }
    }

    private fun text(out: OutputStream, body: String?) {
        val answer = onMain { takeText(body.orEmpty()) }
        reply(out, answer.first, "text/plain; charset=utf-8", answer.second)
    }

    private fun goGet(a: MainActivity, out: OutputStream, id: String, authed: Boolean) {
        if (!authed) return reply(out, 200, "text/html; charset=utf-8", a.pinHtml())
        if (!ID.matches(id)) return reply(out, 404, "text/plain; charset=utf-8", "Not found.")
        val task = held[id] ?: a.remoteTask(id) ?: return reply(out, 404, "text/plain; charset=utf-8", a.getString(R.string.remote_not_open))
        reply(out, 200, "text/html; charset=utf-8", task.page.html)
    }

    private fun goPost(a: MainActivity, out: OutputStream, input: InputStream, headers: Map<String, String>, id: String, authed: Boolean) {
        if (!authed) return reply(out, 401, "text/plain; charset=utf-8", a.getString(R.string.remote_pin_enter))
        if (!ID.matches(id)) return reply(out, 404, "text/plain; charset=utf-8", "Not found.")
        val task = held[id] ?: a.remoteTask(id) ?: return reply(out, 404, "text/plain; charset=utf-8", a.getString(R.string.remote_not_open))
        if (task.maxBytes < 1) return reply(out, 405, "text/plain; charset=utf-8", "Not allowed")
        val length = headers["content-length"]?.toLongOrNull() ?: -1
        if (length !in 1..task.maxBytes) return reply(out, 413, "text/plain; charset=utf-8", "Too big.")
        val temp = File.createTempFile("remote", ".part", a.cacheDir)
        try {
            temp.outputStream().use { file ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("upload cut short")
                    file.write(buf, 0, n)
                    left -= n
                }
            }
            val (status, text) = task.onSend(temp)
            if (status in 200..299) succeed(id)
            reply(out, status, "text/plain; charset=utf-8", text)
        } finally {
            temp.delete()
        }
    }

    private fun succeed(id: String) {
        val onDone = done[id] ?: return
        home?.ui?.post { if (done[id] === onDone) onDone() }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, max: Long): String? {
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        if (length !in 0..max) return null
        if (length == 0L) return ""
        val buf = ByteArray(length.toInt())
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) return null
            got += n
        }
        return buf.decodeToString()
    }

    private fun <T> onMain(block: MainActivity.() -> T): T {
        val a = home ?: throw IOException("no tv")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return a.block()
        val box = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        a.ui.post {
            box.set(runCatching { a.block() })
            latch.countDown()
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IOException("tv busy")
        return box.get().getOrThrow()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }
}

/** Remote access is switched on. */
internal fun MainActivity.remoteOn() = prefs.getBoolean(K.REMOTE, false)

/**
 * Rows from Remote access to the next fixed row. Show the address sits under the switch while remote access is on,
 * so Backup and restore and the rows under it move down by one.
 */
internal fun rowsAfterRemote(remoteOn: Boolean) = if (remoteOn) 2 else 1

internal fun MainActivity.remoteShowRow() = MainActivity.REMOTE_ROW + 1
internal fun MainActivity.backupRow() = MainActivity.REMOTE_ROW + rowsAfterRemote(remoteOn())
internal fun MainActivity.problemsRow() = backupRow() + 1
internal fun MainActivity.guideRow() = backupRow() + 2
internal fun MainActivity.aboutRow() = backupRow() + 3

/**
 * Remote access on, with a PIN. The PIN is asked here when there isn't one yet. [back] leaves the PIN page.
 * [then] runs once it's on.
 */
internal fun MainActivity.ensureRemote(back: () -> Unit, then: () -> Unit) {
    fun arm() {
        if (!remoteOn()) prefs.edit().putBoolean(K.REMOTE, true).apply()
        Remote.attach(this)
        then()
    }
    if (prefs.contains(K.REMOTE_PIN)) return arm()
    pinPage(getString(R.string.remote_pin_choose), back) { first ->
        pinPage(getString(R.string.pin_enter_again_title), back) { again ->
            if (again != first) {
                Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_LONG).show()
                ensureRemote(back, then)
                return@pinPage null
            }
            prefs.edit().putString(K.REMOTE_PIN, hashPin(first)).apply()
            arm()
            null
        }
        null
    }
}

internal fun MainActivity.toggleRemote() {
    if (remoteOn()) {
        prefs.edit().putBoolean(K.REMOTE, false).apply()
        Remote.stop()
        renderSettings(MainActivity.REMOTE_ROW)
        return
    }
    ensureRemote({ renderSettings(MainActivity.REMOTE_ROW) }) { renderSettings(MainActivity.REMOTE_ROW) }
}

/** The address to save: the whole settings page, after the PIN. */
internal fun MainActivity.showRemoteAddress() {
    val back = { renderSettings(if (remoteOn()) remoteShowRow() else MainActivity.REMOTE_ROW) }
    ensureRemote(back) {
        if (warpOn()) {
            showPage(getString(R.string.setting_remote), listOf(page(getString(R.string.dialog_done)) { back() }), top = warpQrNotice(), back = back)
            return@ensureRemote
        }
        val ip = lanAddress() ?: return@ensureRemote Toast.makeText(this, getString(R.string.toast_connect_network), Toast.LENGTH_LONG).show().let { }
        val url = Remote.url(ip) ?: return@ensureRemote Toast.makeText(this, getString(R.string.toast_upload_server_failed), Toast.LENGTH_LONG).show().let { }
        showPage(getString(R.string.setting_remote), listOf(page(getString(R.string.dialog_done)) { back() }),
            top = qrBox(url, url, getString(R.string.remote_home_intro), label("", 12f)), back = back)
    }
}

/**
 * The code for [target]. Saving that setting on the phone closes this screen ([close]: a search starts instead,
 * so the screen is already gone). [status] is the line under the code, the one [task] updates.
 */
internal fun MainActivity.presentRemote(title: String, target: String, task: PhoneTask, back: () -> Unit, status: TextView? = null, close: Boolean = true) {
    if (!ID.matches(target)) return
    ensureRemote(back) {
        if (warpOn()) {
            showPage(title, listOf(page(getString(R.string.dialog_done)) { back() }), top = warpQrNotice(), back = back)
            return@ensureRemote
        }
        val ip = lanAddress() ?: return@ensureRemote Toast.makeText(this, getString(R.string.toast_connect_network), Toast.LENGTH_LONG).show().let { }
        val root = Remote.url(ip) ?: return@ensureRemote Toast.makeText(this, getString(R.string.toast_upload_server_failed), Toast.LENGTH_LONG).show().let { }
        val line = status ?: label(getString(R.string.remote_waiting), 14f)
        if (line.text.isNullOrBlank()) line.text = getString(R.string.remote_waiting)
        (line.parent as? android.view.ViewGroup)?.removeView(line)
        showPage(title, listOf(page(getString(R.string.dialog_done)) { back() }),
            top = qrBox(root + "go/$target", root + "go/$target", getString(R.string.remote_qr_intro), line), back = back)
        val host = this
        Remote.hold(target, task) {
            if (!close) return@hold
            val live = Remote.home
            if (live === host && live.pageShowing(title)) back()
        }
        pageExit = { Remote.release(target) }
    }
}

private fun MainActivity.goBack() {
    if (settingsScrim.visibility != View.VISIBLE) return
    remoteBack?.invoke() ?: closeLauncherSettings()
}

private fun MainActivity.openFromPhone() {
    unlockedUntil = SystemClock.elapsedRealtime() + MainActivity.UNLOCKED_MS
    openLauncherSettings()
}

private fun MainActivity.takeText(body: String): Pair<Int, String> {
    if (settingsScrim.visibility != View.VISIBLE) return 409 to ""
    remotePinSubmit?.let { pin ->
        val err = pin(body.trim())
        return if (err == null) 200 to "" else 400 to err
    }
    val field = remoteField ?: return 409 to ""
    field.setText(body.take(1024 * 1024))
    return 200 to ""
}

/** What the phone shows: the panel's page, or that settings are closed. */
internal fun MainActivity.remoteSnapshot(): JSONObject {
    val open = settingsScrim.visibility == View.VISIBLE && remoteTitle.isNotEmpty()
    val rows = JSONArray()
    if (open) remoteRows.forEach { (name, value) ->
        val shown = when (value) {
            MainActivity.TOGGLE_ON -> getString(R.string.state_on)
            MainActivity.TOGGLE_OFF -> getString(R.string.state_off)
            MainActivity.SUBMENU -> ""
            else -> value
        }
        rows.put(JSONObject().put("label", name).put("value", shown))
    }
    return JSONObject()
        .put("title", if (open) remoteTitle else "")
        .put("closed", !open)
        .put("back", open)
        .put("input", when {
            !open -> ""
            remotePinSubmit != null -> "pin"
            remoteField != null -> "text"
            else -> ""
        })
        .put("rows", rows)
        .put("form", Remote.active.orEmpty())
}

/**
 * A setting the saved address can open on its own, when that screen isn't already waiting (see [Remote.hold]).
 * Names, plugin options and the like exist only while their screen is open.
 */
internal fun MainActivity.remoteTask(id: String): PhoneTask? = when (id) {
    "wallpaper-light", "wallpaper-dark" -> {
        val dark = id.endsWith("dark")
        val dir = wallpaperDir()
        val page = PhonePages.file(phoneTexts(R.string.phone_wallpaper_title, R.string.phone_wallpaper_intro), getString(R.string.phone_choose_photo), "image/*")
        PhoneTask(page, 25L * 1024 * 1024) { file ->
            if (dir == null) return@PhoneTask 500 to getString(R.string.phone_not_a_photo)
            val saved = importWallpaper(file, dir) ?: return@PhoneTask 415 to getString(R.string.phone_not_a_photo)
            ui.post { setWallpaper(dark, "file:${saved.name}") }
            200 to getString(R.string.phone_wallpaper_added, saved.nameWithoutExtension)
        }
    }
    "tmdb-key" -> {
        var unchecked = false
        val texts = PhoneTexts(getString(R.string.setting_tmdb_api_key), getString(R.string.phone_tmdb_intro), getString(R.string.phone_send),
            getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())
        PhoneTask(PhonePages.text(texts, getString(R.string.phone_tmdb_hint), secret = true), 2048) { file ->
            val key = file.readText().trim()
            if (key.length < 16 || key.any { it.isWhitespace() }) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
            val ok = TmdbClient.check(key)
            unchecked = ok == null
            if (ok == false) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
            ui.post { saveTmdbKey(key) }
            200 to getString(if (unchecked) R.string.phone_tmdb_unchecked else R.string.phone_tmdb_saved)
        }
    }
    "epg" -> {
        val texts = PhoneTexts(getString(R.string.setting_epg), getString(R.string.phone_epg_intro), getString(R.string.phone_send),
            getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())
        PhoneTask(PhonePages.text(texts, getString(R.string.epg_hint)), 1024L * 1024) { file ->
            val url = file.readText().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@PhoneTask 400 to getString(R.string.phone_epg_bad)
            ui.post { saveEpgUrl(url) }
            200 to getString(R.string.phone_epg_saved)
        }
    }
    "tmdb-list" -> {
        val texts = PhoneTexts(getString(R.string.setting_tmdb_list), getString(R.string.phone_list_intro), getString(R.string.phone_send),
            getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())
        PhoneTask(PhonePages.text(texts, getString(R.string.phone_list_hint)), 2048) { file ->
            val key = prefs.getString(K.TMDB_API_KEY, null) ?: return@PhoneTask 400 to getString(R.string.phone_list_bad)
            val list = TmdbClient.listId(file.readText()) ?: return@PhoneTask 400 to getString(R.string.phone_list_bad)
            val items = TmdbClient.fetchList(key, list, tmdbLang())
            if (items.isEmpty()) return@PhoneTask 400 to getString(R.string.phone_list_bad)
            ui.post {
                prefs.edit().putString(K.TMDB_LIST, list).putString(K.TMDB_SOURCE, "list").apply()
                tmdbTrending = emptyList()
                loadWallpaper()
            }
            200 to getString(R.string.phone_list_saved, items.size)
        }
    }
    "problems" -> PhoneTask(problemsHtml(), 0) { 405 to "" }
    "backup-save" -> PhoneTask(PhonePages.download(getString(R.string.phone_backup_title), getString(R.string.phone_backup_intro),
        getString(R.string.phone_backup_download), "zephyr-backup.json", Backup.export(prefs, filesDir, users().map { it.id }), phoneFooter()).html, 0) { 405 to "" }
    "backup-restore" -> {
        val page = PhonePages.file(phoneTexts(R.string.phone_restore_title, R.string.phone_restore_intro), getString(R.string.phone_choose_file), ".json,application/json")
        PhoneTask(page, 16L * 1024 * 1024) { file ->
            val n = try {
                Backup.restore(prefs, file.readText(), filesDir)
            } catch (e: Exception) {
                warn("restore failed ($e)")
                return@PhoneTask 400 to getString(R.string.phone_restore_bad)
            }
            ui.postDelayed({
                prefs.edit().putInt(K.REOPEN_ROW, backupRow()).apply()
                recreate()
            }, 2000)
            200 to getString(R.string.phone_restore_done, n)
        }
    }
    "ext-add" -> {
        val page = PhonePages.text(phoneTexts(R.string.ext_phone_title, R.string.ext_add_intro), getString(R.string.ext_hint))
        PhoneTask(page, 1024L * 1024) { file ->
            val url = Extensions.publishedUrl(file.readText().trim()) ?: return@PhoneTask 400 to getString(R.string.ext_err_not)
            ui.post { extensionFromAddress(url) }
            200 to getString(R.string.ext_received)
        }
    }
    "ext-zip" -> {
        val page = PhonePages.file(phoneTexts(R.string.ext_phone_title, R.string.ext_zip_intro), getString(R.string.ext_choose_zip), ".zip,application/zip")
        PhoneTask(page, 10L * 1024 * 1024) { file ->
            val zip = File(cacheDir, "extension-${System.currentTimeMillis()}.zip")
            file.copyTo(zip, overwrite = true)
            if (Extensions.manifestOf(zip) == null) {
                zip.delete()
                return@PhoneTask 415 to getString(R.string.ext_err_not)
            }
            ui.post { extensionFromZip(zip) }
            200 to getString(R.string.ext_received)
        }
    }
    "catalog-add" -> {
        val page = PhonePages.text(phoneTexts(R.string.catalog_add, R.string.catalog_add_intro), getString(R.string.addon_hint))
        PhoneTask(page, 1024L * 1024) { file ->
            val url = Stremio.manifestUrl(file.readText().lines().firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
            ui.post { addCatalogUrl(url) }
            200 to getString(R.string.phone_addon_saved)
        }
    }
    "addon-add" -> {
        val page = PhonePages.addon(phoneTexts(R.string.phone_addon_title, R.string.phone_addon_intro), getString(R.string.addon_hint), getString(R.string.addon_adult))
        PhoneTask(page, 1024L * 1024) { file ->
            val lines = file.readText().lines()
            val url = Stremio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
            val adult = lines.drop(1).any { it.trim() == "adult" }
            ui.post { addAddon(url, adult) }
            200 to getString(R.string.phone_addon_saved)
        }
    }
    "nuvio-add" -> {
        val page = PhonePages.addon(phoneTexts(R.string.nuvio_phone_title, R.string.nuvio_add_intro), getString(R.string.nuvio_hint), getString(R.string.addon_adult))
        PhoneTask(page, 1024L * 1024) { file ->
            val lines = file.readText().lines()
            val url = Nuvio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
            val adult = lines.drop(1).any { it.trim() == "adult" }
            ui.post { addRepo(url, adult) }
            200 to getString(R.string.phone_addon_saved)
        }
    }
    "search" -> {
        val page = PhonePages.text(phoneTexts(R.string.phone_search_title, R.string.phone_search_intro), getString(R.string.search_hint))
        PhoneTask(page, 1024) { file ->
            val query = file.readText().trim().take(200)
            if (query.isEmpty()) return@PhoneTask 400 to getString(R.string.search_hint)
            ui.post {
                closeLauncherSettings()
                searchTmdb(query)
            }
            200 to getString(R.string.phone_search_sent, query)
        }
    }
    else -> null
}

private fun js(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c").replace("\n", " ")

/** Light, dark or glass, as the TV is set. Auto follows the same hours as the home screen. */
private fun MainActivity.remoteTheme() = when (prefs.getString(K.THEME, "light")) {
    "dark" -> "dark"
    "glass" -> "glass"
    "auto" -> if (prefersDark(prefs)) "dark" else "light"
    else -> "light"
}

/** The mark from the app icon: a TV, without the word under it. */
private const val LOGO = """<svg class="logo" viewBox="0 0 72 72" aria-hidden="true"><defs><linearGradient id="lg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0F172A"/><stop offset="1" stop-color="#020617"/></linearGradient><linearGradient id="lf" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#38BDF8"/><stop offset="1" stop-color="#2563EB"/></linearGradient></defs><rect width="72" height="72" rx="16" fill="url(#lg)"/><circle cx="36" cy="12" r="1.7" fill="#38BDF8"/><path d="M36 13.8 V18" stroke="#38BDF8" stroke-width="1.6" stroke-linecap="round"/><rect x="14" y="18" width="44" height="28" rx="5" fill="none" stroke="url(#lf)" stroke-width="3"/><polygon points="31,25 31,39 45,32" fill="#fff"/></svg>"""

/** The settings button on the home screen (ic_tune). */
private const val TUNE = """<svg class="tune" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"/></svg>"""

/**
 * The phone page in the TV's theme. [body] is the page. [dock] is the bar at the bottom. The logo, the app name
 * and the TV's name stay at the top.
 */
private fun MainActivity.remotePage(body: String, dock: String, script: String) = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${escHtml(getString(R.string.app_name))}</title><style>
      :root{--panel:#f7f8fa;--ground:#f3f5f7;--fg:#111214;--on:#fff;--muted:rgba(17,18,20,.6);--line:rgba(17,18,20,.12);--field:#e5e7eb;--fill:#111214}
      body.dark{--panel:#09090b;--ground:#000;--fg:#f5f5f7;--on:#111214;--muted:rgba(245,245,247,.6);--line:rgba(245,245,247,.16);--field:#2a2a32;--fill:#f5f5f7}
      body.glass{--panel:transparent;--ground:#000;--fg:#fff;--on:#111214;--muted:rgba(255,255,255,.6);--line:rgba(255,255,255,.16);--field:rgba(255,255,255,.08);--fill:#fff}
      *{box-sizing:border-box} body{margin:0;background:var(--ground);color:var(--fg);font:17px system-ui,sans-serif}
      .sheet{max-width:420px;min-height:100vh;margin:0 auto;display:flex;flex-direction:column;background:var(--panel)}
      body.glass .sheet{background:linear-gradient(180deg,rgba(42,42,50,.85),rgba(16,16,20,.9))}
      header{text-align:center;padding:28px 20px 8px}
      .logo{width:72px;height:72px;display:block;margin:0 auto 12px}
      h1{font:500 20px system-ui,sans-serif;margin:0}
      .tv{margin:4px 0 0;font-size:14px;color:var(--muted)}
      main{flex:1;padding:12px 16px 16px}
      h2{display:flex;align-items:center;gap:10px;margin:8px 0 10px;padding:0 14px;font:500 20px system-ui,sans-serif}
      .tune{width:22px;height:22px;flex:none}
      p.note{margin:0 14px 12px;color:var(--muted);font-size:15px}
      button.row{display:flex;align-items:center;gap:12px;width:100%;min-height:44px;margin-top:4px;padding:0 14px;border:0;border-radius:8px;background:transparent;color:inherit;font:14px system-ui,sans-serif;text-align:left}
      body.glass button.row{margin-top:6px;background:linear-gradient(180deg,rgba(44,44,52,.55),rgba(18,18,22,.63));box-shadow:inset 0 1px 0 rgba(255,255,255,.14)}
      button.row:active{background:var(--fg);color:var(--on)}
      body.glass button.row:active{background:linear-gradient(180deg,rgba(90,90,102,.8),rgba(36,36,44,.8));color:#fff}
      .name{flex:1;font-weight:500} .val{max-width:46%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;color:var(--muted)}
      button.row:active .val{color:inherit;opacity:.75}
      .chev{width:20px;height:20px;flex:none;background:currentColor;-webkit-mask:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M8.6 7.4 10 6l6 6-6 6-1.4-1.4L13.2 12z'/></svg>") center/contain no-repeat;mask:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M8.6 7.4 10 6l6 6-6 6-1.4-1.4L13.2 12z'/></svg>") center/contain no-repeat}
      .sw{width:36px;height:20px;border-radius:10px;border:2px solid var(--muted);position:relative;flex:none}
      .sw:after{content:"";position:absolute;width:12px;height:12px;border-radius:50%;top:2px;left:2px;background:var(--muted)}
      .sw.on{background:var(--fg);border-color:var(--fg)} .sw.on:after{left:18px;background:var(--panel)}
      body.glass .sw.on:after{background:#121216}
      button.row:active .sw{border-color:var(--on)} button.row:active .sw:after{background:var(--on)}
      button.row:active .sw.on{background:var(--on)} button.row:active .sw.on:after{background:var(--fg)}
      a.form{display:block;margin:8px 14px 12px;color:inherit}
      textarea,input{display:block;width:100%;margin:8px 0;padding:14px;border:0;border-radius:8px;background:var(--field);color:inherit;font:inherit}
      textarea{min-height:6em} #e{min-height:1.4em;margin:8px 14px;font-size:14px}
      .send{display:block;width:100%;margin-top:8px;padding:14px;border:0;border-radius:8px;background:var(--fill);color:var(--on);font:500 15px system-ui,sans-serif}
      .dock{position:sticky;bottom:0;display:flex;gap:8px;margin-top:auto;padding:12px 16px calc(12px + env(safe-area-inset-bottom));background:var(--panel);border-top:1px solid var(--line)}
      body.glass .dock{background:rgba(16,16,20,.92)}
      .dock button{flex:1;padding:14px;border:0;border-radius:8px;background:var(--fill);color:var(--on);font:500 15px system-ui,sans-serif}
      .dock button[hidden]{display:none}
    </style></head><body class="${remoteTheme()}">
    <div class="sheet">
    <header>$LOGO<h1>${escHtml(getString(R.string.app_name))}</h1><p class="tv">${escHtml(tvName())}</p></header>
    <main>$body</main>
    $dock
    </div>
    <script>$script</script></body></html>
""".trimIndent()

private fun MainActivity.pinHtml(): String {
    val lost = js(getString(R.string.phone_unreachable))
    return remotePage("""
        <h2>$TUNE<span>${escHtml(getString(R.string.remote_menu_title))}</span></h2>
        <p class="note">${escHtml(getString(R.string.remote_pin_enter))}</p>
        <input id="p" inputmode="numeric" autocomplete="off" maxlength="4" type="password">
        <button id="b" class="send" type="button">${escHtml(getString(R.string.phone_send))}</button>
        <div id="e"></div>
    """.trimIndent(), "", """
        const root=location.pathname.replace(/\/go\/[^/]*$/, '').replace(/\/?$/, '/');
        const e=document.getElementById('e'), p=document.getElementById('p');
        document.getElementById('b').onclick=async()=>{
          e.textContent='';
          try{
            const r=await fetch(root+'api/pin',{method:'POST',body:p.value});
            if(r.ok) location.reload(); else e.textContent=await r.text();
          }catch(x){e.textContent='$lost';}
        };
    """.trimIndent())
}

private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun MainActivity.menuHtml(): String {
    val send = js(getString(R.string.phone_send))
    val form = js(getString(R.string.remote_open_form))
    val lost = js(getString(R.string.phone_unreachable))
    val on = js(getString(R.string.state_on))
    val off = js(getString(R.string.state_off))
    val dock = """<nav class="dock"><button id="open" type="button">${escHtml(getString(R.string.remote_open))}</button><button id="back" type="button">${escHtml(getString(R.string.remote_back))}</button></nav>"""
    return remotePage("""
        <h2 id="heading">$TUNE<span id="title"></span></h2>
        <p id="closed" class="note" hidden>${escHtml(getString(R.string.remote_closed))}</p>
        <a id="form" class="form" hidden href=""></a>
        <div id="rows"></div>
        <div id="extra"></div>
        <div id="e"></div>
    """.trimIndent(), dock, """
        const root=location.pathname.replace(/\/go\/[^/]*$/, '').replace(/\/?$/, '/');
        const title=document.getElementById('title'), heading=document.getElementById('heading'), rows=document.getElementById('rows'), back=document.getElementById('back');
        const closed=document.getElementById('closed'), extra=document.getElementById('extra'), form=document.getElementById('form'), e=document.getElementById('e');
        let last='', err='', kind='';
        async function api(path, body){
          const r=await fetch(root+path,{method:body==null?'GET':'POST',body:body==null?undefined:body});
          if(r.status==401){location.reload();return null;}
          return r;
        }
        async function act(path, body){
          e.textContent='';
          try{const r=await api(path, body==null?'':body); if(!r)return; err=r.ok?'':await r.text(); last=''; await draw();}
          catch(x){e.textContent='$lost';}
        }
        async function draw(){
          let r; try{r=await api('api/page');}catch(x){e.textContent='$lost';return;}
          if(!r)return;
          const p=await r.json();
          const raw=JSON.stringify(p);
          if(raw===last){if(err)e.textContent=err; return;}
          last=raw;
          title.textContent=p.title||'';
          heading.hidden=!p.title;
          closed.hidden=!p.closed;
          back.hidden=!p.back;
          form.hidden=!p.form;
          form.textContent='$form';
          form.href=p.form?root+'go/'+encodeURIComponent(p.form):'';
          while(rows.firstChild) rows.removeChild(rows.firstChild);
          (p.rows||[]).forEach(function(row,i){
            const b=document.createElement('button');
            b.type='button'; b.className='row';
            const n=document.createElement('span'); n.className='name'; n.textContent=row.label; b.appendChild(n);
            if(row.value==='$on'||row.value==='$off'){
              const s=document.createElement('span'); s.className='sw'+(row.value==='$on'?' on':''); b.appendChild(s);
            }else if(row.value){
              const v=document.createElement('span'); v.className='val'; v.textContent=row.value; b.appendChild(v);
            }else{
              const c=document.createElement('span'); c.className='chev'; b.appendChild(c);
            }
            b.onclick=function(){act('api/press', String(i));};
            rows.appendChild(b);
          });
          const mark=(p.input||'')+'\n'+(p.title||'');
          if(mark!==kind){
            kind=mark; while(extra.firstChild) extra.removeChild(extra.firstChild);
            if(p.input==='text'||p.input==='pin'){
              const x=document.createElement(p.input==='pin'?'input':'textarea');
              if(p.input==='pin'){x.type='password'; x.inputMode='numeric'; x.maxLength=4;}
              const s=document.createElement('button'); s.className='send'; s.type='button'; s.textContent='$send';
              s.onclick=function(){act('api/text', x.value);};
              extra.appendChild(x); extra.appendChild(s);
            }
          }
          e.textContent=err;
        }
        document.getElementById('open').onclick=function(){act('api/open');};
        back.onclick=function(){act('api/back');};
        draw(); setInterval(draw, 800);
    """.trimIndent())
}
