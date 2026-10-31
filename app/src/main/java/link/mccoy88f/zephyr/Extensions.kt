package link.mccoy88f.zephyr

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zephyr TV's extensions (docs/extensions.md): a .zip with a manifest.json and its code, installed from the address
 * of its manifest-zephyrtv.json, from a .zip, or with Zephyr TV (preinstalled). Here: what an extension is, checking
 * one, and keeping it on disk — files/extensions/<id> its package, files/extension-data/<id> what it keeps (its
 * localStorage, its playlists), kept across updates. Running one is Js's, in its own process (ScriptProcess).
 */
internal object Extensions {
    /** The newest version of the standard this Zephyr TV knows ("zephyr" in a manifest). */
    const val STANDARD = 1

    /** The features added to the standard after 1.0 that this Zephyr TV has (see "requires" and zephyr.has()). */
    val FEATURES: Set<String> = setOf("match-posters")

    /** The name of the file an extension is published with. */
    const val PUBLISHED = "manifest-zephyrtv.json"
    const val REPOSITORY = "repository-zephyrtv.json" // a repository: many extensions' addresses in one (see repository)

    private const val MAX_ZIP = 10L * 1024 * 1024
    private const val MAX_FILES = 20L * 1024 * 1024 // unzipped
    private const val MAX_ENTRIES = 2000

    /** The collectors an extension's content can go to (see "collectors"). */
    val COLLECTORS = listOf("movies", "series", "tv", "radio", "videos", "courses", "podcasts", "audiobooks", "events")

    /** An extension installed: its manifest's fields, and the folder of its package. */
    internal class Extension(val id: String, val version: String, val zephyr: Int, val name: String, val description: String,
                             val author: String, val icon: String?, val main: String, val types: List<String>,
                             val idPrefixes: List<String>?, val settings: Boolean, val updateUrl: String?, val adult: Boolean,
                             val configurationRequired: Boolean, val requires: List<String>, val collectors: List<String>,
                             val manifest: JSONObject, val dir: File) {
        /** Its resources by name ("catalog", "stream", "guide", …), as the manifest lists them. */
        val resources: List<String> = manifest.optJSONArray("resources")?.let { a ->
            (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String) ?: (a.opt(i) as? JSONObject)?.optString("name") }
        }.orEmpty().filter { it.isNotBlank() }

        /** Its icon as a file of the package, when it's one. */
        val iconFile: File? get() = icon?.takeIf { !it.startsWith("http") }?.let { safe(dir, it) }?.takeIf { it.exists() }
    }

    /** What a manifest-zephyrtv.json says: an extension's latest version, where its package is, and its SHA-256. */
    internal class Published(val url: String, val id: String, val version: String, val zephyr: Int, val name: String,
                             val description: String, val author: String, val icon: String?, val packageUrl: String,
                             val sha256: String, val adult: Boolean)

    /** A repository-zephyrtv.json: its [name], and the addresses of its extensions' manifest-zephyrtv.json. */
    internal class Repository(val url: String, val name: String, val extensions: List<String>)

    /** Why an extension can't be installed. */
    internal enum class Why { NOT_AN_EXTENSION, NEWER_STANDARD, NEEDS_FEATURES, TOO_LARGE, DAMAGED, DIFFERENT, UNREACHABLE }

    internal class Refused(val why: Why, val detail: String = "") : Exception("${why.name.lowercase()}: $detail")

    // ---------- reading ----------

    /**
     * The manifest-zephyrtv.json at [input]: an address ending in its name, or the folder it's in. Null when it isn't
     * a web address.
     */
    fun publishedUrl(input: String): String? {
        val url = input.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        if (url.substringAfter("://").substringBefore('/').isEmpty()) return null
        val path = url.substringBefore('?').substringBefore('#')
        return if (path.endsWith(PUBLISHED) || path.endsWith(REPOSITORY)) url else url.removeSuffix("/") + "/" + PUBLISHED
    }

    /** Whether [m], read from an address, is a repository (its list of extensions) rather than one extension. */
    fun isRepository(m: JSONObject) = m.has("extensions") && !m.has("package")

    /**
     * [m], the repository-zephyrtv.json read from [url], checked: `{ "zephyr": 1, "name": …, "extensions": [address…] }`,
     * each address a manifest-zephyrtv.json (absolute, or relative to the repository's). Throws Refused.
     */
    fun repository(url: String, m: JSONObject): Repository {
        val zephyr = m.optInt("zephyr", 0)
        val list = m.optJSONArray("extensions")
        if (zephyr < 1 || list == null || m.optString("name").isBlank()) throw Refused(Why.NOT_AN_EXTENSION, url)
        if (zephyr > STANDARD) throw Refused(Why.NEWER_STANDARD, "$zephyr")
        val addresses = (0 until list.length()).mapNotNull { i ->
            list.optString(i).takeIf { it.isNotBlank() }?.let { runCatching { java.net.URL(java.net.URL(url), it).toString() }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }.distinct()
        return Repository(url, m.optString("name"), addresses)
    }

    /** [m], the manifest-zephyrtv.json read from [url], checked. Throws Refused. */
    fun published(url: String, m: JSONObject): Published {
        val id = m.optString("id")
        val zephyr = m.optInt("zephyr", 0)
        val pkg = m.optString("package")
        val sha = m.optString("sha256").lowercase()
        if (zephyr < 1 || !validId(id) || pkg.isBlank() || !Regex("[0-9a-f]{64}").matches(sha) || m.optString("name").isBlank())
            throw Refused(Why.NOT_AN_EXTENSION, url)
        if (zephyr > STANDARD) throw Refused(Why.NEWER_STANDARD, "$zephyr")
        val version = m.optString("version").takeIf { validVersion(it) } ?: throw Refused(Why.NOT_AN_EXTENSION, "version")
        val packageUrl = runCatching { java.net.URL(java.net.URL(url), pkg).toString() }.getOrNull()?.takeIf { it.startsWith("http") }
            ?: throw Refused(Why.NOT_AN_EXTENSION, "package")
        val icon = m.optString("icon").takeIf { it.isNotBlank() }?.let { runCatching { java.net.URL(java.net.URL(url), it).toString() }.getOrNull() }
        return Published(url, id, version, zephyr, m.optString("name"), m.optString("description"), m.optString("author"), icon,
            packageUrl, sha, m.optBoolean("adult"))
    }

    /** Reads the manifest-zephyrtv.json at [url]. Network: off the UI thread. Throws Refused. */
    fun published(url: String): Published {
        val m = runCatching { Http.json(url) }.getOrElse { throw Refused(Why.UNREACHABLE, it.toString()) }
        return published(url, m)
    }

    /** The extension whose package is in [dir], from its manifest.json, checked. Throws Refused. */
    fun read(dir: File): Extension {
        val m = runCatching { JSONObject(File(dir, "manifest.json").readText()) }.getOrElse { throw Refused(Why.NOT_AN_EXTENSION, "manifest.json") }
        fun strings(key: String) = m.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        val zephyr = m.optInt("zephyr", 0)
        if (zephyr < 1) throw Refused(Why.NOT_AN_EXTENSION, "zephyr")
        if (zephyr > STANDARD) throw Refused(Why.NEWER_STANDARD, "$zephyr")
        val id = m.optString("id").takeIf { validId(it) } ?: throw Refused(Why.NOT_AN_EXTENSION, "id")
        val version = m.optString("version").takeIf { validVersion(it) } ?: throw Refused(Why.NOT_AN_EXTENSION, "version")
        val name = m.optString("name").takeIf { it.isNotBlank() } ?: throw Refused(Why.NOT_AN_EXTENSION, "name")
        val main = m.optString("main").takeIf { it.isNotBlank() } ?: throw Refused(Why.NOT_AN_EXTENSION, "main")
        if (safe(dir, main)?.isFile != true) throw Refused(Why.NOT_AN_EXTENSION, main)
        val requires = strings("requires").orEmpty()
        val missing = requires.filter { it !in FEATURES }
        if (missing.isNotEmpty()) throw Refused(Why.NEEDS_FEATURES, missing.joinToString(", "))
        val hints = m.optJSONObject("behaviorHints")
        return Extension(id, version, zephyr, name, m.optString("description"), m.optString("author"),
            m.optString("icon").takeIf { it.isNotBlank() }, main, strings("types").orEmpty(), strings("idPrefixes")?.takeIf { it.isNotEmpty() },
            m.optBoolean("settings"), m.optString("updateUrl").takeIf { it.startsWith("http") }, hints?.optBoolean("adult") == true,
            hints?.optBoolean("configurationRequired") == true, requires, strings("collectors").orEmpty().filter { it in COLLECTORS },
            m, dir)
    }

    // ---------- installing ----------

    /**
     * Installs the package [zip] in [files] (the app's files folder), in place of the same extension's older one, its
     * data kept. With [expected] (from a manifest-zephyrtv.json), its id and version must match. Throws Refused.
     */
    fun install(files: File, zip: File, expected: Published? = null): Extension {
        if (zip.length() > MAX_ZIP) throw Refused(Why.TOO_LARGE, "${zip.length()}")
        val root = File(files, "extensions").apply { mkdirs() }
        val temp = File(root, ".new-" + UUID.randomUUID())
        try {
            unzip(zip, temp)
            // A package zipped with its folder ("my-extension/manifest.json") is read from that folder.
            val top = temp.listFiles().orEmpty()
            val base = if (!File(temp, "manifest.json").exists() && top.size == 1 && top[0].isDirectory) top[0] else temp
            val ext = read(base)
            if (expected != null && (expected.id != ext.id || expected.version != ext.version)) throw Refused(Why.DIFFERENT, "${ext.id} ${ext.version}")
            val dir = File(root, ext.id)
            val old = File(root, ".old-" + UUID.randomUUID())
            if (dir.exists() && !dir.renameTo(old)) throw Refused(Why.DAMAGED, "can't replace ${ext.id}")
            if (!base.renameTo(dir)) {
                old.renameTo(dir)
                throw Refused(Why.DAMAGED, "can't install ${ext.id}")
            }
            old.deleteRecursively()
            File(files, "extension-data/${ext.id}").mkdirs()
            cache.remove(ext.id)
            return read(dir)
        } finally {
            temp.deleteRecursively()
        }
    }

    /** Downloads [p]'s package, checks its SHA-256 and installs it (see install). Network: off the UI thread. */
    fun download(files: File, p: Published): Extension {
        val zip = File(files, "extensions/.download-" + UUID.randomUUID()).apply { parentFile?.mkdirs() }
        try {
            runCatching {
                Http.open(p.packageUrl).use { input -> zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP) throw Refused(Why.TOO_LARGE, "$total")
                        out.write(buf, 0, n)
                    }
                } }
            }.getOrElse { throw if (it is Refused) it else Refused(Why.UNREACHABLE, it.toString()) }
            if (sha256(zip) != p.sha256) throw Refused(Why.DAMAGED, "sha256")
            return install(files, zip, p)
        } finally {
            zip.delete()
        }
    }

    /** The manifest.json inside the package [zip] (at its root, or in its one folder), or null. */
    fun manifestOf(zip: File): JSONObject? = runCatching {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val parts = e.name.split('/')
                if (!e.isDirectory && parts.last() == "manifest.json" && parts.size <= 2) return@runCatching JSONObject(z.readBytes().decodeToString())
            }
            null
        }
    }.getOrNull()

    /** Unzips [zip] into [into], refusing paths outside it and packages too large or with too many files. */
    internal fun unzip(zip: File, into: File) {
        into.mkdirs()
        var total = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw Refused(Why.TOO_LARGE, "files")
                val target = safe(into, e.name) ?: throw Refused(Why.DAMAGED, e.name)
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILES) throw Refused(Why.TOO_LARGE, "$total")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (entries == 0) throw Refused(Why.NOT_AN_EXTENSION, "empty")
    }

    /** [path] inside [dir], or null when it would be outside it (absolute, "..", a backslash). */
    internal fun safe(dir: File, path: String): File? {
        if (path.isBlank() || path.startsWith("/") || '\\' in path || path.split('/').any { it == ".." }) return null
        val f = File(dir, path)
        return f.takeIf { it.canonicalPath.startsWith(dir.canonicalPath + File.separator) || it.canonicalPath == dir.canonicalPath }
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- installed ----------

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Extension>()

    /** The extension [id] as installed in [files], or null. Reads the disk the first time. */
    fun installed(files: File, id: String): Extension? {
        cache[id]?.let { return it }
        val dir = File(files, "extensions/$id").takeIf { it.isDirectory } ?: return null
        return runCatching { read(dir) }.onFailure { warn("Extension unreadable: $id ($it)") }.getOrNull()?.also { cache[id] = it }
    }

    /** Every extension installed in [files], by id. */
    fun installed(files: File): List<Extension> =
        File(files, "extensions").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { installed(files, it.name) }

    /** Removes the extension [id]: its package and what it kept. */
    fun remove(files: File, id: String) {
        cache.remove(id)
        File(files, "extensions/$id").deleteRecursively()
        File(files, "extension-data/$id").deleteRecursively()
    }

    /** The folder [id] keeps its data in (its localStorage, its playlists): kept across updates. */
    fun dataDir(files: File, id: String) = File(files, "extension-data/$id").apply { mkdirs() }

    // ---------- versions ----------

    internal fun validId(id: String) = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(id)

    internal fun validVersion(v: String) = Regex("""\d+(\.\d+){0,2}(-[0-9A-Za-z.-]+)?""").matches(v)

    /** Whether version [a] is newer than [b] (semver: 1.10.0 > 1.9.2; 2.0.0 > 2.0.0-beta.3). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }
        val (pa, pb) = parts(a) to parts(b)
        for (i in 0 until 3) if (pa[i] != pb[i]) return pa[i] > pb[i]
        val (ra, rb) = a.substringAfter('-', "") to b.substringAfter('-', "")
        if (ra.isEmpty() != rb.isEmpty()) return ra.isEmpty() // a release is newer than its pre-releases
        val (xa, xb) = ra.split('.') to rb.split('.')
        for (i in 0 until minOf(xa.size, xb.size)) {
            val (na, nb) = xa[i].toIntOrNull() to xb[i].toIntOrNull()
            val c = if (na != null && nb != null) na.compareTo(nb) else xa[i].compareTo(xb[i])
            if (c != 0) return c > 0
        }
        return xa.size > xb.size
    }

    /** The manifest's list [key] as JSON, for what reads it as Stremio's (catalogs, resources). */
    fun list(e: Extension, key: String): JSONArray = e.manifest.optJSONArray(key) ?: JSONArray()
}
