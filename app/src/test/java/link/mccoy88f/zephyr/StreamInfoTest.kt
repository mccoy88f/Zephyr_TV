package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamInfoTest {
    @Test fun theSourcesNameGoesFromTheFirstLine() {
        assertEquals("4k · Film.2008.2160p.mkv", streamInfo("Torrentio 4k · Film.2008.2160p.mkv", "Torrentio"))
        assertEquals("1080p · Film.mkv", streamInfo("torrentio 1080p · Film.mkv", "Torrentio"))
        assertEquals("[RD+] 4k · Film.mkv", streamInfo("[RD+] Torrentio 4k · Film.mkv", "Torrentio"))
        assertEquals("720p", streamInfo("MediaFusion 720p", "MediaFusion ElfHosted"))
    }

    @Test fun anythingElseStaysAsItIs() {
        assertEquals("1080p · Film.mkv", streamInfo("1080p · Film.mkv", "Torrentio"))
        assertEquals("Torrentio", streamInfo("Torrentio", "Torrentio")) // nothing would be left
        assertEquals("Torrentiopia 4k", streamInfo("Torrentiopia 4k", "Torrentio")) // another word
        assertEquals("Film 4k", streamInfo("Film 4k", null))
    }
}
