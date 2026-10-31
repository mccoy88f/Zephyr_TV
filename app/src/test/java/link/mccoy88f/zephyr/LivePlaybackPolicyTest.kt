package link.mccoy88f.zephyr

import org.junit.Assert.*
import org.junit.Test

class LivePlaybackPolicyTest {
    @Test fun italianLanguageWinsOverAkFallback() {
        assertTrue(LivePlaybackPolicy.audioRank("it-IT", "Audio 1") > LivePlaybackPolicy.audioRank("en", "audio ak"))
        assertTrue(LivePlaybackPolicy.audioRank("ita", null) > 0)
        assertTrue(LivePlaybackPolicy.audioRank(null, "Italiano") > LivePlaybackPolicy.audioRank("ak", null))
    }
    @Test fun akOnlyMatchesTheEndAndNeverReplacesRealItalian() {
        assertTrue(LivePlaybackPolicy.audioRank("ak", null) > 0)
        assertTrue(LivePlaybackPolicy.audioRank("qak", null) > 0)
        assertTrue(LivePlaybackPolicy.audioRank(null, "Traccia AK ") > 0)
        assertEquals(0, LivePlaybackPolicy.audioRank("en", "Akustik stereo"))
        assertEquals(0, LivePlaybackPolicy.audioRank("de", "Deutsch"))
    }
    @Test fun fullHdStreamsComeFirstWithoutDroppingFallbacks() {
        val source = listOf(720, 2160, 1080, -1, 480)
        assertEquals(listOf(1080,720,2160,-1,480), source.sortedBy { LivePlaybackPolicy.streamRank(it) })
    }
    @Test fun fullHdIsPreferredButOtherTracksRemainFallbacks() {
        assertTrue(LivePlaybackPolicy.isFullHd(1920, 1080))
        assertFalse(LivePlaybackPolicy.isFullHd(1280, 720))
        assertFalse(LivePlaybackPolicy.isFullHd(3840, 2160))
        assertFalse(LivePlaybackPolicy.isFullHd(-1, -1))
    }
    @Test fun startupRefreshIsExplicitlyOptInAndOnlyForEnabledExtensions() {
        assertTrue(LivePlaybackPolicy.refreshAtStartup(true, true))
        assertFalse(LivePlaybackPolicy.refreshAtStartup(true, false))
        assertFalse(LivePlaybackPolicy.refreshAtStartup(false, true))
    }
}
