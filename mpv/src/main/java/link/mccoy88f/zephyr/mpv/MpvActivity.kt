package link.mccoy88f.zephyr.mpv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.jdtech.mpv.MPVLib
import java.util.Locale

/**
 * Plays one stream with mpv, for Zephyr TV (its Mpv): what it asks ([URL], [HEADERS], [AUDIO], from [POSITION]), and
 * back with how far it got ([POSITION], [DURATION], [ENDED]), or [FAILED] when it couldn't be opened ([ERROR]).
 * Back before it opened: RESULT_CANCELED, the user leaving.
 *
 * The remote: OK (play/pause) pauses and plays, Left and Right go back and forward (further while held), Up and Down
 * go through the audio and subtitle tracks, Back leaves.
 */
class MpvActivity : Activity(), MPVLib.EventObserver, MPVLib.LogObserver, SurfaceHolder.Callback {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var bar: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private lateinit var time: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var note: TextView
    private var url = ""

    private var created = false // mpv's core made and started (create, options, init)
    private var asked = false // the file asked for (loadfile), once there was a surface for it
    private var loaded = false // the file opened: its end from now on is an end, not a failure
    private var done = false // the result set, the activity finishing
    private var position = 0.0 // seconds
    private var duration = 0.0
    private var paused = false
    private var buffering = true
    private var eof = false
    @Volatile private var lastError: String? = null // mpv's last error line: why it couldn't open the file

    private val hideBar = Runnable { if (!paused) bar.visibility = View.GONE }
    private val hideNote = Runnable { note.visibility = View.GONE }
    private val tooSlow = Runnable { if (!loaded) fail(lastError ?: "not opened in ${OPEN_MS / 1000} s") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val address = intent.getStringExtra(URL)
        if (!fromZephyr()) return fail("not opened by Zephyr TV")
        if (address.isNullOrBlank()) return fail("no address")
        url = address
        build()
        try {
            start()
        } catch (e: Throwable) { // its libraries not loaded (a device it wasn't built for), mpv not starting
            return fail("mpv didn't start: $e")
        }
        surface.holder.addCallback(this)
        ui.postDelayed(tooSlow, OPEN_MS)
    }

    /** Opened by Zephyr TV (release or debug), signed with this app's key: only it hands its streams' headers here. */
    private fun fromZephyr(): Boolean {
        val caller = callingPackage ?: return false // not opened for a result: not Zephyr TV
        if (caller != ZEPHYR && caller != "$ZEPHYR.debug") return false
        return packageManager.checkSignatures(caller, packageName) == PackageManager.SIGNATURE_MATCH
    }

    /** mpv's core, set up for a TV: hardware decoding, Android's audio, a modest cache, the TV's languages first. */
    private fun start() {
        MPVLib.create(applicationContext)
        fun opt(name: String, value: String) {
            if (MPVLib.setOptionString(name, value) < 0) Log.w(TAG, "option not taken: $name")
        }
        opt("config", "no")
        opt("vo", "gpu")
        opt("gpu-context", "android")
        opt("opengl-es", "yes")
        opt("hwdec", "mediacodec-copy")
        opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        opt("ao", "audiotrack,opensles")
        opt("cache", "yes")
        opt("demuxer-max-bytes", "48MiB") // TVs have little memory
        opt("demuxer-max-back-bytes", "16MiB")
        opt("network-timeout", "30")
        opt("force-window", "no")
        opt("idle", "yes")
        opt("keep-open", "no")
        opt("input-default-bindings", "no")
        opt("osd-level", "0") // this activity draws its own
        opt("save-position-on-quit", "no")
        // The TV's language first, then English; subtitles in the language spoken only when forced (a foreign line).
        val langs = languages()
        opt("alang", langs)
        opt("slang", langs)
        opt("subs-with-matching-audio", "no")
        for (h in intent.getStringArrayExtra(HEADERS).orEmpty()) {
            val name = h.substringBefore(':').trim()
            val value = h.substringAfter(':', "").trim()
            if (name.isEmpty()) continue
            if (name.equals("User-Agent", ignoreCase = true)) opt("user-agent", value)
            else opt("http-header-fields-append", "$name: $value") // one at a time: a value may hold commas
        }
        intent.getStringExtra(AUDIO)?.takeIf { it.isNotBlank() }?.let { opt("audio-files-append", it) }
        val from = intent.getLongExtra(POSITION, 0L)
        if (from > 0) opt("start", String.format(Locale.US, "%.3f", from / 1000.0))
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        created = true
    }

    /** "it,ita,en,eng": the TV's language as tracks name it (two letters, three, and the older three of some). */
    private fun languages(): String {
        val l = Locale.getDefault()
        val older = mapOf("de" to "ger", "fr" to "fre", "zh" to "chi", "nl" to "dut", "cs" to "cze", "el" to "gre", "ro" to "rum")
        val own = listOfNotNull(l.language, runCatching { l.getISO3Language() }.getOrNull(), older[l.language])
        return (own + listOf("en", "eng")).filter { it.isNotEmpty() }.distinct().joinToString(",")
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        note = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xB3000000.toInt()) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        root.addView(note, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(32), dp(32), dp(32), dp(32))
        })
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(48), dp(48), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = intent.getStringExtra(TITLE).orEmpty()
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        time = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 15f }
        bar.addView(title)
        bar.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        bar.addView(time)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        showBar()
    }

    // ---------- the surface ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!created) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        if (!asked) {
            asked = true
            MPVLib.command(arrayOf("loadfile", url))
        } else {
            MPVLib.setPropertyString("vo", "gpu") // back after the surface went (the TV's screen off and on)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (created) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!created) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    // ---------- mpv's news (from its thread) ----------

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {
        ui.post {
            when (property) {
                "time-pos" -> position = value
                "duration" -> duration = value
            }
            drawTime()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        ui.post {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> buffering = value
                "eof-reached" -> eof = value
            }
            drawState()
        }
    }

    override fun event(eventId: Int) {
        ui.post {
            when (eventId) {
                MPVLib.MPV_EVENT_FILE_LOADED -> {
                    loaded = true
                    ui.removeCallbacks(tooSlow)
                }
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> buffering = false
                MPVLib.MPV_EVENT_END_FILE -> fileEnded()
            }
            drawState()
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = "$prefix: ${text.trim()}"
        when {
            level <= MPVLib.MPV_LOG_LEVEL_ERROR -> { lastError = line; Log.e(TAG, line) }
            level <= MPVLib.MPV_LOG_LEVEL_WARN -> Log.w(TAG, line)
        }
    }

    /** The file over: never opened (a failure), or played to its end, or cut short (the network) where it got to. */
    private fun fileEnded() {
        if (done) return
        if (!loaded) return fail(lastError ?: "the file didn't open")
        finishWith(ended = eof || (duration > 0 && duration - position < END_S))
    }

    // ---------- the remote ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!created || done) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.repeatCount == 0) MPVLib.command(arrayOf("cycle", "pause"))
                showBar()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { MPVLib.setPropertyBoolean("pause", false); showBar() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { MPVLib.setPropertyBoolean("pause", true); showBar() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-step(event))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(step(event))
            KeyEvent.KEYCODE_DPAD_UP -> if (event.repeatCount == 0) nextTrack(audio = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (event.repeatCount == 0) nextTrack(audio = false)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> leave()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Seconds a seek jumps: further the longer the key is held. */
    private fun step(e: KeyEvent) = when {
        e.repeatCount > 20 -> 120
        e.repeatCount > 6 -> 60
        e.repeatCount > 0 -> 30
        else -> 10
    }

    private fun seek(seconds: Int) {
        MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
        showBar()
    }

    /** The next audio track (Up) or subtitle track, then off (Down), told at the top. */
    private fun nextTrack(audio: Boolean) {
        MPVLib.command(arrayOf("cycle", if (audio) "aid" else "sid"))
        ui.postDelayed({ if (!done) tellTrack(audio) }, 250)
    }

    private fun tellTrack(audio: Boolean) {
        val type = if (audio) "audio" else "sub"
        val id = MPVLib.getPropertyString(if (audio) "aid" else "sid")
        if (id == null || id == "no") {
            return note(if (audio) getString(R.string.mpv_audio, "—") else getString(R.string.mpv_subtitles_off))
        }
        val about = listOfNotNull(
            MPVLib.getPropertyString("current-tracks/$type/lang")?.uppercase(Locale.ROOT),
            MPVLib.getPropertyString("current-tracks/$type/title"),
            MPVLib.getPropertyString("current-tracks/$type/codec")?.uppercase(Locale.ROOT),
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { getString(R.string.mpv_track, id) }
        note(getString(if (audio) R.string.mpv_audio else R.string.mpv_subtitles, about))
    }

    // ---------- what's drawn ----------

    private fun showBar() {
        bar.visibility = View.VISIBLE
        ui.removeCallbacks(hideBar)
        if (!paused) ui.postDelayed(hideBar, BAR_MS)
    }

    private fun drawTime() {
        if (duration > 0) progress.progress = (position / duration * 1000).toInt().coerceIn(0, 1000)
        progress.visibility = if (duration > 0) View.VISIBLE else View.INVISIBLE // live: no end to measure against
        time.text = if (duration > 0) "${clock(position)} / ${clock(duration)}" else clock(position)
    }

    private fun drawState() {
        spinner.visibility = if (!loaded || buffering) View.VISIBLE else View.GONE
        if (paused) {
            bar.visibility = View.VISIBLE
            ui.removeCallbacks(hideBar)
        } else if (bar.visibility == View.VISIBLE) {
            ui.removeCallbacks(hideBar)
            ui.postDelayed(hideBar, BAR_MS)
        }
    }

    private fun note(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
        ui.removeCallbacks(hideNote)
        ui.postDelayed(hideNote, NOTE_MS)
    }

    private fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- leaving ----------

    /** Back: where it got to, for Zephyr TV to keep; before it opened, just leaving (RESULT_CANCELED). */
    private fun leave() {
        if (done) return
        if (!loaded) {
            done = true
            setResult(RESULT_CANCELED)
            return finish()
        }
        finishWith(ended = false)
    }

    private fun finishWith(ended: Boolean) {
        done = true
        setResult(RESULT_OK, Intent()
            .putExtra(POSITION, (position * 1000).toLong())
            .putExtra(DURATION, (duration * 1000).toLong())
            .putExtra(ENDED, ended))
        finish()
    }

    private fun fail(why: String) {
        if (done) return
        done = true
        Log.w(TAG, "can't play: $why")
        setResult(FAILED, Intent().putExtra(ERROR, why))
        finish()
    }

    // Home, or another app over it: what Zephyr TV does with its own player (it leaves, keeping where it got to).
    override fun onStop() {
        super.onStop()
        if (!done && !isChangingConfigurations) leave()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (created) {
            created = false
            MPVLib.removeObserver(this)
            MPVLib.removeLogObserver(this)
            MPVLib.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZephyrMPV"
        private const val ZEPHYR = "link.mccoy88f.zephyr"

        // What Zephyr TV hands over and gets back: the same names as its Mpv's.
        const val URL = "url"
        const val TITLE = "title"
        const val HEADERS = "headers" // "Name: value", one each
        const val AUDIO = "audio" // a second address with the sound (video-only streams)
        const val POSITION = "position" // ms: where to start; back: where it got to
        const val DURATION = "duration" // ms, back
        const val ENDED = "ended" // back: played to its end
        const val ERROR = "error" // back with FAILED: why
        const val FAILED = RESULT_FIRST_USER

        private const val OPEN_MS = 45_000L // a file not open by then won't be
        private const val BAR_MS = 4_000L
        private const val NOTE_MS = 3_000L
        private const val END_S = 3.0 // this close to the end, it's played to its end
    }
}
