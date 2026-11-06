package link.mccoy88f.zephyr.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeTest {
    @Test fun readsTheRangesPlayersAsk() {
        assertEquals(0L to 999L, StreamServer.range("bytes=0-", 1000))
        assertEquals(100L to 199L, StreamServer.range("bytes=100-199", 1000))
        assertEquals(900L to 999L, StreamServer.range("bytes=-100", 1000))
        assertEquals(500L to 999L, StreamServer.range("bytes=500-5000", 1000))
    }

    @Test fun aRangeOutsideTheFileCantBeHad() {
        assertNull(StreamServer.range("bytes=1000-", 1000))
        assertNull(StreamServer.range("bytes=300-200", 1000))
        assertNull(StreamServer.range("items=0-1", 1000))
        assertNull(StreamServer.range(null, 1000))
    }
}
