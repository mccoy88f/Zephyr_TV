package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCasesTest {
    // Disc images and archives no player opens: left out, by the add-on's file name or the address's last part.
    @Test fun leavesOutDiscImagesAndArchives() {
        assertTrue(Stremio.unplayable(null, "https://cdn.example/dl/1/Film.2019.BD50.iso"))
        assertTrue(Stremio.unplayable("Film.part1.rar", "https://cdn.example/dl/1"))
        assertTrue(Stremio.unplayable(null, "https://cdn.example/dl/Film%20(2019).7z?token=x"))
        assertTrue(Stremio.unplayable(null, "https://cdn.example/dl/Film.r00"))
        assertFalse(Stremio.unplayable("La.Vita.e.bella.1997.BDRemux-ylnian", "https://cdn.example/dld/2a37"))
        assertFalse(Stremio.unplayable(null, "https://cdn.example/dl/Film.ISO.Edition.mkv"))
        assertFalse(Stremio.unplayable(null, "https://cdn.example/live/index.m3u8"))
    }

    @Test fun streamsLeaveOutAnIsoAndKeepTheirSubtitles() {
        val answer = JSONObject("""{"streams":[
            {"name":"4K","title":"Film BD50","url":"https://cdn.example/a","behaviorHints":{"filename":"Film.iso"}},
            {"name":"1080p","title":"Film","url":"https://cdn.example/b.mkv",
             "subtitles":[{"id":"1","url":"https://subs.example/1.srt","lang":"ita"},{"id":"2","url":"ftp://x/2.srt","lang":"eng"}]}]}""")
        val list = Stremio.streams(answer, vod = true)
        assertEquals(1, list.size)
        assertEquals("https://cdn.example/b.mkv", list[0].url)
        assertEquals(listOf("https://subs.example/1.srt"), list[0].subtitles.map { it.url })
        assertEquals("ita", list[0].subtitles[0].lang)
    }

    @Test fun subtitlesAddOnAnswer() {
        val subs = Stremio.subtitles(JSONObject("""{"subtitles":[
            {"id":"a","url":"https://os.example/a","lang":"ITA"},{"id":"b","url":"https://os.example/a","lang":"ita"},
            {"id":"c","url":"https://os.example/c","lang":"eng"},{"id":"d","lang":"eng"}]}"""), "OpenSubtitles")
        assertEquals(listOf("https://os.example/a", "https://os.example/c"), subs.map { it.url })
        assertEquals("ita", subs[0].lang)
        assertEquals("OpenSubtitles", subs[0].source)
    }

    // The stream cache keeps a stream's subtitles.
    @Test fun streamJsonKeepsSubtitles() {
        val s = Stremio.Stream("https://cdn.example/b.mkv", "1080p", emptyMap(),
            subtitles = listOf(Stremio.Subtitle("https://subs.example/1.srt", "ita", "Add-on"), Stremio.Subtitle("https://subs.example/2.srt", null)))
        val back = PlayerActivity.streamOf(JSONObject(PlayerActivity.streamJson(s).toString()))
        assertEquals(listOf("https://subs.example/1.srt", "https://subs.example/2.srt"), back.subtitles.map { it.url })
        assertEquals("ita", back.subtitles[0].lang)
        assertEquals("Add-on", back.subtitles[0].source)
        assertNull(back.subtitles[1].lang)
        assertTrue(PlayerActivity.streamOf(JSONObject("""{"url":"https://x"}""")).subtitles.isEmpty()) // cached before subtitles
    }

    @Test fun dolbyVisionProfile() {
        assertEquals(7, DolbyVision.profile("dvhe.07.06"))
        assertEquals(8, DolbyVision.profile("dvh1.08.06"))
        assertEquals(5, DolbyVision.profile("DVHE.05.09"))
        assertNull(DolbyVision.profile("hvc1.2.4.L153"))
        assertNull(DolbyVision.profile(null))
    }

    // Dolby Vision alone (profile 5, most likely) by the stream's name; with HDR10 too it has a base layer.
    @Test fun dolbyVisionAlone() {
        assertTrue(DolbyVision.only("2160p · Film.2023.2160p.WEB-DL.DV.H265-GRP"))
        assertTrue(DolbyVision.only("4K DoVi · Film"))
        assertTrue(DolbyVision.only("Film 2160p Dolby Vision"))
        assertFalse(DolbyVision.only("Film.2023.2160p.WEB-DL.DV.HDR10.H265"))
        assertFalse(DolbyVision.only("Film 2160p HDR DV"))
        assertFalse(DolbyVision.only("Film.2023.DVDRip.XviD"))
        assertFalse(DolbyVision.only("Film 1080p"))
    }

    @Test fun frameRatePicksTheMultiple() {
        val modes = listOf(FrameRate.Mode(1, 3840, 2160, 60f), FrameRate.Mode(2, 3840, 2160, 23.976f),
            FrameRate.Mode(3, 3840, 2160, 24f), FrameRate.Mode(4, 3840, 2160, 50f), FrameRate.Mode(5, 1920, 1080, 23.976f),
            FrameRate.Mode(6, 3840, 2160, 59.94f))
        assertEquals(2, FrameRate.pick(3840, 2160, modes, 23.976f)?.id)
        assertEquals(3, FrameRate.pick(3840, 2160, modes, 24f)?.id)
        assertEquals(4, FrameRate.pick(3840, 2160, modes, 25f)?.id)
        assertEquals(6, FrameRate.pick(3840, 2160, modes, 29.97f)?.id)
        assertEquals(1, FrameRate.pick(3840, 2160, modes, 30f)?.id)
        assertNull(FrameRate.pick(3840, 2160, listOf(FrameRate.Mode(1, 3840, 2160, 60f)), 23.976f)) // 60 Hz: none
        assertNull(FrameRate.pick(3840, 2160, modes, -1f)) // no frame rate known
    }

    // A 5.1 packet, 16 bits at 48 kHz: the disc's L R C LS RS LFE comes out as Android's L R C LFE LS RS, little-endian.
    @Test fun lpcmFiveOne() {
        val header = byteArrayOf(0, 12, (9 shl 4 or 1).toByte(), (1 shl 6).toByte())
        val samples = ByteArray(12) { (it / 2 + 1).toByte() }.also { for (i in 1 until 12 step 2) it[i] = 0x10 } // channel n: 0x0n10
        val h = assertNotNullAnd(Lpcm.header(header + samples, 16))
        assertEquals(6, h.layout.channels)
        assertEquals(48_000, h.rate)
        val out = Lpcm.toPcm16(header + samples, Lpcm.HEADER, 12, h)
        assertArrayEquals(byteArrayOf(0x10, 1, 0x10, 2, 0x10, 3, 0x10, 6, 0x10, 4, 0x10, 5), out)
    }

    // 24 bits, mono (with its padding channel): the two highest bytes of the heard channel.
    @Test fun lpcmMono24() {
        val header = byteArrayOf(0, 6, (1 shl 4 or 4).toByte(), (3 shl 6).toByte())
        val h = assertNotNullAnd(Lpcm.header(header, 4))
        assertEquals(1, h.layout.channels)
        assertEquals(96_000, h.rate)
        val frame = byteArrayOf(0x12, 0x34, 0x56, 0x7F, 0x7F, 0x7F) // sample 0x123456, then the padding
        assertArrayEquals(byteArrayOf(0x34, 0x12), Lpcm.toPcm16(frame, 0, 6, h))
        assertNull(Lpcm.header(byteArrayOf(0, 0, (2 shl 4 or 1).toByte(), (1 shl 6).toByte()), 4)) // assignment 2: reserved
    }

    // An AC-3 frame of 448 kb/s at 48 kHz (1792 bytes): whole frames are AC-3; TrueHD, or part of a frame, isn't.
    @Test fun ac3InsideTrueHd() {
        val frame = ByteArray(1792).also { it[0] = 0x0B; it[1] = 0x77; it[4] = 0x1E; it[5] = (8 shl 3).toByte() }
        assertTrue(Ac3Core.isAc3(frame, 1792))
        assertTrue(Ac3Core.isAc3(frame + frame, 3584))
        assertFalse(Ac3Core.isAc3(frame, 1000))
        assertFalse(Ac3Core.isAc3(byteArrayOf(0xF8.toByte(), 0x72, 0x6F, 0xBA.toByte(), 0, 0), 6)) // TrueHD's sync
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
