package link.mccoy88f.zephyr

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
import android.graphics.drawable.TransitionDrawable
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.FrameLayout
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

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. Glass is a dark theme. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString(K.THEME, "light")) {
    "dark", "glass" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}

/** The wallpaper to show: the one chosen, else Spotlight once there's a TMDB key, else the bundled one for the theme. */
internal fun chooseWallpaper(saved: String?, hasTmdbKey: Boolean, forDark: Boolean) = saved
    ?: if (hasTmdbKey) SS_TMDB else bundledWallpaper(forDark)

internal fun bundledWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/** Newest first across apps (ties keep their order), at most [perApp] per app: one app's ten shows can't bury the rest. */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    return items.sortedByDescending { it.first }.map { it.second }.filter { shown.merge(app(it), 1, Int::plus)!! <= perApp }
}

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

/** "5", "5-1", "5.1", "101" as numbers to sort by (5-1 right after 5); no number sorts last. */
internal fun channelKey(number: String?): Double {
    val parts = number?.split('-', '.', ' ')?.mapNotNull { it.toIntOrNull() } ?: return Double.MAX_VALUE
    val major = parts.firstOrNull() ?: return Double.MAX_VALUE
    return major + (parts.getOrNull(1) ?: 0) / 1000.0
}

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

/** Allowed to start an activity from the background because the Home button service is bound. */
internal const val CW_PKG = "piume:continue_watching"
internal const val SEARCH_PKG = "piume:search" // the search results tile, after Continue Watching while a search is open

class MainActivity : Activity() {

    // picture: the tile image for Zephyr TV's own apps (Films, Series, TV channels), centered in the card; the
    // icon stays the small mark on cards.
    internal class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable, val offline: Boolean = false,
                       val picture: Drawable? = null)
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
    internal var lastCard: View? = null
    internal var editing = false
    internal var grabbed: View? = null
    internal var holdFired = false
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
        Problems.init(File(filesDir, "problems.log"))
        Images.use(this) // images on disk (see Images): every part of Zephyr TV goes through it
        Js.useAssets(applicationContext) // the modules plugins and extensions require()
        Nuvio.process = ScriptProcess.plugins(this) // plugins run in a process of their own
        ExtensionCalls.use(this) // extensions answer as add-ons, from their own process
        setContentView(R.layout.activity_main)
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
        applyTheme()
        applyClock()
        setupSpotlight() // after applyTheme: its veil and arrow discs use the theme's colours
        touchSetup() // phones and tablets: the page and Spotlight with a finger
        warpAgain() // WARP on before: up again
        loadWallpaper()

        // Top bar: users, search, network, All apps, TV inputs, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.usersButton to { showUsers() },
            R.id.searchButton to { openSearch(if (searchQuery != null) lastSearchWord else "") },
            R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            R.id.allAppsButton to { openAllApps() },
            R.id.tvInputsButton to { openInputsMenu(findViewById(R.id.tvInputsButton)) },
            R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
            R.id.launcherSettings to { unlock { openLauncherSettings() } },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ring.setColor(if (focused) fg else 0)
                setColorFilter(if (focused) onFg else fg)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
        }
        showInputsButton() // gone where there are no inputs (a stick, a phone) or they're tiles in the apps row
        showUserIcon() // the user in use's letters, in place of the icon
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

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
        shortcuts() // a phone or tablet: Search and the Play queue on the icon
        ui.post { handleLink(intent) } // started by a link, a share or a shortcut (see Links)
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
            else Toast.makeText(this, getString(R.string.toast_mic_needed), Toast.LENGTH_LONG).show()
            afterMicAccess = null
            return
        }
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else Toast.makeText(this, getString(R.string.toast_photo_access_needed), Toast.LENGTH_LONG).show()
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
        upload?.stop()
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

    internal fun wantDark() = prefersDark(prefs)

    internal fun applyTheme() {
        dark = wantDark()
        glass = prefs.getString(K.THEME, "light") == "glass"
        fg = if (glass) Color.WHITE else if (dark) 0xFFF5F5F7.toInt() else 0xFF111214.toInt()
        // Small controls (top-bar icons, settings and menu rows, Spotlight buttons) show focus by inverting:
        // an fg fill with onFg content. A white-on-near-white or grey-on-black highlight barely reads from the sofa.
        onFg = if (dark) 0xFF111214.toInt() else Color.WHITE
        field = if (dark) 0xFF2A2A32.toInt() else 0xFFE5E7EB.toInt()
        // Dark cards are matte black glass on a true-black ground: lit faintly from the top and fading to
        // near-black, no outline (outlines read as a grey grid). Focus brightens the glass, never to grey.
        // Light cards are frosted grey glass, so they still read on white wallpapers; focus turns them white.
        // Glass cards are smoked glass: the wallpaper shows through, but dark enough under white marks and text to
        // read on any photo. Without a blur (Android 9 has none for views), the tint does the blur's job. Focus
        // lightens the glass; the rim and the sheen (see style) give the gloss.
        cardRest = when {
            glass -> intArrayOf(0x8C2C2C34.toInt(), 0xA6121216.toInt())
            dark -> intArrayOf(0xFF16161B.toInt(), 0xFF060607.toInt())
            else -> intArrayOf(0xD9E2E5E9.toInt(), 0xD9D8DCE1.toInt())
        }
        cardLit = when {
            glass -> intArrayOf(0xCC5A5A66.toInt(), 0xCC24242C.toInt())
            dark -> intArrayOf(0xFF26262E.toInt(), 0xFF0C0C0F.toInt())
            else -> intArrayOf(Color.WHITE, Color.WHITE)
        }
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) Color.BLACK else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        allApps.setBackgroundColor(ground)
        // A halo in the ground colour: dark text gets a light one, light text a dark one, so either stays readable
        // where it crosses a photo or a TMDB backdrop.
        // Glass has white text straight on the photo, with no fade to a plain colour behind it: a stronger halo.
        val textShadow = if (glass) 0xB3000000.toInt() else if (dark) 0x80000000.toInt() else 0xB3FFFFFF.toInt()
        val textRadius = if (glass) 8f else if (dark) 6f else 5f
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather, R.id.spotlightTitle, R.id.spotlightMeta, R.id.spotlightOverview)) {
            findViewById<TextView>(id)?.apply {
                setTextColor(fg)
                setShadowLayer(textRadius, 0f, if (dark) 2f else 0f, textShadow)
            }
        }
        wifi.setColorFilter(fg)
        spotlightPrev.setColorFilter(fg)
        spotlightNext.setColorFilter(fg)
        renderSpotlight()
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/link.mccoy88f.zephyr/files/wallpapers/
    // (adb push, no permission needed) and get the fade painted once when loaded. A folder on the TV (MediaStore,
    // so USB drives too) shows its photos in shuffled turns, one decoded at a time.
    // Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    internal fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    internal fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            listOf(wallpaperName("tmdb:trending") to "tmdb:trending") + // Cinemeta's titles without a TMDB key
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    internal fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    internal fun defaultWallpaper(forDark: Boolean) = bundledWallpaper(forDark)

    internal fun currentWallpaper(forDark: Boolean = dark) = chooseWallpaper(
        prefs.getString(if (forDark) K.WP_DARK else K.WP_LIGHT, null), !prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank(), forDark)

    /** Fade your own photos into the background at the bottom. Bundled wallpapers have it baked in either way. */
    internal fun wallpaperFade() = prefs.getBoolean(K.WP_FADE, true)

    internal fun wallpaperName(choice: String) = when {
        choice == "tmdb:trending" -> getString(R.string.wallpaper_tmdb_trending)
        choice.startsWith("folder:") -> getString(R.string.wallpaper_prefix_folder, choice.substringAfter(':').substringAfter(':'))
        choice == "plain:Pure black" -> getString(R.string.wallpaper_plain_pure_black)
        choice == "plain:Soft white" -> getString(R.string.wallpaper_plain_soft_white)
        choice == "res:Rockies (day)" -> getString(R.string.wallpaper_rockies_day)
        choice == "res:Rockies (dusk)" -> getString(R.string.wallpaper_rockies_dusk)
        else -> choice.substringAfter(':').substringBeforeLast('.')
    }

    internal fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            view.setImageDrawable(null)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } ?: decodeWallpaper(fallback)
            ui.post {
                // The next title's countdown starts once this backdrop is up, so slow loads never pile up.
                if (choice == "tmdb:trending") {
                    restartSpotlightTick()
                    prefetchBackdrops()
                }
                val old = view.drawable
                if (old == null || bmp == null) {
                    view.setImageBitmap(bmp)
                    renderSpotlight()
                    return@post
                }
                // Soft change from one photo to the next; then drop the old one so only one stays in memory.
                val fade = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, bmp))).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(fade)
                fade.startTransition(800)
                ui.postDelayed({ if (view.drawable === fade) view.setImageBitmap(bmp) }, 900)
                renderSpotlight()
            }
        }
    }

    internal fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        if (choice == "tmdb:trending") {
            val key = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
            val type = prefs.getString(K.TMDB_TYPE, "all") ?: "all"
            // Trending is per day: refetch now and then, or a home screen that never closes keeps last week's list.
            // Cinemeta's popular titles need no key (in the TV's language with one); TMDB's lists need it.
            if (tmdbTrending.isEmpty() || System.currentTimeMillis() - tmdbFetchedAt > TMDB_FRESH_MS) {
                val listId = prefs.getString(K.TMDB_LIST, null)
                val source = spotlightSource()
                val fresh = when {
                    key == null || source == "cinemeta" -> spotlightTitles(type)
                    source == "list" && listId != null -> TmdbClient.fetchList(key, listId, tmdbLang(), type)
                    else -> TmdbClient.fetchCategory(key, source, tmdbLang(), type, TmdbClient.region(tmdbLang()))
                }
                // Films not out at home yet (in cinemas, or to come) left out, when they're hidden (see Releases).
                val shown = withoutUnreleased(fresh, key)
                if (shown.isNotEmpty()) {
                    val fresh = shown
                    // A different order each time, so the same top titles don't always open the round.
                    tmdbTrending = fresh.shuffled()
                    tmdbIndex = 0
                    tmdbFetchedAt = System.currentTimeMillis()
                    saveBackdropTitles(fresh)
                }
            }
            val item = spotlightItem() ?: return null
            val url = TmdbClient.backdropUrl(item.backdropPath) ?: return null
            val file = Images.file(url, Images.Kind.BACKDROP)
            val bmp = fitWallpaper { file.inputStream() } ?: return null
            val h = bmp.height.toFloat()
            // Only the fade under the apps row, from halfway down: the backdrop shows clear everywhere else. The
            // clock, the icons and the Spotlight text read by their halo.
            if (wallpaperFade()) glassOrFade(bmp, h * 0.5f, h * 0.8f)
            return bmp
        }
        // fitWallpaper's bitmap is mutable: the fades are painted onto it.
        val bmp = if (choice.startsWith("folder:")) {
            val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
            fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
        } else {
            val file = File(wallpaperDir(), choice.removePrefix("file:"))
            fitWallpaper { file.inputStream() }
        } ?: return null
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        if (wallpaperFade()) glassOrFade(bmp, h * 0.28f, h * 0.5f)
        // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
        // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.3f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /**
     * Fades the photo into the ground colour from [from] to [to]. Glass keeps the photo instead: a soft shade from
     * the middle down, so the rows' white text reads while the photo still shows behind the glass cards.
     */
    private fun glassOrFade(bmp: Bitmap, from: Float, to: Float) {
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
            shader = if (glass) LinearGradient(0f, h * 0.45f, 0f, h, intArrayOf(0, 0x73000000, 0xA6000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            else LinearGradient(0f, from, 0f, to, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        })
    }

    /** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    internal fun pickWallpaper(forDark: Boolean) {
        val row = if (forDark) DARK_WP_ROW else LIGHT_WP_ROW
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val current = currentWallpaper(forDark)
        val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }, page(getString(R.string.choose_from_tv)) { pickFolder(forDark) }) +
            choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
            if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo)) { removeWallpaper(own, forDark) })
        showPage(if (forDark) getString(R.string.setting_dark_wallpaper) else getString(R.string.setting_light_wallpaper), rows, 2 + choices.indexOfFirst { it.second == current }) {
            renderSettings(row)
        }
    }

    internal fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) K.WP_DARK else K.WP_LIGHT, choice)
            .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
        if (forDark == dark) loadWallpaper()
    }

    // ---------- folder wallpapers ----------

    internal var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    internal val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    internal fun photoKey(forDark: Boolean = dark) = if (forDark) K.WP_PHOTO_DARK else K.WP_PHOTO_LIGHT
    internal fun photoAtKey(forDark: Boolean = dark) = if (forDark) K.WP_PHOTO_AT_DARK else K.WP_PHOTO_AT_LIGHT
    internal fun photoEvery() = prefs.getInt(K.WP_EVERY, 30)

    internal fun photoEveryName(minutes: Int) = when (minutes) {
        1 -> getString(R.string.interval_every_minute)
        5 -> getString(R.string.interval_every_5_min)
        10 -> getString(R.string.interval_every_10_min)
        15 -> getString(R.string.interval_every_15_min)
        30 -> getString(R.string.interval_every_30_min)
        60 -> getString(R.string.interval_every_hour)
        1440 -> getString(R.string.interval_every_day)
        else -> "$minutes min"
    }

    internal fun pickPhotoEvery() {
        val rows = PHOTO_EVERY.map { minutes ->
            page(photoEveryName(minutes), if (minutes == photoEvery()) CHOSEN else "") {
                prefs.edit().putInt(K.WP_EVERY, minutes).apply()
                ui.removeCallbacks(wallpaperTick)
                ui.post(wallpaperTick)
                renderSettings(PHOTO_EVERY_ROW)
            }
        }
        showPage(getString(R.string.setting_folder_photo_changes), rows, PHOTO_EVERY.indexOf(photoEvery())) { renderSettings(PHOTO_EVERY_ROW) }
    }

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

    internal fun nextPhoto() {
        prefs.edit().remove(photoKey()).putLong(photoAtKey(), System.currentTimeMillis()).apply()
        loadWallpaper()
    }

    /** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
    internal fun folderPhoto(choice: String): Long? {
        val ids = folderPhotos(contentResolver, choice)
        val all = ids.toHashSet()
        prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
        deck.removeAll { it !in all } // deleted, or another folder's
        if (deck.isEmpty()) deck.addAll(ids.shuffled())
        return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
    }

    /** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
    internal fun photoFolders(): List<Triple<String, String, Int>> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = (c.getString(1) ?: getString(R.string.folder_untitled)) to (found[id]?.second ?: 0) + 1
            }
        }
        return found.map { (id, v) -> Triple(id, v.first, v.second) }
    }

    internal fun pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
        setWallpaper(forDark, choice)
        renderSettings(if (forDark) DARK_WP_ROW else LIGHT_WP_ROW)
    }

    /** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
    internal fun pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
        if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
            afterPhotoAccess = { pickFolder(back, onPick) }
            return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
        }
        val folders = photoFolders()
        if (folders.isEmpty()) {
            return Toast.makeText(this, getString(R.string.toast_no_photos_found), Toast.LENGTH_LONG).show()
        }
        val rows = folders.map { (id, name, n) ->
            val countStr = if (n == 1) getString(R.string.photos_count_singular, n) else getString(R.string.photos_count_plural, n)
            page(name, countStr) { onPick("folder:$id:$name") }
        }
        showPage(getString(R.string.choose_folder_title), rows, back = back)
    }

    internal fun removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
        showPage(getString(R.string.remove_photo), own.map { (name, choice) ->
            page(name) {
                showPage(getString(R.string.delete_photo_confirm, name), listOf(
                    page(getString(R.string.dialog_cancel)) { removeWallpaper(own, forDark) },
                    page(getString(R.string.dialog_delete)) {
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString(K.WP_LIGHT, null) == choice) remove(K.WP_LIGHT)
                            if (prefs.getString(K.WP_DARK, null) == choice) remove(K.WP_DARK)
                        }.apply()
                        loadWallpaper()
                        pickWallpaper(forDark)
                    },
                )) { removeWallpaper(own, forDark) }
            }
        }) { pickWallpaper(forDark) }
    }

    internal var upload: PhoneLink? = null
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
    internal var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)
    internal var pageSides: ((Int) -> Unit)? = null // Left/Right (-1, +1) on a page that has them: a series' seasons
    internal var pageReturn: (() -> Unit)? = null // the page to show again, as it was, back from the player it opened

    internal var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    internal var pinFails = 0
    private val adultRelock = AdultRelock()
    internal var adultShown = true
    internal var focusFirstApp = false // the apps row built again: focus on its first app (18+ just locked) // whether the adult add-ons showed last time the rows were built (see checkAdultLock)
    internal var pinWaitUntil = 0L // ponytail: in memory, so restarting Zephyr TV resets the wait; fine for kids at a remote
    internal var pinBox: View? = null

    internal fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
        // A red dot while there's no internet: no network, or one Android couldn't reach the internet through.
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        wifi.post {
            wifi.overlay.remove(offlineDot)
            if (online || wifi.width == 0) return@post
            val size = dp(9)
            offlineDot.setBounds(wifi.width - size - dp(3), dp(3), wifi.width - dp(3), dp(3) + size)
            wifi.overlay.add(offlineDot)
        }
    }

    internal val offlineDot by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /**
     * The TV's voice search (Google Assistant on Google TV), by the ways Android TVs offer it, most specific first.
     * Started directly rather than looked up first: Android 11+ hides other apps' activities from lookups.
     */
    internal fun assistant() {
        for (intent in listOf(
            Intent("android.intent.action.VOICE_ASSIST"),
            Intent(Intent.ACTION_ASSIST),
            Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
            Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
        )) {
            try {
                return startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
        Toast.makeText(this, getString(R.string.toast_no_voice_search), Toast.LENGTH_SHORT).show()
    }

    internal fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    internal fun refreshApps() {
        appsDirty = false
        io.execute {
            val list = try {
                loadApps()
            } catch (e: Exception) {
                return@execute
            }
            ui.post {
                apps = list
                APPS = list
                renderApps()
                spotlightApps = null
                renderSpotlight()
                updateInputsDot()
                if (focusFirstApp && settingsScrim.visibility != View.VISIBLE) appRow.getChildAt(0)?.requestFocus()
                focusFirstApp = false
            }
        }
    }

    internal fun loadApps(): List<App> {
        val pm = packageManager
        val installed = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .map { a ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                )
            }
        val hdmi = when {
            inputsMode() == "apps" -> hdmiInputs()
            inputsInApps() -> hdmiInputs().filter { !it.offline }
            else -> emptyList()
        }
        return installed + hdmi + addonApps()
    }

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
     * The external inputs (HDMI, AV) with a device connected, by input id. Sony TVs keep the real state in their
     * external inputs app's own table, readable by any app; Android's own input state there says "connected" for
     * every HDMI input. Elsewhere, Android's state (connected, not standby or disconnected).
     */
    internal fun connectedInputs(): Set<String> {
        val sony = runCatching {
            contentResolver.query(SONY_INPUTS,
                arrayOf("input_id", "connect_state"), null, null, null)?.use { c ->
                val out = HashSet<String>()
                while (c.moveToNext()) if (c.getInt(1) == 1) c.getString(0)?.let { out += it }
                out
            }
        }.getOrNull()
        if (sony != null) return sony

        val tm = getSystemService(TvInputManager::class.java) ?: return emptySet()
        return runCatching {
            val cec = tm.tvInputList.filter { it.parentId != null }.mapNotNull { it.parentId }.toSet()
            val hdmi = tm.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput }
            val connected = hdmi.filter { tm.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED }.map { it.id }.toSet()
            if (hdmi.isNotEmpty() && connected.size == hdmi.size && cec.isNotEmpty()) cec else cec + connected
        }.getOrDefault(emptySet())
    }

    /** A green dot on the TV inputs button while at least one external input has an active device (see hdmiInputs). */
    internal fun updateInputsDot() {
        val icon = findViewById<ImageView>(R.id.tvInputsButton) ?: return
        val on = hdmiInputs().any { !it.offline }
        icon.overlay.remove(inputsDot)
        if (!on) return
        if (icon.width == 0) {
            icon.post { updateInputsDot() }
            return
        }
        val size = dp(9)
        inputsDot.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
        icon.overlay.add(inputsDot)
    }

    internal fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString(K.SORT, "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString(K.ORDER, "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    internal fun rowAlign() = prefs.getString(K.ROW_ALIGN, "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

    /**
     * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
     * centred, the scroller would push its first tiles out of reach.
     */
    internal fun alignRow() {
        val content = appRow.parent as View
        (content.layoutParams as FrameLayout.LayoutParams).gravity =
            if (appTileCount() > ROW_TILES) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
        content.requestLayout()
    }

    internal fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        // And the first time, wherever Android put it (Spotlight's title): the home screen starts on the Play queue.
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow ||
            (appRow.childCount == 0 && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE)
        val gap = dp(GRID_GAP)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * (ROW_TILES - 1)) / ROW_TILES
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        // Fixed first tile: "Continue Watching" hub
        val cwDrawable = getDrawable(R.drawable.ic_continue_watching) ?: getDrawable(R.drawable.ic_apps)!!
        // Tinted with the theme's foreground like the other marks: a white disc vanishes on the light theme's white focused card.
        val cwTile = tile(null, cwDrawable, mono = true, CW_PKG) {
            watchTitle.text = getString(R.string.continue_watching)
            tmdbLastFocusApp = null
            queryAppChannels(null)
        }.apply {
            contentDescription = getString(R.string.play_queue)
        }

        val searchTile = searchQuery?.let { q ->
            tile(null, getDrawable(R.drawable.ic_search)!!, mono = true, SEARCH_PKG) { openSearch(lastSearchWord) }.apply {
                contentDescription = getString(R.string.search_results_tile, q)
            }
        }
        val pinned = pinnedInputs()
        val appTiles = sortedApps().filter { inRow(it.pkg, it.offline, hidden, pinned) }.map { appTile(it, labeled = false) }
        val tiles = listOfNotNull(cwTile, searchTile) + appTiles
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, dp(76)).apply { if (i > 0) marginStart = gap })
            // A finger: the first tap selects the app, its rows showing under it, as focus does on a TV; the next opens
            // it. An app known to have no rows opens with the first (see rowsKnown).
            t.isFocusableInTouchMode = !rowless(t.tag as? String)
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own?.onFocusChange(v, focused)
                if (focused && v.isInTouchMode && handheld()) v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (focused) {
                    lastApp = v
                    val pkg = v.tag as? String
                    if (pkg == CW_PKG) {
                        watchTitle.text = getString(R.string.continue_watching)
                        tmdbLastFocusApp = null
                        showLoading(pkg)
                        ui.removeCallbacks(appChannelDebounce)
                        ui.postDelayed(appChannelDebounce, 200)
                    } else if (pkg == SEARCH_PKG) {
                        tmdbLastFocusApp = pkg
                        ui.removeCallbacks(appChannelDebounce)
                        showSearchResults()
                    } else if (pkg != null && !pkg.startsWith(HDMI)) {
                        tmdbLastFocusApp = pkg
                        showLoading(pkg)
                        ui.removeCallbacks(appChannelDebounce)
                        ui.postDelayed(appChannelDebounce, 300)
                    }
                }
                showRowLabel(v, focused)
            }
        }
        showNames()
        alignRow()
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean(K.TIP_MOVE, false)) {
            prefs.edit().putBoolean(K.TIP_MOVE, true).apply()
            Toast.makeText(this, getString(R.string.tip_rearrange_row), Toast.LENGTH_LONG).show()
        }
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    internal fun rowNames() = prefs.getString(K.NAMES, "focus")!!

    /** Each app's own shipped icon instead of the mono mark, in the row and All apps. */
    internal fun colourIcons() = prefs.getBoolean(K.COLOUR_ICONS, false)

    /** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
    internal fun appTile(a: App, labeled: Boolean = true): View {
        val logo = LOGOS[a.pkg]
        val custom = customIcon(a.pkg)
        val name = a.label.takeIf { labeled }
        // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
        val colour = colourIcons()
        val t = when {
            custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
            a.picture != null -> {
                val pic = if (colour) a.picture.constantState?.newDrawable(resources) ?: a.picture
                          else (a.picture.constantState?.newDrawable(resources) ?: a.picture).mutate().apply { colorFilter = GREYSCALE }
                tile(null, pic, false, a.pkg, padding = dp(4)) {
                    if (allApps.visibility == View.VISIBLE) {
                        closeAllApps()
                        appRow.findViewWithTag<View>(a.pkg)?.requestFocus()
                    } else if (!playLastChannel(a.pkg) && watchApp == a.pkg) firstCard()?.requestFocus()
                }
            }
            // An add-on's tile (see addonApps): OK goes down to its channels; from All apps, to its tile in the row.
            a.pkg.startsWith(STREMIO) -> {
                val icon = if (colour || a.icon !is android.graphics.drawable.BitmapDrawable) a.icon
                           else (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }
                tile(name, icon, a.icon !is android.graphics.drawable.BitmapDrawable, a.pkg) {
                    if (allApps.visibility == View.VISIBLE) {
                        closeAllApps()
                        appRow.findViewWithTag<View>(a.pkg)?.requestFocus()
                    } else if (!playLastChannel(a.pkg) && watchApp == a.pkg) firstCard()?.requestFocus()
                }
            }
            a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg,
                tint = BRAND_COLOURS[hdmiIcon(a.label, true)]?.takeIf { colour }) { launch(a) }
            logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
                wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14),
                tint = BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
            else -> cachedGlyph(a)?.let { tile(name, it, true, a.pkg, tint = if (colour) cachedAccent(a) else null) { launch(a) } }
                ?: if (colour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
                // A copy: greying the shared icon itself would keep it grey after switching to colour.
                else tile(name, (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
        }
        t.contentDescription = a.label
        return t
    }

    /** "App names: Always": a name under every tile, same width and gap as the tiles, so they line up. */
    internal fun showNames() {
        val row = findViewById<LinearLayout>(R.id.nameRow)
        val always = rowNames() == "always"
        row.visibility = if (always) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowLabel).visibility = if (always) View.GONE else View.VISIBLE
        row.removeAllViews()
        if (!always) return
        for (i in 0 until appRow.childCount) {
            val t = appRow.getChildAt(i)
            row.addView(label(t.contentDescription?.toString().orEmpty(), 13f).apply {
                gravity = Gravity.CENTER
                alpha = if (dark) 0.85f else 1f
            }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
        }
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    internal fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<TextView>(R.id.rowLabel)
        val inWatchRow = currentFocus?.getTag(R.id.watchRow) is Prog
        if (focused && !editing && rowNames() == "focus") {
            label.text = v.contentDescription
            // Posted: on first render focus lands before layout, when every width is still 0.
            v.post { label.translationX = v.left + v.width / 2f - label.width / 2f }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
            // Reset app tiles alpha
            for (i in 0 until appRow.childCount) appRow.getChildAt(i).alpha = 1f
        } else if (inWatchRow && !editing) {
            // Keep the selected app name visible, and dim the non-selected app tiles
            val activeTile = lastApp?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
            if (activeTile != null && rowNames() == "focus") {
                label.text = activeTile.contentDescription
                activeTile.post { label.translationX = activeTile.left + activeTile.width / 2f - label.width / 2f }
                label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
            }
            for (i in 0 until appRow.childCount) {
                val child = appRow.getChildAt(i)
                child.animate().alpha(if (child === activeTile) 1f else 0.45f).setDuration(160).start()
            }
        } else {
            ui.post {
                if (currentFocus?.parent !== appRow && currentFocus?.getTag(R.id.watchRow) !is Prog || editing) {
                    label.animate().alpha(0f).setDuration(120).start()
                    for (i in 0 until appRow.childCount) appRow.getChildAt(i).animate().alpha(1f).setDuration(160).start()
                }
            }
        }
    }

    /** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */

    internal fun tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, tint: Int? = null, padding: Int? = null, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mono) setColorFilter(tint?.let { readable(it) } ?: fg)
        }
        val text = label?.let {
            label(it, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
            }
        }
        // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
        val restLogo = if (dark && !glass) 0.8f else 1f
        val restText = if (glass) 0.85f else if (dark) 0.65f else 1f
        img.alpha = restLogo
        text?.alpha = restText
        style(col, dp(8), card = true) { focused ->
            if (!dark) return@style
            img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
            text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
        }
        if (text == null) {
            // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
            // (crop the empty top and bottom) or they shrink to a sliver.
            if (wordmarkInset != null) {
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
            } else if (padding != null) {
                img.setPadding(padding, padding, padding, padding)
            } else {
                img.setPadding(dp(16), dp(20), dp(16), dp(20))
            }
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
            col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        col.tag = tag
        col.setOnClickListener { onClick() }
        return col
    }

    /**
     * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
     * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
     * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
     * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
     */
    internal fun glyph(icon: Drawable): Bitmap? {
        if (Build.VERSION.SDK_INT < 26) return null // Android 7 has no adaptive icons: the greyscale look
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
        val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
        fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
        fg.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
        val alpha = FloatArray(px.size) { i ->
            val c = px[i]
            Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val peak = alpha.max().takeIf { it > 0f } ?: return null
        // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
        for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
        val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
        val l = on.minOf { it % size }; val r = on.maxOf { it % size }
        val t = on.minOf { it / size }; val b = on.maxOf { it / size }
        val side = maxOf(r - l, b - t) + 1
        val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
        val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    /**
     * A brand colour nudged towards the theme's foreground until it stands out from the card at 3:1 (the bar for
     * icons): Spotify green or VLC orange on the light theme's pale grey cards are only about 2:1 as they come.
     */
    internal fun readable(c: Int): Int {
        val card = if (dark) 0xFF0E0E11.toInt() else 0xFFE0E3E7.toInt() // the rest card, middle of its gradient
        fun contrast(a: Int): Float {
            val (hi, lo) = Color.luminance(a).let { la -> Color.luminance(card).let { lc -> maxOf(la, lc) to minOf(la, lc) } }
            return (hi + 0.05f) / (lo + 0.05f)
        }
        var out = c
        var mix = 0f
        while (contrast(out) < 3f && mix < 1f) {
            mix += 0.1f
            out = Color.rgb(
                (Color.red(c) * (1 - mix) + Color.red(fg) * mix).toInt(),
                (Color.green(c) * (1 - mix) + Color.green(fg) * mix).toInt(),
                (Color.blue(c) * (1 - mix) + Color.blue(fg) * mix).toInt())
        }
        return out
    }

    /** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
    internal fun cachedGlyph(a: App): Drawable? {
        val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    /**
     * The colour an icon is mostly, for tinting its mark: the biggest hue among its vivid pixels (backplate
     * included, which is where most apps keep their colour). Null if it's all greys, or too close to the
     * card colour to read on it; the mark then stays the theme's mono colour.
     */
    internal fun accent(icon: Drawable): Int? {
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.constantState?.newDrawable(resources)?.apply { setBounds(0, 0, size, size) }?.draw(Canvas(bmp)) ?: return null
        val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
        bmp.recycle()
        val hsv = FloatArray(3)
        val buckets = px.filter { Color.alpha(it) > 200 }
            .filter { Color.colorToHSV(it, hsv); hsv[1] > 0.35f && hsv[2] > 0.3f }
            .groupBy { Color.colorToHSV(it, hsv); (hsv[0] / 30).toInt() } // 12 hue buckets
        val top = buckets.values.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 } ?: return null
        return Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
    }

    /** accent() per app, kept like GLYPHS; the contrast check is per theme, so it runs on every use. */
    internal fun cachedAccent(a: App): Int? {
        val c = if (a.pkg in ACCENTS) ACCENTS[a.pkg] else accent(a.icon).also { ACCENTS[a.pkg] = it }
        val lum = c?.let { Color.luminance(it) } ?: return null
        return c.takeIf { if (dark) lum > 0.08f else lum < 0.6f }
    }

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    internal fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener in style() is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (spotlightIdle) {
            spotlightIdle = false
            restartSpotlightTick()
        }
        return homeDrag(e) || hanging(e) || super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (spotlightIdle) {
            spotlightIdle = false
            restartSpotlightTick()
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: don't let Left/Right leak focus to the home screen behind it.
        // Horizontal scroll views (like the app icons bar in details), the PIN boxes and text fields use them.
        val inHorizontal = currentFocus?.let { v ->
            var p = v.parent
            while (p != null && p !== settingsList) {
                if (p is android.widget.HorizontalScrollView) return@let true
                p = p.parent
            }
            false
        } ?: false
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText) {
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val targetId = if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) currentFocus?.nextFocusDownId else currentFocus?.nextFocusUpId
                    if (targetId != null && targetId != View.NO_ID && targetId != currentFocus?.id) {
                        val target = findViewById<View>(targetId)
                        if (target != null && target.requestFocus()) return true
                    }
                    if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus != null && currentFocus === detailDescView) {
                        var p = currentFocus?.parent
                        while (p != null && p !is android.widget.ScrollView) p = p.parent
                        (p as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
                        return true
                    }
                }
            } else if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (inHorizontal) {
                        val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                        val next = currentFocus?.focusSearch(dir)
                        if (next != null && next !== currentFocus) {
                            next.requestFocus()
                            return true
                        }
                    }
                    val targetId = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) currentFocus?.nextFocusLeftId else currentFocus?.nextFocusRightId
                    if (targetId != null && targetId != View.NO_ID && targetId != currentFocus?.id) {
                        val target = findViewById<View>(targetId)
                        if (target != null && target.requestFocus()) return true
                    }
                    if (!inHorizontal) pageSides?.invoke(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
                return true
            }
        }
        if (editing && editKey(e, confirm)) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
     * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
     * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
     * let Android handle the key.
     */
    internal fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val cards = if (watchScroll.visibility != View.VISIBLE) null // Down from the apps: the first section
            else alive(lastCard)?.takeIf { it.parent?.parent === watchRow } ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        val spotVis = spotlightContainer.visibility == View.VISIBLE
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> if (spotVis) spotlightInfoBox else apps ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == R.id.usersButton) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v === spotlightInfoBox -> when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> spotlightAction(-1, 1) ?: apps ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cycleSpotlight(1)
                    v
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    cycleSpotlight(-1)
                    v
                }
                // OK opens a cut-off overview in full (OK again closes it); otherwise it goes on to the buttons.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (toggleOverview()) v else spotlightAction(-1, 1) ?: v
                else -> null
            }
            v.parent === spotlightActions -> {
                val idx = spotlightActions.indexOfChild(v)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: cards ?: v
                    KeyEvent.KEYCODE_DPAD_UP -> spotlightInfoBox
                    KeyEvent.KEYCODE_DPAD_LEFT -> spotlightAction(idx, -1) ?: v
                    KeyEvent.KEYCODE_DPAD_RIGHT -> spotlightAction(idx, 1) ?: v
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        v.performClick()
                        v
                    }
                    else -> null
                }
            }
            v.parent === appRow -> when (key) {
                // Up climbs Spotlight from the bottom: its buttons (shown only while it has focus), the title, the top bar.
                KeyEvent.KEYCODE_DPAD_UP -> if (spotVis) spotlightAction(-1, 1)?.also { spotlightActions.visibility = View.VISIBLE } ?: spotlightInfoBox else top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (appRow.indexOfChild(v) == 0) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (appRow.indexOfChild(v) == appRow.childCount - 1) v else null
                else -> null
            }
            v.getTag(R.id.watchRow) is Prog -> { // a card: Up and Down go between sections, to the nearest card
                val row = v.parent.parent as ViewGroup
                val rows = cardRows()
                val r = rows.indexOf(row)
                val i = row.indexOfChild(v.parent as View)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (r <= 0) apps ?: top else nearestCard(rows[r - 1], v) ?: v
                    KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(r + 1)?.let { nearestCard(it, v) } ?: v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == row.childCount - 1) v else null
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    internal fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    internal fun fireHold(v: View) {
        if (v.isInTouchMode) v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    internal fun onHold(v: View) {
        val tag = v.tag as? String
        @Suppress("UNCHECKED_CAST")
        (v.getTag(R.id.settingsList) as? () -> Unit)?.let { return it() } // a page's row with a hold of its own (an episode's: watched)
        when {
            v.id == R.id.searchButton -> assistant() // the TV's own voice search, for what TMDB doesn't know
            tag == SEARCH_PKG -> endSearch()
            allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
                val edit = prefs.edit().putStringSet(K.ROW_HIDDEN, rowHiddenSet() - tag)
                if (tag.startsWith(HDMI)) edit.putStringSet(K.INPUTS_PINNED, pinnedInputs() + tag)
                edit.apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow -> appMenu(v)
            (v.getTag(R.id.watchRow) as? Prog)?.let { it.channelPage != null || (it.vod != null && watchApp == SEARCH_PKG) } == true -> {} // a search result: OK shows all it has
            v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
        }
    }

    /** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
    internal fun appMenu(v: View) {
        val pkg = v.tag as String
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.menu_open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
        ))
        holdMenu(v, listOfNotNull(
            getString(R.string.menu_move) to { startEdit(grab = v, once = true) },
            getString(R.string.menu_remove_from_row) to {
                val i = appRow.indexOfChild(v)
                val offline = apps.any { it.pkg == pkg && it.offline }
                prefs.edit().putStringSet(K.ROW_HIDDEN, if (offline) rowHiddenSet() else rowHiddenSet() + pkg)
                    .putStringSet(K.INPUTS_PINNED, pinnedInputs() - pkg).apply()
                renderApps()
                appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
                Toast.makeText(this, getString(R.string.removed_from_row_toast), Toast.LENGTH_SHORT).show()
            },
            sectionCache?.takeIf { it.first == pkg && it.second.isNotEmpty() }?.let { (_, sections) ->
                getString(R.string.menu_sections) to { pickSections(pkg, sections) }
            },
            // Zephyr TV's own apps only (Films, Series, TV channels, an add-on's): their rows are named from the add-ons'
            // catalogs, an app's own are named by the app.
            sectionCache?.takeIf { it.first == pkg && it.second.isNotEmpty() && pkg.startsWith(STREMIO) }?.let { (_, sections) ->
                getString(R.string.menu_rename_sections) to { renameSections(pkg, sections) }
            },
            if (pkg.startsWith(HDMI) || pkg.startsWith(STREMIO)) null else getString(R.string.menu_app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
        ))
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    internal fun watchMenu(v: View, p: Prog) {
        if (p.playing) return holdMenu(v, listOfNotNull(
            getString(R.string.menu_close_playing) to { closePlaying(p) },
            getString(R.string.menu_open) to { v.performClick(); Unit },
        ))
        holdMenu(v, listOfNotNull(
            p.channelId?.let { id -> getString(R.string.menu_details) to { showChannelDetails(id) } },
            p.addon?.let { c -> getString(R.string.menu_details) to { showAddonChannelDetails(c); Unit } },
            if (locked()) null else getString(R.string.menu_remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.takeIf { !it.startsWith(STREMIO) }?.let { pkg ->
                getString(R.string.menu_open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    /** Background for popup menus (inputs, app hold menu) matching the current theme, with smoked glass in Glass theme. */
    internal fun menuBackground(): Drawable = when {
        glass -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9202028.toInt(), 0xE6121218.toInt())).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x26FFFFFF)
        }
        dark -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF18181D.toInt(), 0xFF121216.toInt())).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x1AFFFFFF)
        }
        else -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2F3F7.toInt())).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x1A000000)
        }
    }

    /** A small Zephyr TV-styled menu just above [anchor]; Back closes it. */
    internal fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = menuBackground()
            elevation = dp(8).toFloat()
        }
        val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            elevation = dp(8).toFloat()
        }
        items.forEachIndexed { i, (name, action) ->
            list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
                if (i > 0) topMargin = dp(2)
            })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], maxOf(dp(8), at[1] - list.measuredHeight - dp(8)))
        list.getChildAt(0).requestFocus()
    }

    /** Top-bar TV Inputs popup menu listing all HDMI and passthrough inputs. */
    internal fun openInputsMenu(anchor: View) {
        val inputs = hdmiInputs()
        if (inputs.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_inputs), Toast.LENGTH_SHORT).show()
            return
        }
        val on = connectedInputs()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = menuBackground()
            elevation = dp(8).toFloat()
        }
        val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            elevation = dp(8).toFloat()
        }
        inputs.distinctBy { it.pkg }.forEachIndexed { i, src ->
            val active = !src.offline
            val row = settingsRow(src.label, "") {
                menu.dismiss()
                startActivity(src.launch)
            } as LinearLayout
            val iconView = ImageView(this).apply {
                setImageDrawable(src.icon)
                setColorFilter(fg)
            }
            row.addView(iconView, 0, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            if (active) row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INPUTS_ON) } },
                LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(8) })
            val prevFocus = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, focused ->
                prevFocus?.onFocusChange(v, focused)
                iconView.setColorFilter(if (focused) onFg else fg)
            }
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
                if (i > 0) topMargin = dp(2)
            })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        // Display directly underneath the top bar icon
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0] - dp(100), at[1] + anchor.height + dp(8))
        list.getChildAt(0)?.requestFocus()
    }

    internal fun appTileCount() = appRow.childCount

    internal var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    internal fun startEdit(grab: View?, once: Boolean = false) {
        editing = true
        moveOnce = once
        appsTitle.text = if (once) getString(R.string.moving_app_instructions) else getString(R.string.editing_row_instructions)
        appsTitle.visibility = View.VISIBLE
        (grab ?: appRow.getChildAt(0)).requestFocus()
        markGrabbed(grab)
    }

    internal fun markGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    internal fun endEdit() {
        markGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
        currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    internal fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
                if (moveOnce) endEdit() else markGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    showNames()
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
    internal fun rowHiddenSet() = prefs.getStringSet(K.ROW_HIDDEN, emptySet())!!

    internal fun pickRowApps() {
        val list = sortedApps().filter { !it.offline || it.pkg in pinnedInputs() }
        toggles(getString(R.string.setting_apps_in_row), list.map { it.label to it.pkg }, K.ROW_HIDDEN, getString(R.string.state_shown), getString(R.string.state_hidden)) {
            renderApps()
            renderSettings(ROW_APPS_ROW)
        }
    }

    /**
     * A page of on/off rows saved as the set of keys that are off; [done] runs on Back. With two rows or more, the
     * first turns them all on (Select all), or all off once they all are (Deselect all).
     */
    internal fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0, done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        val keys = items.map { it.second }
        val all = if (items.size < 2) emptyList() else listOf(
            if (keys.any { it in offSet }) page(getString(R.string.toggles_all_on)) {
                prefs.edit().putStringSet(pref, offSet - keys.toSet()).apply()
                toggles(title, items, pref, on, off, 0, done)
            } else page(getString(R.string.toggles_all_off)) {
                prefs.edit().putStringSet(pref, offSet + keys).apply()
                toggles(title, items, pref, on, off, 0, done)
            }
        )
        showPage(title, all + items.mapIndexed { i, (name, key) ->
            page(name, if (key in offSet) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i + all.size, done)
            }
        }, focus, back = done)
    }

    internal fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString(K.ORDER, pkgs.joinToString(",")).putString(K.SORT, "manual").apply()
    }

    internal fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString(K.SORT, "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
    }

    // ---------- all apps ----------

    internal fun openAllApps() {
        allAppsGrid.removeAllViews()
        val gap = dp(10)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
        for (a in sortedApps()) {
            val t = appTile(a) // holding OK here pins it to the row (see onHold)
            allAppsGrid.addView(t, GridLayout.LayoutParams().apply {
                this.width = width
                height = dp(76)
                setMargins(0, 0, gap, gap)
            })
        }
        allApps.visibility = View.VISIBLE
        homeFocusable(false)
        allAppsGrid.getChildAt(0)?.requestFocus()
    }

    internal fun closeAllApps() {
        allApps.visibility = View.GONE
        homeFocusable(true)
        renderApps()
        findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    internal fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    internal fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    internal fun galleryOn() = currentScreensaver() == gallery()

    internal fun screensaverName(component: String = currentScreensaver()): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.state_off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.screensaver_system_default)
        }
    }

    /** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
    internal fun photoScreensaver() = prefs.getString(K.SS_FOLDER, null)

    internal fun pickScreensaver(focus: Int = 0) {
        val paintings = galleryOn() && photoScreensaver() == null
        val tmdb = galleryOn() && photoScreensaver() == SS_TMDB
        val photos = galleryOn() && photoScreensaver() != null && !tmdb
        val other = if (galleryOn()) prefs.getString(K.PREV_SCREENSAVER, "").orEmpty() else currentScreensaver()
        showPage(getString(R.string.setting_screensaver), listOf(
            page(getString(R.string.screensaver_gallery_paintings), if (paintings) CHOSEN else if (downloading) getString(R.string.updates_checking) else PAINTINGS_MB) {
                useGallery(null)
                pickScreensaver(0)
            },
            page(getString(R.string.screensaver_your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
                pickFolder({ pickScreensaver(1) }) { choice ->
                    useGallery(choice)
                    pickScreensaver(1)
                }
            },
            page(screensaverName(other), if (galleryOn()) "" else CHOSEN) {
                if (galleryOn()) stopGallery()
                pickScreensaver(2)
            },
        ) + listOfNotNull(
            // Spotlight's backdrops (Cinemeta's, or TMDB's lists with a key).
            page(getString(R.string.screensaver_tmdb), if (tmdb) CHOSEN else "") {
                useGallery(SS_TMDB)
                pickScreensaver(3)
            },
        ), focus) { renderSettings(SCREENSAVER_ROW) }
    }

    /** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
    internal fun useGallery(folder: String?) {
        prefs.edit().putString(K.SS_FOLDER, folder).apply()
        if (folder == null && !paintingsReady(this)) return downloadPaintings { useGallery(null) }
        if (folder == SS_TMDB) prefetchAllBackdrops() // the screensaver shows what's on disk; it doesn't download
        if (folder != null) paintingsDir(this).deleteRecursively() // not needed for photos: give the space back
        if (galleryOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission Zephyr TV can't switch it itself; Android's own list has "Gallery".
            Toast.makeText(this, getString(R.string.toast_screensaver_choose_gallery), Toast.LENGTH_LONG).show()
            return openScreensaverSettings()
        }
        prefs.edit().putString(K.PREV_SCREENSAVER, currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to whatever screensaver was set before Gallery. */
    internal fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString(K.PREV_SCREENSAVER, "") ?: "")
        paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    internal fun openScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile internal var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    internal fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, getString(R.string.toast_screensaver_downloading, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                warn("paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else Toast.makeText(this, getString(R.string.toast_screensaver_download_failed), Toast.LENGTH_LONG).show()
                refreshSettings(SCREENSAVER_ROW)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    internal fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_screensaver_cannot_start), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK streams straight into an install session, so there's no temporary file to clean up.

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
            val busy = isNight() || isDescendantOf(currentFocus, spotlightContainer) || settingsScrim.visibility == View.VISIBLE ||
                allApps.visibility == View.VISIBLE || editing || !hasWindowFocus()
            if (busy) restartSpotlightTick() else cycleSpotlight(1)
        }
    }

    internal fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            Http.open(uri)
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
     * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
     * Only transform/alpha/colour change, so it stays on the GPU and costs no re-layout.
     */
    internal fun style(v: View, corner: Int, card: Boolean, lift: Float = 1.08f, onFocus: ((Boolean) -> Unit)? = null) {
        val radius = if (glass && card) corner * 2 else corner // glass panes are rounder
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius.toFloat()
            alpha = 0
        }
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (card) cardRest else intArrayOf(0, 0)).apply {
            cornerRadius = radius.toFloat()
        }
        // Glass: a gloss on the top half of the card and a thin bright rim, brighter with focus, like light caught
        // on the edge of a glass pane. Over the content, so a poster gets the rim too.
        val rim = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setStroke(1, 0x1FFFFFFF) // a hairline: a bright outline marks the panes off more than the glass itself
        }
        val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius.toFloat()
        }
        v.background = if (glass && card) LayerDrawable(arrayOf(bg, gloss)) else bg
        if (glass && card) v.foreground = rim else if (!dark) v.foreground = sheen
        v.clipToOutline = true
        // Android 9+ only (8.x would crash on these); black is the default shadow colour before that anyway.
        if (Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = Color.BLACK
            v.outlineAmbientShadowColor = Color.BLACK
        }
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheen.alpha = if (focused) 255 else 0
            rim.setStroke(if (focused) dp(1) else 1, if (focused) 0x99FFFFFF.toInt() else 0x1FFFFFFF)
            if (card) bg.colors = if (focused) cardLit else cardRest
            v.animate()
                .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    internal fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        // Settings sections, and each row's address in them (see renderSettings).
        const val SECTION = 100
        const val LOOK_SECTION = 0
        const val APPS_SECTION = 1
        const val WATCH_SECTION = 2
        const val SPOTLIGHT_SECTION = 3
        const val SCREENSAVER_SECTION = 4
        const val SYSTEM_SECTION = 5
        const val TV_SECTION = 6 // listed after Continue Watching (see renderSettingsHome)
        const val ADDONS_SECTION = 7 // listed after TV
        const val THEME_ROW = LOOK_SECTION * SECTION
        const val LIGHT_WP_ROW = THEME_ROW + 1
        const val DARK_WP_ROW = THEME_ROW + 2
        const val FADE_ROW = THEME_ROW + 3
        const val PHOTO_EVERY_ROW = THEME_ROW + 4
        const val WEATHER_ROW = THEME_ROW + 5
        const val WEATHER_UNITS_ROW = THEME_ROW + 6
        const val CITY_ROW = THEME_ROW + 7
        const val CLOCK_ROW = THEME_ROW + 8
        const val NIGHT_ROW = THEME_ROW + 9
        const val NIGHT_FROM_ROW = THEME_ROW + 10
        const val NIGHT_TO_ROW = THEME_ROW + 11
        const val STATUS_BAR_ROW = THEME_ROW + 12 // phones and tablets only
        const val SORT_ROW = APPS_SECTION * SECTION + 1
        const val ROW_APPS_ROW = APPS_SECTION * SECTION + 2
        const val ALIGN_ROW = APPS_SECTION * SECTION + 3
        const val NAMES_ROW = APPS_SECTION * SECTION + 4
        const val ICONS_ROW = APPS_SECTION * SECTION + 5
        const val LOCK_ROW = APPS_SECTION * SECTION + 6
        const val CW_APPS_ROW = WATCH_SECTION * SECTION
        const val HIDDEN_ROW = WATCH_SECTION * SECTION + 1
        const val INPUTS_MODE_ROW = TV_SECTION * SECTION
        const val INPUTS_IN_APPS_ROW = TV_SECTION * SECTION + 1
        const val CHANNEL_LOGOS_ROW = TV_SECTION * SECTION + 2
        const val EPG_ROW = TV_SECTION * SECTION + 4
        const val ADDON_ADD_ROW = ADDONS_SECTION * SECTION // the Stremio add-ons' section: Add, then one row per add-on
        const val ADDON_ROWS = ADDON_ADD_ROW + 1
        const val CINEMETA_ROW = WATCH_SECTION * SECTION + 3 // the content catalog, last of Content
        const val TMDB_SOURCE_ROW = SPOTLIGHT_SECTION * SECTION
        const val TMDB_LIST_ROW = TMDB_SOURCE_ROW + 1
        const val TMDB_TYPE_ROW = TMDB_SOURCE_ROW + 2
        const val TMDB_CYCLE_ROW = TMDB_SOURCE_ROW + 3
        const val SCREENSAVER_ROW = SCREENSAVER_SECTION * SECTION
        const val HOME_BUTTON_ROW = SYSTEM_SECTION * SECTION + 1 // after "Set up Home button"
        const val DEFAULT_HOME_ROW = HOME_BUTTON_ROW + 1
        const val UPDATE_ROW = HOME_BUTTON_ROW + 2
        const val BACKUP_ROW = HOME_BUTTON_ROW + 3
        const val PROBLEMS_ROW = HOME_BUTTON_ROW + 4
        const val GUIDE_ROW = HOME_BUTTON_ROW + 5
        const val ABOUT_ROW = HOME_BUTTON_ROW + 6
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
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
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
            "settop" to R.drawable.ic_settop,
            "playback" to R.drawable.ic_playback,
            "hdmi" to R.drawable.ic_hdmi,
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
