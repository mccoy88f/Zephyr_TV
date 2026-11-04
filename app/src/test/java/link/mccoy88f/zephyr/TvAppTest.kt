package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvAppTest {
    private val preference = listOf("org.droidtv.playtv", "org.droidtv.channels", "com.sony.dtv.tvx", "com.google.android.tv")

    @Test fun theTvsOwnAppComesFirst() {
        // A Philips with Live Channels too: Philips' own, whatever order Android lists them in.
        assertEquals("org.droidtv.playtv", autoTvAppOf(listOf("com.google.android.tv", "org.droidtv.channels", "org.droidtv.playtv"), preference))
        assertEquals("org.droidtv.channels", autoTvAppOf(listOf("com.google.android.tv", "org.droidtv.channels"), preference))
    }

    @Test fun anotherTvAppWhenTheTvHasNoneKnown() {
        assertEquals("com.example.tv", autoTvAppOf(listOf("com.example.tv", "com.example.other"), preference))
        assertNull(autoTvAppOf(emptyList(), preference))
    }
}
