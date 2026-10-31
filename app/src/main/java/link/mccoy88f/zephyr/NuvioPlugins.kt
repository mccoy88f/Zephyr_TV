package link.mccoy88f.zephyr

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nuvio's plugins: a repository is a manifest.json listing its scrapers, each a JavaScript file with one function,
 * getStreams(tmdbId, mediaType, season, episode), that finds a film's or episode's streams by itself (asking the
 * sites with fetch) and hands them back as Stremio does. Unlike an add-on there's no server to ask: the script runs
 * here, in the engine extensions run in too (Js), with its options (see fields) and a localStorage of its own (kept on
 * disk, see storageFile).
 */
internal object Nuvio {
    /**
     * One of a repository's plugins, as its manifest describes it. [types]: "movie", "series", "tv" (Nuvio's word for
     * series, a TV channel's too), "anime". [options]: it has options of its own (see fields).
     */
    internal class Scraper(val id: String, val name: String, val description: String, val version: String, val file: String,
                           val types: List<String>, val prefixes: List<String>?, val enabled: Boolean, val languages: List<String>,
                           val options: Boolean = false)

    /**
     * One of a plugin's options, as its onSettings() describes it: [type] "toggle" (yes or no), "select" (one of
     * [choices], label to value), "text" ([secret]: a password, a cookie), "header" (a title) or "info" (a note), these
     * two with no value.
     */
    internal class Field(val type: String, val key: String, val label: String, val description: String, val placeholder: String,
                         val secret: Boolean, val choices: List<Pair<String, Any>>, val default: Any?,
                         val item: List<Field> = emptyList(), val itemLabel: String = "") // a "list": one item's fields, the key naming it

    /** A repository: its manifest's address, name, version and author, and its plugins. */
    internal class Repo(val url: String, val name: String, val version: String, val author: String, val description: String,
                        val scrapers: List<Scraper>)

    private const val RUN_MS = 25_000L // a plugin finding its streams, at most

    /** Where plugins run: their own process (ScriptProcess.plugins, set by the activities); here when unset (the tests). */
    @Volatile var process: ScriptProcess? = null

    /** Runs a plugin's [script] where plugins run (see process), then [call]: what it answers, as a list. */
    private fun exec(script: String, call: String, globals: String, stored: (String) -> Unit): JSONArray? {
        val p = process ?: return run(script, call, globals, stored = stored)
        val answer = p.run(script, "((await $call) || [])", globals, RUN_MS, wantsStore = true)
        answer.store?.let(stored)
        return answer.out?.let { JSONArray(it) }
    }

    /** Runs a plugin's [script] in the engine here (see Js.run), then [call]: what it answers, as a list (empty for none). */
    internal fun run(script: String, call: String, globals: String = "", timeoutMs: Long = RUN_MS,
                     fetch: (JSONObject) -> Js.Fetched = Js::httpFetch, stored: ((String) -> Unit)? = null): JSONArray? =
        Js.run(script, "((await $call) || [])", globals, timeoutMs, fetch, stored)?.let { JSONArray(it) }

    @Volatile private var repos: Map<String, Repo> = emptyMap()

    /** Nuvio's plugins are given a title's TMDB id: IMDb's ("tt…") turned into it once, then kept. */
    private val tmdbIds = ConcurrentHashMap<String, String>()

    /**
     * The TMDB id of the film or series with IMDb's [imdb]: Cinemeta's page has it, without a key; else TMDB's own
     * answer, with [key]. Null when neither knows. Network: off the UI thread.
     */
    fun tmdbIdOf(type: String, imdb: String, key: String?): String? {
        tmdbIds[imdb]?.let { return it }
        val kind = if (type == "movie") "movie" else "series"
        val found = runCatching { Http.json("https://v3-cinemeta.strem.io/meta/$kind/$imdb.json").optJSONObject("meta")?.optLong("moviedb_id") }
            .getOrNull()?.takeIf { it > 0 }?.toString()
            ?: key?.let { k ->
                runCatching {
                    val answer = JSONObject(TmdbClient.get(k, "/find/$imdb?external_source=imdb_id"))
                    val list = answer.optJSONArray(if (type == "movie") "movie_results" else "tv_results")
                    list?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }?.toString()
                }.getOrNull()
            }
        return found?.also { tmdbIds[imdb] = it }
    }

    /** A repository's manifest address from what was typed or sent: an address ending in .json, else its folder's manifest.json. */
    fun manifestUrl(input: String): String? {
        val url = input.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty().removeSuffix("/")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        if (url.substringAfter("://").substringBefore('/').isEmpty()) return null
        return if (url.substringBefore('?').endsWith(".json")) url else "$url/manifest.json"
    }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?')

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun folder(dir: File, url: String) = File(dir, "nuvio/${key(url)}")
    private fun scriptFile(dir: File, url: String, s: Scraper) = File(folder(dir, url), s.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".js")

    /** [s]'s localStorage, what it keeps from one run to the next (a token that expires): gone with its repository. */
    private fun storageFile(dir: File, url: String, s: Scraper) = File(folder(dir, url), s.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".storage.json")

    /**
     * What every run of [s] is given: its id, its [options] (SCRAPER_SETTINGS) and its localStorage as last kept, with
     * [tmdbKey] for the plugins that ask TMDB.
     */
    private fun globals(dir: File, url: String, s: Scraper, options: JSONObject?, tmdbKey: String? = null): String {
        val stored = runCatching { JSONObject(storageFile(dir, url, s).readText()) }.getOrNull() ?: JSONObject()
        return "globalThis.SCRAPER_ID = ${JSONObject.quote(s.id)}; globalThis.TMDB_API_KEY = ${tmdbKey?.let { JSONObject.quote(it) } ?: "undefined"}; " +
            "globalThis.SCRAPER_SETTINGS = ${options ?: "{}"}; globalThis.__store = $stored;"
    }

    /** Keeps [s]'s localStorage as a run left it ([json]), when it changed. */
    private fun keep(dir: File, url: String, s: Scraper, json: String) {
        val f = storageFile(dir, url, s)
        val now = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (if (f.exists()) runCatching { f.readText() }.getOrNull() == now.toString() else now.length() == 0) return
        runCatching {
            val part = File(f.path + ".part")
            part.writeText(now.toString())
            part.renameTo(f)
        }
    }

    internal fun repo(url: String, m: JSONObject): Repo {
        fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter { s -> s.isNotBlank() } }
        val list = m.optJSONArray("scrapers") ?: JSONArray()
        val scrapers = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { s ->
            val id = s.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = s.optString("filename").takeIf { it.isNotBlank() && !it.contains("..") } ?: return@mapNotNull null
            Scraper(id, s.optString("name").ifBlank { id }, s.optString("description"), s.optString("version"), file,
                strings(s.optJSONArray("supportedTypes")).orEmpty().map { it.lowercase() },
                strings(s.optJSONArray("idPrefixes"))?.takeIf { it.isNotEmpty() }, s.optBoolean("enabled", true),
                strings(s.optJSONArray("contentLanguage")).orEmpty(), s.optBoolean("hasSettings"))
        }
        return Repo(url, m.optString("name").ifBlank { host(url) }, m.optString("version"), m.optString("author"),
            m.optString("description"), scrapers)
    }

    /** The repository at [url] as last read, or null. Reads the disk the first time. */
    fun saved(dir: File, url: String): Repo? {
        repos[url]?.let { return it }
        val f = File(folder(dir, url), "manifest.json")
        val r = runCatching { repo(url, JSONObject(f.readText())) }.getOrNull() ?: return null
        repos = repos + (url to r)
        return r
    }

    /**
     * Reads the repository at [url] again: its manifest, and each plugin's script whose version changed or that's
     * missing. The repository, or null when its manifest couldn't be read. Network: off the UI thread.
     */
    fun update(dir: File, url: String): Repo? {
        val m = runCatching { Http.json(url) }.onFailure { warn("Nuvio repository failed: ${host(url)} ($it)") }.getOrNull() ?: return null
        val r = repo(url, m)
        val old = saved(dir, url)?.scrapers?.associateBy { it.id }.orEmpty()
        val base = url.substringBefore('?').substringBeforeLast('/')
        folder(dir, url).mkdirs()
        for (s in r.scrapers) {
            val f = scriptFile(dir, url, s)
            if (f.exists() && old[s.id]?.version == s.version) continue
            runCatching {
                val code = Http.open("$base/${s.file.removePrefix("/")}").use { it.readCapped(Http.MAX_TEXT) }
                val part = File(f.path + ".part")
                part.writeBytes(code)
                part.renameTo(f)
            }.onFailure { warn("Nuvio plugin failed: ${s.name} ($it)") }
        }
        File(folder(dir, url), "manifest.json").writeText(m.toString())
        repos = repos + (url to r)
        return r
    }

    /** How long ago the repository at [url] was last read (forever when never). */
    fun age(dir: File, url: String) = File(folder(dir, url), "manifest.json").let { if (it.exists()) System.currentTimeMillis() - it.lastModified() else Long.MAX_VALUE }

    /** Forgets a repository the user removed, and its scripts. */
    fun forget(dir: File, url: String) {
        repos = repos - url
        folder(dir, url).deleteRecursively()
    }

    /**
     * Whether [s] can find streams for an item of Stremio's [type] ("movie", "series", "tv" for a TV channel, or an
     * add-on's own) with this [id]: by its types (Nuvio says "tv" for series) and its id prefixes.
     */
    fun provides(s: Scraper, type: String, id: String): Boolean {
        if (type == "tv") {
            // A TV channel: Nuvio's "tv" is series, so only a plugin that says channels ("live", "channel"), or "tv"
            // with ids of its own (not films' and series'), and one of them.
            val ownIds = s.prefixes?.filter { p -> TITLE_IDS.none { p.startsWith(it) } }.orEmpty()
            val live = s.types.any { it == "live" || it == "channel" } || ("tv" in s.types && ownIds.isNotEmpty())
            return live && (s.prefixes == null || s.prefixes.any { id.startsWith(it) })
        }
        val kinds = when (type) {
            "movie" -> setOf("movie")
            "series" -> setOf("series", "tv", "anime")
            else -> setOf(type.lowercase())
        }
        if (s.types.none { it in kinds }) return false
        // Without prefixes, the ids Nuvio gives every plugin: TMDB's, which IMDb's are turned into (see streams).
        return (s.prefixes ?: listOf("tt", "tmdb:")).any { id.startsWith(it) }
    }

    /** The ids films and series go by (IMDb's, TMDB's, the anime databases'), as plugins' prefixes write them. */
    private val TITLE_IDS = listOf("tt", "tmdb", "kitsu", "mal", "anilist", "anidb", "imdb", "tvdb")

    /** Each plugin's options as last read ("<manifest>#<id>@<version>"): their script runs once for them. */
    private val fieldsRead = ConcurrentHashMap<String, List<Field>>()

    /**
     * [s]'s options, as its onSettings() lists them (given the values chosen, [options]); empty when it has none or it
     * fails. The values chosen reach the plugin as globalThis.SCRAPER_SETTINGS (see streams). Runs the script: off the
     * UI thread.
     */
    fun fields(dir: File, url: String, s: Scraper, options: JSONObject? = null): List<Field> {
        val key = "$url#${s.id}@${s.version}"
        fieldsRead[key]?.let { return it }
        val script = runCatching { scriptFile(dir, url, s).readText() }.getOrNull() ?: return emptyList()
        val call = "(typeof onSettings === \"function\" ? onSettings : module.exports.onSettings)()"
        val list = runCatching { exec(script, call, globals(dir, url, s, options)) { keep(dir, url, s, it) } }.onFailure { warn("Nuvio plugin options failed: ${s.name} ($it)") }.getOrNull()
            ?: return emptyList()
        return fields(list).also { if (it.isNotEmpty()) fieldsRead[key] = it }
    }

    internal fun fields(list: JSONArray): List<Field> = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { f ->
        val type = f.optString("type").lowercase()
        val key = f.optString("key")
        if (type != "header" && type != "info" && key.isBlank()) return@mapNotNull null
        // A "list" (Zephyr TV's extensions): its items' fields, no list among them.
        val item = if (type == "list") fields(f.optJSONArray("item") ?: JSONArray()).filter { it.type != "list" } else emptyList()
        if (type == "list" && item.none { it.type in setOf("text", "select", "toggle") }) return@mapNotNull null
        val options = f.optJSONArray("options") ?: JSONArray()
        val choices = (0 until options.length()).mapNotNull { i ->
            options.optJSONObject(i)?.let { o -> o.opt("value")?.let { v -> o.optString("label").ifBlank { v.toString() } to v } }
                ?: options.opt(i)?.takeIf { it !is JSONObject && it != JSONObject.NULL }?.let { it.toString() to it }
        }
        Field(type, key, f.optString("label").ifBlank { key }, f.optString("description"), f.optString("placeholder"),
            f.optBoolean("isPassword"), choices, f.opt("defaultValue")?.takeIf { it != JSONObject.NULL }, item, f.optString("itemLabel"))
    }

    /**
     * [s]'s streams for the film or episode [id] of Stremio's [type] (an episode's id ends ":season:episode"), named
     * [name] in the lists. [tmdbKey]: the user's, for the plugins that ask TMDB (they have their own otherwise); [options]:
     * the values of its options the user chose, by key.
     * Empty when it finds none, fails or runs out of time. Network: off the UI thread.
     */
    fun streams(dir: File, url: String, s: Scraper, name: String, type: String, id: String, tmdbKey: String?,
                options: JSONObject? = null): List<Stremio.Stream> {
        val script = runCatching { scriptFile(dir, url, s).readText() }.getOrNull() ?: return emptyList()
        val parts = id.split(':')
        val episodic = type == "series" && parts.size >= 3 && parts.takeLast(2).all { p -> p.isNotEmpty() && p.all(Char::isDigit) }
        val own = if (episodic) parts.dropLast(2).joinToString(":") else id
        // TMDB's id, as Nuvio gives it (most plugins know no other); IMDb's when TMDB's can't be had.
        val base = if (own.startsWith("tt")) tmdbIdOf(type, own, tmdbKey) ?: own else own.removePrefix("tmdb:")
        val season = if (episodic) parts[parts.size - 2] else "null"
        val episode = if (episodic) parts.last() else "null"
        val media = if (type == "movie") "movie" else "tv"
        val call = "(typeof getStreams === \"function\" ? getStreams : module.exports.getStreams)(" +
            "${JSONObject.quote(base)}, ${JSONObject.quote(media)}, $season, $episode)"
        val found = runCatching { exec(script, call, globals(dir, url, s, options, tmdbKey)) { keep(dir, url, s, it) } }.onFailure { warn("Nuvio plugin failed: ${s.name} ($it)") }.getOrNull()
            ?: return emptyList()
        return Stremio.streams(asStremio(found), vod = true).map { Stremio.Stream(it.url, it.title, it.headers, it.group, name) }
    }

    /** A plugin's streams as a Stremio add-on's answer: its headers where Stremio keeps them, its title as the description. */
    internal fun asStremio(found: JSONArray): JSONObject {
        val out = JSONArray()
        for (i in 0 until found.length()) {
            val s = found.optJSONObject(i) ?: continue
            val hints = s.optJSONObject("behaviorHints") ?: JSONObject()
            val request = JSONObject()
            listOfNotNull(hints.optJSONObject("headers"), hints.optJSONObject("proxyHeaders")?.optJSONObject("request"), s.optJSONObject("headers"))
                .forEach { h -> h.keys().forEach { k -> request.put(k, h.optString(k)) } }
            hints.put("proxyHeaders", JSONObject().put("request", request))
            val name = listOfNotNull(s.optString("name").takeIf { it.isNotBlank() }, s.optString("quality").takeIf { it.isNotBlank() })
                .joinToString(" ").replace(Regex("[​-‍⁠-⁤]"), "") // zero-width marks some plugins sort by
            out.put(JSONObject().put("url", s.optString("url")).put("name", name)
                .put("description", s.optString("title").ifBlank { s.optString("description") }).put("behaviorHints", hints))
        }
        return JSONObject().put("streams", out)
    }

}
