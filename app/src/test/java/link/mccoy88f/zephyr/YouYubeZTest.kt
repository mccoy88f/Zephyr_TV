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

    @Test fun netscapeCookiesBecomeACookieHeaderAndUnlockTheFourLists() {
        val netscape = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc\n.youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\txyz"
        val m = run("""module.exports.manifest({settings:{cookies:${JSONObject.quote(netscape)}}})""", emptyMap())
        val ids = (0 until m.getJSONArray("catalogs").length()).map { m.getJSONArray("catalogs").getJSONObject(it).getString("id") }
        assertEquals(listOf("discover", "subs", "later", "history", "search"), ids)

        val seen = ArrayList<JSONObject>()
        run(
            """module.exports.catalog({id:"subs", extra:{}, settings:{cookies:${JSONObject.quote(netscape)}}})""",
            mapOf("https://www.youtube.com/youtubei/v1/browse" to """{"contents":{}}"""),
            seen,
        )
        assertEquals("SID=abc; SAPISID=xyz", seen.single().getJSONObject("headers").getString("Cookie"))
    }
}
