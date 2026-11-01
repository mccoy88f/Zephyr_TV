package link.mccoy88f.zephyr

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class YouYubeZTest {
    private val script: String by lazy {
        val f = listOf("../Zephyr-TV-Extensions/youyubez/main.js", "../../Zephyr-TV-Extensions/youyubez/main.js")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("YouYubeZ lives next to this repository", f != null)
        f!!.readText()
    }

    private fun run(call: String, pages: Map<String, String>, seen: MutableList<JSONObject> = ArrayList()): JSONObject {
        val fetch: (JSONObject) -> Js.Fetched = { req ->
            seen += req
            val body = pages.entries.firstOrNull { req.getString("url").startsWith(it.key) }?.value
            if (body == null) Js.Fetched(404, "Not Found", req.getString("url"), emptyList(), ByteArray(0), null)
            else Js.Fetched(200, "OK", req.getString("url"), emptyList(), body.toByteArray(), "UTF-8")
        }
        return JSONObject(Js.run(script, call, timeoutMs = 8_000, fetch = fetch)!!)
    }

    @Test fun searchReturnsVideoRenderers() {
        val page = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"dQw4w9WgXcQ",
              "title":{"simpleText":"Rick Astley"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","width":480}]}}}]}}]}}}}}"""
        val out = run(
            """module.exports.catalog({id:"search", extra:{search:"rick"}, settings:{}})""",
            mapOf("https://www.youtube.com/youtubei/v1/search" to page),
        )
        val metas = out.getJSONArray("metas")
        assertEquals(1, metas.length())
        assertEquals("ytz:dQw4w9WgXcQ", metas.getJSONObject(0).getString("id"))
        assertEquals("Rick Astley", metas.getJSONObject(0).getString("name"))
        assertEquals("video", metas.getJSONObject(0).getString("type"))
    }

    @Test fun searchPutsChannelDateAndSnippetOnCatalogItems() {
        val page = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"dQw4w9WgXcQ",
              "title":{"simpleText":"Never Gonna Give You Up"},
              "ownerText":{"runs":[{"text":"Rick Astley"}]},
              "publishedTimeText":{"simpleText":"13 years ago"},
              "descriptionSnippet":{"runs":[{"text":"The official video."}]},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","width":480}]}}}]}}]}}}}}"""
        val out = run(
            """module.exports.catalog({id:"search", extra:{search:"rick"}, settings:{}})""",
            mapOf("https://www.youtube.com/youtubei/v1/search" to page),
        )
        val m = out.getJSONArray("metas").getJSONObject(0)
        assertEquals("Rick Astley", m.getJSONArray("director").getString(0))
        assertEquals("Rick Astley", m.getJSONArray("genres").getString(0))
        assertEquals("13 years ago", m.getString("releaseInfo"))
        assertEquals("The official video.", m.getString("description"))
    }

    @Test fun metaReadsChannelDateAndDescriptionFromPlayer() {
        val player = """{"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna Give You Up","author":"Rick Astley",
            "shortDescription":"The official video.","lengthSeconds":"213",
            "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","width":480}]}},
            "microformat":{"playerMicroformatRenderer":{"ownerChannelName":"Rick Astley","publishDate":"2009-10-25T07:57:33-07:00",
            "description":{"simpleText":"Official music video for “Never Gonna Give You Up”"}}}}"""
        val seen = ArrayList<JSONObject>()
        val out = run(
            """module.exports.meta({id:"ytz:dQw4w9WgXcQ", settings:{}})""",
            mapOf("https://www.youtube.com/youtubei/v1/player" to player),
            seen,
        )
        val m = out.getJSONObject("meta")
        assertEquals("Never Gonna Give You Up", m.getString("name"))
        assertEquals("Official music video for “Never Gonna Give You Up”", m.getString("description"))
        assertEquals("Rick Astley", m.getJSONArray("genres").getString(0))
        assertEquals("Rick Astley", m.getJSONArray("director").getString(0))
        assertEquals("2009-10-25", m.getString("releaseInfo"))
        assertEquals("3:33", m.getString("runtime"))
        assertTrue(seen.none { it.getString("url").contains("/next") || it.getString("url").contains("oembed") })
    }

    @Test fun metaFallsBackToNextThenOembedAndSendsCookies() {
        val next = """{"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
            {"videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"From next"}]},"dateText":{"simpleText":"Oct 25, 2009"}}},
            {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"title":{"simpleText":"Channel Z"}}},
              "attributedDescription":{"content":"A longer description."}}}]}}}}}"""
        val netscape = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc"
        val seen = ArrayList<JSONObject>()
        val out = run(
            """module.exports.meta({id:"ytz:dQw4w9WgXcQ", settings:{cookies:${JSONObject.quote(netscape)}}})""",
            mapOf(
                "https://www.youtube.com/youtubei/v1/player" to "{}",
                "https://www.youtube.com/youtubei/v1/next" to next,
            ),
            seen,
        )
        val m = out.getJSONObject("meta")
        assertEquals("From next", m.getString("name"))
        assertEquals("A longer description.", m.getString("description"))
        assertEquals("Channel Z", m.getJSONArray("genres").getString(0))
        assertEquals("Channel Z", m.getJSONArray("director").getString(0))
        assertEquals("Oct 25, 2009", m.getString("releaseInfo"))
        assertTrue(seen.filter { it.getString("url").startsWith("https://www.youtube.com/youtubei/v1/player") }
            .any { it.optJSONObject("headers")?.optString("Cookie") == "SID=abc" })

        val oembed = run(
            """module.exports.meta({id:"ytz:dQw4w9WgXcQ", settings:{}})""",
            mapOf(
                "https://www.youtube.com/youtubei/v1/player" to "{}",
                "https://www.youtube.com/youtubei/v1/next" to "{}",
                "https://www.youtube.com/oembed" to """{"title":"Oembed title","author_name":"Oembed channel","thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}""",
            ),
        ).getJSONObject("meta")
        assertEquals("Oembed title", oembed.getString("name"))
        assertEquals("Oembed channel", oembed.getJSONArray("genres").getString(0))
        assertEquals("Oembed channel", oembed.getJSONArray("director").getString(0))
        assertTrue(!oembed.has("description") || oembed.optString("description").isEmpty())
    }

    @Test fun streamReturnsADirectUrlBesideTheYoutubeApp() {
        val player = """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
            {"itag":18,"qualityLabel":"360p","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
             "audioQuality":"AUDIO_QUALITY_LOW","url":"https://rr1.googlevideo.com/videoplayback?id=1"}]}}"""
        val seen = ArrayList<JSONObject>()
        val out = run(
            """module.exports.stream({id:"ytz:dQw4w9WgXcQ", settings:{play:"both"}})""",
            mapOf("https://www.youtube.com/youtubei/v1/player" to player),
            seen,
        )
        val streams = out.getJSONArray("streams")
        assertEquals(2, streams.length())
        val zephyr = streams.getJSONObject(0)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=1", zephyr.getString("url"))
        assertEquals("360p video/mp4", zephyr.getString("title"))
        assertTrue(!zephyr.has("ytId") || zephyr.optString("ytId").isEmpty())
        assertEquals("dQw4w9WgXcQ", streams.getJSONObject(1).getString("ytId"))
        val req = seen.first { it.getString("url").startsWith("https://www.youtube.com/youtubei/v1/player") }
        assertTrue(req.getString("body").contains("ANDROID_VR"))
        val cookieReqs = seen.filter { it.getString("url").startsWith("https://www.youtube.com/youtubei/v1/player") }
        assertTrue(cookieReqs.first().optJSONObject("headers")?.optString("Cookie").isNullOrEmpty())
    }

    @Test fun streamAsksThePlayerWithoutCookiesFirst() {
        val player = """{"streamingData":{"formats":[{"itag":18,"qualityLabel":"360p","mimeType":"video/mp4; codecs=\"avc1, mp4a\"",
            "url":"https://rr1.googlevideo.com/videoplayback?id=1"}]}}"""
        val netscape = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc"
        val seen = ArrayList<JSONObject>()
        val out = run(
            """module.exports.stream({id:"ytz:dQw4w9WgXcQ", settings:{play:"zephyr", cookies:${JSONObject.quote(netscape)}}})""",
            mapOf("https://www.youtube.com/youtubei/v1/player" to player),
            seen,
        )
        assertEquals(1, out.getJSONArray("streams").length())
        val playerReqs = seen.filter { it.getString("url").startsWith("https://www.youtube.com/youtubei/v1/player") }
        assertEquals(1, playerReqs.size)
        assertTrue(playerReqs[0].optJSONObject("headers")?.optString("Cookie").isNullOrEmpty())
    }

    @Test fun catalogsWithoutCookiesStillListDiscoverAndTrending() {
        val m = run("""module.exports.manifest({settings:{}})""", emptyMap())
        val ids = (0 until m.getJSONArray("catalogs").length()).map { m.getJSONArray("catalogs").getJSONObject(it).getString("id") }
        assertEquals(listOf("discover", "trending", "search"), ids)
        assertTrue(m.getJSONArray("catalogs").getJSONObject(0).optJSONArray("extra")?.let { extra ->
            (0 until extra.length()).none { extra.getJSONObject(it).optBoolean("isRequired") }
        } == true)
    }

    @Test fun netscapeCookiesBecomeACookieHeaderAndUnlockTheFourLists() {
        val netscape = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc\n.youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\txyz"
        val m = run("""module.exports.manifest({settings:{cookies:${JSONObject.quote(netscape)}}})""", emptyMap())
        val ids = (0 until m.getJSONArray("catalogs").length()).map { m.getJSONArray("catalogs").getJSONObject(it).getString("id") }
        assertEquals(listOf("discover", "subs", "later", "history", "trending", "search"), ids)

        val seen = ArrayList<JSONObject>()
        run(
            """module.exports.catalog({id:"subs", extra:{}, settings:{cookies:${JSONObject.quote(netscape)}}})""",
            mapOf("https://www.youtube.com/youtubei/v1/browse" to """{"contents":{"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"simpleText":"x"}}}}"""),
            seen,
        )
        assertEquals("SID=abc; SAPISID=xyz", seen.single().getJSONObject("headers").getString("Cookie"))
    }

    @Test fun discoverFallsBackToAndroidWhenWebListsNothing() {
        val androidPage = """{"contents":{"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"simpleText":"Rick"}}}}"""
        val seen = ArrayList<JSONObject>()
        val fetch: (JSONObject) -> Js.Fetched = { req ->
            seen += req
            val ua = req.optJSONObject("headers")?.optString("User-Agent") ?: ""
            val body = if (ua.contains("com.google.android.youtube/")) androidPage else "{}"
            Js.Fetched(200, "OK", req.getString("url"), emptyList(), body.toByteArray(), "UTF-8")
        }
        val out = JSONObject(Js.run(script, """module.exports.catalog({id:"discover", extra:{}, settings:{}})""", timeoutMs = 8_000, fetch = fetch)!!)
        assertEquals(1, out.getJSONArray("metas").length())
        assertEquals("ytz:dQw4w9WgXcQ", out.getJSONArray("metas").getJSONObject(0).getString("id"))
        assertTrue(seen.any { it.optJSONObject("headers")?.optString("User-Agent").orEmpty().contains("com.google.android.youtube/") })
    }
}
