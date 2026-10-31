package link.mccoy88f.zephyr

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// A film's or series' page, as a whole page (see DetailPage: its seasons, an episode's page, the trailer and services), and
// playing one in Zephyr TV's player. Functions of MainActivity, like the other topics; see Titles for the rows.

/**
 * OK on a film's or series' card: its page, from the add-on it came from if that one has pages for it, else the
 * first that does (Cinemeta, for IMDb ids). A card from Continue Playing goes on playing instead.
 */
internal fun MainActivity.openTitle(ref: TitleRef) {
    if (ref.video != null) return resumeTitle(ref)
    // The page at once, with the card's art, what it's about as the card knows it, and Loading…; then the add-on's page
    // as it answers (in the TV's language as far as it's known already); then all of it in the TV's language (a
    // series' episodes too, asked season by season), focus where it is. Each only while the page is still open.
    val top = titleTop("", null, ref.about)
    titlePage(ref.name, Triple(ref.background, ref.logo, ref.poster), ref.fallback, ref.name, top,
        listOf(page(getString(R.string.player_loading)) { }), 0, emptyMap()) { closeLauncherSettings() }
    netIo.execute {
        val raw = addonMeta(ref)
        val known = raw?.let { localizeKnown(it) }
        ui.post {
            if (!pageShowing(ref.name)) return@post // closed meanwhile
            when {
                known != null -> showVodTitle(known, ref)
                ref.type != "series" -> { closeLauncherSettings(); playTitle(ref, null, null) }
                else -> showPage(ref.name, listOf(page(getString(R.string.title_no_page)) { closeLauncherSettings() }), back = { closeLauncherSettings() })
            }
        }
        if (raw == null || known == null) return@execute
        val whole = localizeMeta(raw)
        if (whole === raw) return@execute // nothing TMDB adds
        wholeMeta[wholeKey(whole.title.id)] = whole
        ui.post {
            if (pageShowing(known.title.name)) showVodTitle(whole, ref, focusRow = focusedRow())
            else if (redrawFor == whole.title.id) redraw?.invoke() // a season's or an episode's page: its names and what each is about
        }
    }
}

/**
 * [ref]'s page from the add-on it came from, else the first with pages for it; in the TV's language when TMDB has
 * it (see localizeMeta). Network: off the UI thread.
 */
private fun MainActivity.titleMeta(ref: TitleRef): Stremio.TitleMeta? = addonMeta(ref)?.let { localizeMeta(it) }

/** Titles' pages in the TV's language as last had whole (see openTitle), by id: a page drawn again keeps them. */
private val wholeMeta = java.util.concurrent.ConcurrentHashMap<String, Stremio.TitleMeta>()

/** The season's or episode's page on show, drawn again once its title's whole page is in (see openTitle). */
private var redraw: (() -> Unit)? = null
private var redrawFor: String? = null

/** Its key: in this language, and whether TMDB completes titles (either changed, it's had again). */
private fun MainActivity.wholeKey(id: String) = "${tmdbLang()}|${tmdbEnrich()}|$id"

/** [ref]'s page as the add-on has it, before TMDB is asked (see titleMeta). Network: off the UI thread. */
private fun MainActivity.addonMeta(ref: TitleRef): Stremio.TitleMeta? {
    // The catalog sources first, the main one first (see catalogSources); then the add-on it came from; then the rest.
    val sources = catalogSources()
    val addons = vodAddons().filter { it.provides("meta", ref.type, ref.id) }
        .sortedBy { a -> sources.indexOf(a.url).takeIf { it >= 0 } ?: if (a.url == ref.source) sources.size else sources.size + 1 }
    for (a in addons) runCatching { Stremio.titleMeta(a.base, ref.type, ref.id) }.getOrNull()?.let { return it }
    return null
}

/**
 * A film's or series' page in the side panel: its art across the top, rating, kind, year and
 * genre, what it's about; then Watch (for movies), and Watch on each add-on that has it with [found] streams (as an
 * episode's page), or the list of seasons (for TV series); last, the trailer and the apps that have it.
 */
internal fun MainActivity.showVodTitle(given: Stremio.TitleMeta, ref: TitleRef, available: Set<String>? = null,
                                       focusRow: Int? = null, found: Map<String, Int>? = null) {
    // Drawn again by what was started with an earlier version of it (the streams counted): the whole one, once in.
    val meta = wholeMeta[wholeKey(given.title.id)] ?: given
    val t = meta.title
    val kind = getString(if (t.type == "series") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    markWhole(meta)
    // Watched (by the user in use): a film when, a series its last episode watched and when.
    var mine = VodWatched.all(vodDir())
    if (t.type == "series" && t.id in mine && meta.episodes.isNotEmpty() && meta.episodes.none { it.id in mine }) {
        VodWatched.set(vodDir(), meta.episodes.map { it.id to t.id }, true, name = t.name)
        mine = VodWatched.all(vodDir())
    }
    val seenLine = if (t.type == "series") mine.values.filter { it.metaId == t.id && ':' in it.id }.maxByOrNull { it.at }?.let { m ->
        val e = meta.episodes.firstOrNull { it.id == m.id }
        getString(R.string.title_last_watched, listOfNotNull(e?.let { episodeLine(it) }, watchedOn(m.at)).joinToString(" · "))
    } else mine[t.id]?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }

    val (servicesBar, services) = serviceBar(t, available)
    val genresText = meta.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() }
    val tmdbId = t.tmdbId ?: Local.get(t.id)?.tmdbId
    val top = titleTop(listOfNotNull((meta.rating ?: ref.rating)?.let { "★ $it" }, kind, t.year, genresText, meta.runtime)
        .joinToString(" · "), null, meta.description, seenLine, director = meta.director, cast = meta.cast, tmdbId = tmdbId, isTv = t.type == "series")

    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    val holds = HashMap<Int, () -> Unit>()
    val marks = VodWatched.all(vodDir())

    val episodic = sources.isNotEmpty() && t.type == "series" && meta.episodes.isNotEmpty()
    if (sources.isEmpty()) {
        rows += page(getString(R.string.title_no_streams)) { }
    } else if (!episodic) {
        val at = VodProgress.position(vodDir(), t.id)
        val seen = t.id in marks
        // Back from the player: the page again, focus on the row that started it (Watch, or Watch on one of them).
        fun play(only: String?) {
            val row = focusedRow()
            pageReturn = { showVodTitle(meta, ref, services.known, focusRow = row, found = found) }
            playTitle(ref, meta, null, only)
        }
        // Watch, from all of them; how many streams they have once they've said (see warmTitle), none: no Watch.
        val having = found?.filterValues { it > 0 }?.keys?.let { keys -> sources.filter { it.first in keys } }
        val one = soleAddon(having ?: sources)
        if (having?.isEmpty() == true) rows += page(getString(R.string.title_no_streams)) { }
        else {
            icons[rows.size] = watchIcon(one)
            rows += page(watchLabel(one, at, seen), listOfNotNull(if (seen) "✓" else null, streamsLine(found)).joinToString(" · ")) {
                val row = focusedRow()
                if (manualStreams()) pickStream(ref, meta, null) { showVodTitle(meta, ref, services.known, focusRow = row, found = found) } else play(one?.base)
            }
        }
        val markRow = rows.size
        rows += page(getString(if (seen) R.string.title_mark_unwatched else R.string.title_mark_watched)) {
            VodWatched.set(vodDir(), listOf(t.id to t.id), !seen, name = t.name)
            traktHistory(listOf(t.id to t.id), !seen)
            if (!seen) VodProgress.remove(vodDir(), t.id)
            showVodTitle(meta, ref, services.known, focusRow = markRow, found = found)
        }
    } else {
        // TV Series: show the list of seasons on the main title page
        val seasons = meta.episodes.map { it.season }.distinct()
        fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
        for (s in seasons) {
            val inSeason = meta.episodes.filter { it.season == s }
            val watchedCount = inSeason.count { it.id in marks }
            val allWatched = inSeason.isNotEmpty() && watchedCount == inSeason.size
            val value = if (allWatched) "✓" else if (watchedCount > 0) "$watchedCount/${inSeason.size} ✓" else resources.getQuantityString(R.plurals.title_episodes, inSeason.size, inSeason.size)
            rows += page(seasonName(s), value) {
                showVodSeason(meta, ref, s, services.known)
            }
        }
        val allSeriesWatched = meta.episodes.all { it.id in marks }
        rows += page(getString(if (allSeriesWatched) R.string.title_unmark_season else R.string.title_mark_season)) {
            markWatched(meta, meta.episodes, !allSeriesWatched)
            showVodTitle(meta, ref, services.known, focusRow = seasons.size)
        }
    }

    // A phone or tablet: the page shared, as a link that opens it in Zephyr TV (see Links).
    if (handheld()) rows += page(getString(R.string.share)) { shareTitle(t.type, t.id, t.name) }
    val focus = focusRow ?: if (top.descView != null) -1 else 0
    val art = if (ref.background != null) Triple(ref.background, ref.logo, ref.poster ?: t.poster) else Triple(t.background, t.logo, ref.poster ?: t.poster)
    titlePage(t.name, art, ref.fallback, t.name, top, rows, focus, icons, holds, servicesBar = servicesBar) { closeLauncherSettings() }
    services.later { showVodTitle(meta, ref, it, focusRow, found) }
    if (!episodic && found == null) warmTitle(t.type, t.id, sources, t.name) { counts -> showVodTitle(meta, ref, services.known, focusedRow(), counts) }
}

/**
 * A series' season page: with Left/Right (sx / dx) you move between seasons, and below it the list of episodes.
 */
internal fun MainActivity.showVodSeason(given: Stremio.TitleMeta, ref: TitleRef, season: Int, available: Set<String>? = null,
                                        episode: String? = null, focusRow: Int? = null) {
    val meta = wholeMeta[wholeKey(given.title.id)] ?: given // the episodes in the TV's language once they're in
    val t = meta.title
    val seasons = meta.episodes.map { it.season }.distinct()
    val shown = season.takeIf { it in seasons } ?: seasons.first()
    fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
    val at = seasons.indexOf(shown)
    val sides = { step: Int -> showVodSeason(meta, ref, seasons[Math.floorMod(at + step, seasons.size)], available) }

    val marks = VodWatched.all(vodDir())
    val inSeason = meta.episodes.filter { it.season == shown }
    val watchedCount = inSeason.count { it.id in marks }
    val seenLine = if (watchedCount == inSeason.size) "✓ " + getString(R.string.title_watched) else if (watchedCount > 0) "$watchedCount / ${inSeason.size} " + getString(R.string.title_watched) else null

    val (servicesBar, services) = serviceBar(t, available)
    val tmdbId = t.tmdbId ?: Local.get(t.id)?.tmdbId
    val top = titleTop(listOfNotNull(t.name, seasonName(shown), resources.getQuantityString(R.plurals.title_episodes, inSeason.size, inSeason.size))
        .joinToString(" · "), null, meta.description, seenLine, director = meta.director, cast = meta.cast, tmdbId = tmdbId, isTv = true)

    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    val holds = HashMap<Int, () -> Unit>()
    var focus = 0

    // Row 0: Season switcher header (navigates with sx / dx)
    rows += page(seasonName(shown), "‹ ${at + 1} / ${seasons.size} ›") { sides(1) }

    val begun = VodProgress.all(vodDir()).associateBy { it.id }
    val ahead = inSeason.firstOrNull { begun[it.id] != null }?.id
        ?: inSeason.takeIf { list -> list.any { it.id in marks } }?.firstOrNull { it.id !in marks }?.id

    inSeason.forEach { e ->
        val name = getString(R.string.title_episode_row, e.episode, e.name.ifBlank { getString(R.string.title_episode_n, e.episode) })
        val value = begun[e.id]?.let { if (it.upNext) getString(R.string.title_up_next) else clock(it.position) }
            ?: if (e.id in marks) "✓" else ""
        if (episode == e.id || (episode == null && focusRow == null && e.id == ahead)) focus = rows.size
        holds[rows.size] = { // hold OK: watched, or not
            markWatched(meta, listOf(e), e.id !in marks)
            showVodSeason(meta, ref, shown, services.known, e.id)
        }
        rows += page(name, value) { showVodEpisode(meta, ref, e, services.known) }
    }
    val all = inSeason.all { it.id in marks }
    val markAt = rows.size
    rows += page(getString(if (all) R.string.title_unmark_season else R.string.title_mark_season)) {
        markWatched(meta, inSeason, !all)
        showVodSeason(meta, ref, shown, services.known, focusRow = markAt)
    }

    focusRow?.let { focus = it }
    val art = if (ref.background != null) Triple(ref.background, ref.logo, ref.poster ?: t.poster) else Triple(t.background, t.logo, ref.poster ?: t.poster)
    val pageTitle = "${t.name} · ${seasonName(shown)}"
    redrawFor = t.id
    redraw = { if (pageShowing(pageTitle)) showVodSeason(meta, ref, shown, available, episode, focusedRow() ?: focusRow) }
    titlePage(pageTitle, art, ref.fallback, t.name, top, rows, focus, icons, holds, servicesBar = servicesBar) {
        showVodTitle(meta, ref, services.known)
    }
    pageSides = sides
    services.later { showVodSeason(meta, ref, shown, it, episode, focusRow) }
}

/**
 * An episode's page, from its row on its season's page: its still (else the series' art) across the top, its season,
 * number and name, when it came out, what it's about; then Watch (or Resume).
 */
internal fun MainActivity.showVodEpisode(given: Stremio.TitleMeta, ref: TitleRef, picked: Stremio.Episode, available: Set<String>? = null,
                                         found: Map<String, Int>? = null, focus: Int = -1) {
    // The episode as the whole page has it, once in: its name and what it's about in the TV's language.
    val meta = wholeMeta[wholeKey(given.title.id)] ?: given
    val e = meta.episodes.firstOrNull { it.id == picked.id } ?: picked
    val t = meta.title
    val title = getString(R.string.player_episode, t.name, e.season, e.episode)
    redrawFor = t.id
    redraw = { if (pageShowing(title)) showVodEpisode(meta, ref, e, available, found, focusedRow() ?: focus) }
    val released = e.released?.take(10)?.let { d -> runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(d) }.getOrNull() }
        ?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(it) }
    val mark = VodWatched.all(vodDir())[e.id]
    val seen = mark != null
    val seenLine = mark?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }

    val (servicesBar, services) = serviceBar(t, available)
    // What this episode is about; the series' description isn't its own, so none says so.
    val about = e.overview?.takeIf { it.isNotBlank() } ?: getString(R.string.episode_no_description)
    val tmdbId = t.tmdbId ?: Local.get(t.id)?.tmdbId
    val top = titleTop(listOfNotNull(episodeLine(e), released).joinToString(" · "), e.name.takeIf { it.isNotBlank() }, about, seenLine,
        director = meta.director, cast = meta.cast, tmdbId = tmdbId, isTv = true)

    val sources = streamSources(t.type, e.id)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    fun play(only: String?) {
        pageReturn = { showVodEpisode(meta, ref, e, services.known, found, focus) }
        playTitle(ref, meta, e, only)
    }
    val having = found?.filterValues { it > 0 }?.keys?.let { keys -> sources.filter { it.first in keys } }
    val manual = manualStreams()
    if (sources.isEmpty() || having?.isEmpty() == true) rows += page(getString(R.string.title_no_streams)) { }
    else {
        // Watch: the player takes the streams found here (see warmTitle), or, picked by hand, the streams' page does.
        val sole = soleAddon(having ?: sources)
        icons[rows.size] = watchIcon(sole)
        rows += page(watchLabel(sole, VodProgress.position(vodDir(), e.id), seen), streamsLine(found)) {
            if (manual) pickStream(ref, meta, e) { showVodEpisode(meta, ref, e, services.known, found, 0) } else play(sole?.base)
        }
    }
    val markRow = rows.size
    rows += page(getString(if (seen) R.string.title_mark_unwatched else R.string.title_mark_watched)) {
        markWatched(meta, listOf(e), !seen)
        showVodEpisode(meta, ref, e, services.known, found, markRow)
    }
    val still = e.thumbnail
    val art = when {
        still != null -> Triple(still, null, ref.poster ?: t.poster)
        ref.background != null -> Triple(ref.background, ref.logo, ref.poster ?: t.poster)
        else -> Triple(t.background, t.logo, ref.poster ?: t.poster)
    }
    if (handheld()) rows += page(getString(R.string.share)) { shareTitle(t.type, t.id, t.name, e.season, e.episode) }
    val targetFocus = if (focus < 0 && top.descView != null) -1 else if (focus < 0) 0 else focus
    titlePage(title, art, ref.fallback, t.name, top, rows, targetFocus, icons, servicesBar = servicesBar) {
        showVodSeason(meta, ref, e.season, services.known, e.id)
    }
    services.later { showVodEpisode(meta, ref, e, it, found, focusedRow() ?: targetFocus) }
    if (found == null) warmTitle(t.type, e.id, sources, title) { counts -> showVodEpisode(meta, ref, e, services.known, counts, focusedRow() ?: targetFocus) }
}

/**
 * A film's or episode's streams looked for as its page opens, every source at once, and kept for the player (see
 * StreamCache): Watch then plays at once. Once all have answered, [shown] with how many each has, while the page
 * (titled [page]) is still the one up.
 */
private fun MainActivity.warmTitle(type: String, id: String, sources: List<Pair<String, String>>, page: String, shown: (Map<String, Int>) -> Unit) {
    if (sources.isEmpty()) return
    val names = sources.toMap()
    StreamCache.warm(cacheDir, sources.map { it.first }, type, id, { b -> sourceStreams(prefs, filesDir, b, names[b].orEmpty(), type, id) }) { counts ->
        ui.post { if (pageShowing(page)) shown(counts) }
    }
}

/** Watch's count: looking for streams, then how many all the sources have. */
private fun MainActivity.streamsLine(found: Map<String, Int>?): String =
    found?.values?.sum()?.let { n -> resources.getQuantityString(R.plurals.title_streams, n, n) } ?: getString(R.string.title_finding_streams)

/**
 * Marks [episodes] of the series [meta] watched ([on]) or not, from its pages: watched ones off Continue Playing; the
 * series itself watched once all its episodes are (specials aside), and not any more once one isn't.
 */
private fun MainActivity.markWatched(meta: Stremio.TitleMeta, episodes: List<Stremio.Episode>, on: Boolean) {
    val id = meta.title.id
    VodWatched.set(vodDir(), episodes.map { it.id to id }, on, name = meta.title.name)
    traktHistory(episodes.map { it.id to id }, on)
    if (on) episodes.forEach { VodProgress.remove(vodDir(), it.id) }
    markWhole(meta)
}

/**
 * The series [meta] marked watched once all its episodes are (specials aside), and not any more once one isn't:
 * after marks on its pages, and when its page opens (the player and Trakt mark episodes only).
 */
private fun MainActivity.markWhole(meta: Stremio.TitleMeta) {
    if (meta.title.type != "series" || meta.episodes.isEmpty()) return
    val id = meta.title.id
    val marks = VodWatched.all(vodDir())
    val whole = meta.episodes.filter { it.season > 0 }.ifEmpty { meta.episodes }.all { it.id in marks }
    if (whole != (id in marks)) VodWatched.set(vodDir(), listOf(id to id), whole, name = meta.title.name)
}

/**
 * A film's or episode's Watch row: Resume from where it [at] got to, Watch again once [seen], else Watch; "on" the
 * add-on when one alone ([sole]) has its streams.
 */
private fun MainActivity.watchLabel(sole: Stremio.Addon?, at: Long?, seen: Boolean) = when {
    sole != null && at != null -> getString(R.string.title_resume_on, clock(at), sole.name)
    at != null -> getString(R.string.title_resume, clock(at))
    sole != null && seen -> getString(R.string.title_watch_again_on, sole.name)
    seen -> getString(R.string.title_watch_again)
    sole != null -> getString(R.string.tmdb_search_on, sole.name)
    else -> getString(R.string.title_watch)
}

/** "Season 1 · Episode 3" (specials: "Special 3"). */
internal fun MainActivity.episodeLine(e: Stremio.Episode) =
    if (e.season == 0) getString(R.string.title_special_n, e.episode) else getString(R.string.title_season_episode, e.season, e.episode)

internal class TitleTopResult(
    val root: View,
    val descView: View?,
    val directorCards: List<View> = emptyList(),
    val castCards: List<View> = emptyList()
)

private fun MainActivity.personCard(name: String, isDirector: Boolean, onImage: (ImageView) -> Unit): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(76), WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
    }
    val frame = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }
    val bg = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x28FFFFFF)
        }
        layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
    }
    frame.addView(bg)

    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        setImageResource(R.drawable.ic_profile)
        imageAlpha = 140
        layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
    }
    frame.addView(img)

    val rim = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2), Color.TRANSPARENT)
    }
    val rimView = View(this).apply {
        background = rim
        layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
    }
    frame.addView(rimView)

    card.addView(frame)

    val nameLabel = TextView(this).apply {
        text = name
        textSize = 10.5f
        setTextColor(fg)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        alpha = 0.8f
        setPadding(0, dp(4), 0, 0)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
    card.addView(nameLabel)

    card.setOnFocusChangeListener { _, focused ->
        rim.setStroke(dp(2), if (focused) Color.WHITE else Color.TRANSPARENT)
        nameLabel.alpha = if (focused) 1f else 0.8f
        card.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(140).start()
        if (focused) {
            val hScroll = card.parent?.parent as? android.widget.HorizontalScrollView
            hScroll?.post {
                hScroll.smoothScrollTo((card.left - dp(16)).coerceAtLeast(0), 0)
            }
        }
    }

    card.setOnClickListener {
        closeLauncherSettings()
        searchFilter = if (isDirector) SearchFilter(director = name) else SearchFilter(actor = name)
        searchTmdb("")
    }

    onImage(img)
    return card
}

/** Under a title's art: its [info] line, a [name] (an episode's), when the user [seen] it, what it's about, [director] and [cast]. */
private fun MainActivity.titleTop(info: String, name: String?, about: String?, seen: String? = null,
                                  director: List<String> = emptyList(), cast: List<String> = emptyList(),
                                  tmdbId: Long? = null, isTv: Boolean = false): TitleTopResult {
    var descView: View? = null
    val directorCards = ArrayList<View>()
    val castCards = ArrayList<View>()
    val pendingPhotos = ArrayList<Pair<String, ImageView>>()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        name?.let { addView(label(it, 16f).apply {
            setPadding(0, 0, 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            isSingleLine = false
            maxLines = 2
        }) }
        addView(label(info, 13f).apply {
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            isSingleLine = false
            maxLines = 2
        })
        seen?.let { addView(label("✓  $it", 13f).apply {
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }) }
        about?.let { d ->
            val v = label(d, 13.5f).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
                maxLines = 10
                isSingleLine = false
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.2f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isFocusable = true
                isClickable = true
                background = null
                setOnClickListener {
                    maxLines = if (maxLines == 10) 50 else 10
                }
            }
            descView = v
            addView(v)
        }
        if (director.isNotEmpty() || cast.isNotEmpty()) {
            val combinedRow = LinearLayout(this@titleTop).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
            if (director.isNotEmpty()) {
                val dirSection = LinearLayout(this@titleTop).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        if (cast.isNotEmpty()) marginEnd = dp(20)
                    }
                    addView(label(getString(R.string.title_director_label), 12f).apply {
                        alpha = 0.65f
                        setPadding(0, dp(10), 0, dp(4))
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    })
                    val dirRow = LinearLayout(this@titleTop).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    }
                    for (d in director) {
                        val card = personCard(d, isDirector = true) { img -> pendingPhotos.add(d to img) }
                        directorCards.add(card)
                        dirRow.addView(card)
                    }
                    addView(dirRow)
                }
                combinedRow.addView(dirSection)
            }
            if (cast.isNotEmpty()) {
                val castSection = LinearLayout(this@titleTop).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    addView(label(getString(R.string.title_cast_label), 12f).apply {
                        alpha = 0.65f
                        setPadding(0, dp(10), 0, dp(4))
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    })
                    val castRow = LinearLayout(this@titleTop).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    }
                    for (c in cast.take(15)) {
                        val card = personCard(c, isDirector = false) { img -> pendingPhotos.add(c to img) }
                        castCards.add(card)
                        castRow.addView(card)
                    }
                    addView(castRow)
                }
                combinedRow.addView(castSection)
            }
            addView(android.widget.HorizontalScrollView(this@titleTop).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                addView(combinedRow)
            })
        }
    }

    if (pendingPhotos.isNotEmpty()) {
        val key = tmdbKey()
        if (key != null) posterIo.execute {
            if (tmdbId != null && tmdbId > 0) {
                runCatching { TmdbClient.credits(key, tmdbId, isTv) }
            }
            for ((pName, img) in pendingPhotos) {
                val photo = TmdbClient.cachedPersonPhoto(pName) ?: runCatching { TmdbClient.personPhoto(key, pName) }.getOrNull()
                if (photo != null) {
                    val bmp = runCatching { art(photo) }.getOrNull()
                    if (bmp != null) ui.post {
                        if (img.parent != null) {
                            img.setImageBitmap(bmp)
                            img.imageAlpha = 255
                        }
                    }
                }
            }
        }
    }

    return TitleTopResult(root, descView, directorCards, castCards)
}

/**
 * A title's page, as a whole page (see detailPage): [art] (backdrop, logo, poster) over the screen;
 * on wide screens, [top] (details, description, director, cast) is placed in a left column and [rows]
 * in the right panel; then [servicesBar] (trailer and external apps) at the bottom of the buttons.
 */
private fun MainActivity.titlePage(title: String, art: Triple<String?, String?, String?>, fallback: Triple<String?, String?, String?>?, name: String,
                                   top: TitleTopResult, rows: List<Triple<String, String, () -> Unit>>, focus: Int,
                                   icons: Map<Int, android.graphics.drawable.Drawable>, holds: Map<Int, () -> Unit> = emptyMap(),
                                   servicesBar: View? = null,
                                   back: () -> Unit) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val scrim = settingsScrim as FrameLayout
    val w = scrim.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    val wide = w > dp(720)
    showPage(title, rows, focus, top = if (wide) null else top.root, back = back, panes = glass)
    // A whole page: the art over the screen, its logo and details on the left, buttons on the right.
    detailPage("$name|${art.first}|${art.third}", listOfNotNull(art.second, fallback?.second).distinct(), title = name, infoView = top.root) { frame, fw, fh ->
        fillArt(frame, fw, fh, art.first, null, art.third, name, fallback?.let { Triple(it.first, null, it.third) })
        sharpBackdrop(frame, fw, listOfNotNull(art.first, fallback?.first).distinct())
    }
    val firstIndex = if (wide) 0 else 1
    val descView = top.descView
    val firstPersonCard = top.directorCards.firstOrNull() ?: top.castCards.firstOrNull()

    // If starting on description, focus it and scroll to top
    if (focus < 0 && descView != null) {
        descView.post {
            descView.requestFocus()
            val scroll = (descView.parent?.parent as? android.widget.ScrollView) ?: (settingsList.parent as? android.widget.ScrollView)
            scroll?.scrollTo(0, 0)
        }
    }
    val focusBg = (fg and 0xFFFFFF) or 0x2E000000
    val descBg = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.TRANSPARENT)
    }
    descView?.background = descBg
    descView?.setOnFocusChangeListener { _, focused ->
        val from = if (focused) Color.TRANSPARENT else focusBg
        val to = if (focused) focusBg else Color.TRANSPARENT
        android.animation.ValueAnimator.ofArgb(from, to).setDuration(180).apply {
            addUpdateListener { descBg.setColor(it.animatedValue as Int) }
        }.start()
        descView.alpha = if (focused) 1f else 0.85f
        if (focused) {
            var p = descView.parent
            while (p != null && p !is android.widget.ScrollView) {
                p = p.parent
            }
            val scroll = (p as? android.widget.ScrollView) ?: (settingsList.parent as? android.widget.ScrollView)
            scroll?.post {
                val rect = android.graphics.Rect()
                descView.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(descView, rect)
                val targetY = (rect.top - dp(16)).coerceAtLeast(0)
                scroll.smoothScrollTo(0, targetY)
            }
        }
    }
    // Back up on the first row, the page scrolls back up: nothing above it takes focus to pull it there (unless top has focusables).
    val firstRow = settingsList.getChildAt(firstIndex)
    if (firstRow != null && firstRow.id == View.NO_ID) firstRow.id = View.generateViewId()
    val firstRowId = firstRow?.id
    firstRow?.let { first ->
        val own = first.onFocusChangeListener
        first.setOnFocusChangeListener { v, focused ->
            own?.onFocusChange(v, focused)
            val scroll = settingsList.parent as android.widget.ScrollView
            if (focused) scroll.post { scroll.smoothScrollTo(0, (v.bottom + dp(8) - scroll.height).coerceAtLeast(0)) }
        }
    }

    val targetRow = settingsList.getChildAt(firstIndex + focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
    if (targetRow != null && targetRow.id == View.NO_ID) targetRow.id = View.generateViewId()

    // Remote navigation: left from buttons goes to top element on the left (the title);
    // right from left column goes to the first item at the top of the right column.
    if (wide) {
        val titleBox = detailTitleView
        val leftTopId = titleBox?.id ?: descView?.id ?: firstPersonCard?.id

        if (leftTopId != null) {
            for (i in 0 until rows.size) {
                val row = settingsList.getChildAt(firstIndex + i) ?: continue
                row.nextFocusLeftId = leftTopId
            }
        }
        val allPersonCards = top.directorCards + top.castCards

        if (titleBox != null) {
            if (firstRowId != null) titleBox.nextFocusRightId = firstRowId
            titleBox.nextFocusUpId = titleBox.id
            titleBox.nextFocusLeftId = titleBox.id
            titleBox.nextFocusDownId = descView?.id ?: allPersonCards.firstOrNull()?.id ?: titleBox.id
        }

        if (descView != null) {
            if (firstRowId != null) descView.nextFocusRightId = firstRowId
            descView.nextFocusLeftId = descView.id
            descView.nextFocusUpId = titleBox?.id ?: descView.id
            if (allPersonCards.isNotEmpty()) descView.nextFocusDownId = allPersonCards[0].id
        }

        for (i in 0 until allPersonCards.size) {
            val card = allPersonCards[i]
            card.nextFocusUpId = descView?.id ?: titleBox?.id ?: card.id
            card.nextFocusDownId = card.id
            if (i > 0) card.nextFocusLeftId = allPersonCards[i - 1].id
            else card.nextFocusLeftId = card.id
            if (i < allPersonCards.size - 1) card.nextFocusRightId = allPersonCards[i + 1].id
            else if (firstRowId != null) card.nextFocusRightId = firstRowId
        }
    }
    // What holding OK on a row does (see MainActivity.onHold).
    for ((i, hold) in holds) settingsList.getChildAt(firstIndex + i)?.setTag(R.id.settingsList, hold)
    rowIcons(icons, first = firstIndex)
    servicesBar?.let { bar ->
        settingsList.addView(bar.apply { setPadding(dp(14), dp(12), dp(14), dp(4)) })
        // showPage keeps Down on the last row from leaving it: here it goes down to the apps, and Up from them back to it;
        // Down from the apps goes nowhere.
        val buttons = ((bar as? android.widget.HorizontalScrollView)?.getChildAt(0) as? LinearLayout) ?: return@let
        val last = settingsList.getChildAt(settingsList.childCount - 2)?.takeIf { rows.isNotEmpty() } ?: return@let
        if (last.id == View.NO_ID) last.id = View.generateViewId()
        last.nextFocusDownId = buttons.getChildAt(0).id
        val leftId = detailTitleView?.id ?: descView?.id ?: firstPersonCard?.id
        for (c in 0 until buttons.childCount) buttons.getChildAt(c).let {
            it.nextFocusUpId = last.id
            it.nextFocusDownId = it.id
            if (leftId != null && wide) it.nextFocusLeftId = leftId
        }
    }
}

/**
 * Each row with an icon in [icons] (by its index among the rows, the first at [first] in the list) starts with it,
 * before the name. Vector and shape icons are tinted as the row's text, inverted with focus; pictures stay as they are.
 */
internal fun MainActivity.rowIcons(icons: Map<Int, android.graphics.drawable.Drawable>, first: Int) {
    for ((i, icon) in icons) {
        val row = settingsList.getChildAt(first + i) as? LinearLayout ?: continue
        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        row.addView(iv, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val orig = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, focused ->
            orig?.onFocusChange(v, focused)
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                iv.setColorFilter(if (focused && !glass) onFg else fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }
}

/**
 * A title's trailer and streaming apps, as TMDB knows it, shown horizontally without "Watch on...";
 * [known]: the services TMDB lists it on, when it has answered. [later] asks it when it hasn't,
 * and calls back with the answer.
 */
private class Services(val known: Set<String>?, val later: (((Set<String>) -> Unit) -> Unit))

private fun MainActivity.serviceBar(t: Stremio.Title, available: Set<String>?): Pair<View?, Services> {
    val item = tmdbItemOf(t) ?: return null to Services(available) { }
    val region = TmdbClient.region(tmdbLang())
    val known = available ?: TmdbClient.cachedProviders(item, region)
    val apps = titleApps().filter { TmdbClient.offers(it.pkg, known) }

    val barView = if (apps.isEmpty()) null else {
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val rest = if (glass) 0x662C2C34.toInt() else if (dark) 0x991C1C22.toInt() else 0x99DCE0E8.toInt()

            val btnIds = apps.indices.map { View.generateViewId() }
            for ((idx, a) in apps.withIndex()) {
                val btn = FrameLayout(context).apply {
                    id = btnIds[idx]
                    isFocusable = true
                    isClickable = true
                    nextFocusLeftId = if (idx > 0) btnIds[idx - 1] else btnIds[0]
                    nextFocusRightId = if (idx < apps.size - 1) btnIds[idx + 1] else btnIds[idx]
                    val pad = dp(6)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else a.label

                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(rest)
                    }
                    background = bg
                    val light = if (glass) glassPane(this, dp(8)) else null

                    val iv = ImageView(context).apply {
                        setImageDrawable(a.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(iv, FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                        gravity = Gravity.CENTER
                    })

                    // Focus marks it with a ring inside its own bounds: grown, it was cut off by the bar's edges.
                    setOnFocusChangeListener { _, focused ->
                        if (light != null) light(focused) else bg.setStroke(if (focused) dp(2) else 0, if (focused) fg else Color.TRANSPARENT)
                        if (focused) {
                            (parent?.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(left - dp(16), 0)
                        }
                    }
                    setOnClickListener {
                        closeLauncherSettings()
                        openInApp(a, item)
                    }
                }
                container.addView(btn, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginStart = if (idx > 0) dp(8) else 0
                })
            }
            addView(container)
        }
    }

    val title = findViewById<android.widget.TextView>(R.id.settingsTitle)
    val services = Services(known) { again ->
        val key = tmdbKey()
        if (known == null && providersOn() && key != null) {
            val shown = title?.text?.toString() ?: ""
            spotlightIo.execute {
                val found = TmdbClient.providers(key, item, region) ?: return@execute
                ui.post { if (pageShowing(shown)) again(found) }
            }
        }
    }
    return barView to services
}

/**
 * [t] as a TMDB result, for its trailer and services: known once its name in the TV's language was asked of TMDB
 * (see localizeMeta); its IMDb id kept with it, for the apps that open a title by it. Null without a TMDB key.
 */
private fun MainActivity.tmdbItemOf(t: Stremio.Title): TmdbItem? {
    if (tmdbKey() == null || !t.id.startsWith("tt")) return null
    Local.use(filesDir, tmdbLang())
    val l = Local.get(t.id) ?: return null
    val item = TmdbItem(l.tmdbId, l.title, l.overview, null, l.vote, if (l.tv) "tv" else "movie", l.genres, l.year)
    TmdbClient.knowImdbId(item, t.id)
    return item
}

/** The add-on behind [sources] when it's the only one (Watch on it), else null. */
internal fun MainActivity.soleAddon(sources: List<Pair<String, String>>): Stremio.Addon? =
    sources.singleOrNull()?.let { (base, _) -> addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.firstOrNull { it.base == base } }

/**
 * A Watch row's icon: [addon]'s tile's (when it has a tile of its own), else its logo (once on disk), else Play,
 * tinted for the row when it's a mark rather than a picture.
 */
internal fun MainActivity.watchIcon(addon: Stremio.Addon?): android.graphics.drawable.Drawable =
    addon?.let { addonIcon(it) } ?: getDrawable(R.drawable.ic_play)!!.mutate()

/** A Watch on row's icon for the stream source [base]: a Nuvio plugin's mark, else its add-on's (see watchIcon). */
internal fun MainActivity.sourceIcon(base: String, name: String): android.graphics.drawable.Drawable =
    if (base.startsWith(PLUGIN)) getDrawable(R.drawable.ic_stream)!!.mutate() else watchIcon(soleAddon(listOf(base to name)))

/** [addon]'s icon: its tile's (when it has a tile of its own), else its logo once on disk (see updateAddon); else none. */
internal fun MainActivity.addonIcon(addon: Stremio.Addon): android.graphics.drawable.Drawable? {
    apps.firstOrNull { it.pkg == STREMIO + addon.id }?.icon?.constantState?.newDrawable(resources)?.mutate()?.let { return it }
    return addon.logo?.takeIf { Images.has(it, Images.Kind.LOGO) }?.let { uri ->
        runCatching { android.graphics.BitmapFactory.decodeFile(Images.file(uri, Images.Kind.LOGO).path) }.getOrNull()
            ?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
    }
}

/**
 * Plays the film, or the episode [episode] with the ones after it (for the next ones), in Zephyr TV's player: the
 * stream add-ons that have it are asked for their streams (see PlayerActivity, MODE_VOD), or [only] the one at that
 * address. The player shows the series' name, and the episode's season, number and name under it.
 */
internal fun MainActivity.playTitle(ref: TitleRef, meta: Stremio.TitleMeta?, episode: Stremio.Episode?, only: String? = null,
                                    chosen: Stremio.Stream? = null, resumeWith: String? = null) {
    val t = meta?.title
    val name = t?.name ?: ref.name
    val poster = ref.poster ?: t?.poster
    val bg = ref.background ?: t?.background
    val locandina = poster ?: bg
    val backdrop = bg ?: poster
    // One item for a film; for a series its episodes, the one picked [start] among them, so CH- goes back as CH+ goes
    // on: a window around it (a long series' every episode, with what each is about, is too much to hand the player).
    val all = if (episode == null || meta == null) emptyList() else meta.episodes
    val picked = all.indexOfFirst { it.id == episode?.id }.coerceAtLeast(0)
    val from = (picked - EPISODES_BEFORE).coerceAtLeast(0)
    val items: List<Triple<String, String, Stremio.Episode?>> = if (all.isEmpty()) listOf(Triple(t?.id ?: ref.id, name, null))
        else all.subList(from, minOf(all.size, picked + EPISODES_AFTER)).map { e -> Triple(e.id, name, e) }
    val start = if (all.isEmpty()) 0 else picked - from
    val type = t?.type ?: ref.type
    val sources = streamSources(type, items[start].first).filter { only == null || it.first == only }
    if (sources.isEmpty()) return Toast.makeText(this, getString(R.string.title_no_streams), Toast.LENGTH_LONG).show()
    val adultUrls = adultAddonUrls()
    val adultAddons = adultUrls.mapNotNull { Stremio.saved(filesDir, it)?.addon }
    val adultSourcesList = adultUrls + adultAddons.map { it.base } + adultAddons.map { it.name } + adultPluginSources(prefs, filesDir)
    val isCatalogAdult = isAdultSource(ref.source)
    val isOnlyAdult = only != null && isAdultSource(only)
    fun strings(f: (Triple<String, String, Stremio.Episode?>) -> String?) = ArrayList(items.map { f(it).orEmpty() })
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.MODE, PlayerActivity.MODE_VOD)
            .putExtra(PlayerActivity.INDEX, start)
            .apply { chosen?.let { putExtra(PlayerActivity.CHOSEN, PlayerActivity.streamJson(it).toString()) } }
            // Resumed: the stream it played, kept with its progress; a search only if it no longer plays.
            .apply { if (chosen == null && resumeWith != null) putExtra(PlayerActivity.CHOSEN, resumeWith).putExtra(PlayerActivity.CHOSEN_ELSE_SEARCH, true) }
            .putExtra(PlayerActivity.ADULT, isCatalogAdult || isOnlyAdult)
            .putStringArrayListExtra(PlayerActivity.ADULT_SOURCES, ArrayList(adultSourcesList))
            .putStringArrayListExtra(PlayerActivity.SOURCES, ArrayList(sources.map { it.first }))
            .putStringArrayListExtra(PlayerActivity.SOURCE_NAMES, ArrayList(sources.map { it.second }))
            .putStringArrayListExtra(PlayerActivity.TYPES, strings { type })
            .putStringArrayListExtra(PlayerActivity.IDS, strings { it.first })
            .putStringArrayListExtra(PlayerActivity.NAMES, strings { it.second })
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.third?.let { e -> listOf(episodeLine(e), e.name).filter { n -> n.isNotBlank() }.joinToString(" · ") } ?: t?.year ?: "" })
            // In the player the art across: the backdrop (an episode's still without one), with the title's logo on it.
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { bg ?: it.third?.thumbnail ?: poster })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { it.third?.thumbnail ?: poster ?: "" })
            .putExtra(PlayerActivity.TITLE_LOGO, ref.logo ?: t?.logo)
            .putStringArrayListExtra(PlayerActivity.DESCS, strings { it.third?.overview ?: meta?.description })
            .putExtra(PlayerActivity.META_TYPE, type)
            .putExtra(PlayerActivity.META_ID, t?.id ?: ref.id)
            .putExtra(PlayerActivity.POSTER, locandina)
            .putExtra(PlayerActivity.USER_DIR, vodDir().path))
    } catch (e: Exception) {
        warn("player didn't open ($e)")
    }
}

/** Whether streams are picked by hand from all the sources' (Settings → Content → Stream choice), not tried in turn. */
internal fun MainActivity.manualStreams() = prefs.getBoolean(K.STREAM_MANUAL, false)

/**
 * Manual stream choice: every add-on's and plugin's streams for the film, or the [episode], in one list, each with
 * the source it's from, grouped by quality. The sources are asked all at once; each one's streams come in after those
 * already there in their group (focus stays on the stream it's on), and the last row says the search goes on until all
 * have answered. A stream picked plays at once.
 */
internal fun MainActivity.pickStream(ref: TitleRef, meta: Stremio.TitleMeta, episode: Stremio.Episode?, back: () -> Unit) {
    val t = meta.title
    val id = episode?.id ?: t.id
    val title = getString(R.string.stream_pick_title)
    val sources = streamSources(t.type, id)
    val found = java.util.Collections.synchronizedList(ArrayList<Stremio.Stream>())
    var left = sources.size
    var shown = false
    // Grouped by quality as in the player's panel (the highest first, those that say none last), each group under its
    // heading; each stream on two lines: what it is on top (scrolling when it's long), where it's from under it, in italics.
    fun show() {
        val keep = if (shown) currentFocus?.tag as? String else null // the stream focused stays so as others come in
        val list = found.toList().distinctBy { it.url }.sortedByDescending { qualityGroup(it.title) }
        val rest = when {
            left > 0 -> listOf(page(getString(R.string.title_finding_streams)) { })
            found.isEmpty() -> listOf(page(getString(R.string.title_no_streams)) { })
            else -> emptyList()
        }
        showPage(title, rest, back = back)
        var group = -1
        var at = 0
        for (s in list) {
            val q = qualityGroup(s.title)
            if (q != group) {
                group = q
                settingsList.addView(label(qualityHeading(q), 13f).apply { alpha = 0.6f; setPadding(dp(14), dp(16), 0, dp(6)) }, at++)
            }
            settingsList.addView(twoLineRow(s.title.ifBlank { s.url.substringAfterLast('/').take(60) }, s.source.orEmpty()) {
                pageReturn = back
                closeLauncherSettings()
                playTitle(ref, meta, episode, chosen = s)
            }.apply { tag = s.url }, at++, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        val rows = (0 until settingsList.childCount).map { settingsList.getChildAt(it) }.filter { it.isFocusable }
        (rows.firstOrNull { keep != null && it.tag == keep } ?: rows.firstOrNull())?.requestFocus()
        shown = true
    }
    show()
    for ((base, name) in sources) netIo.execute {
        val got = runCatching { cachedStreams(prefs, filesDir, cacheDir, base, name, t.type, id) }.getOrDefault(emptyList())
            .map { Stremio.Stream(it.url, it.title, it.headers, it.group, it.source ?: name, it.langRank, it.clearkey, it.app) }
        ui.post {
            left--
            // Each source's, in the TV's language first then the preferred quality's order, after those already there in
            // their quality's group.
            found += got.sortedWith(compareBy<Stremio.Stream> { it.langRank }.then(qualityOrder(preferredQuality(prefs))))
            if (pageShowing(title)) show()
        }
    }
}

/** The episodes before and after the one picked that the player gets (see playTitle). */
private const val EPISODES_BEFORE = 100
private const val EPISODES_AFTER = 300

/** OK on a Continue Playing card of a film or episode: it goes on (a series with its episodes around it). */
private fun MainActivity.resumeTitle(ref: TitleRef) {
    netIo.execute {
        val meta = titleMeta(ref)
        ui.post {
            val episode = meta?.episodes?.firstOrNull { it.id == ref.video }
            val kept = VodProgress.all(vodDir()).firstOrNull { it.id == ref.video }?.takeIf { !it.upNext }?.stream
            playTitle(ref, meta, episode, resumeWith = kept)
        }
    }
}
