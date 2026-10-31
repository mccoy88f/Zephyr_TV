package link.mccoy88f.zephyr

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URL
import org.json.JSONObject

data class TmdbItem(
    val id: Long,
    val title: String,
    val overview: String,
    val backdropPath: String?,
    val voteAverage: Double,
    val mediaType: String, // "movie" or "tv"
    val genres: List<String>, // names in the language the list was fetched in
    val releaseDate: String?,
    val posterPath: String? = null,
    val imdbId: String? = null, // known already: a title from Cinemeta (see MainActivity.spotlightTitles)
)

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w1280"

    /** Languages offered in settings; the device's own one is picked when it's among them. */
    val LANGUAGES = listOf("it-IT", "en-US", "es-ES", "fr-FR", "de-DE")

    fun defaultLanguage(): String {
        val device = java.util.Locale.getDefault().language
        return LANGUAGES.firstOrNull { it.substringBefore('-') == device } ?: "en-US"
    }

    // Genre names per language ("it-IT" -> id -> name), fetched once: TMDB only sends ids with each title.
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()

    fun fetchTrending(apiKey: String, language: String, mediaType: String = "all"): List<TmdbItem> {
        val type = if (mediaType in setOf("movie", "tv")) mediaType else "all"
        val json = try {
            JSONObject(get(apiKey, "/trending/$type/day?language=$language"))
        } catch (e: Exception) {
            warn("TMDB trending failed (${redact(e)})")
            return emptyList()
        }
        return items(json.optJSONArray("results") ?: return emptyList(), genres(apiKey, language), type)
    }

    /** Films and series matching [query], most relevant first. When matching people, their works are included. Empty when none, or TMDB can't be reached. */
    fun search(apiKey: String, query: String, language: String): List<TmdbItem> {
        val json = try {
            JSONObject(get(apiKey, "/search/multi?language=$language&include_adult=false&query=${Uri.encode(query)}"))
        } catch (e: Exception) {
            warn("TMDB search failed (${redact(e)})")
            return emptyList()
        }
        val results = json.optJSONArray("results") ?: return emptyList()
        return parseSearchResults(results, genres(apiKey, language))
    }

    internal fun parseSearchResults(results: org.json.JSONArray, genres: Map<Int, String>): List<TmdbItem> {
        val out = ArrayList<TmdbItem>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val mediaType = obj.optString("media_type")
            if (mediaType == "person") {
                val knownFor = obj.optJSONArray("known_for")
                if (knownFor != null) {
                    out += items(knownFor, genres, "movie").filter { it.mediaType == "movie" || it.mediaType == "tv" }
                }
            } else if (mediaType == "movie" || mediaType == "tv") {
                val single = org.json.JSONArray().put(obj)
                out += items(single, genres, "movie").filter { it.mediaType == "movie" || it.mediaType == "tv" }
            }
        }
        return out.distinctBy { it.id }
    }

    /**
     * TMDB's ready-made lists Spotlight can show, by key: (path, kind of its titles) for films, then for series. A
     * list that has only one kind (films in cinemas, series on air) has null for the other.
     */
    val CATEGORIES: Map<String, Pair<String?, String?>> = linkedMapOf(
        "trending" to ("/trending/movie/day" to "/trending/tv/day"),
        "trending_week" to ("/trending/movie/week" to "/trending/tv/week"),
        "popular" to ("/movie/popular" to "/tv/popular"),
        "top_rated" to ("/movie/top_rated" to "/tv/top_rated"),
        "now_playing" to ("/movie/now_playing" to null),
        "upcoming" to ("/movie/upcoming" to null),
        "on_the_air" to (null to "/tv/on_the_air"),
    )

    /**
     * The titles in TMDB's list [category] (see CATEGORIES), of [mediaType] ("movie", "tv" or "all": both, taken in
     * turn), for [region] where it matters (what's in cinemas). Empty when TMDB can't be reached.
     */
    fun fetchCategory(apiKey: String, category: String, language: String, mediaType: String, region: String): List<TmdbItem> {
        if (category == "trending" && mediaType !in setOf("movie", "tv")) return fetchTrending(apiKey, language, "all")
        val (films, series) = CATEGORIES[category] ?: return emptyList()
        val genres = genres(apiKey, language)
        fun page(path: String?, type: String, wanted: Boolean): List<TmdbItem> {
            if (path == null || !wanted) return emptyList()
            return try {
                items(JSONObject(get(apiKey, "$path?language=$language&region=$region")).optJSONArray("results") ?: return emptyList(), genres, type)
            } catch (e: Exception) {
                warn("TMDB $category failed (${redact(e)})")
                emptyList()
            }
        }
        val a = page(films, "movie", mediaType != "tv")
        val b = page(series, "tv", mediaType != "movie")
        // Films and series in turn, so neither crowds the other out of the first titles.
        return (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }
    }

    /**
     * The titles in a public TMDB list (a "watchlist" made on themoviedb.org), in its order, up to 5 pages.
     * Empty when the list doesn't exist, isn't public, or TMDB can't be reached.
     */
    fun fetchList(apiKey: String, listId: String, language: String, mediaType: String = "all"): List<TmdbItem> {
        val genres = genres(apiKey, language)
        val out = ArrayList<TmdbItem>()
        for (page in 1..5) {
            val json = try {
                JSONObject(get(apiKey, "/list/$listId?language=$language&page=$page"))
            } catch (e: Exception) {
                warn("TMDB list failed (${redact(e)})")
                break
            }
            out += items(json.optJSONArray("items") ?: break, genres, "movie")
            if (page >= json.optInt("total_pages", 1)) break
        }
        return if (mediaType in setOf("movie", "tv")) out.filter { it.mediaType == mediaType } else out
    }

    /**
     * The list id in what someone pasted: a list's address ("https://www.themoviedb.org/list/8123456-best-films")
     * or just its number. Null when there's none.
     */
    fun listId(text: String): String? {
        val t = text.trim()
        Regex("""/list/(\d+)""").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** An image path, or null: TMDB sends JSON null for a missing one, which optString would turn into "null". */
    private fun path(obj: JSONObject, key: String) = if (obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotBlank() }

    private fun items(results: org.json.JSONArray, genres: Map<Int, String>, defaultType: String): List<TmdbItem> {
        val list = ArrayList<TmdbItem>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val title = obj.optString("title").ifEmpty { obj.optString("name") }
            if (title.isBlank()) continue
            val genreArray = obj.optJSONArray("genre_ids")
            val names = ArrayList<String>()
            if (genreArray != null) {
                for (g in 0 until genreArray.length()) genres[genreArray.getInt(g)]?.let { names.add(it) }
            }
            list.add(
                TmdbItem(
                    id = obj.optLong("id"),
                    title = title,
                    overview = obj.optString("overview"),
                    backdropPath = path(obj, "backdrop_path"),
                    voteAverage = obj.optDouble("vote_average", 0.0),
                    mediaType = obj.optString("media_type", defaultType),
                    genres = names,
                    releaseDate = obj.optString("release_date").ifEmpty { obj.optString("first_air_date") },
                    posterPath = path(obj, "poster_path"),
                )
            )
        }
        return list
    }

    /** Movie and TV genres together (their ids don't clash). Empty on failure, so titles just show no genre. */
    private fun genres(apiKey: String, language: String): Map<Int, String> = genreCache[language] ?: try {
        val map = HashMap<Int, String>()
        for (kind in listOf("movie", "tv")) {
            val arr = JSONObject(get(apiKey, "/genre/$kind/list?language=$language")).getJSONArray("genres")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { map[it.getInt("id")] = it.getString("name") }
        }
        map.also { genreCache[language] = it }
    } catch (e: Exception) {
        warn("TMDB genres failed (${redact(e)})")
        emptyMap()
    }

    /**
     * A film or series as TMDB has it in a language, found by its IMDb id: for the Stremio add-ons' titles, whose
     * catalogs (Cinemeta's) are in English only. [overview] is empty when TMDB has none in that language.
     */
    data class Localized(val tmdbId: Long, val tv: Boolean, val title: String, val overview: String, val poster: String?,
                         val backdrop: String?, val genres: List<String>, val year: String?, val vote: Double = 0.0)

    /** One episode as TMDB has it in a language: its name, what it's about, its still. */
    data class LocalEpisode(val name: String, val overview: String, val still: String?)

    /** [imdbId]'s title in [language], or null when TMDB doesn't know it. Throws when TMDB can't be asked. */
    fun localized(apiKey: String, imdbId: String, language: String): Localized? =
        localized(JSONObject(get(apiKey, "/find/${Uri.encode(imdbId)}?external_source=imdb_id&language=$language")), genres(apiKey, language))

    internal fun localized(answer: JSONObject, genres: Map<Int, String>): Localized? {
        val movie = answer.optJSONArray("movie_results")?.optJSONObject(0)
        val r = movie ?: answer.optJSONArray("tv_results")?.optJSONObject(0) ?: return null
        val tv = movie == null
        val title = r.optString(if (tv) "name" else "title").takeIf { it.isNotBlank() } ?: return null
        val ids = r.optJSONArray("genre_ids")
        val names = if (ids == null) emptyList() else (0 until ids.length()).mapNotNull { genres[ids.optInt(it)] }
        fun path(name: String) = r.optString(name).takeIf { it.isNotBlank() && it != "null" }
        return Localized(r.optLong("id"), tv, title, r.optString("overview").trim(), path("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            cardUrl(path("backdrop_path")), names, r.optString(if (tv) "first_air_date" else "release_date").take(4).takeIf { it.length == 4 },
            r.optDouble("vote_average", 0.0))
    }

    /** A season's episodes in [language], by their number. Throws when TMDB can't be asked. */
    fun seasonEpisodes(apiKey: String, tvId: Long, season: Int, language: String): Map<Int, LocalEpisode> =
        seasonEpisodes(JSONObject(get(apiKey, "/tv/$tvId/season/$season?language=$language")))

    internal fun seasonEpisodes(answer: JSONObject): Map<Int, LocalEpisode> {
        val list = answer.optJSONArray("episodes") ?: return emptyMap()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate { e ->
            e.optInt("episode_number") to LocalEpisode(e.optString("name").trim(), e.optString("overview").trim(),
                e.optString("still_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w300$it" })
        }
    }

    // A whole address (a Cinemeta title's background) is used as it is.
    fun backdropUrl(path: String?): String? = if (path.isNullOrBlank()) null else if (path.startsWith("http")) path else "$IMAGE_BASE$path"

    /** The same image at card size. */
    fun cardUrl(path: String?): String? = if (path.isNullOrBlank()) null else if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w780$path"

    /** Profile photo URL for an avatar: 185px wide from TMDB. */
    fun profileUrl(path: String?): String? = if (path.isNullOrBlank() || path == "null") null else if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w185$path"

    // "movie/123" -> (directors, cast)
    private val creditsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Pair<String, String?>>, List<Pair<String, String?>>>>()
    private val personPhotoCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Directors and cast for [tmdbId] ([isTv] for series, else film).
     * Pair of (directors, cast) where each element is (Name, profilePhotoUrl or null).
     */
    fun credits(apiKey: String, tmdbId: Long, isTv: Boolean): Pair<List<Pair<String, String?>>, List<Pair<String, String?>>> {
        val type = if (isTv) "tv" else "movie"
        val key = "$type/$tmdbId"
        creditsCache[key]?.let { return it }
        val res = try {
            credits(JSONObject(get(apiKey, "/$type/$tmdbId/credits")))
        } catch (e: Exception) {
            warn("TMDB credits failed (${redact(e)})")
            emptyList<Pair<String, String?>>() to emptyList<Pair<String, String?>>()
        }
        creditsCache[key] = res
        return res
    }

    internal fun credits(json: JSONObject): Pair<List<Pair<String, String?>>, List<Pair<String, String?>>> {
        val crewArr = json.optJSONArray("crew")
        val castArr = json.optJSONArray("cast")

        val directors = ArrayList<Pair<String, String?>>()
        if (crewArr != null) {
            for (i in 0 until crewArr.length()) {
                val o = crewArr.optJSONObject(i) ?: continue
                val job = o.optString("job")
                if (job.equals("Director", ignoreCase = true)) {
                    val name = o.optString("name").trim()
                    if (name.isNotEmpty() && directors.none { it.first.equals(name, ignoreCase = true) }) {
                        val photo = profileUrl(path(o, "profile_path"))
                        directors.add(name to photo)
                        if (photo != null) personPhotoCache[name.lowercase()] = photo
                    }
                }
            }
        }

        val cast = ArrayList<Pair<String, String?>>()
        if (castArr != null) {
            for (i in 0 until castArr.length()) {
                val o = castArr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isNotEmpty() && cast.none { it.first.equals(name, ignoreCase = true) }) {
                    val photo = profileUrl(path(o, "profile_path"))
                    cast.add(name to photo)
                    if (photo != null) personPhotoCache[name.lowercase()] = photo
                }
            }
        }
        return directors to cast
    }

    /** Profile photo URL from TMDB for [personName], or null. Network: call off the UI thread. */
    fun personPhoto(apiKey: String, personName: String): String? {
        val norm = personName.trim().lowercase()
        if (norm.isEmpty()) return null
        personPhotoCache[norm]?.let { return it }
        val found = try {
            val json = JSONObject(get(apiKey, "/search/person?query=${Uri.encode(personName)}&include_adult=false"))
            val results = json.optJSONArray("results")
            val path = results?.optJSONObject(0)?.let { path(it, "profile_path") }
            profileUrl(path)
        } catch (e: Exception) {
            warn("TMDB person search failed (${redact(e)})")
            null
        }
        if (found != null) personPhotoCache[norm] = found
        return found
    }

    /** Known photo URL already cached in memory for [personName], without network. */
    fun cachedPersonPhoto(personName: String): String? = personPhotoCache[personName.trim().lowercase()]

    /**
     * Titles associated with [personName] (as director or cast member) in [language]. Network: call off the UI thread.
     */
    fun personCredits(apiKey: String, personName: String, language: String, isDirector: Boolean): List<TmdbItem> {
        val norm = personName.trim()
        if (norm.isEmpty()) return emptyList()
        return try {
            val searchJson = JSONObject(get(apiKey, "/search/person?query=${Uri.encode(norm)}&include_adult=false&language=$language"))
            val personId = searchJson.optJSONArray("results")?.optJSONObject(0)?.optLong("id") ?: return emptyList()
            val creditsJson = JSONObject(get(apiKey, "/person/$personId/combined_credits?language=$language"))
            parsePersonCredits(creditsJson, genres(apiKey, language), isDirector)
        } catch (e: Exception) {
            warn("TMDB person credits failed (${redact(e)})")
            emptyList()
        }
    }

    internal fun parsePersonCredits(json: JSONObject, genres: Map<Int, String>, isDirector: Boolean): List<TmdbItem> {
        val targetArray = if (isDirector) {
            val crew = json.optJSONArray("crew") ?: return emptyList()
            val filtered = org.json.JSONArray()
            for (i in 0 until crew.length()) {
                val o = crew.optJSONObject(i) ?: continue
                val job = o.optString("job")
                val dept = o.optString("department")
                if (job.equals("Director", ignoreCase = true) || dept.equals("Directing", ignoreCase = true)) {
                    filtered.put(o)
                }
            }
            filtered
        } else {
            json.optJSONArray("cast") ?: return emptyList()
        }
        return items(targetArray, genres, "movie")
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .distinctBy { it.id }
            .sortedByDescending { it.releaseDate ?: "" }
    }

    // Nuvio's builds: GitHub ("full") and Play Store. Both take nuvio://tmdb/<type>/<id> and have no search link
    // (their stremio:// handler only installs addons).
    private val NUVIO = setOf("com.nuvio.tv", "com.nuvio.app")
    private const val STREMIO = "com.stremio.one"
    private const val YOUTUBE = "com.google.android.youtube.tv"

    // TMDB id -> IMDb id (or "" when TMDB has none), so a second press doesn't ask again.
    private val imdbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // TMDB id -> YouTube key of its best trailer (or "" when there's none), per language.
    private val trailerCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Streaming services TMDB lists for a title (its "watch providers", by provider id) and their Android TV apps.
     * Only well-known services: an app missing here is simply always offered, like any app TMDB doesn't know.
     */
    private val PROVIDER_APPS: Map<Int, String> = mapOf(
        8 to "com.netflix.ninja", 1796 to "com.netflix.ninja", // Netflix, Netflix with ads
        9 to "com.amazon.amazonvideo.livingroom", 119 to "com.amazon.amazonvideo.livingroom", // Prime Video
        337 to "com.disney.disneyplus",
        2 to "com.apple.atve.androidtv.appletv", 350 to "com.apple.atve.androidtv.appletv", // Apple TV store, Apple TV+
        531 to "com.cbs.ott", // Paramount+
        283 to "com.crunchyroll.crunchyroid",
        384 to "com.wbd.stream", 1899 to "com.wbd.stream", // HBO Max, Max
    )
    private val providerPkgs = PROVIDER_APPS.values.toSet()

    /** The services' apps, for Spotlight to offer when they're installed. */
    val providerApps: Set<String> get() = providerPkgs

    /**
     * Whether Spotlight offers [pkg] for a title that TMDB lists on the apps in [available]. A standard service's
     * app only when the title is on it; everything else always: Nuvio and Stremio find titles through their addons
     * wherever they are, YouTube is the trailer button, and other apps TMDB doesn't know about. [available] null
     * means not known (yet, or offline): then every app is offered, as before.
     */
    fun offers(pkg: String, available: Set<String>?) = available == null || pkg !in providerPkgs || pkg in available

    // "movie/123/IT" -> apps of the services that have it there.
    private val providerCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** The country whose services count: the TV's, else the one in the TMDB language ("it-IT" -> IT). */
    fun region(language: String) = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
        ?: language.substringAfter('-', "US").uppercase()

    /** Already-known [providers] for [item] in [region], without asking TMDB. */
    fun cachedProviders(item: TmdbItem, region: String) = providerCache["${item.mediaType}/${item.id}/$region"]

    /**
     * The apps of the services TMDB lists for [item] in [region], to stream, rent or buy; null when TMDB couldn't be
     * asked. Network: call it off the UI thread.
     */
    fun providers(apiKey: String, item: TmdbItem, region: String): Set<String>? {
        val key = "${item.mediaType}/${item.id}/$region"
        providerCache[key]?.let { return it }
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val found = try {
            val here = JSONObject(get(apiKey, "/$type/${item.id}/watch/providers")).optJSONObject("results")?.optJSONObject(region)
            val apps = HashSet<String>()
            for (kind in listOf("flatrate", "free", "ads", "rent", "buy")) {
                val list = here?.optJSONArray(kind) ?: continue
                for (i in 0 until list.length()) PROVIDER_APPS[list.getJSONObject(i).optInt("provider_id")]?.let { apps.add(it) }
            }
            apps
        } catch (e: Exception) {
            warn("TMDB providers failed (${redact(e)})")
            return null
        }
        providerCache[key] = found
        return found
    }

    /**
     * Apps whose title links need something looked up first: Stremio the IMDb id (its catalogue, Cinemeta, is
     * keyed by it), YouTube the trailer TMDB lists for the title.
     */
    fun needsLookup(pkg: String) = pkg == STREMIO || pkg == YOUTUBE

    /** What [openIntent] needs for [pkg] as its `ref`, or null. Network: call it off the UI thread. */
    /** What [lookup] would find for [pkg] that's known already, without TMDB: a Cinemeta title's IMDb id, for Stremio. */
    fun knownRef(pkg: String, item: TmdbItem): String? = if (pkg == STREMIO) item.imdbId else null

    fun lookup(apiKey: String, pkg: String, item: TmdbItem, language: String): String? = when (pkg) {
        STREMIO -> imdbId(apiKey, item)
        YOUTUBE -> trailerKey(apiKey, item, language)
        else -> null
    }

    /** A video TMDB lists for a title: the same trailers and teasers its website shows. */
    class Video(val key: String, val site: String, val type: String, val official: Boolean, val language: String, val published: String)

    /**
     * The trailer to play: YouTube only (the TV app plays those), a trailer before a teaser, in [language] before
     * English before anything else, official before fan-made, newest first. Null when there's no trailer or teaser.
     */
    fun bestTrailer(videos: List<Video>, language: String): Video? {
        val lang = language.substringBefore('-')
        return videos.filter { it.site == "YouTube" && it.key.isNotBlank() && (it.type == "Trailer" || it.type == "Teaser") }
            .sortedWith(compareBy<Video>(
                { if (it.type == "Trailer") 0 else 1 },
                { when (it.language) { lang -> 0; "en" -> 1; else -> 2 } },
                { if (it.official) 0 else 1 },
            ).thenByDescending { it.published })
            .firstOrNull()
    }

    /** The YouTube key of the title's trailer, or null. Network: call it off the UI thread. */
    private fun trailerKey(apiKey: String, item: TmdbItem, language: String): String? {
        val key = "${item.mediaType}/${item.id}/$language"
        trailerCache[key]?.let { return it.ifEmpty { null } }
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val lang = language.substringBefore('-')
        val videos = try {
            // Videos in the chosen language plus English ones, as a fallback when there's no dubbed trailer.
            val list = JSONObject(get(apiKey, "/$type/${item.id}/videos?language=$language&include_video_language=$lang,en"))
                .optJSONArray("results")
            (0 until (list?.length() ?: 0)).map { i ->
                val v = list!!.getJSONObject(i)
                Video(v.optString("key"), v.optString("site"), v.optString("type"), v.optBoolean("official"),
                    v.optString("iso_639_1"), v.optString("published_at"))
            }
        } catch (e: Exception) {
            warn("TMDB videos failed (${redact(e)})")
            return null // not cached: a network hiccup shouldn't stick
        }
        val best = bestTrailer(videos, language)?.key
        trailerCache[key] = best.orEmpty()
        return best
    }

    /** The title's IMDb id ("tt…"), or null, for the Stremio add-ons. Network: call it off the UI thread. */
    fun imdbIdOf(apiKey: String, item: TmdbItem): String? = imdbId(apiKey, item)

    /** [item]'s IMDb id when it's known already (a Stremio title's), so it isn't asked of TMDB. */
    fun knowImdbId(item: TmdbItem, imdb: String) { imdbCache["${item.mediaType}/${item.id}"] = imdb }

    /** The title's IMDb id ("tt…"), or null. Network: call it off the UI thread. */
    private fun imdbId(apiKey: String, item: TmdbItem): String? {
        item.imdbId?.let { return it }
        val key = "${item.mediaType}/${item.id}"
        imdbCache[key]?.let { return it.ifEmpty { null } }
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val id = try {
            JSONObject(get(apiKey, "/$type/${item.id}/external_ids")).optString("imdb_id").takeIf { it.startsWith("tt") }
        } catch (e: Exception) {
            warn("TMDB external ids failed (${redact(e)})")
            return null // not cached: a network hiccup shouldn't stick
        }
        imdbCache[key] = id.orEmpty()
        return id
    }

    /**
     * Opens [item] in [pkg]: on its own page where the app takes an id (Nuvio: TMDB id; Stremio: the IMDb id in
     * [ref]); on YouTube the trailer TMDB lists ([ref], its video key), or a search for one in [language] when it
     * lists none; otherwise through the app's search by title, otherwise the app itself. [ref] comes from [lookup].
     */
    fun openIntent(context: Context, pkg: String, item: TmdbItem, ref: String?, language: String): Intent {
        val pm = context.packageManager
        for (uri in titleLinks(pkg, item, ref)) viewIn(pm, pkg, uri)?.let { return it }
        return searchIntent(context, pkg, if (isYouTube(pkg)) trailerQuery(item.title, language) else item.title)
    }

    /** The links that open [item] directly in [pkg], best first; empty for apps without one (they search). */
    fun titleLinks(pkg: String, item: TmdbItem, ref: String?): List<String> {
        val series = item.mediaType == "tv"
        return when {
            pkg in NUVIO -> listOf("nuvio://tmdb/${if (series) "series" else "movie"}/${item.id}")
            // Movies open on the title (the video id is the title's own id); series on their episode list.
            pkg == STREMIO && ref != null -> listOf(if (series) "stremio:///detail/series/$ref" else "stremio:///detail/movie/$ref/$ref")
            // The watch link: YouTube for Android TV takes a vnd.youtube: link as a plain app launch and drops the
            // video id (it opens on its home screen), so that one is only the fallback.
            pkg == YOUTUBE && ref != null -> listOf("https://www.youtube.com/watch?v=$ref", "vnd.youtube:$ref")
            else -> emptyList()
        }
    }

    /** YouTube's Spotlight button is a trailer button: it plays TMDB's trailer, or searches "<title> trailer <language>". */
    fun isYouTube(pkg: String) = pkg == YOUTUBE

    /** "Dune trailer italiano": the language named in itself, so YouTube ranks the dubbed or subtitled cut first. */
    fun trailerQuery(title: String, language: String): String {
        val name = when (language.substringBefore('-')) {
            "it" -> "italiano"
            "es" -> "español"
            "fr" -> "français"
            "de" -> "deutsch"
            else -> "english"
        }
        return "$title trailer $name"
    }

    /** An ACTION_VIEW of [uri] for [pkg] only, or null when the app doesn't take that link. */
    /**
     * Whether TMDB accepts [apiKey]: true, false (it refused it), or null when TMDB couldn't be asked (no network).
     * Network: call it off the UI thread.
     */
    fun check(apiKey: String): Boolean? = try {
        val bearer = apiKey.length > 40
        val url = if (bearer) "$BASE_URL/configuration" else "$BASE_URL/configuration?api_key=" + Uri.encode(apiKey)
        val conn = Http.connect(url, if (bearer) mapOf("Authorization" to "Bearer $apiKey") else emptyMap())
        when (conn.responseCode) {
            in 200..299 -> true
            401, 403 -> false
            else -> null
        }.also { conn.disconnect() }
    } catch (e: Exception) {
        warn("TMDB key check failed (${redact(e)})")
        null
    }

    /**
     * An error's text without the API key: a failed request's exception names its URL, and a v3 key travels in
     * it as a parameter, so logging it plainly would put the key in the TV's log for any app with log access.
     */
    internal fun redact(e: Throwable) = Problems.redactKeys(e.toString())

    private fun viewIn(pm: android.content.pm.PackageManager, pkg: String, uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.takeIf { it.resolveActivity(pm) != null }

    private fun searchIntent(context: Context, pkg: String, query: String): Intent {
        val pm = context.packageManager
        fun view(uri: String) = viewIn(pm, pkg, uri)
        val q = Uri.encode(query)
        // Specialized intents where known
        when (pkg) {
            STREMIO -> view("stremio:///search?search=$q")?.let { return it } // documented in Stremio's addon SDK
            "com.netflix.ninja" -> {
                Intent("android.intent.action.SEARCH").apply {
                    setPackage(pkg)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(pm) != null }?.let { return it }
            }
            YOUTUBE -> view("https://www.youtube.com/results?search_query=$q")?.let { return it }
        }

        // Generic search intent
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(pkg)
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(pm) != null) return searchIntent

        // Fallback: regular launch intent for the package
        return pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Accepts either kind of TMDB credential: a v4 read access token (a long JWT, sent as a Bearer header,
     * so it stays out of the URL) or a classic 32-character v3 API key (which TMDB only takes as a parameter).
     */
    internal fun get(apiKey: String, path: String): String {
        val bearer = apiKey.length > 40
        val url = if (bearer) BASE_URL + path
            else BASE_URL + path + (if ('?' in path) "&" else "?") + "api_key=" + Uri.encode(apiKey)
        return Http.text(url, if (bearer) mapOf("Accept" to "application/json", "Authorization" to "Bearer $apiKey")
            else mapOf("Accept" to "application/json"))
    }
}
