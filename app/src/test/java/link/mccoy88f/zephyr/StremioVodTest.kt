package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioVodTest {
    // Cinemeta's shape: catalogs of films and series (one needs a genre: searched, not listed), meta for IMDb ids.
    private val cinemeta = JSONObject("""{"id":"com.linvo.cinemeta","name":"Cinemeta","types":["movie","series"],
        "idPrefixes":["tt"],"resources":["catalog","meta","addon_catalog"],"catalogs":[
          {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action"]},{"name":"search"},{"name":"skip"}]},
          {"type":"series","id":"top","name":"Popular","extra":[{"name":"genre"},{"name":"search"},{"name":"skip"}]},
          {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2026"]}]},
          {"type":"movie","id":"by-year","name":"By year","extra":[{"name":"genre","isRequired":true},{"name":"search"}]}]}""")

    // A stream-only add-on: no catalogs, streams for films and series with IMDb ids.
    private val streamsOnly = JSONObject("""{"id":"org.example.streams","name":"Example Streams","types":["movie","series"],
        "resources":[{"name":"stream","types":["movie","series"],"idPrefixes":["tt","kitsu"]}],"catalogs":[]}""")

    @Test fun readsWhatAnAddOnAnswers() {
        val c = Stremio.addon(Stremio.CINEMETA, cinemeta)
        assertTrue(c.provides("meta", "movie", "tt1254207"))
        assertTrue(c.provides("meta", "series"))
        assertFalse(c.provides("meta", "movie", "kitsu:1")) // only IMDb ids
        assertFalse(c.streamsVod)
        val s = Stremio.addon("https://streams.example/manifest.json", streamsOnly)
        assertTrue(s.streamsVod)
        assertTrue(s.provides("stream", "series", "tt0898266:9:17"))
        assertTrue(s.provides("stream", "movie", "kitsu:12"))
        assertFalse(s.provides("stream", "movie", "yt:abc"))
        assertFalse(s.provides("stream", "tv"))
        assertTrue(s.catalogs.isEmpty() && s.vod.isEmpty())
    }

    @Test fun readsTheFilmAndSeriesCatalogs() {
        val c = Stremio.addon(Stremio.CINEMETA, cinemeta)
        assertTrue(c.catalogs.isEmpty()) // no TV
        assertEquals(listOf("movie/top", "series/top", "movie/by-year"), c.vod.map { "${it.type}/${it.id}" })
        assertTrue(c.vod[0].listed && c.vod[0].search)
        assertFalse(c.vod[2].listed) // a required genre: searched only
        assertEquals("https://v3-cinemeta.strem.io", c.base)
    }

    @Test fun aResourceObjectWithoutPrefixesTakesAnyId() {
        val m = JSONObject("""{"id":"x","name":"X","types":["movie"],"idPrefixes":["tt"],
            "resources":["meta",{"name":"stream","types":["movie"]}]}""")
        val a = Stremio.addon("https://x/manifest.json", m)
        assertTrue(a.provides("stream", "movie", "anything:1")) // the object has its own types and no prefixes
        assertFalse(a.provides("meta", "movie", "anything:1")) // the string takes the manifest's
    }

    @Test fun readsTitlesFromACatalogPage() {
        val titles = Stremio.titles(JSONObject("""{"metas":[
            {"id":"tt1254207","type":"movie","name":"Big Buck Bunny","poster":"https://img/p.jpg","background":"https://img/b.jpg","releaseInfo":"2008"},
            {"id":"tt0898266","type":"series","name":"The Big Bang Theory","poster":"https://img/s.jpg","releaseInfo":"2007–2019"},
            {"id":"tv|x","type":"tv","name":"A channel"},
            {"id":"tt1","type":"movie","name":" "}]}"""))
        assertEquals(listOf("Big Buck Bunny", "The Big Bang Theory"), titles.map { it.name })
        assertEquals("https://img/b.jpg", titles[0].background)
        assertNull(titles[1].background)
        assertEquals("2007–2019", titles[1].year)
    }

    @Test fun readsASeriesPageWithItsEpisodesInOrder() {
        val meta = Stremio.titleMeta(JSONObject("""{"meta":{"id":"tt0898266","type":"series","name":"The Big Bang Theory",
            "description":"Two physicists.","genres":["Comedy"],"imdbRating":"8.2","videos":[
              {"id":"tt0898266:2:1","season":2,"episode":1,"name":"The Bad Fish Paradigm","released":"2008-09-22T00:00:00.000Z"},
              {"id":"tt0898266:0:1","season":0,"episode":1,"name":"Special"},
              {"id":"tt0898266:1:2","season":1,"episode":2,"title":"The Big Bran Hypothesis","thumbnail":"https://img/e.jpg"},
              {"id":"tt0898266:1:1","season":1,"episode":1,"name":"Pilot"},
              {"id":"tt0898266:1:1","season":1,"episode":1,"name":"Pilot again"},
              {"id":"no-season","name":"?"}]}}"""), "series")!!
        assertEquals("The Big Bang Theory", meta.title.name)
        assertEquals("8.2", meta.rating)
        assertEquals(listOf("tt0898266:1:1", "tt0898266:1:2", "tt0898266:2:1", "tt0898266:0:1"), meta.episodes.map { it.id })
        assertEquals("The Big Bran Hypothesis", meta.episodes[1].name)
    }

    @Test fun aFilmPageHasNoEpisodes() {
        val meta = Stremio.titleMeta(JSONObject("""{"meta":{"id":"tt1254207","type":"movie","name":"Big Buck Bunny",
            "videos":[{"id":"tt1254207","season":1,"episode":1}]}}"""), "movie")!!
        assertTrue(meta.episodes.isEmpty())
        assertNull(Stremio.titleMeta(JSONObject("{}"), "movie"))
    }

    @Test fun readsCastAndDirector() {
        val meta = Stremio.titleMeta(JSONObject("""{"meta":{"id":"tt1375666","type":"movie","name":"Inception",
            "cast":["Leonardo DiCaprio","Joseph Gordon-Levitt"],"director":["Christopher Nolan"]}}"""), "movie")!!
        assertEquals(listOf("Leonardo DiCaprio", "Joseph Gordon-Levitt"), meta.cast)
        assertEquals(listOf("Christopher Nolan"), meta.director)
    }

    @Test fun readsAFilmsStreamsDirectLinksOnly() {
        val streams = Stremio.streams(JSONObject("""{"streams":[
            {"name":"Example\n1080p","description":"Big.Buck.Bunny.2008.1080p.mkv\n💾 1.2 GB","url":"https://cdn.example/play.php?id=7",
             "behaviorHints":{"bingeGroup":"example-1080p","filename":"Big.Buck.Bunny.2008.1080p.mkv"}},
            {"name":"Torrent 4K","title":"Big.Buck.Bunny.2160p","infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":0},
            {"name":"Trailer","ytId":"aqz-KE-bpKQ"},
            {"name":"Elsewhere","externalUrl":"https://example.com/watch"},
            {"title":"Plain","url":"http://files.example/bunny.mp4"}]}"""), vod = true)
        assertEquals(listOf("https://cdn.example/play.php?id=7", "http://files.example/bunny.mp4"), streams.map { it.url })
        assertEquals("Example 1080p · Big.Buck.Bunny.2008.1080p.mkv", streams[0].title)
        assertEquals("example-1080p", streams[0].group)
        assertEquals("Plain", streams[1].title)
    }

    @Test fun anExtensionsYouTubeVideoOpensOnItsWatchPageOnYouTubeForTv() {
        // vnd.youtube: opens YouTube for Android TV on its home screen: the watch link opens the video.
        val s = Stremio.streams(JSONObject("""{"streams":[{"name":"YouTube","ytId":"aqz-KE-bpKQ"},
            {"name":"Other","externalUrl":"https://example.com/watch","behaviorHints":{"androidPackage":"com.example.app"}}]}"""), external = true)
        assertEquals(listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://example.com/watch"), s.map { it.url })
        assertEquals(listOf("com.google.android.youtube.tv", "com.example.app"), s.map { it.app })
    }

    @Test fun shelfKeyMatchesExpectedFormat() {
        val c = Stremio.addon(Stremio.CINEMETA, cinemeta)
        assertEquals("vod:com.linvo.cinemeta:movie:top", shelfKey(c, c.vod[0]))
    }

    @Test fun readsYouTubeChannelVideosAndFiltersChannelOptions() {
        val meta = Stremio.titleMeta(JSONObject("""{"meta":{"id":"yt_id:UC123","type":"YouTube","name":"Test Channel",
            "videos":[
              {"id":"yt_id:UC123:1:1","title":"Channel Options","season":1,"episode":1},
              {"id":"yt_id:video1","title":"Video 1","season":1,"episode":2,"thumbnail":"https://img/1.jpg"},
              {"id":"yt_id:video2","title":"Video 2","episode":3}
            ]}}"""), "YouTube")!!
        assertEquals("Test Channel", meta.title.name)
        assertEquals(listOf("yt_id:video1", "yt_id:video2"), meta.episodes.map { it.id })
        assertEquals("Video 1", meta.episodes[0].name)
        assertEquals("Video 2", meta.episodes[1].name)
    }

    @Test fun youTubeStreamsTargetYouTubeTvApp() {
        val s = Stremio.streams(JSONObject("""{"streams":[
            {"name":"Stremio Player","ytId":"3ScO1QgfysY"},
            {"name":"External Player","externalUrl":"https://www.youtube.com/watch?v=3ScO1QgfysY"},
            {"name":"YT-DLP Channel","externalUrl":"stremio:///discover/https%3A%2F%2Fexample.com"}
        ]}"""), external = true)
        assertEquals(listOf("https://www.youtube.com/watch?v=3ScO1QgfysY", "https://www.youtube.com/watch?v=3ScO1QgfysY"), s.map { it.url })
        assertEquals(listOf("com.google.android.youtube.tv", "com.google.android.youtube.tv"), s.map { it.app })
    }

    @Test fun collectorOfMapsYouTubeToVideos() {
        assertEquals("videos", collectorOf("YouTube"))
        assertEquals("videos", collectorOf("youtube"))
        assertEquals("videos", collectorOf("channel"))
        assertEquals("movies", collectorOf("movie"))
        assertEquals("videos", collectorOf(""))
        assertEquals("videos", collectorOf("unknown"))
        assertEquals("courses", collectorOf("course"))
        assertEquals("podcasts", collectorOf("podcast"))
        assertEquals("audiobooks", collectorOf("audiobook"))
        assertEquals("events", collectorOf("event"))
        assertEquals("radio", collectorOf("radio"))
    }

    @Test fun collectorPrefKeyMapsAllKinds() {
        assertEquals(K.ADDONS_VIDEOS, collectorPrefKey("videos"))
        assertEquals(K.ADDONS_COURSES, collectorPrefKey("courses"))
        assertEquals(K.ADDONS_PODCASTS, collectorPrefKey("podcasts"))
        assertEquals(K.ADDONS_AUDIOBOOKS, collectorPrefKey("audiobooks"))
        assertEquals(K.ADDONS_EVENTS, collectorPrefKey("events"))
        assertEquals(K.RADIO_PLACE, collectorPrefKey("radio"))
    }
}
