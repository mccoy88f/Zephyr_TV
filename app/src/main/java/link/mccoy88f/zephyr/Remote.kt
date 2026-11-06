package link.mccoy88f.zephyr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Remote access: one page on this TV, for a phone on the home network. The 18+ PIN opens it. The address
// stays the same, so it can be saved. The page shows the panel that is open (settings, users or search) and can
// change it, and its remote sends the TV's keys. A code on a single setting opens that setting; saving it closes
// the screen. WARP hides the codes: the phone cannot reach the TV while Zephyr TV's traffic goes through the tunnel.

/**
 * The home screen while it is in front. Only this process: the player has a process of its own, and its keys
 * go there as a broadcast (see PlayerKeys).
 */
internal object Screen {
    var activity: Activity? = null
}

/** A name the phone may send. Anything else is ignored. */
internal val REMOTE_COMMANDS = setOf("up", "down", "left", "right", "ok", "back", "home", "vol+", "vol-", "ch+", "ch-")

internal fun remotePanelName(name: String) = name.takeIf { it == "users" || it == "search" || it == "settings" }

internal fun remoteKeyCode(name: String): Int? = when (name) {
    "up" -> KeyEvent.KEYCODE_DPAD_UP
    "down" -> KeyEvent.KEYCODE_DPAD_DOWN
    "left" -> KeyEvent.KEYCODE_DPAD_LEFT
    "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
    "back" -> KeyEvent.KEYCODE_BACK
    "ch+" -> KeyEvent.KEYCODE_CHANNEL_UP
    "ch-" -> KeyEvent.KEYCODE_CHANNEL_DOWN
    else -> null
}

/**
 * Where a sideways move lands in a row of [count] items. [index] null means nothing is focused yet: start at the
 * first item, then take the step. At either end the move stays there.
 */
internal fun remoteRowIndex(index: Int?, count: Int, forward: Boolean): Int {
    if (count <= 0) return 0
    val start = (index ?: 0).coerceIn(0, count - 1)
    return (start + if (forward) 1 else -1).coerceIn(0, count - 1)
}

/** Where an arrow lands in a grid of [columns]. Left and right stay inside the row. Up and down stay inside the grid. */
internal fun remoteGridIndex(index: Int?, count: Int, columns: Int, key: Int): Int {
    if (count <= 0) return 0
    val cols = columns.coerceAtLeast(1)
    val start = (index ?: 0).coerceIn(0, count - 1)
    val rowEnd = minOf(count - 1, start - start % cols + cols - 1)
    return when (key) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (start % cols == 0) start else start - 1
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (start == rowEnd) start else start + 1
        KeyEvent.KEYCODE_DPAD_UP -> if (start < cols) start else start - cols
        KeyEvent.KEYCODE_DPAD_DOWN -> if (start + cols >= count) start else start + cols
        else -> start
    }
}

/** Four digits 0-9: the only PIN the TV can type. Anything else from the phone is refused. */
internal fun isPinDigits(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }

/**
 * The page the phone saw: its title and its row names. The values are left out, so a switch that changes
 * keeps the same page.
 */
internal fun remotePageKey(title: String, labels: List<String>) = (listOf(title) + labels).joinToString("\u0000").hashCode()

/**
 * The row the phone pressed, from "page:index". Null when the TV shows another page now: the phone saw an
 * older one, and the same index is another row there.
 */
internal fun remotePressIndex(body: String, page: Int): Int? {
    val parts = body.trim().split(':')
    if (parts.size != 2 || parts[0].toIntOrNull() != page) return null
    return parts[1].toIntOrNull()?.takeIf { it >= 0 }
}

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

    /** The one phone server (see PhoneServer), while remote access is on. */
    @Volatile private var server: PhoneServer? = null
    val port: Int? get() = server?.port
    @Volatile private var cachedToken: String? = null

    private val sessions = ConcurrentHashMap<String, Long>()
    private val held = ConcurrentHashMap<String, PhoneTask>()
    private val done = ConcurrentHashMap<String, () -> Unit>()
    @Volatile var active: String? = null

    /** Wrong PINs from phones. It lives with the server, so a new home screen keeps the wait. */
    private val pinGuard = PinGuard(MainActivity.PIN_TRIES) { SystemClock.elapsedRealtime() }

    /** Remote access is on, and its page is served. A restart keeps serving it. */
    fun attach(a: MainActivity) {
        home = a
        if (a.remoteOn() && a.remoteHasPin(tell = true)) listen()
    }

    /** The home screen went away. The page keeps its port across a recreate; [stop] is turning the setting off. */
    fun detach(a: MainActivity) {
        if (home === a) home = null
    }

    fun stop() {
        server?.stop()
        server = null
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
        if (server?.running == true) return
        val a = home ?: return
        cachedToken = tokenOf(a)
        server = PhoneServer.start(PORT, "remote-access", ::handle)
    }

    private fun token(): String? = home?.let { tokenOf(it) } ?: cachedToken

    private fun tokenOf(a: MainActivity): String {
        a.prefs.getString(K.REMOTE_TOKEN, null)?.let { return it }
        val t = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        a.prefs.edit().putString(K.REMOTE_TOKEN, t).apply()
        return t
    }

    private fun handle(r: PhoneRequest) {
        if (!isPrivateLan(r.from)) return r.reply(403, TEXT, "This page is only for your home network.")
        val token = token() ?: return r.reply(503, TEXT, "Zephyr TV is starting.")
        val rest = remoteRest(r.path, token) ?: return r.reply(404, TEXT, "Not found.")
        val a = home
        if (a == null || !a.remoteOn()) return r.reply(503, TEXT, "Remote access is off.")
        val authed = authed(r.headers)
        val post = r.method == "POST"
        when {
            rest == "api/pin" && post -> pin(a, r, r.body(32))
            !authed && rest.startsWith("api/") -> r.reply(401, TEXT, a.remotePinPrompt())
            rest == "api/page" && r.method == "GET" -> r.reply(200, JSON, onMain { remoteSnapshot().toString() })
            rest == "api/press" && post -> press(r, r.body(32))
            rest == "api/back" && post -> { onMain { goBack() }; r.reply(200) }
            rest == "api/open" && post -> { onMain { showOnTv { openFromPhone() } }; r.reply(200) }
            rest == "api/panel" && post -> panel(r, r.body(16))
            rest == "api/key" && post -> key(r, r.body(16))
            rest == "api/text" && post -> text(r, r.body(1024L * 1024))
            rest.isEmpty() && r.method == "GET" -> r.reply(200, HTML, if (authed) a.menuHtml() else a.pinHtml())
            rest.startsWith("go/") -> go(a, r, rest.removePrefix("go/"), authed)
            else -> r.reply(404, TEXT, "Not found.")
        }
    }

    private fun pin(a: MainActivity, r: PhoneRequest, body: String?) {
        val error = checkPin(a, body?.trim().orEmpty())
        if (error != null) return r.reply(401, TEXT, error)
        val z = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        sessions[z] = System.currentTimeMillis() + SESSION_MS
        val token = token().orEmpty()
        r.reply(200, TEXT, "", "Set-Cookie: z=$z; HttpOnly; SameSite=Strict; Path=/$token\r\n")
    }

    /** Null when the PIN is right. */
    private fun checkPin(a: MainActivity, pin: String): String? {
        val stored = pinStored(a.prefs) ?: return a.getString(R.string.remote_not_open)
        return a.pinMessage(pinGuard.check { isPinDigits(pin) && pinMatches(stored, pin) })
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

    private fun panel(r: PhoneRequest, body: String?) {
        val which = remotePanelName(body?.trim().orEmpty()) ?: return r.reply(400)
        val a = home ?: return r.reply(503)
        onMain {
            a.showOnTv {
                when (which) {
                    "users" -> a.showUsers()
                    "search" -> a.openSearch(if (a.searchQuery != null) a.lastSearchWord else "")
                    else -> a.openFromPhone()
                }
            }
        }
        r.reply(200)
    }

    private fun key(r: PhoneRequest, body: String?) {
        val name = body?.trim().orEmpty()
        if (name !in REMOTE_COMMANDS) return r.reply(400)
        val a = home ?: return r.reply(503)
        onMain { a.remoteCommand(name) }
        r.reply(200)
    }

    /** A row from the phone. 409 when the TV shows another page now: the phone draws the page again. */
    private fun press(r: PhoneRequest, body: String?) {
        val status = try {
            onMain {
                if (settingsScrim.visibility != View.VISIBLE) return@onMain 409
                val i = remotePressIndex(body.orEmpty(), phonePanel.key) ?: return@onMain 409
                val row = phonePanel.rows.getOrNull(i) ?: return@onMain 409
                row.third.invoke()
                200
            }
        } catch (e: Exception) {
            warn("remote press ($e)")
            503
        }
        r.reply(status)
    }

    private fun text(r: PhoneRequest, body: String?) {
        val answer = onMain { takeText(body.orEmpty()) }
        r.reply(answer.first, TEXT, answer.second)
    }

    /** A single setting's page (see [hold] and remoteTask): its page, and what the phone sends it. */
    private fun go(a: MainActivity, r: PhoneRequest, id: String, authed: Boolean) {
        if (!authed) return if (r.method == "GET") r.reply(200, HTML, a.pinHtml()) else r.reply(401, TEXT, a.remotePinPrompt())
        if (!ID.matches(id)) return r.reply(404, TEXT, "Not found.")
        val task = held[id] ?: a.remoteTask(id) ?: return r.reply(404, TEXT, a.getString(R.string.remote_not_open))
        // Its page with the remote page's bar at the bottom, as everywhere on the phone (see docked).
        if (r.method == "GET") return r.reply(200, HTML, onMain { docked(task.page.html, id) })
        val status = r.serve(task, a.cacheDir)
        if (r.method == "POST" && status in 200..299) succeed(id)
    }

    private fun succeed(id: String) {
        val onDone = done[id] ?: return
        home?.ui?.post { if (done[id] === onDone) onDone() }
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
}

/** Remote access is switched on. */
internal fun MainActivity.remoteOn() = prefs.getBoolean(K.REMOTE, false)

/**
 * Remote access has the PIN to ask for. Without one (cleared data, or a restore) no phone can open it, so remote
 * access turns off, and the TV says so when [tell].
 */
internal fun MainActivity.remoteHasPin(tell: Boolean = false): Boolean {
    if (pinSet()) return true
    if (remoteOn()) {
        prefs.edit().putBoolean(K.REMOTE, false).apply()
        Remote.stop()
        if (tell) toast(R.string.remote_off_no_pin)
    }
    return false
}

/**
 * Rows from Remote access to the next fixed row. Show the address sits under the switch while remote access is on,
 * so Backup and restore and the rows under it move down by one.
 */
internal fun rowsAfterRemote(remoteOn: Boolean) = if (remoteOn) 2 else 1

internal fun MainActivity.remoteShowRow() = SettingsRows.REMOTE_ROW + 1
internal fun MainActivity.backupRow() = SettingsRows.BACKUP_ROW
internal fun MainActivity.problemsRow() = SettingsRows.PROBLEMS_ROW
internal fun MainActivity.guideRow() = SettingsRows.GUIDE_ROW
internal fun MainActivity.aboutRow() = SettingsRows.ABOUT_ROW

/** What the phone asks for: the one PIN (see Pins). */
private fun MainActivity.remotePinPrompt() = getString(R.string.remote_pin_enter)

/**
 * Remote access on, with the one PIN. Turning it on asks for the PIN when one is set, or has a new one chosen
 * (see withPin); once on, it doesn't ask again. [back] leaves the PIN page. [then] runs once remote access is on.
 */
internal fun MainActivity.ensureRemote(back: () -> Unit, then: () -> Unit) {
    fun arm() {
        if (!remoteOn()) prefs.edit().putBoolean(K.REMOTE, true).apply()
        Remote.attach(this)
        then()
    }
    if (remoteOn() && pinSet()) return arm()
    withPin(back) { arm() }
}

internal fun MainActivity.toggleRemote() {
    if (remoteOn()) {
        prefs.edit().putBoolean(K.REMOTE, false).apply()
        Remote.stop()
        dropUnusedPin() // a lock may still use it
        renderSettings(SettingsRows.REMOTE_ROW)
        return
    }
    ensureRemote({ renderSettings(SettingsRows.REMOTE_ROW) }) { renderSettings(SettingsRows.REMOTE_ROW) }
}

/** The address to save: the whole settings page, after the PIN. */
internal fun MainActivity.showRemoteAddress() {
    val back = { renderSettings(if (remoteOn()) remoteShowRow() else SettingsRows.REMOTE_ROW) }
    ensureRemote(back) {
        if (warpOn()) {
            showPage(getString(R.string.setting_remote), listOf(page(getString(R.string.dialog_done)) { back() }), top = warpQrNotice(), back = back)
            return@ensureRemote
        }
        val ip = lanAddress() ?: return@ensureRemote toast(R.string.toast_connect_network)
        val url = Remote.url(ip) ?: return@ensureRemote toast(R.string.toast_upload_server_failed)
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
        val ip = lanAddress() ?: return@ensureRemote toast(R.string.toast_connect_network)
        val root = Remote.url(ip) ?: return@ensureRemote toast(R.string.toast_upload_server_failed)
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
    phonePanel.back?.invoke() ?: closeLauncherSettings()
}

/** Brings the home screen forward when the player is in front, then opens [open] once that screen is up. */
private fun MainActivity.showOnTv(open: () -> Unit) {
    if (Screen.activity === this) {
        open()
        return
    }
    phonePanel.pending = open
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/**
 * One key from the phone's remote. Volume changes the TV's volume. Home brings Zephyr TV forward.
 * The player runs in a process of its own (":player"), so this process never sees it: while the home screen is
 * not in front, a TV key goes to the player first (see [PlayerKeys]). When no player takes it, the home screen does.
 */
private fun MainActivity.remoteCommand(name: String) {
    val code = remoteKeyCode(name)
    if (code != null && Screen.activity !is MainActivity) {
        PlayerKeys.send(this, code) { taken -> if (!taken) homeCommand(name) }
        return
    }
    homeCommand(name)
}

/** A key for the home screen, or for the app in front when Zephyr TV isn't. */
private fun MainActivity.homeCommand(name: String) {
    comeForward()
    when (name) {
        "home" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        "vol+", "vol-" -> {
            val dir = if (name == "vol+") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        "back" -> if (Screen.activity is MainActivity) onBackPressed() else if (HomeOverride.running?.goBack() != true) onBackPressed()
        "ch+", "ch-" -> Unit // no player in front: the home screen has no channel to change
        "up", "down", "left", "right" -> remoteArrow(remoteKeyCode(name) ?: return)
        "ok" -> remoteOk()
        else -> remoteTap(remoteKeyCode(name) ?: return)
    }
}

/**
 * TV keys from the phone to the player, across processes. The broadcast stays inside this app: it names the
 * package, the player registers its receiver only while it is in front and not exported, and older Android
 * versions also need a signature permission. The player sets [TAKEN] as the result.
 */
internal object PlayerKeys {
    const val ACTION = "link.mccoy88f.zephyr.action.PLAYER_KEY"
    const val CODE = "code"
    const val TAKEN = 1

    fun permission(c: Context) = "${c.packageName}.permission.REMOTE_KEYS"

    /** [then] runs on this process's main thread: true when the player took [code]. */
    fun send(c: Context, code: Int, then: (Boolean) -> Unit) {
        val intent = Intent(ACTION).setPackage(c.packageName).putExtra(CODE, code)
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) = then(resultCode == TAKEN)
        }
        c.sendOrderedBroadcast(intent, permission(c), result, null, 0, null, null)
    }

    // Before Android 13 there is no flag for this. The signature permission keeps other apps out instead.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(a: Activity, r: BroadcastReceiver) {
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, filter, permission(a), null, Context.RECEIVER_NOT_EXPORTED)
        else a.registerReceiver(r, filter, permission(a), null)
    }
}

/** Zephyr TV to the front, still on the screen it had open. A Home press is a different path. */
private fun MainActivity.comeForward() {
    val front = Screen.activity
    if (front != null && front.hasWindowFocus()) return
    try {
        getSystemService(android.app.ActivityManager::class.java).appTasks.firstOrNull()?.moveToFront()
    } catch (_: RuntimeException) {}
}

/** A press and a release, as the TV's own remote sends them. */
internal fun Activity.remoteTap(code: Int) {
    val t = SystemClock.uptimeMillis()
    fun event(action: Int, at: Long) = KeyEvent(t, at, action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
    dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, t))
    dispatchKeyEvent(event(KeyEvent.ACTION_UP, t + 40))
}

private fun MainActivity.openFromPhone() {
    unlockedUntil = SystemClock.elapsedRealtime() + MainActivity.UNLOCKED_MS
    openLauncherSettings()
}

private fun MainActivity.takeText(body: String): Pair<Int, String> {
    if (settingsScrim.visibility != View.VISIBLE) return 409 to ""
    phonePanel.pin?.let { pin ->
        val typed = body.trim()
        // The TV types only four digits. A shorter PIN or letters would make a PIN that the TV can never enter.
        if (!isPinDigits(typed)) return 400 to getString(R.string.pin_four_digits)
        val err = pin(typed)
        return if (err == null) 200 to "" else 400 to err
    }
    val field = phonePanel.field ?: return 409 to ""
    field.setText(body.take(1024 * 1024))
    return 200 to ""
}

/** How the phone draws a row: a switch, a value, or a chevron to another page. */
internal fun remoteRowJson(label: String, value: String, on: String, off: String): JSONObject {
    val row = JSONObject().put("label", label)
    return when (value) {
        MainActivity.TOGGLE_ON -> row.put("type", "switch").put("on", true).put("value", on)
        MainActivity.TOGGLE_OFF -> row.put("type", "switch").put("on", false).put("value", off)
        MainActivity.SUBMENU -> row.put("type", "page").put("value", "")
        else -> choiceName(value)?.let { row.put("type", "page").put("value", it) }
            ?: row.put("type", if (value.isEmpty()) "page" else "value").put("value", value)
    }
}

/** What the phone shows: the panel's page, or that settings are closed. */
internal fun MainActivity.remoteSnapshot(): JSONObject {
    val p = phonePanel
    val open = settingsScrim.visibility == View.VISIBLE && p.title.isNotEmpty()
    val rows = JSONArray()
    if (open) {
        val on = getString(R.string.state_on)
        val off = getString(R.string.state_off)
        p.rows.forEach { (name, value) -> rows.put(remoteRowJson(name, value, on, off)) }
    }
    return JSONObject()
        .put("title", if (open) p.title else "")
        .put("closed", !open)
        .put("back", open)
        .put("input", when {
            !open -> ""
            p.pin != null -> "pin"
            p.field != null -> "text"
            else -> ""
        })
        .put("rows", rows)
        .put("page", if (open) p.key else 0)
        .put("form", Remote.active.orEmpty())
        .put("kind", if (open) p.kind.id else "")
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
        val texts = phoneTexts(getString(R.string.setting_tmdb_api_key), getString(R.string.phone_tmdb_intro))
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
        val texts = phoneTexts(getString(R.string.setting_epg), getString(R.string.phone_epg_intro))
        PhoneTask(PhonePages.text(texts, getString(R.string.epg_hint)), 1024L * 1024) { file ->
            val url = file.readText().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@PhoneTask 400 to getString(R.string.phone_epg_bad)
            ui.post { saveEpgUrl(url) }
            200 to getString(R.string.phone_epg_saved)
        }
    }
    "tmdb-list" -> {
        val texts = phoneTexts(getString(R.string.setting_tmdb_list), getString(R.string.phone_list_intro))
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
    // With a PIN set, the backup opens only from the TV, after the PIN there (see backupToPhone).
    "backup-save" -> if (pinSet()) null else PhoneTask(backupPage(), 0) { 405 to "" }
    "backup-restore" -> {
        val page = PhonePages.file(phoneTexts(R.string.phone_restore_title, R.string.phone_restore_intro), getString(R.string.phone_choose_file), ".json,application/json")
        PhoneTask(page, 16L * 1024 * 1024) { file -> restoreBackup(file.readText()) }
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
            val url = Stremio.manifestUrl(file.readText().lines().firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_addon_bad)
            ui.post { addCatalogUrl(url) }
            200 to getString(R.string.phone_addon_saved)
        }
    }
    "addon-add" -> {
        val page = PhonePages.addon(phoneTexts(R.string.phone_addon_title, R.string.phone_addon_intro), getString(R.string.addon_hint), getString(R.string.addon_adult))
        PhoneTask(page, 1024L * 1024) { file ->
            val lines = file.readText().lines()
            val url = Stremio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_addon_bad)
            val adult = lines.drop(1).any { it.trim() == "adult" }
            ui.post { addAddon(url, adult) }
            200 to getString(R.string.phone_addon_saved)
        }
    }
    "nuvio-add" -> {
        val page = PhonePages.addon(phoneTexts(R.string.nuvio_phone_title, R.string.nuvio_add_intro), getString(R.string.nuvio_hint), getString(R.string.addon_adult))
        PhoneTask(page, 1024L * 1024) { file ->
            val lines = file.readText().lines()
            val url = Nuvio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_addon_bad)
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




/** The settings button on the home screen (ic_tune). */
private const val TUNE = """<svg class="tune" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"/></svg>"""

/** The users button on the home screen (ic_profile), first of the top bar. */
private const val PROFILE = """<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,5c1.66,0 3,1.34 3,3s-1.34,3 -3,3 -3,-1.34 -3,-3 1.34,-3 3,-3zM12,19.2c-2.5,0 -4.71,-1.28 -6,-3.22 0.03,-1.99 4,-3.08 6,-3.08 1.99,0 5.97,1.09 6,3.08 -1.29,1.94 -3.5,3.22 -6,3.22z"/></svg>"""

/** The search button, next to the users button. */
private const val SEARCH = """<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M15.5,14h-0.79l-0.28,-0.27A6.47,6.47 0 0 0 16,9.5 6.5,6.5 0 1 0 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"/></svg>"""

/** A handset, for the remote page. */
private const val HANDSET = """<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2" width="10" height="20" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="7.5" r="1.3" fill="currentColor"/><circle cx="12" cy="13" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>"""

/** The bottom bar's look, on the remote page and on the pages it opens (see docked). */
private const val DOCK_CSS = """
      .dock{position:sticky;bottom:0;display:flex;align-items:center;gap:8px;margin-top:auto;padding:12px 16px calc(12px + env(safe-area-inset-bottom));background:var(--panel);border-top:1px solid var(--line)}
      body.glass .dock{background:rgba(16,16,20,.92)}
      .dock .side{display:flex;align-items:center;gap:8px;flex:1;min-width:0}
      .dock .side.end{justify-content:flex-end}
      .dock .device{flex:none;max-width:42%;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:center;font-size:14px;color:var(--muted)}
      .dock .ico{display:block;flex:none;width:44px;height:44px;margin:0;padding:10px;border:1px solid var(--line);border-radius:8px;background:transparent;color:var(--fg)}
      .dock .ico svg{width:100%;height:100%;display:block}
      .dock .ico.on{background:var(--fill);color:var(--on);border-color:transparent}
"""

/**
 * The bar at the bottom of the remote page: users and search, the TV's name, the menu and the remote. [link]: as
 * links back to the remote page, which opens what was picked (from a page of its own, see docked); [on] is lit.
 */
private fun MainActivity.remoteDock(link: Boolean = false, on: String? = null): String {
    fun item(id: String, label: Int, icon: String): String {
        val cls = if (id == on) "ico on" else "ico"
        val name = escHtml(getString(label))
        return if (link) """<a id="$id" class="$cls" href="../#$id" aria-label="$name">$icon</a>"""
        else """<button id="$id" class="$cls" type="button" aria-label="$name">$icon</button>"""
    }
    return """<nav class="dock"><div class="side">${item("users", R.string.users_title, PROFILE)}${item("search", R.string.search_title, SEARCH)}</div>""" +
        """<p class="device">${escHtml(tvName())}</p><div class="side end">${item("open", R.string.remote_open, TUNE)}${item("remote", R.string.remote_pad, HANDSET)}</div></nav>"""
}

/**
 * [html], a page the remote page opens (the search's, a key's, a form's: see Remote.go), with the remote page's bar at
 * the bottom, as on the remote page: its buttons go back there. [id] is the page's screen: the search lights search.
 */
internal fun MainActivity.docked(html: String, id: String): String {
    val at = html.lastIndexOf("</body>")
    if (at < 0) return html
    val style = "<style>$DOCK_CSS .dock{position:fixed;left:0;right:0;max-width:420px;margin:0 auto} .sheet{padding-bottom:96px}</style>"
    return html.substring(0, at) + style + remoteDock(link = true, on = if (id == "search") "search" else "open") + html.substring(at)
}

/**
 * The phone page in the TV's theme. [body] is the page. [dock] is the bar at the bottom. The logo and the app name
 * stay at the top. The device name sits in the center of the bar.
 */
private fun MainActivity.remotePage(body: String, dock: String, script: String) = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${escHtml(getString(R.string.app_name))}</title><style>
      ${PhoneStyle.THEMES}
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
      .send,button.back{display:block;width:100%;margin-top:8px;padding:14px;border:0;border-radius:8px;background:var(--fill);color:var(--on);font:500 15px system-ui,sans-serif}
      button.back{margin:0 0 8px;background:transparent;color:inherit;border:1px solid var(--line)}
      button.back[hidden]{display:none}
      #pad{display:flex;flex-direction:column;gap:8px;padding-top:8px}
      #pad[hidden],#panel[hidden]{display:none}
      .dpad{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;width:min(100%,280px);margin:0 auto}
      .dpad button,.pair button{min-height:64px;border:0;border-radius:8px;background:var(--field);color:inherit;font:600 20px system-ui,sans-serif}
      .dpad .up{grid-column:2;grid-row:1} .dpad .left{grid-column:1;grid-row:2} .dpad .ok{grid-column:2;grid-row:2;background:var(--fill);color:var(--on)}
      .dpad .right{grid-column:3;grid-row:2} .dpad .down{grid-column:2;grid-row:3}
      .pair{display:flex;gap:8px} .pair button{flex:1;min-height:52px;font-size:16px}
      .group{margin:10px 0 0;font-size:13px;color:var(--muted);text-align:center}
      .dpad button:active,.pair button:active{background:var(--fg);color:var(--on)}
      .padError{min-height:1.4em;margin:8px 0 0;font-size:14px;text-align:center}
      $DOCK_CSS
    </style></head><body class="${phoneTheme()}">
    <div class="sheet">
    <header>${PhoneStyle.LOGO}<h1>${escHtml(getString(R.string.app_name))}</h1></header>
    <main>$body</main>
    $dock
    </div>
    <script>$script</script></body></html>
""".trimIndent()

private fun MainActivity.pinHtml(): String {
    val lost = escJs(getString(R.string.phone_unreachable))
    return remotePage("""
        <h2>$TUNE<span>${escHtml(getString(R.string.remote_menu_title))}</span></h2>
        <p class="note">${escHtml(remotePinPrompt())}</p>
        <input id="p" inputmode="numeric" autocomplete="off" maxlength="4" type="password">
        <button id="b" class="send" type="button">${escHtml(getString(R.string.phone_send))}</button>
        <div id="e"></div>
    """.trimIndent(), "", """
        const root=location.pathname.replace(/\/go\/[^/]*$/, '').replace(/\/?$/, '/');
        const e=document.getElementById('e'), p=document.getElementById('p');
        const b=document.getElementById('b');
        p.addEventListener('keydown', function(ev){if(ev.key==='Enter'){ev.preventDefault(); b.click();}});
        b.onclick=async()=>{
          e.textContent='';
          try{
            const r=await fetch(root+'api/pin',{method:'POST',body:p.value});
            if(r.ok) location.reload(); else e.textContent=await r.text();
          }catch(x){e.textContent='$lost';}
        };
    """.trimIndent())
}


private fun MainActivity.menuHtml(): String {
    val send = escJs(getString(R.string.phone_send))
    val form = escJs(getString(R.string.remote_open_form))
    val lost = escJs(getString(R.string.phone_unreachable))
    val up = escHtml(getString(R.string.remote_up))
    val down = escHtml(getString(R.string.remote_down))
    val left = escHtml(getString(R.string.remote_left))
    val right = escHtml(getString(R.string.remote_right))
    val dock = remoteDock()
    return remotePage("""
        <div id="panel">
        <h2 id="heading">$TUNE<span id="title"></span></h2>
        <p id="closed" class="note" hidden>${escHtml(getString(R.string.remote_closed))}</p>
        <button id="back" class="back" type="button" hidden>${escHtml(getString(R.string.remote_back))}</button>
        <a id="form" class="form" hidden href=""></a>
        <div id="rows"></div>
        <div id="extra"></div>
        <div id="e"></div>
        </div>
        <div id="pad" hidden>
        <div class="dpad">
          <button class="up" type="button" data-k="up" aria-label="$up">▲</button>
          <button class="left" type="button" data-k="left" aria-label="$left">◀</button>
          <button class="ok" type="button" data-k="ok">${escHtml(getString(R.string.remote_ok))}</button>
          <button class="right" type="button" data-k="right" aria-label="$right">▶</button>
          <button class="down" type="button" data-k="down" aria-label="$down">▼</button>
        </div>
        <div class="pair">
          <button type="button" data-k="back">${escHtml(getString(R.string.remote_back))}</button>
          <button type="button" data-k="home">${escHtml(getString(R.string.remote_home_key))}</button>
        </div>
        <p class="group">${escHtml(getString(R.string.remote_volume))}</p>
        <div class="pair"><button type="button" data-k="vol-">−</button><button type="button" data-k="vol+">+</button></div>
        <p class="group">${escHtml(getString(R.string.remote_program))}</p>
        <div class="pair"><button type="button" data-k="ch-">−</button><button type="button" data-k="ch+">+</button></div>
        <div id="pe" class="padError"></div>
        </div>
    """.trimIndent(), dock, """
        const root=location.pathname.replace(/\/go\/[^/]*$/, '').replace(/\/?$/, '/');
        const title=document.getElementById('title'), heading=document.getElementById('heading'), rows=document.getElementById('rows'), back=document.getElementById('back');
        const closed=document.getElementById('closed'), extra=document.getElementById('extra'), form=document.getElementById('form'), e=document.getElementById('e');
        const pe=document.getElementById('pe');
        let last='', err='', kind='', page=0;
        function lost(){e.textContent='$lost'; pe.textContent='$lost';}
        async function api(path, body){
          const r=await fetch(root+path,{method:body==null?'GET':'POST',body:body==null?undefined:body});
          if(r.status==401){location.reload();return null;}
          return r;
        }
        async function act(path, body){
          e.textContent='';
          try{
            const r=await api(path, body==null?'':body); if(!r)return;
            pe.textContent='';
            err=(r.ok||r.status==409)?'':await r.text(); last=''; await draw();
          }
          catch(x){lost();}
        }
        async function draw(){
          let r; try{r=await api('api/page');}catch(x){lost();return;}
          if(!r)return;
          if(!r.ok){lost();return;}
          pe.textContent='';
          const p=await r.json();
          page=p.page||0;
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
            if(row.type==='switch'){
              const s=document.createElement('span'); s.className='sw'+(row.on?' on':''); b.appendChild(s);
              b.setAttribute('aria-pressed', row.on?'true':'false');
            }else if(row.type==='value'){
              const v=document.createElement('span'); v.className='val'; v.textContent=row.value; b.appendChild(v);
            }else{
              const c=document.createElement('span'); c.className='chev'; b.appendChild(c);
            }
            b.onclick=function(){act('api/press', page+':'+i);};
            rows.appendChild(b);
          });
          const mark=(p.input||'')+'\n'+(p.title||'');
          if(mark!==kind){
            kind=mark; while(extra.firstChild) extra.removeChild(extra.firstChild);
            if(p.input==='text'||p.input==='pin'){
              const x=document.createElement(p.input==='pin'?'input':'textarea');
              if(p.input==='pin'){x.type='password'; x.inputMode='numeric'; x.maxLength=4;}
              const s=document.createElement('button'); s.className='send'; s.type='button'; s.textContent='$send';
              s.onclick=function(){const v=x.value; if(p.input==='pin') x.value=''; act('api/text', v);};
              if(p.input==='pin') x.addEventListener('keydown', function(ev){if(ev.key==='Enter'){ev.preventDefault(); s.click();}});
              extra.appendChild(x); extra.appendChild(s);
            }
          }
          e.textContent=err;
          if(pad.hidden) choose(p.kind==='users'?'users':p.kind==='search'?'search':p.title?'open':'');
        }
        const panel=document.getElementById('panel'), pad=document.getElementById('pad');
        function choose(id){['users','search','open','remote'].forEach(function(x){document.getElementById(x).classList.toggle('on', x===id);});}
        function showPanel(){panel.hidden=false; pad.hidden=true;}
        function home(){showPanel(); choose(''); act('api/key','home');}
        function dock(id, open){
          document.getElementById(id).onclick=function(){
            if(document.getElementById(id).classList.contains('on')) home(); else open();
          };
        }
        dock('users', function(){showPanel(); act('api/panel','users');});
        dock('search', function(){showPanel(); act('api/panel','search');});
        dock('open', function(){showPanel(); act('api/open');});
        // The remote again closes the remote and shows the panel. It sends no key: the TV stays where it is.
        document.getElementById('remote').onclick=function(){
          if(!pad.hidden){showPanel(); last=''; draw(); return;}
          panel.hidden=true; pad.hidden=false; choose('remote');
        };
        pad.querySelectorAll('button').forEach(function(b){b.onclick=function(){act('api/key', b.dataset.k);};});
        back.onclick=function(){act('api/back');};
        // One request at a time, and none while the page is hidden or the remote is open: requests that pile up
        // hold the TV's few workers, and the keys then wait behind them.
        async function tick(){
          if(!document.hidden && pad.hidden){try{await draw();}catch(x){}}
          setTimeout(tick, 800);
        }
        document.addEventListener('visibilitychange', function(){if(!document.hidden){last=''; draw();}});
        // Back from a page of its own (the search's, a form's): what its bar's button asked for, opened here.
        const asked=location.hash.slice(1);
        if(['users','search','open','remote'].indexOf(asked)>=0){history.replaceState(null,'',location.pathname+location.search); document.getElementById(asked).click();}
        tick();
    """.trimIndent())
}
