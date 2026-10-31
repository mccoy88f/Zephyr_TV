package link.mccoy88f.zephyr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Zephyr TV's player, for the Stremio add-ons' TV channels (see Addons). It gets the channels of the row the user
 * picked from, plays the one picked and zaps through the others: CH+/CH- (never Up/Down), the number keys, Last
 * channel. For each it asks the add-on for its streams and plays the first that works; Left/Right go to the
 * channel's other streams, and one that fails or stays stuck gives way to the next by itself (round the list, until
 * each was tried). OK or Info shows what's on, in short; OK again shows all of it (the add-on's guide, else the one
 * set in Zephyr TV settings). Up/Down show the banner, and with it up reach its buttons, as Left/Right do: Play/Pause, then Audio, Subtitles and Stream, each opening its list to pick from (holding OK, or Menu, opens audio and subtitles together). The audio and subtitles
 * picked are kept by language, for the next channels and the next time.
 *
 * Films and episodes (MODE "vod", see Titles) play the same way, their streams asked of every stream add-on at once
 * and the first that works played: Left/Right then go back and on 10 seconds (more while held), CH+/CH- to the
 * episode before and after, and the Stream button to another stream. It resumes where it was left (VodProgress),
 * and an episode that ends goes on to the next, on the same kind of stream when there's one (its bingeGroup).
 *
 * Media3's ExoPlayer plays them, the one library Zephyr TV ships, for HLS and DASH as the TV's own player doesn't do
 * reliably. It runs in a process of its own (":player" in the manifest), so a stream or a decoder that hangs or
 * crashes never takes the home screen with it, and all its memory goes back when it closes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    /** One channel of the list: what to ask the add-on for, its name, logos (the add-on's, then Zephyr TV's) and what's on. */
    private class Channel(val type: String, val id: String, val name: String, val logos: List<String>, val now: String?)

    private val net = Executors.newSingleThreadExecutor() // streams of the channel being tuned, and their checks
    private val ahead = Executors.newSingleThreadExecutor() // the next and previous channels' streams, and pages
    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var base = ""
    private var addon = ""
    private var channels: List<Channel> = emptyList()
    private var at = 0 // the channel playing (or being tuned)
    private var quietZap = false
    private var shown = 0 // the channel the banner shows: ahead of [at] while zapping
    private var previous = -1 // for Last channel
    private var streams: List<Stremio.Stream> = emptyList()
    private var tried = -1 // the stream playing, or being tried
    private var playing: Attempt? = null // how the stream being tried is played (see start), to start it again so
    private var why: Stremio.Why? = null // why the last stream tried didn't play: told when none does
    private var attempts = 0 // streams tried since the channel was tuned, or one was picked: all of them, and it's none
    private var lateLeft = 0 // a film's sources still looking after the first streams came (slow Nuvio plugins)
    private var waitingLate = false // all the streams so far failed: waiting for theirs rather than saying none works
    private var searchIfChosenFails = false // a resumed film's kept stream failing: look for others (see next)
    private var holdForLanguage = false // none in the TV's language so far: waiting for the sources still looking
    private var expanded = false // the banner shows all of what's on, not just its first line
    private var tune = 0 // counts tunings, so an answer for a channel left meanwhile is dropped
    private var typed = "" // number keys pressed so far
    private var vod = false // films and episodes (see MODE): seeking, resuming, the next episode; else live channels
    private var adult = false // whether this content is from an adult add-on (no history or continue watching)
    private var adultSources: Set<String> = emptySet()
    private var sources: List<Pair<String, String>> = emptyList() // films' and episodes' stream add-ons: address and name, in order
    private var descs: List<String> = emptyList() // what each film or episode is about (OK on the banner shows it)
    private var pendingSeek = -1L // where the stream starts once it's ready: resumed, or where a failed one stopped
    private var group: String? = null // the bingeGroup of the stream playing: the next episode's like it first
    private var seeking = false // the banner came up for a seek: Left/Right go on seeking, not to its buttons
    private var scrubbing = false // a finger on the progress bar: it shows where the finger is, not where the film is
    private var locked = false // a finger: the controls locked, taps only bring up the way to unlock them (see lockControls)
    private var zoomed = false // a finger: the picture fills the screen, cut at its edges, instead of fitting in it (pinch)
    private var lastSize: VideoSize? = null // the picture's size, to fit it again when zoomed or not
    private var counting = false // the next episode coming after a countdown (see ended)
    private var countLeft = 0
    private var side = 0 // a finger going up or down: -1 on the left third (brightness), +1 on the right (volume), 0 between
    private var volumeSteps = 0f // a volume swipe's part of a step, until it makes one
    private lateinit var hint: TextView // brightness, volume, zoom: a moment in the middle
    private lateinit var unlock: TextView // the way out of locked controls
    // A phone or tablet (touch, no leanback): sound decoded in software, and an X on the panel (see Handheld).
    private val handheld by lazy {
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
    private var ticks = 0
    private val pool by lazy { Executors.newFixedThreadPool(6) } // a film's streams, every add-on and plugin at once
    private val lateWaits by lazy { Executors.newCachedThreadPool() } // waiting for the sources that answer late
    private val checks by lazy { Executors.newFixedThreadPool(12) } // a film's streams checked all at once (see checked)
    private val probes = ConcurrentHashMap<String, Stremio.Probe>() // what each stream turned out to be, by address
    private val fetched = ConcurrentHashMap<String, Pair<Long, List<Stremio.Stream>>>() // channel id to its streams, and when
    private val pages = ConcurrentHashMap<String, Stremio.Meta>()
    private val done = HashSet<String>() // the films and episodes marked watched while the player is open
    private val userDir by lazy { intent.getStringExtra(USER_DIR)?.let(::File) ?: filesDir } // the user's: progress, watched, Trakt
    private var scrobbling = false // Trakt was told the film or episode playing plays
    private val logos = ConcurrentHashMap<String, Bitmap>()
    private val guide by lazy {
        getSharedPreferences(K.FILE, MODE_PRIVATE).getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }?.let { Epg.saved(filesDir, it) }
    }

    private class LogoBox(val frame: FrameLayout, val bg: ImageView, val fg: ImageView, val mark: ImageView)

    private lateinit var surface: SurfaceView
    private lateinit var blurBackdrop: ImageView
    private lateinit var blurBackdropShade: View
    private lateinit var status: TextView // under the channel's logo and name while it loads: which stream, or none works
    private lateinit var loading: LinearLayout // in the middle while a channel loads: its logo, name and the status
    private lateinit var loadLogoBox: LogoBox
    private lateinit var loadName: TextView
    private lateinit var loadSub: TextView // under the name, for an episode: its season, number and name
    private var loadFor = -1 // the channel (film, episode) the loading screen shows
    private var breath: android.animation.ObjectAnimator? = null // the status line, breathing while it's under way
    private var soundOnly = false // what plays has no picture (a radio station): its screen stays, with the song on air
    private var onAir: String? = null // the song on air, as the stream says it (ICY, ID3)
    private lateinit var banner: LinearLayout
    private lateinit var logoBox: LogoBox
    private var titleLogo: Bitmap? = null
    private val blurLogos = ConcurrentHashMap<String, Bitmap>()
    private val roundArt = ConcurrentHashMap<String, Bitmap>() // a film's art with its corners rounded (see rounded)
    private lateinit var title: TextView
    private lateinit var position: TextView
    private lateinit var clock: TextView
    private lateinit var onNow: TextView
    private lateinit var bar: ProgressBar
    private lateinit var more: TextView
    private lateinit var source: TextView
    private lateinit var trackLine: TextView // in the banner: the audio track and subtitles playing
    private lateinit var subtitles: FrameLayout // over the picture, its size: the subtitles' cues
    private lateinit var tracksPanel: LinearLayout // Audio and subtitles, on the right
    private lateinit var tracksTitle: TextView
    private lateinit var tracksList: LinearLayout
    private lateinit var buttons: LinearLayout // in the banner, right of the stream and audio lines: icons for Pause, Audio, Subtitles, Stream
    private lateinit var desc: LinearLayout // in the banner: what's on, focused once OK brings it up (OK there: all of it)
    private lateinit var lines: LinearLayout // the stream and audio lines, the buttons as tall as both

    /** The audio and subtitle languages picked, for every channel: a file only this process writes (see pick). */
    private val choices by lazy { getSharedPreferences(CHOICES_FILE, MODE_PRIVATE) }

    /**
     * The home screen's preferences, read again whenever the file changed (it's written in the other process): the
     * Nuvio plugins on, and whether 18+ shows, as they are now.
     */
    @Suppress("DEPRECATION")
    private val prefs get() = getSharedPreferences(K.FILE, MODE_MULTI_PROCESS)

    /** One audio or subtitle track of the stream playing, as the panel lists it. */
    private class Track(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

    private val hideBanner = Runnable {
        expanded = false
        seeking = false
        unfocusBanner()
        banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility = View.GONE }.start()
    }
    private val zapNow = Runnable { if (shown != at) play(shown) }
    private val goTyped = Runnable {
        typed.toIntOrNull()?.let { n -> if (n in 1..channels.size) zapTo(n - 1, delay = 0) }
        typed = ""
    }
    /** Once a second while a film plays: its time in the banner; every ten, where it got to (VodProgress). */
    private val tick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (!vod) return
            if (player?.isPlaying == true) {
                if (banner.visibility == View.VISIBLE && shown == at) fillGuide(channels[at])
                if (++ticks % 10 == 0) saveProgress()
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val stuck = Runnable {
        if (player?.playWhenReady == false) return@Runnable // paused: waiting is what it's asked to do
        // One that played and stalls (the network, a server slow for a while, a jump in its times): the same again,
        // from now, a couple of times; one that never started, or keeps stalling: the next.
        if (again("stalled")) return@Runnable
        warn("stream ${tried + 1} stuck, trying the next")
        why = Stremio.Why.STUCK
        next()
    }

    /** How a stream is played: [noAudio] without its sound (the TV can't decode it), [agent] with VLC's User-Agent. */
    private class Attempt(val s: Stremio.Stream, val format: String?, val noAudio: Boolean = false, val agent: String? = null,
                          var played: Boolean = false, var restarts: Int = 0)

    /** The stream being tried started again, as it is (see Attempt): when it had played and not too often. */
    private fun again(what: String): Boolean {
        val a = playing ?: return false
        if (!a.played || a.restarts >= RESTARTS) return false
        warn("stream ${tried + 1} $what: started again")
        if (vod) player?.let { if (it.currentPosition > 5000) pendingSeek = it.currentPosition }
        start(a.s, a.format, a.noAudio, a.agent, a.restarts + 1)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Js.useAssets(applicationContext) // the modules plugins and extensions require(), in this process too
        Images.use(this) // the images the home screen keeps, read and added to here too
        Nuvio.process = ScriptProcess.plugins(this) // plugins run in a process of their own
        ExtensionCalls.use(this) // extensions answer as add-ons, from their own process
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vod = intent.getStringExtra(MODE) == MODE_VOD
        base = intent.getStringExtra(BASE) ?: if (vod) "" else return finish()
        addon = intent.getStringExtra(ADDON).orEmpty()
        adult = intent.getBooleanExtra(ADULT, false)
        fun list(key: String) = intent.getStringArrayListExtra(key).orEmpty()
        adultSources = list(ADULT_SOURCES).toSet()
        sources = list(SOURCES).zip(list(SOURCE_NAMES))
        descs = list(DESCS)
        if (vod && sources.isEmpty()) return finish()
        val ids = list(IDS)
        channels = ids.indices.map { i ->
            Channel(list(TYPES).getOrNull(i)?.ifEmpty { null } ?: "tv", ids[i], list(NAMES).getOrNull(i).orEmpty(),
                listOfNotNull(list(LOGOS).getOrNull(i), list(FALLBACK_LOGOS).getOrNull(i)).filter { it.isNotEmpty() }.distinct(),
                list(NOWS).getOrNull(i)?.ifEmpty { null })
        }
        if (channels.isEmpty()) return finish()
        // A stream picked by hand (see pickStream), or the one it played when it's resumed: the one the first episode or
        // film plays, none looked for; one resumed that no longer plays gives way to a search (see next).
        searchIfChosenFails = intent.getBooleanExtra(CHOSEN_ELSE_SEARCH, false)
        intent.getStringExtra(CHOSEN)?.let { j -> runCatching { streamOf(JSONObject(j)) }.getOrNull() }?.let { s ->
            channels.getOrNull(intent.getIntExtra(INDEX, 0))?.let { fetched[it.id] = System.currentTimeMillis() to listOf(s) }
        }
        build()
        if (vod) markInto(logoBox)
        play(intent.getIntExtra(INDEX, 0).coerceIn(0, channels.size - 1))
        if (vod) ui.post(tick)
    }

    // ---------- screen ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** One line that scrolls round when it's longer than its room. */
    private fun TextView.scrolling() {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        blurBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        blurBackdropShade = View(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        root.addView(blurBackdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(blurBackdropShade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // While a channel loads: its logo and name in the middle, and under them the stream being tried, or that
        // none works.
        loadLogoBox = createLogoBox(dp(6), dp(6), 12)
        val loadSize = LinearLayout.LayoutParams(dp(320), dp(180)) // wide, as the channels' cards and logos are
        loadName = text(28f, bold = true).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        loadSub = text(20f).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }
        status = text(18f).apply { gravity = Gravity.CENTER; alpha = 0.8f; setPadding(0, dp(10), 0, 0) }
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), 0, dp(48), 0)
            addView(loadLogoBox.frame, loadSize.apply { bottomMargin = dp(16) })
            addView(loadName, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(loadSub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        root.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        subtitles = FrameLayout(this)
        root.addView(subtitles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // The banner: across the bottom, on a shade so it reads over any picture.
        logoBox = createLogoBox(dp(8), dp(6), 10)
        title = text(24f, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        position = text(14f).apply { alpha = 0.7f; setPadding(dp(12), 0, 0, 0) }
        clock = text(18f)
        onNow = text(16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x40FFFFFF) },
                ClipDrawable(GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.WHITE) }, Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        more = text(14f).apply { alpha = 0.85f; maxLines = 14; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
        // The stream's line and the audio and subtitles' under the bar: too long for it, they scroll (selected: they
        // scroll without focus, which stays on the buttons).
        source = text(12f).apply { alpha = 0.6f; scrolling() }
        trackLine = text(12f).apply { alpha = 0.6f; scrolling(); setPadding(0, dp(4), 0, 0) }
        buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        // What's on: framed while it has the focus, so it's clear OK there shows all of it.
        val descBg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.TRANSPARENT) }
        desc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
            background = descBg
            addView(onNow)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            addView(more)
            isFocusable = false
            isClickable = true
            setOnClickListener { toggleMore() }
            setOnFocusChangeListener { _, has ->
                descBg.setColor(if (has) 0x26FFFFFF else Color.TRANSPARENT)
                descBg.setStroke(if (has) dp(2) else 0, Color.WHITE)
            }
        }
        lines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(source)
            addView(trackLine)
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) buttons.post { sizeButtons() }
            }
        }
        val foot = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(position)
            addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
        }
        // Nothing clips the buttons, which grow a little with the focus (see button).
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(head)
            // Its frame's padding out to the side, so its text lines up with the title.
            addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = -dp(12)
                marginEnd = -dp(12)
            })
            addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        banner = LinearLayout(this).apply {
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(dp(48), dp(48), dp(48), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0xB3000000.toInt(), 0x00000000))
            addView(logoBox.frame, LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(24) })
            addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        hint = text(18f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xB3000000.toInt()) }
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        unlock = text(26f).apply {
            text = "🔓"
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.player_unlock)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()) }
            visibility = View.GONE
            setOnClickListener {
                locked = false
                visibility = View.GONE
                showBanner(at)
            }
        }
        root.addView(unlock, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(24), 0, 0) })
        // A finger (phones and tablets): taps, double taps and swipes on the picture; the banner keeps its own taps,
        // and its bar, too thin for a finger, takes them from a strip around it.
        val gestures = touches()
        // Two fingers apart: the picture fills the screen; together: it fits again.
        var pinch = 1f
        val scale = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector) = !locked.also { pinch = 1f }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean { pinch *= d.scaleFactor; return true }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                val fill = when { pinch > 1.15f -> true; pinch < 0.87f -> false; else -> return }
                if (fill == zoomed) return
                zoomed = fill
                lastSize?.let { fit(it) }
                flash(getString(if (fill) R.string.player_zoom_fill else R.string.player_zoom_fit))
            }
        })
        root.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress && e.pointerCount == 1) gestures.onTouchEvent(e)
            true
        }
        banner.isClickable = true
        // Not a TouchDelegate: one moves a touch beside the view it's for to that view's middle, so the film went to its
        // half wherever the finger was. The strip round the bar, in its box, is read here instead, where the finger is.
        bar.setOnTouchListener { _, e -> scrub(e, e.x) }
        var onBar = false
        desc.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                onBar = bar.visibility == View.VISIBLE && e.y >= bar.top - dp(16) && e.y <= bar.bottom + dp(16)
            onBar && scrub(e, e.x - bar.left)
        }

        // Audio and subtitles: a side panel over the picture, its rows picked with Up/Down and OK.
        tracksList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tracksPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
            setBackgroundColor(0xF0101014.toInt())
            tracksTitle = text(22f, bold = true).apply { setPadding(dp(14), 0, 0, dp(16)) }
            if (handheld) addView(text(20f).apply { // a finger: an X closes it, as Back does
                text = "✕"
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.setup_close)
                setOnClickListener { closeTracks() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = -dp(32); bottomMargin = dp(4) })
            addView(tracksTitle)
            addView(ScrollView(this@PlayerActivity).apply { isVerticalScrollBarEnabled = false; addView(tracksList) })
            visibility = View.GONE
        }
        tracksPanel.isClickable = true // a tap beside its rows stays in it; one on the picture closes it (see touches)
        root.addView(tracksPanel, FrameLayout.LayoutParams(dp(380), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * Shows the banner for channel [i] (for a while, or until hidden with [stay]), filling in what's known so far: in
     * short, or all of what's on once OK [expanded] it.
     */
    private fun showBanner(i: Int, stay: Boolean = false) {
        if(!LivePlaybackPolicy.showControls(vod,quietZap))return
        val c = channels[i]
        title.text = c.name
        position.text = if (vod && channels.size < 2) "" else getString(R.string.player_channel_n, i + 1, channels.size)
        clock.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        fillGuide(c)
        fillSource(i)
        fillTrackLine(i)
        fillButtons(i)
        showLogo(c)
        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.visibility = View.VISIBLE
        }
        banner.animate().cancel()
        banner.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(hideBanner)
        if (!stay && !expanded) ui.postDelayed(hideBanner, if (bannerFocused()) BUTTONS_MS else BANNER_MS)
        // The add-on's page for the channel, for what's on and next: once, in the background.
        if (!vod && !pages.containsKey(c.id)) ahead.execute {
            val meta = runCatching { Stremio.meta(base, c.type, c.id) }.getOrNull() ?: return@execute
            pages[c.id] = meta
            ui.post { if (shown == i && banner.visibility == View.VISIBLE) fillGuide(c) }
        }
    }

    /** The stream playing on channel [i] and its picture's resolution ("Stream 1 of 2 · RAI 1 · 1080p"), or Loading. */
    private fun fillSource(i: Int) {
        val playing = streams.getOrNull(tried)?.takeIf { i == at }
        source.text = if (playing != null)
            listOfNotNull(getString(R.string.player_stream, tried + 1, streams.size, playing.title), resolution()).joinToString(" · ")
                .let { if (player?.playWhenReady == false) getString(R.string.player_paused) + "  ·  " + it else it }
        else getString(R.string.player_loading)
    }

    /** "720p", "1080p", "4K": the picture's lines (its width, for a wide film cropped shorter); null before it plays. */
    private fun resolution(): String? {
        val size = player?.videoSize ?: return null
        if (size.width == 0 || size.height == 0) return null
        val lines = maxOf(size.height, size.width * 9 / 16)
        return when {
            lines >= 2100 -> "4K"
            lines >= 1400 -> "1440p"
            lines >= 1000 -> "1080p"
            lines >= 700 -> "720p"
            else -> "${size.height}p"
        }
    }

    /** What's on: the add-on's (its page, else its catalog's line), else the guide set in Zephyr TV settings. */
    private fun fillGuide(c: Channel) {
        if (vod) return fillTime(c)
        val meta = pages[c.id]
        val epg = if (meta?.description == null && meta?.now == null && c.now == null) guide?.let { Epg.nowAndNext(it, c.name) } else null
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val progress: Int?
        when {
            meta?.description != null -> {
                onNow.text = meta.now ?: c.now ?: ""
                more.text = meta.description
                progress = Stremio.progress(meta.description)
            }
            epg != null -> {
                val (now, next) = epg
                onNow.text = "${now.title}  ·  ${time.format(java.util.Date(now.start))} – ${time.format(java.util.Date(now.stop))}"
                more.text = listOfNotNull(now.desc.takeIf { it.isNotBlank() },
                    next?.let { getString(R.string.channel_next, time.format(java.util.Date(it.start)), it.title) }).joinToString("\n")
                progress = Epg.progress(now)
            }
            else -> {
                onNow.text = meta?.now ?: c.now ?: ""
                more.text = ""
                progress = null
            }
        }
        onNow.visibility = if (onNow.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // All of it only once OK asks: over a channel playing, the banner stays short.
        more.visibility = if (expanded && !more.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        bar.progress = progress ?: 0
        desc.visibility = if (onNow.visibility == View.VISIBLE || bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /**
     * A film's or episode's line in the banner: its name (an episode's), the time it's at and how long it is, with the
     * bar; OK there shows what it's about.
     */
    /**
     * Where a seek goes when the stream's sound is Dolby or DTS (see passthrough): -1 for none. Such a sound goes to
     * the TV as it is, and on some TVs it never starts again after a seek's flush (its clock stays still, and so does
     * the picture): the stream is started again there instead, once the keys stop.
     */
    private var hardSeekTo = -1L
    private val hardSeek = Runnable {
        val p = player ?: return@Runnable
        val to = hardSeekTo.also { hardSeekTo = -1L }.takeIf { it >= 0 } ?: return@Runnable
        p.stop()
        p.seekTo(to)
        p.prepare()
    }

    private fun passthrough(p: ExoPlayer) = p.audioFormat?.sampleMimeType in PASSTHROUGH

    private fun fillTime(c: Channel) {
        val p = player?.takeIf { channels.indexOf(c) == at }
        val duration = p?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
        val pos = p?.let { if (hardSeekTo >= 0) hardSeekTo else it.currentPosition } ?: 0
        onNow.text = listOfNotNull(c.now, duration?.let { "${clock(pos)} / ${clock(it)}" }).joinToString("  ·  ")
        more.text = descs.getOrNull(channels.indexOf(c)).orEmpty()
        onNow.visibility = if (onNow.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        more.visibility = if (expanded && !more.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (duration == null) View.GONE else View.VISIBLE
        if (!scrubbing) bar.progress = if (duration == null) 0 else (pos * 100 / duration).toInt().coerceIn(0, 100)
        desc.visibility = if (onNow.visibility == View.VISIBLE || bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /**
     * Left/Right on a film: back or on [step] (-1, +1), 10 seconds, more while held; the banner shows where to, and
     * Left/Right go on seeking while it's up for it.
     */
    private fun seek(step: Int, repeat: Int = 0) {
        val p = player ?: return
        val by = when { repeat > 20 -> 60_000L; repeat > 5 -> 30_000L; else -> 10_000L }
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val from = if (hardSeekTo >= 0) hardSeekTo else p.currentPosition
        val to = (from + step * by).coerceIn(0, duration?.minus(1000) ?: Long.MAX_VALUE)
        if (banner.visibility != View.VISIBLE) seeking = true
        seekTo(to)
    }

    /** To [to] ms in: a Dolby or DTS stream starts again there (see hardSeek); the banner shows where. */
    private fun seekTo(to: Long) {
        val p = player ?: return
        if (passthrough(p)) {
            hardSeekTo = to
            ui.removeCallbacks(hardSeek)
            ui.postDelayed(hardSeek, HARD_SEEK_MS)
        } else p.seekTo(to)
        ui.removeCallbacks(stuck)
        showBanner(at)
        fillTime(channels[at])
    }

    /**
     * A finger: a tap shows the banner or hides it (and closes the audio and subtitles panel); a double tap on a film
     * goes back or on 10 seconds, on the side tapped; a swipe up or down on a channel goes to the next or the one
     * before. The banner's buttons and its bar take their own taps (see scrub).
     */
    private fun touches() = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean {
            val w = resources.displayMetrics.widthPixels
            side = if (!handheld || locked) 0 else if (e.x < w / 3) -1 else if (e.x > w * 2 / 3) 1 else 0
            volumeSteps = 0f
            return true
        }

        /** Up or down on the left third: the screen's brightness; on the right third: the volume. */
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            if (side == 0 || tracksPanel.visibility == View.VISIBLE || Math.abs(dy) <= Math.abs(dx)) return false
            val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            if (side < 0) {
                val lp = window.attributes
                val now = lp.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                lp.screenBrightness = (now + dy / height).coerceIn(0.01f, 1f)
                window.attributes = lp
                flash("☀  ${(lp.screenBrightness * 100).toInt()}%")
            } else {
                val audio = getSystemService(android.media.AudioManager::class.java)
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                volumeSteps += dy / height * max * 1.5f
                while (Math.abs(volumeSteps) >= 1f) {
                    val step = if (volumeSteps > 0) 1 else -1
                    val v = (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                    volumeSteps -= step
                }
                flash("🔊  ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)}%")
            }
            return true
        }

        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
            if (locked) return true.also { showUnlock() }
            when {
                tracksPanel.visibility == View.VISIBLE -> closeTracks()
                banner.visibility == View.VISIBLE -> hideBanner.run()
                else -> {
                    seeking = false
                    showBanner(shown)
                }
            }
            return true
        }

        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
            if (locked) return true
            if (!vod || tracksPanel.visibility == View.VISIBLE) return false
            seek(if (e.x < resources.displayMetrics.widthPixels / 2) -1 else +1)
            return true
        }

        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            if (locked || side != 0 || vod || tracksPanel.visibility == View.VISIBLE || Math.abs(vy) < Math.abs(vx) * 2) return false
            zap(if (vy < 0) +1 else -1)
            return true
        }
    })

    /** The progress bar with a finger: it follows the finger, and the film goes there when it's lifted. */
    private fun scrub(e: android.view.MotionEvent, x: Float): Boolean {
        val p = player ?: return false
        val duration = p.duration.takeIf { vod && it > 0 && it != C.TIME_UNSET } ?: return false
        val f = (x / bar.width.coerceAtLeast(1)).coerceIn(0f, 1f) // [x]: across the bar, from its start
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                scrubbing = true
                ui.removeCallbacks(hideBanner)
                bar.progress = (f * 100).toInt()
                onNow.text = listOfNotNull(channels[at].now, "${clock((duration * f).toLong())} / ${clock(duration)}").joinToString("  ·  ")
            }
            android.view.MotionEvent.ACTION_UP -> {
                scrubbing = false
                seekTo((duration * f).toLong().coerceAtMost(duration - 1000))
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                fillTime(channels[at])
            }
        }
        return true
    }

    private fun isAdult(): Boolean = adult || (streams.getOrNull(tried)?.source?.let { it in adultSources } == true)

    /** Where the film or episode playing got to, for resuming it and for Continue Playing; at the credits, it's watched. */
    private fun saveProgress() {
        if (!vod || isAdult()) return
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        if (at !in channels.indices) return
        val e = entry(at, p.currentPosition, duration)
        if (VodProgress.finished(e.position, duration)) return watched(at)
        ahead.execute { runCatching { VodProgress.put(userDir, e) } }
    }

    private fun entry(i: Int, position: Long, duration: Long): VodProgress.Entry {
        val c = channels[i]
        // The stream playing, kept with where it got to: Continue Playing starts with it, without a search.
        val playing = if (i == at) streams.getOrNull(tried)?.let { streamJson(it).toString() } else null
        return VodProgress.Entry(c.id, intent.getStringExtra(META_TYPE) ?: c.type, intent.getStringExtra(META_ID) ?: c.id,
            c.name, c.now.orEmpty(), intent.getStringExtra(POSTER) ?: c.logos.firstOrNull(), position, duration, System.currentTimeMillis(),
            playing)
    }

    /**
     * Film or episode [i] watched (at the credits, or to its end): marked so, off Continue Playing, and the next
     * episode waiting there in its place. Once each. (Whether the whole series is watched, the home screen tells:
     * its episodes, specials aside; see Titles.markWatched.)
     */
    private fun watched(i: Int) {
        val c = channels.getOrNull(i) ?: return
        if (!done.add(c.id)) return
        if (isAdult()) return
        // Watched here at the credits, maybe before Trakt's 80% (a short episode): Trakt counts it watched too.
        if (i == at) scrobble("stop", 100.0)
        val meta = intent.getStringExtra(META_ID) ?: c.id
        val last = i == channels.size - 1 // the episodes after the one picked, to the series' last
        val next = if (last) null else entry(i + 1, 0L, 0L)
        ahead.execute {
            runCatching {
                VodWatched.set(userDir, listOf(c.id to meta), true, name = c.name)
                VodProgress.remove(userDir, c.id)
                next?.let { VodProgress.putNext(userDir, it) }
            }
        }
    }

    /**
     * Tells the user's Trakt account (when they have one) about the film or episode playing: "start" (it plays, again
     * after a pause), "pause", "stop" (left, or ended), with how far in it is.
     */
    private fun scrobble(action: String, percent: Double? = null) {
        if (!vod || isAdult()) return
        val p = player ?: return
        val c = channels.getOrNull(at) ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        when {
            action == "start" -> scrobbling = true
            !scrobbling -> return // nothing told yet: nothing to pause or stop
            action == "stop" -> scrobbling = false
        }
        val progress = percent ?: (p.currentPosition * 100.0 / duration)
        val meta = intent.getStringExtra(META_ID) ?: c.id
        val dir = userDir
        ahead.execute { Trakt.scrobble(dir, action, c.id, meta, progress) }
    }

    /** A film or episode played to its end: watched, then the next episode, or the player closes. */
    private fun ended() {
        scrobble("stop")
        watched(at)
        if (at >= channels.size - 1) return finish()
        // A season's last episode: the next season's first only if the user wants it; Back leaves it there.
        val now = season(channels[at].id)
        val next = season(channels[at + 1].id)
        if (now == null || next == null || now == next) return countdown()
        release()
        openPanel(getString(R.string.player_season_end, now)) { row ->
            row(getString(R.string.player_next_season, next), false, 1) { play(at + 1) }
            row(getString(R.string.player_leave), false, 1) { finish() }
        }
    }

    /** The season of the episode [id] ("tt0944947:2:1": 2), or null for a film or an id without one. */
    private fun season(id: String): Int? {
        val parts = id.split(':')
        if (parts.size < 3 || !parts.takeLast(2).all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) return null
        return parts[parts.size - 2].toInt()
    }

    /** OK on what's on: all of it (the description, what's next), or back to its first line. */
    private fun toggleMore() {
        if (more.text.isNullOrEmpty()) return
        expanded = !expanded
        fillGuide(channels[shown])
        keepUp()
    }

    private fun createLogoBox(padH: Int, padV: Int, radiusDp: Int = 10): LogoBox {
        // A film's art stands on its own, with no box: its corners are rounded on the picture itself (see rounded).
        val frame = FrameLayout(this).apply {
            if (!vod) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(radiusDp).toFloat()
                    setColor(0x33000000)
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
            if (vod) visibility = View.GONE
        }
        val fg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!vod) setPadding(padH, padV, padH, padV)
        }
        // The title's logo, over the middle of its art.
        val mark = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        frame.addView(bg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(fg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(mark, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (mark.paddingLeft != w / 8) mark.setPadding(w / 8, h / 4, w / 8, h / 4)
        }
        return LogoBox(frame, bg, fg, mark)
    }

    /**
     * [src] with its corners rounded, when it's a picture that fills its frame; one with see-through corners (a logo,
     * a cut-out poster) is left as it is.
     */
    private fun rounded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return src
        val corners = listOf(src.getPixel(0, 0), src.getPixel(w - 1, 0), src.getPixel(0, h - 1), src.getPixel(w - 1, h - 1))
        if (src.hasAlpha() && corners.any { Color.alpha(it) < 255 }) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        val radius = w * 0.04f
        android.graphics.Canvas(out).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, paint)
        return out
    }

    /** The title's logo over its art in [box]: decoded once, while the player is open. */
    private fun markInto(box: LogoBox) {
        val uri = intent.getStringExtra(TITLE_LOGO)?.takeIf { it.isNotEmpty() } ?: return
        titleLogo?.let { box.mark.setImageBitmap(it); box.mark.visibility = View.VISIBLE; return }
        ahead.execute {
            val bmp = runCatching { Http.open(uri).use { it.readCapped(Http.MAX_IMAGE) } }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@execute
            ui.post {
                titleLogo = bmp
                for (b in listOf(logoBox, loadLogoBox)) {
                    b.mark.setImageBitmap(bmp)
                    b.mark.visibility = View.VISIBLE
                }
            }
        }
    }

    /** The channel's logo in the banner: the add-on's, then Zephyr TV's own; downloaded once, kept while the player is open. */
    private fun showLogo(c: Channel) = logoInto(logoBox, c) { title.text == c.name }

    /** [c]'s logo in [box]: the add-on's, then Zephyr TV's own; downloaded once, set if [still] holds when it arrives. */
    /**
     * [uri]'s image, through Images as the home screen's: a channel logo it keeps, else art (downloaded into its
     * folder the first time). Network: off the UI thread.
     */
    private fun imageBytes(uri: String): ByteArray {
        if (!uri.startsWith("http")) return Images.open(uri).use { it.readCapped(Http.MAX_IMAGE) }
        return (if (Images.has(uri, Images.Kind.LOGO)) Images.file(uri, Images.Kind.LOGO) else Images.file(uri)).readBytes()
    }

    private fun logoInto(box: LogoBox, c: Channel, isFullScreenBackdrop: Boolean = false, still: () -> Boolean) {
        val knownUri = c.logos.firstOrNull { logos[it] != null }
        val known = knownUri?.let { logos[it] }
        if (known != null) {
            val blur = blurLogos[knownUri] ?: runCatching { softBlur(known) }.getOrNull()?.also { blurLogos[knownUri] = it }
            box.bg.setImageBitmap(blur)
            box.fg.setImageBitmap(if (vod) roundArt[knownUri] ?: known else known)
            if (isFullScreenBackdrop && blur != null) blurBackdrop.setImageBitmap(blur)
        } else {
            box.bg.setImageDrawable(null)
            box.fg.setImageDrawable(null)
            if (isFullScreenBackdrop) blurBackdrop.setImageDrawable(null)
        }
        box.frame.visibility = if (c.logos.isEmpty()) View.GONE else View.VISIBLE
        if (known != null || c.logos.isEmpty()) return
        ahead.execute {
            var foundUri: String? = null
            val bmp = c.logos.firstNotNullOfOrNull { uri ->
                runCatching {
                    val bytes = imageBytes(uri)
                    // A film's art is a backdrop, up to 4K: decoded at about the size it shows (320 dp wide at most).
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= dp(320)) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.also {
                            logos[uri] = it
                            foundUri = uri
                        }
                }.getOrNull()
            } ?: return@execute
            val blur = foundUri?.let { u -> blurLogos.getOrPut(u) { softBlur(bmp) } } ?: runCatching { softBlur(bmp) }.getOrNull()
            val shown = if (vod) runCatching { rounded(bmp) }.getOrNull()?.also { r -> foundUri?.let { roundArt[it] = r } } ?: bmp else bmp
            ui.post {
                if (still()) {
                    box.bg.setImageBitmap(blur)
                    box.fg.setImageBitmap(shown)
                    if (isFullScreenBackdrop && blur != null) blurBackdrop.setImageBitmap(blur)
                }
            }
        }
    }

    /**
     * The channel tuned in the middle of the screen, its logo and name over [text] (Loading…, Loading stream 2 of
     * 3…, or that none works); null takes it away, once the picture plays. While [busy] (something under way, not
     * an answer such as no streams or the song on air), the line breathes.
     */
    private fun say(text: String?, busy: Boolean = true) {
        breathe(text != null && busy)
        if (text == null) {
            loading.visibility = View.GONE
            blurBackdrop.visibility = View.GONE
            blurBackdropShade.visibility = View.GONE
            return
        }
        val c = channels[at]
        if (loadFor != at) {
            val i = at
            loadFor = i
            loadName.text = c.name
            // A film's or episode's line (an episode's season, number and name); a channel's what's on stays in the banner.
            loadSub.text = if (vod) c.now.orEmpty() else ""
            loadSub.visibility = if (loadSub.text.isEmpty()) View.GONE else View.VISIBLE
            logoInto(loadLogoBox, c, isFullScreenBackdrop = true) { loadFor == i }
        }
        status.text = text
        loading.visibility = View.VISIBLE
        blurBackdrop.visibility = View.VISIBLE
        blurBackdropShade.visibility = View.VISIBLE
    }

    /** The status line fading in and out, slowly, while [on]; still, as it was, when not. */
    private fun breathe(on: Boolean) {
        if (!on) {
            breath?.cancel()
            breath = null
            status.alpha = 0.8f
            return
        }
        if (breath != null) return
        breath = android.animation.ObjectAnimator.ofFloat(status, View.ALPHA, 0.8f, 0.25f).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ---------- remote ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Audio and subtitles open: its rows take the arrows and OK; Back closes it.
        if (tracksPanel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) closeTracks() else return super.onKeyDown(keyCode, event)
            return true
        }
        // The banner in use (OK, Up or Down brought it up): the focus is on what's on or on a button. Down goes to
        // the buttons, Up back to what's on, Left/Right along the buttons; OK picks (the views' own handling). It
        // stays up while it's used.
        if (bannerFocused()) when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideBanner.run(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (buttonFocused()) focusDesc() else keepUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!buttonFocused() && buttons.childCount > 0) focusButton(0) else keepUp(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                // A film's line is its timeline: Left/Right there seek.
                if (vod && currentFocus === desc) { seek(step, event.repeatCount); keepUp(); return true }
                val n = buttons.childCount
                if (n > 0) focusButton(if (buttonFocused()) (buttons.indexOfChild(currentFocus) + step).coerceIn(0, n - 1) else if (step > 0) 0 else n - 1)
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { hideBanner.run(); return true }
        }
        // The banner up (and done fading in) with buttons: Left/Right reach them; otherwise they change the stream.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && banner.visibility == View.VISIBLE &&
            banner.alpha >= 1f && shown == at && buttons.childCount > 0 && !(vod && seeking)) {
            focusButton(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 0 else buttons.childCount - 1)
            return true
        }
        // Live Up/Down zap quietly unless a control already owns navigation; VOD keeps its controls.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!vod) {
                if(event.repeatCount==0) {quietZap=true;hideBanner.run();zap(if(keyCode==KeyEvent.KEYCODE_DPAD_UP)+1 else -1)}
            } else okPressed()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_MEDIA_NEXT -> zap(+1) // a series' next episode (a channel's next, live)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> zap(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (vod) seek(-1, event.repeatCount) else otherStream(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (vod) seek(+1, event.repeatCount) else otherStream(+1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (vod) seek(-1, 6)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (vod) seek(+1, 6)
            // OK acts once let go (see onKeyUp), as holding it opens Audio and subtitles (onKeyLongPress).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) event.startTracking()
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> if (event.repeatCount == 0) okPressed()
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycle(C.TRACK_TYPE_AUDIO)
            KeyEvent.KEYCODE_CAPTIONS -> cycle(C.TRACK_TYPE_TEXT)
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous in channels.indices) zapTo(previous, delay = 0)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> player?.let { p ->
                setPlaying(if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) !p.playWhenReady else keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                typed = (typed + (keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
                title.text = typed
                position.text = ""
                showBanner(shown, stay = true)
                title.text = typed
                ui.removeCallbacks(goTyped)
                ui.postDelayed(goTyped, 1500)
            }
            KeyEvent.KEYCODE_BACK -> if (banner.visibility == View.VISIBLE) hideBanner.run() else finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.onKeyLongPress(keyCode, event)
        openTracks()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (tracksPanel.visibility == View.VISIBLE || bannerFocused()) return super.onKeyUp(keyCode, event)
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) okPressed() // a hold opened the panel instead
        return true
    }

    /**
     * OK (or Up, Down, Info) with the banner down, or up as a zap shows it: the banner, the focus on what's on (so it's
     * clear where OK goes next: all of it), else on the first button. Once in use, the banner's views take OK.
     */
    private fun okPressed() {
        quietZap=false
        seeking = false
        showBanner(shown)
        if (shown == at) focusDesc()
    }

    // ---------- audio and subtitles ----------

    /** The stream's audio or subtitle tracks the TV can play, in the stream's order, each named for the panel. */
    private fun tracks(type: Int): List<Track> {
        val p = player ?: return emptyList()
        var n = 0
        return p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> Track(g, i, trackName(g.getTrackFormat(i), ++n), g.isTrackSelected(i)) }
        }
    }

    /** "Italiano 5.1", "English (forced)": the track's language (or label, or number), and what tells it from others. */
    private fun trackName(f: Format, n: Int): String {
        val locale = resources.configuration.locales[0]
        val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale).takeIf { d -> d.isNotBlank() && d != it } }
            ?.replaceFirstChar { it.titlecase(locale) }
        val name = listOfNotNull(lang, f.label?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }).joinToString(" · ")
            .ifEmpty { getString(R.string.player_track_n, n) }
        val sound = when (f.channelCount) { 1 -> "Mono"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) getString(R.string.player_track_forced) else null
        return listOfNotNull(name, sound, forced).joinToString(" ")
    }

    /** In the banner, under the stream: the audio track and subtitles playing, and how to change them. */
    private fun fillTrackLine(i: Int) {
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        if (i != at || (audio.size < 2 && subs.isEmpty())) {
            trackLine.visibility = View.GONE
            return
        }
        val sound = audio.firstOrNull { it.selected }?.name ?: audio.firstOrNull()?.name ?: "–"
        val shownSubs = subs.firstOrNull { it.selected }?.name ?: getString(R.string.player_subtitles_off)
        trackLine.text = getString(R.string.player_tracks_line, sound, shownSubs)
        trackLine.visibility = View.VISIBLE
    }

    // ---------- the banner's buttons ----------

    /** The banner's buttons for channel [i] once it plays: Audio with two tracks or more, Subtitles with any, Stream with two or more. */
    private fun fillButtons(i: Int) {
        val keep = (currentFocus?.parent === buttons).let { if (it) buttons.indexOfChild(currentFocus) else -1 }
        val paused = player?.playWhenReady == false
        val list = if (i != at || player == null) emptyList() else listOfNotNull(
            // A series' episodes: the one before and the one after, either side of play and pause.
            if (vod && at > 0) Triple(R.drawable.ic_chevron_left, R.string.player_episode_previous) { zap(-1) } else null,
            if (paused) Triple(R.drawable.ic_play, R.string.player_play) { setPlaying(true) }
            else Triple(R.drawable.ic_pause, R.string.player_pause) { setPlaying(false) },
            if (vod && at < channels.size - 1) Triple(R.drawable.ic_chevron_right, R.string.player_episode_next) { zap(+1) } else null,
            if (tracks(C.TRACK_TYPE_AUDIO).size > 1) Triple(R.drawable.ic_audio, R.string.player_audio) { openTracks(C.TRACK_TYPE_AUDIO) } else null,
            if (tracks(C.TRACK_TYPE_TEXT).isNotEmpty()) Triple(R.drawable.ic_subtitles, R.string.player_subtitles) { openTracks(C.TRACK_TYPE_TEXT) } else null,
            if (streams.size > 1) Triple(R.drawable.ic_stream, R.string.player_streams) { openStreams() } else null,
            if (handheld) Triple(R.drawable.ic_lock, R.string.player_lock) { lockControls() } else null,
        )
        val now = (0 until buttons.childCount).map { buttons.getChildAt(it).tag }
        if (now == list.map { it.second }) return // the same: leave them (and focus) as they are
        buttons.removeAllViews()
        list.forEach { (icon, label, action) -> buttons.addView(button(icon, getString(label), action).apply { tag = label }) }
        buttons.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        sizeButtons()
        if (bannerFocused() || keep >= 0) reachable() // in use: the new ones too
        if (keep >= 0) focusButton(minOf(keep, buttons.childCount - 1))
    }

    /**
     * Play or pause the channel (the button, or the remote's media keys). Paused, the banner stays up and says so; a
     * live stream paused past what it keeps catches up with the broadcast when it plays again (see onPlayerError).
     */
    private fun setPlaying(play: Boolean) {
        quietZap=false
        val p = player ?: return
        if (p.playWhenReady == play) return
        p.playWhenReady = play
        ui.removeCallbacks(stuck) // a pause isn't a stream that's stuck
        showBanner(at, stay = !play)
    }

    /**
     * A square icon (its name for TalkBack), lit white while it has the focus; focusable only once the banner is in
     * use. As tall as the stream and audio lines beside it (see sizeButtons).
     */
    private fun button(icon: Int, label: String, action: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        contentDescription = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
        background = bg
        isFocusable = false
        isClickable = true
        setOnClickListener { action() }
        setOnFocusChangeListener { v, has ->
            bg.setColor(if (has) Color.WHITE else 0x33FFFFFF)
            setColorFilter(if (has) Color.BLACK else Color.WHITE)
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) }
    }

    /** The buttons square, as tall as the stream and audio lines together (one line when there's no audio line). */
    private fun sizeButtons() {
        val h = maxOf(lines.height, dp(32))
        for (k in 0 until buttons.childCount) {
            val b = buttons.getChildAt(k)
            val lp = b.layoutParams as LinearLayout.LayoutParams
            if (lp.width == h && lp.height == h) continue
            lp.width = h
            lp.height = h
            b.layoutParams = lp
            b.setPadding(h / 4, h / 4, h / 4, h / 4)
        }
    }

    private fun buttonFocused() = ::buttons.isInitialized && currentFocus?.parent === buttons

    /** The banner in use: the focus on what's on or one of its buttons. */
    private fun bannerFocused() = ::desc.isInitialized && (currentFocus === desc || buttonFocused())

    /** The banner up a while longer (as long as all of what's on shows, when it does): it's in use. */
    private fun keepUp() {
        ui.removeCallbacks(hideBanner)
        if (!expanded) ui.postDelayed(hideBanner, BUTTONS_MS)
    }

    /** Makes the banner's views reachable: what's on and the buttons. */
    private fun reachable() {
        desc.isFocusable = true
        for (k in 0 until buttons.childCount) buttons.getChildAt(k).isFocusable = true
    }

    /** The focus on what's on (else, with nothing on, on the first button); the banner stays up a while longer. */
    private fun focusDesc() {
        if (desc.visibility != View.VISIBLE) return if (buttons.childCount > 0) focusButton(0) else Unit
        reachable()
        desc.requestFocus()
        keepUp()
    }

    /** The focus on the [n]th button; the banner stays up a while longer. */
    private fun focusButton(n: Int) {
        reachable()
        buttons.getChildAt(n)?.requestFocus()
        keepUp()
    }

    /** What's on and the buttons out of reach again (not focusable, so the window can't hand them the focus by itself). */
    private fun unfocusBanner() {
        if (!::buttons.isInitialized) return
        desc.isFocusable = false
        for (k in 0 until buttons.childCount) buttons.getChildAt(k).isFocusable = false
    }

    /** Stream: the channel's streams, as the add-on lists them; the one picked plays (then the next ones, if it fails). */
    /** A stream's row in the panel: [info] on top, scrolling; [source] under it in italics; ✓ when it's [on]. */
    private fun streamRow(info: String, source: String, on: Boolean, pick: () -> Unit) {
        val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        val top = text(16f).apply {
            text = if (on) "✓  $info" else "     $info"
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = handheld
        }
        val under = text(13f).apply { text = "     $source"; setTypeface(typeface, Typeface.ITALIC); alpha = 0.75f; setPadding(0, dp(2), 0, 0) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bg
            isFocusable = true
            isClickable = true
            addView(top)
            if (source.isNotBlank()) addView(under)
            setOnFocusChangeListener { _, has ->
                bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                top.setTextColor(if (has) Color.BLACK else Color.WHITE)
                top.isSelected = has || handheld
                under.setTextColor(if (has) Color.BLACK else Color.WHITE)
            }
            setOnClickListener { pick(); closeTracks() }
        }
        tracksList.addView(row)
        if (on) row.post { row.requestFocus() }
    }

    private fun openStreams() {
        if (streams.size < 2) return
        // Grouped by quality, the highest first and those that say none last, each group under its heading; picking one
        // plays it from the list as is.
        openPanel(getString(R.string.player_streams)) { row ->
            var group = -1
            for (k in streams.indices.sortedByDescending { qualityGroup(streams[it].title) }) {
                val s = streams[k]
                val q = qualityGroup(s.title)
                if (q != group) {
                    group = q
                    tracksList.addView(text(13f).apply {
                        text = qualityHeading(q)
                        alpha = 0.6f
                        setPadding(dp(14), dp(16), 0, dp(6))
                    })
                }
                // Two lines: what it is on top (scrolling when it's long), where it's from under it, in italics.
                streamRow(getString(R.string.player_stream_row, k + 1, s.title), s.source.orEmpty(), k == tried) {
                    tried = k - 1
                    attempts = 0
                    next()
                }
            }
        }
    }

    /** Hold OK, or Menu (both lists), or a button (the audio tracks, or Off and the subtitles); focus on what's playing. */
    private fun openTracks(type: Int? = null) {
        quietZap=false
        if (player == null) return showBanner(shown)
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        val title = when (type) {
            C.TRACK_TYPE_AUDIO -> R.string.player_audio
            C.TRACK_TYPE_TEXT -> R.string.player_subtitles
            else -> R.string.player_tracks_title
        }
        openPanel(getString(title)) { row ->
            fun heading(res: Int) = tracksList.addView(text(13f).apply {
                this.text = getString(res)
                alpha = 0.6f
                setPadding(dp(14), dp(16), 0, dp(6))
            })
            if (type != C.TRACK_TYPE_TEXT) {
                if (type == null) heading(R.string.player_audio)
                if (audio.isEmpty()) row(getString(R.string.player_track_n, 1), true, 1) {}
                audio.forEach { t -> row(t.name, t.selected, 1) { pick(t, C.TRACK_TYPE_AUDIO) } }
            }
            if (type != C.TRACK_TYPE_AUDIO) {
                if (type == null) heading(R.string.player_subtitles)
                row(getString(R.string.player_subtitles_off), subs.none { it.selected }, 1) { subtitlesOff() }
                subs.forEach { t -> row(t.name, t.selected, 1) { pick(t, C.TRACK_TYPE_TEXT) } }
            }
        }
    }

    /**
     * The side panel titled [title], its rows added by [fill] through the row maker it's given (a name, whether it's
     * the one playing, its lines at most: a stream's long name takes two, what picking it does); focus on the one playing.
     */
    private fun openPanel(title: String, fill: ((String, Boolean, Int, () -> Unit) -> Unit) -> Unit) {
        hideBanner.run()
        tracksTitle.text = title
        tracksList.removeAllViews()
        var focus: View? = null
        fun row(name: String, on: Boolean, lines: Int, pick: () -> Unit) {
            TextView(this).apply {
                this.text = if (on) "✓  $name" else "     $name"
                textSize = 16f
                // One line scrolls while it has focus (a stream's long name); more are cut short.
                if (lines == 1) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                } else {
                    maxLines = lines
                    ellipsize = TextUtils.TruncateAt.END
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isClickable = true
                val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
                background = bg
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { _, has ->
                    bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                    setTextColor(if (has) Color.BLACK else Color.WHITE)
                    isSelected = has
                }
                setOnClickListener { pick(); closeTracks() }
                tracksList.addView(this)
                if (on && focus == null) focus = this
            }
        }
        fill { name, on, lines, pick -> row(name, on, lines, pick) }
        tracksPanel.visibility = View.VISIBLE
        (focus ?: (0 until tracksList.childCount).map { tracksList.getChildAt(it) }.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun closeTracks() {
        counting = false // Back on the countdown, or a row picked: the episode isn't started by it
        ui.removeCallbacks(countTick)
        tracksPanel.visibility = View.GONE
        tracksList.removeAllViews()
    }

    /** The next episode in a few seconds, as the panel says, unless it's started now or the player stays. */
    private fun countdown() {
        openPanel(getString(R.string.player_next_in, NEXT_IN)) { row ->
            row(getString(R.string.player_next_now), false, 1) { play(at + 1) }
            row(getString(R.string.player_stay), false, 1) { }
        }
        counting = true
        countLeft = NEXT_IN
        ui.postDelayed(countTick, 1000)
    }

    private val countTick = object : Runnable {
        override fun run() {
            if (!counting) return
            if (--countLeft <= 0) {
                closeTracks()
                return play(at + 1)
            }
            tracksTitle.text = getString(R.string.player_next_in, countLeft)
            ui.postDelayed(this, 1000)
        }
    }

    /**
     * Subtitles picked in this player (a language, "und", or "" for Off): for the channels and episodes after this
     * one. Kept for the next time too only with Subtitles always on; otherwise each time starts with them off.
     */
    private var textLang: String? = null
    private var manualAudioPlayer: ExoPlayer? = null

    /** Plays track [t], and keeps its language for the channels after this one (see textLang). */
    private fun pick(t: Track, type: Int) {
        val p = player ?: return
        if (type == C.TRACK_TYPE_AUDIO) manualAudioPlayer = p
        val lang = t.group.getTrackFormat(t.index).language
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
            .apply { if (type == C.TRACK_TYPE_AUDIO) setPreferredAudioLanguage(lang) else setPreferredTextLanguage(lang) }
            .build()
        // Subtitles picked are kept for what plays next; audio isn't: the next stream starts with its default.
        if (type == C.TRACK_TYPE_AUDIO) return
        textLang = lang ?: "und"
        if (subtitlesOn()) choices.edit().putString(TEXT_LANG, textLang).apply()
    }

    private fun subtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        textLang = "" // for this time only: with Subtitles always on, the next starts with them again
        showCues(emptyList())
    }

    /** The remote's audio or subtitles key: the next track (subtitles: after the last, off), shown in the banner. */
    private fun cycle(type: Int) {
        val list = tracks(type)
        val on = list.indexOfFirst { it.selected }
        when {
            type == C.TRACK_TYPE_TEXT && on == list.size - 1 && on >= 0 -> subtitlesOff()
            list.isNotEmpty() && (type == C.TRACK_TYPE_TEXT || list.size > 1) -> pick(list[(on + 1) % list.size], type)
        }
        ui.postDelayed({ showBanner(shown) }, 300) // once the player has switched
    }

    /** The languages picked before, as preferences for a new stream's player: Off stays off. */
    private fun applyChoices(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            // Each stream starts with the default audio: the TV's language (the TMDB one's) where it has it, else its
            // own; one picked in Audio is for that stream alone (see pick).
            setPreferredAudioLanguage((prefs.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()).substringBefore('-'))
            // Subtitles: as picked in this player; else, always on, the language picked last (or the TV's, else the
            // stream's default); else off, but those the stream forces (a foreign line in a dubbed film).
            val tv = (prefs.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()).substringBefore('-')
            val t = textLang ?: if (subtitlesOn()) choices.getString(TEXT_LANG, null)?.ifEmpty { null } ?: tv else null
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, t == "")
            if (t == null) setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(false).setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            else if (t != "") setPreferredTextLanguage(t).setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0)
        }.build()
    }

    private fun subtitlesOn() = prefs.getBoolean(K.SUBTITLES_ON, false)

    /** Supported Full HD renditions first; real Italian before an IPTV provider's "ak" label. */
    private fun applyLivePreferences(p: ExoPlayer, allowAudio: Boolean) {
        if (vod) return
        val params = p.trackSelectionParameters
        val next = params.buildUpon()
        var changed = false
        val preferredLanguage = (prefs.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()).substringBefore('-')
        if (allowAudio && manualAudioPlayer !== p && preferredLanguage == "it") {
            val audio = tracks(C.TRACK_TYPE_AUDIO).maxByOrNull {
                val f = it.group.getTrackFormat(it.index)
                LivePlaybackPolicy.audioRank(f.language, f.label)
            }
            if (audio != null) {
                val f = audio.group.getTrackFormat(audio.index)
                if (LivePlaybackPolicy.audioRank(f.language, f.label) > 0 && !audio.selected) {
                    next.setOverrideForType(TrackSelectionOverride(audio.group.mediaTrackGroup, audio.index))
                    changed = true
                }
            }
        }
        // Keep all supported 1080p bitrates in the same adaptive group. No match: normal automatic selection.
        val fullHd = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.mapNotNull { g ->
            val indices = (0 until g.length).filter { i ->
                val f = g.getTrackFormat(i)
                g.isTrackSupported(i) && LivePlaybackPolicy.isFullHd(f.width, f.height)
            }
            indices.takeIf { it.isNotEmpty() }?.let { g to it }
        }.firstOrNull()
        fullHd?.let { (g, indices) ->
            if (params.overrides[g.mediaTrackGroup]?.trackIndices != indices) {
                next.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, indices))
                changed = true
            }
        }
        if (changed) p.trackSelectionParameters = next.build()
    }

    /**
     * The subtitles on screen: text ones in a caption at the bottom, picture ones (DVB, common on IPTV) where the
     * stream places them, over the picture's own area.
     */
    private fun showCues(cues: List<Cue>) {
        subtitles.removeAllViews()
        val w = subtitles.width
        val h = subtitles.height
        val lines = ArrayList<CharSequence>()
        for (c in cues) {
            val bmp = c.bitmap
            if (bmp == null) {
                c.text?.let { lines += it }
                continue
            }
            if (w == 0 || h == 0) continue
            val cw = if (c.size != Cue.DIMEN_UNSET) (c.size * w).toInt() else bmp.width
            val ch = if (c.bitmapHeight != Cue.DIMEN_UNSET) (c.bitmapHeight * h).toInt() else cw * bmp.height / maxOf(1, bmp.width)
            val x = if (c.position != Cue.DIMEN_UNSET) (c.position * w).toInt() else w / 2
            val y = if (c.line != Cue.DIMEN_UNSET && c.lineType == Cue.LINE_TYPE_FRACTION) (c.line * h).toInt() else h - dp(32)
            val left = when (c.positionAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> x - cw / 2; Cue.ANCHOR_TYPE_END -> x - cw; else -> if (c.position == Cue.DIMEN_UNSET) x - cw / 2 else x }
            val top = when (c.lineAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> y - ch / 2; Cue.ANCHOR_TYPE_END -> y - ch; else -> if (c.line == Cue.DIMEN_UNSET) y - ch else y }
            subtitles.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY },
                FrameLayout.LayoutParams(cw, ch).apply { leftMargin = left; topMargin = top })
        }
        if (lines.isNotEmpty()) subtitles.addView(text(22f).apply {
            text = TextUtils.join("\n", lines)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })
    }

    /** CH+ / CH-: the banner moves on at once; the channel is tuned once the keys rest (holding one runs through). */
    private fun zap(step: Int) {
        if (channels.size < 2) return showBanner(shown)
        // A series' episodes stop at its first and last; channels go round.
        if (vod) return (shown + step).let { to -> if (to in channels.indices) zapTo(to, delay = ZAP_MS) else showBanner(shown) }
        zapTo(Math.floorMod(shown + step, channels.size), delay = ZAP_MS)
    }

    private fun zapTo(i: Int, delay: Long) {
        shown = i
        expanded = false
        showBanner(i)
        ui.removeCallbacks(zapNow)
        ui.postDelayed(zapNow, delay)
    }

    /** Left / Right: the channel's other streams, as the add-on lists them. */
    private fun otherStream(step: Int) {
        if (streams.size < 2 || shown != this.at) return showBanner(shown)
        tried = Math.floorMod(tried + step, streams.size) - 1
        attempts = 0
        why = null
        next()
    }

    // ---------- playing ----------

    /** Tunes channel [i]: its streams (fetched ahead if they're fresh), the first that works, then its neighbours'. */
    private fun play(i: Int) {
        if (vod && player != null) { saveProgress(); scrobble("stop") } // the one left (still [at]), before the next
        if (i != at) previous = at
        at = i
        shown = i
        val c = channels[i]
        val tuning = ++tune
        streams = emptyList()
        tried = -1
        attempts = 0
        why = null
        release()
        pendingSeek = if (vod && !isAdult()) VodProgress.position(userDir, c.id) ?: -1L else -1L
        // A film's or episode's: its streams looked for, then checked, then each tried ("stream 1 of 5").
        // Or found ahead by the launcher (see StreamCache.warm): every source's.
        val known = fetched[c.id]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS } != null ||
            (vod && sources.isNotEmpty() && sources.all { StreamCache.fresh(cacheDir, it.first, c.type, c.id) != null })
        say(getString(if (vod && !known) R.string.player_finding_streams else R.string.player_loading))
        if (banner.visibility == View.VISIBLE) showBanner(i) // zapping: the banner keeps up with it; else the middle says it all
        lateLeft = 0
        waitingLate = false
        holdForLanguage = false
        net.execute {
            // Whatever goes wrong looking for them is said, as none found: the player never closes on it.
            try {
                val lateOnes: (List<Stremio.Stream>?) -> Unit = { more -> val ok = more?.let { checked(it) }; ui.post { late(tuning, ok) } }
                val cached = fetched[c.id]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS } != null
                // Quick start: the first stream in the preferred quality that works plays at once (see quickStreams).
                val (found, ready) = if (vod && !cached && quickStart(prefs)) quickStreams(c, lateOnes)
                    else streamsOf(c, lateOnes) to false
                if (!ready && vod && found.size > 1) ui.post { if (tuning == tune) say(getString(R.string.player_checking_streams)) }
                val list = if (ready) found else checked(found)
                ui.post {
                    if (isFinishing || tuning != tune) return@post
                    streams = if (vod) list else list.sortedBy { LivePlaybackPolicy.streamRank(streamQuality(it.title)) }
                    prefetch(i)
                    // None in the TV's language yet, and sources still looking: theirs are waited for before another
                    // language's (or one that says none) plays.
                    if (vod && list.isNotEmpty() && list.none { it.langRank == Stremio.LANG_OURS } && lateLeft > 0) {
                        holdForLanguage = true
                        return@post say(getString(R.string.player_finding_language))
                    }
                    next()
                }
        } catch (e: Exception) {
                warn("streams not found: $e")
                ui.post { if (!isFinishing && tuning == tune) say(noStreams(), busy = false) }
            }
        }
    }

    /**
     * The order a film's streams are tried in: answering first (not yet known, then failed, after); in the TV's
     * language first, then saying none, then another; then the preferred quality's order.
     */
    private fun streamOrder(): Comparator<Stremio.Stream> =
        compareBy<Stremio.Stream> { s -> when (probes[s.url]?.ok) { true -> 0; null -> 1; false -> 2 } }
            .thenBy { it.langRank }.then(qualityOrder(preferredQuality(prefs)))

    /**
     * Quick start: a film's sources asked all at once, each one's streams looked at as they come; the first in the
     * preferred quality (with the highest preferred, 2160p) that the check finds to be a video is the one to play,
     * with the rest so far after it, and the sources still looking go on in the background ([late]): ready (true).
     * None in time: all that came, to be checked and ordered as usual (false). Off the UI thread.
     */
    private fun quickStreams(c: Channel, late: (List<Stremio.Stream>?) -> Unit): Pair<List<Stremio.Stream>, Boolean> {
        if (sources.isEmpty()) return emptyList<Stremio.Stream>() to false
        val preferred = preferredQuality(prefs)
        val answers = java.util.concurrent.ExecutorCompletionService<List<Stremio.Stream>>(pool)
        for ((b, name) in sources) answers.submit {
            runCatching { cachedStreams(prefs, filesDir, cacheDir, b, name, c.type, c.id) }
                .onFailure { warn("streams failed: ${b.substringAfter("://").substringBefore('/')} ($it)") }.getOrDefault(emptyList())
        }
        val start = System.currentTimeMillis()
        val got = ArrayList<Stremio.Stream>()
        var done = 0
        var hit: Stremio.Stream? = null
        while (done < sources.size && hit == null) {
            // Every source is waited for, unless one in the preferred quality plays first: without it, the best a step
            // below (then the next, and so on) is picked out of them all.
            val limit = start + VOD_ALL_MS
            val answer = answers.poll((limit - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: break
            done++
            val more = runCatching { answer.get() }.getOrDefault(emptyList()).filter { s -> got.none { it.url == s.url } }
            got += more
            // Its best few at the preferred quality (or above, for the highest) checked: the first that answers plays.
            hit = more.filter { s -> s.langRank == Stremio.LANG_OURS && streamQuality(s.title) == preferred }
                .sortedWith(qualityOrder(preferred)).take(3)
                .firstOrNull { s -> (probes[s.url] ?: Stremio.probe(s.url, s.headers).also { probes[s.url] = it }).ok }
        }
        // The sources still looking: their streams come in the background, checked, at the end of the list.
        val left = sources.size - done
        if (left > 0) {
            ui.post { lateLeft += left }
            repeat(left) { lateWaits.execute { late(runCatching { answers.poll(VOD_LATE_MS, TimeUnit.MILLISECONDS)?.get() }.getOrNull()) } }
        }
        val found = hit ?: return got.also { if (it.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to it } to false
        val list = listOf(found) + got.filter { it.url != found.url }.sortedWith(compareBy<Stremio.Stream> { it.langRank }.then(qualityOrder(preferred)))
        fetched[c.id] = System.currentTimeMillis() to list
        return list to true
    }

    private fun streamsOf(c: Channel, late: ((List<Stremio.Stream>?) -> Unit)? = null): List<Stremio.Stream> {
        fetched[c.id]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        if (vod) return titleStreams(c, late)
        // The channel's add-on, then the Nuvio plugins that can play it (those for TV channels, by its id).
        val own = runCatching { StreamCache.get(cacheDir, base, c.type, c.id) { Stremio.streams(base, c.type, c.id) } }.onFailure { warn("streams failed: $it") }.getOrDefault(emptyList())
        val plugins = pluginSources(prefs, filesDir, c.type, c.id, adultShows(prefs)).flatMap { (b, name) ->
            runCatching { cachedStreams(prefs, filesDir, cacheDir, b, name, c.type, c.id) }.getOrDefault(emptyList())
        }
        return (own + plugins).also { if (it.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to it }
    }

    /**
     * A film's or episode's streams from every stream add-on at once, in the add-ons' order (each its best first),
     * waiting for the slow ones up to VOD_SOME_MS, or VOD_WAIT_MS while none has any; the same kind as the one playing
     * (its bingeGroup) first.
     */
    private fun titleStreams(c: Channel, late: ((List<Stremio.Stream>?) -> Unit)? = null): List<Stremio.Stream> {
        val asked = sources.map { (b, name) ->
            pool.submit(Callable { runCatching { cachedStreams(prefs, filesDir, cacheDir, b, name, c.type, c.id) }.onFailure { warn("streams failed: ${b.substringAfter("://").substringBefore('/')} ($it)") }.getOrDefault(emptyList()) })
        }
        val start = System.currentTimeMillis()
        val got = arrayOfNulls<List<Stremio.Stream>>(asked.size)
        val slow = ArrayList<java.util.concurrent.Future<List<Stremio.Stream>>>()
        // Without quick start (or for the highest quality) every source is waited for, to pick the best of all.
        val waitAll = !quickStart(prefs)
        for ((i, f) in asked.withIndex()) {
            val limit = start + if (waitAll) VOD_ALL_MS else if (got.any { !it.isNullOrEmpty() }) VOD_SOME_MS else VOD_WAIT_MS
            got[i] = runCatching { f.get((limit - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) }.getOrElse {
                // Too slow to wait for before playing: played as it comes (see late), or dropped when no one waits.
                if (late != null) slow += f else f.cancel(true)
                null
            }
        }
        if (late != null && slow.isNotEmpty()) {
            ui.post { lateLeft += slow.size }
            for (f in slow) lateWaits.execute { late(runCatching { f.get(VOD_LATE_MS, TimeUnit.MILLISECONDS) }.getOrNull()) }
        }
        val all = got.flatMap { it.orEmpty() }.distinctBy { it.url }
        val list = group?.let { g -> all.sortedByDescending { it.group == g } } ?: all
        if (list.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to list
        return list
    }

    /**
     * A film's [list], those that answer with a video first: each checked at once (see Stremio.probe), so a run of dead
     * links and web pages (some plugins hand many) is skipped without waiting on each; the rest after, as they were.
     * Kept, so the player doesn't check one again. Off the UI thread.
     */
    private fun checked(list: List<Stremio.Stream>): List<Stremio.Stream> {
        if (!vod || list.size < 2) return list
        val todo = list.filter { !probes.containsKey(it.url) }
        runCatching {
            checks.invokeAll(todo.map { s -> Callable { probes[s.url] = Stremio.probe(s.url, s.headers) } }, CHECK_MS, TimeUnit.MILLISECONDS)
        }
        // Answering first, then not yet known (still being checked when time ran out), then those that failed; in each,
        // the best quality first (as its name says it).
        return list.sortedWith(streamOrder())
    }

    /**
     * A source that answered after the first streams came, with [more] (null: none): its streams go at the end of the
     * list, and, when all so far failed and the player waits for them, the next of them plays.
     */
    private fun late(tuning: Int, more: List<Stremio.Stream>?) {
        if (isFinishing || tuning != tune) return
        lateLeft = (lateLeft - 1).coerceAtLeast(0)
        val known = streams.map { it.url }.toSet()
        val new = more.orEmpty().filter { it.url !in known }.distinctBy { it.url }
        if (new.isNotEmpty()) {
            streams = streams + new
            channels.getOrNull(at)?.let { c -> fetched[c.id] = System.currentTimeMillis() to streams }
        }
        // Waiting for one in the TV's language: it came, or every source has answered; the best of all then plays.
        if (holdForLanguage) {
            if (new.none { it.langRank == Stremio.LANG_OURS } && lateLeft > 0) return
            holdForLanguage = false
            streams = streams.sortedWith(streamOrder())
            tried = -1
            attempts = 0
            why = null
            return next()
        }
        if (!waitingLate) return
        if (new.isNotEmpty()) {
            waitingLate = false
            tried = attempts - 1 // the first of the new ones
            next()
        } else if (lateLeft == 0) {
            waitingLate = false
            say(noStreams(), busy = false)
        }
    }

    /** The streams of the channels either side, so CH+ and CH- don't wait for the add-on (some check each stream first: seconds). */
    private fun prefetch(i: Int) {
        if (vod) return run { if (i + 1 < channels.size) channels[i + 1].let { c -> ahead.execute { streamsOf(c) } } } // the next episode only
        if (channels.size < 2) return
        for (j in listOf(Math.floorMod(i + 1, channels.size), Math.floorMod(i - 1, channels.size)).distinct()) {
            val c = channels[j]
            ahead.execute { streamsOf(c) }
        }
    }

    /**
     * Tries the stream after the one that failed (the first, at the start), going round the list; once each was
     * tried since the channel was tuned (or one was picked), says none works.
     */
    private fun next() {
        ui.removeCallbacks(stuck)
        // A film's stream that fails (or another picked) carries on where the last one got to.
        if (vod) player?.let { if (it.currentPosition > 5000) pendingSeek = it.currentPosition }
        if (streams.isEmpty() || ++attempts > streams.size) {
            release()
            // The stream it played, kept to resume with, no longer plays (an address that expired): a search after all.
            if (searchIfChosenFails) {
                searchIfChosenFails = false
                channels.getOrNull(at)?.let { fetched.remove(it.id) }
                return play(at)
            }
            attempts = streams.size // the next one to come is the next to try
            // Sources still looking (slow plugins): their streams are waited for before saying none works.
            if (lateLeft > 0) {
                waitingLate = true
                return say(getString(R.string.player_finding_more))
            }
            return say(noStreams(), busy = false)
        }
        tried = Math.floorMod(tried + 1, streams.size)
        val s = streams[tried]
        say(if (streams.size > 1) getString(R.string.player_stream_n, tried + 1, streams.size) else getString(R.string.player_loading))
        val tuning = tune
        val n = tried
        net.execute {
            // A check that fails is a stream that doesn't answer: the next is tried (never the player closing).
            // Another app's (a YouTube video's watch link): opened, not asked as a stream.
            val probe = if (s.app != null) Stremio.Probe(true, null) else probes[s.url] ?: runCatching { Stremio.probe(s.url, s.headers) }.getOrElse { Stremio.Probe(false, null) }.also { probes[s.url] = it }
            ui.post {
                if (isFinishing || tuning != tune || n != tried) return@post
                // The only stream (one picked by hand) is played even when the check failed: some servers turn the
                // check away (too many requests) and not the player.
                if (!probe.ok && streams.size > 1) { why = probe.why ?: why; next() } else start(s, probe.format)
            }
        }
    }

    private fun start(s: Stremio.Stream, format: String?, noAudio: Boolean = false, agent: String? = null, restarts: Int = 0) {
        playing = Attempt(s, format, noAudio, agent, restarts = restarts)
        // Another app's (externalUrl, ytId): it plays there, with the app the extension names first; Back comes here.
        if (s.app != null) {
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s.url))
            val withApp = s.app.takeIf { it.isNotEmpty() }?.let { android.content.Intent(view).setPackage(it) }?.takeIf { packageManager.queryIntentActivities(it, 0).isNotEmpty() }
            if (runCatching { startActivity(withApp ?: view) }.isSuccess) return finish()
            warn("stream ${tried + 1}: no app opens ${s.url.substringBefore(':')}")
            return next()
        }
        release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // some add-ons' addresses go from https to a proxy's http, and back
            // Not what the connection sets itself: a plugin's Accept-Encoding: gzip would hand the player packed bytes.
            .setDefaultRequestProperties(s.headers.filterKeys { k -> SKIPPED_HEADERS.none { it.equals(k, ignoreCase = true) } })
        (agent ?: s.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value)?.let { http.setUserAgent(it) }
        // Sound as a film's, through the output the TV has chosen (its speakers, a soundbar or receiver on HDMI ARC or
        // optical, Bluetooth): taking the audio focus, so the TV routes it there and other sound stops; pausing when
        // headphones or a Bluetooth speaker go away. The sink follows the output's changes and what it can decode
        // (Dolby, DTS passed through to a receiver that can).
        // The web through [http]; a playlist written in its address (data:, from a Nuvio plugin) read as it is.
        // DASH protected by ClearKey (an extension's drm.clearkey): its keys, here, as the license.
        val drm = s.clearkey.takeIf { it.isNotEmpty() }?.let { keys ->
            fun b64(hex: String) = android.util.Base64.encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val license = JSONObject().put("type", "temporary").put("keys", org.json.JSONArray(keys.map { (kid, key) ->
                JSONObject().put("kty", "oct").put("kid", b64(kid)).put("k", b64(key))
            }))
            androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(androidx.media3.exoplayer.drm.LocalMediaDrmCallback(license.toString().toByteArray()))
        }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)).apply { drm?.let { m -> setDrmSessionManagerProvider { m } } }
        // Sound the device has no decoder for (Dolby and DTS on a phone or tablet, TrueHD or DTS on many TVs): FFmpeg's,
        // after the device's own, so a TV's still passes Dolby on to a receiver (see build.gradle).
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        applyChoices(p)
        // Sound the TV can't decode: the picture alone, rather than nothing.
        if (noAudio) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(stuck)
                        // Its first start (not one started again after a stall): the banner comes up, as OK brings it.
                        val started = playing?.let { !it.played && it.restarts == 0 } == true
                        playing?.played = true
                        // Sound only (a radio station): its logo and name stay, on the blur of its logo, not a black screen.
                        soundOnly = p.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }
                        say(if (soundOnly) onAir.orEmpty() else null, busy = false)
                        if (vod && p.playWhenReady && !scrobbling) ui.post { scrobble("start") } // once it has sought to where it resumes
                        if (pendingSeek > 0) p.seekTo(pendingSeek).also { pendingSeek = -1L }
                        if (vod) group = streams.getOrNull(tried)?.group else remember(channels[at])
                        if ((started || banner.visibility == View.VISIBLE) && shown == at) showBanner(at)
                    }
                    // Waiting too long, at the start or later, is a stream that's stuck: the next one.
                    Player.STATE_BUFFERING -> {
                        ui.removeCallbacks(stuck)
                        ui.postDelayed(stuck, if (vod) VOD_STUCK_MS else STUCK_MS)
                        // Loading again once it played (after a seek, or a stall): the loading screen again, until it plays.
                        if (playing?.played == true) say(getString(R.string.player_loading))
                    }
                    Player.STATE_ENDED -> if (vod) ended()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A live stream fallen too far behind starts again from now; anything else, the next stream.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // One that played and lost the network for a moment: the same again (see again).
                if (error.errorCode in NETWORK_ERRORS && again("lost the network")) return
                val a = playing
                // Sound the TV can't decode (MP2, AC3… on a device without them): the same with the picture alone.
                val renderer = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }
                if (a != null && !a.noAudio && renderer?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true) {
                    warn("stream ${tried + 1}: sound ${renderer.rendererFormat?.sampleMimeType} not decoded here, picture alone")
                    android.widget.Toast.makeText(this@PlayerActivity, getString(R.string.player_no_audio), android.widget.Toast.LENGTH_LONG).show()
                    return start(a.s, a.format, noAudio = true, agent = a.agent)
                }
                // Turned away (401, 403) with no User-Agent of its own: some servers take only players' (VLC's).
                val status = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (a != null && a.agent == null && status in listOf(401, 403) && s.headers.keys.none { it.equals("User-Agent", true) }) {
                    warn("stream ${tried + 1}: $status, again as VLC")
                    return start(a.s, a.format, a.noAudio, agent = VLC_AGENT)
                }
                why = status?.let { Stremio.whyOf(it) } ?: Stremio.whyOf(error.cause)
                    ?: if (renderer != null || error.errorCode in FORMAT_ERRORS) Stremio.Why.FORMAT else null
                // Which one, from where, taken as what; and, when it couldn't be read, what the server handed back.
                val n = tried + 1
                warn("stream $n failed (${error.errorCodeName}): ${s.source ?: "?"} ${s.url.substringAfter("://").substringBefore('/').take(40)} as ${format ?: "file"}")
                if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !s.url.startsWith("data:")) net.execute {
                    runCatching {
                        val c = Http.connect(s.url, s.headers)
                        val head = ByteArray(80)
                        val got = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.use { it.read(head) } ?: 0
                        warn("stream $n answer: ${c.responseCode} ${c.contentType} at ${c.url.host}: " +
                            String(head, 0, got.coerceAtLeast(0), Charsets.ISO_8859_1).map { if (it in ' '..'~') it else '.' }.joinToString(""))
                        c.disconnect()
                    }
                }
                next()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                fit(size)
                if (banner.visibility == View.VISIBLE && shown == at) fillSource(at)
            }

            override fun onCues(cueGroup: CueGroup) = showCues(cueGroup.cues)

            // Paused and played again: Trakt keeps up (it shows the user watching, and where they paused).
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (vod && p.playbackState == Player.STATE_READY) scrobble(if (playWhenReady) "start" else "pause")
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyLivePreferences(p, allowAudio = !noAudio)
                if (banner.visibility == View.VISIBLE && shown == at) { fillTrackLine(at); fillButtons(at) }
            }

            // The song on air, as the stream tells it as it plays: under the station's name.
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                val song = songOf(metadata) ?: return
                if (song == onAir) return
                onAir = song
                if (soundOnly && p.playbackState == Player.STATE_READY) status.text = song
            }
        })
        val item = MediaItem.Builder().setUri(s.url).apply {
            when (format) {
                "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
                "ts", "m2ts" -> setMimeType(MimeTypes.VIDEO_MP2T)
            }
            if (drm != null) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
        }.build()
        // HLS read so that audio timed hours off its video is moved onto it (see HlsAlign): else the player waits for it.
        if (format == "hls") p.setMediaSource(androidx.media3.exoplayer.hls.HlsMediaSource.Factory(DefaultDataSource.Factory(this, http))
            .setExtractorFactory(HlsAlign()).apply { drm?.let { m -> setDrmSessionManagerProvider { m } } }.createMediaSource(item))
        // A Blu-ray's MPEG-TS read as plain MPEG-TS, its packets' times left out (see M2ts).
        else if (format == "m2ts") p.setMediaSource(DefaultMediaSourceFactory(M2ts.Factory(DefaultDataSource.Factory(this, http))).createMediaSource(item))
        else p.setMediaItem(item)
        p.playWhenReady = true
        p.prepare()
        ui.postDelayed(stuck, if (vod) VOD_STUCK_MS else STUCK_MS)
    }

    /** That no stream plays, and why the last one tried didn't, when that's known (a certificate, the server…). */
    private fun noStreams(): String {
        val reason = when (why) {
            Stremio.Why.CERTIFICATE -> R.string.player_why_certificate
            Stremio.Why.DENIED -> R.string.player_why_denied
            Stremio.Why.MISSING -> R.string.player_why_missing
            Stremio.Why.UNREACHABLE -> R.string.player_why_unreachable
            Stremio.Why.FORMAT -> R.string.player_why_format
            Stremio.Why.STUCK -> R.string.player_why_stuck
            null -> null
        }
        return getString(R.string.player_no_streams) + (reason?.let { "\n" + getString(it) } ?: "")
    }

    private fun release() {
        ui.removeCallbacks(stuck)
        ui.removeCallbacks(hardSeek)
        hardSeekTo = -1L
        player?.release()
        player = null
        soundOnly = false // the next stream (or channel) says its own
        onAir = null
        if (::subtitles.isInitialized) subtitles.removeAllViews()
    }

    /** The picture as large as the screen allows, at its own shape (black bars rather than stretched). */
    private fun fit(size: VideoSize) {
        if (size.width == 0 || size.height == 0) return
        lastSize = size
        val root = surface.parent as View
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        val wider = root.width.toFloat() / root.height > ratio
        // Fitting: all of the picture, with bars; zoomed (pinch): all of the screen, the picture cut at its edges.
        val (w, h) = if (wider != zoomed) (root.height * ratio).toInt() to root.height else root.width to (root.width / ratio).toInt()
        surface.layoutParams = (surface.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }
        // Placed over the picture, but never past the screen's edges, where they'd be cut.
        subtitles.layoutParams = (subtitles.layoutParams as FrameLayout.LayoutParams).apply { width = minOf(w, root.width); height = minOf(h, root.height) }
    }

    /** [text] a moment in the middle of the picture: brightness, volume, zoom. */
    private fun flash(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, 900)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    /** A finger: the controls locked, for watching without touching them by mistake; the 🔓 unlocks them. */
    private fun lockControls() {
        locked = true
        hideBanner.run()
        showUnlock()
    }

    private fun showUnlock() {
        unlock.visibility = View.VISIBLE
        ui.removeCallbacks(hideUnlock)
        ui.postDelayed(hideUnlock, 3000)
    }

    private val hideUnlock = Runnable { unlock.visibility = View.GONE }

    /** Leaving the player on a phone or tablet while it plays: it goes on in a small window over the other apps. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!handheld || android.os.Build.VERSION.SDK_INT < 26 || player?.isPlaying != true) return
        val s = lastSize
        val ratio = s?.let { android.util.Rational((it.width * it.pixelWidthHeightRatio).toInt().coerceAtLeast(1), it.height.coerceAtLeast(1)) }
            ?.takeIf { it.toFloat() in 0.42f..2.39f } ?: android.util.Rational(16, 9)
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
            .onFailure { warn("picture in picture: $it") }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) {
            hideBanner.run()
            closeTracks()
            hint.visibility = View.GONE
            unlock.visibility = View.GONE
        }
    }

    /** The channel that plays, for Continue Playing and the add-on's tile (see Addons.lastAddonChannel): a file both processes read. */
    private fun remember(c: Channel) {
        if (isAdult()) return
        ahead.execute {
            runCatching {
                val o = JSONObject().put("addon", addon).put("type", c.type).put("id", c.id).put("name", c.name)
                    .put("logo", c.logos.firstOrNull().orEmpty()).put("at", System.currentTimeMillis())
                val f = File(filesDir, LAST_FILE)
                val part = File(filesDir, "$LAST_FILE.part")
                part.writeText(o.toString())
                part.renameTo(f)
            }
        }
    }

    // Live TV: nothing to come back to, so leaving the player closes it; a film is kept where it got to.
    override fun onStop() {
        super.onStop()
        saveProgress()
        scrobble("stop")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        breath?.cancel()
        release()
        net.shutdownNow()
        ahead.shutdown()
        if (vod) pool.shutdownNow()
    }

    companion object {
        const val ADDON = "addon" // the add-on's manifest address
        const val BASE = "base" // the add-on's address, without /manifest.json
        const val INDEX = "index" // the channel picked, in the lists below
        const val CHOSEN = "chosen" // a film's or episode's stream picked by hand, as streamJson writes it
        const val CHOSEN_ELSE_SEARCH = "chosen_else_search" // the chosen one is the one kept to resume with: if it fails, search

        /** [s] for an Intent: its address, name, headers, source, ClearKey keys and the app that opens it. */
        internal fun streamJson(s: Stremio.Stream): JSONObject = JSONObject().put("url", s.url).put("title", s.title)
            .put("headers", JSONObject(s.headers as Map<*, *>)).put("group", s.group ?: JSONObject.NULL).put("source", s.source ?: JSONObject.NULL)
            .put("clearkey", JSONObject(s.clearkey as Map<*, *>)).put("app", s.app ?: JSONObject.NULL).put("lang", s.langRank)

        internal fun streamOf(j: JSONObject): Stremio.Stream {
            val h = j.optJSONObject("headers") ?: JSONObject()
            val k = j.optJSONObject("clearkey") ?: JSONObject()
            return Stremio.Stream(j.getString("url"), j.optString("title"), h.keys().asSequence().associateWith { h.optString(it) },
                j.optString("group").takeIf { !j.isNull("group") && it.isNotEmpty() }, j.optString("source").takeIf { !j.isNull("source") && it.isNotEmpty() },
                j.optInt("lang", Stremio.LANG_OURS), k.keys().asSequence().associateWith { k.optString(it) }, if (j.isNull("app")) null else j.optString("app"))
        }
        const val TYPES = "types"
        const val IDS = "ids"
        const val NAMES = "names"
        const val NOWS = "nows" // what's on, as the add-on's catalog says
        const val LOGOS = "logos" // the add-on's
        const val FALLBACK_LOGOS = "fallback_logos" // Zephyr TV's own, by name (see ChannelLogos)
        const val LAST_FILE = "last_channel.json"
        const val MODE = "mode" // MODE_VOD for films and episodes; none for TV channels
        const val MODE_VOD = "vod"
        const val SOURCES = "sources" // films and episodes: the stream add-ons' addresses (without /manifest.json), in order
        const val SOURCE_NAMES = "source_names"
        const val DESCS = "descs" // what each film or episode is about
        const val META_TYPE = "meta_type" // the title's type and id ("series", "tt…"), for its page from Continue Playing
        const val META_ID = "meta_id"
        const val POSTER = "poster" // the title's art, for its Continue Playing card
        const val TITLE_LOGO = "title_logo" // a film's or series' logo, over its art in the player
        const val USER_DIR = "user_dir" // the folder of the user watching (see Users): their progress, watched titles, Trakt
        const val ADULT = "adult" // whether this content is from an adult add-on (no history, no continue watching)
        const val ADULT_SOURCES = "adult_sources" // names and addresses of adult stream add-ons
        private const val VOD_WAIT_MS = 20_000L // a film's slower add-ons (an aggregator) are waited for this long
        private const val CHECK_MS = 15_000L // a film's streams checked all at once, at most this long
        private const val VOD_ALL_MS = 30_000L // ...or, to pick the best of them all, every one up to this long
        private const val VOD_LATE_MS = 40_000L // ...and those that didn't, waited for this long while it plays
        private const val VOD_SOME_MS = 12_000L // ...or this long, once one has given streams
        private const val VOD_STUCK_MS = 30_000L // a film's stream buffering longer than this: the next
        private const val CHOICES_FILE = "player" // not K.FILE: the home screen's process writes that one whole
        private const val TEXT_LANG = "text_lang" // "" for off; none for the stream's own choice
        private const val BANNER_MS = 5000L
        private const val HARD_SEEK_MS = 400L // a Dolby or DTS stream's seek, once the keys stop (see hardSeek)
        private const val NEXT_IN = 10 // seconds before the next episode starts by itself
        private val PASSTHROUGH = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_TRUEHD)
        private const val BUTTONS_MS = 10_000L // the banner stays up this long after a key on its buttons
        private const val ZAP_MS = 700L // the keys rest this long before the channel is tuned
        private const val STUCK_MS = 15_000L // buffering longer than this: the next stream
        private const val RESTARTS = 2 // a stream that played and stalls, started again at most this many times
        private const val VLC_AGENT = "VLC/3.0.20 LibVLC/3.0.20" // what servers that take only players' User-Agent take
        private val NETWORK_ERRORS = listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        private val FORMAT_ERRORS = listOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        private val SKIPPED_HEADERS = listOf("User-Agent", "Accept-Encoding", "Content-Length", "Host", "Connection")
        private const val FRESH_MS = 90_000L // fetched streams are used this long (their addresses can expire)
    }
}

/**
 * The song on air in [m]: an Icecast or Shoutcast station's title (ICY), or an HLS one's ID3 tags (title and
 * artist, or the group some stations write "Artist - Song" in). Null when it says none.
 */
@OptIn(UnstableApi::class)
internal fun songOf(m: androidx.media3.common.Metadata): String? {
    var icy: String? = null
    var title: String? = null
    var artist: String? = null
    var group: String? = null
    for (i in 0 until m.length()) when (val e = m.get(i)) {
        is androidx.media3.extractor.metadata.icy.IcyInfo -> icy = e.title
        is androidx.media3.extractor.metadata.id3.TextInformationFrame -> when (e.id) {
            "TIT2" -> title = e.values.firstOrNull()
            "TPE1" -> artist = e.values.firstOrNull()
            "TIT1" -> group = e.values.firstOrNull()
        }
    }
    val song = icy ?: title?.let { t -> listOfNotNull(artist?.takeIf { it.isNotBlank() }, t).joinToString(" - ") } ?: group
    return song?.trim()?.takeIf { it.isNotEmpty() }
}
