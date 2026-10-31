package link.mccoy88f.zephyr

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.App
import link.mccoy88f.zephyr.MainActivity.Companion.DECELERATE
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.SPOTLIGHT_PKGS
import link.mccoy88f.zephyr.MainActivity.Prog
import java.io.File

// TMDB Spotlight on the home screen: the title and its arrows, the app buttons, cycling and backdrops.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.tmdbLang() = prefs.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()

internal fun MainActivity.isDescendantOf(child: View?, parent: View): Boolean {
    var v = child
    while (v != null) {
        if (v === parent) return true
        v = v.parent as? View
    }
    return false
}

internal fun MainActivity.setupSpotlight() {
    // The arrows get a small disc in the ground colour, so they read on any backdrop.
    for (arrow in arrayOf(spotlightPrev, spotlightNext)) {
        arrow.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((ground and 0xFFFFFF) or 0x80000000.toInt()) }
        arrow.setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    spotlightPrev.setOnClickListener { cycleSpotlight(-1) }
    spotlightNext.setOnClickListener { cycleSpotlight(1) }
    for (v in arrayOf(spotlightContainer, bottomSection)) v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centerSpotlight() }

    // The title has no card to light up like the tiles: a soft panel round its text says it has focus.
    spotlightInfoBox.background = TextHalo(spotlightInfoBox as ViewGroup, dp(14).toFloat(), (fg and 0xFFFFFF) or 0x2E000000).apply { alpha = 0 }

    window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
        updateSpotlightFocusState(newFocus)
        keepInView(newFocus)
    }
}

/**
 * The home screen doesn't scroll, but with Spotlight on top the rows under it can run past the bottom edge.
 * Focus down there slides the whole page up just enough to show the focused item with what's under it (a
 * card's title and details, a tile's name), and focus anywhere above slides it back.
 */
internal fun MainActivity.keepInView(v: View?) {
    val content = findViewById<View>(R.id.content)
    if (v == null || !isDescendantOf(v, content)) return
    val target = if (v.getTag(R.id.watchRow) is Prog) v.parent as View else v
    val under = if (v.parent === appRow) dp(28) else 0
    content.post {
        var shift = 0f
        if (isDescendantOf(v, bottomSection)) {
            val at = IntArray(2).also { target.getLocationInWindow(it) }
            // Where it will sit once the page and the rows stop moving (both may be mid-animation).
            val bottom = at[1] - content.translationY - bottomSection.translationY + target.height + under
            shift = (bottom - (findViewById<View>(R.id.root).height - dp(16))).coerceAtLeast(0f)
        }
        if (shift != -content.translationY) content.animate().translationY(-shift).setDuration(200).setInterpolator(DECELERATE).start()
    }
}

/**
 * The title block sits under the clock and weather, but with the rows below it can end up nearly touching the
 * apps. Moved up so the gap above its first line (from the weather) and the one under the title (to the app
 * tiles) are the same; never down, and never past the weather. Measured to the title, not the whole block, so
 * the overview and buttons that open under it don't move it.
 */
internal fun MainActivity.centerSpotlight() {
    val content = spotlightContainer.parent as ViewGroup
    val infoRow = (spotlightContainer as ViewGroup).getChildAt(0)
    val apps = findViewById<View>(R.id.appScroll)
    val rowTop = spotlightContainer.top + infoRow.top + spotlightInfoBox.top
    val above = rowTop - content.getChildAt(0).bottom
    val below = bottomSection.top + apps.top + apps.paddingTop - (rowTop + spotlightTitle.bottom)
    spotlightContainer.translationY = -((above - below) / 2).coerceIn(0, above.coerceAtLeast(0)).toFloat()
}

/** Opens the overview in full when it's cut off, or closes it again; false when there's nothing to open or close. */
internal fun MainActivity.toggleOverview(): Boolean {
    if (spotlightOverview.maxLines == Int.MAX_VALUE) {
        spotlightOverview.maxLines = OVERVIEW_LINES
        return true
    }
    val layout = spotlightOverview.layout ?: return false
    if (layout.lineCount == 0 || layout.getEllipsisCount(layout.lineCount - 1) == 0) return false
    spotlightOverview.maxLines = Int.MAX_VALUE
    return true
}

private const val OVERVIEW_LINES = 3

internal fun MainActivity.updateSpotlightFocusState(targetFocus: View?) {
    val inSpotlight = isDescendantOf(targetFocus, spotlightContainer)
    if (!inSpotlight) spotlightOverview.maxLines = OVERVIEW_LINES
    spotlightOverview.visibility = if (inSpotlight) View.VISIBLE else View.GONE
    spotlightActions.visibility = if (inSpotlight) View.VISIBLE else View.GONE
    for (arrow in arrayOf(spotlightPrev, spotlightNext)) arrow.animate().alpha(if (inSpotlight) 0.9f else 0f).setDuration(220).start()
    (spotlightInfoBox.background as? TextHalo)?.let { halo ->
        val to = if (targetFocus === spotlightInfoBox) 255 else 0
        if (halo.alpha != to) ValueAnimator.ofInt(halo.alpha, to).setDuration(180).apply { addUpdateListener { halo.alpha = it.animatedValue as Int } }.start()
    }
    // Translate bottomSection downwards to give spotlight breathing room
    val targetTransY = if (inSpotlight) dp(65).toFloat() else 0f
    val targetAlpha = if (inSpotlight) 0.65f else 1f
    bottomSection.animate().translationY(targetTransY).alpha(targetAlpha).setDuration(220).start()
}

internal fun MainActivity.resolveAppByPkg(pkg: String): App? = try {
    val pm = packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
    val info = pm.getApplicationInfo(pkg, 0)
    App(pkg, info.loadLabel(pm).toString(), intent, info.loadIcon(pm))
} catch (_: Exception) {
    null
}

internal fun MainActivity.spotlightItem(): TmdbItem? = tmdbTrending.let { list -> if (list.isEmpty()) null else list[tmdbIndex.mod(list.size)] }

internal fun MainActivity.renderSpotlight() {
    val item = spotlightItem()
    if (currentWallpaper() != "tmdb:trending" || item == null) {
        spotlightContainer.visibility = View.GONE
        return
    }
    spotlightContainer.visibility = View.VISIBLE
    val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
    val rating = if (item.voteAverage > 0) "★ %.1f · ".format(item.voteAverage) else ""
    val typeBadge = if (item.mediaType == "tv") getString(R.string.tmdb_badge_tv) else getString(R.string.tmdb_badge_movie)
    val genre = item.genres.firstOrNull()?.let { " · $it" } ?: ""
    // A finger: where it is among the titles, dots for a few (it swipes), a count for more.
    val n = tmdbTrending.size
    val dots = if (!handheld() || n < 2) "" else if (n <= 10) (0 until n).joinToString(" ") { if (it == tmdbIndex) "●" else "○" } + "   "
        else "${tmdbIndex + 1} / $n   "
    spotlightMeta.text = "$dots$rating$typeBadge$year$genre"
    spotlightTitle.text = item.title
    spotlightOverview.text = item.overview
    spotlightOverview.maxLines = OVERVIEW_LINES // a new title starts closed
    val more = if (tmdbTrending.size > 1) View.VISIBLE else View.INVISIBLE
    spotlightPrev.visibility = more
    spotlightNext.visibility = more
    // The buttons search for whichever title shows when pressed, so they're only rebuilt when the apps change
    // (rebuilding them on every title would also drop the focus off them).
    if (spotlightApps == null) renderSpotlightActions()
    showProviders(item)
}

internal fun MainActivity.providersOn() = prefs.getBoolean(K.TMDB_PROVIDERS, true)

/**
 * Shows the buttons of the services that have [item] (TMDB's watch providers for this country) and hides the
 * other services' ones; aggregators, YouTube and unknown apps always stay. Known answers apply at once, others
 * once TMDB answers (until then, and offline, every button shows).
 */
internal fun MainActivity.showProviders(item: TmdbItem) {
    val key = prefs.getString(K.TMDB_API_KEY, null)
    if (!providersOn() || key == null || item.id <= 0) return applyProviders(null) // a Cinemeta title TMDB doesn't know: all of them
    val region = TmdbClient.region(tmdbLang())
    val known = TmdbClient.cachedProviders(item, region)
    applyProviders(known)
    if (known == null) spotlightIo.execute {
        val found = TmdbClient.providers(key, item, region) ?: return@execute
        ui.post { if (spotlightItem() == item) applyProviders(found) }
    }
}

internal fun MainActivity.applyProviders(available: Set<String>?) {
    var first = true
    for (i in 0 until spotlightActions.childCount) {
        val b = spotlightActions.getChildAt(i)
        val show = TmdbClient.offers(b.tag as? String ?: continue, available)
        b.visibility = if (show) View.VISIBLE else View.GONE
        // Gaps only between visible buttons, so the first one lines up with the text above.
        (b.layoutParams as LinearLayout.LayoutParams).marginStart = if (show && !first) dp(8) else 0
        if (show) first = false
    }
    spotlightActions.requestLayout()
    if (currentFocus?.parent === spotlightActions && currentFocus?.visibility != View.VISIBLE) {
        (spotlightAction(-1, 1) ?: spotlightInfoBox).requestFocus()
    }
}

/** The next visible Spotlight button after index [from] going [step] (±1), or null at the end. */
internal fun MainActivity.spotlightAction(from: Int, step: Int): View? {
    var i = from + step
    while (i in 0 until spotlightActions.childCount) {
        spotlightActions.getChildAt(i).takeIf { it.visibility == View.VISIBLE }?.let { return it }
        i += step
    }
    return null
}

/** Up to five apps to open a title in: the known streaming apps first, then other installed apps, minus those turned off. */
internal fun MainActivity.titleApps(): List<App> {
    val offSet = prefs.getStringSet(K.TMDB_DEFAULT_APPS, emptySet()) ?: emptySet()
    val candidateApps = mutableListOf<App>()
    // The known services' apps join whenever installed: each shows only for titles TMDB lists on it.
    for (pkg in SPOTLIGHT_PKGS + TmdbClient.providerApps) {
        if (pkg in offSet) continue
        val app = apps.firstOrNull { it.pkg == pkg } ?: resolveAppByPkg(pkg)
        if (app != null && candidateApps.none { it.pkg == app.pkg }) {
            candidateApps.add(app)
        }
    }
    for (app in apps) {
        if (candidateApps.size >= 5) break
        if (app.pkg !in offSet && !app.pkg.startsWith(HDMI) && !app.pkg.startsWith(STREMIO) && app.pkg != CW_PKG && candidateApps.none { it.pkg == app.pkg }) {
            candidateApps.add(app)
        }
    }
    // Five at most, not counting services' apps beyond that: those hide for titles they don't have. The trailer first.
    return candidateApps.filterIndexed { i, a -> i < 5 || a.pkg in TmdbClient.providerApps }.sortedByDescending { TmdbClient.isYouTube(it.pkg) }
}

/**
 * Spotlight's buttons: Watch first, when an add-on has streams for films or series (the title's Cinemeta page, its
 * seasons and episodes, as from the Films and Series rows), then one per app in [titleApps].
 */
internal fun MainActivity.renderSpotlightActions() {
    spotlightActions.removeAllViews()
    val targetApps = emptyList<MainActivity.App>()
    spotlightApps = targetApps
    val streamers = shownAddonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.streamsVod }
    val watch = if (streamers.isEmpty() && nuvioRepoUrls(prefs).isEmpty()) emptyList() else listOf(MainActivity.App(WATCH_ACTION, getString(R.string.title_watch),
        android.content.Intent(), watchIcon(streamers.singleOrNull())))
    // Watch's icon is Zephyr TV's play mark (no add-on's logo): coloured as the text, so it shows on the focused button too.
    val markOnWatch = streamers.singleOrNull()?.let { addonIcon(it) } == null

    for (a in watch + targetApps) {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            val rest = if (glass) 0x992C2C34.toInt() else if (dark) 0xCC1C1C22.toInt() else 0xCCDCE0E8.toInt() // opaque enough for any backdrop
            val btnBg = GradientDrawable().apply {
                cornerRadius = dp(if (glass) 16 else 8).toFloat() // glass buttons are capsules
                setColor(rest)
                if (glass) setStroke(1, 0x1FFFFFFF) // a hairline: the glass shows by its tint, not an outline
            }
            background = btnBg
            isFocusable = true
            isClickable = true
            tag = a.pkg
            val mark = a.pkg == WATCH_ACTION && markOnWatch
            val ic = ImageView(this@renderSpotlightActions).apply {
                setImageDrawable(a.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (mark) setColorFilter(fg)
            }
            val txt = TextView(this@renderSpotlightActions).apply {
                text = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else a.label
                setTextColor(fg)
                textSize = 12f
                setPadding(dp(6), 0, 0, 0)
            }
            addView(ic, dp(18), dp(18))
            addView(txt)
            setOnFocusChangeListener { _, focused ->
                btnBg.setColor(if (focused) fg else rest)
                txt.setTextColor(if (focused) onFg else fg)
                if (mark) ic.setColorFilter(if (focused) onFg else fg)
                scaleX = if (focused) 1.08f else 1f
                scaleY = if (focused) 1.08f else 1f
            }
            setOnClickListener { if (a.pkg == WATCH_ACTION) spotlightItem()?.let { watchSpotlight(it) } else openInApp(a) }
        }
        spotlightActions.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = if (spotlightActions.childCount > 0) dp(8) else 0
        })
    }
}

/**
 * Opens the Spotlight title in [a]: straight on its page where the app takes an id (Nuvio by TMDB id, Stremio by
 * IMDb id, looked up first), YouTube on the trailer TMDB lists (or a search for one), otherwise through the
 * app's search, otherwise just the app.
 */
/** The tag of Spotlight's Watch button (see renderSpotlightActions): not an app, so it shows for every title. */
private const val WATCH_ACTION = "piume:watch"

/**
 * Spotlight's Watch: the title's Cinemeta page (see openTitle), by its IMDb id (Cinemeta's titles have it; a TMDB
 * list's is asked of TMDB first), with Spotlight's backdrop.
 */
internal fun MainActivity.watchSpotlight(item: TmdbItem) {
    val key = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
    netIo.execute {
        val imdb = item.imdbId ?: key?.let { TmdbClient.imdbIdOf(it, item) }
        ui.post {
            if (imdb == null) return@post Toast.makeText(this, getString(R.string.title_no_page), Toast.LENGTH_LONG).show()
            openTitle(TitleRef(if (item.mediaType == "tv") "series" else "movie", imdb, item.title, null,
                TmdbClient.cardUrl(item.backdropPath), source = cinemetaUrl(),
                rating = item.voteAverage.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }, about = item.overview.ifBlank { null }))
        }
    }
}

internal fun MainActivity.openInApp(a: App, title: TmdbItem? = spotlightItem()) {
    val item = title ?: return launch(a)
    val lang = tmdbLang()
    fun start(ref: String?) = try {
        startActivity(TmdbClient.openIntent(this, a.pkg, item, ref, lang))
    } catch (e: Exception) {
        launch(a)
    }
    val key = prefs.getString(K.TMDB_API_KEY, null)
    if (key == null || !TmdbClient.needsLookup(a.pkg)) return start(TmdbClient.knownRef(a.pkg, item)) // a Cinemeta title's IMDb id, for Stremio
    spotlightIo.execute {
        val ref = TmdbClient.lookup(key, a.pkg, item, lang)
        ui.post { start(ref) }
    }
}

internal fun MainActivity.cycleSpotlight(direction: Int) {
    if (tmdbTrending.isEmpty()) return
    ui.removeCallbacks(spotlightTick) // restarted once the new backdrop is up
    tmdbIndex = (tmdbIndex + direction).mod(tmdbTrending.size)
    renderSpotlight()
    loadWallpaper()
}

/** Downloads the next and previous titles' backdrops ahead, so ◀ ▶ and the timer show them at once. */
internal fun MainActivity.prefetchBackdrops() {
    val list = tmdbTrending
    if (list.size < 2) return
    val urls = listOf(1, -1).mapNotNull { TmdbClient.backdropUrl(list[(tmdbIndex + it).mod(list.size)].backdropPath) }
        .filterNot { Images.has(it, Images.Kind.BACKDROP) }
    if (urls.isNotEmpty()) spotlightIo.execute {
        for (url in urls) try {
            Images.file(url, Images.Kind.BACKDROP)
        } catch (e: Exception) {
            warn("backdrop prefetch failed ($e)") // shown later from the network instead
        }
    }
}

internal fun MainActivity.spotlightEvery() = prefs.getInt(K.TMDB_CYCLE, 5) // seconds; 0 = only by hand

internal fun MainActivity.restartSpotlightTick() {
    ui.removeCallbacks(spotlightTick)
    val every = spotlightEvery()
    if (every > 0 && tmdbTrending.size > 1) ui.postDelayed(spotlightTick, every * 1000L)
}

/** Notes what each trending title's backdrop shows, so the TMDB screensaver can caption it without the network. */
internal fun MainActivity.saveBackdropTitles(list: List<TmdbItem>) {
    val lines = list.mapNotNull { item ->
        val url = TmdbClient.backdropUrl(item.backdropPath) ?: return@mapNotNull null
        val about = listOfNotNull(item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres.firstOrNull()).joinToString(" · ")
        listOf(BackdropCache.nameFor(url), item.title, about).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }
    try {
        File(cacheDir, BACKDROP_TITLES).writeText(lines.joinToString("\n"))
    } catch (e: java.io.IOException) {
        warn("backdrop titles not saved ($e)")
    }
}

/** Downloads every trending title's backdrop ahead, for the TMDB screensaver. */
internal fun MainActivity.prefetchAllBackdrops() {
    val urls = tmdbTrending.mapNotNull { TmdbClient.backdropUrl(it.backdropPath) }.filterNot { Images.has(it, Images.Kind.BACKDROP) }
    if (urls.isNotEmpty()) spotlightIo.execute {
        for (url in urls) try {
            Images.file(url, Images.Kind.BACKDROP)
        } catch (e: Exception) {
            warn("backdrop prefetch failed ($e)")
        }
    }
}

/**
 * A band across the whole screen behind [box] (its text, [pad] above and below). Drawn past the box's own bounds,
 * which its parents don't clip, so it reaches both screen edges whatever the page margins.
 */
internal class TextHalo(private val box: ViewGroup, private val pad: Float, private val fill: Int) : Drawable() {
    private val paint = Paint()
    private var shown = 255

    override fun draw(canvas: Canvas) {
        if (shown == 0) return
        val at = IntArray(2).also { box.getLocationInWindow(it) }
        paint.color = fill
        paint.alpha = (fill ushr 24) * shown / 255
        canvas.drawRect(RectF(-at[0].toFloat(), -pad * 0.5f, (box.rootView.width - at[0]).toFloat(), box.height + pad * 0.35f), paint)
    }

    override fun setAlpha(alpha: Int) {
        shown = alpha
        invalidateSelf()
    }

    override fun getAlpha() = shown
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
