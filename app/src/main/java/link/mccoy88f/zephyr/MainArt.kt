package link.mccoy88f.zephyr

// The main catalog's art on the cards from everywhere else (the other catalog sources' rows, the add-ons' rows, the
// search's results): a title looks the same wherever it shows, as its page has it. Functions of MainActivity; see
// Titles for the rows, Library for the search, Localize for TMDB's art in the TV's language.

/**
 * The main catalog's art (poster, background, logo) for titles other add-ons list, by "type|id". Cinemeta's
 * is an address made from the IMDb id, never kept; another main catalog's is asked for, title by title, and kept on
 * disk a couple of weeks for that catalog.
 */
internal object MainArt {
    private const val FILE = "main_art.json"
    private const val AGE_MS = 14 * 24 * 3600_000L
    @Volatile private var main: String? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, Triple<String?, String?, String?>>()
    private val unknown: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val asking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The art known for the main catalog [url], read from disk the first time (or when the main catalog changed). */
    @Synchronized
    fun use(dir: java.io.File, url: String) {
        if (main == url) return
        main = url
        known.clear()
        unknown.clear()
        val f = java.io.File(dir, FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > AGE_MS) return
        runCatching {
            val o = org.json.JSONObject(f.readText())
            if (o.optString("main") != url) return
            val items = o.getJSONObject("items")
            for (key in items.keys()) {
                val a = items.optJSONArray(key) ?: run { unknown += key; null } ?: continue
                known[key] = Triple(a.optString(0).ifEmpty { null }, a.optString(1).ifEmpty { null }, a.optString(2).ifEmpty { null })
            }
        }
    }

    fun get(key: String) = known[key]
    fun asked(key: String) = known.containsKey(key) || key in unknown
    fun put(key: String, art: Triple<String?, String?, String?>?) { if (art != null) known[key] = art else unknown += key }

    @Synchronized
    fun save(dir: java.io.File) {
        val items = org.json.JSONObject()
        for ((key, a) in known) items.put(key, org.json.JSONArray(listOf(a.first.orEmpty(), a.second.orEmpty(), a.third.orEmpty())))
        for (key in unknown) items.put(key, org.json.JSONObject.NULL)
        val f = java.io.File(dir, FILE)
        val part = java.io.File(dir, "$FILE.part")
        part.writeText(org.json.JSONObject().put("main", main).put("items", items).toString())
        part.renameTo(f)
    }
}

/** Whether the main catalog is Cinemeta (its address, or one of its mirrors'): its art is had from the IMDb id alone. */
private fun MainActivity.mainIsCinemeta() = cinemetaUrl().let { it == Stremio.CINEMETA || "cinemeta" in it }

/**
 * [t], from an add-on other than the main catalog, with the main catalog's art where it has some, and [t]'s own as
 * its fallback: the card shows its add-on's picture when the main catalog's won't load (Cinemeta's addresses are made,
 * not told: one may not be there). [missing] gets it when the main catalog wasn't asked about it yet. No network.
 */
internal fun MainActivity.withMainArt(t: Stremio.Title, missing: MutableList<Stremio.Title>): Stremio.Title {
    val own = Triple(t.background, t.logo, t.poster)
    if (mainIsCinemeta()) return if (!t.id.startsWith("tt")) t
        else t.copy(poster = Stremio.art(t.id, "poster/small"), background = Stremio.art(t.id, "background/medium"),
            logo = Stremio.art(t.id, "logo/medium"), fallback = own)
    MainArt.use(filesDir, cinemetaUrl())
    val key = "${t.type}|${t.id}"
    val art = MainArt.get(key) ?: return t.also { if (!MainArt.asked(key)) missing += it }
    return t.copy(poster = art.first ?: t.poster, background = art.second ?: t.background, logo = art.third ?: t.logo, fallback = own)
}

/** Asks the main catalog for [titles]' art in the background; once in, the rows under [pkg] again if it's still in focus. */
internal fun MainActivity.mainArtLater(titles: List<Stremio.Title>, pkg: String) {
    val todo = mainArtTodo(titles) ?: return
    netIo.execute { if (askMainArt(todo, 60)) ui.post { rowsAgainSoon(pkg) } }
}

/** Asks the main catalog for [titles]' art and waits for it, [seconds] at most: for the search's results. Off the UI thread. */
internal fun MainActivity.mainArtNow(titles: List<Stremio.Title>, seconds: Long) {
    mainArtTodo(titles)?.let { askMainArt(it, seconds) }
}

/**
 * Of [titles], those to ask the main catalog about (marked as being asked), or null for none. Those it can't have (not
 * its kind of id) are known to have none, without asking.
 */
private fun MainActivity.mainArtTodo(titles: List<Stremio.Title>): Pair<Stremio.Addon, List<Stremio.Title>>? {
    val addon = Stremio.saved(filesDir, cinemetaUrl())?.addon ?: return null
    val (todo, never) = titles.distinctBy { it.type + "|" + it.id }.filter { MainArt.asking.add("${it.type}|${it.id}") }
        .partition { addon.provides("meta", it.type, it.id) }
    never.forEach { MainArt.put("${it.type}|${it.id}", null); MainArt.asking.remove("${it.type}|${it.id}") }
    return if (todo.isEmpty()) null else addon to todo
}

/** Asks [todo]'s add-on for its titles' art, a few at a time, and keeps what it says; whether any came in. Network. */
private fun MainActivity.askMainArt(todo: Pair<Stremio.Addon, List<Stremio.Title>>, seconds: Long): Boolean {
    val (addon, titles) = todo
    val got = try {
        // A failure is asked again next time; a title the catalog doesn't know is kept as unknown.
        parallel(titles, 6, seconds) { t ->
            val key = "${t.type}|${t.id}"
            runCatching { Stremio.titleMeta(addon.base, t.type, t.id) }.getOrElse { return@parallel null }
                ?.title.let { m -> MainArt.put(key, m?.let { Triple(it.poster, it.background, it.logo) }) }
        }
    } finally {
        titles.forEach { MainArt.asking.remove("${it.type}|${it.id}") }
    }
    runCatching { MainArt.save(filesDir) }
    return got.isNotEmpty()
}
