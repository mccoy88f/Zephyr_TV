package link.mccoy88f.zephyr

import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zephyr TV's settings as a small JSON file, to keep on a phone and bring back after a reset or onto another TV.
 * It holds what the user chose (theme, wallpapers, the apps row, Spotlight with its TMDB key, weather...), not
 * passing state (the last weather reading, update checks) and not the layout lock PIN, which could lock someone
 * out of a TV whose PIN they no longer remember. Photos used as wallpapers stay on the TV that has them. With each
 * user's data too ("data": what they watched, where they got to), not their Trakt account (its keys: they sign in
 * again from a phone).
 */
internal object Backup {
    private const val FORMAT = 1

    /** The keys a backup carries. Everything else is state the TV works out again by itself. */
    private val KEYS = setOf(
        K.THEME, K.WP_LIGHT, K.WP_DARK, K.WP_FADE, K.WP_EVERY, K.COLOUR_ICONS,
        K.UPD_BETA, K.SORT, K.ORDER, K.ROW_HIDDEN, K.ROW_ALIGN, K.NAMES,
        K.HIDDEN_WATCH, K.CW_OFF, K.SECTIONS_OFF, K.ADDONS, K.ADDONS_TV, K.ADDONS_VOD, K.ADDONS_MOVIES, K.ADDONS_SERIES, K.ADDONS_VIDEOS, K.ADDONS_COURSES, K.ADDONS_PODCASTS, K.ADDONS_AUDIOBOOKS, K.ADDONS_EVENTS, K.RADIO_PLACE, K.ADULT_ADDONS, K.ADULT_PIN, K.ADULT_FOREVER, K.ADULT_APP, K.ADULT_PLACE, K.STREAM_MANUAL, K.STREAM_LANG_ONLY, K.STREAM_QUALITY, K.FAST_START, K.HIDE_UNKNOWN_LANG, K.SUBTITLES_ON, K.HIDE_UNRELEASED, K.ADDONS_OFF, K.NUVIO_REPOS_OFF, K.NUVIO_REPOS, K.NUVIO_ON, K.NUVIO_OFF, K.NUVIO_ADULT, K.NUVIO_OPTIONS, K.NUVIO_ORDER, K.EXT_ORDER, K.EXT_OFF, K.EXT_ADULT, K.EXT_SOURCES, K.EXT_REPOS, K.EXT_COLLECT, K.EXT_OPTIONS, K.CINEMETA_URL, K.CATALOG_SOURCES, K.CATALOGS, K.SECTION_NAMES, K.SHELVES_OFF, K.USERS, K.USER,
        K.TMDB_API_KEY, K.TMDB_LANG, K.TMDB_TYPE, K.TMDB_DEFAULT_APPS, K.TMDB_ENRICH, K.TMDB_CYCLE, K.TMDB_PROVIDERS, K.TMDB_SOURCE, K.TMDB_LIST,
        K.WEATHER, K.WX_UNIT, K.GEO_MANUAL, K.GEO_CITY, K.GEO_LAT, K.GEO_LON,
        K.SS_FOLDER, K.CLOCK_24, K.STATUS_BAR, K.NIGHT, K.NIGHT_FROM, K.NIGHT_TO,
        K.EPG_URL, K.CHANNEL_LOGOS, K.INPUTS_MODE, K.INPUTS_PINNED, K.INPUTS_IN_APPS,
    )

    /** A user's files a backup carries, from their folder (see vodDir): what they watched and where they got to. */
    private val USER_FILES = listOf("vod_watched.json", "vod_progress.json")

    /** The ids user folders go by (as Users keeps them): nothing else is written from a backup. */
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,32}")

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

    /** [prefs]' settings (KEYS), and with [files] and [users] their data (see exportData). */
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
        val root = JSONObject().put("app", "zephyr").put("format", FORMAT).put("prefs", values)
        if (files != null) root.put("data", exportData(files, users))
        return root.toString(2)
    }

    /**
     * Puts the settings in [json] back, replacing the current ones a backup covers (a setting missing from the
     * file goes back to its default), and with [files] the users' data it carries. Returns how many settings it set;
     * throws [IllegalArgumentException] for a file that isn't a Zephyr TV backup.
     */
    fun restore(prefs: SharedPreferences, json: String, files: File? = null): Int {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("not JSON")
        }
        // Piume's backups too: Zephyr TV is what Piume became, with the same settings.
        require(root.optString("app") in setOf("zephyr", "piume") && root.optInt("format") in 1..FORMAT) { "not a Zephyr TV backup" }
        val values = root.getJSONObject("prefs")
        val edit = prefs.edit()
        KEYS.forEach { edit.remove(it) }
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
        if (files != null) root.optJSONObject("data")?.let { restoreData(files, it) }
        return n
    }
}
