package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentsTest {
    @Test fun aStreamsInfoHashBecomesAMagnetLinkWithItsTrackersFileAndName() {
        val s = JSONObject("""{"name":"Example","infoHash":"0123456789ABCDEF0123456789ABCDEF01234567","fileIdx":3,
            "sources":["tracker:udp://tracker.example:1337/announce","dht:0123456789abcdef0123456789abcdef01234567",
                       "tracker:udp://tracker.example:1337/announce","tracker:https://other.example/announce"],
            "behaviorHints":{"filename":"Big Buck Bunny (2008).mkv"}}""")
        assertEquals("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
            "&dn=Big%20Buck%20Bunny%20%282008%29.mkv" +
            "&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce&tr=https%3A%2F%2Fother.example%2Fannounce&so=3", Torrents.magnet(s))
    }

    @Test fun aMagnetLinkGivenAsTheAddressIsKeptAsItIs() {
        val link = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&tr=udp%3A%2F%2Ft.example%3A80"
        assertEquals(link, Torrents.magnet(JSONObject().put("url", link)))
    }

    @Test fun anInfoHashThatIsntOneIsLeftOut() {
        assertNull(Torrents.magnet(JSONObject("""{"infoHash":"abc"}""")))
        assertNull(Torrents.magnet(JSONObject("""{"url":"https://cdn.example/a.mkv"}""")))
        assertNull(Torrents.magnet(JSONObject("""{"infoHash":null}""")))
    }

    @Test fun withoutTheEngineTorrentsArentOffered() {
        val list = listOf(Stremio.Stream("https://cdn.example/a.mkv", "a", emptyMap()),
            Stremio.Stream("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "b", emptyMap()))
        // The engine isn't installed off a TV (Torrents.available stays false).
        assertEquals(listOf("a"), Torrents.offered(list).map { it.title })
    }

    @Test fun theSeedersItsTextSaysGoWithIt() {
        val s = JSONObject("{\"name\":\"Example\\n1080p\",\"title\":\"Film.2008.1080p.mkv\\n\uD83D\uDC64 42 \uD83D\uDCBE 1.2 GB\",\"infoHash\":\"0123456789abcdef0123456789abcdef01234567\"}")
        assertEquals(42, Torrents.seedersOf(Torrents.magnet(s)!!))
        assertEquals(7, Torrents.seeders("Seeders: 7"))
        assertNull(Torrents.seeders("Film.2008.1080p.mkv"))
        assertNull(Torrents.seedersOf("https://cdn.example/a.mkv?zs=3"))
    }

    @Test fun speedsAsPeopleReadThem() {
        assertEquals("300 KB/s", Torrents.speedName(300L * 1024))
        assertEquals(String.format(java.util.Locale.getDefault(), "%.1f MB/s", 2.5), Torrents.speedName(5L * 512 * 1024))
    }
}
