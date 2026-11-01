package link.mccoy88f.zephyr

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live InnerTube through QuickJS + Http, as the TV runs YouYubeZ. Skipped when the cookie file is not on this machine. */
class YouYubeZLiveTest {
    private val script: String by lazy {
        val f = listOf("../Zephyr-TV-Extensions/youyubez/main.js", "../../Zephyr-TV-Extensions/youyubez/main.js")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue(f != null)
        f!!.readText()
    }
    private val cookies: String by lazy {
        val f = File("/Users/antonello/Downloads/www.youtube.com_cookies (13).txt")
        assumeTrue("cookie file 13", f.isFile)
        f.readText()
    }

    private fun run(call: String, timeoutMs: Long = 90_000): JSONObject {
        Js.console = { line -> println(line) }
        val out = Js.run(script, call, timeoutMs = timeoutMs) ?: error("timeout")
        return JSONObject(out)
    }

    @Test fun cookieFileFillsDiscoverAndDirectStreams() {
        val q = JSONObject.quote(cookies)
        val discover = run("""module.exports.catalog({id:"discover", extra:{}, settings:{cookies:$q}})""")
            .getJSONArray("metas")
        println("DISCOVER ${discover.length()}")
        assertTrue("discover must list videos", discover.length() > 0)
        val raw = run("""module.exports.stream({id:"ytz:dQw4w9WgXcQ", settings:{play:"both", cookies:$q}})""")
        val parsed = Stremio.streams(raw, vod = true, external = true)
        val direct = parsed.filter { it.app == null && it.url.contains("googlevideo") }
        println("DIRECT ${direct.map { "${it.title} audio=${it.audio != null}" }}")
        assertTrue("several heights", direct.size > 1)
        val auto = direct.first { it.title.contains("Auto") }
        assertTrue("Auto is the highest", streamQuality(auto.title) == direct.maxOf { streamQuality(it.title) })
        assertTrue("every height has paired audio", direct.all { !it.audio.isNullOrEmpty() })
        val probe = Stremio.probe(auto.url, auto.headers)
        assertTrue("direct url answers ${probe.why}", probe.ok)
        val a = Stremio.probe(auto.audio!!, auto.headers)
        assertTrue("audio url answers ${a.why}", a.ok)
    }
}
