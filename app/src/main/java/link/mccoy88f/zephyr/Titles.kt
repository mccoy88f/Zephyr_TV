package link.mccoy88f.zephyr

import android.content.Intent
import link.mccoy88f.zephyr.MainActivity.Prog

// Films and series from the Stremio add-ons on the home screen: the Films and Series tiles (or each add-on's own),
// their rows and cards, and films and episodes begun in Continue Playing. A title's page is in TitlePages, the
// search and Spotlight's titles in Library, the TV's language in Localize. Functions of MainActivity, like the
// other topics; the add-ons themselves are read by Stremio.

/** App.pkg of the Films and Series tiles (see addonsVod), and of the Continue Playing cards of films and episodes. */
internal const val MOVIES_PKG = STREMIO + "movies"
internal const val SERIES_PKG = STREMIO + "series"
private const val VOD_PKG = STREMIO + "vod"

/**
 * A film or series a card stands for: what to ask its page for ([type], [id]), its name and art (as the card shows
 * it, for its page and the player), the add-on it came from ([source]: its manifest's address, asked for the page
 * first), and, on a Continue Playing card, the film or episode to go on with ([video]).
 */
internal class TitleRef(val type: String, val id: String, val name: String, val poster: String?, val background: String?,
                        val source: String? = null, val video: String? = null, val logo: String? = null, val rating: String? = null,
                        val about: String? = null, // what it's about, as its card knows it: on its page at once
                        val fallback: Triple<String?, String?, String?>? = null) // art to fall back on (see Stremio.Title.fallback)

/**
 * The catalog sources, in the user's order (Content catalog): the first, the main one, gives titles' details, search
 * and art (the others after it, for what it hasn't); the others add their rows to Films and Series, in this order.
 * Cinemeta alone by default; one set before there was a list (the catalog source) is the main one.
 */
internal fun MainActivity.catalogSources(): List<String> {
    val saved = prefs.getString(K.CATALOG_SOURCES, null)?.lines()?.mapNotNull { Stremio.manifestUrl(it) }
        ?: listOfNotNull(prefs.getString(K.CINEMETA_URL, null)?.let { Stremio.manifestUrl(it) })
    return saved.map { if (it.startsWith(Stremio.NUVIO_BASE)) nuvioUrl() else it }.distinct().ifEmpty { listOf(Stremio.CINEMETA) }
}

/** The main catalog source (see catalogSources): Cinemeta by default. */
internal fun MainActivity.cinemetaUrl(): String = catalogSources().first()

/** Nuvio's catalog in the TMDB language and the TV's country: it follows them when they change. */
internal fun MainActivity.nuvioUrl() = Stremio.nuvio(tmdbLang(), TmdbClient.region(tmdbLang()))

/**
 * Where films (or, with [type] "series", series) show: their tile (Films, Series), each add-on's own tile, or nowhere.
 * Set apart for each; one not set yet follows what was set for both before.
 */
internal fun MainActivity.addonsVod(type: String = "movie"): VodPlace =
    (prefs.getString(if (type == "series") K.ADDONS_SERIES else K.ADDONS_MOVIES, null) ?: prefs.getString(K.ADDONS_VOD, null))
        .let { k -> VodPlace.entries.firstOrNull { it.key == k } } ?: VodPlace.TILES

/** Films' or series' setting a content [type] follows: series for series, films for the rest (an add-on's own types). */
internal fun vodKind(type: String) = if (type == "series") "series" else "movie"

/** The add-ons films and series are listed from (the catalog sources first, in their order), as manifest addresses. */
internal fun MainActivity.libraryUrls(): List<String> = (catalogSources() + shownAddonUrls()).distinct()

/** The add-ons as read, those with film or series catalogs or streams. Reads them when they're missing (in the background). */
internal fun MainActivity.vodAddons(): List<Stremio.Addon> = libraryUrls().mapNotNull { url ->
    Stremio.saved(filesDir, url)?.addon ?: null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
}

/** The add-ons that give streams for a film or episode of [type] with this [id] (address without /manifest.json, and name). */
internal fun MainActivity.streamSources(type: String, id: String): List<Pair<String, String>> {
    refreshPlugins()
    return shownAddonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.provides("stream", type, id) }.map { it.base to it.name } +
        pluginSources(prefs, filesDir, type, id, adultUnlocked()) // Nuvio's plugins after the add-ons
}

/** The tiles films and series add to the apps row: Films and Series, or nothing here (each add-on's own, see addonApps). */
internal fun MainActivity.vodApps(): List<MainActivity.App> {
    if (libraryUrls().isEmpty()) return emptyList()
    return listOfNotNull(
        if (addonsVod("movie") != VodPlace.TILES) null
        else MainActivity.App(MOVIES_PKG, getString(R.string.vod_movies), Intent(), getDrawable(R.drawable.ic_movie)!!, picture = getDrawable(R.drawable.tile_films)),
        if (addonsVod("series") != VodPlace.TILES) null
        else MainActivity.App(SERIES_PKG, getString(R.string.vod_series), Intent(), getDrawable(R.drawable.ic_series)!!, picture = getDrawable(R.drawable.tile_series)),
    )
}

/** The add-ons with film or series catalogs that get a tile of their own (films and series "apps"). */
/** Optional private home collection. Its URLs live only in the device's preferences. */
internal fun MainActivity.homeCatalogs(): List<Pair<String,Set<String>>> = runCatching {
    val a=org.json.JSONArray(prefs.getString("home_catalogs", "[]"))
    (0 until a.length()).map { i -> val o=a.getJSONObject(i);val ids=o.optJSONArray("catalogs")
        o.getString("url") to (if(ids==null)emptySet() else (0 until ids.length()).map { ids.getString(it) }.toSet()) }
}.getOrDefault(emptyList())

internal fun MainActivity.vodTileAddons(): List<Stremio.Addon> =
    vodAddons().filter { homeCatalogs().isEmpty() || it.url==homeCatalogs().first().first }.filter { a -> a.vod.any { it.listed && addonsVod(vodKind(it.type)) == VodPlace.APPS } && !apart(a.url) }

/**
 * The rows of films or series under the tile [pkg]: under Films or Series, every add-on's catalogs of that type
 * ("Cinemeta · Popular"); under an add-on's own tile, its catalogs of both. A catalog read a few hours ago is read
 * again in the background, and the rows built again once it's in. Runs off the UI thread.
 */
internal fun MainActivity.titleSections(pkg: String, hidden: Set<String>): List<Section> {
    val type = when (pkg) { MOVIES_PKG -> "movie"; SERIES_PKG -> "series"; else -> null }
    val mode = addonsVod(type ?: "movie")
    val collector = COLLECTOR_PKGS[pkg] // Radio, Videos, Courses…: the extensions' rows of that kind
    val ownUrl = ownAppUrl(pkg)
    val adult = pkg == ADULT_PKG || (ownUrl != null && isAdultAddon(ownUrl))
    val personal=homeCatalogs()
    val collection=personal.isNotEmpty() && vodAddons().firstOrNull { it.url==personal.first().first }?.let { STREMIO+it.id }==pkg
    val addons = when {
        collection -> personal.mapNotNull { (url,_) -> vodAddons().firstOrNull { it.url==url } }
        collector != null -> vodAddons().filter { a -> a.vod.any { extensionRoutes(a.url, it.type) && collectorOf(it.type) == collector } }
        ownUrl != null -> vodAddons().filter { it.url == ownUrl } // an add-on's own app: its films and series
        adult -> if (adultApp()) vodAddons().filter { isAdultAddon(it.url) } else return emptyList()
        mode == VodPlace.TILES && type != null -> vodAddons().filter { !apart(it.url) || extensionRoutes(it.url, type) }
        pkg.startsWith(STREMIO) && VodPlace.APPS in listOf(addonsVod("movie"), addonsVod("series")) -> vodAddons().filter { STREMIO + it.id == pkg }
        else -> return emptyList()
    }
    // Which of an add-on's shelves of [shelfType] show here: in a collector, those given to it; in an extension's own
    // app, those not given away; under Films and Series, an extension's given to them (films: its own types too).
    fun shows(url: String, shelfType: String): Boolean = when {
        collector != null -> extensionRoutes(url, shelfType) && collectorOf(shelfType) == collector
        ownUrl != null -> !extensionRoutes(url, shelfType)
        ExtensionCalls.idOf(url) != null && type != null -> !apart(url) || extensionRoutes(url, shelfType)
        // An add-on's own tile: its films, its series, those set to show in their add-on's tile.
        collection -> true
        type == null && !adult -> addonsVod(vodKind(shelfType)) == VodPlace.APPS
        else -> true
    }
    val missing = ArrayList<String>()
    val artless = ArrayList<Stremio.Title>()
    val unasked = ArrayList<String>() // films whose release TMDB hasn't been asked about (see Releases)
    val hideSoon = hideUnreleased()
    if (tmdbKey() != null) Releases.use(filesDir)
    val watched = VodWatched.all(vodDir())
    val main = cinemetaUrl()
    val shelvesOff = prefs.getStringSet(K.SHELVES_OFF, emptySet())!! // turned off on the catalog's page (see shelfKey)
    return addons.flatMap { addon ->
        if (Stremio.vodDue(filesDir, addon.url) && addon.vod.any { it.listed }) refreshShelves(addon, pkg)
        // Under Films, the add-ons' own types too ("Porn", "Anime"): they play as films.
        Stremio.shelves(filesDir, addon).filter { type == null || (it.catalog.type == "series") == (type == "series") }
            .filter { !collection || personal.firstOrNull { p -> p.first==addon.url }?.second?.let { ids -> ids.isEmpty() || "${it.catalog.type}/${it.catalog.id}" in ids }==true }
            .filter { shows(addon.url, it.catalog.type) && shelfKey(addon, it.catalog) !in shelvesOff }.mapNotNull { shelf ->
            val kind = kindName(shelf.catalog.type)
            val name = if (type != null || adult) "${addon.name} · ${shelf.catalog.name}" else "$kind · ${shelf.catalog.name}"
            // In the TV's language when TMDB has the title (those it hasn't been asked about yet: once it's answered);
            // else, in another add-on's row (another catalog source's too), with the main catalog's art (see withMainArt).
            val cards = shelf.titles.map { t ->
                localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id }.let { if (addon.url != main) withMainArt(it, artless) else it }
            }
                .filter { hideKey(null, it.name) !in hidden }
                .onEach { if (it.type == "movie" && Releases.askable(it.id) && Releases.known(it.id) == null) unasked += it.id }
                .filter { !(hideSoon && it.type == "movie" && Releases.unreleased(it.id)) }
                .map { titleCard(it, addon.url, pkg, it.id in watched) }
            if (cards.isEmpty()) null else Section(shelfKey(addon, shelf.catalog), name, name, cards)
        }
    }.plus(
        // Last, the films and series watched: under Films and Series, or Cinemeta's own tile.
        if (!collection && !adult && collector == null && ownUrl == null && (mode == VodPlace.TILES || addons.any { it.url == cinemetaUrl() }))
            watchedSections(type, pkg, hidden, missing) else emptyList()
    ).also {
        if (missing.isNotEmpty()) localizeLater(missing.distinct(), pkg)
        if (artless.isNotEmpty()) mainArtLater(artless, pkg)
        if (unasked.isNotEmpty()) releasesLater(unasked.distinct(), pkg)
    }
}

/** A catalog's row, the same under every tile: its section's key, and what turns it off (K.SHELVES_OFF). */
internal fun shelfKey(addon: Stremio.Addon, c: Stremio.Catalog) = "vod:${addon.id}:${c.type}:${c.id}"

/** Whether films not out at home yet are hidden from Spotlight and the apps' rows (Content). */
internal fun MainActivity.hideUnreleased() = prefs.getBoolean(K.HIDE_UNRELEASED, false)

/** A round of [ids] (films) asked of TMDB whether they're out at home (see Releases); then the rows under [pkg] again. */
private fun MainActivity.releasesLater(ids: List<String>, pkg: String) {
    val key = tmdbKey() ?: return
    val region = TmdbClient.region(tmdbLang())
    val todo = ids.filter { Releases.asking.add(it) }.take(RELEASES_ROUND)
    if (todo.isEmpty()) return
    netIo.execute {
        runCatching { Releases.ask(key, todo, region) }.onFailure { warn("TMDB releases failed (${TmdbClient.redact(it)})") }
        Releases.asking.removeAll(todo.toSet())
        ui.post { rowsAgain(pkg) }
    }
}

/**
 * Spotlight's [titles] without the films not out at home yet, when they're hidden: those not asked about yet asked
 * first (TMDB's key needed; without it, all stay). Network: off the UI thread.
 */
internal fun MainActivity.withoutUnreleased(titles: List<TmdbItem>, key: String?): List<TmdbItem> {
    if (!hideUnreleased() || key == null) return titles
    Releases.use(filesDir)
    fun id(t: TmdbItem) = t.imdbId ?: "tmdb:${t.id}"
    val films = titles.filter { it.mediaType == "movie" }.map { id(it) }
    runCatching { Releases.ask(key, films.filter { Releases.known(it) == null }, TmdbClient.region(tmdbLang())) }
    return titles.filter { !(it.mediaType == "movie" && Releases.unreleased(id(it))) }
}

private const val RELEASES_ROUND = 80 // films asked about at a time: the rest at the next round

/**
 * The Films watched and Series watched rows (films only for [type] "movie", series only for "series"), newest
 * first, a series by its last episode watched: Cinemeta's art for each (by its IMDb id), in the TV's language when
 * TMDB has it ([missing] gets those it wasn't asked about yet), when it was watched under it. Names missing from old
 * marks are looked up on Cinemeta, and the rows built again once they're in. Off the UI thread.
 */
private fun MainActivity.watchedSections(type: String?, pkg: String, hidden: Set<String>, missing: MutableList<String>): List<Section> {
    val (films, shows) = VodWatched.titles(vodDir())
    val nameless = (films.map { it to "movie" } + shows.map { it to "series" }).filter { it.first.name.isEmpty() }
    if (nameless.isNotEmpty()) nameLater(nameless, pkg)
    fun section(kind: String, title: Int, marks: List<VodWatched.Mark>): Section? {
        val cards = marks.filter { it.metaId.startsWith("tt") }.take(40).map { m ->
            val t = Stremio.Title(kind, m.metaId, m.name.ifEmpty { m.metaId }, Stremio.art(m.metaId, "poster/small"), Stremio.art(m.metaId, "background/medium"),
                Stremio.art(m.metaId, "logo/medium"), null)
            val shown = localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id }
            titleCard(shown, cinemetaUrl(), pkg, watched = true, sub = listOfNotNull("✓", watchedOn(m.at)).joinToString(" · "))
        }.filter { hideKey(null, it.title) !in hidden }
        val name = getString(title)
        return if (cards.isEmpty()) null else Section("vod:watched:$kind", name, name, cards)
    }
    return listOfNotNull(
        if (type == "series") null else section("movie", R.string.vod_watched_movies, films),
        if (type == "movie") null else section("series", R.string.vod_watched_series, shows),
    )
}

private val naming = java.util.Collections.synchronizedSet(HashSet<String>()) // asked once while Zephyr TV runs

/** The names of [marks] (with their kind) from Cinemeta, kept with them; then the rows under [pkg] again, if it's still in focus. */
private fun MainActivity.nameLater(marks: List<Pair<VodWatched.Mark, String>>, pkg: String) {
    val todo = marks.filter { naming.add(it.first.metaId) }.take(40)
    if (todo.isEmpty()) return
    val dir = vodDir()
    val base = cinemetaUrl().removeSuffix("/manifest.json")
    netIo.execute {
        val names = parallel(todo, 6, 20) { (m, kind) -> Stremio.titleMeta(base, kind, m.metaId)?.title?.name?.let { m.metaId to it } }.toMap()
        runCatching { VodWatched.name(dir, names) }
        if (names.isNotEmpty()) ui.post { rowsAgain(pkg) }
    }
}

private val refreshing = java.util.Collections.synchronizedSet(HashSet<String>())

/** Reads [addon]'s film and series catalogs again; once they're in, the rows under [pkg] again if it's still in focus. */
private fun MainActivity.refreshShelves(addon: Stremio.Addon, pkg: String) {
    if (!refreshing.add(addon.url)) return
    netIo.execute {
        val ok = runCatching { Stremio.updateShelves(filesDir, addon) }.onFailure { warn("Stremio catalogs failed: ${android.net.Uri.parse(addon.url).host} ($it)") }.isSuccess
        refreshing.remove(addon.url)
        if (ok) ui.post { rowsAgain(pkg) }
    }
}

/**
 * A film's or series' card: its wide art (or its poster on a blur of itself), under it its year, genre and rating,
 * marked with the icon of the tile [icon] it's under (Films, Series, or the add-on's own), and ✓ once [watched]; or [sub].
 */
internal fun titleCard(t: Stremio.Title, source: String?, icon: String?, watched: Boolean = false, sub: String? = null) =
    Prog(t.name, sub ?: listOfNotNull("✓".takeIf { watched }, t.year, t.genres.firstOrNull(), t.rating?.let { "★ $it" }).joinToString(" · "), 0, t.background, null, null,
        logo = t.logo, poster = t.poster, icon = icon, cinema = t.type == "movie" && Releases.known(t.id) == Releases.State.CINEMA,
        vod = TitleRef(t.type, t.id, t.name, t.poster, t.background, source, logo = t.logo, rating = t.rating, about = t.description,
            fallback = t.fallback))

/** The tile a title of [type] from the add-on at [source] is under: Films or Series, or the add-on's own (null: none). */
internal fun MainActivity.titleTile(type: String, source: String?): String? = if (source != null && inAdultApp(source)) ADULT_PKG
    else if (source != null && extensionRoutes(source, type)) collectorOf(type).let { c -> if (c == "series") SERIES_PKG else COLLECTOR_PKGS.entries.firstOrNull { it.value == c }?.key ?: MOVIES_PKG }
    else if (source != null && ownApp(source)) Stremio.saved(filesDir, source)?.addon?.let { STREMIO + it.id } else when (addonsVod(vodKind(type))) {
    VodPlace.TILES -> if (type == "series") SERIES_PKG else MOVIES_PKG
    VodPlace.APPS -> source?.let { Stremio.saved(filesDir, it)?.addon }?.let { STREMIO + it.id }
    VodPlace.OFF -> null
}

// ---------- Continue Playing ----------

/** The films and episodes begun, as Continue Playing cards (with the time they were left, for its order). Off the UI thread. */
internal fun MainActivity.titleProgressCards(hidden: Set<String>): List<Pair<Long, Prog>> =
    // One card a series: its newest episode, begun or waiting next.
    VodProgress.all(vodDir()).filter { hideKey(VOD_PKG, it.name) !in hidden }.distinctBy { it.metaId }.take(10).map { e ->
        val left = ((e.duration - e.position) / 60_000).coerceAtLeast(1)
        val tile = if (addonsVod(vodKind(e.type)) == VodPlace.TILES) (if (e.type == "series") SERIES_PKG else MOVIES_PKG) else null
        val state = if (e.upNext) getString(R.string.title_up_next) else getString(R.string.time_left_min, left.toInt())
        // Wide art with its logo, as the Films and Series rows have: for an IMDb title its background, whatever was kept
        // (a poster, before), else what was kept.
        val wide = if (e.metaId.startsWith("tt")) Stremio.art(e.metaId, "background/medium") else e.poster
        val logo = if (e.metaId.startsWith("tt")) Stremio.art(e.metaId, "logo/medium") else null
        e.at to Prog(e.name, listOf(e.sub, state).filter { it.isNotBlank() }.joinToString(" · "),
            e.percent, wide, null, VOD_PKG, logo = logo, poster = e.poster, icon = tile,
            vod = TitleRef(e.type, e.metaId, e.name.substringBefore(" · "), null, wide, video = e.id, logo = logo))
    }
