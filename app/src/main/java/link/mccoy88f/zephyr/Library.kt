package link.mccoy88f.zephyr

// Finding films and series: the search (words, filters, or both) in Cinemeta and the add-ons' catalogs, with TMDB
// for names in the TV's language, and Spotlight's titles. Functions of MainActivity; see Titles for the rows.

/** The films and series the add-ons' searchable catalogs find for [query] (Cinemeta first), each once. Off the UI thread. */
internal fun MainActivity.findTitles(query: String): List<Pair<Stremio.Title, String>> {
    val asked = vodAddons().flatMap { a -> a.vod.filter { it.search }.map { c -> a to c } }
    if (asked.isEmpty()) return emptyList()
    return parallel(asked, 4, 15) { (a, c) -> Stremio.search(a, c, query).map { it to a.url } }.flatten().distinctBy { it.first.type + it.first.id }
}

/**
 * The films and series for a search of [query], each once: with a TMDB key, those TMDB finds first (it knows their
 * names in the TV's language: "Il padrino" finds The Godfather), as Cinemeta's titles by their IMDb ids (those without
 * one left out); then those the add-ons' searchable catalogs find (Cinemeta's first). In the TV's language, as the
 * Films and Series rows are. Off the UI thread.
 */
internal fun MainActivity.searchLibrary(query: String): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    val viaTmdb = if (key == null) emptyList() else {
        parallel(TmdbClient.search(key, query, tmdbLang()), 6, 15) { item ->
            val imdb = TmdbClient.imdbIdOf(key, item) ?: return@parallel null
            Stremio.Title(if (item.mediaType == "tv") "series" else "movie", imdb, item.title,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, TmdbClient.cardUrl(item.backdropPath), null,
                item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres,
                item.voteAverage.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }, item.overview, item.id) to cinemetaUrl()
        }
    }
    return localizeAll((viaTmdb + findTitles(query)).distinctBy { it.first.type + it.first.id })
}

/**
 * [all] in the TV's language, as the Films and Series rows are (TMDB asked about those it wasn't yet); those TMDB
 * doesn't have, from an add-on other than the main catalog, with the main catalog's art (asked for, a few seconds at
 * most). Off the UI thread.
 */
internal fun MainActivity.localizeAll(all: List<Pair<Stremio.Title, String>>): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = all.map { it.first }.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    val main = cinemetaUrl()
    val artless = ArrayList<Stremio.Title>()
    all.forEach { (t, source) -> if (source != main && localize(t) == null) withMainArt(t, artless) }
    if (artless.isNotEmpty()) mainArtNow(artless, 8)
    return all.map { (t, source) -> (localize(t) ?: if (source != main) withMainArt(t, ArrayList()) else t) to source }
}

/**
 * Films and series associated with [personName] (as director if [isDirector] else cast/actor), via TMDB
 * combined credits (with IMDb ids resolved) and Cinemeta/addon searches. In the TV's language. Off the UI thread.
 */
internal fun MainActivity.searchPersonTitles(personName: String, isDirector: Boolean, filter: SearchFilter): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    val viaTmdb = if (key == null) emptyList() else {
        val items = TmdbClient.personCredits(key, personName, tmdbLang(), isDirector)
        parallel(items.take(40), 6, 15) { item ->
            val imdb = TmdbClient.imdbIdOf(key, item) ?: return@parallel null
            Stremio.Title(if (item.mediaType == "tv") "series" else "movie", imdb, item.title,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, TmdbClient.cardUrl(item.backdropPath), null,
                item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres,
                item.voteAverage.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }, item.overview, item.id) to cinemetaUrl()
        }
    }
    val viaAddons = findTitles(personName)
    val combined = (viaTmdb + viaAddons).filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }
        .distinctBy { it.first.type + it.first.id }
    return localizeAll(combined)
}

/**
 * Search filters: films, series or both ([type] "movie", "series", "all"), a [year], a [genre] (Cinemeta's
 * names, English: "Sci-Fi"), [director] and [actor] names, each null for any.
 */
internal class SearchFilter(val type: String = "all", val year: String? = null, val genre: String? = null,
                            val adult: String = ADULT_SHOW, val director: String? = null, val actor: String? = null) {
    val any get() = type != "all" || year != null || genre != null || adult != ADULT_SHOW || director != null || actor != null

    companion object {
        // Adult content in the results (offered only while 18+ shows: see adultSearch): with the rest, left out, or alone.
        const val ADULT_SHOW = "show"
        const val ADULT_HIDE = "hide"
        const val ADULT_ONLY = "only"
    }

    /** Whether [t] passes, its year and genres as Cinemeta has them (a series' years "2008–2013" span), or [local] names. */
    fun accepts(t: Stremio.Title, local: (String) -> String = { it }): Boolean {
        if (type != "all" && collectorOf(t.type) != collectorOf(type)) return false
        if (year != null && !spans(t.year, year.toInt())) return false
        return genre == null || t.genres.any { it.equals(genre, ignoreCase = true) || it.equals(local(genre), ignoreCase = true) }
    }

    private fun spans(years: String?, y: Int): Boolean {
        val from = years?.take(4)?.toIntOrNull() ?: return false
        if (y == from) return true
        val rest = years.substringAfter('–', "").takeIf { '–' in years } ?: return false
        val to = rest.take(4).toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) // running: to this year
        return y in from..to
    }
}

/**
 * The films and series for [filter] without words, from the catalog sources (the main one first: see catalogSources):
 * in each, for each type, the catalog its manifest offers the year in (Cinemeta's "year", Nuvio's "by year"), else
 * the genre (Cinemeta's "top", Nuvio's "popular"), else its first; its first pages, more when a genre is then picked
 * out of a year's titles here. In the TV's language. Off the UI thread.
 */
internal fun MainActivity.browseLibrary(filter: SearchFilter): List<Pair<Stremio.Title, String>> {
    val kinds = if (filter.type == "all") listOf("movie", "series") else listOf(filter.type)
    val asks = catalogSources().mapNotNull { Stremio.saved(filesDir, it)?.addon }.flatMap { addon ->
        kinds.mapNotNull { kind ->
            val catalogs = addon.vod.filter { it.type == kind }
            fun offering(v: String) = catalogs.firstNotNullOfOrNull { c -> c.genres.firstOrNull { it.equals(v, ignoreCase = true) }?.let { c to it } }
            when {
                filter.year != null -> offering(filter.year)
                filter.genre != null -> offering(filter.genre)
                else -> catalogs.firstOrNull { it.listed }?.let { it to null }
            }?.let { (c, option) -> Triple(addon, c, option) }
        }
    }
    val pages = if (filter.year != null && filter.genre != null) 4 else 2
    // Each catalog page after page, skipping as many as it gave (Cinemeta's pages are 50, Nuvio's 20); catalogs at once.
    val found = parallel(asks, 6, 25) { (addon, c, option) ->
        val out = ArrayList<Pair<Stremio.Title, String>>()
        var read = 0
        for (page in 0 until pages) {
            val extra = listOfNotNull(option?.let { "genre=${Stremio.enc(it)}" }, if (page == 0) null else "skip=$read").joinToString("&")
            val got = runCatching {
                Stremio.titles(Stremio.get("${addon.base}/catalog/${Stremio.enc(c.type)}/${Stremio.enc(c.id)}" + (if (extra.isEmpty()) "" else "/$extra") + ".json"))
            }.getOrDefault(emptyList())
            if (got.isEmpty()) break
            read += got.size
            out += got.map { it to addon.url }
        }
        out
    }.flatten()
    // The genre by its name in English (Cinemeta's) or in the TV's language (Nuvio's titles have it so).
    return localizeAll(found.filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }.distinctBy { it.first.type + it.first.id })
}

/** The main catalog's genres, for the search filter (its films' and series' catalogs' options), as its manifest lists them. */
internal fun MainActivity.libraryGenres(): List<String> =
    // Its catalogs that filter by genre (one of them a genre known here): not those by year, nor Nuvio's by language.
    Stremio.saved(filesDir, cinemetaUrl())?.addon?.vod.orEmpty().filter { c -> c.genres.any { it in GENRES } }.flatMap { it.genres }
        .distinct().ifEmpty { GENRES.keys.toList() }

// ---------- Spotlight ----------

/**
 * Spotlight's titles from Cinemeta, which needs no key: its popular films, series or both ([type]: "movie", "tv",
 * "all", taken in turn), those with a background. With a TMDB key, in the TV's language as the Films and Series rows
 * are (see localize), with their TMDB ids for the services; their background stays Cinemeta's, the largest.
 * Empty when Cinemeta can't be asked. Network: off the UI thread.
 */
internal fun MainActivity.spotlightTitles(type: String): List<TmdbItem> {
    val url = cinemetaUrl()
    val addon = Stremio.saved(filesDir, url)?.addon
    val kinds = when (type) { "movie" -> listOf("movie"); "tv" -> listOf("series"); else -> listOf("movie", "series") }
    val lists = kinds.map { k ->
        val catalog = addon?.vod?.firstOrNull { it.type == k && it.listed }?.id ?: "top"
        runCatching { Stremio.titles(Stremio.get("${url.removeSuffix("/manifest.json")}/catalog/$k/${Stremio.enc(catalog)}.json")) }
            .onFailure { warn("Cinemeta for Spotlight failed ($it)") }.getOrDefault(emptyList())
    }
    val titles = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        .filter { it.background != null }.distinctBy { it.id }.take(40)
    val key = tmdbKey()
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = titles.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    return titles.map { t ->
        val local = localize(t)
        val l = if (key != null && t.id.startsWith("tt")) Local.get(t.id) else null
        val name = local?.name ?: t.name
        TmdbItem(l?.tmdbId ?: t.tmdbId ?: 0, name, l?.overview?.ifBlank { null } ?: t.description.orEmpty(),
            t.background?.replace("/background/medium/", "/background/large/"), t.rating?.toDoubleOrNull() ?: l?.vote ?: 0.0,
            if (t.type == "series") "tv" else "movie", local?.genres ?: t.genres, t.year, imdbId = t.id)
            .also { if (it.id > 0) TmdbClient.knowImdbId(it, t.id) }
    }
}
