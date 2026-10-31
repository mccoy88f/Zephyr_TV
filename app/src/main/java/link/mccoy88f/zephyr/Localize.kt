package link.mccoy88f.zephyr

// Films and series in the TV's language: what TMDB, with its key, has for Cinemeta's titles (by their IMDb ids).
// Functions of MainActivity; see Titles for the rows, TitlePages for the pages.

/**
 * Cinemeta's catalogs and pages are in English (IMDb's) and take no language. With a TMDB key, a title with an IMDb
 * id gets its name, art, genres and what it's about from TMDB in the TV's language (tmdbLang), and a series its
 * episodes' names, overviews and stills; what TMDB lacks stays Cinemeta's. Kept on disk a couple of weeks, per language.
 */
internal object Local {
    private const val FILE = "tmdb_titles.json"
    private const val AGE_MS = 14 * 24 * 3600_000L
    @Volatile private var lang: String? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, TmdbClient.Localized>()
    private val unknown: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val asking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The titles known in [language], read from disk the first time (or when the language changed). */
    @Synchronized
    fun use(dir: java.io.File, language: String) {
        if (lang == language) return
        lang = language
        known.clear()
        unknown.clear()
        val f = java.io.File(dir, FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > AGE_MS) return
        runCatching {
            val o = org.json.JSONObject(f.readText())
            if (o.optString("lang") != language) return
            val items = o.getJSONObject("items")
            for (id in items.keys()) {
                val e = items.optJSONObject(id) ?: run { unknown += id; null } ?: continue
                fun list(a: org.json.JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
                known[id] = TmdbClient.Localized(e.optLong("id"), e.optBoolean("tv"), e.optString("t"), e.optString("o"),
                    e.optString("p").ifEmpty { null }, e.optString("b").ifEmpty { null }, list(e.optJSONArray("g")), e.optString("y").ifEmpty { null },
                    e.optDouble("v", 0.0))
            }
        }
    }

    fun get(id: String) = known[id]
    fun asked(id: String) = known.containsKey(id) || id in unknown
    fun put(id: String, l: TmdbClient.Localized?) { if (l != null) known[id] = l else unknown += id }

    @Synchronized
    fun save(dir: java.io.File) {
        val items = org.json.JSONObject()
        for ((id, l) in known) items.put(id, org.json.JSONObject().put("id", l.tmdbId).put("tv", l.tv).put("t", l.title).put("o", l.overview)
            .put("p", l.poster.orEmpty()).put("b", l.backdrop.orEmpty()).put("g", org.json.JSONArray(l.genres)).put("y", l.year.orEmpty()).put("v", l.vote))
        for (id in unknown) items.put(id, org.json.JSONObject.NULL)
        val f = java.io.File(dir, FILE)
        val part = java.io.File(dir, "$FILE.part")
        part.writeText(org.json.JSONObject().put("lang", lang).put("items", items).toString())
        part.renameTo(f)
    }
}

/** TMDB's rating ("7.4"), for a title with no IMDb one; null when it has none. */
private fun TmdbClient.Localized.rating() = vote.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }

internal fun MainActivity.tmdbKey() = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }

/**
 * Complete with TMDB: catalog titles' names, art, what they're about and episodes in the TV's language. Off by
 * default with Nuvio's catalog (in it already). TMDB is still asked for each title's TMDB id without it: the
 * services and apps on its page need that.
 */
internal fun MainActivity.tmdbEnrich() = prefs.getBoolean(K.TMDB_ENRICH, !cinemetaUrl().startsWith(Stremio.NUVIO_BASE))

/** Whether [t] could be had in the TV's language and TMDB wasn't asked yet. */
internal fun MainActivity.wantsLocal(t: Stremio.Title): Boolean {
    if (tmdbKey() == null || !t.id.startsWith("tt")) return false
    Local.use(filesDir, tmdbLang())
    return !Local.asked(t.id)
}

/** [t] in the TV's language, as TMDB has it (its logo, written in English, left out); null when it isn't known (yet). */
internal fun MainActivity.localize(t: Stremio.Title): Stremio.Title? {
    if (tmdbKey() == null || !tmdbEnrich() || !t.id.startsWith("tt")) return null
    Local.use(filesDir, tmdbLang())
    val l = Local.get(t.id) ?: return null
    val same = l.title.equals(t.name, ignoreCase = true)
    return t.copy(name = l.title, poster = l.poster ?: t.poster, background = l.backdrop ?: t.background, logo = t.logo.takeIf { same },
        year = t.year ?: l.year, genres = l.genres.ifEmpty { t.genres }, rating = t.rating ?: l.rating(),
        description = l.overview.ifBlank { t.description.orEmpty() }.ifBlank { null }, tmdbId = l.tmdbId)
}

/** Asks TMDB for [todo] (marked as being asked) in [lang], a few at a time, and keeps what it says. Network: off the UI thread. */
internal fun MainActivity.askLocal(todo: List<String>, key: String, lang: String) {
    try {
        // A failure is asked again next time; an unknown title (null) is kept as unknown.
        parallel(todo, 6, 60) { id -> TmdbClient.localized(key, id, lang).also { Local.put(id, it) } ?: Unit }
    } finally {
        todo.forEach { Local.asking.remove(it) }
    }
    runCatching { Local.save(filesDir) }
}

/** Asks TMDB for [ids] in the TV's language, a few at a time; once in, the rows under [pkg] again if it's still in focus. */
internal fun MainActivity.localizeLater(ids: List<String>, pkg: String) {
    val key = tmdbKey() ?: return
    val lang = tmdbLang()
    val todo = ids.filter { Local.asking.add(it) }
    if (todo.isEmpty()) return
    netIo.execute {
        askLocal(todo, key, lang)
        ui.post { rowsAgain(pkg) }
    }
}

/**
 * [meta] in the TV's language as far as it's known already (asked of TMDB before): name, art, genres and what it's
 * about, not a series' episodes. No network: for a title's page at once, localizeMeta's whole after.
 */
internal fun MainActivity.localizeKnown(meta: Stremio.TitleMeta): Stremio.TitleMeta {
    if (tmdbKey() == null || !tmdbEnrich() || !meta.title.id.startsWith("tt")) return meta
    Local.use(filesDir, tmdbLang())
    val l = Local.get(meta.title.id) ?: return meta
    return Stremio.TitleMeta(localize(meta.title) ?: meta.title, l.overview.ifBlank { meta.description.orEmpty() }.ifBlank { null },
        l.genres.ifEmpty { meta.genres }, meta.rating ?: l.rating(), meta.runtime, meta.episodes,
        cast = meta.cast, director = meta.director)
}

/**
 * [meta] in the TV's language when TMDB has it: name, art, genres and what it's about, and a series' episodes
 * (their names, overviews and stills, a season at a time). Network: off the UI thread.
 */
internal fun MainActivity.localizeMeta(meta: Stremio.TitleMeta): Stremio.TitleMeta {
    val key = tmdbKey() ?: return meta
    val t = meta.title
    if (!t.id.startsWith("tt")) return meta
    val lang = tmdbLang()
    Local.use(filesDir, lang)
    val l = Local.get(t.id) ?: if (Local.asked(t.id)) return meta else
        runCatching { TmdbClient.localized(key, t.id, lang) }.getOrNull().also { Local.put(t.id, it); runCatching { Local.save(filesDir) } } ?: return meta
    if (!tmdbEnrich()) return meta // its TMDB id known, for its services; the rest as the catalog has it
    val title = localize(t) ?: t
    val episodes = if (!l.tv || meta.episodes.isEmpty()) meta.episodes else {
        val seasons = meta.episodes.map { it.season }.distinct().take(40)
        val local = parallel(seasons, 4, 20) { n -> n to runCatching { TmdbClient.seasonEpisodes(key, l.tmdbId, n, lang) }.getOrDefault(emptyMap()) }.toMap()
        // Episodes TMDB has nothing about in the TV's language, nor the catalog: what TMDB has in English, for those seasons.
        val bare = meta.episodes.filter { e -> local[e.season]?.get(e.episode)?.overview.isNullOrBlank() && e.overview.isNullOrBlank() }
            .map { it.season }.distinct().takeIf { !lang.startsWith("en") }.orEmpty()
        val english = parallel(bare, 4, 20) { n -> n to runCatching { TmdbClient.seasonEpisodes(key, l.tmdbId, n, "en-US") }.getOrDefault(emptyMap()) }.toMap()
        meta.episodes.map { e ->
            val le = local[e.season]?.get(e.episode)
            val en = english[e.season]?.get(e.episode)?.overview.orEmpty()
            if (le == null) return@map if (e.overview.isNullOrBlank() && en.isNotBlank()) Stremio.Episode(e.id, e.season, e.episode, e.name, e.released, e.thumbnail, en) else e
            Stremio.Episode(e.id, e.season, e.episode, le.name.ifBlank { e.name }, e.released, le.still ?: e.thumbnail,
                le.overview.ifBlank { e.overview.orEmpty() }.ifBlank { en }.ifBlank { null })
        }
    }
    return Stremio.TitleMeta(title, l.overview.ifBlank { meta.description.orEmpty() }.ifBlank { null }, l.genres.ifEmpty { meta.genres },
        meta.rating ?: l.rating(), meta.runtime, episodes, cast = meta.cast, director = meta.director)
}
