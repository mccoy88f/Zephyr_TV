package link.mccoy88f.zephyr.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetTest {
    @Test fun readsTheHashTrackersNameAndFile() {
        val m = Magnet.parse("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Big%20Buck%20Bunny.mkv" +
            "&tr=udp%3A%2F%2Ftracker.example%3A1337&tr=https%3A%2F%2Fother.example%2Fannounce&so=3")!!
        assertEquals("0123456789abcdef0123456789abcdef01234567", m.hash)
        assertEquals(listOf("udp://tracker.example:1337", "https://other.example/announce"), m.trackers)
        assertEquals("Big Buck Bunny.mkv", m.name)
        assertEquals(3, m.file)
    }

    @Test fun aBase32HashIsReadAsHex() {
        // 20 bytes of 0x00..0x13 in base32
        assertEquals("000102030405060708090a0b0c0d0e0f10111213", Magnet.hex("AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"))
    }

    @Test fun aSelectOnlyRangeTakesItsFirstFile() {
        assertEquals(2, Magnet.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&so=2-4,7")!!.file)
        assertNull(Magnet.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")!!.file)
    }

    @Test fun whatIsntAMagnetLinkIsntRead() {
        assertNull(Magnet.parse("https://example.com/a.torrent"))
        assertNull(Magnet.parse("magnet:?dn=nothing"))
    }
}
