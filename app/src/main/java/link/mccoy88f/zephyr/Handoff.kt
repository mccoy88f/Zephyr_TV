package link.mccoy88f.zephyr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Send to: the film or episode playing handed to another device of the same Zephyr TV account, which plays it there
// from where it got to. The player lists the account's devices that are on (see the site's api/v1/devices) and sends
// the title, its stream and its position (api/v1/devices/<id>/send). Every Zephyr TV on its home screen keeps a
// request open on the site (api/v1/handoffs/wait), answered as soon as something is sent to it: it says so for five
// seconds, Watch or Cancel, then plays it (see offerHandoff).

internal object Handoff {
    /** One of the account's devices: its name, whether it's on now, whether it's this one. */
    class Device(val id: String, val name: String, val model: String?, val online: Boolean, val me: Boolean) {
        /** Zephyr TV Receiver (webos/): a TV that only plays what's sent, with its own player: no torrents, no search. */
        val receiver get() = model?.contains("Receiver") == true

        /** The Receiver on VIDAA: a page of the site, no service on the TV to add a stream's headers. */
        val noHeaders get() = receiver && model?.startsWith("VIDAA") == true
    }

    /** What a device was sent: from which device (its name), and what to play. */
    class Sent(val from: String, val what: What)

    /**
     * What to play: the title ([type], [meta]: its id), the film or episode ([video]: its id, the film's own for a film),
     * its [name] and [poster] to say what it is, the [stream] it played (as PlayerActivity.streamJson writes it; none
     * for a downloaded file, which the other device doesn't have) and where it got to ([position], in ms).
     */
    class What(val type: String, val meta: String, val video: String, val name: String, val poster: String?,
               val stream: String?, val position: Long) {
        fun json(): JSONObject = JSONObject().put("v", 1).put("type", type).put("meta", meta).put("video", video).put("name", name)
            .put("poster", poster ?: JSONObject.NULL).put("stream", stream?.let { JSONObject(it) } ?: JSONObject.NULL).put("position", position)

        companion object {
            fun of(o: JSONObject): What? {
                fun text(k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotBlank() }
                return What(text("type") ?: return null, text("meta") ?: return null, text("video") ?: text("meta")!!,
                    text("name").orEmpty(), text("poster"), o.optJSONObject("stream")?.toString(), o.optLong("position").coerceAtLeast(0))
            }
        }
    }

    private const val WAIT_READ_MS = 40_000 // the site answers a wait within 25 s: a little more, for the network

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    /** This device's token on the site, when it's linked to an account (see ZtvAccount); any process reads it. */
    fun token(c: Context): String? =
        c.getSharedPreferences(K.FILE, Context.MODE_MULTI_PROCESS).getString(K.ZTV_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** The account's devices. Throws offline; ZtvAccount.Unlinked when the token no longer works. */
    fun devices(token: String): List<Device> {
        val (code, body) = Http.send("$SITE/api/v1/devices", null, auth(token), "GET")
        if (code == 401) throw ZtvAccount.Unlinked()
        if (code !in 200..299) throw java.io.IOException("devices: $code")
        val a = JSONObject(body).optJSONArray("devices") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { d ->
            Device(d.optString("id"), d.optString("name"), d.optString("model").takeIf { !d.isNull("model") && it.isNotBlank() },
                d.optBoolean("online"), d.optBoolean("me"))
        }
    }

    /** [what] sent to the device [id]: whether the site took it. */
    fun send(token: String, id: String, what: What): Boolean =
        runCatching { Http.send("$SITE/api/v1/devices/$id/send", what.json(), auth(token)).first in 200..299 }
            .onFailure { warn("send to: $it") }.getOrDefault(false)

    /** What's sent to this device: as soon as it is, or nothing after a while. Throws offline; Unlinked unlinked. */
    fun wait(token: String): List<Sent> {
        val (code, body) = Http.send("$SITE/api/v1/handoffs/wait", null, auth(token), "GET", timeout = WAIT_READ_MS)
        if (code == 401) throw ZtvAccount.Unlinked()
        if (code == 204 || body.isBlank()) return emptyList()
        if (code !in 200..299) throw java.io.IOException("wait: $code")
        val a = JSONObject(body).optJSONArray("handoffs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { h ->
            What.of(h.optJSONObject("data") ?: return@mapNotNull null)?.let { Sent(h.optString("from"), it) }
        }
    }
}

/** Whether this home screen waits for what's sent to it (see listenForHandoffs): while it's on screen. */
@Volatile private var listening = false

/** The thread that waits, while there's one. */
@Volatile private var listener: Thread? = null

/**
 * The home screen on screen, linked to an account: it waits for what the account's other devices send it, one wait
 * after the other, and says each (see offerHandoff). Offline, it tries again in a while; unlinked, it stops.
 */
internal fun MainActivity.listenForHandoffs() {
    listening = true
    if (listener?.isAlive == true) return
    listener = Thread({
        while (listening) {
            val token = Handoff.token(this) ?: break
            try {
                Handoff.wait(token).forEach { sent -> ui.post { offerHandoff(sent) } }
            } catch (e: ZtvAccount.Unlinked) {
                break
            } catch (e: Exception) {
                runCatching { Thread.sleep(15_000) } // offline, or the site down: again in a while
            }
        }
        listener = null
    }, "handoff-wait").apply { isDaemon = true }
    listener?.start()
}

/** Off screen: no more waits (the one under way ends by itself, within half a minute). */
internal fun stopHandoffs() {
    listening = false
}

/** Seconds a sent title waits for Cancel before it plays. */
private const val HANDOFF_SECONDS = 5

/**
 * What another device sent: said on a page of its own ("From Phone: Film (2024), from 12:34"), Watch now (in 5 s) or
 * Cancel; nothing pressed, it plays once the seconds are up. Back cancels too.
 */
internal fun MainActivity.offerHandoff(sent: Handoff.Sent) {
    val w = sent.what
    val title = getString(R.string.handoff_title, sent.from.ifBlank { getString(R.string.handoff_device) })
    var left = HANDOFF_SECONDS
    var done = false
    fun go() {
        if (done) return
        done = true
        closeLauncherSettings()
        playSent(w)
    }
    fun cancel() {
        done = true
        closeLauncherSettings()
    }
    fun draw() {
        if (done) return
        val at = if (w.position > 0) getString(R.string.handoff_from, clockOf(w.position)) else null
        val about = label(listOfNotNull(w.name, at).joinToString("\n"), 14f).apply {
            maxLines = 4
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }
        showPage(title, listOf(
            page(getString(R.string.handoff_watch, left)) { go() },
            page(getString(R.string.dialog_cancel)) { cancel() },
        ), focus = 0, top = about) { cancel() }
    }
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    draw()
    fun tick() {
        ui.postDelayed({
            if (done) return@postDelayed
            if (!pageShowing(title)) { done = true; return@postDelayed } // left for another page: as if cancelled
            if (--left <= 0) go() else { draw(); tick() }
        }, 1000)
    }
    tick()
}

/** 1:02:03, or 12:34: a position as the player says it. */
private fun clockOf(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
