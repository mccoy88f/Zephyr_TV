package link.mccoy88f.zephyr

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTest {
    private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"assets":[${assets.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://github.com/o/r/releases/download/$tag/$it"}"""
        }}]}"""

    // The newest release with ZephyrMPV.apk: a newer one without it (Zephyr TV alone) and drafts are passed over.
    @Test fun findsTheNewestReleaseWithIt() {
        val list = JSONArray("[" + listOf(
            release("v4.0.0-beta.14", "ZephyrMPV.apk", draft = true),
            release("v4.0.0-beta.13", "ZephyrTV.apk"),
            release("v4.0.0-beta.12", "ZephyrTV.apk", "ZephyrMPV.apk"),
            release("v4.0.0-beta.11", "ZephyrTV.apk", "ZephyrMPV.apk"),
        ).joinToString(",") + "]")
        assertEquals("https://github.com/o/r/releases/download/v4.0.0-beta.12/ZephyrMPV.apk", Mpv.apkIn(list))
    }

    @Test fun noReleaseWithIt() {
        assertNull(Mpv.apkIn(JSONArray("[" + release("v1", "ZephyrTV.apk") + "]")))
        assertNull(Mpv.apkIn(JSONArray("[]")))
    }

    // The web's, RTSP and the like; not another app's (YouTube), nor a playlist in its address, nor ClearKey's DASH.
    @Test fun takesWhatMpvPlays() {
        assertTrue(Mpv.takes(Stremio.Stream("https://cdn.example/dld/1", "1080p", emptyMap())))
        assertTrue(Mpv.takes(Stremio.Stream("rtsp://cam.local/live", "cam", emptyMap())))
        assertFalse(Mpv.takes(Stremio.Stream("https://www.youtube.com/watch?v=x", "yt", emptyMap(), app = "")))
        assertFalse(Mpv.takes(Stremio.Stream("data:application/vnd.apple.mpegurl,#EXTM3U", "hls", emptyMap())))
        assertFalse(Mpv.takes(Stremio.Stream("https://cdn.example/a.mpd", "dash", emptyMap(),
            clearkey = mapOf("0".repeat(32) to "1".repeat(32)))))
    }

    @Test fun headersOneEach() {
        assertArrayEquals(arrayOf("User-Agent: VLC/3.0", "Referer: https://a.example/, b"),
            Mpv.headerLines(linkedMapOf("User-Agent" to "VLC/3.0", "Referer" to "https://a.example/, b")))
    }
}
