package link.mccoy88f.zephyr

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extensions as add-ons: an extension's address ("zext://<id>/…", Stremio.EXT) is answered by calling its handler —
 * catalog, meta, stream — in the extensions' process, with what the standard gives it (args, zephyr, its settings,
 * its localStorage). The rest of Zephyr TV asks it as it asks a Stremio add-on (Stremio.get).
 */
internal object ExtensionCalls {
    private const val CALL_MS = 25_000L // meta and stream
    private const val LONG_MS = 60_000L // catalog, manifest, guide

    /** The address of the extension [id] as an add-on. */
    fun url(id: String) = "${Stremio.EXT}$id/manifest.json"

    /** The id of the extension an add-on address is, or null when it's a real add-on's. */
    fun idOf(url: String?): String? = url?.takeIf { it.startsWith(Stremio.EXT) }?.removePrefix(Stremio.EXT)?.substringBefore('/')

    /** Answers the extensions' addresses in this process (the home screen's, the player's), through [context]'s. */
    fun use(context: Context) {
        val app = context.applicationContext
        Stremio.local = { url, _ -> answer(app, url) }
    }

    /** The answer at an extension's [url]: its manifest, or what one of its handlers says. Off the UI thread. */
    internal fun answer(context: Context, url: String): JSONObject {
        val rest = url.removePrefix(Stremio.EXT)
        val id = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        val e = Extensions.installed(context.filesDir, id) ?: throw IOException("no extension $id")
        if (path == "manifest.json") return manifest(context, e)
        val parts = path.removeSuffix(".json").split('/')
        val resource = parts.getOrNull(0).orEmpty()
        if (resource !in listOf("catalog", "meta", "stream") || resource !in e.resources || parts.size < 3) throw IOException("$id: no $resource")
        val args = request(parts[1], parts[2], parts.getOrNull(3)).put("settings", settings(context, e))
        val out = call(context, e, "module.exports.$resource($args)", if (resource == "catalog") LONG_MS else CALL_MS)
        return out?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }

    /** A handler's args: [type], [id], and [extra] ("genre=Sport&skip=100") as an object, its values decoded. */
    internal fun request(type: String, id: String, extra: String?): JSONObject {
        fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val more = JSONObject()
        extra?.split('&')?.filter { '=' in it }?.forEach { kv ->
            val (k, v) = dec(kv.substringBefore('=')) to dec(kv.substringAfter('='))
            if (k == "skip") more.put(k, v.toIntOrNull() ?: 0) else more.put(k, v)
        }
        return JSONObject().put("type", dec(type)).put("id", dec(id)).put("extra", more)
    }

    /**
     * [e]'s manifest as an add-on's: its catalogs as manifest() makes them with its settings (when it exports it), its
     * icon as its logo.
     */
    private fun manifest(context: Context, e: Extensions.Extension): JSONObject {
        val m = JSONObject(e.manifest.toString())
        val catalogs = runCatching {
            call(context, e, "(typeof module.exports.manifest === \"function\" ? module.exports.manifest(${withSettings(context, e)}) : null)", LONG_MS)
        }.onFailure { warn("Extension manifest() failed: ${e.name} ($it)") }.getOrNull()
            ?.let { runCatching { JSONObject(it).optJSONArray("catalogs") }.getOrNull() }
        if (catalogs != null) m.put("catalogs", catalogs)
        (e.iconFile?.let { "file://" + it.path } ?: e.icon?.takeIf { it.startsWith("http") })?.let { m.put("logo", it) }
        return m
    }

    /** The settings chosen for [e] on its options page (Prefs EXT_OPTIONS); none chosen yet: empty. */
    internal fun settings(context: Context, e: Extensions.Extension): JSONObject {
        val p = context.getSharedPreferences(K.FILE, Context.MODE_PRIVATE)
        return runCatching { JSONObject(p.getString(K.EXT_OPTIONS, null) ?: "{}").optJSONObject(e.id) }.getOrNull() ?: JSONObject()
    }

    /** [e]'s settings fields, as its settings() lists them; empty when it has none or it fails. Off the UI thread. */
    fun fields(context: Context, e: Extensions.Extension): List<Nuvio.Field> {
        if (!e.settings) return emptyList()
        val out = runCatching { call(context, e, "(typeof module.exports.settings === \"function\" ? module.exports.settings(${withSettings(context, e)}) : [])", CALL_MS) }
            .onFailure { warn("Extension settings() failed: ${e.name} ($it)") }.getOrNull() ?: return emptyList()
        return runCatching { Nuvio.fields(JSONArray(out)) }.getOrDefault(emptyList())
    }

    private val scripts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Where [e]'s guide sources are kept, as its guide() last gave them. */
    fun guidesFile(context: Context, id: String) = File(Extensions.dataDir(context.filesDir, id), "guides.json")

    /** Asks [e]'s guide() for its sources (XMLTV addresses) and keeps them; none when it has no guide. Off the UI thread. */
    fun refreshGuides(context: Context, e: Extensions.Extension): List<String> {
        val out = if ("guide" !in e.resources) null else runCatching {
            call(context, e, "(typeof module.exports.guide === \"function\" ? module.exports.guide(${withSettings(context, e)}) : { sources: [] })", LONG_MS)
        }.onFailure { warn("Extension guide() failed: ${e.name} ($it)") }.getOrNull()
        val sources = out?.let { runCatching { JSONObject(it).optJSONArray("sources") }.getOrNull() }?.let { a ->
            (0 until a.length()).mapNotNull { i -> (a.opt(i) as? JSONObject)?.optString("url") ?: (a.opt(i) as? String) }.filter { it.startsWith("http") }
        }.orEmpty().distinct()
        guidesFile(context, e.id).writeText(JSONArray(sources).toString())
        return sources
    }

    /** A handler's args with nothing but [e]'s settings: for manifest() and settings(). */
    private fun withSettings(context: Context, e: Extensions.Extension) = JSONObject().put("settings", settings(context, e))

    /** [e]'s code, read once (again when its file changed: an update). */
    private fun script(e: Extensions.Extension): String {
        val f = File(e.dir, e.main)
        scripts[e.id]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        return f.readText().also { scripts[e.id] = f.lastModified() to it }
    }

    /**
     * Runs [e]'s code in the extensions' process, then [expr], with args' settings added: what it answers (JSON), or
     * null when it ran out of time. Its localStorage is kept in its data folder. Throws when it fails.
     */
    private fun call(context: Context, e: Extensions.Extension, expr: String, timeoutMs: Long): String? {
        val files = context.filesDir
        val storage = File(Extensions.dataDir(files, e.id), "storage.json")
        val stored = runCatching { JSONObject(storage.readText()) }.getOrNull() ?: JSONObject()
        val settings = settings(context, e)
        val locale = java.util.Locale.getDefault()
        val zephyr = JSONObject().put("version", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()).put("standard", Extensions.STANDARD)
            .put("language", locale.language).put("region", locale.country)
        val globals = """
            globalThis.SCRAPER_ID = ${JSONObject.quote(e.id)};
            globalThis.SCRAPER_SETTINGS = $settings;
            globalThis.__store = $stored;
            globalThis.zephyr = $zephyr;
            zephyr.has = function (name) { return ${JSONArray(Extensions.FEATURES.toList())}.indexOf(name) >= 0; };
            zephyr.asset = function (path) { return __zephyr("asset", { path: path }); };
            zephyr.epg = { now: function (o) { return __zephyr("epg.now", o || {}); } };
            zephyr.matchPoster = function (o) { return __zephyr("match.poster", o || {}); };
            zephyr.m3u = {
              load: function (name, o) { o = o || {}; return __zephyr("m3u.load", { name: name, url: o.url || "", text: o.text || "", headers: o.headers || {}, maxAge: o.maxAge == null ? 86400 : o.maxAge }); },
              channels: function (name, o) { o = o || {}; return __zephyr("m3u.channels", { name: name, kind: o.kind || "", group: o.group || "", search: o.search || "", skip: o.skip || 0, limit: o.limit || 100 }); },
              channel: function (name, id) { return __zephyr("m3u.channel", { name: name, id: id }); },
              forget: function (name) { return __zephyr("m3u.forget", { name: name }); }
            };
        """.trimIndent()
        val answer = ScriptProcess.extensions(context).run(script(e), expr, globals, timeoutMs, wantsStore = true, dir = e.dir)
        answer.store?.let { s ->
            if (runCatching { storage.readText() }.getOrNull() != s) runCatching {
                val part = File(storage.path + ".part")
                part.writeText(s)
                part.renameTo(storage)
            }
        }
        return answer.out
    }
}
