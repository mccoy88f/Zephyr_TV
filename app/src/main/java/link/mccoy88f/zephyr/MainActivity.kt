package link.mccoy88f.zephyr

import link.mccoy88f.zephyr.SettingsRows.DARK_WP_ROW
import link.mccoy88f.zephyr.SettingsRows.DEFAULT_HOME_ROW
import link.mccoy88f.zephyr.SettingsRows.LIGHT_WP_ROW
import link.mccoy88f.zephyr.SettingsRows.PHOTO_EVERY_ROW
import link.mccoy88f.zephyr.SettingsRows.ROW_APPS_ROW
import link.mccoy88f.zephyr.SettingsRows.SCREENSAVER_ROW
import link.mccoy88f.zephyr.SettingsRows.SECTION
import link.mccoy88f.zephyr.SettingsRows.THEME_ROW
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.Executors

/** Allowed to start an activity from the background because the Home button service is bound. */
internal const val CW_PKG = "piume:continue_watching"
internal const val SEARCH_PKG = "piume:search" // the search results tile, after Continue Watching while a search is open

class MainActivity : Activity() {

    // picture: the tile image for Zephyr TV's own apps (one logo for each kind of content), centered in the card; the
    // icon stays the small mark on cards.
    internal class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable, val offline: Boolean = false,
                       val picture: Drawable? = null, val sublabel: String? = null)
    internal class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?, val weight: Long = 0, val badge: String? = null, val logo: String? = null, val poster: String? = null, val channelPage: ChannelDetail? = null, val channelId: Long? = null, val playing: Boolean = false, val addon: AddonChannel? = null, val icon: String? = null, val vod: TitleRef? = null, val cinema: Boolean = false) // icon: the tile whose icon marks the card, when not pkg's; vod: a film or series (see Titles); cinema: a film still only in cinemas

    internal val prefs by lazy { getSharedPreferences(K.FILE, MODE_PRIVATE) }
    internal val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    internal val posterIo = Executors.newFixedThreadPool(3)
    // Network work that can take a while (weather, update checks and downloads, city search) has its own threads,
    // so an update download or a slow weather server never holds up the rows, which io reads from the TV.
    internal val netIo = Executors.newFixedThreadPool(2)
    // What takes long and nobody waits for (the TV guide, the channel logos' table, an update's download, a Trakt
    // sync): on a thread of its own, so a title's page or a search never queues behind it.
    internal val longIo = Executors.newSingleThreadExecutor()
    // Spotlight's own thread: downloading the next backdrop ahead mustn't hold up the rows' data on io.
    internal val spotlightIo = Executors.newSingleThreadExecutor()
    internal var lastInput = SystemClock.elapsedRealtime() // last key press, for pausing Spotlight when nobody watches
    internal val drag = HomeDrag() // a finger on the home screen (see Touch)
    internal var spotlightIdle = false
    internal var nightShown = false // night mode's dimming is showing
    internal val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    internal val ui = Handler(Looper.getMainLooper())
    internal val posters = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { // decoded: rows scrolled back show at once
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    internal var apps = APPS ?: emptyList()
    internal var appsDirty = APPS == null
    internal var watchKey: String? = null
    internal var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    internal var lastApp: View? = null
    internal var spotlightFolded = false // focus in the rows under the apps: Spotlight folded away, its carousel still (see foldSpotlight)
    internal lateinit var rowsVeil: RowsVeil
    internal var lastCard: View? = null
    internal var editing = false
    internal var grabbed: View? = null
    internal var holdFired = false
    internal var holdView: View? = null
    internal val holdTick = Runnable {
        val v = holdView
        if (!holdFired && v != null && v === currentFocus) fireHold(v)
    }
    internal var sceneAlive = false
    internal val scene = Scene(this)
    internal var dark = false
    internal var glass = false // the Glass theme: dark, with glossy see-through cards over the wallpaper
    internal var fg = Color.BLACK
    internal var ground = Color.WHITE
    internal var onFg = Color.WHITE // text and icons on an fg-coloured fill (focused small controls)
    internal var field = Color.WHITE // input boxes (PIN, API key) on the settings panel
    internal var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    internal var cardLit = cardRest

    internal lateinit var appRow: LinearLayout
    internal lateinit var watchRow: LinearLayout
    internal lateinit var watchTitle: TextView
    internal val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    internal lateinit var watchScroll: View
    internal val moreSections by lazy { findViewById<LinearLayout>(R.id.moreSections) }
    internal var sectionCache: Pair<String, List<Section>>? = null // the last app queried and all its sections
    internal var watchApp: String? = null // whose rows are under the apps (CW_PKG for Continue Watching), null for none yet
    internal val sectionCounts = HashMap<String, Int>() // rows each app showed last time, for its loading placeholder
    internal lateinit var allApps: View
    internal lateinit var allAppsGrid: GridLayout
    internal lateinit var wifi: ImageView
    internal lateinit var spotlightContainer: View
    internal lateinit var spotlightInfoBox: View
    internal lateinit var spotlightTitle: TextView
    internal lateinit var spotlightMeta: TextView
    internal lateinit var spotlightOverview: TextView
    internal lateinit var spotlightActions: LinearLayout
    internal lateinit var spotlightPrev: ImageView
    internal lateinit var spotlightNext: ImageView
    internal lateinit var bottomSection: View

    // Written on the io thread (decodeWallpaper), read on the UI thread.
    @Volatile internal var tmdbTrending: List<TmdbItem> = emptyList()
    @Volatile internal var tmdbIndex = 0
    @Volatile internal var tmdbFetchedAt = 0L
    internal var spotlightApps: List<App>? = null // apps behind the Spotlight buttons; null rebuilds them
    internal var tmdbLastFocusApp: String? = null
    internal var searchQuery: String? = null // the last search, while its results tile is in the apps row; null for none
    internal var lastSearchWord: String = "" // the words typed in the search box for the active search
    internal var searchFilter = SearchFilter() // the search's filters: films or series, year, genre (see Search)
    internal var searchChannels: List<ChannelDetail> = emptyList() // the TV channels the search found
    internal var searchTitles: List<Pair<Stremio.Title, String>> = emptyList() // without a TMDB key, the films and series the add-ons found (and from which)
    internal var searchLoading = 0 // the search under way (its number), its tile up with its words while its results come; 0 for none
    internal var searchCount = 0 // searches started, to tell the last one's results from an earlier one's
    internal var recognizer: android.speech.SpeechRecognizer? = null // dictating into the search box
    internal var afterMicAccess: (() -> Unit)? = null // what asked, while Android's microphone prompt is up
    internal val appChannelDebounce = Runnable { queryAppChannels(tmdbLastFocusApp) }

    internal val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            forgetChannelApps() // a TV app may have come or gone (see TvApp)
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it); ACCENTS.remove(it) } // an update may bring a new icon
        }
    }

    internal val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Safety.watch(applicationContext)
        val calmed = Safety.calm(this)
        Problems.init(File(filesDir, "problems.log"))
        if (migratePins(prefs)) toast(R.string.pin_merged_toast) // one PIN for everything now (see Pins)
        dropUnusedPin() // after a restore, say: nothing may use it any more
        Images.use(this) // images on disk (see Images): every part of Zephyr TV goes through it
        Js.useAssets(applicationContext) // the modules plugins and extensions require()
        Nuvio.process = ScriptProcess.plugins(this) // plugins run in a process of their own
        ExtensionCalls.use(this) // extensions answer as add-ons, from their own process
        setContentView(R.layout.activity_main)
        bindViews()
        applyTheme()
        applyClock()
        setupSpotlight() // after applyTheme: its veil and arrow discs use the theme's colours
        touchSetup() // phones and tablets: the page and Spotlight with a finger
        warpAgain() // WARP on before: up again
        loadWallpaper()

        setupTopBar()
        showInputsButton() // gone where there are no inputs (a stick, a phone) or they're tiles in the apps row
        showUserIcon() // the user in use's letters, in place of the icon
        openFirstScreen()

        registerWatchers()

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
        shortcuts() // a phone or tablet: Search and the Play queue on the icon
        ui.post { handleLink(intent) } // started by a link, a share or a shortcut (see Links)
        if (calmed) ui.post { Safety.tell(this) }
        Remote.attach(this) // remote access on: the phone's page is served again after a restart
    }

    /** The views the home screen keeps at hand, from its layout. */
    private fun bindViews() {
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        watchScroll = findViewById(R.id.watchScroll)
        bottomSection = findViewById(R.id.bottomSection)
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        spotlightContainer = findViewById(R.id.spotlightContainer)
        spotlightInfoBox = findViewById(R.id.spotlightInfoBox)
        spotlightTitle = findViewById(R.id.spotlightTitle)
        spotlightMeta = findViewById(R.id.spotlightMeta)
        spotlightOverview = findViewById(R.id.spotlightOverview)
        spotlightActions = findViewById(R.id.spotlightActions)
        spotlightPrev = findViewById(R.id.spotlightPrev)
        spotlightNext = findViewById(R.id.spotlightNext)
    }

    /** What opens first: the panel row a restart came back to, Piume's settings once, or the Home setup once. */
    private fun openFirstScreen() {
        if (prefs.contains(K.REOPEN_ROW)) {
            val row = prefs.getInt(K.REOPEN_ROW, THEME_ROW)
            prefs.edit().remove(K.REOPEN_ROW).apply()
            ui.post { openLauncherSettings(row) }
        } else if (takeFromPiume()) {
            // Piume's settings and history first (the screen is built again with them); the Home setup after.
        } else if (!homeServiceOn() && !prefs.getBoolean(K.SETUP_SHOWN, false)) {
            // Fresh install: pick the catalog of films and series, then walk through making Home land on Zephyr TV,
            // the one step that has to happen by hand.
            prefs.edit().putBoolean(K.SETUP_SHOWN, true).apply()
            ui.post { catalogPopup { startHomeSetup() } }
        }
    }

    /** What the home screen follows while it lives: standby, apps installed or removed, the network, the TV inputs. */
    private fun registerWatchers() {
        registerReceiver(adultRelock, AdultRelock.FILTER) // standby locks 18+ again (see AdultLock)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).let { cm ->
            // No network at all calls nothing back: start from what there is now (the red dot when offline).
            showNet(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
            cm.registerDefaultNetworkCallback(net)
        }
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)
        watchSessions()
        // Sony's table changes when a device is plugged in or out: the dot follows (see connectedInputs).
        runCatching { contentResolver.registerContentObserver(SONY_INPUTS, true, inputsObserver) }
    }

    /**
     * The screen's size changed (a phone: the player hid its bars, the status bar shown or hidden, the device turned
     * round): the home screen stays as it is, with the page and the app it had open, instead of being made again.
     * The apps row's tiles take the new width.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val width = resources.displayMetrics.widthPixels
        if (shownWidth != 0 && width != shownWidth) renderApps()
        shownWidth = width
    }
    private var shownWidth = 0

    override fun onResume() {
        super.onResume()
        Screen.activity = this
        if (shownWidth == 0) shownWidth = resources.displayMetrics.widthPixels
        applyStatusBar()
        ensureHomeService()
        homeSetupResumed()
        checkAdultLock()
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        // Back from the player a title's page opened: the page again (its progress, Resume), where it was.
        pageReturn?.let { again ->
            pageReturn = null
            if (settingsScrim.visibility == View.VISIBLE) again()
        }
        phonePanel.pending?.let { phonePanel.pending = null; it() }
        watchSessions() // once allowed (see NowPlaying): the Play queue follows what's playing
        refreshExtensions() // the preinstalled ones, and a newer version of each, once a day
        ui.post(playingTick)
        refreshWatchNext()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        ui.removeCallbacks(nightTick)
        ui.post(nightTick)
        ui.removeCallbacks(wallpaperTick)
        ui.post(wallpaperTick)
        lastInput = SystemClock.elapsedRealtime() // back from an app: someone is here
        syncTraktNow() // what the user watched elsewhere, now and then
        piumeGoesNow() // Piume's things taken and the Home set: Piume can go
        spotlightIdle = false
        sceneAlive = true
        scene.arm()
        restartSpotlightTick()
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && photoScreensaver() == null && !paintingsReady(this)) downloadPaintings {}
        checkUpdate(force = false)
        ui.post { updateBadge(); updateInputsDot() } // after layout, so the icons have their size
    }

    /** Night mode starts and ends on time even while the home screen just sits there. */
    internal val nightTick = object : Runnable {
        override fun run() {
            applyNight()
            ui.postDelayed(this, 60_000L)
        }
    }

    /** Auto theme flips at 7:00 and 19:00 even while the home screen just sits there. */
    internal val themeTick = object : Runnable {
        override fun run() {
            // Rebuilding the screen while someone uses it would throw them out of the panel or a half-done move.
            // Wait until it's been left alone for a minute.
            val busy = settingsScrim.visibility == View.VISIBLE || allApps.visibility == View.VISIBLE || editing ||
                setupDialog != null || SystemClock.elapsedRealtime() - lastInput < 60_000L
            if (wantDark() != dark && !busy) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == MIC_REQUEST) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterMicAccess?.invoke()
            else toast(R.string.toast_mic_needed)
            afterMicAccess = null
            return
        }
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else toast(R.string.toast_photo_access_needed)
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == FILE_REQUEST) return fileResult(result, data)
        if (code == VPN_REQUEST) return vpnAnswer(result == RESULT_OK)
        if (code == ROLE_REQUEST) refreshSettings(DEFAULT_HOME_ROW)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        if (Screen.activity === this) Screen.activity = null
        sceneAlive = false
        scene.stop()
        ui.removeCallbacks(holdTick)
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        ui.removeCallbacks(wallpaperTick)
        ui.removeCallbacks(spotlightTick)
        ui.removeCallbacks(nightTick)
        ui.removeCallbacks(playingTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onStop() {
        unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
        if (homeTest != 0) leftDuringTest = true
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        unregisterReceiver(adultRelock)
        unregisterReceiver(packageReceiver)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        contentResolver.unregisterContentObserver(inputsObserver)
        Remote.detach(this) // the page stays up across a recreate; it stops when remote access is turned off
        io.shutdownNow()
        posterIo.shutdownNow()
        spotlightIo.shutdownNow()
        netIo.shutdownNow()
        longIo.shutdownNow()
        super.onDestroy()
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Zephyr TV, stopping at the first that works: the default-home
    // role, then the Home button service, then adb help for TVs that grey out the service (Android 13+
    // "restricted settings" for sideloaded apps). Each way is checked by asking for a Home press: one that
    // reaches us arrives as a Home intent; one that doesn't sends us to the background.

    internal var setupDialog: Dialog? = null
    internal var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open
    internal var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    internal var leftDuringTest = false
    internal var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (handleLink(intent)) return // a link, a share or a shortcut: what it asks, not Home's start again
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        // Back out of an app: Zephyr TV covering Google TV, not the Home key. Everything stays as it was left.
        if (intent.getBooleanExtra(HomeOverride.COVER, false)) return
        if (editing) endEdit()
        if (movingChannelId != null) endChannelCardMove()
        channelOrderGrabId = null
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays, and goes back up to an app's first section")
    override fun onBackPressed() {
        when {
            // Back to the page before: focus on the row it was left from (see showPage).
            settingsScrim.visibility == View.VISIBLE -> {
                goingBack = true
                try { pageBack?.invoke() ?: closeLauncherSettings() } finally { goingBack = false }
            }
            allApps.visibility == View.VISIBLE -> closeAllApps()
            endSearch() -> {}
            // On a card in a section further down (an IPTV add-on's categories): the first section's first card.
            currentFocus?.parent?.parent.let { row -> row is ViewGroup && row !== watchRow && row in cardRows() } -> {
                watchScroll.scrollTo(0, 0)
                firstCard()?.requestFocus()
            }
        }
    }

    // ---------- theme ----------

    internal var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    internal val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    /** While the home screen shows, a folder wallpaper moves on to its next photo when its time is up. */
    internal val wallpaperTick = object : Runnable {
        override fun run() {
            val every = photoEvery() * 60_000L
            if (!currentWallpaper().startsWith("folder:")) return
            val wait = prefs.getLong(photoAtKey(), 0) + every - System.currentTimeMillis()
            if (wait <= 0) nextPhoto()
            ui.postDelayed(this, if (wait <= 0) every else wait)
        }
    }

    internal var fileDone: ((android.net.Uri?) -> Unit)? = null // the file picker or Save dialog open (see Handheld)
    internal var warpBack: (() -> Unit)? = null // the WARP page's Back, to show it again once WARP is up (see Privacy)

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    internal val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    internal val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    internal var panelFrom: View? = null // focus to go back to when the panel closes

    // The main page lists sections; each opens a page of its own. A row is addressed as section * SECTION + its
    // position on that page (the *_ROW constants), so whatever a row opens can come back to that row.

    internal var shownSection = -1 // section page on screen, -1 for the main list or any other page

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Zephyr TV's look. Back goes up one page; from the main list it closes the panel.

    internal var pageBack: (() -> Unit)? = null
    internal var goingBack = false // Back is drawing the page before: its row left from gets focus (see showPage)
    internal val pageFocus = HashMap<String, Int>() // each page's row last left from, by its title
    internal var pageExit: (() -> Unit)? = null // cleanup when the page goes away (releases its phone page, see Remote.hold)
    internal val phonePanel = PhonePanel() // what the phone's remote-access page shows of the panel (see Remote)

    // A page's state lives with the home screen that drew it, not in the process: a new home screen (a restart, a
    // theme) starts clean, and the old one's views go with it.
    // The details page (see DetailPage): the art behind it and what it shows, so a page drawn again with the same
    // art keeps it, unloaded; its logo by its key; and its two columns, for the arrows (see Navigation).
    internal var detailArt: FrameLayout? = null
    internal var detailKey: String? = null
    internal var detailHeld = false
    internal var detailLogo: Pair<String, Bitmap>? = null
    internal var detailLeftScroll: ScrollView? = null
    internal var detailDescView: View? = null
    internal var detailLastLeftFocusables: List<View> = emptyList()
    // The season's or episode's page on show, drawn again once its title's whole page is in (see openTitle).
    internal var titleRedraw: (() -> Unit)? = null
    internal var titleRedrawFor: String? = null
    // Moving channels (see ChannelOrder): the one picked up in the settings list, the card moved on the home
    // rows, and the card to focus once the channels' row is built again.
    internal var channelOrderGrabId: Long? = null
    internal var movingChannelId: Long? = null
    internal var pendingChannelFocusId: Long? = null
    internal var pageSides: ((Int) -> Unit)? = null // Left/Right (-1, +1) on a page that has them: a series' seasons
    internal var pageReturn: (() -> Unit)? = null // the page to show again, as it was, back from the player it opened

    internal var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    internal val pinGuard = PinGuard(PIN_TRIES) { SystemClock.elapsedRealtime() } // the layout lock and the 18+ lock
    private val adultRelock = AdultRelock()
    internal var adultShown = true
    internal var focusFirstApp = false // the apps row built again: focus on its first app (18+ just locked) // whether the adult add-ons showed last time the rows were built (see checkAdultLock)
    internal var pinBox: View? = null

    internal val offlineDot by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    internal val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) { showInputsButton(); updateInputsDot(); if (inputsMode() == "apps" || inputsInApps()) refreshApps() }
        override fun onInputRemoved(id: String) { showInputsButton(); updateInputsDot(); if (inputsMode() == "apps" || inputsInApps()) refreshApps() }
        override fun onInputStateChanged(id: String, state: Int) { updateInputsDot(); if (inputsMode() == "apps" || inputsInApps()) refreshApps() }
    }

    /** What's playing changed: the Play queue's rows again, if they're the ones on screen (see refreshWatchNext). */
    internal val playingRefresh = Runnable { if (watchApp == CW_PKG) { watchKey = null; refreshWatchNext() } }

    /**
     * Every few seconds while the Play queue's rows show: what's playing, compared by track and playing or paused,
     * rebuilt when it changed. Some apps' sessions don't tell listeners (Spotify on this Sony didn't).
     */
    internal val playingTick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (watchApp != CW_PKG || !hasWindowFocus()) return ui.postDelayed(this, 5000).let { }
            io.execute {
                val now = nowPlaying().joinToString("|") { "${it.title}/${it.sub}" }
                ui.post {
                    if (lastPlaying != null && now != lastPlaying) playingRefresh.run()
                    lastPlaying = now
                    ui.postDelayed(this, 5000)
                }
            }
        }
    }
    internal var lastPlaying: String? = null

    internal val inputsObserver = object : android.database.ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) {
            updateInputsDot()
            if (inputsMode() == "apps" || inputsInApps()) refreshApps()
        }
    }

    internal val inputsDot by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INPUTS_ON) } }

    /**
     * Hold detection lives here, not in View long-click. The timer starts when OK goes down, not on the remote's
     * key repeats: an infrared remote repeats at its own pace, and some barely repeat at all. The long-click
     * listener in style() is a fallback for a key that never arrives as a down. [holdFired] stops both firing.
     */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (spotlightIdle) {
            spotlightIdle = false
            restartSpotlightTick()
        }
        return homeDrag(e) || hanging(e) || super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = handleKey(e) || super.dispatchKeyEvent(e)

    internal var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    @Volatile internal var downloading = false

    @Volatile internal var checking = false
    @Volatile internal var updating = false

    internal val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    internal val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile internal var publishingPkgs = emptySet<String>()

    /**
     * Spotlight moves on to the next title by itself every few seconds while the home screen shows. It waits
     * while you're on the title or its buttons (it shouldn't change under you), while the panel or All apps is
     * open, and while something covers the home screen (a screensaver, a dialog), so it doesn't fetch backdrops unseen.
     */
    internal val spotlightTick = object : Runnable {
        override fun run() {
            if (spotlightContainer.visibility != View.VISIBLE) return // armed again when a TMDB backdrop loads
            // Nobody has pressed a key for a while: stop turning titles (and decoding backdrops) for an empty room.
            // The next key press starts it again.
            if (SystemClock.elapsedRealtime() - lastInput > SPOTLIGHT_IDLE_MS) {
                spotlightIdle = true
                return
            }
            val busy = isNight() || spotlightFolded || isDescendantOf(currentFocus, spotlightContainer) || settingsScrim.visibility == View.VISIBLE ||
                allApps.visibility == View.VISIBLE || editing || !hasWindowFocus()
            if (busy) restartSpotlightTick() else cycleSpotlight(1)
        }
    }

    internal companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        const val MIC_REQUEST = 4
        const val FILE_REQUEST = 5 // a file picked, or saved, on a phone or tablet (see Handheld)
        const val VPN_REQUEST = 6 // Android's permission for WARP's VPN (see Privacy)
        const val SPOTLIGHT_IDLE_MS = 15 * 60_000L // Spotlight stops turning after this long without a key press
        const val TMDB_FRESH_MS = 3 * 3600_000L // how long a trending list is kept before it's fetched again
        val SPOTLIGHT_PKGS = listOf("com.nuvio.tv", "com.nuvio.app", "com.stremio.one", "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom")
        val SPOTLIGHT_EVERY = listOf(0, 5, 8, 10, 15, 30) // seconds between Spotlight titles; 0 = off
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val BADGE = 0xFFFF453A.toInt()
        const val INPUTS_ON = 0xFF30D158.toInt() // the TV inputs button's dot: a device is on
        val SONY_INPUTS: Uri = Uri.parse("content://com.sony.dtv.tvinput.external.provider/sony_external_input")
        const val REPO = "mccoy88f/Zephyr_TV" // where Zephyr TV's releases are published
        const val HELP_URL = "https://github.com/$REPO#home-button-on-android-13-and-newer"
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val SUBMENU = "\u0000more" // a row that opens a page of its own: drawn as an arrow
        const val PIN_TRIES = 5 // wrong PINs before a wait (see PinGuard)
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = listOf(1, 5, 10, 15, 30, 60, 1440)
        const val CHOSEN = "✓"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        val SPRING = android.animation.TimeInterpolator { spring(it) }
        @Volatile var APPS: List<App>? = null
        /** Mono marks made by glyph(), by package; null = icon has no usable symbol. Main thread only. */
        val GLYPHS = HashMap<String, Bitmap?>()
        /** accent() colours by package; null = no clear colour. Main thread only. */
        val ACCENTS = HashMap<String, Int?>()

        /** HDMI tile icons by hdmiIcon key: brand marks (Simple Icons, CC0), the rest from Material Symbols (Apache 2.0). */
        val HDMI_ICONS = mapOf(
            "playstation" to R.drawable.logo_playstation,
            "xbox" to R.drawable.logo_xbox,
            "nintendoswitch" to R.drawable.logo_nintendoswitch,
            "appletv" to R.drawable.logo_appletv,
            "settop" to R.drawable.ic_input,
            "playback" to R.drawable.ic_input,
            "hdmi" to R.drawable.ic_input,
        )

        /**
         * Colour mode tints for the bundled marks: Simple Icons' brand hex, except where that's too dark to
         * read on dark cards (PlayStation, Prime Video) or grey (Google Play), where the brand's brighter blue/green is used.
         */
        val BRAND_COLOURS = mapOf(
            "playstation" to 0xFF0070D1.toInt(),
            "xbox" to 0xFF107C10.toInt(),
            "nintendoswitch" to 0xFFE60012.toInt(),
            "com.nuvio.app" to 0xFF6C5CE7.toInt(),
            "com.nuvio.tv" to 0xFF6C5CE7.toInt(), // Nuvio's GitHub build
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.stremio.one" to 0xFF8D3DAF.toInt(),
            "io.gh.reisxd.tizentube.cobalt" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tvmusic" to 0xFFFF0000.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "org.videolan.vlc" to 0xFFFF8800.toInt(),
            "com.android.vending" to 0xFF01875F.toInt(),
        )

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}
