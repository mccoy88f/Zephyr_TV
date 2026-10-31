package link.mccoy88f.zephyr

import android.content.SharedPreferences
import android.widget.Toast
import java.io.File
import org.json.JSONObject
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU

// Nuvio's plugins in Zephyr TV: the repositories added, which of their plugins are on, which repositories are 18+,
// and the plugins as stream sources next to the Stremio add-ons (for the title pages and the player alike). The
// plugins themselves, and the engine they run in, are in NuvioPlugins. Settings → Add-ons and plugins → Nuvio.

/** A stream source that's a plugin, in the lists of sources the add-ons are in: "nuvio:<manifest>#<plugin id>". */
internal const val PLUGIN = "nuvio:"

/** The repositories added, as manifest addresses, in the order added. */
internal fun nuvioRepoUrls(p: SharedPreferences) = p.getString(K.NUVIO_REPOS, "")!!.lines().filter { it.isNotBlank() }

/** The repositories marked 18+: their plugins only while 18+ shows (see adultShows). */
internal fun nuvioAdultRepos(p: SharedPreferences) = p.getStringSet(K.NUVIO_ADULT, emptySet())!!

private fun pluginKey(repo: String, s: Nuvio.Scraper) = "$repo#${s.id}"

/** Whether [s] of [repo] is on: as its manifest says, unless the user switched it. */
internal fun pluginOn(p: SharedPreferences, repo: String, s: Nuvio.Scraper): Boolean {
    val key = pluginKey(repo, s)
    return key in p.getStringSet(K.NUVIO_ON, emptySet())!! || (s.enabled && key !in p.getStringSet(K.NUVIO_OFF, emptySet())!!)
}

/** [repo]'s [plugins] in the order the user put them; those never moved (new ones too) after, as the manifest has them. */
internal fun orderedPlugins(p: SharedPreferences, repo: String, plugins: List<Nuvio.Scraper>): List<Nuvio.Scraper> {
    val order = p.getString(K.NUVIO_ORDER, "")!!.lines().filter { it.startsWith("$repo#") }.map { it.removePrefix("$repo#") }
    return plugins.sortedBy { s -> order.indexOf(s.id).let { if (it < 0) Int.MAX_VALUE else it } }
}

/** Saves [plugins] as [repo]'s order ([plugins] empty: forgets it, the repository was removed). */
private fun savePluginOrder(p: SharedPreferences, repo: String, plugins: List<Nuvio.Scraper>) {
    val others = p.getString(K.NUVIO_ORDER, "")!!.lines().filter { it.isNotBlank() && !it.startsWith("$repo#") }
    p.edit().putString(K.NUVIO_ORDER, (others + plugins.map { pluginKey(repo, it) }).joinToString("\n")).apply()
}

/** The values of [s]'s options the user chose, by key; null when none was. */
internal fun pluginOptions(p: SharedPreferences, repo: String, s: Nuvio.Scraper): JSONObject? =
    runCatching { JSONObject(p.getString(K.NUVIO_OPTIONS, null) ?: "{}") }.getOrNull()?.optJSONObject(pluginKey(repo, s))

/**
 * Sets [s]'s option [key] to [value] (null: back to the plugin's own). The first one chosen saves the others' defaults
 * with it ([fields]): a plugin that has any option set then has them all.
 */
private fun setPluginOption(p: SharedPreferences, repo: String, s: Nuvio.Scraper, fields: List<Nuvio.Field>, key: String, value: Any?) {
    val all = runCatching { JSONObject(p.getString(K.NUVIO_OPTIONS, null) ?: "{}") }.getOrDefault(JSONObject())
    val mine = all.optJSONObject(pluginKey(repo, s)) ?: JSONObject().apply { fields.forEach { f -> f.default?.let { put(f.key, it) } } }
    if (value == null) mine.remove(key) else mine.put(key, value)
    p.edit().putString(K.NUVIO_OPTIONS, all.put(pluginKey(repo, s), mine).toString()).apply()
}

/** Forgets the options of [repo]'s plugins: the repository was removed. */
private fun forgetPluginOptions(p: SharedPreferences, repo: String) {
    val all = runCatching { JSONObject(p.getString(K.NUVIO_OPTIONS, null) ?: "{}") }.getOrNull() ?: return
    all.keys().asSequence().toList().filter { it.startsWith("$repo#") }.forEach { all.remove(it) }
    p.edit().putString(K.NUVIO_OPTIONS, all.toString()).apply()
}

/**
 * The plugins on that can find streams for an item of [type] with this [id], as stream sources (source, name): the
 * 18+ repositories' only when [adult]. For the title pages, and the player for a channel.
 */
internal fun pluginSources(p: SharedPreferences, dir: File, type: String, id: String, adult: Boolean): List<Pair<String, String>> {
    val hidden = (if (adult) emptySet() else nuvioAdultRepos(p)) + p.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!!
    val lang = streamLanguage(p)
    return nuvioRepoUrls(p).filter { it !in hidden }.flatMap { repo ->
        orderedPlugins(p, repo, Nuvio.saved(dir, repo)?.scrapers.orEmpty()).filter { pluginOn(p, repo, it) && Nuvio.provides(it, type, id) }
            // A plugin for other languages only (its manifest's contentLanguage) isn't asked, with the language on.
            .filter { s -> lang == null || s.languages.isEmpty() || s.languages.any { it.lowercase().startsWith(lang) } }
            .map { "$PLUGIN$repo#${it.id}" to it.name }
    }
}

/** The stream sources of the 18+ repositories' plugins: the player keeps no history of what they play. */
internal fun adultPluginSources(p: SharedPreferences, dir: File) = nuvioAdultRepos(p).flatMap { repo ->
    Nuvio.saved(dir, repo)?.scrapers.orEmpty().map { "$PLUGIN$repo#${it.id}" }
}

/**
 * The streams of a film or episode from the source [base] (named [name]): a Stremio add-on asked, or a Nuvio plugin
 * run. Throws when an add-on can't be asked; a plugin that fails finds none. Network: off the UI thread.
 */
internal fun sourceStreams(p: SharedPreferences, dir: File, base: String, name: String, type: String, id: String): List<Stremio.Stream> {
    val lang = streamLanguage(p)
    var declared = emptySet<String>() // a plugin's languages (its manifest's): a stream of it that says none is in those
    val all = if (!base.startsWith(PLUGIN)) Stremio.titleStreams(base, name, type, id) else {
        val repo = base.removePrefix(PLUGIN).substringBeforeLast('#')
        val s = Nuvio.saved(dir, repo)?.scrapers?.firstOrNull { it.id == base.substringAfterLast('#') } ?: return emptyList()
        declared = s.languages.map { it.lowercase().take(2) }.toSet()
        Nuvio.streams(dir, repo, s, name, type, id, p.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() },
            pluginOptions(p, repo, s))
    }
    if (lang == null) return all
    // Each marked with its language against the TV's: the player and the streams' page put ours first, then those
    // saying none (unless they're hidden), then the others, as a last choice.
    val hideUnknown = p.getBoolean(K.HIDE_UNKNOWN_LANG, false)
    return all.map { s -> Stremio.Stream(s.url, s.title, s.headers, s.group, s.source, languageRank(s.title, lang, declared), s.clearkey, s.app) }
        .filter { !(hideUnknown && it.langRank == Stremio.LANG_UNKNOWN) }
}

/** [sourceStreams] through StreamCache: those found ahead by the launcher, or a search going on, waited for. */
internal fun cachedStreams(p: SharedPreferences, dir: File, cache: File, base: String, name: String, type: String, id: String) =
    StreamCache.get(cache, base, type, id) { sourceStreams(p, dir, base, name, type, id) }

/**
 * The language streams are preferred in ("it", from the TMDB language "it-IT"), or null when Content → Streams in
 * the TV's language first is off: then no language comes first.
 */
internal fun streamLanguage(p: SharedPreferences): String? {
    if (!p.getBoolean(K.STREAM_LANG_ONLY, true)) return null
    return (p.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()).substringBefore('-').lowercase()
}

/**
 * The words and flags a stream's name says its language with, by language: those add-ons and plugins write ("ITA",
 * "🇮🇹", "Italian", "SUB-ITA"). Words only whole, and none that's an everyday word too.
 */
private val LANGUAGE_MARKS = mapOf(
    "it" to listOf("ita", "italian", "italiano", "\uD83C\uDDEE\uD83C\uDDF9"),
    "en" to listOf("eng", "english", "\uD83C\uDDEC\uD83C\uDDE7", "\uD83C\uDDFA\uD83C\uDDF8"),
    "es" to listOf("spa", "esp", "spanish", "español", "castellano", "\uD83C\uDDEA\uD83C\uDDF8", "\uD83C\uDDF2\uD83C\uDDFD"),
    "fr" to listOf("fre", "french", "français", "vff", "vostfr", "\uD83C\uDDEB\uD83C\uDDF7"),
    "de" to listOf("ger", "deu", "german", "deutsch", "\uD83C\uDDE9\uD83C\uDDEA"),
    "pt" to listOf("portuguese", "português", "\uD83C\uDDF5\uD83C\uDDF9", "\uD83C\uDDE7\uD83C\uDDF7"),
    "hi" to listOf("hin", "hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
    "ta" to listOf("tamil"), "te" to listOf("telugu"), "ml" to listOf("malayalam"), "kn" to listOf("kannada"),
    "ja" to listOf("jpn", "japanese", "\uD83C\uDDEF\uD83C\uDDF5"), "ko" to listOf("korean", "\uD83C\uDDF0\uD83C\uDDF7"),
    "ar" to listOf("arabic"), "ru" to listOf("rus", "russian", "\uD83C\uDDF7\uD83C\uDDFA"),
)

/**
 * The picture's height a stream's name says ("2160p", "4K", "FHD", "720"), for putting the best first; [UNKNOWN_QUALITY]
 * when it says none (an "Auto" HLS one, which picks as it plays, among them).
 */
internal fun streamQuality(title: String): Int {
    val t = title.lowercase()
    Regex("""(?<![0-9])(4320|2160|1440|1080|720|576|540|480|360|240)\s*[pi]?(?![0-9])""").findAll(t)
        .map { it.groupValues[1].toInt() }.maxOrNull()?.let { return it }
    return when {
        Regex("""(?<![\p{L}0-9])(8k)(?![\p{L}])""").containsMatchIn(t) -> 4320
        Regex("""(?<![\p{L}0-9])(4k|uhd)(?![\p{L}])""").containsMatchIn(t) -> 2160
        Regex("""(?<![\p{L}0-9])(qhd|2k)(?![\p{L}])""").containsMatchIn(t) -> 1440
        Regex("""(?<![\p{L}])(fhd|full[\s_.-]*hd)(?![\p{L}])""").containsMatchIn(t) -> 1080
        Regex("""(?<![\p{L}])hd(?![\p{L}])""").containsMatchIn(t) -> 720
        Regex("""(?<![\p{L}])sd(?![\p{L}])""").containsMatchIn(t) -> 480
        else -> UNKNOWN_QUALITY
    }
}

/** A stream that says no quality ranks as this: after the full HD ones, before the plain HD ones. */
internal const val UNKNOWN_QUALITY = 900

/**
 * A stream's group in the stream lists (the player's panel, the manual choice): its height, the highest first; 0, the
 * last group, when it says no quality.
 */
internal fun qualityGroup(title: String) = streamQuality(title).let { if (it == UNKNOWN_QUALITY) 0 else it }

/** The heading over a [qualityGroup]'s streams. */
internal fun android.content.Context.qualityHeading(group: Int) =
    if (group == 0) getString(R.string.player_quality_unknown) else if (group >= 2160) "${group}p · 4K" else "${group}p"

/** The qualities Content → Preferred quality offers, by height; 0 is the highest there is. */
internal val QUALITIES = listOf(0, 2160, 1440, 1080, 720, 480)

/** The quality streams are preferred in (see QUALITIES): 0, the highest, by default. */
internal fun preferredQuality(p: SharedPreferences) = p.getInt(K.STREAM_QUALITY, 0)

/**
 * Whether a film starts with the first working stream in the preferred quality (Content → Quick start): never for the
 * highest, which only all the sources' answers can tell.
 */
internal fun quickStart(p: SharedPreferences) = p.getBoolean(K.FAST_START, true) && preferredQuality(p) != 0

/**
 * Streams in the order of [preferred]: those up to it first, the best of them first; then those above it, the
 * nearest first (a 4K one stays, after the 1080p ones, for a TV set to 1080p). With 0, simply the best first.
 */
internal fun qualityOrder(preferred: Int): Comparator<Stremio.Stream> = compareBy<Stremio.Stream> { s ->
    if (preferred == 0 || streamQuality(s.title) <= preferred) 0 else 1
}.thenBy { s -> streamQuality(s.title).let { q -> if (preferred == 0 || q <= preferred) -q else q } }

/** The languages [title] says a stream is in, as LANGUAGE_MARKS knows them; none when it doesn't say. */
internal fun streamLanguages(title: String): Set<String> {
    val t = title.lowercase()
    return LANGUAGE_MARKS.filterValues { marks ->
        marks.any { m -> if (m.first().isLetter()) Regex("(?<![\\p{L}])${Regex.escape(m)}(?![\\p{L}])").containsMatchIn(t) else m in title }
    }.keys
}

/**
 * A stream named [title] against [lang] (see Stremio.Stream.langRank): ours when it says so (or "multi"), or, saying
 * none, its plugin's [declared] languages have it; unknown when neither says any; another language otherwise.
 */
internal fun languageRank(title: String, lang: String, declared: Set<String> = emptySet()): Int {
    if (Regex("(?<![\\p{L}])multi").containsMatchIn(title.lowercase())) return Stremio.LANG_OURS
    val said = streamLanguages(title).ifEmpty { declared }
    return when {
        said.isEmpty() -> Stremio.LANG_UNKNOWN
        lang in said -> Stremio.LANG_OURS
        else -> Stremio.LANG_OTHER
    }
}

/** A day: a repository is read again (new plugins, new versions of them) once its copy is this old. */
private const val REPO_AGE_MS = 24 * 3600_000L

/** The repositories read a day ago or more, read again in the background. */
internal fun MainActivity.refreshPlugins() {
    val due = nuvioRepoUrls(prefs).filter { Nuvio.age(filesDir, it) > REPO_AGE_MS }
    if (due.isNotEmpty()) netIo.execute { due.forEach { Nuvio.update(filesDir, it) } }
}

// ---------- settings ----------

/**
 * Extra features: where their content shows (Virtual apps and integrations), then Stremio's add-ons (their section),
 * Nuvio's plugins and the extensions, each a page of its own.
 */
internal fun MainActivity.addonsAndPlugins(focus: Int = 0) {
    val repos = nuvioRepoUrls(prefs)
    showPage(getString(R.string.settings_section_addons_plugins), listOf(
        page(getString(R.string.virtual_apps_integrations), SUBMENU) { integrationsPage() },
        page(getString(R.string.addons_stremio), "${addonUrls().size}") { renderSettings(MainActivity.ADDON_ADD_ROW) },
        page(getString(R.string.nuvio_plugins), "${repos.size}") { nuvioPage() },
        page(getString(R.string.ext_title), "${extensions(prefs, filesDir).size}") { extensionsPage() },
        // Their requests, and the videos, from Cloudflare's addresses instead of the device's (see Privacy).
        page(getString(R.string.warp_title), getString(if (vpnOn()) R.string.warp_vpn_on else R.string.state_off)) { privacyPage { addonsAndPlugins(4) } },
    ), focus) { renderSettingsHome(HOME_SECTIONS.indexOf(MainActivity.ADDONS_SECTION)) }
}

/** Virtual apps and integrations: where the add-ons', plugins' and extensions' content shows (see placementRows). */
private fun MainActivity.integrationsPage(focus: Int = 0) {
    showPage(getString(R.string.virtual_apps_integrations), placementRows { integrationsPage(it) }, focus) { addonsAndPlugins(0) }
}

/** Nuvio's page: what plugins are (and that they run code from elsewhere), each repository, and Add a repository. */
internal fun MainActivity.nuvioPage(focus: Int = 0) {
    val repos = nuvioRepoUrls(prefs)
    val intro = label(getString(R.string.nuvio_intro), 13f).apply {
        alpha = 0.7f
        maxLines = 8
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(getString(R.string.nuvio_plugins), repos.map { url ->
        val r = Nuvio.saved(filesDir, url)
        val on = r?.scrapers?.count { pluginOn(prefs, url, it) } ?: 0
        val value = when {
            url in prefs.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!! -> getString(R.string.state_off)
            r == null -> SUBMENU
            else -> getString(R.string.nuvio_plugins_on, on, r.scrapers.size)
        }
        page(r?.name ?: url, value) { repoPage(url) }
    } + page(getString(R.string.nuvio_add), SUBMENU) { nuvioFromPhone() }, focus, top = intro) { addonsAndPlugins(2) }
}

/**
 * A repository's page: on or off (off, its plugins aren't asked), each plugin with its switch (and its options, for a
 * plugin that has some), their order, 18+, up or down the list (its plugins' Watch on rows come in this order),
 * Update, Remove.
 */
private fun MainActivity.repoPage(url: String, focus: Int = 0) {
    val r = Nuvio.saved(filesDir, url)
    val scrapers = orderedPlugins(prefs, url, r?.scrapers.orEmpty())
    // Each plugin's row, then its options' for one that has some, then their order (two or more): the rows after
    // them come after [plugins].
    val rowOf = scrapers.runningFold(1) { row, s -> row + if (s.options) 2 else 1 }
    val sortable = scrapers.size > 1
    val plugins = rowOf.last() - 1 + if (sortable) 1 else 0
    val at = nuvioRepoUrls(prefs).indexOf(url)
    val about = listOfNotNull(r?.description?.takeIf { it.isNotBlank() },
        listOfNotNull(r?.version?.takeIf { it.isNotBlank() }?.let { "v$it" }, r?.author?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null })
        .joinToString("\n")
    val top = label(about, 13f).apply {
        alpha = 0.7f
        maxLines = 4
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    fun switch(set: String, key: String, add: Boolean) {
        val now = prefs.getStringSet(set, emptySet())!!.toMutableSet()
        if (add) now += key else now -= key
        prefs.edit().putStringSet(set, now).apply()
    }
    val adult = url in nuvioAdultRepos(prefs)
    val repoOn = url !in prefs.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!!
    val urls = nuvioRepoUrls(prefs)
    fun move(by: Int) {
        val list = urls.toMutableList()
        list.add(at + by, list.removeAt(at))
        prefs.edit().putString(K.NUVIO_REPOS, list.joinToString("\n")).apply()
    }
    val moves = listOfNotNull(
        if (at > 0) page(getString(R.string.addon_move_up)) { move(-1); repoPage(url, plugins + 2) } else null,
        if (at < urls.size - 1) page(getString(R.string.addon_move_down)) { move(+1); repoPage(url, plugins + 2) } else null,
    )
    showPage(r?.name ?: url, listOf(
        page(getString(R.string.addon_on), toggle(repoOn)) {
            switch(K.NUVIO_REPOS_OFF, url, repoOn)
            repoPage(url, 0)
        },
    ) + scrapers.flatMapIndexed { i, s ->
        listOf(page(s.name, toggle(pluginOn(prefs, url, s))) {
            val key = pluginKey(url, s)
            val on = !pluginOn(prefs, url, s)
            switch(K.NUVIO_ON, key, on && !s.enabled)
            switch(K.NUVIO_OFF, key, !on && s.enabled)
            repoPage(url, rowOf[i])
        }) + if (s.options) listOf(page(getString(R.string.nuvio_options, s.name), SUBMENU) { optionsPage(url, s) { repoPage(url, rowOf[i] + 1) } }) else emptyList()
    } + listOfNotNull(
        if (sortable) page(getString(R.string.nuvio_order), SUBMENU) { orderPage(url) { repoPage(url, plugins) } } else null,
    ) + listOf(
        page(getString(R.string.addon_adult), toggle(adult)) {
            switch(K.NUVIO_ADULT, url, !adult)
            repoPage(url, plugins + 1)
        },
    ) + moves + listOf(
        page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val again = Nuvio.update(filesDir, url)
                ui.post {
                    Toast.makeText(this, if (again == null) getString(R.string.toast_addon_failed)
                        else getString(R.string.nuvio_updated, again.name, again.scrapers.size), Toast.LENGTH_LONG).show()
                    if (pageShowing(again?.name ?: "")) repoPage(url, plugins + 2 + moves.size)
                }
            }
        },
        page(getString(R.string.addon_remove)) {
            prefs.edit().putString(K.NUVIO_REPOS, (nuvioRepoUrls(prefs) - url).joinToString("\n")).apply()
            switch(K.NUVIO_ADULT, url, false)
            switch(K.NUVIO_REPOS_OFF, url, false)
            forgetPluginOptions(prefs, url)
            savePluginOrder(prefs, url, emptyList())
            Nuvio.forget(filesDir, url)
            nuvioPage()
        },
    ), focus, top = if (about.isEmpty()) null else top) { nuvioPage(at.coerceAtLeast(0)) }
}

/**
 * The order of a repository's plugins (their Watch on rows come in it): OK on one picks it up ([held]), OK on another
 * puts it in that one's place, OK on it again puts it back.
 */
private fun MainActivity.orderPage(url: String, held: String? = null, focus: Int = 0, back: () -> Unit) {
    val plugins = orderedPlugins(prefs, url, Nuvio.saved(filesDir, url)?.scrapers.orEmpty())
    val moving = plugins.firstOrNull { it.id == held }
    val intro = label(if (moving == null) getString(R.string.nuvio_order_intro) else getString(R.string.nuvio_order_moving, moving.name), 13f).apply {
        alpha = 0.7f
        maxLines = 4
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(getString(R.string.nuvio_order), plugins.mapIndexed { i, s ->
        page(s.name, if (s.id == held) "↕" else "") {
            when {
                moving == null -> orderPage(url, s.id, i, back)
                s.id == held -> orderPage(url, null, i, back)
                else -> {
                    savePluginOrder(prefs, url, plugins.toMutableList().apply { remove(moving); add(i, moving) })
                    orderPage(url, null, i, back)
                }
            }
        }
    }, focus, top = intro) { if (moving != null) orderPage(url, null, plugins.indexOf(moving), back) else back() }
}

/**
 * A plugin's options, as it lists them: a switch for yes or no, a list to pick from, a text (typed, or pasted on a
 * phone: a cookie is long). Read by running the plugin, so the page waits for it the first time.
 */
private fun MainActivity.optionsPage(url: String, s: Nuvio.Scraper, focus: Int = 0, back: () -> Unit) {
    val title = s.name
    netIo.execute {
        val fields = Nuvio.fields(filesDir, url, s, pluginOptions(prefs, url, s)).filter { it.type in setOf("toggle", "select", "text", "info") }
        ui.post { if (pageShowing(title)) optionsRows(url, s, fields, focus, back) }
    }
    showPage(title, listOf(page(getString(R.string.player_loading)) {}), back = back)
}

private fun MainActivity.optionsRows(url: String, s: Nuvio.Scraper, all: List<Nuvio.Field>, focus: Int, back: () -> Unit) {
    if (all.none { it.type in setOf("toggle", "select", "text") }) return showPage(s.name, listOf(page(getString(R.string.nuvio_options_none)) { back() }), back = back)
    val fields = all.filter { it.type != "info" }
    formPage(s.name, s.description, all, focus, { f -> pluginOptions(prefs, url, s)?.opt(f.key) ?: f.default },
        { f, v -> setPluginOption(prefs, url, s, fields, f.key, v) }, { i -> optionsRows(url, s, all, i, back) }, back)
}

/** Adds the repository at [input] once its manifest answers with plugins, telling how it went. */
internal fun MainActivity.addRepo(input: String, adult: Boolean) {
    val url = Nuvio.manifestUrl(input) ?: return Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val r = Nuvio.update(filesDir, url)
        ui.post {
            when {
                r == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
                r.scrapers.isEmpty() -> {
                    Nuvio.forget(filesDir, url)
                    Toast.makeText(this, getString(R.string.nuvio_empty), Toast.LENGTH_LONG).show()
                }
                else -> {
                    prefs.edit().putString(K.NUVIO_REPOS, (nuvioRepoUrls(prefs) + url).distinct().joinToString("\n")).apply()
                    if (adult) prefs.edit().putStringSet(K.NUVIO_ADULT, nuvioAdultRepos(prefs) + url).apply()
                    Toast.makeText(this, getString(R.string.nuvio_added, r.name, r.scrapers.size), Toast.LENGTH_LONG).show()
                    if (settingsScrim.visibility == android.view.View.VISIBLE) nuvioPage(nuvioRepoUrls(prefs).indexOf(url))
                }
            }
        }
    }
}

/** A repository's address typed with the remote, and whether it's 18+. */
private fun MainActivity.typeRepo(initial: String = "", adult: Boolean = false) {
    textPage(getString(R.string.nuvio_add), initial, getString(R.string.nuvio_hint), getString(R.string.nuvio_add_intro), back = { nuvioFromPhone() }) { typed ->
        listOf(
            page(getString(R.string.addon_adult), toggle(adult)) { typeRepo(typed(), !adult) },
            page(getString(R.string.dialog_save)) { addRepo(typed(), adult) },
        )
    }
}

/** Add a repository: a QR code to paste its address on a phone (with the 18+ box), or typed with the remote. */
private fun MainActivity.nuvioFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.addon(phoneTexts(R.string.nuvio_phone_title, R.string.nuvio_add_intro), getString(R.string.nuvio_hint), getString(R.string.addon_adult))
    showPhoneLink(getString(R.string.nuvio_add), PhoneTask(page, 4096) { file ->
        val lines = file.readText().lines()
        val url = Nuvio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
        val adult = lines.getOrNull(1)?.trim() == "adult"
        ui.post {
            status.text = getString(R.string.addon_checking)
            addRepo(url, adult)
        }
        200 to getString(R.string.phone_addon_saved)
    }, status, listOf(page(getString(R.string.addon_type)) { typeRepo() })) { nuvioPage(nuvioRepoUrls(prefs).size) }
}
