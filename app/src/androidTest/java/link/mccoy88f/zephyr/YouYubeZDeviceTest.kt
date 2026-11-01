package link.mccoy88f.zephyr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On the TV, through ADB: YouYubeZ catalogs and direct streams, same QuickJS+Http as the installed app. */
@RunWith(AndroidJUnit4::class)
class YouYubeZDeviceTest {
    @Test fun discoverAndDirectStreamsOnTheTv() {
        val scriptFile = File("/data/local/tmp/youyubez-main.js")
        assumeTrue("adb push youyubez/main.js /data/local/tmp/youyubez-main.js", scriptFile.isFile)
        val script = scriptFile.readText()
        Js.console = { Log.i("YouYubeZTest", it) }

        val catalog = JSONObject(Js.run(script, """module.exports.catalog({id:"discover", extra:{}, settings:{}})""", timeoutMs = 90_000)!!)
        val metas = catalog.getJSONArray("metas")
        Log.i("YouYubeZTest", "DISCOVER ${metas.length()}")
        val names = (0 until metas.length()).map { metas.getJSONObject(it).optString("name") }
        names.take(10).forEach { Log.i("YouYubeZTest", "ITEM $it") }
        assertTrue("discover must list videos", metas.length() > 0)
        assertTrue("discover must not list ads", names.none { it.contains(Regex("(?i)\\b(ad|ads|pubblicit[aà]|sponsored|promoted)\\b")) })

        val raw = JSONObject(Js.run(script, """module.exports.stream({id:"ytz:dQw4w9WgXcQ", settings:{play:"zephyr"}})""", timeoutMs = 90_000)!!)
        val direct = Stremio.streams(raw, vod = true, external = true).filter { it.app == null }
        Log.i("YouYubeZTest", "STREAMS ${direct.map { "${it.title} audio=${it.audio != null}" }}")
        assertTrue("several heights", direct.size > 1)
        val auto = direct.first { it.title.contains("Auto") }
        val max = direct.maxOf { streamQuality(it.title) }
        Log.i("YouYubeZTest", "AUTO ${auto.title} q=${streamQuality(auto.title)} max=$max")
        assertTrue("Auto is the highest", streamQuality(auto.title) == max && max >= 720)
        assertTrue("every height has paired audio", direct.all { !it.audio.isNullOrEmpty() })
        val ok = direct.filter { s ->
            val p = Stremio.probe(s.url, s.headers)
            val a = s.audio?.let { Stremio.probe(it, s.headers) }
            Log.i("YouYubeZTest", "PROBE ${s.title} ok=${p.ok} audio=${a?.ok}")
            p.ok && a?.ok == true
        }
        assertTrue("a paired stream must answer on the TV", ok.isNotEmpty())
    }
}
