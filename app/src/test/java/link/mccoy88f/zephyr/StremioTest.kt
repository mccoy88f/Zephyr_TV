package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StremioTest {
    @Test fun findsTheManifestAddress() {
        assertEquals("https://addon.example/c/abc/manifest.json", Stremio.manifestUrl(" https://addon.example/c/abc/manifest.json "))
        assertEquals("https://addon.example/c/abc/manifest.json", Stremio.manifestUrl("stremio://addon.example/c/abc/manifest.json"))
        assertEquals("https://addon.example/c/abc/manifest.json", Stremio.manifestUrl("https://addon.example/c/abc/"))
        val longUrl = "https://addon.example/" + "a".repeat(16384) + "/manifest.json"
        assertEquals(longUrl, Stremio.manifestUrl("  \n" + longUrl + "\n  "))
        assertNull(Stremio.manifestUrl("addon.example/c/abc"))
        assertNull(Stremio.manifestUrl("https://"))
    }

    // As a TV add-on answers: one TV catalog, its categories, a ⚙️ (refresh items, not channels) among them.
    @Test fun readsTheTvCatalogsOfAManifest() {
        val m = JSONObject("""{"id":"org.example.tv.abc","name":"TV Add-on - SAT","logo":"https://x/tv.png",
            "types":["tv"],"catalogs":[
              {"type":"tv","id":"tv_abc","name":"TV Add-on - SAT","extra":[
                {"name":"genre","isRequired":false,"options":["Mediaset","Sport","Rai","⚙️"]},
                {"name":"search","isRequired":false},{"name":"skip","isRequired":false}]},
              {"type":"tv","id":"search_only","name":"Search","extra":[{"name":"search","isRequired":true}]},
              {"type":"movie","id":"films","name":"Films"}]}""")
        val addon = Stremio.addon("https://addon.example/c/abc/manifest.json", m)
        assertEquals("TV Add-on - SAT", addon.name)
        assertEquals("https://addon.example/c/abc", addon.base)
        assertEquals(listOf("tv_abc"), addon.catalogs.map { it.id })
        assertEquals(listOf("Mediaset", "Sport", "Rai"), addon.catalogs[0].genres)
    }

    @Test fun readsTheChannelsOfACatalogPage() {
        val page = JSONObject("""{"metas":[
            {"id":"tv|rai1.it","type":"tv","name":"RAI 1","logo":"https://raw.githubusercontent.com/tv-logo/rai-1-it.png","releaseInfo":"In onda: RaiNews24","genre":["Rai","ITALIA"]},
            {"id":"tv|eurosport1.it","type":"tv","name":"EUROSPORT 1","poster":"https://placehold.co/500x500/590b8a/ffffff/png?text=EU","releaseInfo":"LIVE"},
            {"id":"tv|111tv","type":"tv","name":"111 TV","logo":"https://encrypted-tbn0.gstatic.com/images?q"},
            {"id":"","name":"no id"}]}""")
        val items = Stremio.items(page)
        assertEquals(listOf("RAI 1", "EUROSPORT 1", "111 TV"), items.map { it.name })
        assertEquals("In onda: RaiNews24", items[0].now)
        assertEquals(listOf("Rai", "ITALIA"), items[0].genres) // the category rows come from these, without asking again
        assertNull(items[1].now) // "LIVE": no guide for it
        assertEquals("https://raw.githubusercontent.com/tv-logo/rai-1-it.png", items[0].logo)
        assertEquals("https://placehold.co/500x500/590b8a/ffffff/png?text=EU", items[1].logo) // the add-on's placeholder is its choice
        assertNull(items[2].logo) // cut short
    }

    @Test fun readsTheStreamsWithTheirHeaders() {
        val answer = JSONObject("""{"streams":[
            {"name":"RAI 1","title":"🌐 RAI 1 (1)\n[EasyProxy HLS]","url":"https://addon.example/s/token1","behaviorHints":{"notWebReady":false}},
            {"name":"RAI 1","title":"RAI 1 (2)","url":"https://cdn.example/live.m3u8","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"VLC","Referer":"https://r.example/"}}}},
            {"name":"torrent","infoHash":"abc"}]}""")
        val streams = Stremio.streams(answer)
        assertEquals(2, streams.size)
        assertEquals("🌐 RAI 1 (1)", streams[0].title)
        assertEquals(emptyMap<String, String>(), streams[0].headers)
        assertEquals(mapOf("User-Agent" to "VLC", "Referer" to "https://r.example/"), streams[1].headers)
    }

    @Test fun tellsTheStreamFormat() {
        val none = ByteArray(0)
        assertEquals("hls", Stremio.format("application/vnd.apple.mpegurl", "https://x/s/token", none))
        assertEquals("hls", Stremio.format("audio/x-mpegURL; charset=utf-8", "https://x/s/token", none))
        assertEquals("dash", Stremio.format("application/dash+xml", "https://x/s/token", none))
        assertEquals("hls", Stremio.format("application/octet-stream", "https://x/live/index.m3u8?token=1", none))
        assertEquals("dash", Stremio.format(null, "https://x/manifest.mpd", none))
        assertEquals("hls", Stremio.format("text/plain", "https://x/s/token", "\uFEFF#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals("dash", Stremio.format("text/xml", "https://x/s/token", "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
        val ts = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
        assertEquals("ts", Stremio.format("application/octet-stream", "https://x/s/token", ts))
        val m2ts = ByteArray(2048).also { b -> for (i in 4 until b.size step 192) b[i] = 0x47 }
        assertEquals("m2ts", Stremio.format("application/octet-stream", "https://x/s/token", m2ts))
        assertEquals("m2ts", Stremio.format("video/mp2t", "https://x/film.ts", m2ts))
        assertNull(Stremio.format("video/mp4", "https://x/s/token", "....ftypisom".toByteArray()))
    }

    // As a TV add-on answers for a channel's page: what's on in short, then on and next; without a guide, a line with its id.
    @Test fun readsWhatTheAddOnSaysIsOn() {
        val meta = Stremio.meta(JSONObject("""{"meta":{"id":"tv|rai1.it","releaseInfo":"RaiNews24 (05:00)",
            "description":"📺 IN ONDA ORA:\nRaiNews24\n\nNOTIZIARIO.\n\n⏰ 05:00 - 00:30\n\n• 11:15 - A Sua immagine"}}"""))
        assertEquals("RaiNews24 (05:00)", meta.now)
        assertEquals("📺 IN ONDA ORA:\nRaiNews24\nNOTIZIARIO.\n⏰ 05:00 - 00:30\n• 11:15 - A Sua immagine", meta.description)
        val none = Stremio.meta(JSONObject("""{"meta":{"id":"tv|x","releaseInfo":"LIVE","description":"Channel: X - ID: x"}}"""))
        assertNull(none.now)
        assertNull(none.description)
    }

    @Test fun findsHowFarIntoTheProgramItIs() {
        fun at(h: Int, m: Int) = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m) }
        assertEquals(50, Stremio.progress("⏰ 20:00 - 22:00", at(21, 0)))
        assertEquals(50, Stremio.progress("⏰ 23:00 - 01:00", at(0, 0))) // past midnight
        assertEquals(25, Stremio.progress("⏰ 23:00 - 01:00", at(23, 30)))
        assertNull(Stremio.progress("⏰ 20:00 - 22:00", at(23, 0)))
        assertNull(Stremio.progress("no times here", at(21, 0)))
    }

    @Test fun encodesPathsAsStremioClientsDo() {
        assertEquals("tv%7Crai1.it", Stremio.enc("tv|rai1.it"))
        assertEquals("Film%20-%20Serie%20TV", Stremio.enc("Film - Serie TV"))
    }

    @Test fun anotherAppsStreamIsNotCheckedOnTheWeb() {
        // A YouTube live (an extension's ytId) is opened by its app: nothing to ask, and never a failure that closes the player.
        org.junit.Assert.assertTrue(Stremio.probe("vnd.youtube:abcdefghijk", emptyMap()).ok)
        org.junit.Assert.assertTrue(Stremio.probe("spotify:track:1", emptyMap()).ok)
    }
}
