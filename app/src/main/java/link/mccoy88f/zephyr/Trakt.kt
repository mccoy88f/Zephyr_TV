package link.mccoy88f.zephyr

import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trakt (trakt.tv), each Zephyr TV user's own account: what they watch is scrobbled as it plays (see PlayerActivity),
 * what they mark watched on a page goes to their history, and what they watched elsewhere and where they paused
 * comes back into Zephyr TV (see MainActivity.syncTrakt).
 *
 * Signing in happens on a phone: the TV shows a QR code of Trakt's authorize page, whose answer goes to a site's
 * /traktauth: Zephyr TV's (ztv.mccoy88f.link), or the one before it (www.mccoy88f.link) while the new one isn't set
 * up (see signInSite). That site holds the app's client secret (Trakt wants it for every token, and an app's own copy
 * would be anyone's), swaps the code for the tokens and keeps them a few minutes for the TV, which asks for them by the
 * random state it put in the QR code. Tokens are refreshed through a site too, the new one first.
 *
 * Kept in a file in the user's folder (the player, in its own process, scrobbles with it). Network: off the UI thread.
 */
internal object Trakt {
    const val CLIENT_ID = "0TPZx8EN9N4gVqDp4QDbegDyKkcgLO9BDC1lavq9WWY"
    /** The sites that sign in to Trakt and refresh its tokens: Zephyr TV's, then the one before it. */
    private val SITES = listOf("https://ztv.mccoy88f.link", "https://www.mccoy88f.link")
    @Volatile private var ready: Pair<String, Long>? = null
    private const val API = "https://api.trakt.tv"
    private const val FILE = "trakt.json"
    private const val REFRESH_BEFORE_MS = 3600_000L // a token about to expire is refreshed this long before (they last a day or more)

    /** A signed-in account: its tokens, when the access token expires, its Trakt name, and when it was last synced. */
    internal class Account(val access: String, val refresh: String, val expires: Long, val user: String, val synced: Long = 0)

    /** A random state for one sign-in: it ties the phone's answer, kept by the site, to this TV. */
    fun newState(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /**
     * The site to sign in through: the first that says it's set up for Trakt (its /api/trakt/ready), asked at most every
     * 10 minutes; else the last, the one before Zephyr TV's site. Network: off the UI thread.
     */
    fun signInSite(): String {
        ready?.takeIf { System.currentTimeMillis() - it.second < 600_000L }?.let { return it.first }
        val site = SITES.dropLast(1).firstOrNull { s ->
            runCatching { Http.connect("$s/api/trakt/ready").let { c -> try { c.responseCode == 200 } finally { c.disconnect() } } }.getOrDefault(false)
        } ?: SITES.last()
        ready = site to System.currentTimeMillis()
        return site
    }

    /** The sign-in for the QR code, on [site]: it sends the phone on to Trakt's page to sign in and allow Zephyr TV (short, for a QR code). */
    fun signInUrl(state: String, site: String) = "$site/traktauth?start=${enc(state)}"

    @Synchronized
    fun account(dir: File): Account? = runCatching {
        val o = JSONObject(File(dir, FILE).readText())
        Account(o.getString("access"), o.getString("refresh"), o.getLong("expires"), o.optString("user"), o.optLong("synced"))
    }.getOrNull()

    @Synchronized
    private fun save(dir: File, a: Account) {
        dir.mkdirs()
        val part = File(dir, "$FILE.part")
        part.writeText(JSONObject().put("access", a.access).put("refresh", a.refresh).put("expires", a.expires)
            .put("user", a.user).put("synced", a.synced).toString())
        part.renameTo(File(dir, FILE))
    }

    /** Signed out: the account, and what Trakt said was watched last time (a new account starts from Zephyr TV's own). */
    fun forget(dir: File) {
        File(dir, FILE).delete()
        File(dir, SEEN).delete()
    }

    private const val SEEN = "trakt_seen.txt"

    /** The films and episodes Trakt said were watched at the last sync, or null before the first one. */
    fun seen(dir: File): Set<String>? = runCatching { File(dir, SEEN).readLines().filter { it.isNotBlank() }.toSet() }.getOrNull()

    fun seen(dir: File, ids: Collection<String>) {
        val part = File(dir, "$SEEN.part")
        part.writeText(ids.joinToString("\n"))
        part.renameTo(File(dir, SEEN))
    }

    fun synced(dir: File, at: Long) = account(dir)?.let { save(dir, Account(it.access, it.refresh, it.expires, it.user, at)) }

    private fun tokens(o: JSONObject, user: String, synced: Long = 0) = Account(o.getString("access_token"), o.getString("refresh_token"),
        (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7 * 86400)) * 1000, user, synced)

    /** Where a sign-in is: done (the account saved), waiting for the phone, refused by Trakt, or no longer known (expired). */
    enum class SignIn { DONE, WAITING, REFUSED, EXPIRED }

    /** Where the sign-in with [state] (begun on [site]) is; once done, the account is saved in [dir] (with its Trakt name). */
    fun poll(dir: File, state: String, site: String): SignIn {
        val (code, body) = Http.send("$site/api/trakt/poll", JSONObject().put("state", state))
        return when (code) {
            200 -> {
                val a = tokens(JSONObject(body), "")
                val name = runCatching {
                    JSONObject(request(a.access, "GET", "/users/settings", null).second).getJSONObject("user").optString("username")
                }.getOrDefault("")
                save(dir, Account(a.access, a.refresh, a.expires, name))
                SignIn.DONE
            }
            202, 429 -> SignIn.WAITING // 429: asked too often (another TV behind the same address): ask again later
            410 -> SignIn.REFUSED
            else -> SignIn.EXPIRED
        }
    }

    /**
     * The account's access token, refreshed (through the site) when it's about to expire; null when signed out.
     * Trakt gives a new refresh token with each refresh and takes the old one back, so one process refreshes at a
     * time (the home screen and the player both call Trakt), and one that finds it refused looks again first: the
     * other may have just refreshed it.
     */
    private fun token(dir: File): String? {
        val a = account(dir) ?: return null
        if (System.currentTimeMillis() < a.expires - REFRESH_BEFORE_MS) return a.access
        return synchronized(this) {
            locked(dir, FILE) {
                val now = account(dir) ?: return@locked null
                if (now.refresh != a.refresh) return@locked now.access // refreshed meanwhile, here or in the other process
                val (code, body) = refreshThroughSites(now.refresh) ?: return@locked now.access // offline: the old one may still work
                when {
                    code == 401 || code == 400 -> { forget(dir); null } // revoked: signed out
                    code !in 200..299 -> now.access
                    else -> tokens(JSONObject(body), now.user, now.synced).also { save(dir, it) }.access
                }
            }
        }
    }

    /**
     * Trakt's answer to [refresh], through the first site that answers it (Zephyr TV's, then the one before it): one that
     * can't be reached, or isn't set up for Trakt (404, 5xx), passes it on to the next. Null when none answered.
     */
    private fun refreshThroughSites(refresh: String): Pair<Int, String>? {
        for (site in SITES) {
            val answer = runCatching { Http.send("$site/api/trakt/refresh", JSONObject().put("refresh_token", refresh)) }.getOrNull() ?: continue
            if (answer.first == 404 || answer.first >= 500) continue
            return answer
        }
        return null
    }

    private fun request(token: String, method: String, path: String, body: JSONObject?): Pair<Int, String> {
        val headers = mapOf("trakt-api-version" to "2", "trakt-api-key" to CLIENT_ID, "Authorization" to "Bearer $token")
        return runCatching { Http.send(API + path, body, headers, method) }.getOrElse { 0 to "" } // 0: offline
    }

    /** Whether Trakt refused the account itself (signed out on trakt.tv, or the app's access taken back). */
    fun refused(answer: Pair<Int, String>?) = answer?.first == 401 || answer?.first == 403

    /** [path] asked as the account in [dir]; null when signed out. */
    private fun call(dir: File, method: String, path: String, body: JSONObject? = null): Pair<Int, String>? =
        token(dir)?.let { request(it, method, path, body) }

    // ---------- what Zephyr TV tells Trakt ----------

    /** A film ([id] its IMDb id) or an episode ("tt…:1:3", [metaId] its series'), as Trakt names it in a scrobble. */
    private fun item(id: String, metaId: String): JSONObject {
        val parts = id.split(':')
        if (parts.size < 3) return JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("imdb", id)))
        return JSONObject().put("show", JSONObject().put("ids", JSONObject().put("imdb", metaId)))
            .put("episode", JSONObject().put("season", parts[1].toIntOrNull() ?: 0).put("number", parts[2].toIntOrNull() ?: 0))
    }

    /**
     * The player's news: "start" (playing, or again after a pause), "pause", "stop" (left or ended; from 80% on,
     * Trakt counts it watched). [progress] in percent. Quietly nothing when signed out or offline.
     */
    fun scrobble(dir: File, action: String, id: String, metaId: String, progress: Double) {
        if (!id.startsWith("tt")) return
        runCatching { call(dir, "POST", "/scrobble/$action", item(id, metaId).put("progress", progress.coerceIn(0.0, 100.0))) }
    }

    /**
     * Films ("tt…") and episodes ("tt…:1:3", with their series' id) added to the history ([on]) or taken out of it,
     * as [items] (an id to its title's id); [at]: when each was watched, when it's known (else now). Whether Trakt
     * took it: false when signed out or offline.
     */
    fun history(dir: File, items: List<Pair<String, String>>, on: Boolean, at: Map<String, Long> = emptyMap()): Boolean {
        val movies = JSONArray()
        val shows = LinkedHashMap<String, LinkedHashMap<Int, JSONArray>>()
        fun JSONObject.watchedAt(id: String) = apply {
            at[id]?.takeIf { on && it > 0 }?.let { put("watched_at", java.time.Instant.ofEpochMilli(it).toString()) }
        }
        for ((id, metaId) in items.filter { it.first.startsWith("tt") }) {
            val parts = id.split(':')
            if (parts.size < 3) movies.put(JSONObject().put("ids", JSONObject().put("imdb", id)).watchedAt(id))
            else shows.getOrPut(metaId) { LinkedHashMap() }.getOrPut(parts[1].toIntOrNull() ?: continue) { JSONArray() }
                .put(JSONObject().put("number", parts[2].toIntOrNull() ?: continue).watchedAt(id))
        }
        val showList = JSONArray(shows.map { (metaId, seasons) -> JSONObject().put("ids", JSONObject().put("imdb", metaId))
            .put("seasons", JSONArray(seasons.map { (n, eps) -> JSONObject().put("number", n).put("episodes", eps) })) })
        if (movies.length() == 0 && showList.length() == 0) return true
        return runCatching { call(dir, "POST", if (on) "/sync/history" else "/sync/history/remove", JSONObject().put("movies", movies).put("shows", showList)) }
            .getOrNull()?.first in 200..299
    }

    // ---------- what Trakt tells Zephyr TV ----------

    /** Films and episodes watched, as VodWatched keeps them (with when, and the title's name). Null when Trakt can't be asked. */
    fun watched(dir: File): List<VodWatched.Mark>? {
        val movies = call(dir, "GET", "/sync/watched/movies")
        if (refused(movies)) throw SignedOut()
        val shows = call(dir, "GET", "/sync/watched/shows?extended=progress")
        if (movies?.first != 200 || shows?.first != 200) return null
        return parseWatched(JSONArray(movies.second), JSONArray(shows.second))
    }

    /** Trakt refused the account (see [refused]): it has to be connected again. */
    class SignedOut : Exception("Trakt refused the account")

    internal fun parseWatched(movies: JSONArray, shows: JSONArray): List<VodWatched.Mark> {
        val out = ArrayList<VodWatched.Mark>()
        for (m in objects(movies)) {
            val movie = m.optJSONObject("movie")
            imdb(movie)?.let { out += VodWatched.Mark(it, it, time(m.optString("last_watched_at")), movie?.optString("title").orEmpty()) }
        }
        for (s in objects(shows)) {
            val show = imdb(s.optJSONObject("show")) ?: continue
            val name = s.optJSONObject("show")?.optString("title").orEmpty()
            val reset = s.optString("reset_at").takeIf { it.isNotBlank() && it != "null" } // a rewatch began: earlier ones don't count
            val showTime = time(s.optString("last_watched_at"))
            var count = 0
            for (season in objects(s.optJSONArray("seasons"))) for (e in objects(season.optJSONArray("episodes"))) {
                val at = e.optString("last_watched_at").takeIf { it.isNotBlank() && it != "null" }
                if (reset != null && at != null && at < reset) continue
                val epTime = at?.let { time(it) }?.takeIf { it > 0 } ?: showTime
                val sNum = season.optInt("number")
                val eNum = e.optInt("number", e.optInt("episode", 0))
                out += VodWatched.Mark("$show:$sNum:$eNum", show, epTime, name)
                count++
            }
            if (count == 0 && (reset == null || s.optString("last_watched_at") >= reset)) {
                out += VodWatched.Mark("$show:1:1", show, showTime, name)
            }
        }
        return out
    }

    private fun time(iso: String) = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    /** Where a film or episode was paused, elsewhere or here, as Trakt keeps it. */
    internal class Paused(val id: String, val metaId: String, val series: Boolean, val progress: Double, val runtimeMin: Int, val at: Long,
                          val title: String, val season: Int, val number: Int, val episodeTitle: String)

    fun paused(dir: File): List<Paused>? {
        val (code, body) = call(dir, "GET", "/sync/playback?extended=full") ?: return null
        return if (code == 200) parsePaused(JSONArray(body)) else null
    }

    internal fun parsePaused(list: JSONArray): List<Paused> = objects(list).mapNotNull { p ->
        val at = time(p.optString("paused_at"))
        val progress = p.optDouble("progress", 0.0)
        if (p.optString("type") == "episode") {
            val show = p.optJSONObject("show") ?: return@mapNotNull null
            val e = p.optJSONObject("episode") ?: return@mapNotNull null
            val id = imdb(show) ?: return@mapNotNull null
            Paused("$id:${e.optInt("season")}:${e.optInt("number")}", id, true, progress, e.optInt("runtime", show.optInt("runtime")), at,
                show.optString("title"), e.optInt("season"), e.optInt("number"), e.optString("title").takeIf { it != "null" }.orEmpty())
        } else {
            val m = p.optJSONObject("movie") ?: return@mapNotNull null
            val id = imdb(m) ?: return@mapNotNull null
            Paused(id, id, false, progress, m.optInt("runtime"), at, m.optString("title"), 0, 0, "")
        }
    }

    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    private fun imdb(o: JSONObject?) = o?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
