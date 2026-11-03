package link.mccoy88f.zephyr

import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zephyr TV's settings as a JSON file, to keep on a phone and bring back after a reset or onto another TV.
 * It holds what the user chose (theme, wallpapers, the apps row, Spotlight with its TMDB key, weather...), and all
 * of the add-ons, Nuvio plugins and extensions: which ones, their order, on or off, 18+ and their settings
 * ("prefs"). It also holds what the extensions and the plugins keep for themselves, such as a sign-in ("extras":
 * storage), and the package of each extension installed from a file, which has no address to download it again
 * from (packages). Extensions and repositories with an address are downloaded again after a restore (see
 * refreshExtensions and refreshRepos).
 *
 * Not in it: passing state (the last weather reading, update checks); the PIN, never ("pin" only says that the TV
 * had one: a restore then asks for a new PIN, see needsPin); remote access and WARP (their address, token and
 * account belong to one TV); photos used as wallpapers. The locks' switches are in it, so they come back on. With each user's data too ("data": what they watched, where they
 * got to), not their Trakt account (its keys: they sign in again from a phone).
 */
internal object Backup {
    private const val FORMAT = 1

    /** The keys a backup carries. Everything else is state the TV works out again by itself. */
    private val KEYS = setOf(
        K.THEME, K.WP_LIGHT, K.WP_DARK, K.WP_FADE, K.WP_EVERY, K.COLOUR_ICONS,
        K.UPD_BETA, K.SORT, K.ORDER, K.ROW_HIDDEN, K.ROW_ALIGN, K.NAMES,
        K.HIDDEN_WATCH, K.CW_OFF, K.SECTIONS_OFF, K.ADDONS, K.ADDONS_TV, K.ADDONS_VOD, K.ADDONS_MOVIES, K.ADDONS_SERIES, K.ADDONS_VIDEOS, K.ADDONS_COURSES, K.ADDONS_PODCASTS, K.ADDONS_AUDIOBOOKS, K.ADDONS_EVENTS, K.RADIO_PLACE, K.ADULT_ADDONS, K.ADULT_ON, K.LOCK_ON, K.ADULT_FOREVER, K.ADULT_APP, K.ADULT_PLACE, K.STREAM_MANUAL, K.STREAM_LANG_ONLY, K.STREAM_QUALITY, K.FAST_START, K.HIDE_UNKNOWN_LANG, K.SUBTITLES_ON, K.HIDE_UNRELEASED, K.ADDONS_OFF, K.NUVIO_REPOS_OFF, K.NUVIO_REPOS, K.NUVIO_ON, K.NUVIO_OFF, K.NUVIO_ADULT, K.NUVIO_OPTIONS, K.NUVIO_ORDER, K.EXT_ORDER, K.EXT_OFF, K.EXT_ADULT, K.EXT_SOURCES, K.EXT_REPOS, K.EXT_COLLECT, K.EXT_OWN, K.EXT_APP, K.EXT_OPTIONS, K.CINEMETA_URL, K.CATALOG_SOURCES, K.CATALOGS, K.SECTION_NAMES, K.SHELVES_OFF, K.USERS, K.USER,
        K.TMDB_API_KEY, K.TMDB_LANG, K.TMDB_TYPE, K.TMDB_DEFAULT_APPS, K.TMDB_ENRICH, K.TMDB_CYCLE, K.TMDB_PROVIDERS, K.TMDB_SOURCE, K.TMDB_LIST,
        K.WEATHER, K.WX_UNIT, K.GEO_MANUAL, K.GEO_CITY, K.GEO_LAT, K.GEO_LON,
        K.SS_FOLDER, K.CLOCK_24, K.STATUS_BAR, K.NIGHT, K.NIGHT_FROM, K.NIGHT_TO,
        K.EPG_URL, K.CHANNEL_LOGOS, K.PLAY_CHANNELS, K.INPUTS_MODE, K.INPUTS_PINNED, K.INPUTS_IN_APPS,
    )

    /** A user's files a backup carries, from their folder (see vodDir): what they watched and where they got to. */
    private val USER_FILES = listOf("vod_watched.json", "vod_progress.json")

    /** The ids user folders go by (as Users keeps them): nothing else is written from a backup. */
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,32}")

    /** What the extensions and the Nuvio plugins keep for themselves: their localStorage, under [files]. */
    private val STORAGE = listOf(
        Regex("extension-data/[A-Za-z0-9][A-Za-z0-9._-]{0,99}/storage\\.json"),
        Regex("nuvio/[0-9a-f]{16}/[A-Za-z0-9_.-]{1,200}\\.storage\\.json"),
    )

    /**
     * The most the packages of extensions installed from a file may take, before they are encoded. The backup is
     * read in one piece on the TV and the phone, so it stays small. A package that doesn't fit is left out,
     * and its name goes in "skipped".
     */
    private const val PACKAGES_MAX = 4L * 1024 * 1024

    /** [user]'s folder in [files]: the first user's is [files] itself (see vodDir). */
    private fun userDir(files: File, user: String) = if (user == "") files else File(files, "user_$user")

    /** Each of [users]' files (see USER_FILES) under [files], as {user: {file: its text}}. */
    internal fun exportData(files: File, users: List<String>): JSONObject {
        val out = JSONObject()
        for (u in users.filter { it == "" || USER_ID.matches(it) }) {
            val mine = JSONObject()
            for (name in USER_FILES) File(userDir(files, u), name).takeIf { it.isFile }?.let { mine.put(name, it.readText()) }
            if (mine.length() > 0) out.put(u, mine)
        }
        return out
    }

    /** Puts [data] (see exportData) back under [files], replacing those files; how many it wrote. */
    internal fun restoreData(files: File, data: JSONObject): Int {
        var n = 0
        for (u in data.keys()) {
            if (u != "" && !USER_ID.matches(u)) continue
            val mine = data.optJSONObject(u) ?: continue
            val dir = userDir(files, u).apply { mkdirs() }
            for (name in USER_FILES) {
                val text = mine.optString(name).takeIf { it.isNotEmpty() } ?: continue
                if (runCatching { JSONObject(text) }.isFailure && runCatching { JSONArray(text) }.isFailure) continue // not what it should be
                val part = File(dir, "$name.part")
                part.writeText(text)
                if (part.renameTo(File(dir, name))) n++
            }
        }
        return n
    }

    /**
     * What the extensions and the plugins keep, and the packages of the extensions that are not in [withSource]
     * (installed from a file), under [files]: {"storage": {path: text}, "packages": {id: zip in Base64}, "skipped": [id]}.
     */
    internal fun exportExtras(files: File, withSource: Set<String>): JSONObject {
        val storage = JSONObject()
        listOf(File(files, "extension-data"), File(files, "nuvio")).forEach { top ->
            top.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                dir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                    val path = f.relativeTo(files).invariantSeparatorsPath
                    if (STORAGE.any { it.matches(path) }) runCatching { storage.put(path, f.readText()) }
                }
            }
        }
        val packages = JSONObject()
        val skipped = JSONArray()
        var used = 0L
        File(files, "extensions").listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && Extensions.validId(it.name) && it.name !in withSource }
            .sortedBy { it.name }
            .forEach { dir ->
                val zip = zipOf(dir)
                if (used + zip.size > PACKAGES_MAX) skipped.put(dir.name)
                else {
                    used += zip.size
                    packages.put(dir.name, java.util.Base64.getEncoder().encodeToString(zip))
                }
            }
        return JSONObject().put("storage", storage).put("packages", packages).put("skipped", skipped)
    }

    /** [dir]'s files as a zip, with paths relative to it. */
    private fun zipOf(dir: File): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Puts [extras] (see exportExtras) back under [files]: the storage files, and the packages installed again.
     * Only the paths of STORAGE are written; a package that isn't an extension is left out. How many it put back.
     */
    internal fun restoreExtras(files: File, extras: JSONObject): Int {
        var n = 0
        val storage = extras.optJSONObject("storage") ?: JSONObject()
        for (path in storage.keys()) {
            if (STORAGE.none { it.matches(path) }) continue
            val text = storage.optString(path)
            if (runCatching { JSONObject(text) }.isFailure) continue
            val f = File(files, path)
            f.parentFile?.mkdirs()
            val part = File(f.path + ".part")
            part.writeText(text)
            if (part.renameTo(f)) n++
        }
        val packages = extras.optJSONObject("packages") ?: JSONObject()
        for (id in packages.keys()) {
            if (!Extensions.validId(id)) continue
            val zip = File(files, "extensions/.restore-" + java.util.UUID.randomUUID()).apply { parentFile?.mkdirs() }
            try {
                zip.writeBytes(java.util.Base64.getDecoder().decode(packages.optString(id)))
                Extensions.install(files, zip)
                n++
            } catch (e: Exception) {
                warn("backup: extension not installed again: $id ($e)")
            } finally {
                zip.delete()
            }
        }
        return n
    }

    /** The extensions that have an address to download them again from (see extensionSource). */
    private fun withSource(prefs: SharedPreferences) =
        runCatching { JSONObject(prefs.getString(K.EXT_SOURCES, null) ?: "{}").keys().asSequence().toSet() }.getOrDefault(emptySet())

    /** [prefs]' settings (KEYS), and with [files] and [users] their data (see exportData) and the extras (see exportExtras). */
    fun export(prefs: SharedPreferences, files: File? = null, users: List<String> = emptyList()): String {
        val values = JSONObject()
        for ((key, value) in prefs.all) {
            if (key !in KEYS) continue
            values.put(key, when (value) {
                is String -> JSONObject().put("s", value)
                is Boolean -> JSONObject().put("b", value)
                is Int -> JSONObject().put("i", value)
                is Long -> JSONObject().put("l", value)
                is Float -> JSONObject().put("f", value.toDouble())
                is Set<*> -> JSONObject().put("set", JSONArray(value.map { it.toString() }))
                else -> continue
            })
        }
        val root = JSONObject().put("app", "zephyr").put("format", FORMAT).put("prefs", values).put("pin", prefs.contains(K.PIN))
        if (files != null) root.put("data", exportData(files, users)).put("extras", exportExtras(files, withSource(prefs)))
        return root.toString(2)
    }

    /** [json] read as a backup. Throws [IllegalArgumentException] for a file that isn't a Zephyr TV backup. */
    private fun read(json: String): JSONObject {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("not JSON")
        }
        // Piume's backups too: Zephyr TV is what Piume became, with the same settings.
        require(root.optString("app") in setOf("zephyr", "piume") && root.optInt("format") in 1..FORMAT) { "not a Zephyr TV backup" }
        require(root.optJSONObject("prefs") != null) { "no settings" }
        return root
    }

    /**
     * Whether the TV that made [json] had a PIN: then the restore asks for a new one first. An older backup
     * says so with its 18+ PIN, which isn't put back. Throws [IllegalArgumentException] like [restore].
     */
    fun needsPin(json: String): Boolean = needsPin(read(json))

    private fun needsPin(root: JSONObject) = root.optBoolean("pin") || root.getJSONObject("prefs").has(K.ADULT_PIN)

    /**
     * Puts the settings in [json] back, replacing the current ones a backup covers (a setting missing from the
     * file goes back to its default), and with [files] the users' data it carries. A backup that had a PIN (see
     * [needsPin]) needs [newPin], the new one already hashed; it replaces the TV's PIN. Returns how many settings it
     * set; throws [IllegalArgumentException] for a file that isn't a Zephyr TV backup.
     */
    fun restore(prefs: SharedPreferences, json: String, files: File? = null, newPin: String? = null): Int {
        val root = read(json)
        val pin = needsPin(root)
        require(!pin || newPin != null) { "a new PIN first" }
        val values = root.getJSONObject("prefs")
        val edit = prefs.edit()
        KEYS.forEach { edit.remove(it) }
        if (pin) edit.putString(K.PIN, newPin)
        // An older backup: its 18+ PIN meant the 18+ lock was on. The PIN itself stays out.
        if (values.has(K.ADULT_PIN) && !values.has(K.ADULT_ON)) edit.putBoolean(K.ADULT_ON, true)
        var n = 0
        for (key in values.keys()) {
            if (key !in KEYS) continue // a newer Zephyr TV's setting, or something added by hand
            val v = values.optJSONObject(key) ?: continue
            when {
                v.has("s") -> edit.putString(key, v.getString("s"))
                v.has("b") -> edit.putBoolean(key, v.getBoolean("b"))
                v.has("i") -> edit.putInt(key, v.getInt("i"))
                v.has("l") -> edit.putLong(key, v.getLong("l"))
                v.has("f") -> edit.putFloat(key, v.getDouble("f").toFloat())
                v.has("set") -> v.getJSONArray("set").let { a -> edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
                else -> continue
            }
            n++
        }
        edit.apply()
        if (files != null) {
            root.optJSONObject("data")?.let { restoreData(files, it) }
            root.optJSONObject("extras")?.let { restoreExtras(files, it) }
        }
        return n
    }
}
